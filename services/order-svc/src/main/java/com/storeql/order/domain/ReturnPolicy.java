package com.storeql.order.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A business's return policy (intent/return-controls.md): how long after handover goods may come
 * back, and how much a cashier may refund alone. Pure: it decides which rules a return falls
 * outside of and never reads or writes anything, so the rules can be tested without a database.
 *
 * <p>Ceilings are in the business's home currency; no amount is assumed here. A business that has
 * never set a policy gets {@link #DEFAULT}: thirty days, no cashier ceiling, no-receipt returns
 * off.
 *
 * @param windowDays calendar days from handover in which a return needs no manager
 * @param cashierCeiling the most a cashier may refund on one return, home currency; null for no
 *     ceiling
 * @param noReceiptAllowed whether a return with no receipt may be taken at all
 * @param noReceiptCeiling the most a no-receipt return may refund, home currency; null for none
 */
public record ReturnPolicy(
    int windowDays,
    BigDecimal cashierCeiling,
    boolean noReceiptAllowed,
    BigDecimal noReceiptCeiling) {

  /** The default window, in days. */
  public static final int DEFAULT_WINDOW_DAYS = 30;

  /** The most days a window may be set to. */
  public static final int MAX_WINDOW_DAYS = 3650;

  /** Why a return needs a manager: the handover is older than the window. */
  public static final String WINDOW = "WINDOW";

  /**
   * Why a return needs a manager: the refund is over the cashier's ceiling, or cannot be judged.
   */
  public static final String CEILING = "CEILING";

  /**
   * Why a return needs a manager: past the window, but every line past it is a faulty-goods claim.
   * Consumer law protects faulty goods beyond any window, so these are taken by a manager, never
   * refused.
   */
  public static final String FAULTY_PAST_WINDOW = "FAULTY_PAST_WINDOW";

  /** Every reason code, for the wire and for tests. */
  public static final Set<String> REASONS = Set.of(WINDOW, CEILING, FAULTY_PAST_WINDOW);

  /** What a business that has set nothing gets: 30 days, no cashier ceiling, no-receipt off. */
  public static final ReturnPolicy DEFAULT =
      new ReturnPolicy(DEFAULT_WINDOW_DAYS, null, false, null);

  /**
   * Whether the handover is further back than the window, counted in calendar days in the store's
   * own zone: a sale handed over late on Monday evening is one day old on Tuesday morning, whatever
   * the hours between.
   *
   * @param handedOver when the goods were handed over
   * @param now the moment of the return
   * @param zone the store's own time zone
   * @return true when more than {@code windowDays} calendar days have passed
   */
  public boolean pastWindow(Instant handedOver, Instant now, ZoneId zone) {
    LocalDate from = handedOver.atZone(zone).toLocalDate();
    LocalDate to = now.atZone(zone).toLocalDate();
    return ChronoUnit.DAYS.between(from, to) > windowDays;
  }

  /**
   * The rules a return falls outside of; empty when a cashier may take it alone.
   *
   * @param handedOver when the goods were handed over
   * @param now the moment of the return
   * @param zone the store's own time zone
   * @param refundHome the refund in the business's home currency, or null when it cannot be
   *     translated (no rate): a ceiling that cannot be measured against fails closed
   * @param everyLineFaulty whether every returned line is a faulty-goods claim
   * @return the reasons, in a stable order, at most one of {@link #WINDOW} and {@link
   *     #FAULTY_PAST_WINDOW}
   */
  public List<String> reasons(
      Instant handedOver,
      Instant now,
      ZoneId zone,
      BigDecimal refundHome,
      boolean everyLineFaulty) {
    List<String> out = new ArrayList<>();
    if (pastWindow(handedOver, now, zone)) {
      out.add(everyLineFaulty ? FAULTY_PAST_WINDOW : WINDOW);
    }
    if (cashierCeiling != null
        && (refundHome == null || refundHome.compareTo(cashierCeiling) > 0)) {
      out.add(CEILING);
    }
    return List.copyOf(out);
  }
}
