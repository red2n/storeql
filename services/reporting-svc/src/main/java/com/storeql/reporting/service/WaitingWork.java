package com.storeql.reporting.service;

import com.storeql.reporting.domain.PendingWork.Customer;
import com.storeql.reporting.domain.PendingWork.Item;
import com.storeql.reporting.domain.PendingWork.Kind;
import com.storeql.reporting.domain.PendingWork.Payment;
import com.storeql.reporting.domain.PendingWork.Purchase;
import com.storeql.reporting.domain.PendingWork.Readings;
import com.storeql.reporting.domain.PendingWork.Report;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Builds the waiting-work answer from what the owning services said. Pure: no clock, no I/O.
 *
 * <p>A count the owning service could not give is {@code null} and its kind is named in {@code
 * unreachable}; it is never zero, because a zero is a claim that nothing waits.
 */
public final class WaitingWork {

  /** Said beside purchase orders when no approval limits are configured, so none is held. */
  static final String APPROVALS_NOT_ROUTED =
      "Approval limits are not set, so orders are not held for approval";

  private WaitingWork() {}

  /**
   * @param now the instant the answer is generated at
   * @param readings what the three services said
   * @param opens the admin route that settles each kind; a kind with no screen has none
   */
  public static Report assemble(Instant now, Readings readings, Map<Kind, String> opens) {
    Optional<Purchase> purchase = readings.purchase();
    List<Item> items = new ArrayList<>();
    List<Kind> unreachable = new ArrayList<>();
    for (Kind kind : Kind.values()) {
      Long count = count(kind, readings);
      if (count == null) unreachable.add(kind);
      items.add(new Item(kind, kind.label(), count, opens.get(kind), note(kind, purchase)));
    }
    return new Report(now, items, unreachable);
  }

  private static String note(Kind kind, Optional<Purchase> purchase) {
    boolean unrouted = purchase.map(p -> !p.approvalsRouted()).orElse(false);
    return kind == Kind.PURCHASE_ORDER_APPROVAL && unrouted ? APPROVALS_NOT_ROUTED : null;
  }

  private static Long count(Kind kind, Readings readings) {
    return switch (kind) {
      case PURCHASE_ORDER_APPROVAL -> purchase(readings, Purchase::purchaseOrdersPendingApproval);
      case PAYMENT_RUN -> purchase(readings, Purchase::paymentRunsProposed);
      case SUPPLIER_INVOICE -> purchase(readings, Purchase::supplierInvoicesFlagged);
      case ACCOUNTING_SYNC -> purchase(readings, Purchase::accountingSyncsUncertain);
      case CARD_REFUND ->
          readings.payment().map(Payment::cardRefundDuesNeedingAttention).orElse(null);
      case PRIVACY_REQUEST -> readings.customer().map(Customer::privacyRequestsOpen).orElse(null);
    };
  }

  private static Long purchase(Readings readings, Function<Purchase, Long> field) {
    return readings.purchase().map(field).orElse(null);
  }
}
