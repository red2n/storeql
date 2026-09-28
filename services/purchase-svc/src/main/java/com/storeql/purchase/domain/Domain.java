package com.storeql.purchase.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Domain records for purchase-svc. Never returned over HTTP — use DTOs. */
public final class Domain {

  private Domain() {}

  // ── Nominal code constants (Sage/Xero UK standard chart of accounts) ─────────
  public static final String CODE_DEBTORS = "1100";
  public static final String CODE_BANK = "1200";
  public static final String CODE_CREDITORS = "2100";
  public static final String CODE_VAT_OUTPUT = "2200";
  public static final String CODE_VAT_INPUT = "2201";
  public static final String CODE_IC_SALES = "4000";
  public static final String CODE_IC_PURCHASES = "5000";

  public static final String NAME_DEBTORS = "Trade Debtors Control Account";
  public static final String NAME_BANK = "Bank Current Account";
  public static final String NAME_CREDITORS = "Trade Creditors Control Account";
  public static final String NAME_VAT_OUTPUT = "VAT Output Account";
  public static final String NAME_VAT_INPUT = "VAT Input Account";
  public static final String NAME_IC_SALES = "Sales - Intercompany";
  public static final String NAME_IC_PURCHASES = "Purchases - Intercompany";

  /**
   * Stock on hand, the asset a goods receipt recognises. The default when the store has no GL
   * mapping in inventory-svc; a mapped store posts to its own code instead (17.3).
   */
  public static final String CODE_STOCK = "1001";

  /**
   * Goods received not invoiced: the accrual a receipt credits and the invoice debits, so that
   * between the lorry and the paperwork the liability is visible and after both it nets to zero.
   */
  public static final String CODE_GRIR = "2109";

  public static final String NAME_STOCK = "Stock";
  public static final String NAME_GRIR = "Goods Received Not Invoiced";

  /**
   * Where a landed charge waits for the carrier's or broker's bill (07.x): credited when the charge
   * is applied to the stock, cleared when that bill is settled. Not GR/IR, so the goods supplier's
   * reconciliation is not muddied by what a different party charged.
   */
  public static final String CODE_LANDED_ACCRUAL = "2110";

  public static final String NAME_LANDED_ACCRUAL = "Landed Costs Accrued";

  // ── What produced a ledger posting ────────────────────────────────────────────
  public static final String SOURCE_GOODS_RECEIPT = "GOODS_RECEIPT";
  public static final String SOURCE_SUPPLIER_INVOICE = "SUPPLIER_INVOICE";
  public static final String SOURCE_LANDED_COST = "LANDED_COST";
  public static final String SOURCE_LANDED_COST_REVERSAL = "LANDED_COST_REVERSAL";
  public static final String SOURCE_INVOICE_REVERSAL = "INVOICE_REVERSAL";
  public static final String SOURCE_CREDIT_NOTE = "CREDIT_NOTE";
  public static final String SOURCE_INTERCOMPANY = "INTERCOMPANY";
  public static final String SOURCE_SETTLEMENT = "SETTLEMENT";
  public static final String SOURCE_JOURNAL = "JOURNAL";
  public static final String SOURCE_SUPPLIER_PAYMENT = "SUPPLIER_PAYMENT";
  public static final String SOURCE_SALE = "SALE";

  /** A consignment sale: what the supplier is owed the moment its stock sold. */
  public static final String SOURCE_CONSIGNMENT_SALE = "CONSIGNMENT_SALE";

  /** A dropship order delivered to the customer: goods the business never held, now owed. */
  public static final String SOURCE_DROPSHIP_DELIVERY = "DROPSHIP_DELIVERY";

  /** Duty-suspended goods released from bond: the duty now owed to the revenue. */
  public static final String SOURCE_DUTY_RELEASE = "DUTY_RELEASE";

  public static final String SOURCE_SALE_TENDER = "SALE_TENDER";
  public static final String SOURCE_SALE_REFUND = "SALE_REFUND";
  // Chargebacks (11.9): the acquirer taking a card payment back, and how the argument ended.
  public static final String SOURCE_CHARGEBACK = "CHARGEBACK";
  // A payout reconciled against the acquirer's settlement file (11.10): clearing to bank.
  public static final String SOURCE_CARD_SETTLEMENT = "CARD_SETTLEMENT";
  // Deferred revenue for loyalty points and gift cards (17.11).
  public static final String SOURCE_LOYALTY_DEFERRAL = "LOYALTY_DEFERRAL";
  public static final String SOURCE_LOYALTY_RELEASE = "LOYALTY_RELEASE";
  public static final String SOURCE_GIFT_CARD_LOAD = "GIFT_CARD_LOAD";
  public static final String SOURCE_GIFT_CARD_BREAKAGE = "GIFT_CARD_BREAKAGE";

  // ── Sales and tender posting (17.7) ───────────────────────────────────────────
  public static final String CODE_SALES_CLEARING = "1105";
  public static final String NAME_SALES_CLEARING = "Sales Receipts Clearing";
  public static final String CODE_CASH_IN_TILLS = "1210";
  public static final String NAME_CASH_IN_TILLS = "Cash in Tills";
  public static final String CODE_CARD_CLEARING = "1250";
  public static final String NAME_CARD_CLEARING = "Card and Wallet Clearing";
  public static final String CODE_UNALLOCATED_RECEIPTS = "1299";
  public static final String NAME_UNALLOCATED_RECEIPTS = "Unallocated Receipts";
  public static final String CODE_GIFT_CARD_LIABILITY = "2310";
  public static final String NAME_GIFT_CARD_LIABILITY = "Gift Card and Voucher Liability";
  public static final String CODE_STORE_CREDIT_LIABILITY = "2320";
  public static final String NAME_STORE_CREDIT_LIABILITY = "Store Credit Liability";
  public static final String CODE_SALES = "4010";

  /** Cost of goods sold that were the supplier's until they sold (consignment). */
  public static final String CODE_CONSIGNMENT_PURCHASES = "5010";

  public static final String NAME_CONSIGNMENT_PURCHASES = "Purchases - Consignment";

  /** Cost of goods a supplier shipped straight to the customer (dropship). */
  public static final String CODE_DROPSHIP_PURCHASES = "5020";

  public static final String NAME_DROPSHIP_PURCHASES = "Purchases - Dropship";

  /** Excise duty crystallised on releases from bond. */
  public static final String CODE_EXCISE_DUTY = "5030";

  public static final String NAME_EXCISE_DUTY = "Excise Duty";

  /** Excise duty owed to the revenue and not yet paid. */
  public static final String CODE_DUTY_PAYABLE = "2140";

  public static final String NAME_DUTY_PAYABLE = "Excise Duty Payable";
  public static final String NAME_SALES = "Sales";
  public static final String CODE_DEFERRED_LOYALTY = "2330";
  public static final String NAME_DEFERRED_LOYALTY = "Deferred Income - Loyalty Points";
  public static final String CODE_LOYALTY_REDEEMED = "4020";
  public static final String NAME_LOYALTY_REDEEMED = "Sales - Loyalty Points Redeemed";
  public static final String CODE_LOYALTY_BREAKAGE = "4030";
  public static final String NAME_LOYALTY_BREAKAGE = "Loyalty Points Breakage";
  public static final String CODE_GIFT_CARD_BREAKAGE = "4031";
  public static final String NAME_GIFT_CARD_BREAKAGE = "Gift Card Breakage";
  public static final String CODE_LOYALTY_AWARDED = "6410";
  public static final String NAME_LOYALTY_AWARDED = "Loyalty Points Awarded";
  public static final String CODE_GIFT_CARDS_GIVEN = "6420";
  public static final String NAME_GIFT_CARDS_GIVEN = "Gift Cards Given Away";

  // ── Chargebacks (11.9) ────────────────────────────────────────────────────────
  public static final String CODE_CARD_RECEIPTS_IN_DISPUTE = "1255";
  public static final String NAME_CARD_RECEIPTS_IN_DISPUTE = "Card Receipts in Dispute";
  public static final String CODE_CHARGEBACK_LOSSES = "6510";
  public static final String NAME_CHARGEBACK_LOSSES = "Chargeback Losses";
  public static final String CODE_CHARGEBACK_FEES = "6511";
  public static final String NAME_CHARGEBACK_FEES = "Chargeback Fees";

  // ── Settlement against the acquirer's file (11.10) ────────────────────────────
  public static final String CODE_CARD_PROCESSING_FEES = "6500";
  public static final String NAME_CARD_PROCESSING_FEES = "Card Processing Fees";

  /** A confirmed sale as order-svc announced it. */
  public record SalesOrder(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      String currency,
      java.math.BigDecimal total,
      java.math.BigDecimal taxAmount) {}

  /** A captured tender as payment-svc announced it. */
  public record SalesTender(
      UUID tenantId,
      UUID paymentId,
      UUID orderId,
      UUID storeId,
      String method,
      java.math.BigDecimal amount) {}

  /**
   * An order whose receipts clearing has not netted to zero: paid but never confirmed, confirmed
   * but not fully paid, or refunded against a sale the ledger never saw.
   *
   * @param balance debit less credit on 1105 for the order; negative means money received that no
   *     sale has claimed
   */
  public record OpenClearing(
      UUID orderId,
      UUID storeId,
      java.math.BigDecimal balance,
      java.time.LocalDate firstPosted,
      java.time.LocalDate lastPosted) {}

  // ── Supplier ──────────────────────────────────────────────────────────────────
  /**
   * A supplier.
   *
   * @param remittanceEmail where remittance advice is emailed when a payment run pays it (17.10)
   * @param bankDetailsChangedAt when its bank details last changed, and {@code
   *     bankDetailsChangedBy} who changed them: a change shortly before a payment is the pattern
   *     payment-diversion fraud leaves, so a run flags it
   */
  public record Supplier(
      UUID id,
      UUID tenantId,
      String name,
      String vatNumber,
      boolean vatRegistered,
      String countryCode,
      String currency,
      int paymentTermsDays,
      Instant createdAt,
      Instant updatedAt,
      String remittanceEmail,
      String bankAccountName,
      String bankSortCode,
      String bankAccountNumber,
      String bankIban,
      String bankBic,
      Instant bankDetailsChangedAt,
      UUID bankDetailsChangedBy,
      String einvoiceScheme,
      String einvoiceId,
      /**
       * The supplier's quoted lead time in days: the promise a delivery is measured against when
       * the order named no date. Null when the supplier has never quoted one.
       */
      Integer leadTimeDays) {

    /** The bank details as one validated value. */
    public BankAccount.Details bank() {
      return new BankAccount.Details(
          bankAccountName, bankSortCode, bankAccountNumber, bankIban, bankBic);
    }

    /** Whether a payment run can pay this supplier. */
    public boolean hasBankDetails() {
      return bank().payable();
    }

    /** The same supplier, now known to send e-invoices from this electronic address. */
    public Supplier withEinvoiceAddress(String scheme, String identifier, Instant at) {
      return new Supplier(
          id,
          tenantId,
          name,
          vatNumber,
          vatRegistered,
          countryCode,
          currency,
          paymentTermsDays,
          createdAt,
          at,
          remittanceEmail,
          bankAccountName,
          bankSortCode,
          bankAccountNumber,
          bankIban,
          bankBic,
          bankDetailsChangedAt,
          bankDetailsChangedBy,
          scheme,
          identifier,
          leadTimeDays);
    }
  }

  // ── Purchase Order ────────────────────────────────────────────────────────────
  public static final String PO_DRAFT = "DRAFT";

  /**
   * Raised, but above the raiser's own spend authority — nobody entitled to commit this much has
   * agreed to it yet. A state rather than a flag on SUBMITTED: "waiting for a decision" and
   * "decided" are different facts, and a supplier must never be sent an order that is merely
   * waiting.
   */
  public static final String PO_PENDING_APPROVAL = "PENDING_APPROVAL";

  public static final String PO_SUBMITTED = "SUBMITTED";

  /** Some of the order has arrived and more is still expected. Receivable, like SUBMITTED. */
  public static final String PO_PARTIALLY_RECEIVED = "PARTIALLY_RECEIVED";

  public static final String PO_RECEIVED = "RECEIVED";

  /**
   * Short-closed: part arrived, the rest never will. Distinct from {@link #PO_RECEIVED} because "we
   * got it all" and "we gave up on the rest" are different facts, and a supplier scorecard that
   * cannot tell them apart is worthless. Distinct from {@link #PO_CANCELLED} because stock is
   * booked against this order — SJ-D3's reason for refusing to cancel a received one.
   */
  public static final String PO_CLOSED = "CLOSED";

  public static final String PO_CANCELLED = "CANCELLED";

  public record PurchaseOrder(
      UUID id,
      UUID tenantId,
      UUID supplierId,
      UUID storeId,
      String status,
      String currency,
      BigDecimal totalNet,
      BigDecimal totalVat,
      BigDecimal totalGross,
      LocalDate expectedDelivery,
      Instant createdAt,
      Instant updatedAt,
      Instant cancelledAt,
      String cancelledReason,
      Instant closedAt,
      String closedReason,
      UUID createdBy,
      UUID approvedBy,
      Instant approvedAt,
      /** MANUAL, or PROPOSAL when a proposal run raised it (06.x). */
      String source,
      /**
       * Home units per one unit of the order's currency, as used at submission (03.x); null until
       * then.
       */
      BigDecimal fxRate,
      /** The net translated into the home currency at fxRate; null without a translation. */
      BigDecimal totalNetHome,
      /** The home currency the translation was into; null without one. */
      String homeCurrency,
      /**
       * Whose the goods will be on arrival: OWNED (the business's, the default) or CONSIGNMENT (the
       * supplier's until they sell — nothing is owed at the door, the sale is owed).
       */
      String ownership,
      /** For a DROPSHIP order: the sale it fulfils (order-svc's order, referenced), else null. */
      UUID salesOrderId,
      /** For a DROPSHIP order: the customer the supplier ships to, as the order said. */
      String shipTo,
      /**
       * DUTY_PAID (the default), or DUTY_SUSPENDED for excise goods arriving into bond: the receipt
       * tells inventory-svc so, and the duty is owed only when they are released to home use.
       */
      String dutyStatus) {

    /** An order for duty-paid goods of the given ownership, a dropship order possibly. */
    public PurchaseOrder(
        UUID id,
        UUID tenantId,
        UUID supplierId,
        UUID storeId,
        String status,
        String currency,
        BigDecimal totalNet,
        BigDecimal totalVat,
        BigDecimal totalGross,
        LocalDate expectedDelivery,
        Instant createdAt,
        Instant updatedAt,
        Instant cancelledAt,
        String cancelledReason,
        Instant closedAt,
        String closedReason,
        UUID createdBy,
        UUID approvedBy,
        Instant approvedAt,
        String source,
        BigDecimal fxRate,
        BigDecimal totalNetHome,
        String homeCurrency,
        String ownership,
        UUID salesOrderId,
        String shipTo) {
      this(
          id,
          tenantId,
          supplierId,
          storeId,
          status,
          currency,
          totalNet,
          totalVat,
          totalGross,
          expectedDelivery,
          createdAt,
          updatedAt,
          cancelledAt,
          cancelledReason,
          closedAt,
          closedReason,
          createdBy,
          approvedBy,
          approvedAt,
          source,
          fxRate,
          totalNetHome,
          homeCurrency,
          ownership,
          salesOrderId,
          shipTo,
          PO_DUTY_PAID);
    }

    /** The same order, its goods arriving with the given duty status. */
    public PurchaseOrder withDutyStatus(String duty) {
      return new PurchaseOrder(
          id,
          tenantId,
          supplierId,
          storeId,
          status,
          currency,
          totalNet,
          totalVat,
          totalGross,
          expectedDelivery,
          createdAt,
          updatedAt,
          cancelledAt,
          cancelledReason,
          closedAt,
          closedReason,
          createdBy,
          approvedBy,
          approvedAt,
          source,
          fxRate,
          totalNetHome,
          homeCurrency,
          ownership,
          salesOrderId,
          shipTo,
          duty);
    }

    /** An order for goods the business will own, with a translation possibly recorded. */
    public PurchaseOrder(
        UUID id,
        UUID tenantId,
        UUID supplierId,
        UUID storeId,
        String status,
        String currency,
        BigDecimal totalNet,
        BigDecimal totalVat,
        BigDecimal totalGross,
        LocalDate expectedDelivery,
        Instant createdAt,
        Instant updatedAt,
        Instant cancelledAt,
        String cancelledReason,
        Instant closedAt,
        String closedReason,
        UUID createdBy,
        UUID approvedBy,
        Instant approvedAt,
        String source,
        BigDecimal fxRate,
        BigDecimal totalNetHome,
        String homeCurrency) {
      this(
          id,
          tenantId,
          supplierId,
          storeId,
          status,
          currency,
          totalNet,
          totalVat,
          totalGross,
          expectedDelivery,
          createdAt,
          updatedAt,
          cancelledAt,
          cancelledReason,
          closedAt,
          closedReason,
          createdBy,
          approvedBy,
          approvedAt,
          source,
          fxRate,
          totalNetHome,
          homeCurrency,
          PO_OWNERSHIP_OWNED,
          null,
          null);
    }

    /** The same order, for goods of the given ownership. */
    public PurchaseOrder withOwnership(String owned) {
      return new PurchaseOrder(
          id,
          tenantId,
          supplierId,
          storeId,
          status,
          currency,
          totalNet,
          totalVat,
          totalGross,
          expectedDelivery,
          createdAt,
          updatedAt,
          cancelledAt,
          cancelledReason,
          closedAt,
          closedReason,
          createdBy,
          approvedBy,
          approvedAt,
          source,
          fxRate,
          totalNetHome,
          homeCurrency,
          owned,
          salesOrderId,
          shipTo,
          dutyStatus);
    }

    /** The same order, fulfilling a sale by dropship: shipped by the supplier to the customer. */
    public PurchaseOrder withDropship(UUID sale, String customer) {
      return new PurchaseOrder(
          id,
          tenantId,
          supplierId,
          storeId,
          status,
          currency,
          totalNet,
          totalVat,
          totalGross,
          expectedDelivery,
          createdAt,
          updatedAt,
          cancelledAt,
          cancelledReason,
          closedAt,
          closedReason,
          createdBy,
          approvedBy,
          approvedAt,
          source,
          fxRate,
          totalNetHome,
          homeCurrency,
          ownership,
          sale,
          customer,
          dutyStatus);
    }

    /** Whether the supplier ships this order to the customer: stock the business never holds. */
    public boolean dropship() {
      return PO_SOURCE_DROPSHIP.equals(source);
    }

    /** Whether the supplier keeps ownership of the goods until they sell. */
    public boolean consigned() {
      return PO_OWNERSHIP_CONSIGNMENT.equals(ownership);
    }

    /**
     * How a person names this order — "PO #" and the id's handle, exactly as the procurement screen
     * shows it — for text people read: a journal's description in the accounting package and on the
     * Integrations screen. An order has no separate number, so this is its number; like any handle
     * it is not a key, and the posting's source reference still carries the id.
     */
    public String reference() {
      return Handle.purchaseOrder(id);
    }

    /** An order with no translation recorded yet. */
    public PurchaseOrder(
        UUID id,
        UUID tenantId,
        UUID supplierId,
        UUID storeId,
        String status,
        String currency,
        BigDecimal totalNet,
        BigDecimal totalVat,
        BigDecimal totalGross,
        LocalDate expectedDelivery,
        Instant createdAt,
        Instant updatedAt,
        Instant cancelledAt,
        String cancelledReason,
        Instant closedAt,
        String closedReason,
        UUID createdBy,
        UUID approvedBy,
        Instant approvedAt,
        String source) {
      this(
          id,
          tenantId,
          supplierId,
          storeId,
          status,
          currency,
          totalNet,
          totalVat,
          totalGross,
          expectedDelivery,
          createdAt,
          updatedAt,
          cancelledAt,
          cancelledReason,
          closedAt,
          closedReason,
          createdBy,
          approvedBy,
          approvedAt,
          source,
          null,
          null,
          null);
    }
  }

  public static final String PO_SOURCE_MANUAL = "MANUAL";
  public static final String PO_SOURCE_PROPOSAL = "PROPOSAL";

  /** Raised from a confirmed sale for a supplier that ships to the customer (dropship). */
  public static final String PO_SOURCE_DROPSHIP = "DROPSHIP";

  /** Raised by an RFQ award, at the price the supplier quoted. */
  public static final String PO_SOURCE_RFQ = "RFQ";

  /** Which supplier fulfils a variant per order, at what cost; one live per variant. */
  public record DropshipArrangement(
      UUID id,
      UUID tenantId,
      UUID variantId,
      UUID supplierId,
      BigDecimal unitCost,
      String vatCode,
      boolean active,
      UUID createdBy,
      Instant createdAt,
      Instant endedAt) {}

  /** The business will own the goods on arrival. */
  public static final String PO_OWNERSHIP_OWNED = "OWNED";

  /** The supplier owns the goods until they sell (consignment, sale or return). */
  public static final String PO_OWNERSHIP_CONSIGNMENT = "CONSIGNMENT";

  /** The goods arrive with the duty paid. */
  public static final String PO_DUTY_PAID = "DUTY_PAID";

  /** Excise goods arriving into bond: the duty is owed only on release to home use. */
  public static final String PO_DUTY_SUSPENDED = "DUTY_SUSPENDED";

  /** A release from bond as inventory-svc announced it: the duty now owed to the revenue. */
  public record DutyRelease(
      UUID id,
      UUID tenantId,
      UUID eventId,
      UUID releaseId,
      UUID storeId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal dutyPerUnit,
      BigDecimal dutyAmount,
      String currency,
      String reference,
      LocalDate releasedOn,
      Instant recordedAt) {}

  /** A sale drawn from a supplier's consignment stock, as inventory-svc announced it. */
  public record ConsignmentSale(
      UUID id,
      UUID tenantId,
      UUID eventId,
      UUID supplierId,
      UUID storeId,
      UUID variantId,
      UUID batchId,
      UUID orderId,
      BigDecimal qty,
      BigDecimal unitCost,
      BigDecimal amount,
      String currency,
      LocalDate soldOn,
      UUID settlementId,
      Instant recordedAt) {

    public boolean settled() {
      return settlementId != null;
    }
  }

  /** One statement of a supplier's consignment sales over a period, for it to invoice against. */
  public record ConsignmentSettlement(
      UUID id,
      UUID tenantId,
      UUID supplierId,
      String reference,
      LocalDate periodFrom,
      LocalDate periodTo,
      String currency,
      BigDecimal total,
      int salesCount,
      UUID createdBy,
      Instant createdAt) {}

  /** An item a proposal run could not judge, and why. */
  public record SkippedItem(UUID variantId, String reason) {}

  /** One proposal run: what it looked at, what it raised, what it skipped (06.x). */
  public record ProposalRun(
      UUID id,
      UUID tenantId,
      UUID storeId,
      UUID ranBy,
      Instant ranAt,
      int coverDays,
      int considered,
      int ordersRaised,
      int linesRaised,
      List<UUID> orderIds,
      List<SkippedItem> skipped) {
    public ProposalRun {
      orderIds = List.copyOf(orderIds);
      skipped = List.copyOf(skipped);
    }
  }

  // ── Purchase order approval (spend authority) ─────────────────────────────────

  /** A submission that exceeded the raiser's authority and is waiting for someone else's. */
  public static final String APPROVAL_REQUESTED = "REQUESTED";

  public static final String APPROVAL_APPROVED = "APPROVED";
  public static final String APPROVAL_REJECTED = "REJECTED";

  /**
   * One decision in a purchase order's approval history. Append-only (golden rule #8).
   *
   * <p>A column pair on the order would have covered a single decision, the way V3 handled
   * cancellation — but a rejection sends the order back to DRAFT to be corrected and resubmitted,
   * so one order can cycle through several. A spend-authority trail that keeps only the last
   * decision is not an audit trail.
   *
   * @param totalNet the figure the decision was made against, captured at decision time rather than
   *     read back later: the order can be edited after a rejection, and an approval that silently
   *     re-points at a larger total is the whole attack this feature exists to stop
   * @param authority what the decider was entitled to commit, so the trail can still answer "were
   *     they allowed to?" after the configuration has changed
   */
  public record PurchaseOrderApproval(
      UUID id,
      UUID tenantId,
      UUID poId,
      String decision,
      BigDecimal totalNet,
      String currency,
      BigDecimal authority,
      UUID decidedBy,
      String decidedRole,
      String reason,
      Instant decidedAt) {}

  /**
   * How much of one ordered line has actually turned up.
   *
   * <p>Receipts are matched to order lines by variant rather than by line id: {@code
   * goods_receipt_lines} has never carried a {@code po_line_id}, and a delivery note names products
   * rather than order rows. Two lines on one order for the same variant therefore aggregate here,
   * which is also the answer a warehouse gives when counting what arrived.
   *
   * @param variantId the product
   * @param qtyOrdered what the purchase order asked for
   * @param qtyReceived what has arrived across every receipt against this order
   * @param qtyOutstanding ordered minus received, floored at zero
   */
  public record PurchaseOrderLineProgress(
      UUID variantId,
      BigDecimal qtyOrdered,
      BigDecimal qtyReceived,
      BigDecimal qtyOutstanding,
      /** What has gone back to the supplier out of what was received (07.8). */
      BigDecimal qtyReturned) {

    /** Progress before returns existed: nothing returned. */
    public PurchaseOrderLineProgress(
        UUID variantId, BigDecimal qtyOrdered, BigDecimal qtyReceived, BigDecimal qtyOutstanding) {
      this(variantId, qtyOrdered, qtyReceived, qtyOutstanding, BigDecimal.ZERO);
    }
  }

  /** Part of a warehouse order's line allocated to a shop the warehouse serves (cross-docking). */
  public record LineAllocation(
      UUID id,
      UUID tenantId,
      UUID poId,
      UUID poLineId,
      UUID variantId,
      UUID storeId,
      BigDecimal qty,
      UUID createdBy,
      Instant createdAt) {}

  public record PurchaseOrderLine(
      UUID id,
      UUID tenantId,
      UUID poId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      String vatCode,
      Instant createdAt,
      /**
       * The proposal's arithmetic for this line, in the buyer's words; null when a person typed it.
       */
      String proposalReason) {}

  // ── Goods Receipt (GRN) ───────────────────────────────────────────────────────
  public record GoodsReceipt(
      UUID id,
      UUID tenantId,
      UUID poId,
      UUID storeId,
      Instant receivedAt,
      Instant createdAt,
      String idempotencyKey) {}

  public record GoodsReceiptLine(
      UUID id,
      UUID tenantId,
      UUID grId,
      UUID variantId,
      BigDecimal qtyReceived,
      Instant createdAt) {}

  // ── Supplier invoice (three-way match) ────────────────────────────────────────

  /** Every line agreed with the order and the receipt, inside tolerance. */
  public static final String INVOICE_MATCHED = "MATCHED";

  /** At least one line did not. Captured and posted anyway; blocked for payment until resolved. */
  public static final String INVOICE_FLAGGED = "FLAGGED";

  /** A flagged invoice a manager released for payment, with a reason. */
  public static final String INVOICE_APPROVED = "APPROVED";

  /**
   * A flagged invoice a manager refused. Its posting is reversed and the quantities it billed no
   * longer count against the order, so the supplier's corrected invoice matches cleanly.
   */
  public static final String INVOICE_REJECTED = "REJECTED";

  /**
   * A supplier's invoice against a purchase order.
   *
   * @param invoiceNumber the supplier's own reference as printed on the document; unique per
   *     supplier case-insensitively, because the commonest way to pay twice is for two people to
   *     type the same paper reference on the same morning
   * @param status {@link #INVOICE_MATCHED}, {@link #INVOICE_FLAGGED}, {@link #INVOICE_APPROVED} or
   *     {@link #INVOICE_REJECTED}
   * @param dueDate the invoice date plus the supplier's payment terms
   * @param statedGross the total printed on the document, when keyed; null when not
   * @param headerVariances comma-separated header-level variances, empty when the header agreed
   * @param postedAt when the AP posting was written; null for invoices captured before postings
   * @param resolvedAt when a flagged invoice was approved or rejected
   * @param resolvedBy who decided
   * @param resolutionReason why
   */
  public record SupplierInvoice(
      UUID id,
      UUID tenantId,
      UUID poId,
      UUID supplierId,
      String invoiceNumber,
      LocalDate invoiceDate,
      String currency,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      String status,
      Instant matchedAt,
      UUID createdBy,
      Instant createdAt,
      LocalDate dueDate,
      BigDecimal statedGross,
      String headerVariances,
      Instant postedAt,
      Instant resolvedAt,
      UUID resolvedBy,
      String resolutionReason,
      Instant paidAt,
      UUID paymentRunId) {

    /** Whether the invoice may be paid: matched, or flagged and then approved. */
    public boolean payable() {
      return INVOICE_MATCHED.equals(status) || INVOICE_APPROVED.equals(status);
    }
  }

  /**
   * One line of a supplier invoice, carrying the match outcome it was captured with.
   *
   * @param variances comma-separated variance codes, empty when the line agreed. Stored rather than
   *     recomputed on read: the purchase order can be amended afterwards, and an invoice that
   *     silently re-matched against the amended order would erase the disagreement it was flagged
   *     for
   */
  public record SupplierInvoiceLine(
      UUID id,
      UUID tenantId,
      UUID invoiceId,
      UUID variantId,
      BigDecimal qtyInvoiced,
      BigDecimal unitPrice,
      String vatCode,
      String variances,
      Instant createdAt) {}

  // ── Return to vendor and debit note (07.8) ────────────────────────────────────

  /** The goods have gone back and the debit note is issued. */
  public static final String RETURN_RAISED = "RAISED";

  /** The supplier's credit note has been recorded against the return. */
  public static final String RETURN_CREDITED = "CREDITED";

  /** Why goods went back. The reason is what a supplier scorecard and a debit note both cite. */
  public static final java.util.Set<String> RETURN_REASONS =
      java.util.Set.of(
          "DAMAGED", "WRONG_ITEM", "OVER_DELIVERED", "QUALITY", "EXPIRED", "RECALL", "OTHER");

  /**
   * A return to vendor: goods sent back against a purchase order, with the debit note raised for
   * their value at the order's prices, and the supplier's credit note once it arrives.
   */
  public record VendorReturn(
      UUID id,
      UUID tenantId,
      UUID poId,
      UUID supplierId,
      UUID storeId,
      String status,
      String reason,
      String notes,
      String currency,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      String debitNoteNumber,
      Instant raisedAt,
      UUID raisedBy,
      String creditNoteNumber,
      LocalDate creditNoteDate,
      BigDecimal creditAmount,
      Instant creditedAt,
      UUID creditedBy,
      String idempotencyKey) {}

  /** One variant going back, priced at the order's price when the return was raised. */
  public record VendorReturnLine(
      UUID id,
      UUID tenantId,
      UUID returnId,
      UUID variantId,
      BigDecimal qty,
      BigDecimal unitPrice,
      String vatCode,
      BigDecimal lineNet,
      Instant createdAt) {}

  // ── Intercompany Invoice (Gap #20) ────────────────────────────────────────────
  public static final String INV_AR = "AR";
  public static final String INV_AP = "AP";
  public static final String INV_RAISED = "RAISED";
  public static final String INV_SETTLED = "SETTLED";

  public record IntercompanyInvoice(
      UUID id,
      UUID tenantId,
      String invoiceType,
      UUID fromStoreId,
      UUID toStoreId,
      UUID transferRef,
      BigDecimal netAmount,
      BigDecimal vatAmount,
      BigDecimal grossAmount,
      String vatCode,
      boolean vatDisregarded,
      String status,
      LocalDate invoiceDate,
      LocalDate paymentDueDate,
      String currency,
      Instant createdAt) {}

  // ── Nominal Ledger Entry (FRS 102, append-only) ───────────────────────────────
  public record NominalLedgerEntry(
      UUID id,
      UUID tenantId,
      LocalDate entryDate,
      String nominalCode,
      String nominalName,
      BigDecimal debit,
      BigDecimal credit,
      String description,
      UUID sourceRef,
      Instant createdAt,
      UUID journalId,
      String sourceType,
      UUID storeId) {}

  /** One set of the tenant accountant's estimates for deferred revenue (17.11); append-only. */
  public record DeferredRevenueSettings(
      UUID id,
      UUID tenantId,
      String currency,
      BigDecimal pointValue,
      BigDecimal pointsBreakagePct,
      BigDecimal giftCardBreakagePct,
      String reason,
      UUID setBy,
      Instant setAt) {

    public DeferredRevenue.Settings estimates() {
      return new DeferredRevenue.Settings(pointValue, pointsBreakagePct, giftCardBreakagePct);
    }
  }

  /** An announcement about loyalty points, as the ledger records it (17.11). */
  public record LoyaltyEvent(
      UUID tenantId,
      UUID eventId,
      String kind,
      UUID customerId,
      UUID orderId,
      BigDecimal points,
      BigDecimal orderTotal,
      BigDecimal orderTax) {
    public static final String EARNED = "EARNED";
    public static final String REDEEMED = "REDEEMED";
    public static final String ADJUSTED = "ADJUSTED";

    /** Points that died under the programme's expiry rule (13.x): a lapse, announced. */
    public static final String EXPIRED = "EXPIRED";
  }

  /** A gift card issued or reloaded, as order-svc announced it (17.11). */
  public record GiftCardLoad(
      UUID tenantId,
      UUID transactionId,
      UUID giftCardId,
      UUID storeId,
      String kind,
      String paidBy,
      BigDecimal amount,
      String currency) {}

  /** Where deferred revenue stands for a tenant (17.11). */
  public record DeferredRevenueView(
      DeferredRevenueSettings current,
      List<DeferredRevenueSettings> history,
      DeferredRevenue.PointsPool points,
      long waiting,
      DeferredRevenue.GiftCardPool giftCards) {}

  /** One journal read back whole: its lines and the header they share. */
  public record Journal(
      UUID journalId,
      LocalDate entryDate,
      String description,
      String sourceType,
      UUID sourceRef,
      UUID storeId,
      java.util.List<NominalLedgerEntry> lines) {

    public BigDecimal totalDebit() {
      return lines.stream().map(NominalLedgerEntry::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal totalCredit() {
      return lines.stream()
          .map(NominalLedgerEntry::credit)
          .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
  }

  /** One nominal code's movement and balance over a trial balance's range. */
  public record TrialBalanceRow(
      String nominalCode, String nominalName, BigDecimal debit, BigDecimal credit) {

    /** Debit less credit: positive for an asset or expense balance, negative for a liability. */
    public BigDecimal balance() {
      return debit.subtract(credit);
    }
  }
}
