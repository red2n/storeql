package com.storeql.events.contract;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * What a gated action measures (intent/approvals.md): money (with its currency and the
 * home-currency equivalent), a quantity, or a percentage. At most one kind is set; none = a gate
 * with no measure.
 */
public record ApprovalValue(
    Optional<BigDecimal> amount,
    Optional<String> currency,
    Optional<BigDecimal> homeAmount,
    Optional<BigDecimal> quantity,
    Optional<BigDecimal> percent) {

  public static final String AMOUNT = "valueAmount";
  public static final String CURRENCY = "valueCurrency";
  public static final String HOME_AMOUNT = "valueHomeAmount";
  public static final String QUANTITY = "valueQuantity";
  public static final String PERCENT = "valuePercent";

  public static ApprovalValue none() {
    return new ApprovalValue(
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  /** {@code homeAmount} may be null when the business has no rate (the gate then fails closed). */
  public static ApprovalValue money(BigDecimal amount, String currency, BigDecimal homeAmount) {
    return new ApprovalValue(
        Optional.of(amount),
        Optional.of(currency),
        Optional.ofNullable(homeAmount),
        Optional.empty(),
        Optional.empty());
  }

  public static ApprovalValue quantity(BigDecimal quantity) {
    return new ApprovalValue(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(quantity),
        Optional.empty());
  }

  public static ApprovalValue percent(BigDecimal percent) {
    return new ApprovalValue(
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(percent));
  }

  JsonFields writeTo(JsonFields f) {
    return f.optNum(AMOUNT, amount.orElse(null))
        .optStr(CURRENCY, currency.orElse(null))
        .optNum(HOME_AMOUNT, homeAmount.orElse(null))
        .optNum(QUANTITY, quantity.orElse(null))
        .optNum(PERCENT, percent.orElse(null));
  }

  static ApprovalValue readFrom(EventReader r) {
    return new ApprovalValue(
        r.optDecimal(AMOUNT),
        r.optString(CURRENCY),
        r.optDecimal(HOME_AMOUNT),
        r.optDecimal(QUANTITY),
        r.optDecimal(PERCENT));
  }
}
