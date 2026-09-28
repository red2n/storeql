package com.storeql.purchase.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Whether a caller may commit the business to a given amount, in a given currency.
 *
 * <p>Until this existed, anyone holding any staff role could submit a purchase order for any sum:
 * {@code /purchase-orders} is not under {@code /admin/}, so the authorisation filter's default-deny
 * asked only for "some staff role", and nothing anywhere compared the figure to the person.
 *
 * <p><b>The ceiling is per currency, and that is not a detail.</b> A ceiling of 5,000 is roughly
 * £25 against the yen and roughly £13,000 against the dinar, so one currency's ceiling is never
 * applied to another's figure as if they were the same. Since 03.x a business may keep its own
 * exchange rates, and an order in a currency with no ceiling is measured at the translated figure
 * in the home currency — the rate the business set, never one the platform guessed. Applying one
 * currency's ceiling to another does not degrade gracefully; it either blocks every order from an
 * overseas supplier or grants a hundred times the intended authority, and which one happens depends
 * on nothing but where the tenant buys. That is SJ-D18's inverted sign with money attached.
 *
 * <p><b>Measured on the net.</b> VAT is recoverable for a VAT-registered business, so it is not
 * spend — a £5,000 authority means five thousand pounds of goods, not £4,166 of goods and £834 the
 * business gets back. It is also the figure purchase-svc can always compute from its own data,
 * where VAT depends on a rate table another service owns.
 *
 * <p>A pure function over an amount, a currency, a role set and a limit table, for the reason
 * SJ-D20 gave. Every case below is a unit test that needs no database, no clock and no tenant.
 *
 * @param authorised whether the caller may submit this order without anyone else's agreement
 * @param role the role the decision was made under — the caller's most generous, or null if none
 *     applies
 * @param ceiling the authority that role holds in this currency; null when unlimited or when none
 *     applies
 * @param unlimited whether {@code role} holds unlimited authority in this currency
 * @param reason why approval is needed, phrased for the person who has to act on it; null when
 *     authorised
 */
public record SpendAuthority(
    boolean authorised,
    String role,
    BigDecimal ceiling,
    boolean unlimited,
    String reason,
    Translation translation) {

  /**
   * An order's net translated into the home currency at the business's rate (03.x): what a ceiling
   * declared in the home currency is measured against when the order's own currency has none.
   */
  public record Translation(BigDecimal homeAmount, String homeCurrency, BigDecimal rate) {}

  /** A decision made in the order's own currency, with nothing translated. */
  public SpendAuthority(
      boolean authorised, String role, BigDecimal ceiling, boolean unlimited, String reason) {
    this(authorised, role, ceiling, unlimited, reason, null);
  }

  /**
   * As {@link #decide(BigDecimal, String, Collection, Map)}, but when the order's currency has no
   * ceiling of its own and a translation into the home currency is at hand, the decision is made in
   * the home currency at the translated figure — and says so. Without a translation, an
   * unconfigured currency still fails closed: a rate the business has not set is not a rate.
   *
   * @param translation the net in the home currency at the business's rate, or null
   */
  public static SpendAuthority decide(
      BigDecimal totalNet,
      String currency,
      Collection<String> roles,
      Map<String, Map<String, BigDecimal>> limits,
      Translation translation) {
    if (limits == null || limits.isEmpty() || translation == null) {
      return decide(totalNet, currency, roles, limits);
    }
    String cur = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    Map<String, BigDecimal> own = limits.get(cur);
    Map<String, BigDecimal> home = limits.get(translation.homeCurrency());
    if ((own != null && !own.isEmpty()) || home == null || home.isEmpty()) {
      return decide(totalNet, currency, roles, limits);
    }
    SpendAuthority inHome =
        decide(translation.homeAmount(), translation.homeCurrency(), roles, limits);
    String said =
        cur
            + " "
            + (totalNet == null ? "?" : totalNet.toPlainString())
            + " translated to "
            + translation.homeCurrency()
            + " "
            + translation.homeAmount().toPlainString()
            + " at "
            + translation.rate().stripTrailingZeros().toPlainString()
            + " "
            + translation.homeCurrency()
            + " per "
            + cur;
    return new SpendAuthority(
        inHome.authorised(),
        inHome.role(),
        inHome.ceiling(),
        inHome.unlimited(),
        inHome.reason() == null ? null : inHome.reason() + " (" + said + ")",
        translation);
  }

  /**
   * Decides whether {@code totalNet} is within the caller's own authority.
   *
   * <p>The caller's <em>most generous</em> applicable role decides, not the first or the last. A
   * user holding both MANAGER and STOREKEEPER should be able to do everything a manager can; making
   * that depend on iteration order would be a bug that appears only for multi-role users, which is
   * the hardest kind to notice.
   *
   * @param totalNet the order's net value; a null or negative total is treated as needing approval
   *     rather than as free, because an order whose value is unknown is exactly the one not to wave
   *     through
   * @param currency the order's ISO 4217 currency
   * @param roles the caller's roles from the verified JWT
   * @param limits currency to role to ceiling, from configuration; empty switches approval off
   * @return the decision, carrying the reason when approval is required
   */
  public static SpendAuthority decide(
      BigDecimal totalNet,
      String currency,
      Collection<String> roles,
      Map<String, Map<String, BigDecimal>> limits) {

    // Approval is off entirely. Submission behaves as it did before this feature existed, which is
    // the only safe default for tenants already trading.
    if (limits == null || limits.isEmpty()) {
      return new SpendAuthority(true, null, null, false, null);
    }

    String cur = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    Map<String, BigDecimal> byRole = limits.get(cur);

    // Fail closed on an unconfigured currency. The alternative — treating "no limits here" as
    // unlimited — means enabling limits for sterling leaves a hole shaped exactly like the first
    // order from an overseas supplier, which is the order nobody reviewed.
    if (byRole == null || byRole.isEmpty()) {
      return new SpendAuthority(
          false,
          null,
          null,
          false,
          "no purchase authority is configured for " + cur + ", so this order needs approval");
    }

    String bestRole = null;
    BigDecimal bestCeiling = null;
    boolean bestUnlimited = false;
    for (String raw : roles) {
      String role = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
      if (!byRole.containsKey(role)) continue;
      BigDecimal ceiling = byRole.get(role);
      if (ceiling == null) {
        // Unlimited beats every finite ceiling, so there is nothing left to compare.
        bestRole = role;
        bestCeiling = null;
        bestUnlimited = true;
        break;
      }
      if (!bestUnlimited && (bestCeiling == null || ceiling.compareTo(bestCeiling) > 0)) {
        bestRole = role;
        bestCeiling = ceiling;
      }
    }

    if (bestRole == null) {
      return new SpendAuthority(
          false,
          null,
          null,
          false,
          "none of your roles holds purchase authority in "
              + cur
              + ", so this order needs approval");
    }
    if (bestUnlimited) {
      return new SpendAuthority(true, bestRole, null, true, null);
    }
    // An unknown total is not a small one. Nothing should be waved through on the strength of a
    // figure nobody has.
    if (totalNet == null) {
      return new SpendAuthority(
          false, bestRole, bestCeiling, false, "the order has no total, so it needs approval");
    }
    if (totalNet.compareTo(bestCeiling) <= 0) {
      return new SpendAuthority(true, bestRole, bestCeiling, false, null);
    }
    return new SpendAuthority(
        false,
        bestRole,
        bestCeiling,
        false,
        "this order's net value of "
            + totalNet.toPlainString()
            + " "
            + cur
            + " is above your authority of "
            + bestCeiling.toPlainString()
            + " "
            + cur);
  }
}
