package com.storeql.order.mapper;

import com.storeql.order.domain.Domain;
import com.storeql.order.domain.Domain.AuditEvent;
import com.storeql.order.domain.Domain.ExceptionRow;
import com.storeql.order.domain.Domain.GiftCard;
import com.storeql.order.domain.Domain.GiftCardTransaction;
import com.storeql.order.domain.Domain.Layaway;
import com.storeql.order.domain.Domain.LayawayDeposit;
import com.storeql.order.domain.Domain.LayawayItem;
import com.storeql.order.domain.Domain.Order;
import com.storeql.order.domain.Domain.OrderItem;
import com.storeql.order.domain.Domain.OrderReceipt;
import com.storeql.order.domain.Domain.OrderStatusHistory;
import com.storeql.order.domain.Domain.PosLogEntry;
import com.storeql.order.domain.Domain.PosVoidLog;
import com.storeql.order.domain.Domain.Return;
import com.storeql.order.domain.Domain.ReturnItem;
import com.storeql.order.domain.Domain.SalesByHourRow;
import com.storeql.order.domain.Domain.SalesByStaffRow;
import com.storeql.order.domain.Domain.SpecialOrder;
import com.storeql.order.domain.Domain.SpecialOrderItem;
import com.storeql.order.dto.Dtos;
import com.storeql.order.dto.Dtos.ExceptionRowResponse;
import com.storeql.order.dto.Dtos.GiftCardResponse;
import com.storeql.order.dto.Dtos.GiftCardTransactionResponse;
import com.storeql.order.dto.Dtos.LayawayDepositResponse;
import com.storeql.order.dto.Dtos.LayawayItemResponse;
import com.storeql.order.dto.Dtos.LayawayResponse;
import com.storeql.order.dto.Dtos.OrderItemResponse;
import com.storeql.order.dto.Dtos.OrderReceiptResponse;
import com.storeql.order.dto.Dtos.OrderResponse;
import com.storeql.order.dto.Dtos.OrderStatusHistoryResponse;
import com.storeql.order.dto.Dtos.OrderSummaryResponse;
import com.storeql.order.dto.Dtos.PosLogEntryResponse;
import com.storeql.order.dto.Dtos.ReturnItemResponse;
import com.storeql.order.dto.Dtos.ReturnResponse;
import com.storeql.order.dto.Dtos.SalesByHourRowResponse;
import com.storeql.order.dto.Dtos.SalesByStaffRowResponse;
import com.storeql.order.dto.Dtos.SpecialOrderItemResponse;
import com.storeql.order.dto.Dtos.SpecialOrderResponse;
import com.storeql.order.dto.Dtos.VoidResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Entity → DTO mappers. Entities never cross the HTTP boundary. */
public final class Mappers {

  private Mappers() {}

  /**
   * Converts one order line to its wire form.
   *
   * @param i one order line to convert
   * @return its API representation
   */
  public static OrderItemResponse toDto(OrderItem i) {
    return new OrderItemResponse(
        str(i.id()),
        str(i.variantId()),
        i.qty(),
        i.unitPrice(),
        i.lineTotal(),
        i.notes(),
        str(i.weighingInstrumentId()),
        i.fulfilledQty(),
        i.vatAmount(),
        str(i.markdownId()),
        i.shortQty(),
        i.remainingQty(),
        str(i.substitutesItemId()));
  }

  /**
   * Converts a legal receipt to its wire form, with the regime's stamp when it carries one (18.5).
   *
   * @param r the document
   * @return its API representation
   */
  public static Dtos.FiscalReceiptResponse toDto(Domain.FiscalReceipt r) {
    Dtos.TseStampResponse tse = null;
    if (r.tse() != null) {
      var t = r.tse();
      tse =
          new Dtos.TseStampResponse(
              t.serialNumber(),
              t.clientId(),
              t.transactionNumber(),
              t.signatureCounter(),
              t.signature(),
              t.algorithm(),
              t.publicKey(),
              t.timeFormat(),
              t.startedAt() == null ? null : t.startedAt().toString(),
              t.finishedAt() == null ? null : t.finishedAt().toString(),
              t.processType(),
              t.processData(),
              t.qr(),
              t.error());
    }
    Dtos.PtStampResponse pt = null;
    if (r.pt() != null) {
      var p = r.pt();
      pt =
          new Dtos.PtStampResponse(
              p.invoiceNo(),
              p.hash(),
              p.hashControl(),
              p.atcud(),
              p.certificateNumber(),
              p.printedExcerpt());
    }
    return new Dtos.FiscalReceiptResponse(
        str(r.id()),
        str(r.storeId()),
        r.seriesCode(),
        r.period(),
        r.number(),
        r.fullNumber(),
        str(r.orderId()),
        r.issuedAt() == null ? null : r.issuedAt().toString(),
        str(r.issuedBy()),
        r.currency(),
        r.grossTotal(),
        r.taxTotal(),
        r.voidedAt() == null ? null : r.voidedAt().toString(),
        r.voidReason(),
        r.prevHash(),
        r.hash(),
        r.regime(),
        tse,
        pt);
  }

  /**
   * Converts a store's fiscal settings, and what the deployment offers beside them, to wire form.
   *
   * @param v the settings view
   * @return its API representation
   */
  public static Dtos.FiscalSettingsResponse toDto(
      com.storeql.order.service.FiscalService.SettingsView v) {
    var s = v.settings();
    Dtos.TseDeviceResponse device = null;
    if (v.device() != null) {
      var d = v.device();
      device =
          new Dtos.TseDeviceResponse(
              str(d.id()),
              d.provider(),
              d.clientId(),
              d.serialNumber(),
              d.publicKey(),
              d.signatureAlgorithm(),
              d.timeFormat(),
              d.externalTssId(),
              d.signatureCounter(),
              d.transactionCounter(),
              d.registeredAt() == null ? null : d.registeredAt().toString());
    }
    return new Dtos.FiscalSettingsResponse(
        str(s.storeId()),
        s.regime(),
        s.taxRegistrationNumber(),
        s.certificateNumber(),
        s.seriesValidationCode(),
        s.updatedAt() == null ? null : s.updatedAt().toString(),
        str(s.updatedBy()),
        device,
        v.regimes(),
        v.tseProviders(),
        v.ptKeyConfigured(),
        v.ptPublicKey());
  }

  /**
   * Converts an order and its lines to the full wire form.
   *
   * @param o the order header
   * @param items the order's lines
   * @return its API representation, header and lines together
   */
  public static OrderResponse toDto(Order o, List<OrderItem> items) {
    return toDto(o, items, null);
  }

  /**
   * Converts an order with its deposit lines (09.16).
   *
   * @param deposits the return-scheme deposits on the sale, or null for a shape that omits them
   */
  public static OrderResponse toDto(
      Order o, List<OrderItem> items, List<com.storeql.order.domain.Domain.OrderDeposit> deposits) {
    return toDto(o, items, deposits, null);
  }

  /**
   * Converts an order with the checkout it is a part of (order orchestration).
   *
   * @param group the split checkout, or null for an order never split
   */
  public static OrderResponse toDto(
      Order o,
      List<OrderItem> items,
      List<com.storeql.order.domain.Domain.OrderDeposit> deposits,
      com.storeql.order.domain.OrderGroup group) {
    return toDto(o, items, deposits, group, null);
  }

  /**
   * Converts an order with its handover (ship-from-store and dark-store picking).
   *
   * @param handover how the picked order was handed over, or null until it is
   */
  public static OrderResponse toDto(
      Order o,
      List<OrderItem> items,
      List<com.storeql.order.domain.Domain.OrderDeposit> deposits,
      com.storeql.order.domain.OrderGroup group,
      com.storeql.order.domain.Handover handover) {
    return new OrderResponse(
        str(o.id()),
        str(o.storeId()),
        str(o.customerId()),
        str(o.loginId()),
        o.channel(),
        o.fulfilmentType(),
        o.status(),
        o.subtotal(),
        o.taxAmount(),
        o.discountAmount(),
        o.promotionDiscount() == null ? java.math.BigDecimal.ZERO : o.promotionDiscount(),
        o.total(),
        o.currency(),
        o.notes(),
        ts(o.createdAt()),
        ts(o.updatedAt()),
        items.stream().map(Mappers::toDto).toList(),
        o.taxExempt(),
        o.exemptReason(),
        o.deliveryLine1(),
        o.deliveryLine2(),
        o.deliveryCity(),
        o.deliveryPostalCode(),
        o.deliveryRecipientName(),
        o.deliveryRecipientPhone(),
        o.contactPhone(),
        o.paymentMethod(),
        deposits == null
            ? null
            : deposits.stream()
                .map(com.storeql.order.domain.Domain.OrderDeposit::amount)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add),
        deposits == null ? null : deposits.stream().map(Mappers::toDto).toList(),
        str(o.sellerUserId()),
        group == null ? null : toDto(group),
        handover == null ? null : toDto(handover),
        o.allowSubstitutions(),
        slotOf(o.slotStartsAt(), o.slotEndsAt(), o.slotTimeZone()),
        o.contactPhoneE164());
  }

  /**
   * The delivery or collection window an order holds (delivery and collection slots), with the
   * date/startTime/endTime already computed in the store's own zone — {@code null} when the order
   * carries no window (a till sale, or a store with none), never a partly-filled shape.
   */
  static Dtos.SlotResponse slotOf(Instant startsAt, Instant endsAt, String timeZone) {
    if (startsAt == null || endsAt == null || timeZone == null) {
      return null;
    }
    java.time.ZoneId zone = java.time.ZoneId.of(timeZone);
    java.time.ZonedDateTime localStart = startsAt.atZone(zone);
    java.time.ZonedDateTime localEnd = endsAt.atZone(zone);
    return new Dtos.SlotResponse(
        startsAt.toString(),
        endsAt.toString(),
        timeZone,
        localStart.toLocalDate().toString(),
        localStart.toLocalTime().toString(),
        localEnd.toLocalTime().toString());
  }

  /** A stand-in suggested for a line (substitutions for out-of-stock online lines). */
  public static com.storeql.order.dto.Dtos.SubstituteSuggestionResponse toDto(
      com.storeql.order.service.OrderService.SubstituteSuggestion s) {
    return new com.storeql.order.dto.Dtos.SubstituteSuggestionResponse(
        str(s.variantId()), s.productName(), s.sku(), s.available());
  }

  /** An order the store still owes something on, with the lines it owes. */
  public static com.storeql.order.dto.Dtos.OwingOrderResponse toDto(
      com.storeql.order.service.OrderService.OwingOrder o) {
    return new com.storeql.order.dto.Dtos.OwingOrderResponse(
        str(o.order().id()),
        o.order().status(),
        o.order().fulfilmentType(),
        o.order().allowSubstitutions(),
        ts(o.order().createdAt()),
        o.lines().stream()
            .map(
                l ->
                    new com.storeql.order.dto.Dtos.OwingLineResponse(
                        str(l.variantId()),
                        l.qty(),
                        l.fulfilledQty(),
                        l.shortQty(),
                        l.remainingQty()))
            .toList());
  }

  /** Converts a handover (ship-from-store and dark-store picking). */
  public static com.storeql.order.dto.Dtos.HandoverResponse toDto(
      com.storeql.order.domain.Handover h) {
    return new com.storeql.order.dto.Dtos.HandoverResponse(
        h.kind(),
        h.carrier(),
        h.reference(),
        h.parcels(),
        h.collectedBy(),
        ts(h.handedAt()),
        str(h.handedBy()));
  }

  /** Converts a split checkout (order orchestration). */
  public static com.storeql.order.dto.Dtos.OrderGroupResponse toDto(
      com.storeql.order.domain.OrderGroup g) {
    return new com.storeql.order.dto.Dtos.OrderGroupResponse(
        str(g.id()),
        str(g.loginId()),
        g.total(),
        g.currency(),
        ts(g.createdAt()),
        g.parts().stream()
            .map(
                p ->
                    new com.storeql.order.dto.Dtos.OrderPartResponse(
                        str(p.orderId()),
                        str(p.storeId()),
                        p.status(),
                        p.total(),
                        p.units(),
                        slotOf(p.slotStartsAt(), p.slotEndsAt(), p.slotTimeZone())))
            .toList());
  }

  /** Converts one deposit line (09.16). */
  public static com.storeql.order.dto.Dtos.OrderDepositResponse toDto(
      com.storeql.order.domain.Domain.OrderDeposit d) {
    return new com.storeql.order.dto.Dtos.OrderDepositResponse(
        str(d.variantId()),
        d.material(),
        d.volumeMl(),
        d.qty(),
        d.depositEach(),
        d.amount(),
        d.vatTreatment(),
        d.vatRate(),
        d.vatAmount(),
        d.schemeScope(),
        d.citation());
  }

  /** Converts a till refund of container deposits (09.16). */
  public static com.storeql.order.dto.Dtos.ContainerRefundResponse toDto(
      com.storeql.order.domain.Domain.ContainerRefund r) {
    return new com.storeql.order.dto.Dtos.ContainerRefundResponse(
        str(r.id()),
        str(r.storeId()),
        str(r.tillSessionId()),
        r.currency(),
        r.containers(),
        r.amount(),
        r.schemeScope(),
        str(r.refundedBy()),
        ts(r.createdAt()),
        r.lines().stream()
            .map(
                l ->
                    new com.storeql.order.dto.Dtos.ContainerRefundLineResponse(
                        l.material(), l.volumeMl(), l.count(), l.depositEach(), l.amount()))
            .toList());
  }

  /**
   * Converts an order summary to its wire form.
   *
   * @param o the order summary to convert
   * @return its API representation
   */
  public static OrderSummaryResponse toSummary(Order o) {
    return toSummary(o, null);
  }

  /**
   * Converts an order to its list form, naming the split checkout it is a part of.
   *
   * @param groupId the checkout, or null for an order never split
   */
  public static OrderSummaryResponse toSummary(Order o, java.util.UUID groupId) {
    return toSummary(o, groupId, null);
  }

  /**
   * Converts an order to its list form with its handover (ship-from-store).
   *
   * @param handover how the picked order was handed over, or null until it is
   */
  public static OrderSummaryResponse toSummary(
      Order o, java.util.UUID groupId, com.storeql.order.domain.Handover handover) {
    return new OrderSummaryResponse(
        str(o.id()),
        str(o.storeId()),
        str(o.customerId()),
        o.channel(),
        o.fulfilmentType(),
        o.status(),
        o.subtotal(),
        o.taxAmount(),
        o.discountAmount(),
        o.total(),
        o.currency(),
        ts(o.createdAt()),
        ts(o.updatedAt()),
        o.paymentMethod(),
        str(groupId),
        handover == null ? null : toDto(handover),
        o.allowSubstitutions(),
        slotOf(o.slotStartsAt(), o.slotEndsAt(), o.slotTimeZone()));
  }

  /**
   * Converts one status transition to its wire form.
   *
   * @param h one status transition to convert
   * @return its API representation
   */
  public static OrderStatusHistoryResponse toDto(OrderStatusHistory h) {
    return new OrderStatusHistoryResponse(
        str(h.id()),
        str(h.orderId()),
        h.fromStatus(),
        h.toStatus(),
        h.reason(),
        str(h.changedBy()),
        ts(h.changedAt()));
  }

  /**
   * Converts one returned line to its wire form.
   *
   * @param ri one returned line to convert
   * @return its API representation
   */
  public static ReturnItemResponse toDto(ReturnItem ri) {
    return new ReturnItemResponse(
        str(ri.id()), str(ri.variantId()), ri.qty(), ri.refundAmount(), ri.condition());
  }

  /**
   * Converts a return and its lines to the full wire form.
   *
   * @param r the return header
   * @param items the returned lines
   * @return its API representation, header and lines together
   */
  public static ReturnResponse toDto(Return r, List<ReturnItem> items) {
    return new ReturnResponse(
        str(r.id()),
        str(r.orderId()),
        str(r.storeId()),
        r.reason(),
        r.refundAmount(),
        r.refundMethod(),
        r.status(),
        str(r.createdBy()),
        ts(r.createdAt()),
        ts(r.completedAt()),
        items.stream().map(Mappers::toDto).toList());
  }

  /**
   * Converts an audit-trail event to its wire form.
   *
   * @param e the event
   * @return its API representation
   */
  public static Dtos.AuditEventResponse toDto(AuditEvent e) {
    return new Dtos.AuditEventResponse(
        str(e.id()),
        e.type(),
        ts(e.occurredAt()),
        str(e.actorId()),
        str(e.storeId()),
        str(e.orderId()),
        e.amount(),
        e.reason(),
        e.detail());
  }

  /**
   * Converts a recorded void to its wire form.
   *
   * @param vl the recorded void to convert
   * @return its API representation
   */
  public static VoidResponse toDto(PosVoidLog vl) {
    return new VoidResponse(str(vl.orderId()), vl.reason(), ts(vl.voidedAt()));
  }

  /**
   * Converts one layaway line to its wire form.
   *
   * @param li one layaway line to convert
   * @return its API representation
   */
  public static LayawayItemResponse toDto(LayawayItem li) {
    return new LayawayItemResponse(
        str(li.id()), str(li.variantId()), li.qty(), li.unitPrice(), li.lineTotal());
  }

  /**
   * Converts one layaway payment to its wire form.
   *
   * @param d one layaway payment to convert
   * @return its API representation
   */
  public static LayawayDepositResponse toDto(LayawayDeposit d) {
    return new LayawayDepositResponse(
        str(d.id()), d.amount(), d.paymentMethod(), d.reference(), ts(d.paidAt()));
  }

  /**
   * Converts a layaway with its goods and payments to the full wire form.
   *
   * @param l the layaway header, carrying the total and outstanding balance
   * @param items the goods set aside
   * @param deposits the payments made so far
   * @return its API representation, header, items and deposits together
   */
  public static LayawayResponse toDto(
      Layaway l, List<LayawayItem> items, List<LayawayDeposit> deposits) {
    return new LayawayResponse(
        str(l.id()),
        str(l.storeId()),
        str(l.customerId()),
        l.totalAmount(),
        l.depositPaid(),
        l.balance(),
        l.status(),
        l.notes(),
        ts(l.createdAt()),
        ts(l.dueDate()),
        ts(l.completedAt()),
        ts(l.cancelledAt()),
        items.stream().map(Mappers::toDto).toList(),
        deposits.stream().map(Mappers::toDto).toList());
  }

  /**
   * Converts a gift card to its wire form.
   *
   * @param gc the gift card to convert
   * @return its API representation
   */
  public static GiftCardResponse toDto(GiftCard gc) {
    return new GiftCardResponse(
        str(gc.id()),
        str(gc.storeId()),
        gc.code(),
        gc.initialBalance(),
        gc.currentBalance(),
        gc.status(),
        gc.currency(),
        ts(gc.issuedAt()),
        ts(gc.expiresAt()));
  }

  /**
   * Converts one gift-card transaction to its wire form.
   *
   * @param tx one gift-card transaction to convert
   * @return its API representation
   */
  public static GiftCardTransactionResponse toDto(GiftCardTransaction tx) {
    return new GiftCardTransactionResponse(
        str(tx.id()),
        tx.txType(),
        tx.amount(),
        tx.balanceBefore(),
        tx.balanceAfter(),
        str(tx.orderId()),
        tx.reference(),
        ts(tx.createdAt()));
  }

  /**
   * Converts one special-order line to its wire form.
   *
   * @param i one special-order line to convert
   * @return its API representation
   */
  public static SpecialOrderItemResponse toDto(SpecialOrderItem i) {
    return new SpecialOrderItemResponse(
        str(i.id()), str(i.variantId()), i.qty(), i.unitPrice(), i.lineTotal(), i.notes());
  }

  /**
   * Converts a special order and its lines to the full wire form.
   *
   * @param so the special-order header
   * @param items the ordered lines
   * @return its API representation, header and lines together
   */
  public static SpecialOrderResponse toDto(SpecialOrder so, List<SpecialOrderItem> items) {
    return new SpecialOrderResponse(
        str(so.id()),
        str(so.storeId()),
        str(so.customerId()),
        so.customerName(),
        so.customerPhone(),
        so.customerEmail(),
        so.deliveryAddress(),
        so.requestedDeliveryDate() != null ? so.requestedDeliveryDate().toString() : null,
        so.notes(),
        so.status(),
        so.subtotal(),
        so.total(),
        so.currency(),
        ts(so.createdAt()),
        ts(so.updatedAt()),
        items.stream().map(Mappers::toDto).toList());
  }

  /**
   * Converts one exception-report row to its wire form.
   *
   * @param r one exception-report row to convert
   * @return its API representation
   */
  public static ExceptionRowResponse toDto(ExceptionRow r) {
    return new ExceptionRowResponse(
        r.groupKey(),
        r.discounts(),
        r.discountAmount(),
        r.voids(),
        r.noSales(),
        r.sales(),
        r.salesValue());
  }

  /**
   * Converts one POSLog entry to its wire form.
   *
   * @param e one POSLog entry to convert
   * @return its API representation
   */
  public static PosLogEntryResponse toDto(PosLogEntry e) {
    return new PosLogEntryResponse(
        str(e.id()),
        str(e.orderId()),
        str(e.storeId()),
        str(e.cashierId()),
        e.subtotal(),
        e.taxAmount(),
        e.discountAmount(),
        e.total(),
        e.currency(),
        e.taxExempt(),
        e.exemptReason(),
        ts(e.transactionTs()),
        ts(e.createdAt()));
  }

  /**
   * Converts one receipt event to its wire form.
   *
   * @param r one receipt event to convert
   * @return its API representation
   */
  public static OrderReceiptResponse toDto(OrderReceipt r) {
    return new OrderReceiptResponse(
        str(r.id()),
        str(r.orderId()),
        r.receiptType(),
        r.emailedTo(),
        r.printCount(),
        ts(r.generatedAt()));
  }

  private static String str(Object o) {
    return o == null ? null : o.toString();
  }

  private static String ts(Instant i) {
    return i == null ? null : i.toString();
  }

  /**
   * Converts one hourly sales bucket to its wire form.
   *
   * @param r one hourly sales bucket to convert
   * @return its API representation
   */
  public static SalesByHourRowResponse toDto(SalesByHourRow r) {
    return new SalesByHourRowResponse(
        r.hourOfDay(), r.orders(), r.grossAmount(), r.discountAmount(), r.averageBasket());
  }

  /**
   * Converts one staff member's sales to its wire form.
   *
   * @param r one staff member's sales to convert
   * @return its API representation
   */
  public static SalesByStaffRowResponse toDto(SalesByStaffRow r) {
    return new SalesByStaffRowResponse(
        r.groupKey(),
        r.sales(),
        r.grossAmount(),
        r.discountAmount(),
        r.averageBasket(),
        r.discountRate());
  }

  /**
   * Converts one recorded age check to its wire form.
   *
   * @param v the record
   * @return its API representation
   */
  public static com.storeql.order.dto.Dtos.AgeVerificationResponse toDto(
      com.storeql.order.domain.Domain.AgeVerification v) {
    return new com.storeql.order.dto.Dtos.AgeVerificationResponse(
        str(v.id()),
        str(v.storeId()),
        str(v.cashierId()),
        str(v.posSessionId()),
        str(v.variantId()),
        v.category(),
        v.minimumAge(),
        v.country(),
        v.storePolicy(),
        v.bornBefore() == null ? null : v.bornBefore().toString(),
        v.bornBeforePolicy(),
        v.outcome(),
        v.reason(),
        v.idType(),
        str(v.orderId()),
        ts(v.checkedAt()));
  }

  /**
   * Converts the period summary to its wire form.
   *
   * @param s the counts
   * @return its API representation
   */
  public static com.storeql.order.dto.Dtos.AgeVerificationSummaryResponse toDto(
      com.storeql.order.domain.Domain.AgeVerificationSummary s) {
    return new com.storeql.order.dto.Dtos.AgeVerificationSummaryResponse(
        s.total(), s.passed(), s.refused(), s.refusedByReason(), s.byCategory());
  }

  // ── Delivery and collection slots ─────────────────────────────────────────

  /** Converts a stored window to its wire form. */
  public static com.storeql.order.dto.Dtos.FulfilmentWindowResponse toDto(
      com.storeql.order.domain.Windows.WindowRecord r) {
    com.storeql.order.domain.Windows.Window w = r.window();
    return new com.storeql.order.dto.Dtos.FulfilmentWindowResponse(
        str(w.id()),
        str(w.storeId()),
        w.fulfilmentType(),
        w.weekday(),
        w.startTime().toString(),
        w.endTime().toString(),
        w.capacity(),
        w.cutoffMinutes(),
        w.active(),
        r.timeZone(),
        ts(r.updatedAt()),
        str(r.updatedBy()));
  }

  /**
   * Converts the storefront's read of a store's next seven days to its wire form: each occurrence's
   * server-computed local times, and how many places it has left.
   */
  public static com.storeql.order.dto.Dtos.FulfilmentSlotsResponse toDto(
      com.storeql.order.service.FulfilmentWindowService.SlotsView v) {
    List<com.storeql.order.dto.Dtos.FulfilmentSlotDayResponse> days =
        v.days().stream()
            .map(
                day -> {
                  List<com.storeql.order.dto.Dtos.FulfilmentSlotResponse> slots =
                      day.occurrences().stream().map(occ -> toDto(occ, v)).toList();
                  return new com.storeql.order.dto.Dtos.FulfilmentSlotDayResponse(
                      day.date().toString(), slots);
                })
            .toList();
    return new com.storeql.order.dto.Dtos.FulfilmentSlotsResponse(
        str(v.storeId()), v.fulfilmentType(), v.timeZone(), v.offered(), days);
  }

  private static com.storeql.order.dto.Dtos.FulfilmentSlotResponse toDto(
      com.storeql.order.domain.Windows.Occurrence occ,
      com.storeql.order.service.FulfilmentWindowService.SlotsView v) {
    long taken = v.taken().getOrDefault(occ.windowId(), Map.of()).getOrDefault(occ.startsAt(), 0L);
    int capacity = v.capacityByWindow().getOrDefault(occ.windowId(), occ.capacity());
    int left = (int) Math.max(0, capacity - taken);
    return new com.storeql.order.dto.Dtos.FulfilmentSlotResponse(
        str(occ.windowId()),
        ts(occ.startsAt()),
        ts(occ.endsAt()),
        occ.localStartTime().toString(),
        occ.localEndTime().toString(),
        left,
        taken >= capacity);
  }
}
