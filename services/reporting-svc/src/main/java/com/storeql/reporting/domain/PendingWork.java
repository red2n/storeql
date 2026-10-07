package com.storeql.reporting.domain;

import com.storeql.web.PendingWorkCount;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * What waits for a person in a business, as the services that own each queue count it.
 * reporting-svc keeps none of it: these are the shapes three REST answers are read into and the one
 * answer built from them.
 */
public final class PendingWork {

  private PendingWork() {}

  /**
   * The kinds of waiting work, in the order the screen lists them.
   *
   * <p>The label is the English words for the kind. Where the admin app opens it is not here: see
   * {@link com.storeql.reporting.config.WaitingWorkRoutes}.
   */
  public enum Kind {
    PURCHASE_ORDER_APPROVAL("Purchase orders waiting for approval"),
    PAYMENT_RUN("Supplier payment runs waiting for approval"),
    SUPPLIER_INVOICE("Supplier invoices flagged for a decision"),
    ACCOUNTING_SYNC("Accounting entries that may not have reached the package"),
    CARD_REFUND("Card refunds that need attention"),
    PRIVACY_REQUEST("Privacy requests waiting for an answer");

    private final String label;

    Kind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * purchase-svc's counts.
   *
   * @param approvalsRouted false when this deployment has no purchase approval limits configured (a
   *     deployment-wide setting, not a business's), so no order is held for approval and a zero
   *     does not mean nothing waits
   */
  public record Purchase(
      long purchaseOrdersPendingApproval,
      long paymentRunsProposed,
      long supplierInvoicesFlagged,
      long accountingSyncsUncertain,
      boolean approvalsRouted) {}

  /** payment-svc's count. */
  public record Payment(long cardRefundDuesNeedingAttention) {}

  /** customer-svc's count. */
  public record Customer(long privacyRequestsOpen) {}

  /**
   * What the three services said; empty for a service that could not be reached, refused, was too
   * slow or answered with something unreadable. Empty is not zero.
   */
  public record Readings(
      Optional<Purchase> purchase, Optional<Payment> payment, Optional<Customer> customer) {}

  /**
   * One kind of waiting work.
   *
   * @param count how many wait, read to at most {@link PendingWorkCount#CAP}; {@code null} when its
   *     source could not be reached
   * @param opens the admin app route that settles it; null when no screen exists for it yet
   * @param note a caution about the count, or {@code null}
   */
  public record Item(Kind kind, String label, Long count, String opens, String note) {

    /**
     * Whether the count stopped at the cap, so that many or more wait. A count that could not be
     * had is not capped: it is unknown.
     */
    public boolean capped() {
      return count != null && PendingWorkCount.atCap(count);
    }
  }

  /**
   * The answer.
   *
   * @param unreachable the kinds whose count could not be had, in {@link Kind} order
   */
  public record Report(Instant generatedAt, List<Item> items, List<Kind> unreachable) {

    public Report {
      items = List.copyOf(items);
      unreachable = List.copyOf(unreachable);
    }
  }
}
