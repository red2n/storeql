package com.storeql.customer.mapper;

import com.storeql.customer.domain.Domain.Customer;
import com.storeql.customer.domain.Domain.CustomerAddress;
import com.storeql.customer.domain.Domain.ExpiringSoon;
import com.storeql.customer.domain.Domain.ExpiryRun;
import com.storeql.customer.domain.Domain.LoyaltyAccount;
import com.storeql.customer.domain.Domain.LoyaltyLedgerEntry;
import com.storeql.customer.domain.Domain.LoyaltyView;
import com.storeql.customer.domain.Domain.StoreCreditAccount;
import com.storeql.customer.domain.LoyaltyProgramme;
import com.storeql.customer.dto.Dtos.AddressResponse;
import com.storeql.customer.dto.Dtos.CustomerResponse;
import com.storeql.customer.dto.Dtos.ExpiringSoonResponse;
import com.storeql.customer.dto.Dtos.ExpiryRunResponse;
import com.storeql.customer.dto.Dtos.LoyaltyAccountResponse;
import com.storeql.customer.dto.Dtos.LoyaltyLedgerEntryResponse;
import com.storeql.customer.dto.Dtos.LoyaltyProgrammeResponse;
import com.storeql.customer.dto.Dtos.NextTierResponse;
import com.storeql.customer.dto.Dtos.StoreCreditAccountResponse;
import com.storeql.customer.dto.Dtos.TierResponse;
import java.math.BigDecimal;
import java.time.Instant;

/** Domain → DTO conversion. Never expose domain records directly over HTTP. */
public final class Mappers {

  private Mappers() {}

  /**
   * Converts a customer profile to its wire form.
   *
   * @param c the customer profile to convert
   * @return its API representation, with ids and timestamps rendered as strings
   */
  public static CustomerResponse toCustomer(Customer c) {
    return new CustomerResponse(
        c.id().toString(),
        c.loginId() == null ? null : c.loginId().toString(),
        c.email(),
        c.phone(),
        c.firstName(),
        c.lastName(),
        c.dob() == null ? null : c.dob().toString(),
        c.gender(),
        c.status(),
        ts(c.gdprConsentAt()),
        ts(c.createdAt()),
        ts(c.updatedAt()),
        c.preferredLanguage(),
        c.phoneE164());
  }

  /**
   * Converts a customer address to its wire form.
   *
   * @param a the customer address to convert
   * @return its API representation
   */
  public static AddressResponse toAddress(CustomerAddress a) {
    return new AddressResponse(
        a.id().toString(),
        a.customerId().toString(),
        a.type(),
        a.line1(),
        a.line2(),
        a.city(),
        a.state(),
        a.country(),
        a.pincode(),
        a.isDefault(),
        ts(a.createdAt()));
  }

  /**
   * Converts a loyalty account to its wire form.
   *
   * @param la the loyalty account to convert
   * @return its API representation: balance, lifetime points and tier
   */
  public static LoyaltyAccountResponse toLoyalty(LoyaltyAccount la) {
    return toLoyalty(new LoyaltyView(la, LoyaltyProgramme.defaults(la.tenantId()), null));
  }

  /** A customer's loyalty under the business's programme: tier, next tier, multiplier, expiry. */
  public static LoyaltyAccountResponse toLoyalty(LoyaltyView v) {
    LoyaltyAccount la = v.account();
    LoyaltyProgramme p = v.programme();
    BigDecimal qualifying = la.qualifyingPoints() == null ? BigDecimal.ZERO : la.qualifyingPoints();
    NextTierResponse next =
        p.nextTier(qualifying)
            .map(
                t ->
                    new NextTierResponse(
                        t.name(), t.threshold(), t.threshold().subtract(qualifying)))
            .orElse(null);
    ExpiringSoon soon = v.expiringSoon();
    return new LoyaltyAccountResponse(
        la.customerId().toString(),
        la.pointsBalance(),
        la.lifetimePoints(),
        la.tier(),
        la.tierSince() == null ? null : la.tierSince().toString(),
        qualifying,
        p.multiplierFor(la.tier()),
        next,
        soon == null ? null : new ExpiringSoonResponse(soon.points(), soon.on().toString()),
        p.expiryMonths());
  }

  public static LoyaltyProgrammeResponse toProgramme(LoyaltyProgramme p) {
    return new LoyaltyProgrammeResponse(
        p.expiryMonths(),
        p.qualifyingMonths(),
        p.tiers().stream()
            .map(t -> new TierResponse(t.name(), t.threshold(), t.multiplier()))
            .toList(),
        p.reason(),
        p.setBy() == null ? null : p.setBy().toString(),
        p.setAt() == null ? null : p.setAt().toString(),
        p.isDefault());
  }

  public static ExpiryRunResponse toExpiryRun(ExpiryRun r) {
    return new ExpiryRunResponse(r.customers(), r.points(), r.retiered());
  }

  /**
   * Converts one append-only loyalty ledger entry to its wire form.
   *
   * @param e the ledger entry to convert
   * @return its API representation, including the balance the entry left behind
   */
  public static LoyaltyLedgerEntryResponse toLedgerEntry(LoyaltyLedgerEntry e) {
    return new LoyaltyLedgerEntryResponse(
        e.id().toString(),
        e.type(),
        e.points(),
        e.balanceAfter(),
        e.orderId() == null ? null : e.orderId().toString(),
        e.reason(),
        ts(e.createdAt()));
  }

  /**
   * Converts one store-credit ledger entry to its wire form.
   *
   * @param e the entry to convert
   * @return its API representation
   */
  public static com.storeql.customer.dto.Dtos.StoreCreditLedgerEntryResponse toStoreCreditEntry(
      com.storeql.customer.domain.Domain.StoreCreditLedgerEntry e) {
    return new com.storeql.customer.dto.Dtos.StoreCreditLedgerEntryResponse(
        e.id().toString(),
        e.type(),
        e.amount(),
        e.balanceAfter(),
        e.currency(),
        e.orderId() == null ? null : e.orderId().toString(),
        e.reason(),
        ts(e.createdAt()));
  }

  /**
   * Converts a store-credit account to its wire form.
   *
   * @param sc the account to convert
   * @return its API representation: balance and the currency it is held in
   */
  public static StoreCreditAccountResponse toStoreCredit(StoreCreditAccount sc) {
    return new StoreCreditAccountResponse(sc.customerId().toString(), sc.balance(), sc.currency());
  }

  /**
   * Converts one channel's marketing preference to its wire form.
   *
   * @param p the preference to convert
   * @return its API representation
   */
  public static com.storeql.customer.dto.Dtos.MarketingPreferenceResponse toMarketingPreference(
      com.storeql.customer.domain.Domain.MarketingPreference p) {
    return new com.storeql.customer.dto.Dtos.MarketingPreferenceResponse(
        p.channel(), p.granted(), p.basis(), ts(p.updatedAt()));
  }

  /**
   * Converts one recorded consent change to its wire form.
   *
   * @param e the entry to convert
   * @return its API representation
   */
  public static com.storeql.customer.dto.Dtos.MarketingConsentEntryResponse toMarketingConsentEntry(
      com.storeql.customer.domain.Domain.MarketingConsentEntry e) {
    return new com.storeql.customer.dto.Dtos.MarketingConsentEntryResponse(
        e.id().toString(),
        e.channel(),
        e.granted(),
        e.basis(),
        e.source(),
        e.notice(),
        e.actorId() == null ? null : e.actorId().toString(),
        ts(e.recordedAt()));
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }
}
