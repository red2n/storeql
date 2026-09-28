package com.storeql.payment.service;

import com.storeql.ids.Ids;
import com.storeql.payment.client.OrderClient;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.domain.Domain.RefundTender;
import com.storeql.payment.dto.Dtos.RecordRefundRequest;
import com.storeql.payment.dto.Dtos.RecordTenderRequest;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.service.Jurisdictions;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Business logic for payment-svc. Thin resource → this service → repository.
 *
 * <p>Two capture paths with different trust models: {@link #recordTender} is staff-initiated, where
 * the caller's role is the trust boundary, and {@link #recordOnlinePayment} is customer-initiated,
 * where the claim is verified against order-svc before anything is captured. Both funnel into the
 * same private capture, so the tender rules hold identically across POS and online.
 *
 * <p>Payments and refunds are append-only: nothing here updates a captured tender in place.
 */
@ApplicationScoped
public class PaymentService {

  private static final Set<String> VALID_METHODS =
      Set.of(
          PaymentTender.METHOD_CASH,
          PaymentTender.METHOD_CARD,
          PaymentTender.METHOD_UPI,
          PaymentTender.METHOD_WALLET,
          PaymentTender.METHOD_GIFT_CARD,
          PaymentTender.METHOD_VOUCHER,
          PaymentTender.METHOD_STORE_CREDIT);

  /**
   * The methods the store owner can turn on/off per store (tenant-svc {@code
   * enabledPaymentMethods}). GIFT_CARD and VOUCHER are store-issued instruments, not tenders the
   * owner disables, so they're exempt from the per-store toggle.
   */
  private static final Set<String> STORE_TOGGLEABLE_METHODS =
      Set.of(
          PaymentTender.METHOD_CASH,
          PaymentTender.METHOD_CARD,
          PaymentTender.METHOD_UPI,
          PaymentTender.METHOD_WALLET);

  @Inject PaymentRepository repo;
  @Inject com.storeql.service.TenantProfiles profiles;
  @Inject Jurisdictions jurisdictions;
  @Inject OrderClient orderClient;
  @Inject OrderPaymentGuard guard;
  @Inject com.storeql.payment.client.TenantStoreClient storeClient;
  @Inject com.storeql.payment.client.CustomerClient customerClient;

  /**
   * Staff-recorded tender (POS/back-office) — the caller's role is the trust boundary.
   *
   * @param req the order, amount, method and optional reference/notes
   * @param ctx caller context; supplies the tenant and is checked for store access
   * @param idempotencyKey the caller's {@code Idempotency-Key}, so a retried capture does not take
   *     payment twice
   * @return the captured tender
   * @throws ApiException {@code PAYMENT_INVALID_METHOD} (400) for an unknown method; {@code
   *     PAYMENT_METHOD_DISABLED} (422) when the store owner has switched that method off
   */
  public PaymentTender recordTender(
      RecordTenderRequest req, TenantContext ctx, String idempotencyKey) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = req.storeId() == null ? null : Ids.parse(req.storeId());
    if (storeId != null) {
      ctx.requireStoreAccess(storeId);
    }
    return capture(req, tenantId, Ids.parse(req.orderId()), storeId, idempotencyKey);
  }

  /**
   * Customer-initiated online tender — no staff role guards this endpoint, so the claim is verified
   * against order-svc (the data owner) before it's captured: the order must exist in the tenant,
   * must be an ONLINE order, must belong to the caller when the caller is an authenticated
   * customer, and the claimed amount must match the order total exactly.
   *
   * @param req the order, amount, method and optional reference/notes
   * @param ctx caller context; supplies the tenant and the customer identity, if any
   * @param idempotencyKey the caller's {@code Idempotency-Key}, so a retried capture does not take
   *     payment twice
   * @return the captured tender
   * @throws ApiException when the order does not exist in the tenant, is not an {@code ONLINE}
   *     order, does not belong to the caller, or the amount does not match the order total
   */
  public PaymentTender recordOnlinePayment(
      RecordTenderRequest req, TenantContext ctx, String idempotencyKey) {
    UUID tenantId = ctx.requireTenantId();
    UUID orderId = Ids.parse(req.orderId());
    // Shared with the payment-intent path — see OrderPaymentGuard for what is checked and why.
    UUID storeId = guard.verifyOnlineClaim(tenantId, orderId, req.amount(), ctx).storeId();
    return capture(req, tenantId, orderId, storeId, idempotencyKey);
  }

  /** One payment for a split checkout: the tender captured for each part. */
  public record GroupPayment(UUID groupId, BigDecimal total, List<PaymentTender> tenders) {
    public GroupPayment {
      tenders = List.copyOf(tenders);
    }
  }

  /**
   * One online payment for a delivery checkout split across shops (order orchestration). The claim
   * is verified against order-svc as a single order's is, made of the whole checkout; then one
   * tender is captured per part — its total, its store — on one transaction, each announcing its
   * PaymentCaptured so order-svc confirms that part. A refund stays per part: each tender is its
   * order's. A retry with the same key replays the tenders taken.
   *
   * @throws ApiException 400 {@code PAYMENT_INVALID_METHOD} for a method other than CARD, UPI or
   *     WALLET; the guard's refusals; 422 when a part's store has the method switched off
   */
  public GroupPayment recordOnlineGroupPayment(
      RecordTenderRequest req, TenantContext ctx, String idempotencyKey) {
    UUID tenantId = ctx.requireTenantId();
    UUID groupId = Ids.parse(req.groupId());
    String method = req.method().toUpperCase(Locale.ROOT);
    if (!STORE_TOGGLEABLE_METHODS.contains(method) || PaymentTender.METHOD_CASH.equals(method)) {
      throw ApiException.badRequest(
          "PAYMENT_INVALID_METHOD", "a checkout is paid by CARD, UPI or WALLET — got: " + method);
    }
    OrderClient.GroupInfo group = guard.verifyGroupClaim(tenantId, groupId, req.amount(), ctx);
    for (OrderClient.GroupPart part : group.parts()) {
      requireMethodEnabledForStore(tenantId, part.storeId(), method);
    }
    UUID keyBase = idempotencyKey == null ? null : Ids.parse(idempotencyKey);
    Instant now = Instant.now();
    List<PaymentTender> tenders = new java.util.ArrayList<>();
    List<com.storeql.service.OutboxRow> events = new java.util.ArrayList<>();
    for (OrderClient.GroupPart part : group.parts()) {
      UUID tenderId = Ids.newId();
      tenders.add(
          new PaymentTender(
              tenderId,
              tenantId,
              part.orderId(),
              part.total(),
              method,
              req.reference(),
              keyBase == null ? null : Ids.derived(keyBase, "part:" + part.orderId()).toString(),
              PaymentTender.STATUS_CAPTURED,
              req.notes(),
              now,
              part.storeId()));
      events.add(
          Events.paymentCaptured(
              tenantId, tenderId, part.orderId(), part.total(), method, part.storeId()));
    }
    return new GroupPayment(groupId, group.total(), repo.createTenders(tenders, events));
  }

  private PaymentTender capture(
      RecordTenderRequest req, UUID tenantId, UUID orderId, UUID storeId, String idempotencyKey) {
    String method = req.method().toUpperCase(Locale.ROOT);
    if (!VALID_METHODS.contains(method))
      throw ApiException.badRequest(
          "PAYMENT_INVALID_METHOD",
          "method must be one of CASH, CARD, UPI, WALLET, GIFT_CARD, VOUCHER, STORE_CREDIT — got: "
              + req.method());
    requireMethodEnabledForStore(tenantId, storeId, method);
    if (PaymentTender.METHOD_CASH.equals(method)) {
      requireUnderCashLimit(tenantId, orderId, storeId, req);
    }

    if (PaymentTender.METHOD_STORE_CREDIT.equals(method)) {
      return captureStoreCredit(req, tenantId, orderId, storeId);
    }

    UUID tenderId = Ids.newId();
    PaymentTender tender =
        new PaymentTender(
            tenderId,
            tenantId,
            orderId,
            req.amount(),
            method,
            req.reference(),
            idempotencyKey,
            PaymentTender.STATUS_CAPTURED,
            req.notes(),
            Instant.now(),
            storeId);

    return repo.createTender(
        tender, Events.paymentCaptured(tenantId, tenderId, orderId, req.amount(), method, storeId));
  }

  /**
   * Redeem store credit as tender toward the order. Keyed idempotently on a key derived from the
   * order ({@code Ids.derived(orderId, "store-credit")}, a UUIDv7 like every key): a repeat
   * store-credit tender for the same order returns the existing tender without redeeming again
   * (belt-and-suspenders with customer-svc's own per-order redeem idempotency). The redeem happens
   * BEFORE the tender is recorded, so an insufficient balance (422) or an unreachable customer-svc
   * (503) rejects the tender rather than inflating {@code paid_amount}.
   */
  private PaymentTender captureStoreCredit(
      RecordTenderRequest req, UUID tenantId, UUID orderId, UUID storeId) {
    if (req.customerId() == null || req.customerId().isBlank())
      throw ApiException.badRequest(
          "PAYMENT_CUSTOMER_REQUIRED", "customerId is required for a STORE_CREDIT tender");
    UUID customerId = Ids.parse(req.customerId());
    // The tenant's own currency when the tender names none — never a literal (SJ-D53).
    String currency = profiles.currencyOr(tenantId, req.currency());
    String key = Ids.derived(orderId, "store-credit").toString();

    Optional<PaymentTender> existing = repo.findTenderByKey(tenantId, key);
    if (existing.isPresent()) {
      return existing.get();
    }

    customerClient.redeemStoreCredit(tenantId, customerId, req.amount(), currency, orderId);

    UUID tenderId = Ids.newId();
    PaymentTender tender =
        new PaymentTender(
            tenderId,
            tenantId,
            orderId,
            req.amount(),
            PaymentTender.METHOD_STORE_CREDIT,
            req.reference(),
            key,
            PaymentTender.STATUS_CAPTURED,
            req.notes(),
            Instant.now(),
            storeId);

    return repo.createTender(
        tender,
        Events.paymentCaptured(
            tenantId, tenderId, orderId, req.amount(), PaymentTender.METHOD_STORE_CREDIT, storeId));
  }

  /**
   * Rejects a tender whose method the store owner has switched off (tenant-svc store setting).
   * Fails open when the setting can't be read right now: a briefly unreachable tenant-svc must not
   * stop every sale in the shop.
   */
  /**
   * Refuses cash that would reach the limit the law sets where the store trades (09.17): the EU's
   * EUR 10,000 from 10 July 2027, a member state's lower limit, India's two lakh rupees. What was
   * already taken in cash for the same sale counts — a split payment for the same goods is one
   * payment — so the limit cannot be walked round in instalments.
   *
   * @throws ApiException 409 {@code PAYMENT_CASH_LIMIT_EXCEEDED}
   */
  private void requireUnderCashLimit(
      UUID tenantId, UUID orderId, UUID storeId, RecordTenderRequest req) {
    String currency = profiles.currencyOr(tenantId, req.currency());
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Optional<Jurisdictions.CashLimit> limit =
        jurisdictions.cashLimit(tenantId, storeId, currency, today);
    if (limit.isEmpty()) return;
    BigDecimal cashSoFar =
        repo.findTendersByOrder(tenantId, orderId).stream()
            .filter(
                t ->
                    PaymentTender.METHOD_CASH.equals(t.method())
                        && PaymentTender.STATUS_CAPTURED.equals(t.status()))
            .map(PaymentTender::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal cash = cashSoFar.add(req.amount());
    if (limit.get().refuses(cash)) {
      cash = cash.setScale(2, java.math.RoundingMode.HALF_UP);
      cashSoFar = cashSoFar.setScale(2, java.math.RoundingMode.HALF_UP);
      throw ApiException.conflict(
          "PAYMENT_CASH_LIMIT_EXCEEDED",
          "cash for this sale would come to "
              + currency
              + " "
              + cash.toPlainString()
              + (cashSoFar.signum() > 0
                  ? " (" + cashSoFar.toPlainString() + " already taken in cash)"
                  : "")
              + ": "
              + limit.get().citation()
              + " refuses cash of "
              + currency
              + " "
              + limit.get().fromAmount().toPlainString()
              + " or more; take the balance another way");
    }
  }

  private void requireMethodEnabledForStore(UUID tenantId, UUID storeId, String method) {
    if (storeId == null || !STORE_TOGGLEABLE_METHODS.contains(method)) return;
    Optional<Set<String>> enabled = storeClient.enabledMethods(tenantId, storeId);
    if (enabled.isPresent() && !enabled.get().contains(method))
      throw ApiException.unprocessable(
          "PAYMENT_METHOD_DISABLED",
          method + " payments are not enabled for this store (enabled: " + enabled.get() + ")");
  }

  /**
   * Reads a tender by id with tenant scoping but <strong>no</strong> object-level authorization.
   *
   * <p>For internal callers only — anything serving a request should use {@link #getTender(UUID,
   * UUID, TenantContext)} so a customer cannot read another customer's payment.
   *
   * @param tenantId owning tenant
   * @param tenderId the tender to read
   * @return the tender
   * @throws ApiException {@code PAYMENT_NOT_FOUND} (404) when no such tender exists in this tenant
   */
  public PaymentTender getTender(UUID tenantId, UUID tenderId) {
    return repo.findTender(tenantId, tenderId)
        .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "payment tender not found"));
  }

  /**
   * Payment-by-id read for the API: tenant scope plus object-level authorization.
   *
   * @param tenantId owning tenant
   * @param tenderId the tender to read
   * @param ctx caller context, resolved against the tender's order to decide access
   * @return the tender
   * @throws ApiException {@code PAYMENT_NOT_FOUND} (404) when no such tender exists or the caller
   *     may not read it — denials are 404 so ids cannot be probed for existence
   */
  public PaymentTender getTender(UUID tenantId, UUID tenderId, TenantContext ctx) {
    PaymentTender tender = getTender(tenantId, tenderId);
    requireReadAccess(tenantId, tender.orderId(), ctx);
    return tender;
  }

  /**
   * Lists an order's captured tenders with tenant scoping but <strong>no</strong> object-level
   * authorization.
   *
   * <p>For internal callers only — request-serving code should use the {@link TenantContext}
   * overload. A split-tender sale returns one row per tender.
   *
   * @param tenantId owning tenant
   * @param orderId the order whose tenders to list
   * @return the captured tenders, empty when nothing has been paid
   */
  public List<PaymentTender> listTendersByOrder(UUID tenantId, UUID orderId) {
    return repo.findTendersByOrder(tenantId, orderId);
  }

  /**
   * Lists an order's captured tenders for the API: tenant scope plus object-level authorization.
   *
   * @param tenantId owning tenant
   * @param orderId the order whose tenders to list
   * @param ctx caller context, resolved against the order to decide access
   * @return the captured tenders, empty when nothing has been paid
   * @throws ApiException {@code PAYMENT_NOT_FOUND} (404) when the caller may not read this order's
   *     payments
   */
  public List<PaymentTender> listTendersByOrder(UUID tenantId, UUID orderId, TenantContext ctx) {
    requireReadAccess(tenantId, orderId, ctx);
    return listTendersByOrder(tenantId, orderId);
  }

  /**
   * Object-level authorization for payment reads (mirrors OrderService/CustomerService
   * requireReadAccess). A payment tender doesn't carry the buyer's identity directly — only the
   * order it was captured against — so ownership is resolved one hop away via order-svc (golden
   * rule #1: never trust a caller-supplied customerId, ask the owning service). Staff may read any
   * payment in their tenant; an authenticated customer may only read payments on their own order.
   * Denials are 404 (not 403) so tender/order ids can't be probed for existence.
   *
   * <p>There is deliberately no exemption for a caller with no principal. No other service reads
   * payments, so that branch had no caller to serve — and a guest storefront request carries a
   * tenant with no principal, so it was reachable from outside rather than only from the mesh.
   */
  private void requireReadAccess(UUID tenantId, UUID orderId, TenantContext ctx) {
    guard.requireOrderReadAccess(
        tenantId,
        orderId,
        ctx,
        () -> ApiException.notFound("PAYMENT_NOT_FOUND", "payment tender not found"));
  }

  /**
   * Records a refund against a previously captured tender.
   *
   * <p>Existence, order-match and the cumulative refund cap are enforced inside one transaction
   * with the payment row locked, so two concurrent refunds cannot together exceed the original
   * payment.
   *
   * @param tenantId owning tenant
   * @param orderId the order being refunded
   * @param req the payment being refunded against, the amount, method and reason
   * @param idempotencyKey the caller's {@code Idempotency-Key}, so a retry does not refund twice
   * @return the recorded refund
   * @throws ApiException {@code PAYMENT_INVALID_METHOD} (400) for an unknown method; a conflict
   *     when the refund would exceed what was captured
   */
  public RefundTender recordRefund(
      UUID tenantId, UUID orderId, RecordRefundRequest req, String idempotencyKey) {
    String method = req.method().toUpperCase(Locale.ROOT);
    if (!VALID_METHODS.contains(method))
      throw ApiException.badRequest(
          "PAYMENT_INVALID_METHOD",
          "method must be one of CASH, CARD, UPI, WALLET, GIFT_CARD, VOUCHER — got: "
              + req.method());

    UUID refundId = Ids.newId();
    RefundTender refund =
        new RefundTender(
            refundId,
            tenantId,
            orderId,
            Ids.parse(req.paymentId()),
            req.amount(),
            method,
            req.reference(),
            idempotencyKey,
            req.reason(),
            Instant.now());

    // Existence, order-match, and the cumulative refund cap are all enforced inside ONE
    // transaction with the payment row locked — checking them here first would be a TOCTOU race
    // letting two concurrent refunds together exceed the original payment.
    return repo.createRefundGuarded(
        refund,
        Events.paymentRefunded(
            tenantId,
            refundId,
            orderId,
            req.amount(),
            List.of(
                new com.storeql.payment.domain.Domain.RefundAllocation(
                    Ids.parse(req.paymentId()), method, req.amount(), null))));
  }

  /**
   * Lists an order's refunds with tenant scoping but <strong>no</strong> object-level
   * authorization.
   *
   * <p>For internal callers only — request-serving code should use the {@link TenantContext}
   * overload.
   *
   * @param tenantId owning tenant
   * @param orderId the order whose refunds to list
   * @return the refunds, empty when nothing has been refunded
   */
  public List<RefundTender> listRefundsByOrder(UUID tenantId, UUID orderId) {
    return repo.findRefundsByOrder(tenantId, orderId);
  }

  /**
   * Lists an order's refunds for the API: tenant scope plus object-level authorization.
   *
   * @param tenantId owning tenant
   * @param orderId the order whose refunds to list
   * @param ctx caller context, resolved against the order to decide access
   * @return the refunds, empty when nothing has been refunded
   * @throws ApiException {@code PAYMENT_NOT_FOUND} (404) when the caller may not read this order's
   *     payments
   */
  public List<RefundTender> listRefundsByOrder(UUID tenantId, UUID orderId, TenantContext ctx) {
    requireReadAccess(tenantId, orderId, ctx);
    return listRefundsByOrder(tenantId, orderId);
  }

  /**
   * Automatically refund a captured order in response to an order event. Driven by {@code
   * OrderReturned} (refund the return amount) and {@code OrderCancelled} (refund whatever is still
   * captured), idempotent on the order event's {@code eventId}. {@code requestedAmount == null}
   * means "refund all remaining captured" (cancellation); otherwise the amount is capped at the
   * remaining captured total. Orders with nothing captured (e.g. unpaid pay-later cancellations)
   * are a no-op. Distributes the refund across the order's captured tenders so the per-tender cap
   * invariant holds even for split-tender sales.
   *
   * @param eventId the order event's id, the idempotency key for this refund
   * @param consumer the consumer name recorded alongside the dedupe mark
   * @param tenantId owning tenant
   * @param orderId the order being refunded
   * @param requestedAmount the amount to refund, capped at what remains captured, or {@code null}
   *     to refund everything still captured (the cancellation case)
   * @param reason free-text reason recorded against each refund
   */
  public void refundForOrderEvent(
      UUID eventId,
      String consumer,
      UUID tenantId,
      UUID orderId,
      BigDecimal requestedAmount,
      String reason) {
    refundForOrderEvent(eventId, consumer, tenantId, orderId, requestedAmount, reason, null);
  }

  /**
   * As above, naming the refund's {@code kind} on the event: {@code ORDER_ADJUSTMENT} for a line
   * closed short or substituted (substitutions for out-of-stock online lines), so order-svc records
   * the money without moving the order's status; null for a return or a cancellation.
   */
  public void refundForOrderEvent(
      UUID eventId,
      String consumer,
      UUID tenantId,
      UUID orderId,
      BigDecimal requestedAmount,
      String reason,
      String kind) {
    UUID refundBatchId = Ids.newId();
    repo.refundOrderOnce(
        eventId,
        consumer,
        tenantId,
        orderId,
        requestedAmount,
        reason,
        (amt, shares) ->
            Events.paymentRefunded(tenantId, refundBatchId, orderId, amt, shares, kind));
  }
}
