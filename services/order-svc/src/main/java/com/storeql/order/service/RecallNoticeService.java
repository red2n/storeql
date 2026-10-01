package com.storeql.order.service;

import com.storeql.order.domain.RecallNotice.ChosenVia;
import com.storeql.order.domain.RecallNotice.Detail;
import com.storeql.order.domain.RecallNotice.Progress;
import com.storeql.order.domain.RecallNotice.Remedy;
import com.storeql.order.domain.RecallNotice.Resolution;
import com.storeql.order.domain.RecallNotice.Status;
import com.storeql.order.repo.RecallNoticeRepository;
import com.storeql.web.ApiException;
import com.storeql.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;

/** A recall's notices to buyers: what a shopper sees and chooses, and what staff settle. */
@ApplicationScoped
public class RecallNoticeService {

  @Inject RecallNoticeRepository repo;

  /**
   * The notices issued to a shopper's own login, newest first.
   *
   * @param tenantId the shop, from the storefront header
   * @param loginId the shopper's login, from the token
   */
  public List<Detail> mine(UUID tenantId, UUID loginId) {
    return repo.listMine(tenantId, loginId);
  }

  /**
   * The shopper chooses a remedy on a notice issued to their login. A notice issued to anyone else
   * is not found, so notice ids cannot be probed.
   *
   * @throws ApiException 404 {@code RECALL_NOTICE_NOT_FOUND}; 409 {@code
   *     RECALL_REMEDY_ALREADY_CHOSEN}, {@code RECALL_REMEDY_NOT_OFFERED} or {@code
   *     RECALL_NOTICE_RESOLVED}
   */
  public Detail chooseAsShopper(UUID tenantId, UUID noticeId, UUID loginId, Remedy remedy) {
    return repo.chooseRemedy(
        tenantId, noticeId, remedy, loginId, ChosenVia.SHOPPER, n -> n.belongsTo(loginId));
  }

  /**
   * Staff record the remedy a buyer chose at the counter or on the phone, on any notice of the
   * business.
   *
   * @throws ApiException as {@link #chooseAsShopper}
   */
  public Detail chooseAsStaff(UUID tenantId, UUID noticeId, UUID actorId, Remedy remedy) {
    return repo.chooseRemedy(tenantId, noticeId, remedy, actorId, ChosenVia.STAFF, n -> true);
  }

  /**
   * Cursor-paginated notices, newest first, of one recall or of one order (or both).
   *
   * <p>Asked by order, the answer is what the caller may see of it: only notices at stores the
   * caller keeps, so another business's order, or one at a store the caller is not held to, gives
   * an empty list and nothing that says the order exists.
   *
   * @param recallId the recall, or {@code null} to read across recalls (then {@code orderId} is
   *     required)
   * @param orderId the order whose open notices are wanted, or {@code null}
   * @param storeScope the stores the caller is held to, or {@code null} when held to none; applied
   *     when asked by order
   * @param status restrict to one status, or {@code null} for all
   */
  public Cursor.Page<Detail> list(
      UUID tenantId,
      UUID recallId,
      UUID orderId,
      java.util.Set<UUID> storeScope,
      Status status,
      String after,
      Integer limit) {
    if (recallId == null && orderId == null) {
      throw ApiException.badRequest(
          "VALIDATION_FAILED", "recallId or orderId: one of them is required");
    }
    int lim = Cursor.clampLimit(limit);
    var rows =
        repo.list(
            tenantId,
            recallId,
            orderId,
            orderId == null || storeScope == null || storeScope.isEmpty() ? null : storeScope,
            status,
            Cursor.decodeCreatedAtId(after),
            lim + 1);
    return Cursor.page(rows, lim, d -> d.notice().issuedAt() + "|" + d.notice().id());
  }

  /** How a recall's buyers stand. */
  public Progress progress(UUID tenantId, UUID recallId) {
    return repo.progress(tenantId, recallId);
  }

  /**
   * Staff settle a notice other than by a refund through a return: a replacement handed over, a
   * repair done, or a buyer who wanted nothing. A refund is recorded through {@code POST
   * /orders/{id}/returns} with the notice named, so the money and the goods stay on one record.
   *
   * @param ctx the caller, who must keep the store of the notice's order
   * @throws ApiException 404 {@code RECALL_NOTICE_NOT_FOUND}; 403 {@code STORE_ACCESS_DENIED} for
   *     staff held to other stores; 409 {@code RECALL_NOTICE_RESOLVED}; 400 {@code
   *     RECALL_REFUND_THROUGH_RETURN} for REFUNDED
   */
  public Detail resolve(
      UUID tenantId,
      UUID noticeId,
      UUID actorId,
      Resolution resolution,
      String notes,
      com.storeql.web.TenantContext ctx) {
    if (resolution == Resolution.REFUNDED) {
      throw ApiException.badRequest(
          "RECALL_REFUND_THROUGH_RETURN",
          "Record a refund as a return of the order, naming this notice, so the goods come back"
              + " with the money");
    }
    return repo.resolve(
        tenantId,
        noticeId,
        resolution,
        notes == null || notes.isBlank() ? null : notes.trim(),
        actorId,
        n -> ctx.requireStoreAccess(n.storeId()));
  }
}
