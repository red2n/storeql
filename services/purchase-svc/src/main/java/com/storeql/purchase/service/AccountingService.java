package com.storeql.purchase.service;

import com.storeql.ids.Ids;
import com.storeql.purchase.client.accounting.AccountingPackage;
import com.storeql.purchase.client.accounting.AccountingPackages;
import com.storeql.purchase.domain.Accounting;
import com.storeql.purchase.domain.Accounting.Attempt;
import com.storeql.purchase.domain.Accounting.Connection;
import com.storeql.purchase.domain.Accounting.Counts;
import com.storeql.purchase.domain.Accounting.Credentials;
import com.storeql.purchase.domain.Accounting.Mapping;
import com.storeql.purchase.domain.Accounting.Provider;
import com.storeql.purchase.domain.Accounting.Run;
import com.storeql.purchase.domain.Accounting.Sync;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.dto.AccountingDtos;
import com.storeql.purchase.repo.AccountingRepository;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Accounting connectors (17.9): the package a business keeps its books in, its account mapping, and
 * every journal's push to it — once, with what came back on a log, tried again by the clock or by
 * hand when the package said no.
 */
@ApplicationScoped
public class AccountingService {

  private static final Logger LOG = System.getLogger(AccountingService.class.getName());
  private static final Duration LEASE = Duration.ofMinutes(2);
  private static final long REFRESH_LEEWAY_SECONDS = 60;
  private static final int DESCRIPTION_MAX = 500;

  @Inject AccountingRepository repo;
  @Inject PurchaseRepository ledger;
  @Inject AccountingPackages packages;
  @Inject AccountingSecrets secrets;

  @Inject
  @ConfigProperty(name = "storeql.accounting.batch", defaultValue = "50")
  int batch;

  // ── the connection ──────────────────────────────────────────────────────────

  public List<Provider> providers() {
    return Accounting.CATALOGUE;
  }

  /** The connection with what it has done: what is waiting, delivered, failed, uncertain. */
  public record View(Connection connection, Counts counts, boolean hasRefreshToken) {}

  public View view(UUID tenantId) {
    Connection c = require(tenantId);
    return new View(c, repo.counts(tenantId, c.id()), hasRefreshToken(c));
  }

  private boolean hasRefreshToken(Connection c) {
    if (c.credentialsSealed() == null) return false;
    try {
      return secrets.openCredentials(c.credentialsSealed()).canRefresh();
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * Connects the business's package, replacing whatever it had — its mapping and its log with it.
   */
  public View connect(UUID tenantId, UUID by, AccountingDtos.ConnectRequest req) {
    String code =
        req.provider() == null ? "" : req.provider().trim().toUpperCase(java.util.Locale.ROOT);
    Provider provider =
        Accounting.provider(code)
            .orElseThrow(
                () ->
                    ApiException.badRequest(
                        "ACCOUNTING_PROVIDER_UNKNOWN",
                        "provider is one of "
                            + Accounting.CATALOGUE.stream().map(Provider::code).toList()));
    Map<String, String> settings = new LinkedHashMap<>();
    if (req.settings() != null) {
      for (var e : req.settings().entrySet()) {
        if (e.getValue() != null && !e.getValue().isBlank())
          settings.put(e.getKey().trim(), e.getValue().trim());
      }
    }
    List<String> missing =
        provider.required().stream().filter(k -> !settings.containsKey(k)).toList();
    if (!missing.isEmpty()) {
      throw ApiException.badRequest(
          "ACCOUNTING_SETTINGS_INVALID", provider.name() + " needs " + missing + " in settings");
    }
    for (String key : settings.keySet()) {
      if (!provider.required().contains(key) && !provider.optional().contains(key)) {
        throw ApiException.badRequest(
            "ACCOUNTING_SETTINGS_INVALID", provider.name() + " has no setting '" + key + "'");
      }
    }
    LocalDate syncFrom;
    try {
      syncFrom = LocalDate.parse(req.syncFrom() == null ? "" : req.syncFrom().trim());
    } catch (DateTimeParseException e) {
      throw new ApiException(
          400, "ACCOUNTING_SYNC_FROM_INVALID", "syncFrom is a day, yyyy-MM-dd", List.of(), e);
    }
    String sealed = null;
    if (provider.needsCredentials()) {
      AccountingDtos.CredentialsRequest cr = req.credentials();
      if (cr == null || cr.accessToken() == null || cr.accessToken().isBlank()) {
        throw ApiException.badRequest(
            "ACCOUNTING_CREDENTIALS_MISSING",
            provider.name() + " needs an access token: " + provider.tokens());
      }
      if (!secrets.isConfigured()) {
        throw new ApiException(
            503,
            "ACCOUNTING_NOT_CONFIGURED",
            "This deployment has no key to keep accounting tokens under (storeql.accounting.secrets-key)",
            List.of());
      }
      Instant expiresAt = null;
      if (cr.expiresAt() != null && !cr.expiresAt().isBlank()) {
        try {
          expiresAt = Instant.parse(cr.expiresAt().trim());
        } catch (DateTimeParseException e) {
          throw new ApiException(
              400,
              "ACCOUNTING_CREDENTIALS_INVALID",
              "expiresAt is an ISO-8601 instant",
              List.of(),
              e);
        }
      }
      sealed =
          secrets.sealCredentials(
              new Credentials(
                  cr.accessToken().trim(),
                  blankToNull(cr.refreshToken()),
                  blankToNull(cr.clientId()),
                  blankToNull(cr.clientSecret()),
                  expiresAt));
    }
    Instant now = Instant.now();
    Connection c =
        repo.replace(
            new Connection(
                Ids.newId(),
                tenantId,
                provider.code(),
                Accounting.ACTIVE,
                settings,
                sealed,
                syncFrom,
                by,
                now,
                now,
                null,
                null,
                null));
    LOG.log(Level.INFO, "Tenant {0} connected {1} for accounting", tenantId, provider.code());
    return new View(c, new Counts(0, 0, 0, 0, 0), sealed != null && hasRefreshToken(c));
  }

  public void disconnect(UUID tenantId) {
    if (!repo.delete(tenantId)) {
      throw notConnected();
    }
  }

  public View setEnabled(UUID tenantId, boolean enabled, UUID by) {
    require(tenantId);
    repo.setStatus(
        tenantId,
        enabled ? Accounting.ACTIVE : Accounting.DISABLED,
        enabled ? null : "switched off by " + by,
        Instant.now());
    return view(tenantId);
  }

  // ── the chart and the mapping ───────────────────────────────────────────────

  public List<AccountingPackage.ExternalAccount> accounts(UUID tenantId) {
    Connection c = require(tenantId);
    AccountingPackage pkg = packageOf(c);
    Credentials creds = freshCredentials(c, pkg, Instant.now());
    try {
      return pkg.accounts(c, creds);
    } catch (AccountingPackage.Refused e) {
      throw new ApiException(502, "ACCOUNTING_PROVIDER_REFUSED", e.getMessage(), List.of(), e);
    } catch (AccountingPackage.Unreachable e) {
      throw new ApiException(
          503,
          "ACCOUNTING_PROVIDER_UNREACHABLE",
          "The package did not answer: " + e.getMessage(),
          List.of(),
          e);
    }
  }

  public List<Mapping> mappings(UUID tenantId) {
    return repo.mappings(require(tenantId).id());
  }

  public List<Mapping> replaceMappings(UUID tenantId, List<AccountingDtos.MappingRequest> wanted) {
    Connection c = require(tenantId);
    Map<String, Mapping> byCode = new LinkedHashMap<>();
    for (AccountingDtos.MappingRequest m :
        wanted == null ? List.<AccountingDtos.MappingRequest>of() : wanted) {
      String code = m.nominalCode() == null ? "" : m.nominalCode().trim();
      String external = m.externalAccount() == null ? "" : m.externalAccount().trim();
      if (!Accounting.NOMINAL_CODE.matcher(code).matches()
          || external.isEmpty()
          || external.length() > 200) {
        throw ApiException.badRequest(
            "ACCOUNTING_MAPPING_INVALID",
            "each mapping is a nominal code of one to ten letters or digits onto the package's account");
      }
      String name = blankToNull(m.externalName());
      byCode.put(code, new Mapping(c.id(), code, external, name == null ? null : name.trim()));
    }
    repo.replaceMappings(c.id(), new ArrayList<>(byCode.values()));
    return repo.mappings(c.id());
  }

  // ── pushing ─────────────────────────────────────────────────────────────────

  /**
   * A pass for one business, now: what the ledger has posted is queued, and what is due is tried.
   */
  public Run syncNow(UUID tenantId) {
    Connection c = require(tenantId);
    if (!c.active()) {
      throw ApiException.conflict(
          "ACCOUNTING_DISABLED", "The connection is switched off; switch it on to sync");
    }
    return runFor(c);
  }

  /** A pass for every connected business: the clock's. */
  public Run tick() {
    int queued = 0;
    int delivered = 0;
    int failed = 0;
    int uncertain = 0;
    for (Connection c : repo.activeConnections()) {
      try {
        Run r = runFor(c);
        queued += r.queued();
        delivered += r.delivered();
        failed += r.failed();
        uncertain += r.uncertain();
      } catch (RuntimeException e) {
        LOG.log(
            Level.WARNING,
            "Accounting sync for tenant " + c.tenantId() + " failed: " + e.getMessage(),
            e);
      }
    }
    return new Run(queued, delivered, failed, uncertain);
  }

  private Run runFor(Connection c) {
    Instant now = Instant.now();
    int queued = repo.queue(c, now, batch);
    int delivered = 0;
    int failed = 0;
    int uncertain = 0;
    String firstError = null;
    List<Sync> due = repo.claimDue(c.id(), batch, now, LEASE);
    if (!due.isEmpty()) {
      AccountingPackage pkg = packageOf(c);
      Map<String, String> mapping = new HashMap<>();
      for (Mapping m : repo.mappings(c.id())) mapping.put(m.nominalCode(), m.externalAccount());
      Function<String, String> account = code -> mapping.getOrDefault(code, code);
      Credentials creds = freshCredentialsQuietly(c, pkg, now);
      for (Sync s : due) {
        Sync after = push(c, pkg, creds, s, account);
        if (Accounting.DELIVERED.equals(after.status())) delivered++;
        else if (Accounting.UNCERTAIN.equals(after.status())) uncertain++;
        else failed++;
        if (!Accounting.DELIVERED.equals(after.status()) && firstError == null)
          firstError = after.lastError();
      }
    }
    repo.syncFinished(c.tenantId(), c.id(), Instant.now(), firstError);
    return new Run(queued, delivered, failed, uncertain);
  }

  private Sync push(
      Connection c,
      AccountingPackage pkg,
      Credentials creds,
      Sync s,
      Function<String, String> account) {
    int attempt = s.attempts() + 1;
    Instant at = Instant.now();
    long started = System.nanoTime();
    Integer status = null;
    String error = null;
    String snippet = null;
    String externalId = null;
    boolean retryable = true;
    boolean uncertain = false;
    List<NominalLedgerEntry> lines = ledger.findJournal(c.tenantId(), s.journalId());
    if (lines.isEmpty()) {
      error = "journal " + s.journalId() + " is no longer in the ledger";
      retryable = false;
    } else if (creds == null
        && Accounting.provider(c.provider()).map(Provider::needsCredentials).orElse(true)) {
      error = "the package's tokens could not be read; connect it again";
      retryable = false;
    } else {
      try {
        externalId = pkg.push(c, creds, journalOf(lines), account).externalId();
        status = 200;
      } catch (AccountingPackage.Refused r) {
        status = r.status();
        error = r.getMessage();
        snippet = r.getMessage();
        retryable = r.retryable();
      } catch (AccountingPackage.Unreachable u) {
        error = u.getMessage();
        // A push that may have reached a package with no idempotency key must not be sent again by
        // the clock: a second try could book the journal twice. A person decides.
        uncertain = u.requestSent() && !pkg.idempotentWrites();
      }
    }
    int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000L);
    Sync next;
    if (externalId != null) {
      next = with(s, Accounting.DELIVERED, attempt, externalId, null, s.nextAttemptAt(), at);
    } else if (uncertain) {
      next = with(s, Accounting.UNCERTAIN, attempt, null, error, s.nextAttemptAt(), null);
    } else if (retryable && attempt < Accounting.MAX_ATTEMPTS) {
      next =
          with(
              s,
              Accounting.PENDING,
              attempt,
              null,
              error,
              at.plus(Accounting.backoff(attempt)),
              null);
    } else {
      next = with(s, Accounting.FAILED, attempt, null, error, s.nextAttemptAt(), null);
    }
    repo.recordAttempt(
        new Attempt(
            Ids.newId(),
            s.tenantId(),
            s.id(),
            attempt,
            at,
            status,
            shorten(error),
            snippet,
            durationMs),
        next);
    return next;
  }

  private static Sync with(
      Sync s,
      String status,
      int attempts,
      String externalId,
      String error,
      Instant nextAttemptAt,
      Instant deliveredAt) {
    return new Sync(
        s.id(),
        s.tenantId(),
        s.connectionId(),
        s.journalId(),
        status,
        attempts,
        externalId,
        shorten(error),
        nextAttemptAt,
        null,
        s.createdAt(),
        deliveredAt,
        s.entryDate(),
        s.description(),
        s.sourceType(),
        s.total());
  }

  static Domain.Journal journalOf(List<NominalLedgerEntry> lines) {
    NominalLedgerEntry first = lines.get(0);
    return new Domain.Journal(
        first.journalId(),
        first.entryDate(),
        first.description(),
        first.sourceType(),
        first.sourceRef(),
        first.storeId(),
        lines);
  }

  /** The tokens, refreshed first when they are about to run out and can be. */
  private Credentials freshCredentials(Connection c, AccountingPackage pkg, Instant now) {
    if (c.credentialsSealed() == null) return null;
    Credentials creds;
    try {
      creds = secrets.openCredentials(c.credentialsSealed());
    } catch (RuntimeException e) {
      throw new ApiException(
          503,
          "ACCOUNTING_NOT_CONFIGURED",
          "The package's tokens cannot be opened under this deployment's key",
          List.of(),
          e);
    }
    if (creds.canRefresh() && creds.expiringBy(now, REFRESH_LEEWAY_SECONDS)) {
      try {
        Credentials refreshed = pkg.refresh(creds, now);
        repo.updateCredentials(c.tenantId(), c.id(), secrets.sealCredentials(refreshed), now);
        return refreshed;
      } catch (AccountingPackage.Refused e) {
        throw new ApiException(502, "ACCOUNTING_PROVIDER_REFUSED", e.getMessage(), List.of(), e);
      } catch (AccountingPackage.Unreachable e) {
        throw new ApiException(
            503,
            "ACCOUNTING_PROVIDER_UNREACHABLE",
            "The package's token endpoint did not answer: " + e.getMessage(),
            List.of(),
            e);
      }
    }
    return creds;
  }

  private Credentials freshCredentialsQuietly(Connection c, AccountingPackage pkg, Instant now) {
    try {
      return freshCredentials(c, pkg, now);
    } catch (ApiException e) {
      LOG.log(Level.WARNING, "Accounting tokens for tenant {0}: {1}", c.tenantId(), e.getMessage());
      return c.credentialsSealed() == null ? null : openQuietly(c);
    }
  }

  private Credentials openQuietly(Connection c) {
    try {
      return secrets.openCredentials(c.credentialsSealed());
    } catch (RuntimeException e) {
      return null;
    }
  }

  // ── the log ─────────────────────────────────────────────────────────────────

  public record Page(List<Sync> items, String nextCursor) {}

  public Page syncs(UUID tenantId, String status, UUID after, int limit) {
    Connection c = require(tenantId);
    if (status != null && !Accounting.SYNC_STATUSES.contains(status)) {
      throw ApiException.badRequest(
          "ACCOUNTING_STATUS_INVALID", "status is one of " + Accounting.SYNC_STATUSES);
    }
    int size = Math.max(1, Math.min(limit, 100));
    List<Sync> found = repo.syncs(tenantId, c.id(), status, after, size + 1);
    if (found.size() <= size) return new Page(found, null);
    List<Sync> page = found.subList(0, size);
    return new Page(page, page.get(size - 1).id().toString());
  }

  public record Detail(Sync sync, List<NominalLedgerEntry> lines, List<Attempt> attempts) {}

  public Detail sync(UUID tenantId, UUID id) {
    Sync s = repo.sync(tenantId, id).orElseThrow(AccountingService::syncNotFound);
    return new Detail(s, ledger.findJournal(tenantId, s.journalId()), repo.attempts(tenantId, id));
  }

  public Detail retry(UUID tenantId, UUID id) {
    repo.sync(tenantId, id).orElseThrow(AccountingService::syncNotFound);
    if (!repo.retry(tenantId, id, Instant.now())) {
      throw ApiException.conflict(
          "ACCOUNTING_SYNC_DELIVERED", "This journal is already in the package");
    }
    return sync(tenantId, id);
  }

  public Detail skip(UUID tenantId, UUID id, String reason) {
    repo.sync(tenantId, id).orElseThrow(AccountingService::syncNotFound);
    String why = reason == null ? "" : reason.trim();
    if (why.isEmpty() || why.length() > DESCRIPTION_MAX) {
      throw ApiException.badRequest(
          "ACCOUNTING_REASON_REQUIRED", "say why the journal is not to be pushed");
    }
    if (!repo.skip(tenantId, id, why)) {
      throw ApiException.conflict(
          "ACCOUNTING_SYNC_DELIVERED", "This journal is already in the package");
    }
    return sync(tenantId, id);
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private Connection require(UUID tenantId) {
    return repo.connection(tenantId).orElseThrow(AccountingService::notConnected);
  }

  private AccountingPackage packageOf(Connection c) {
    return packages
        .forProvider(c.provider())
        .orElseThrow(
            () ->
                new ApiException(
                    503,
                    "ACCOUNTING_PROVIDER_UNAVAILABLE",
                    "No driver for " + c.provider() + " in this deployment",
                    List.of()));
  }

  private static ApiException notConnected() {
    return ApiException.notFound(
        "ACCOUNTING_NOT_CONNECTED", "This business has no accounting package connected");
  }

  private static ApiException syncNotFound() {
    return ApiException.notFound("ACCOUNTING_SYNC_NOT_FOUND", "No such journal push");
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static String shorten(String s) {
    if (s == null) return null;
    return s.length() <= DESCRIPTION_MAX ? s : s.substring(0, DESCRIPTION_MAX);
  }
}
