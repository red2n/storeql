package com.storeql.purchase.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.purchase.client.accounting.AccountingPackage;
import com.storeql.purchase.client.accounting.AccountingPackages;
import com.storeql.purchase.domain.Accounting;
import com.storeql.purchase.domain.Accounting.Attempt;
import com.storeql.purchase.domain.Accounting.Connection;
import com.storeql.purchase.domain.Accounting.Credentials;
import com.storeql.purchase.domain.Accounting.Mapping;
import com.storeql.purchase.domain.Accounting.Sync;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.dto.AccountingDtos;
import com.storeql.purchase.repo.AccountingRepository;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the accounting service says when the package, or the key its tokens are sealed under, is not
 * there: a package that refuses is a bad gateway, one that does not answer is unavailable, and a
 * deployment that cannot open or seal a token says it is not configured. No database and no
 * network: the package is a stand-in that fails as it is told to, and nothing is written when it
 * does.
 */
class AccountingServiceFailureTest {

  private static final SecureRandom RANDOM = new SecureRandom();

  /** A key as the deployment would configure it, made for this test and never written down. */
  private static String newKey() {
    byte[] key = new byte[32];
    RANDOM.nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  private static AccountingSecrets secretsUnder(String keyBase64) {
    AccountingSecrets secrets = new AccountingSecrets();
    secrets.accountingKey = Optional.ofNullable(keyBase64);
    secrets.initAccounting();
    return secrets;
  }

  /** The one connection a business has, and a count of everything written to it. */
  private static class OneConnection extends AccountingRepository {
    private final Connection connection;
    int credentialWrites;
    int replaced;

    OneConnection(Connection connection) {
      this.connection = connection;
    }

    @Override
    public Optional<Connection> connection(UUID tenantId) {
      return connection != null && connection.tenantId().equals(tenantId)
          ? Optional.of(connection)
          : Optional.empty();
    }

    @Override
    public void updateCredentials(UUID tenantId, UUID id, String sealed, Instant now) {
      credentialWrites++;
    }

    @Override
    public Connection replace(Connection c) {
      replaced++;
      return c;
    }
  }

  /** A package that fails as it is told to. */
  private static final class FailingPackage implements AccountingPackage {
    RuntimeException onChart;
    RuntimeException onRefresh;

    @Override
    public String provider() {
      return Accounting.XERO;
    }

    @Override
    public boolean idempotentWrites() {
      return true;
    }

    @Override
    public Pushed push(
        Connection connection,
        Credentials credentials,
        Domain.Journal journal,
        Function<String, String> account) {
      throw new UnsupportedOperationException("not asked in these tests");
    }

    @Override
    public List<ExternalAccount> accounts(Connection connection, Credentials credentials) {
      if (onChart != null) throw onChart;
      return List.of();
    }

    @Override
    public Credentials refresh(Credentials credentials, Instant now) {
      if (onRefresh != null) throw onRefresh;
      return credentials;
    }
  }

  private static final class OnlyThis extends AccountingPackages {
    private final AccountingPackage only;

    OnlyThis(AccountingPackage only) {
      this.only = only;
    }

    @Override
    public Optional<AccountingPackage> forProvider(String provider) {
      return only.provider().equals(provider) ? Optional.of(only) : Optional.empty();
    }
  }

  private static Connection xero(UUID tenantId, String sealed) {
    Instant now = Instant.now();
    return new Connection(
        Ids.newId(),
        tenantId,
        Accounting.XERO,
        Accounting.ACTIVE,
        Map.of("tenantId", "xero-org-1"),
        sealed,
        LocalDate.of(2026, 1, 1),
        Ids.newId(),
        now,
        now,
        null,
        null,
        null);
  }

  private static AccountingService service(
      OneConnection repo, AccountingPackage pkg, AccountingSecrets secrets) {
    AccountingService s = new AccountingService();
    s.repo = repo;
    s.packages = new OnlyThis(pkg);
    s.secrets = secrets;
    return s;
  }

  /** Tokens that have run out and can be traded for new ones, sealed under the key. */
  private static String expiredTokens(AccountingSecrets secrets) {
    return secrets.sealCredentials(
        new Credentials(
            "access-" + Ids.newId(),
            "refresh-" + Ids.newId(),
            "client-" + Ids.newId(),
            "secret-" + Ids.newId(),
            Instant.now().minusSeconds(3600)));
  }

  // ── the chart, asked of the package ─────────────────────────────────────────

  @Test
  @DisplayName("A package that refuses the chart is a bad gateway, 502 ACCOUNTING_PROVIDER_REFUSED")
  void aPackageThatRefusesTheChartIsBadGateway() {
    UUID tenant = Ids.newId();
    FailingPackage pkg = new FailingPackage();
    pkg.onChart = new AccountingPackage.Refused(401, "Xero said: the token is not valid", false);
    OneConnection repo = new OneConnection(xero(tenant, null));

    ApiException e =
        assertThrows(
            ApiException.class, () -> service(repo, pkg, secretsUnder(null)).accounts(tenant));

    assertThat(e.status(), is(502));
    assertThat(e.code(), is("ACCOUNTING_PROVIDER_REFUSED"));
    assertThat("the package's own words are kept", e.getMessage(), containsString("not valid"));
    assertThat("nothing was written", repo.credentialWrites + repo.replaced, is(0));
  }

  @Test
  @DisplayName(
      "A package that does not answer the chart is unavailable, 503 ACCOUNTING_PROVIDER_UNREACHABLE")
  void aPackageThatDoesNotAnswerTheChartIsUnavailable() {
    UUID tenant = Ids.newId();
    FailingPackage pkg = new FailingPackage();
    pkg.onChart = new AccountingPackage.Unreachable("connection refused", false, null);
    OneConnection repo = new OneConnection(xero(tenant, null));

    ApiException e =
        assertThrows(
            ApiException.class, () -> service(repo, pkg, secretsUnder(null)).accounts(tenant));

    assertThat(e.status(), is(503));
    assertThat(e.code(), is("ACCOUNTING_PROVIDER_UNREACHABLE"));
    assertThat(e.getMessage(), containsString("connection refused"));
    assertThat("nothing was written", repo.credentialWrites + repo.replaced, is(0));
  }

  @Test
  @DisplayName("A business with no connection is not found, whatever the package would have said")
  void aBusinessWithNoConnectionIsNotFound() {
    FailingPackage pkg = new FailingPackage();
    pkg.onChart = new AccountingPackage.Unreachable("never asked", false, null);
    UUID theirs = Ids.newId();
    OneConnection repo = new OneConnection(xero(theirs, null));

    ApiException e =
        assertThrows(
            ApiException.class, () -> service(repo, pkg, secretsUnder(null)).accounts(Ids.newId()));

    assertThat(e.status(), is(404));
    assertThat(e.code(), is("ACCOUNTING_NOT_CONNECTED"));
  }

  // ── the tokens, traded at the package's token endpoint ──────────────────────

  @Test
  @DisplayName(
      "A token endpoint that refuses is 502 ACCOUNTING_PROVIDER_REFUSED, and keeps the old tokens")
  void aTokenEndpointThatRefusesIsBadGateway() {
    UUID tenant = Ids.newId();
    AccountingSecrets secrets = secretsUnder(newKey());
    FailingPackage pkg = new FailingPackage();
    pkg.onRefresh = new AccountingPackage.Refused(400, "the refresh token was revoked", false);
    OneConnection repo = new OneConnection(xero(tenant, expiredTokens(secrets)));

    ApiException e =
        assertThrows(ApiException.class, () -> service(repo, pkg, secrets).accounts(tenant));

    assertThat(e.status(), is(502));
    assertThat(e.code(), is("ACCOUNTING_PROVIDER_REFUSED"));
    assertThat(e.getMessage(), containsString("revoked"));
    assertThat("the sealed tokens were not rewritten", repo.credentialWrites, is(0));
  }

  @Test
  @DisplayName(
      "A token endpoint that does not answer is 503 ACCOUNTING_PROVIDER_UNREACHABLE, and keeps the old tokens")
  void aTokenEndpointThatDoesNotAnswerIsUnavailable() {
    UUID tenant = Ids.newId();
    AccountingSecrets secrets = secretsUnder(newKey());
    FailingPackage pkg = new FailingPackage();
    pkg.onRefresh = new AccountingPackage.Unreachable("timed out", true, null);
    OneConnection repo = new OneConnection(xero(tenant, expiredTokens(secrets)));

    ApiException e =
        assertThrows(ApiException.class, () -> service(repo, pkg, secrets).accounts(tenant));

    assertThat(e.status(), is(503));
    assertThat(e.code(), is("ACCOUNTING_PROVIDER_UNREACHABLE"));
    assertThat(e.getMessage(), containsString("token endpoint"));
    assertThat("the sealed tokens were not rewritten", repo.credentialWrites, is(0));
  }

  // ── the key the tokens are sealed under ─────────────────────────────────────

  @Test
  @DisplayName("Tokens sealed under another key cannot be opened: 503 ACCOUNTING_NOT_CONFIGURED")
  void tokensSealedUnderAnotherKeyAreNotOpened() {
    UUID tenant = Ids.newId();
    String sealedUnderTheOldKey = expiredTokens(secretsUnder(newKey()));
    FailingPackage pkg = new FailingPackage();
    OneConnection repo = new OneConnection(xero(tenant, sealedUnderTheOldKey));

    // The key was rotated, and the tokens were not re-sealed.
    ApiException rotated =
        assertThrows(
            ApiException.class, () -> service(repo, pkg, secretsUnder(newKey())).accounts(tenant));
    assertThat(rotated.status(), is(503));
    assertThat(rotated.code(), is("ACCOUNTING_NOT_CONFIGURED"));

    // Or the key is gone from the deployment altogether.
    ApiException gone =
        assertThrows(
            ApiException.class, () -> service(repo, pkg, secretsUnder(null)).accounts(tenant));
    assertThat(gone.status(), is(503));
    assertThat(gone.code(), is("ACCOUNTING_NOT_CONFIGURED"));
    assertThat("nothing was written", repo.credentialWrites + repo.replaced, is(0));
  }

  @Test
  @DisplayName("A deployment with no key connects no package that needs tokens: 503, nothing kept")
  void aDeploymentWithNoKeyConnectsNoCredentialedPackage() {
    UUID tenant = Ids.newId();
    OneConnection repo = new OneConnection(null);
    AccountingService noKey = service(repo, new FailingPackage(), secretsUnder(null));
    AccountingDtos.ConnectRequest xeroWithTokens =
        new AccountingDtos.ConnectRequest(
            "XERO",
            Map.of("tenantId", "xero-org-1"),
            new AccountingDtos.CredentialsRequest("access-" + Ids.newId(), null, null, null, null),
            "2026-01-01");

    ApiException e =
        assertThrows(ApiException.class, () -> noKey.connect(tenant, Ids.newId(), xeroWithTokens));

    assertThat(e.status(), is(503));
    assertThat(e.code(), is("ACCOUNTING_NOT_CONFIGURED"));
    assertThat(e.getMessage(), containsString("storeql.accounting.secrets-key"));
    assertThat("no connection was kept", repo.replaced, is(0));

    // The platform's own stand-in holds no tokens, so it needs no key: it is the refusal above that
    // is about the tokens, not about connecting.
    noKey.connect(
        tenant,
        Ids.newId(),
        new AccountingDtos.ConnectRequest("SIMULATED", Map.of(), null, "2026-01-01"));
    assertThat("the stand-in connected", repo.replaced, is(1));
  }

  // ── a connection's settings ─────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A QuickBooks realm id is the company's digits: anything else is 400"
          + " ACCOUNTING_SETTINGS_INVALID and nothing is kept")
  void aQuickBooksRealmIdIsDigits() {
    UUID tenant = Ids.newId();
    OneConnection repo = new OneConnection(null);
    AccountingService svc = service(repo, new FailingPackage(), secretsUnder(newKey()));
    for (String realm :
        new String[] {"91 30", "9130%", "9130/../../v3/company/1", "abc", "9130\n1", "91.30"}) {
      ApiException e =
          assertThrows(
              ApiException.class, () -> svc.connect(tenant, Ids.newId(), quickBooks(realm)), realm);
      assertThat(realm, e.status(), is(400));
      assertThat(realm, e.code(), is("ACCOUNTING_SETTINGS_INVALID"));
      assertThat(realm, e.getMessage(), containsString("realmId"));
    }
    assertThat("no connection was kept", repo.replaced, is(0));

    svc.connect(tenant, Ids.newId(), quickBooks(" 9130354823543226 "));
    assertThat("a company id connects, trimmed", repo.replaced, is(1));
  }

  @Test
  @DisplayName("A setting is one line of text: a control character in any of them is refused")
  void aSettingIsOneLine() {
    UUID tenant = Ids.newId();
    OneConnection repo = new OneConnection(null);
    AccountingService svc = service(repo, new FailingPackage(), secretsUnder(newKey()));
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.connect(
                    tenant,
                    Ids.newId(),
                    new AccountingDtos.ConnectRequest(
                        "XERO",
                        Map.of("tenantId", "xero-org-1\r\nX-Injected: 1"),
                        new AccountingDtos.CredentialsRequest(
                            "access-" + Ids.newId(), null, null, null, null),
                        "2026-01-01")));
    assertThat(e.status(), is(400));
    assertThat(e.code(), is("ACCOUNTING_SETTINGS_INVALID"));
    assertThat("no connection was kept", repo.replaced, is(0));
  }

  private static AccountingDtos.ConnectRequest quickBooks(String realmId) {
    return new AccountingDtos.ConnectRequest(
        "QUICKBOOKS",
        Map.of("realmId", realmId, "environment", "SANDBOX"),
        new AccountingDtos.CredentialsRequest("access-" + Ids.newId(), null, null, null, null),
        "2026-01-01");
  }

  // ── a push that cannot be made ──────────────────────────────────────────────

  /** One due push for one connection, and what the service wrote back about it. */
  private static final class OneDuePush extends OneConnection {
    final Sync due;
    final List<Attempt> attempts = new ArrayList<>();
    final List<Sync> outcomes = new ArrayList<>();
    final List<String> finished = new ArrayList<>();

    OneDuePush(Connection connection, Sync due) {
      super(connection);
      this.due = due;
    }

    @Override
    public List<Connection> activeConnections() {
      return List.of(connection(due.tenantId()).orElseThrow());
    }

    @Override
    public int queue(Connection c, Instant now, int limit) {
      return 0;
    }

    @Override
    public List<Sync> claimDue(UUID connectionId, int limit, Instant now, Duration lease) {
      return List.of(due);
    }

    @Override
    public List<Mapping> mappings(UUID connectionId) {
      return List.of();
    }

    @Override
    public void recordAttempt(Attempt a, Sync s) {
      attempts.add(a);
      outcomes.add(s);
    }

    @Override
    public void syncFinished(UUID tenantId, UUID id, Instant at, String error) {
      finished.add(error == null ? "" : error);
    }
  }

  /** The ledger, holding the one journal the push is for. */
  private static final class OneJournal extends PurchaseRepository {
    private final List<NominalLedgerEntry> lines;

    OneJournal(UUID tenantId, UUID journalId) {
      Instant now = Instant.now();
      LocalDate day = LocalDate.of(2026, 9, 23);
      this.lines =
          List.of(
              new NominalLedgerEntry(
                  Ids.newId(),
                  tenantId,
                  day,
                  "1200",
                  "Stock",
                  new BigDecimal("10.00"),
                  BigDecimal.ZERO,
                  "Goods received",
                  Ids.newId(),
                  now,
                  journalId,
                  "GR",
                  null),
              new NominalLedgerEntry(
                  Ids.newId(),
                  tenantId,
                  day,
                  "2109",
                  "GRNI",
                  BigDecimal.ZERO,
                  new BigDecimal("10.00"),
                  "Goods received",
                  Ids.newId(),
                  now,
                  journalId,
                  "GR",
                  null));
    }

    @Override
    public List<NominalLedgerEntry> findJournal(UUID tenantId, UUID journalId) {
      return lines.isEmpty() || !lines.get(0).tenantId().equals(tenantId) ? List.of() : lines;
    }
  }

  /** A package whose every push fails as it is told to. */
  private static final class PushFails implements AccountingPackage {
    private final RuntimeException failure;
    private final boolean idempotent;

    PushFails(RuntimeException failure, boolean idempotent) {
      this.failure = failure;
      this.idempotent = idempotent;
    }

    @Override
    public String provider() {
      return Accounting.XERO;
    }

    @Override
    public boolean idempotentWrites() {
      return idempotent;
    }

    @Override
    public List<ExternalAccount> accounts(Connection connection, Credentials credentials) {
      return List.of();
    }

    @Override
    public Credentials refresh(Credentials credentials, Instant now) {
      return credentials;
    }

    @Override
    public Pushed push(
        Connection connection,
        Credentials credentials,
        Domain.Journal journal,
        Function<String, String> account) {
      throw failure;
    }
  }

  private static OneDuePush duePush(AccountingSecrets secrets) {
    UUID tenant = Ids.newId();
    String sealed =
        secrets.sealCredentials(new Credentials("access-" + Ids.newId(), null, null, null, null));
    Connection c = xero(tenant, sealed);
    Instant now = Instant.now();
    Sync due =
        new Sync(
            Ids.newId(),
            tenant,
            c.id(),
            Ids.newId(),
            Accounting.PENDING,
            0,
            null,
            null,
            now,
            now.plusSeconds(120),
            now,
            null,
            LocalDate.of(2026, 9, 23),
            "Goods received",
            "GR",
            new BigDecimal("10.00"));
    return new OneDuePush(c, due);
  }

  private static AccountingService pushing(
      OneDuePush repo, AccountingPackage pkg, AccountingSecrets secrets) {
    AccountingService s = service(repo, pkg, secrets);
    s.ledger = new OneJournal(repo.due.tenantId(), repo.due.journalId());
    s.batch = 50;
    return s;
  }

  @Test
  @DisplayName(
      "A push refused before it was sent is failed at once, with the reason and no status code:"
          + " never left claimed with nobody told")
  void aPushRefusedUnsentIsFailedWithItsReason() {
    AccountingSecrets secrets = secretsUnder(newKey());
    OneDuePush repo = duePush(secrets);
    AccountingPackage pkg =
        new PushFails(
            AccountingPackage.Refused.unsent("the package's address cannot be formed: realmId"),
            true);

    Accounting.Run run = pushing(repo, pkg, secrets).tick();

    assertThat(run.failed(), is(1));
    assertThat("the try is on the log", repo.attempts.size(), is(1));
    Attempt a = repo.attempts.get(0);
    assertThat(a.attempt(), is(1));
    assertThat("nothing was sent, so nothing answered", a.statusCode(), is(nullValue()));
    assertThat(a.error(), containsString("cannot be formed"));
    Sync after = repo.outcomes.get(0);
    assertThat("a person must put it right; the clock will not", after.status(), is("FAILED"));
    assertThat(after.lastError(), containsString("realmId"));
    assertThat("the pass was finished, with the reason", repo.finished.size(), is(1));
    assertThat(repo.finished.get(0), containsString("cannot be formed"));
  }

  @Test
  @DisplayName(
      "A driver that fails in a way it should not still has its try counted and its reason kept;"
          + " where a second send could book twice, a person decides")
  void aDriverFaultIsCountedNotLost() {
    AccountingSecrets secrets = secretsUnder(newKey());

    OneDuePush idempotent = duePush(secrets);
    Accounting.Run first =
        pushing(idempotent, new PushFails(new IllegalStateException("driver bug"), true), secrets)
            .tick();
    assertThat(first.failed(), is(1));
    assertThat(idempotent.attempts.size(), is(1));
    Sync retried = idempotent.outcomes.get(0);
    assertThat("tried again later, as an unanswered push is", retried.status(), is("PENDING"));
    assertThat(retried.attempts(), is(1));
    assertThat(retried.lastError(), containsString("driver bug"));
    assertThat(idempotent.finished.size(), is(1));

    OneDuePush once = duePush(secrets);
    Accounting.Run second =
        pushing(once, new PushFails(new IllegalStateException("driver bug"), false), secrets)
            .tick();
    assertThat(second.uncertain(), is(1));
    assertThat(once.outcomes.get(0).status(), is("UNCERTAIN"));
    assertThat(once.finished.size(), is(1));
  }

  // ── QuickBooks Online's environment ─────────────────────────────────────────

  @Test
  @DisplayName(
      "QuickBooks Online's environment is PRODUCTION or SANDBOX, in any case as its driver reads"
          + " it: anything else is 400 ACCOUNTING_SETTINGS_INVALID naming the setting, nothing kept")
  void aQuickBooksEnvironmentIsProductionOrSandbox() {
    UUID tenant = Ids.newId();
    OneConnection repo = new OneConnection(null);
    AccountingService svc = service(repo, new FailingPackage(), secretsUnder(newKey()));
    for (String environment :
        new String[] {"PROD", "Sandbx", "live", "production2", "SANDBOX PRODUCTION", "test"}) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> svc.connect(tenant, Ids.newId(), quickBooks("9130", environment)),
              environment);
      assertThat(environment, e.status(), is(400));
      assertThat(environment, e.code(), is("ACCOUNTING_SETTINGS_INVALID"));
      assertThat(environment, e.getMessage(), containsString("environment"));
    }
    assertThat("no connection was kept", repo.replaced, is(0));

    int kept = 0;
    for (String environment : new String[] {"PRODUCTION", "SANDBOX", "sandbox", " Production "}) {
      svc.connect(tenant, Ids.newId(), quickBooks("9130", environment));
      assertThat(environment + " connects", repo.replaced, is(++kept));
    }
    // Left out, it is production, as it always was.
    svc.connect(
        tenant,
        Ids.newId(),
        new AccountingDtos.ConnectRequest(
            "QUICKBOOKS",
            Map.of("realmId", "9130"),
            new AccountingDtos.CredentialsRequest("access-" + Ids.newId(), null, null, null, null),
            "2026-01-01"));
    assertThat(repo.replaced, is(kept + 1));
  }

  private static AccountingDtos.ConnectRequest quickBooks(String realmId, String environment) {
    return new AccountingDtos.ConnectRequest(
        "QUICKBOOKS",
        Map.of("realmId", realmId, "environment", environment),
        new AccountingDtos.CredentialsRequest("access-" + Ids.newId(), null, null, null, null),
        "2026-01-01");
  }

  /** A QuickBooks package that counts what it is asked and answers every push. */
  private static final class CountingQuickBooks implements AccountingPackage {
    int pushes;
    int charts;

    @Override
    public String provider() {
      return Accounting.QUICKBOOKS;
    }

    @Override
    public boolean idempotentWrites() {
      return true;
    }

    @Override
    public List<ExternalAccount> accounts(Connection connection, Credentials credentials) {
      charts++;
      return List.of();
    }

    @Override
    public Credentials refresh(Credentials credentials, Instant now) {
      return credentials;
    }

    @Override
    public Pushed push(
        Connection connection,
        Credentials credentials,
        Domain.Journal journal,
        Function<String, String> account) {
      pushes++;
      return new Pushed("145");
    }
  }

  /** A QuickBooks connection kept before its settings were checked, with one push due. */
  private static OneDuePush savedBeforeTheCheck(
      AccountingSecrets secrets, Map<String, String> settings) {
    OneDuePush xero = duePush(secrets);
    Connection old = xero.connection(xero.due.tenantId()).orElseThrow();
    Connection qbo =
        new Connection(
            old.id(),
            old.tenantId(),
            Accounting.QUICKBOOKS,
            Accounting.ACTIVE,
            settings,
            old.credentialsSealed(),
            old.syncFrom(),
            old.createdBy(),
            old.createdAt(),
            old.updatedAt(),
            null,
            null,
            null);
    return new OneDuePush(qbo, xero.due);
  }

  @Test
  @DisplayName(
      "A connection saved before its settings were checked is refused unsent at push time, with"
          + " its reason and no status code: never sent to an address it cannot be")
  void aStoredConnectionWithABadSettingIsRefusedUnsent() {
    AccountingSecrets secrets = secretsUnder(newKey());
    // Each kept connection, and the setting its refusal names.
    Map<Map<String, String>, String> kept =
        Map.of(
            Map.of("realmId", "9130", "environment", "PROD"), "environment",
            Map.of("realmId", "9130/../../v3/company/1", "environment", "SANDBOX"), "realmId",
            Map.of("environment", "SANDBOX"), "realmId");
    for (var k : kept.entrySet()) {
      Map<String, String> settings = k.getKey();
      OneDuePush repo = savedBeforeTheCheck(secrets, settings);
      CountingQuickBooks pkg = new CountingQuickBooks();

      Accounting.Run run = pushing(repo, pkg, secrets).tick();

      assertThat(settings.toString(), run.failed(), is(1));
      assertThat(settings + ": QuickBooks was never asked", pkg.pushes, is(0));
      Attempt a = repo.attempts.get(0);
      assertThat("nothing was sent, so nothing answered", a.statusCode(), is(nullValue()));
      Sync after = repo.outcomes.get(0);
      assertThat("a person must put it right; the clock will not", after.status(), is("FAILED"));
      assertThat(settings.toString(), after.lastError(), containsString(k.getValue()));
      assertThat(repo.finished.size(), is(1));

      // The chart is not asked for either: the connection is to be put right first.
      ApiException chart =
          assertThrows(
              ApiException.class, () -> service(repo, pkg, secrets).accounts(repo.due.tenantId()));
      assertThat(chart.status(), is(409));
      assertThat(chart.code(), is("ACCOUNTING_SETTINGS_INVALID"));
      assertThat(pkg.charts, is(0));
    }

    // The same connection with its settings put right is pushed.
    OneDuePush fixed =
        savedBeforeTheCheck(secrets, Map.of("realmId", "9130", "environment", "sandbox"));
    CountingQuickBooks pkg = new CountingQuickBooks();
    assertThat(pushing(fixed, pkg, secrets).tick().delivered(), is(1));
    assertThat(pkg.pushes, is(1));
  }
}
