package com.storeql.purchase.mapper;

import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.GoodsReceipt;
import com.storeql.purchase.domain.Domain.GoodsReceiptLine;
import com.storeql.purchase.domain.Domain.IntercompanyInvoice;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.domain.Domain.PurchaseOrder;
import com.storeql.purchase.domain.Domain.PurchaseOrderLine;
import com.storeql.purchase.domain.Domain.Supplier;
import com.storeql.purchase.domain.LandedCost;
import com.storeql.purchase.domain.SpendAuthority;
import com.storeql.purchase.domain.SupplierScorecard;
import com.storeql.purchase.dto.Dtos;
import com.storeql.purchase.dto.Dtos.GoodsReceiptLineResponse;
import com.storeql.purchase.dto.Dtos.GoodsReceiptResponse;
import com.storeql.purchase.dto.Dtos.IntercompanyInvoiceResponse;
import com.storeql.purchase.dto.Dtos.LandedCostLineResponse;
import com.storeql.purchase.dto.Dtos.LandedCostResponse;
import com.storeql.purchase.dto.Dtos.NominalLedgerEntryResponse;
import com.storeql.purchase.dto.Dtos.ProposalRunResponse;
import com.storeql.purchase.dto.Dtos.ProposedOrderResponse;
import com.storeql.purchase.dto.Dtos.PurchaseOrderLineProgressResponse;
import com.storeql.purchase.dto.Dtos.PurchaseOrderLineResponse;
import com.storeql.purchase.dto.Dtos.PurchaseOrderResponse;
import com.storeql.purchase.dto.Dtos.SkippedItemResponse;
import com.storeql.purchase.dto.Dtos.SupplierResponse;
import com.storeql.purchase.service.ProposalService;
import java.util.List;

/** Domain → DTO mappers. No business logic. */
public final class Mappers {

  private Mappers() {}

  /**
   * Converts a supplier to its wire form.
   *
   * @param s the supplier to convert
   * @return its API representation
   */
  public static SupplierResponse toDto(Supplier s) {
    return new SupplierResponse(
        s.id(),
        s.tenantId(),
        s.name(),
        s.vatNumber(),
        s.vatRegistered(),
        s.countryCode(),
        s.currency(),
        s.paymentTermsDays(),
        s.createdAt(),
        s.updatedAt(),
        s.remittanceEmail(),
        s.bankAccountName(),
        s.bankSortCode(),
        com.storeql.purchase.domain.BankAccount.masked(s.bankAccountNumber()),
        com.storeql.purchase.domain.BankAccount.masked(s.bankIban()),
        s.bankBic(),
        s.hasBankDetails(),
        s.bankDetailsChangedAt(),
        s.einvoiceScheme(),
        s.einvoiceId(),
        s.leadTimeDays());
  }

  // ── Supplier lead times and scorecards ─────────────────────────────────────

  public static Dtos.SupplierDeliveryResponse toDto(SupplierScorecard.Delivery d) {
    return new Dtos.SupplierDeliveryResponse(
        d.id(),
        d.supplierId(),
        d.poId(),
        d.grId(),
        d.storeId(),
        d.orderedAt(),
        d.promisedDate(),
        d.receivedAt(),
        d.leadDays(),
        d.lateDays(),
        d.complete(),
        d.receivedQty());
  }

  public static Dtos.SupplierScorecardResponse toDto(SupplierScorecard.Card c) {
    SupplierScorecard.Deliveries d = c.deliveries();
    SupplierScorecard.Fill f = c.fill();
    SupplierScorecard.Quality q = c.quality();
    SupplierScorecard.Invoices i = c.invoices();
    return new Dtos.SupplierScorecardResponse(
        c.supplierId(),
        c.supplierName(),
        c.leadTimeDays(),
        c.from(),
        c.to(),
        new Dtos.DeliveryStatsResponse(
            d.count(),
            d.avgLeadDays(),
            d.medianLeadDays(),
            d.maxLeadDays(),
            d.promised(),
            d.onTime(),
            d.late(),
            d.onTimePct(),
            d.avgDaysLate(),
            d.receivedQty()),
        new Dtos.FillStatsResponse(
            f.orders(), f.orderedQty(), f.receivedQty(), f.fillRatePct(), f.shortClosed()),
        new Dtos.QualityStatsResponse(q.returns(), q.returnedQty(), q.returnRatePct()),
        new Dtos.InvoiceStatsResponse(i.invoices(), i.flagged(), i.accuracyPct()),
        c.score(),
        c.grade());
  }

  /**
   * Converts a purchase order header to its wire form.
   *
   * @param po the purchase order header to convert
   * @return its API representation, including the stored net/VAT/gross totals
   */
  public static PurchaseOrderResponse toDto(PurchaseOrder po) {
    return new PurchaseOrderResponse(
        po.id(),
        po.tenantId(),
        po.supplierId(),
        po.storeId(),
        po.status(),
        po.currency(),
        po.totalNet(),
        po.totalVat(),
        po.totalGross(),
        po.expectedDelivery(),
        po.createdAt(),
        po.updatedAt(),
        po.cancelledAt(),
        po.cancelledReason(),
        po.closedAt(),
        po.closedReason(),
        po.createdBy(),
        po.approvedBy(),
        po.approvedAt(),
        po.source(),
        po.fxRate(),
        po.totalNetHome(),
        po.homeCurrency(),
        po.ownership(),
        po.salesOrderId(),
        po.shipTo(),
        po.dutyStatus());
  }

  public static Dtos.DutyReleaseResponse toDto(Domain.DutyRelease r) {
    return new Dtos.DutyReleaseResponse(
        r.id(),
        r.releaseId(),
        r.storeId(),
        r.variantId(),
        r.qty(),
        r.dutyPerUnit(),
        r.dutyAmount(),
        r.currency(),
        r.reference(),
        r.releasedOn());
  }

  public static Dtos.DropshipArrangementResponse toDto(Domain.DropshipArrangement a) {
    return new Dtos.DropshipArrangementResponse(
        a.id(),
        a.variantId(),
        a.supplierId(),
        a.unitCost(),
        a.vatCode(),
        a.active(),
        a.createdAt(),
        a.endedAt());
  }

  public static Dtos.ConsignmentSaleResponse toDto(Domain.ConsignmentSale s) {
    return new Dtos.ConsignmentSaleResponse(
        s.id(),
        s.supplierId(),
        s.storeId(),
        s.variantId(),
        s.batchId(),
        s.orderId(),
        s.qty(),
        s.unitCost(),
        s.amount(),
        s.currency(),
        s.soldOn(),
        s.settled(),
        s.settlementId(),
        s.recordedAt());
  }

  public static Dtos.ConsignmentSettlementResponse toDto(
      Domain.ConsignmentSettlement s, List<Domain.ConsignmentSale> sales) {
    return new Dtos.ConsignmentSettlementResponse(
        s.id(),
        s.supplierId(),
        s.reference(),
        s.periodFrom(),
        s.periodTo(),
        s.currency(),
        s.total(),
        s.salesCount(),
        s.createdAt(),
        sales == null ? null : sales.stream().map(Mappers::toDto).toList());
  }

  /**
   * @param lines the invoice's stored lines
   * @param positions what the order and receipts say now, keyed by variant, so the screen can show
   *     the three documents side by side
   */
  public static Dtos.SupplierInvoiceResponse toDto(
      Domain.SupplierInvoice inv,
      java.util.List<Domain.SupplierInvoiceLine> lines,
      java.util.List<com.storeql.purchase.domain.ThreeWayMatch.OrderPosition> positions) {
    var byVariant =
        positions.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    com.storeql.purchase.domain.ThreeWayMatch.OrderPosition::variantId,
                    p -> p,
                    (a, b) -> a));
    var rows =
        lines.stream()
            .map(
                l -> {
                  var p = byVariant.get(l.variantId());
                  return new Dtos.SupplierInvoiceMatchLineResponse(
                      l.variantId(),
                      p == null ? java.math.BigDecimal.ZERO : p.qtyOrdered(),
                      p == null ? java.math.BigDecimal.ZERO : p.qtyReceived(),
                      // What OTHER invoices billed: the stored total includes this one.
                      p == null
                          ? java.math.BigDecimal.ZERO
                          : p.qtyAlreadyInvoiced()
                              .subtract(l.qtyInvoiced())
                              .max(java.math.BigDecimal.ZERO),
                      l.qtyInvoiced(),
                      p == null ? null : p.orderedUnitPrice(),
                      l.unitPrice(),
                      l.variances() == null || l.variances().isBlank()
                          ? java.util.List.of()
                          : java.util.List.of(l.variances().split(",")));
                })
            .toList();
    return new Dtos.SupplierInvoiceResponse(
        inv.id(),
        inv.poId(),
        inv.supplierId(),
        inv.invoiceNumber(),
        inv.invoiceDate(),
        inv.currency(),
        inv.netAmount(),
        inv.vatAmount(),
        inv.grossAmount(),
        inv.status(),
        inv.createdBy(),
        inv.createdAt(),
        inv.dueDate(),
        inv.statedGross(),
        inv.headerVariances() == null || inv.headerVariances().isBlank()
            ? java.util.List.of()
            : java.util.List.of(inv.headerVariances().split(",")),
        inv.postedAt(),
        inv.payable(),
        inv.resolvedAt(),
        inv.resolvedBy(),
        inv.resolutionReason(),
        inv.paidAt(),
        inv.paymentRunId(),
        inv.paidAt() != null,
        rows);
  }

  /**
   * Converts one approval-trail row to its wire form.
   *
   * @param a the trail entry to convert
   * @return its API representation, carrying the figure and authority as they stood at the time
   */
  public static Dtos.PurchaseOrderApprovalResponse toDto(Domain.PurchaseOrderApproval a) {
    return new Dtos.PurchaseOrderApprovalResponse(
        a.id(),
        a.poId(),
        a.decision(),
        a.totalNet(),
        a.currency(),
        a.authority(),
        a.decidedBy(),
        a.decidedRole(),
        a.reason(),
        a.decidedAt());
  }

  /**
   * @param currency the currency the authority was asked about, echoed so a client caching several
   *     answers cannot mix them up
   */
  public static Dtos.SpendAuthorityResponse toDto(SpendAuthority a, String currency, boolean off) {
    return new Dtos.SpendAuthorityResponse(
        currency, a.ceiling(), a.unlimited(), a.role(), off, a.reason());
  }

  /**
   * Converts a purchase order line to its wire form.
   *
   * @param line the purchase order line to convert
   * @return its API representation
   */
  public static PurchaseOrderLineResponse toDto(PurchaseOrderLine line) {
    return new PurchaseOrderLineResponse(
        line.id(),
        line.poId(),
        line.variantId(),
        line.qty(),
        line.unitPrice(),
        line.vatCode(),
        line.createdAt(),
        line.proposalReason());
  }

  /**
   * Converts a goods receipt and its lines to the wire form.
   *
   * @param gr the receipt header
   * @param lines the receipt's lines
   * @return its API representation, header and lines together
   */
  public static GoodsReceiptResponse toDto(GoodsReceipt gr, List<GoodsReceiptLine> lines) {
    return new GoodsReceiptResponse(
        gr.id(),
        gr.tenantId(),
        gr.poId(),
        gr.storeId(),
        gr.receivedAt(),
        lines.stream().map(Mappers::toDto).toList());
  }

  /**
   * Converts one goods receipt line to its wire form.
   *
   * @param l the receipt line to convert
   * @return its API representation
   */
  public static GoodsReceiptLineResponse toDto(GoodsReceiptLine l) {
    return new GoodsReceiptLineResponse(l.id(), l.variantId(), l.qtyReceived(), l.createdAt());
  }

  /**
   * Converts an intercompany invoice to its wire form.
   *
   * @param inv the intercompany invoice to convert
   * @return its API representation
   */
  public static IntercompanyInvoiceResponse toDto(IntercompanyInvoice inv) {
    return new IntercompanyInvoiceResponse(
        inv.id(),
        inv.tenantId(),
        inv.invoiceType(),
        inv.fromStoreId(),
        inv.toStoreId(),
        inv.transferRef(),
        inv.netAmount(),
        inv.vatAmount(),
        inv.grossAmount(),
        inv.vatCode(),
        inv.vatDisregarded(),
        inv.status(),
        inv.invoiceDate(),
        inv.paymentDueDate(),
        inv.currency(),
        inv.createdAt());
  }

  /**
   * Converts one nominal-ledger entry to its wire form.
   *
   * @param e the ledger entry to convert
   * @return its API representation
   */
  public static NominalLedgerEntryResponse toDto(NominalLedgerEntry e) {
    return new NominalLedgerEntryResponse(
        e.id(),
        e.tenantId(),
        e.journalId(),
        e.sourceType(),
        e.storeId(),
        e.entryDate(),
        e.nominalCode(),
        e.nominalName(),
        e.debit(),
        e.credit(),
        e.description(),
        e.sourceRef(),
        e.createdAt());
  }

  /**
   * Converts one line-progress row to its wire form.
   *
   * @param p ordered against received for a single variant
   * @return its API representation, including the balance still due
   */
  public static PurchaseOrderLineProgressResponse toDto(Domain.PurchaseOrderLineProgress p) {
    return new PurchaseOrderLineProgressResponse(
        p.variantId(), p.qtyOrdered(), p.qtyReceived(), p.qtyOutstanding(), p.qtyReturned());
  }

  /**
   * Converts a return to vendor and its lines to the wire form: the debit note as a document.
   *
   * @param r the return
   * @param lines its lines
   * @return its API representation
   */
  public static com.storeql.purchase.dto.Dtos.VendorReturnResponse toDto(
      Domain.VendorReturn r, java.util.List<Domain.VendorReturnLine> lines) {
    return new com.storeql.purchase.dto.Dtos.VendorReturnResponse(
        r.id(),
        r.poId(),
        r.supplierId(),
        r.storeId(),
        r.status(),
        r.reason(),
        r.notes(),
        r.currency(),
        r.netAmount(),
        r.vatAmount(),
        r.grossAmount(),
        r.debitNoteNumber(),
        r.raisedAt(),
        r.raisedBy(),
        r.creditNoteNumber(),
        r.creditNoteDate() == null ? null : r.creditNoteDate().toString(),
        r.creditAmount(),
        r.creditedAt(),
        r.creditedBy(),
        lines.stream()
            .map(
                l ->
                    new com.storeql.purchase.dto.Dtos.VendorReturnLineResponse(
                        l.id(), l.variantId(), l.qty(), l.unitPrice(), l.vatCode(), l.lineNet()))
            .toList());
  }

  /**
   * Converts a journal to its wire form: the header its lines share and the lines.
   *
   * @param j the journal
   * @return its API representation
   */
  public static Dtos.JournalResponse toDto(Domain.Journal j) {
    return new Dtos.JournalResponse(
        j.journalId(),
        j.entryDate(),
        j.description(),
        j.sourceType(),
        j.sourceRef(),
        j.storeId(),
        j.totalDebit(),
        j.totalCredit(),
        j.lines().stream().map(Mappers::toDto).toList());
  }

  /**
   * Converts trial balance rows to the wire form, with the totals and whether they agree.
   *
   * @param rows one per nominal code
   * @param from the range start, or null
   * @param to the range end, or null
   * @param storeId the store filter, or null
   * @return the trial balance
   */
  public static Dtos.TrialBalanceResponse toTrialBalance(
      java.util.List<Domain.TrialBalanceRow> rows,
      java.time.LocalDate from,
      java.time.LocalDate to,
      java.util.UUID storeId) {
    java.math.BigDecimal debit = java.math.BigDecimal.ZERO;
    java.math.BigDecimal credit = java.math.BigDecimal.ZERO;
    var out = new java.util.ArrayList<Dtos.TrialBalanceRowResponse>(rows.size());
    for (Domain.TrialBalanceRow r : rows) {
      debit = debit.add(r.debit());
      credit = credit.add(r.credit());
      out.add(
          new Dtos.TrialBalanceRowResponse(
              r.nominalCode(), r.nominalName(), r.debit(), r.credit(), r.balance()));
    }
    return new Dtos.TrialBalanceResponse(
        from, to, storeId, out, debit, credit, debit.compareTo(credit) == 0);
  }

  /**
   * Converts a payment run to its wire form: the run, what it pays each supplier, and any supplier
   * it does not pay. Bank details never appear here; they reach the bank file alone.
   */
  public static Dtos.PaymentRunResponse toDto(com.storeql.purchase.domain.PaymentRuns.View v) {
    var r = v.run();
    return new Dtos.PaymentRunResponse(
        r.id(),
        r.reference(),
        r.status(),
        r.payUpTo(),
        r.paymentDate(),
        r.currency(),
        r.total(),
        r.proposedBy(),
        r.proposedAt(),
        r.approvedBy(),
        r.approvedAt(),
        r.paidBy(),
        r.paidAt(),
        r.cancelledBy(),
        r.cancelledAt(),
        r.cancelReason(),
        v.proposal().payments().stream()
            .map(
                p -> {
                  var s = v.suppliers().get(p.supplierId());
                  return new Dtos.PaymentRunSupplierResponse(
                      p.supplierId(),
                      p.name(),
                      p.net(),
                      s != null && s.remittanceEmail() != null,
                      p.warnings(),
                      p.documents().stream()
                          .map(
                              d ->
                                  new Dtos.PaymentRunDocumentResponse(
                                      d.type(),
                                      d.documentId(),
                                      d.storeId(),
                                      d.reference(),
                                      d.documentDate(),
                                      d.dueDate(),
                                      d.amount()))
                          .toList(),
                      toDto(v.checks().get(p.supplierId())));
                })
            .toList(),
        v.proposal().excluded().stream()
            .map(
                e ->
                    new Dtos.PaymentRunExcludedResponse(
                        e.supplierId(), e.name(), e.reason(), e.net()))
            .toList());
  }

  /** An order left open on the sales receipts clearing account, in wire form. */
  public static Dtos.SalesClearingResponse toDto(Domain.OpenClearing o) {
    return new Dtos.SalesClearingResponse(
        o.orderId(), o.storeId(), o.balance(), o.firstPosted(), o.lastPosted());
  }

  public static Dtos.DeferredRevenueResponse toDto(Domain.DeferredRevenueView v) {
    return new Dtos.DeferredRevenueResponse(
        toDto(v.current()),
        v.history().stream().map(s -> toDto(s)).toList(),
        v.points().outstanding(),
        v.points().deferred(),
        v.points().unmatched(),
        v.waiting(),
        v.giftCards().loaded(),
        v.giftCards().redeemed(),
        v.giftCards().breakage(),
        v.giftCards().liability());
  }

  public static Dtos.DeferredRevenueSettingsResponse toDto(Domain.DeferredRevenueSettings s) {
    return s == null
        ? null
        : new Dtos.DeferredRevenueSettingsResponse(
            s.currency(),
            s.pointValue(),
            s.pointsBreakagePct(),
            s.giftCardBreakagePct(),
            s.reason(),
            s.setBy(),
            s.setAt());
  }

  public static Dtos.PayeeCheckResponse toDto(
      com.storeql.purchase.domain.PaymentRuns.PayeeCheck c) {
    return c == null
        ? null
        : new Dtos.PayeeCheckResponse(
            c.endToEndId(),
            c.status(),
            c.reasonCode(),
            c.payeeMatch(),
            c.matchedName(),
            c.held(),
            c.releasable(),
            c.releasedBy(),
            c.releasedAt(),
            c.releaseReason());
  }

  public static Dtos.PayingAccountResponse toDto(
      com.storeql.purchase.domain.PayingAccounts.PayingAccount a) {
    return new Dtos.PayingAccountResponse(
        a.currency(),
        a.accountName(),
        a.sortCode(),
        com.storeql.purchase.domain.BankAccount.masked(a.accountNumber()),
        com.storeql.purchase.domain.BankAccount.masked(a.iban()),
        a.bic(),
        a.serviceUserNumber(),
        a.sendsBacs(),
        a.sendsSepa(),
        a.setBy(),
        a.setAt());
  }

  // ── Landed cost (07.x) ──────────────────────────────────────────────────────

  /** A landed charge and its lines, on the wire. */
  public static LandedCostResponse toDto(LandedCost.Charge c, List<LandedCost.Line> lines) {
    return new LandedCostResponse(
        c.id(),
        c.grId(),
        c.poId(),
        c.storeId(),
        c.chargeType(),
        c.basis(),
        c.currency(),
        c.amount(),
        c.reference(),
        c.chargedBy(),
        c.notes(),
        c.status(),
        c.appliedAt(),
        c.appliedBy(),
        c.reversedAt(),
        c.reversedBy(),
        c.reversedReason(),
        lines.stream().map(Mappers::toDto).toList());
  }

  public static LandedCostLineResponse toDto(LandedCost.Line l) {
    return new LandedCostLineResponse(
        l.id(), l.grLineId(), l.variantId(), l.qty(), l.lineValue(), l.amount(), l.perUnit());
  }

  /** A proposal run on the wire, its orders described (06.x). */
  public static ProposalRunResponse toDto(ProposalService.RunResult r) {
    return new ProposalRunResponse(
        r.run().id(),
        r.run().storeId(),
        r.run().ranAt().toString(),
        r.run().coverDays(),
        r.run().considered(),
        r.orders().stream()
            .map(
                o ->
                    new ProposedOrderResponse(
                        o.poId(),
                        o.supplierId(),
                        o.supplierName(),
                        o.currency(),
                        o.lines(),
                        o.totalNet()))
            .toList(),
        r.run().skipped().stream()
            .map(x -> new SkippedItemResponse(x.variantId(), x.reason()))
            .toList());
  }
}
