package com.storeql.customer.dto;

import jakarta.json.JsonArray;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Request/response DTOs for customer-svc. tenant_id never appears in requests — it comes from the
 * JWT/TenantContext.
 */
public final class Dtos {

  private Dtos() {}

  // ── requests ─────────────────────────────────────────────────────────────────

  @Schema(name = "RegisterCustomerRequest", description = "Customer registration.")
  public record RegisterCustomerRequest(
      @Schema(description = "Unique (per tenant) login/contact email.") @NotBlank @Email
          String email,
      String phone,
      @NotBlank String firstName,
      @NotBlank String lastName,
      @Schema(description = "Date of birth, ISO-8601 yyyy-MM-dd.") String dob,
      String gender,
      @Schema(description = "True if the customer consented to GDPR data processing.")
          Boolean gdprConsent) {}

  @Schema(name = "UpdateCustomerRequest", description = "Update a customer's profile fields.")
  public record UpdateCustomerRequest(
      @Size(max = 32) String phone,
      @NotBlank @Size(max = 100) String firstName,
      @NotBlank @Size(max = 100) String lastName,
      @Schema(description = "Date of birth, ISO-8601 yyyy-MM-dd.") String dob,
      @Size(max = 20) String gender,
      @Schema(description = "True if the customer consented to GDPR data processing.")
          Boolean gdprConsent,
      @Schema(
              description =
                  "The language their messages are written in, ISO 639 (en, pl, hi…). Left out, it"
                      + " is unchanged; empty clears it, and the shop's own language is used.")
          @jakarta.validation.constraints.Pattern(regexp = "([a-zA-Z]{2,3})?")
          String preferredLanguage) {}

  @Schema(name = "AddAddressRequest", description = "Add or replace a customer address.")
  public record AddAddressRequest(
      @Schema(description = "HOME, WORK, or other address type.") @NotBlank @Size(max = 20)
          String type,
      @NotBlank @Size(max = 120) String line1,
      @Size(max = 120) String line2,
      @Size(max = 120) String city,
      @Size(max = 120) String state,
      @NotBlank @Size(max = 120) String country,
      @Size(max = 20) String pincode,
      @Schema(description = "True to mark this the customer's default address.")
          Boolean isDefault) {}

  @Schema(name = "EarnPointsRequest", description = "Manually award loyalty points.")
  public record EarnPointsRequest(
      @Schema(description = "Points to award; must be positive.") @NotNull @Positive
          BigDecimal points,
      @Schema(description = "UUID of the order this award relates to, if any.") String orderId,
      String reason) {}

  @Schema(name = "RedeemPointsRequest", description = "Redeem loyalty points.")
  public record RedeemPointsRequest(
      @Schema(description = "Points to redeem; must be positive and not exceed the balance.")
          @NotNull
          @Positive
          BigDecimal points,
      @Schema(description = "UUID of the order this redemption relates to, if any.") String orderId,
      String reason) {}

  @Schema(name = "AdjustPointsRequest", description = "Manual correction to a loyalty balance.")
  public record AdjustPointsRequest(
      @Schema(description = "Signed adjustment amount; may be negative.") @NotNull
          BigDecimal points,
      String reason) {}

  @Schema(name = "IssueStoreCreditRequest", description = "Issue store credit to a customer.")
  public record IssueStoreCreditRequest(
      @Schema(description = "Amount to issue; must be positive.") @NotNull @Positive
          BigDecimal amount,
      @Schema(description = "ISO currency code; the tenant's own when omitted.") String currency,
      @Schema(description = "UUID of the order this issuance relates to, if any.") String orderId,
      String reason) {}

  @Schema(name = "RedeemStoreCreditRequest", description = "Redeem a customer's store credit.")
  public record RedeemStoreCreditRequest(
      @Schema(description = "Amount to redeem; must be positive and not exceed the balance.")
          @NotNull
          @Positive
          BigDecimal amount,
      @Schema(description = "ISO currency code; the tenant's own when omitted.") String currency,
      @Schema(
              description =
                  "UUID of the order this redemption relates to; makes the redemption idempotent"
                      + " per order.")
          String orderId,
      String reason) {}

  // ── responses ────────────────────────────────────────────────────────────────

  @Schema(name = "CustomerResponse", description = "A customer profile.")
  public record CustomerResponse(
      String id,
      @Schema(
              description =
                  "The login this record is linked to, when a shopper has claimed it (SJ-D44); null for a till-only customer.")
          String loginId,
      String email,
      String phone,
      String firstName,
      String lastName,
      @Schema(description = "Date of birth, ISO-8601 yyyy-MM-dd.") String dob,
      String gender,
      @Schema(description = "ACTIVE or ANONYMIZED.") String status,
      @Schema(description = "Timestamp GDPR consent was recorded; null if not given.")
          String gdprConsentAt,
      String createdAt,
      String updatedAt,
      @Schema(description = "The language their messages are written in; null when not said")
          String preferredLanguage,
      @Schema(
              description =
                  "phone normalised to E.164 against the business's own countries; null"
                      + " when phone is null or none of them parse it.")
          String phoneE164) {}

  @Schema(name = "AddressResponse", description = "A customer address.")
  public record AddressResponse(
      String id,
      String customerId,
      @Schema(description = "HOME, WORK, or other address type.") String type,
      String line1,
      String line2,
      String city,
      String state,
      String country,
      String pincode,
      @Schema(description = "True if this is the customer's default address.") boolean isDefault,
      String createdAt) {}

  @Schema(name = "LoyaltyAccountResponse", description = "A customer's loyalty account.")
  public record LoyaltyAccountResponse(
      String customerId,
      @Schema(description = "Current redeemable points balance.") BigDecimal pointsBalance,
      @Schema(description = "Total points ever earned, never decremented.")
          BigDecimal lifetimePoints,
      @Schema(description = "The tier the qualifying points reach under the programme.")
          String tier,
      @Schema(description = "Since when the customer has held the tier.") String tierSince,
      @Schema(
              description =
                  "What counts towards the tier: lifetime points, or the points earned within the"
                      + " programme's qualifying window.")
          BigDecimal qualifyingPoints,
      @Schema(description = "The earn multiplier the tier gives on a sale.") BigDecimal multiplier,
      @Schema(description = "The next tier and how far it is; absent at the top.")
          NextTierResponse nextTier,
      @Schema(
              description =
                  "Points that die within thirty days, and the first day any do; absent when none.")
          ExpiringSoonResponse expiringSoon,
      @Schema(
              description =
                  "Months a point lives under the programme; absent when points never expire.")
          Integer expiryMonths) {}

  @Schema(name = "NextTierResponse")
  public record NextTierResponse(String name, BigDecimal threshold, BigDecimal pointsToGo) {}

  @Schema(name = "ExpiringSoonResponse")
  public record ExpiringSoonResponse(BigDecimal points, String on) {}

  @Schema(name = "TierResponse")
  public record TierResponse(
      @Schema(description = "Capitals, digits and underscores; e.g. GOLD.") String name,
      @Schema(description = "Qualifying points that reach it; the first tier's is zero.")
          BigDecimal threshold,
      @Schema(description = "Points per base point on a sale: 1 to 10.") BigDecimal multiplier) {}

  @Schema(
      name = "LoyaltyProgrammeResponse",
      description = "The business's loyalty programme, or the platform's default.")
  public record LoyaltyProgrammeResponse(
      @Schema(description = "Months a point lives; absent for never.") Integer expiryMonths,
      @Schema(description = "Months of earning that count towards a tier; absent for a lifetime.")
          Integer qualifyingMonths,
      List<TierResponse> tiers,
      String reason,
      String setBy,
      String setAt,
      @Schema(description = "True when the business never set a programme of its own.")
          boolean isDefault) {}

  @Schema(name = "TierRequest")
  public record TierRequest(
      @NotBlank String name,
      @NotNull BigDecimal threshold,
      @Schema(description = "1 when absent.") BigDecimal multiplier) {}

  @Schema(name = "SetLoyaltyProgrammeRequest", description = "The programme to put in force.")
  public record SetLoyaltyProgrammeRequest(
      @Schema(description = "1 to 120; absent for never.") Integer expiryMonths,
      @Schema(description = "1 to 36; absent for a lifetime.") Integer qualifyingMonths,
      @NotNull @Size(min = 1, max = 6) List<TierRequest> tiers,
      @NotBlank String reason) {}

  @Schema(name = "ExpiryRunResponse", description = "What a loyalty sweep did.")
  public record ExpiryRunResponse(
      @Schema(description = "Customers whose points died.") int customers,
      @Schema(description = "Points that died.") BigDecimal points,
      @Schema(description = "Accounts that changed tier.") int retiered) {}

  @Schema(
      name = "LoyaltyLedgerEntryResponse",
      description = "One append-only entry in a customer's loyalty ledger.")
  public record LoyaltyLedgerEntryResponse(
      String id,
      @Schema(description = "EARN, REDEEM, EXPIRE or ADJUST.") String type,
      @Schema(description = "Signed points delta for this entry.") BigDecimal points,
      @Schema(description = "Points balance immediately after this entry.") BigDecimal balanceAfter,
      String orderId,
      String reason,
      String createdAt) {}

  @Schema(name = "StoreCreditAccountResponse", description = "A customer's store-credit balance.")
  public record StoreCreditAccountResponse(
      String customerId,
      @Schema(description = "Current redeemable store-credit balance.") BigDecimal balance,
      @Schema(description = "ISO currency code.") String currency) {}

  @Schema(
      name = "StoreCreditLedgerEntryResponse",
      description = "One append-only entry in a customer's store-credit ledger.")
  public record StoreCreditLedgerEntryResponse(
      String id,
      @Schema(description = "ISSUE, REDEEM, EXPIRE, or ADJUST.") String type,
      @Schema(description = "Signed amount for this entry; positive issued, negative redeemed.")
          BigDecimal amount,
      @Schema(description = "Balance immediately after this entry.") BigDecimal balanceAfter,
      String currency,
      String orderId,
      String reason,
      String createdAt) {}

  @Schema(
      name = "DataExportResponse",
      description =
          "Everything this shop holds about one person, in one machine-readable document (UK GDPR"
              + " art.20). Assembled rather than stored: the profile, addresses, loyalty and"
              + " store credit come from customer-svc, and the orders from order-svc, which owns"
              + " them.")
  public record DataExportResponse(
      @Schema(description = "When this export was produced, ISO-8601 UTC.") String exportedAt,
      @Schema(description = "The shop the data belongs to.") String tenantId,
      @Schema(
              description =
                  "Who the export is about. A person has up to two ids here: the shop's own"
                      + " customer record, and the login they sign in with. Either may be absent —"
                      + " a walk-in has no login, and a shopper who has only ever browsed has no"
                      + " customer record.")
          ExportSubject subject,
      @Schema(description = "The shop's customer record, or null if it holds none.")
          CustomerResponse profile,
      List<AddressResponse> addresses,
      @Schema(description = "The loyalty account, or null if the person has none.")
          LoyaltyAccountResponse loyalty,
      List<LoyaltyLedgerEntryResponse> loyaltyLedger,
      List<StoreCreditAccountResponse> storeCredit,
      List<StoreCreditLedgerEntryResponse> storeCreditLedger,
      @Schema(description = "What the shop may currently send them, channel by channel.")
          List<MarketingPreferenceResponse> marketingPreferences,
      @Schema(
              description =
                  "Every recorded change to those preferences, newest first — the evidence UK"
                      + " GDPR art.7(1) requires the shop to hold, and therefore part of what it"
                      + " holds about the person.")
          List<MarketingConsentEntryResponse> marketingConsentLog,
      @Schema(
              description =
                  "Every order the person placed at this shop, newest first, exactly as order-svc"
                      + " serves it — including the lines, the delivery address and the totals.")
          JsonArray orders) {}

  @Schema(
      name = "MarketingConsentEntryResponse",
      description = "One recorded change to a person's marketing preferences.")
  public record MarketingConsentEntryResponse(
      String id,
      String channel,
      boolean granted,
      String basis,
      @Schema(
              description =
                  "SIGNUP, CHECKOUT, PREFERENCE_CENTRE, STAFF, UNSUBSCRIBE_LINK or IMPORT.")
          String source,
      @Schema(description = "The wording the person was shown when they agreed.") String notice,
      @Schema(description = "The staff member who acted, when it was not the customer themselves.")
          String actorId,
      String recordedAt) {}

  @Schema(name = "ExportSubject", description = "The two ids a person may be known by here.")
  public record ExportSubject(String customerId, String loginId, String email) {}

  @Schema(
      name = "MarketingPreferenceResponse",
      description = "What this shop may currently send one person on one channel.")
  public record MarketingPreferenceResponse(
      @Schema(description = "EMAIL, SMS, PHONE or POST.") String channel,
      boolean granted,
      @Schema(
              description =
                  "CONSENT, SOFT_OPT_IN (PECR's existing-customer exception) or NONE for an"
                      + " opt-out.")
          String basis,
      String updatedAt) {}

  @Schema(
      name = "SetMarketingPreferencesRequest",
      description =
          "Set what a person may be sent. Channels left out are not touched, so a preference"
              + " centre can save one switch without restating the rest.")
  public record SetMarketingPreferencesRequest(
      @NotNull @Schema(description = "One entry per channel being changed.")
          List<MarketingChannelChoice> channels,
      @Schema(
              description =
                  "The wording the person was shown when they agreed, recorded as the evidence"
                      + " UK GDPR art.7(1) requires. Ignored for an opt-out.")
          String notice) {}

  @Schema(name = "MarketingChannelChoice", description = "One channel's answer.")
  public record MarketingChannelChoice(
      @NotBlank @Schema(description = "EMAIL, SMS, PHONE or POST.") String channel,
      @NotNull Boolean granted,
      @Schema(description = "CONSENT (default) or SOFT_OPT_IN. Ignored for an opt-out.")
          String basis) {}

  @Schema(
      name = "MarketingAllowanceResponse",
      description =
          "Whether one marketing message may lawfully be sent, and the opt-out link it must carry"
              + " if it is (PECR reg.23).")
  public record MarketingAllowanceResponse(
      boolean allowed,
      @Schema(description = "CONSENT, SOFT_OPT_IN, or NONE when nothing permits the send.")
          String basis,
      @Schema(description = "Why a send was refused, for the log; null when allowed.")
          String reason,
      @Schema(
              description =
                  "A single-customer opt-out token for the unsubscribe link. Minted per send, and"
                      + " null when the send is refused.")
          String unsubscribeToken) {}

  @Schema(
      name = "UnsubscribeRequest",
      description = "The one-click opt-out behind the link in a marketing message.")
  public record UnsubscribeRequest(
      @NotBlank String token,
      @Schema(
              description =
                  "One channel to stop, or null to stop every channel — which is what an"
                      + " unsubscribe link means and what art.21(3) requires of an objection.")
          String channel) {}

  @Schema(name = "CustomerListResponse", description = "Cursor-paginated page of customers.")
  public record CustomerListResponse(
      List<CustomerResponse> items,
      @Schema(
              description =
                  "Cursor to pass as ?after= for the next page; null if this is the"
                      + " last page.")
          String nextCursor) {}
}
