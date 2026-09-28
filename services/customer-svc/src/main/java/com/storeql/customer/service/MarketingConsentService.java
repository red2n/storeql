package com.storeql.customer.service;

import com.storeql.customer.domain.Domain.Customer;
import com.storeql.customer.domain.Domain.MarketingConsentEntry;
import com.storeql.customer.domain.Domain.MarketingPreference;
import com.storeql.customer.dto.Dtos.MarketingAllowanceResponse;
import com.storeql.customer.dto.Dtos.MarketingChannelChoice;
import com.storeql.customer.dto.Dtos.SetMarketingPreferencesRequest;
import com.storeql.customer.repo.CustomerRepository;
import com.storeql.ids.Ids;
import com.storeql.service.CapabilityTokens;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Marketing consent, preferences and the unsubscribe link (PECR reg.22/23, UK GDPR art.7(1) and
 * art.21(3)), gated by the MARKETING purpose (DPDP Act s.6): {@link PrivacyService} decides whether
 * that purpose currently permits a channel; this class enforces it everywhere a channel can be
 * switched on.
 *
 * <p>Kept apart from {@link CustomerService} because it answers a different question. That one is
 * about who a person is to the shop; this one is about what the shop is allowed to send them, which
 * has its own law, its own evidence requirement, and its own way of being withdrawn.
 *
 * <p>The rule this service exists to enforce is that <strong>silence is not consent</strong>: a
 * channel with no preference row is refused, not permitted. Everything else follows from that.
 */
@ApplicationScoped
public class MarketingConsentService {

  private static final Logger LOG = System.getLogger(MarketingConsentService.class.getName());

  /** Hard cap on the evidence trail an export or a screen reads at once. */
  private static final int CONSENT_LOG_LIMIT = 1000;

  /** The most channels one batch of the start-up reconciliation corrects at a time. */
  private static final int RECONCILE_BATCH = 500;

  /** The most tenants one reconciliation run looks at; generous, and a safety bound. */
  private static final int MAX_TENANTS_PER_RECONCILE_RUN = 10_000;

  private static final Set<String> CHANNELS =
      Set.of(
          MarketingPreference.CHANNEL_EMAIL,
          MarketingPreference.CHANNEL_SMS,
          MarketingPreference.CHANNEL_PHONE,
          MarketingPreference.CHANNEL_POST);

  @Inject CustomerRepository repo;
  @Inject PrivacyService privacy;

  /**
   * What this shop may currently send one person.
   *
   * @param tenantId owning tenant
   * @param customerId the customer to read
   * @return one entry per channel ever decided; a channel with no entry has no consent
   */
  public List<MarketingPreference> preferences(UUID tenantId, UUID customerId) {
    return repo.listPreferences(tenantId, customerId);
  }

  /**
   * The evidence behind those preferences (UK GDPR art.7(1)).
   *
   * @param tenantId owning tenant
   * @param customerId the customer to read
   * @return every recorded change, newest first
   */
  public List<MarketingConsentEntry> consentLog(UUID tenantId, UUID customerId) {
    return repo.listConsentLog(tenantId, customerId, CONSENT_LOG_LIMIT);
  }

  /**
   * Records what a person has agreed to, and the evidence for it.
   *
   * <p>Channels the request does not name are left alone: a preference centre saving one switch
   * must not silently withdraw the others.
   *
   * @param tenantId owning tenant
   * @param customer the customer whose preferences these are; an erased record refuses
   * @param req the channels being changed and the wording the person was shown
   * @param source how the change reached the shop, e.g. the preference centre or a staff member
   * @param actorId the staff member acting, or {@code null} when the customer did it themselves
   * @return the preferences as they now stand
   * @throws ApiException {@code MARKETING_CHANNEL_UNKNOWN} (400) for a channel that is not one of
   *     EMAIL, SMS, PHONE, POST; {@code CUSTOMER_ANONYMIZED} (409) for an erased customer; {@code
   *     MARKETING_PURPOSE_NOT_GRANTED} (409) for a channel being switched on while the MARKETING
   *     purpose gate refuses it — nothing in the request is applied when this is thrown
   */
  public List<MarketingPreference> setPreferences(
      UUID tenantId,
      Customer customer,
      SetMarketingPreferencesRequest req,
      String source,
      UUID actorId) {
    if (Customer.STATUS_ANONYMIZED.equals(customer.status())) {
      throw ApiException.conflict(
          "CUSTOMER_ANONYMIZED", "Customer has been erased and can no longer be marketed to");
    }
    if (req.channels() == null || req.channels().isEmpty()) {
      throw ApiException.badRequest("MARKETING_NO_CHANNELS", "name at least one channel");
    }
    Instant now = Instant.now();
    List<MarketingConsentEntry> entries = new ArrayList<>();
    // Computed at most once per call, and only if a channel is actually being switched on — a
    // request that only withdraws channels never needs the purpose gate at all.
    Boolean blocked = null;
    for (MarketingChannelChoice choice : req.channels()) {
      String channel = choice.channel().trim().toUpperCase(Locale.ROOT);
      if (!CHANNELS.contains(channel)) {
        throw ApiException.badRequest(
            "MARKETING_CHANNEL_UNKNOWN", "unknown marketing channel: " + choice.channel());
      }
      boolean granted = Boolean.TRUE.equals(choice.granted());
      if (granted) {
        if (blocked == null) {
          blocked = purposeGateBlocks(tenantId, customer.id());
        }
        if (blocked) {
          throw ApiException.conflict(
              "MARKETING_PURPOSE_NOT_GRANTED",
              "the MARKETING purpose is not granted, so no channel may be switched on until it"
                  + " is");
        }
      }
      entries.add(
          new MarketingConsentEntry(
              Ids.newId(),
              tenantId,
              customer.id(),
              channel,
              granted,
              granted ? basisOf(choice.basis()) : MarketingPreference.BASIS_NONE,
              source,
              granted ? req.notice() : null,
              actorId,
              now));
    }
    repo.recordConsent(entries);
    return repo.listPreferences(tenantId, customer.id());
  }

  /**
   * Whether the MARKETING purpose gate refuses a channel right now, never throwing: a business
   * whose country or jurisdiction rules cannot be read right now cannot demonstrate the purpose
   * stands granted, so this refuses exactly as it does when the purpose is genuinely unanswered —
   * <em>silence is not consent</em>, applied to the gate's own availability, not only to the
   * person's answer.
   *
   * @param tenantId owning tenant
   * @param customerId the person a channel would be switched on, or sent to
   * @return {@code true} when the gate refuses; never throws
   */
  public boolean purposeGateBlocks(UUID tenantId, UUID customerId) {
    try {
      return privacy.marketingChannelBlocked(tenantId, customerId);
    } catch (ApiException e) {
      LOG.log(
          Level.WARNING,
          "marketing purpose gate unreadable for tenant {0}: {1}",
          tenantId,
          e.getMessage());
      return true;
    }
  }

  private static String basisOf(String requested) {
    if (requested == null || requested.isBlank()) {
      return MarketingPreference.BASIS_CONSENT;
    }
    String basis = requested.trim().toUpperCase(Locale.ROOT);
    if (MarketingPreference.BASIS_CONSENT.equals(basis)
        || MarketingPreference.BASIS_SOFT_OPT_IN.equals(basis)) {
      return basis;
    }
    throw ApiException.badRequest(
        "MARKETING_BASIS_UNKNOWN",
        "a marketing basis is CONSENT or SOFT_OPT_IN; an opt-out records neither");
  }

  /**
   * Whether one marketing message may lawfully be sent, and the opt-out token it must carry.
   *
   * <p>notification-svc asks this before every marketing send. An unknown channel, a customer with
   * no preference, an opt-out, or an erased record all answer no — the default is refusal, because
   * a shop that cannot show consent does not have it.
   *
   * <p>A fresh token is minted per allowed send, and only its hash is stored. Per-send rather than
   * per-customer so one leaked message cannot be replayed against a link that outlives it.
   *
   * @param tenantId owning tenant
   * @param customerId the person to be contacted
   * @param channelRaw the channel the message would go out on
   * @return the decision, with the unsubscribe token when the answer is yes
   */
  public MarketingAllowanceResponse allowance(UUID tenantId, UUID customerId, String channelRaw) {
    String channel = channelRaw == null ? "" : channelRaw.trim().toUpperCase(Locale.ROOT);
    if (!CHANNELS.contains(channel)) {
      return new MarketingAllowanceResponse(
          false, MarketingPreference.BASIS_NONE, "unknown channel: " + channelRaw, null);
    }
    Customer customer = repo.findById(tenantId, customerId).orElse(null);
    if (customer == null || Customer.STATUS_ANONYMIZED.equals(customer.status())) {
      return new MarketingAllowanceResponse(
          false, MarketingPreference.BASIS_NONE, "no active customer record", null);
    }
    MarketingPreference pref = repo.findPreference(tenantId, customerId, channel).orElse(null);
    if (pref == null) {
      return new MarketingAllowanceResponse(
          false, MarketingPreference.BASIS_NONE, "no consent recorded for this channel", null);
    }
    if (!pref.granted()) {
      return new MarketingAllowanceResponse(
          false, MarketingPreference.BASIS_NONE, "the customer has opted out", null);
    }
    // Re-checked live, not only at grant time: a channel granted lawfully before the
    // business came under a per-purpose consent law is not lawful to send on once it does, and
    // the cascade above already covers a purpose withdrawn since — this is the rest of it.
    if (purposeGateBlocks(tenantId, customerId)) {
      return new MarketingAllowanceResponse(
          false, MarketingPreference.BASIS_NONE, "the MARKETING purpose is not granted", null);
    }
    String token = mintToken();
    repo.storeUnsubscribeToken(tenantId, customerId, hash(token));
    return new MarketingAllowanceResponse(true, pref.basis(), null, token);
  }

  /**
   * Acts on the link in a marketing message: stops one channel, or every channel.
   *
   * <p>Unauthenticated by design — the token is the capability, and requiring a sign-in to stop
   * marketing would fail PECR reg.23's "simple means of refusing". A token that has been used
   * before still works: an objection does not expire, and a second click must not answer "invalid".
   *
   * @param token the raw token from the link
   * @param channelRaw one channel to stop, or {@code null} to stop all of them
   * @return how many channels were stopped
   * @throws ApiException {@code UNSUBSCRIBE_TOKEN_INVALID} (404) when the token is not one this
   *     shop issued
   */
  public int unsubscribe(String token, String channelRaw) {
    String tokenHash = hash(token);
    var subject =
        repo.findUnsubscribeSubject(tokenHash)
            .orElseThrow(
                () ->
                    ApiException.notFound(
                        "UNSUBSCRIBE_TOKEN_INVALID", "this opt-out link is not one we issued"));
    List<String> channels;
    if (channelRaw == null || channelRaw.isBlank()) {
      channels = List.copyOf(CHANNELS);
    } else {
      String channel = channelRaw.trim().toUpperCase(Locale.ROOT);
      if (!CHANNELS.contains(channel)) {
        throw ApiException.badRequest(
            "MARKETING_CHANNEL_UNKNOWN", "unknown marketing channel: " + channelRaw);
      }
      channels = List.of(channel);
    }
    Instant now = Instant.now();
    List<MarketingConsentEntry> entries =
        channels.stream()
            .map(
                channel ->
                    new MarketingConsentEntry(
                        Ids.newId(),
                        subject.tenantId(),
                        subject.customerId(),
                        channel,
                        false,
                        MarketingPreference.BASIS_NONE,
                        MarketingConsentEntry.SOURCE_UNSUBSCRIBE_LINK,
                        null,
                        null,
                        now))
            .toList();
    int stopped = repo.recordConsent(entries);
    repo.markUnsubscribeTokenUsed(tokenHash);
    return stopped;
  }

  /**
   * Shared with the pay link a dunning notice carries (21.12): both are a token that <em>is</em>
   * the permission, reaching somebody who cannot be asked to sign in. See {@link CapabilityTokens}
   * for why SHA-256 and not a password hash.
   */
  private static String mintToken() {
    return CapabilityTokens.mint();
  }

  private static String hash(String token) {
    return CapabilityTokens.hash(token);
  }

  // ── start-up reconciliation ────────────────────────────────────────

  /**
   * Fixes every customer, across every tenant, whose MARKETING purpose stands withdrawn but who
   * still has a channel recorded as granted — predating this rule, or from a gap before the cascade
   * covered every path. Tenant by tenant (every write below it is tenant-scoped, {@code tenant_id}
   * first), and within a tenant in batches, so one very large table is never read or held in memory
   * at once; idempotent, so calling this twice in a row does the second time as nothing (each batch
   * stops finding a row once it is fixed).
   *
   * @return how many channels were switched off in total
   */
  public int reconcilePurposeWithdrawals() {
    int total = 0;
    for (UUID tenantId :
        repo.distinctTenantsNeedingMarketingReconciliation(MAX_TENANTS_PER_RECONCILE_RUN)) {
      int fixed;
      do {
        fixed = repo.reconcileMarketingPurposeWithdrawalsBatchForTenant(tenantId, RECONCILE_BATCH);
        total += fixed;
      } while (fixed == RECONCILE_BATCH);
    }
    return total;
  }
}
