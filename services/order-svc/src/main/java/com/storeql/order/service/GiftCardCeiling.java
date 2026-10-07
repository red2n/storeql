package com.storeql.order.service;

import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The ceiling on value a manager hands out by hand, with no sale behind it (till-sessions slice 8):
 * above the business's ceiling the issue or reload needs a second person, through the approvals
 * actions {@code sales.gift-card-issue} and {@code sales.gift-card-reload}.
 *
 * <p>The approvals mechanism is a shared block that does not exist yet, so this is only the hook:
 * every hand load asks it, and it approves. When the mechanism lands, this is the one place that
 * reads the business's ceiling (unlimited until set) and asks for the approval; nothing else in the
 * gift-card path changes. A card loaded by a paid sale line has money behind it and never asks.
 */
@ApplicationScoped
public class GiftCardCeiling {

  /** The two hand actions the ceiling is set for. */
  public enum Action {
    ISSUE,
    RELOAD
  }

  /**
   * Asks whether a manager may hand out this much by hand.
   *
   * @param tenantId owning tenant
   * @param action issue or reload
   * @param amount the value to be handed out
   * @param currency the card's currency
   * @param ctx the manager asking
   */
  public void requireWithin(
      UUID tenantId, Action action, BigDecimal amount, String currency, TenantContext ctx) {
    // Waiting for the approvals block: no ceiling is enforced yet.
  }
}
