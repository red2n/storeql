package com.storeql.payment.mapper;

import com.storeql.payment.domain.Domain.PaymentIntent;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.domain.Domain.RefundTender;
import com.storeql.payment.domain.Domain.TenderMixRow;
import com.storeql.payment.domain.PendingWork;
import com.storeql.payment.dto.Dtos.PaymentIntentResponse;
import com.storeql.payment.dto.Dtos.RefundResponse;
import com.storeql.payment.dto.Dtos.TenderMixRowResponse;
import com.storeql.payment.dto.Dtos.TenderResponse;
import com.storeql.payment.dto.PendingWorkDtos.PendingWorkResponse;

/** Maps payment-svc domain records to the DTOs served over HTTP. */
public final class Mappers {

  private Mappers() {}

  /**
   * Converts a captured tender to its wire form.
   *
   * @param t the captured tender to convert
   * @return its API representation
   */
  public static TenderResponse toDto(PaymentTender t) {
    return new TenderResponse(
        t.id(),
        t.orderId(),
        t.amount(),
        t.method(),
        t.reference(),
        t.status(),
        t.notes(),
        t.createdAt());
  }

  /**
   * Converts a refund to its wire form.
   *
   * @param r the refund to convert
   * @return its API representation
   */
  public static RefundResponse toDto(RefundTender r) {
    return new RefundResponse(
        r.id(),
        r.orderId(),
        r.paymentId(),
        r.amount(),
        r.method(),
        r.reference(),
        r.reason(),
        r.createdAt());
  }

  /**
   * @param i the intent
   * @return its API representation
   */
  public static PaymentIntentResponse toDto(PaymentIntent i) {
    return new PaymentIntentResponse(
        i.id().toString(),
        i.orderId().toString(),
        i.provider(),
        i.status(),
        i.amount(),
        i.capturedAmount(),
        i.currency(),
        i.nextActionUrl(),
        i.failureCode(),
        i.failureMessage(),
        i.paymentId() == null ? null : i.paymentId().toString(),
        i.createdAt().toString());
  }

  /**
   * Converts one tender-mix row to its wire form.
   *
   * @param r the per-method totals to convert
   * @return its API representation, including that method's share of net takings
   */
  public static TenderMixRowResponse toTenderMixRow(TenderMixRow r) {
    return new TenderMixRowResponse(
        r.method(),
        r.capturedAmount(),
        r.capturedCount(),
        r.refundedAmount(),
        r.refundedCount(),
        r.failedCount(),
        r.netAmount(),
        r.shareOfNet());
  }

  /** What waits for a person in payments on the wire (the system-health screen). */
  public static PendingWorkResponse toDto(PendingWork w) {
    return new PendingWorkResponse(w.cardRefundDuesNeedingAttention());
  }
}
