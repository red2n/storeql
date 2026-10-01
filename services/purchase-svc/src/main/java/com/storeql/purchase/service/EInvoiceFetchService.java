package com.storeql.purchase.service;

import com.storeql.einvoice.Fa3;
import com.storeql.purchase.client.KsefInbox;
import com.storeql.purchase.client.KsefInbox.KsefException;
import com.storeql.purchase.domain.EInvoiceInbox;
import com.storeql.purchase.domain.EInvoiceInbox.Fetch;
import com.storeql.purchase.domain.EInvoiceInbox.Settings;
import com.storeql.purchase.domain.EInvoiceInbox.Waiting;
import com.storeql.purchase.domain.SupplierEInvoices;
import com.storeql.purchase.repo.EInvoiceInboxRepository;
import com.storeql.service.SealedSecrets;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Fetching a Polish buyer's invoices (07.13).
 *
 * <p>Every other network on this platform delivers. KSeF does not: a Polish buyer's invoices sit in
 * the ministry's system until the buyer asks, so <b>a platform that only listens receives nothing
 * in Poland</b>, however correct the rest of it is. This service is the asking.
 *
 * <p>Four judgements shape it.
 *
 * <p><b>A window, not a cursor.</b> KSeF is asked by date range, so the settings remember the day
 * the last fetch reached and the next asks from there. A range can be asked again safely — the
 * inbox recognises a document it already holds by its bytes — where a cursor that was lost would
 * leave a gap nobody could see.
 *
 * <p><b>What arrives goes through the ordinary intake.</b> FA(3) is read into the same model a UBL
 * document produces, so the checks, the supplier matching and the three-way match are the ones
 * every other invoice gets. Nothing about a Polish invoice is special once it is read.
 *
 * <p><b>One document's failure is not the fetch's.</b> A document the inbox will not take is
 * counted and named, and the rest are still taken: a single malformed invoice must not stop a
 * fortnight of purchases from reaching the ledger.
 *
 * <p><b>The window only advances over what was asked and answered.</b> A network that could not be
 * reached leaves {@code fetched_to} where it was, so the next fetch asks again rather than skipping
 * the days nobody ever looked at.
 */
@ApplicationScoped
public class EInvoiceFetchService {

  private static final Logger LOG = System.getLogger(EInvoiceFetchService.class.getName());

  /** How many days one fetch will ask for at most, so a first run cannot pull a decade. */
  static final int MAX_WINDOW_DAYS = 90;

  @Inject EInvoiceInboxRepository repo;
  @Inject SupplierEInvoiceService inbox;
  @Inject KsefInbox ksef;
  @Inject SealedSecrets secrets;
  @Inject TenantProfiles profiles;

  /** The settings as a screen may see them: never the credential, only that one is held. */
  public record SettingsView(
      Settings settings, boolean hasSecret, boolean deploymentConfigured, String requires) {}

  public SettingsView settings(UUID tenantId) {
    Settings s = repo.find(tenantId).orElseGet(() -> Settings.none(tenantId));
    return new SettingsView(
        s,
        s.hasSecret(),
        ksef.isConfigured(),
        "storeql.einvoice.ksef.base-url, the business's NIP as its account, and its KSeF token");
  }

  /**
   * Chooses where the business fetches from.
   *
   * @param secret the business's KSeF token as typed, null to keep the one stored, blank to remove
   *     it
   * @throws ApiException 400 on an unknown network or provider, or a NIP that is not one; 409 when
   *     the deployment cannot reach KSeF, or no token is held for a provider that signs in
   */
  public SettingsView setSettings(
      UUID tenantId, String network, String provider, String account, String secret, UUID actorId) {
    String net = upper(network);
    String prov = upper(provider);
    if (!EInvoiceInbox.NETWORKS.contains(net)) {
      throw ApiException.badRequest(
          "PURCHASE_INBOX_NETWORK_UNKNOWN", "a network is NONE or KSEF — got: " + network);
    }
    if (EInvoiceInbox.NETWORK_NONE.equals(net)) {
      repo.upsert(
          new Settings(
              tenantId,
              EInvoiceInbox.NETWORK_NONE,
              EInvoiceInbox.PROVIDER_NONE,
              null,
              null,
              null,
              null,
              null,
              null,
              actorId),
          false);
      return settings(tenantId);
    }
    if (!EInvoiceInbox.PROVIDERS.contains(prov) || EInvoiceInbox.PROVIDER_NONE.equals(prov)) {
      throw ApiException.badRequest(
          "PURCHASE_INBOX_PROVIDER_UNKNOWN", "a provider is SIMULATED or KSEF — got: " + provider);
    }
    String nip = Fa3.nipOf(account) != null ? Fa3.nipOf(account) : strip(account);
    if (nip == null || !Fa3.validNip(nip)) {
      throw ApiException.badRequest(
          "PURCHASE_INBOX_ACCOUNT_INVALID",
          "KSeF knows a business by its NIP, and that is not a valid one");
    }
    if (EInvoiceInbox.PROVIDER_KSEF.equals(prov) && !ksef.isConfigured()) {
      throw ApiException.conflict(
          "PURCHASE_INBOX_NOT_DEPLOYED",
          "this deployment holds no KSeF endpoint, so nothing can be fetched from it");
    }
    boolean replaceSecret = secret != null;
    String sealed = null;
    if (secret != null && !secret.isBlank()) {
      if (!secrets.isConfigured()) {
        throw ApiException.conflict(
            "PURCHASE_SECRETS_KEY_MISSING",
            "this deployment has no key to seal a credential with, so none can be kept");
      }
      sealed = secrets.seal(secret.strip());
    }
    Settings current = repo.find(tenantId).orElseGet(() -> Settings.none(tenantId));
    boolean willHoldSecret = replaceSecret ? sealed != null : current.hasSecret();
    if (EInvoiceInbox.PROVIDER_KSEF.equals(prov) && !willHoldSecret) {
      throw ApiException.conflict(
          "PURCHASE_INBOX_SECRET_REQUIRED",
          "KSeF signs a business in with its own token; record one to fetch");
    }
    repo.upsert(
        new Settings(tenantId, net, prov, nip, sealed, null, null, null, null, actorId),
        replaceSecret);
    return settings(tenantId);
  }

  /**
   * One thing that must be true before a first fetch, and what to do when it is not.
   *
   * @param state {@code READY}, {@code UNREACHABLE} or {@code REFUSED} for the network itself
   */
  public record Readiness(
      String network,
      String provider,
      boolean ready,
      List<String> outstanding,
      String state,
      String detail) {

    public Readiness {
      outstanding = outstanding == null ? List.of() : List.copyOf(outstanding);
    }
  }

  /**
   * What stands between a business and its first fetched invoice — the network asked, not assumed.
   *
   * <p>For the day a KSeF token is issued: a shop presses one button and knows whether tomorrow's
   * invoices will arrive, rather than finding out from an inbox that stays empty. Nothing is
   * fetched; the ministry is asked whether it knows us.
   */
  public Readiness readiness(UUID tenantId) {
    Settings s = repo.find(tenantId).orElseGet(() -> Settings.none(tenantId));
    List<String> outstanding = new ArrayList<>();
    if (!s.fetching()) {
      outstanding.add("choose where this business fetches from: KSEF, with its NIP and its token");
    }
    if (s.fetching() && s.providerAccount() == null) {
      outstanding.add("record the business's NIP: KSeF knows a business by it");
    }
    if (EInvoiceInbox.PROVIDER_KSEF.equals(s.provider()) && !s.hasSecret()) {
      outstanding.add("record the business's KSeF token");
    }
    if (EInvoiceInbox.PROVIDER_KSEF.equals(s.provider()) && !ksef.isConfigured()) {
      outstanding.add("this deployment holds no KSeF endpoint (storeql.einvoice.ksef.base-url)");
    }
    if (!outstanding.isEmpty()) {
      return new Readiness(s.network(), s.provider(), false, outstanding, null, null);
    }
    if (EInvoiceInbox.PROVIDER_SIMULATED.equals(s.provider())) {
      return new Readiness(
          s.network(),
          s.provider(),
          false,
          List.of("the platform is standing in for KSeF, so nothing will ever arrive"),
          "REFUSED",
          "the platform stands in for the ministry: nothing leaves it, so nothing arrives");
    }
    String token;
    try {
      token = secrets.open(s.providerSecret());
    } catch (IllegalStateException keyGone) {
      // The deployment's key changed under a credential sealed with the old one: a person must
      // record the token again, which is a refusal and not something waiting will mend.
      return new Readiness(
          s.network(),
          s.provider(),
          false,
          List.of("record the business's KSeF token again: the one held cannot be opened"),
          "REFUSED",
          "the business's credential could not be opened: " + keyGone.getMessage());
    }
    KsefException failed;
    try {
      failed = ksef.checkSignIn(s.providerAccount(), token);
    } catch (RuntimeException e) {
      // A check must never be the thing that breaks: whatever the ministry does, the answer is what
      // it did.
      return new Readiness(
          s.network(),
          s.provider(),
          false,
          List.of(),
          "UNREACHABLE",
          String.valueOf(e.getMessage()));
    }
    if (failed == null) {
      return new Readiness(
          s.network(), s.provider(), true, List.of(), "READY", "KSeF signed the business in");
    }
    return new Readiness(
        s.network(),
        s.provider(),
        false,
        List.of(),
        failed.retryable() ? "UNREACHABLE" : "REFUSED",
        failed.getMessage());
  }

  /**
   * Fetches what the network is holding for the business.
   *
   * @param from the first day to ask about, or null to carry on from the last fetch
   * @param to the last day, or null for today
   * @throws ApiException 409 when the business fetches from nowhere, 503 when the network could not
   *     be reached
   */
  public Fetch fetch(TenantContext ctx, LocalDate from, LocalDate to) {
    UUID tenantId = ctx.requireTenantId();
    Settings s =
        repo.find(tenantId)
            .filter(Settings::fetching)
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "PURCHASE_INBOX_NOT_SET",
                        "the business fetches its invoices from nowhere yet"));
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate end = to == null || to.isAfter(today) ? today : to;
    LocalDate start =
        from != null
            ? from
            : s.fetchedTo() != null
                ? s.fetchedTo()
                : today.minusDays(EInvoiceInbox.FIRST_FETCH_DAYS);
    if (start.isAfter(end)) {
      throw ApiException.badRequest(
          "PURCHASE_INBOX_WINDOW_INVALID", "the window ends before it starts");
    }
    if (start.plusDays(MAX_WINDOW_DAYS).isBefore(end)) {
      throw ApiException.badRequest(
          "PURCHASE_INBOX_WINDOW_INVALID",
          "one fetch asks for at most " + MAX_WINDOW_DAYS + " days");
    }

    if (EInvoiceInbox.PROVIDER_SIMULATED.equals(s.provider())) {
      // The platform standing in for the ministry: nothing leaves, so nothing arrives. Said plainly
      // rather than answered as an empty success, because a shop must not believe it has fetched.
      repo.fetched(tenantId, end, "the platform stands in for KSeF; nothing is fetched");
      return new Fetch(
          start,
          end,
          0,
          0,
          0,
          0,
          List.of("the platform stands in for KSeF: nothing left it, so nothing arrived"));
    }

    String token = secrets.open(s.providerSecret());
    List<Waiting> waiting;
    try {
      waiting = ksef.waiting(s.providerAccount(), token, start, end);
    } catch (KsefException e) {
      repo.fetched(tenantId, s.fetchedTo(), e.getMessage());
      throw new ApiException(
          422,
          e.retryable() ? "PURCHASE_INBOX_NETWORK_DOWN" : "PURCHASE_INBOX_REFUSED",
          e.getMessage(),
          List.of(),
          e);
    }

    int received = 0;
    int already = 0;
    int refused = 0;
    List<String> notes = new ArrayList<>();
    String access = null;
    for (Waiting w : waiting) {
      try {
        // One sign-in for the whole fetch, held in this local only.
        if (access == null) access = ksef.signIn(s.providerAccount(), token);
        byte[] document = ksef.download(access, w.reference());
        var receipt =
            inbox.receive(
                ctx,
                document,
                "application/xml",
                SupplierEInvoices.CHANNEL_KSEF,
                w.reference(),
                null);
        if (receipt.alreadyReceived()) already++;
        else received++;
      } catch (KsefException e) {
        // The network went away partway through. What has been taken stays taken, and the window
        // stops where it got to, so the next fetch asks for the rest rather than starting over.
        notes.add(w.reference() + ": " + e.getMessage());
        repo.fetched(
            tenantId, w.issueDate() == null ? s.fetchedTo() : w.issueDate(), e.getMessage());
        return new Fetch(start, end, waiting.size(), received, already, refused, notes);
      } catch (ApiException e) {
        refused++;
        notes.add(w.reference() + ": " + e.getMessage());
        LOG.log(Level.INFO, "KSeF invoice {0} was not taken: {1}", w.reference(), e.getMessage());
      }
    }
    repo.fetched(
        tenantId,
        end,
        waiting.isEmpty() ? "nothing was waiting" : received + " taken of " + waiting.size());
    return new Fetch(start, end, waiting.size(), received, already, refused, notes);
  }

  /** The country the business trades in, for a screen that wants to say whether this applies. */
  public String country(UUID tenantId) {
    return profiles.requireCountry(tenantId);
  }

  private static String upper(String s) {
    return s == null ? "" : s.strip().toUpperCase(Locale.ROOT);
  }

  private static String strip(String s) {
    if (s == null) return null;
    String out = s.replaceAll("[^0-9]", "");
    return out.isEmpty() ? null : out;
  }
}
