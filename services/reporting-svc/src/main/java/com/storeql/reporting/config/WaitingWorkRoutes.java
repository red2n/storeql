package com.storeql.reporting.config;

import com.storeql.reporting.domain.PendingWork.Kind;
import java.util.Map;

/**
 * Where the admin app opens each kind of waiting work: the one place these routes are written, so
 * correcting one when the app's routes change is a one-line edit.
 */
public final class WaitingWorkRoutes {

  private WaitingWorkRoutes() {}

  /**
   * A kind with no entry has no screen to open (card refunds have none yet), and its tile is shown
   * without a link.
   */
  public static final Map<Kind, String> OPENS =
      Map.of(
          Kind.PURCHASE_ORDER_APPROVAL, "/admin/procurement?tab=purchase-orders",
          Kind.PAYMENT_RUN, "/admin/procurement?tab=payments",
          Kind.SUPPLIER_INVOICE, "/admin/procurement?tab=invoices",
          Kind.ACCOUNTING_SYNC, "/admin/integrations",
          Kind.PRIVACY_REQUEST, "/admin/privacy");
}
