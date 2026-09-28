package com.storeql.customer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Domain records for customer-svc (Customers, Addresses, Loyalty, StoreCredit). */
public final class Domain {

  private Domain() {}

  /**
   * A shop's own record of a person. {@code loginId} is the iam-svc login it belongs to, or {@code
   * null} for a walk-in the till created — a login is global and a customer record is not, so one
   * login has at most one of these per tenant (SJ-D44). {@code firstName}/{@code lastName} are null
   * until someone gives them: a linked login starts with an email and nothing else.
   */
  /**
   * @param phoneE164 {@code phone} normalised to E.164 against the business's own regions; {@code
   *     null} when {@code phone} is null, was typed with no reachable region, or parses under none
   *     of the business's regions. Not the API's business to invent — filled wherever {@code phone}
   *     is written, kept as a fast, exact-match read.
   * @param phoneE164CheckedAt when {@code phoneE164} was last derived against regions that were
   *     actually readable, whatever it found; {@code null} for a row the start-up backfill has not
   *     yet reached, or last reached while the business's regions could not be read. Bookkeeping
   *     only — never rendered over the API.
   */
  public record Customer(
      UUID id,
      UUID tenantId,
      UUID loginId,
      String email,
      String phone,
      String firstName,
      String lastName,
      LocalDate dob,
      String gender,
      String status,
      Instant gdprConsentAt,
      Instant anonymizedAt,
      Instant createdAt,
      Instant updatedAt,
      String preferredLanguage,
      String phoneE164,
      Instant phoneE164CheckedAt) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_ANONYMIZED = "ANONYMIZED";
  }

  /**
   * What a shop may lawfully send one person on one channel, as it stands now. The evidence for it
   * is {@link MarketingConsentEntry}; this is the fast read.
   */
  public record MarketingPreference(
      UUID tenantId,
      UUID customerId,
      String channel,
      boolean granted,
      String basis,
      Instant updatedAt) {

    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_SMS = "SMS";
    public static final String CHANNEL_PHONE = "PHONE";
    public static final String CHANNEL_POST = "POST";

    /** Freely given agreement — PECR reg.22's default route. */
    public static final String BASIS_CONSENT = "CONSENT";

    /** PECR's existing-customer exception: narrower than consent, and worth telling apart. */
    public static final String BASIS_SOFT_OPT_IN = "SOFT_OPT_IN";

    /** No lawful basis: an opt-out is the absence of one, not a kind of one. */
    public static final String BASIS_NONE = "NONE";
  }

  /**
   * One append-only record of consent being given or withdrawn. UK GDPR art.7(1) requires the
   * controller to be able to demonstrate that the person consented, which a current-state row
   * cannot do — so every change writes one of these, with what the person was shown and who acted.
   */
  public record MarketingConsentEntry(
      UUID id,
      UUID tenantId,
      UUID customerId,
      String channel,
      boolean granted,
      String basis,
      String source,
      String notice,
      UUID actorId,
      Instant recordedAt) {

    public static final String SOURCE_SIGNUP = "SIGNUP";
    public static final String SOURCE_CHECKOUT = "CHECKOUT";
    public static final String SOURCE_PREFERENCE_CENTRE = "PREFERENCE_CENTRE";
    public static final String SOURCE_STAFF = "STAFF";
    public static final String SOURCE_UNSUBSCRIBE_LINK = "UNSUBSCRIBE_LINK";
    public static final String SOURCE_IMPORT = "IMPORT";

    /**
     * The cascade: withdrawing the MARKETING purpose switches this channel off in the same step,
     * never disguised as a preference-centre click or a staff act.
     */
    public static final String SOURCE_PURPOSE_WITHDRAWN = "PURPOSE_WITHDRAWN";
  }

  public record CustomerAddress(
      UUID id,
      UUID tenantId,
      UUID customerId,
      String type,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      boolean isDefault,
      Instant createdAt) {

    public static final String TYPE_HOME = "HOME";
    public static final String TYPE_BILLING = "BILLING";
    public static final String TYPE_SHIPPING = "SHIPPING";
  }

  /**
   * A customer's points: the balance they can spend, everything they ever earned, what counts
   * towards their tier under the business's programme (lifetime, or the qualifying window), the
   * tier that reaches, and since when they have held it.
   */
  public record LoyaltyAccount(
      UUID id,
      UUID tenantId,
      UUID customerId,
      BigDecimal pointsBalance,
      BigDecimal lifetimePoints,
      String tier,
      Instant createdAt,
      Instant updatedAt,
      BigDecimal qualifyingPoints,
      Instant tierSince) {

    public static final String TIER_BRONZE = "BRONZE";
    public static final String TIER_SILVER = "SILVER";
    public static final String TIER_GOLD = "GOLD";
    public static final String TIER_PLATINUM = "PLATINUM";
  }

  /** Points that will die soon: how many, and on what day. */
  public record ExpiringSoon(BigDecimal points, Instant on) {}

  /**
   * A customer's loyalty as the API answers it: the account, the programme it sits under (for the
   * next tier and the multiplier), and the points about to expire, if any.
   */
  public record LoyaltyView(
      LoyaltyAccount account, LoyaltyProgramme programme, ExpiringSoon expiringSoon) {}

  /** A tier a customer moved to, and from where. */
  public record TierChange(
      UUID tenantId,
      UUID customerId,
      String fromTier,
      String toTier,
      BigDecimal qualifyingPoints) {}

  /** What an expiry sweep did. */
  public record ExpiryRun(int customers, BigDecimal points, int retiered) {}

  /** Points that died for one customer. */
  public record Expired(UUID tenantId, UUID customerId, BigDecimal points, Instant expiredAt) {}

  public record LoyaltyLedgerEntry(
      UUID id,
      UUID tenantId,
      UUID customerId,
      String type,
      BigDecimal points,
      BigDecimal balanceAfter,
      UUID orderId,
      String reason,
      Instant createdAt) {

    public static final String TYPE_EARN = "EARN";
    public static final String TYPE_REDEEM = "REDEEM";
    public static final String TYPE_EXPIRE = "EXPIRE";
    public static final String TYPE_ADJUST = "ADJUST";
  }

  public record StoreCreditAccount(
      UUID id,
      UUID tenantId,
      UUID customerId,
      BigDecimal balance,
      String currency,
      Instant createdAt,
      Instant updatedAt) {}

  public record StoreCreditLedgerEntry(
      UUID id,
      UUID tenantId,
      UUID customerId,
      String type,
      BigDecimal amount,
      BigDecimal balanceAfter,
      String currency,
      UUID orderId,
      String reason,
      Instant createdAt) {

    public static final String TYPE_ISSUE = "ISSUE";
    public static final String TYPE_REDEEM = "REDEEM";
    public static final String TYPE_EXPIRE = "EXPIRE";
    public static final String TYPE_ADJUST = "ADJUST";
  }
}
