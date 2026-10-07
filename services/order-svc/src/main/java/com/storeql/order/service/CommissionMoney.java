package com.storeql.order.service;

import com.storeql.order.client.TenantClient.SchemeTerms;
import com.storeql.service.Fx;
import com.storeql.service.FxRates;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a band's commission is worth on a statement, which is counted in one currency.
 *
 * <p>A percentage of the net is in the currency the net was counted in — the statement's own. A
 * per-unit amount is in the currency its arrangement names, which tenant-svc lets a business set to
 * any ISO 4217 code. When that is not the statement's, the commission is translated at the
 * business's own rates ({@link FxRates}, home units per one unit of the other currency), through
 * {@link Fx} and rounded once, half up, to the statement currency's minor units; with no rate it is
 * refused by the caller, never guessed. Pure: no rates are fetched here.
 */
final class CommissionMoney {

  private CommissionMoney() {}

  /**
   * One band's commission as a statement keeps it.
   *
   * @param commission in the statement's currency, at its own minor units
   * @param rateCurrency the currency a per-unit rate — and what it earned — is in; null for a
   *     percentage, and for a stretch under no arrangement
   * @param ratedCommission what the band earned in {@code rateCurrency}, as rated, when it was
   *     translated; null when nothing was
   */
  record Stated(BigDecimal commission, String rateCurrency, BigDecimal ratedCommission) {}

  /**
   * The currency a per-unit arrangement pays in, or null when the stretch was not under one.
   *
   * @param schemeId the stretch's arrangement, or null for none
   * @param schemes what each arrangement pays in, by id
   * @return the per-unit currency, upper case; null for a percentage or no arrangement
   * @throws IllegalStateException when the stretch names an arrangement the list does not hold,
   *     which the caller reads as arrangements it could not read
   */
  static String rateCurrency(UUID schemeId, Map<UUID, SchemeTerms> schemes) {
    if (schemeId == null) return null;
    SchemeTerms terms = schemes.get(schemeId);
    if (terms == null) throw new IllegalStateException("scheme " + schemeId + " is not listed");
    return terms.perUnit() && terms.currency() != null
        ? terms.currency().toUpperCase(Locale.ROOT)
        : null;
  }

  /**
   * An amount in one currency stated in another, at the business's rates, rounded once, half up, to
   * the target's minor units. Never through a rounded figure in between: a cross translation
   * multiplies into home units unrounded and divides out once.
   *
   * @param amount the amount in {@code from}
   * @param from the currency it is in
   * @param to the currency it is stated in
   * @param rates the business's home currency and its rates
   * @return the amount in {@code to}; empty when either currency other than home has no rate
   */
  static Optional<BigDecimal> translate(
      BigDecimal amount, String from, String to, FxRates.Table rates) {
    String source = from.toUpperCase(Locale.ROOT);
    String target = to.toUpperCase(Locale.ROOT);
    if (source.equals(target)) {
      return Optional.of(amount.setScale(Fx.minorUnits(target), RoundingMode.HALF_UP));
    }
    String home = rates.home().toUpperCase(Locale.ROOT);
    Fx.Rate sourceRate = rates.rates().get(source);
    Fx.Rate targetRate = rates.rates().get(target);
    if (target.equals(home)) {
      return sourceRate == null
          ? Optional.empty()
          : Optional.of(Fx.toHome(amount, sourceRate.rate(), target));
    }
    if (targetRate == null) return Optional.empty();
    if (source.equals(home)) return Optional.of(Fx.fromHome(amount, targetRate.rate(), target));
    if (sourceRate == null) return Optional.empty();
    return Optional.of(Fx.fromHome(amount.multiply(sourceRate.rate()), targetRate.rate(), target));
  }
}
