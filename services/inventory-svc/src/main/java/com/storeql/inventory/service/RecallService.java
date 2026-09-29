package com.storeql.inventory.service;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Recall;
import com.storeql.inventory.domain.Recall.ActiveItem;
import com.storeql.inventory.domain.Recall.Detail;
import com.storeql.inventory.domain.Recall.Disposition;
import com.storeql.inventory.domain.Recall.Hazard;
import com.storeql.inventory.domain.Recall.Header;
import com.storeql.inventory.domain.Recall.Kind;
import com.storeql.inventory.domain.Recall.Remedy;
import com.storeql.inventory.domain.Recall.Scope;
import com.storeql.inventory.domain.Recall.Source;
import com.storeql.inventory.domain.Recall.Status;
import com.storeql.inventory.domain.Recall.StoreAction;
import com.storeql.inventory.domain.Recall.Summary;
import com.storeql.inventory.repo.RecallRepository;
import com.storeql.service.Jurisdictions;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Opening, working and closing product withdrawals and recalls. */
@ApplicationScoped
public class RecallService {

  static final String TOPIC_RECALL_OPENED = "storeql.inventory.recall-opened";
  static final String TOPIC_RECALL_SALE_AFFECTED = "storeql.inventory.recall-sale-affected";
  static final String TOPIC_STOCK_ADJUSTED = "storeql.inventory.stock-adjusted";

  /** A recall notice lists a handful of lines; a hundred is a mistake, not a notice. */
  static final int MAX_SCOPE_LINES = 100;

  /** GPSR arts.35–37, as tenant-svc's jurisdiction rules name it. */
  static final String GPSR_RECALL_NOTICE = "GPSR_RECALL_NOTICE";

  private static final Pattern CONTACT_URL = Pattern.compile("^https?://\\S+$");

  @Inject RecallRepository repo;
  @Inject Jurisdictions jurisdictions;

  public record OpenRecall(
      UUID tenantId,
      UUID actorId,
      String reference,
      Kind kind,
      Hazard hazard,
      String reason,
      String customerNotice,
      Source source,
      String sourceReference,
      List<ScopeLine> scope,
      Set<Remedy> remedies,
      String singleRemedyReason,
      String contactPhone,
      String contactUrl,
      LocalDate soldFrom) {}

  /** What a recall offers the buyer and where a buyer turns; nothing for a withdrawal. */
  private record Offer(
      Set<Remedy> remedies, String singleRemedyReason, String contactPhone, String contactUrl) {
    static final Offer NONE = new Offer(Set.of(), null, null, null);
  }

  public record ScopeLine(
      UUID variantId, String batchNo, LocalDate expiryFrom, LocalDate expiryTo) {}

  public record RecordStoreAction(
      UUID tenantId,
      UUID actorId,
      UUID recallId,
      UUID storeId,
      BigDecimal qtyFound,
      Disposition disposition,
      boolean noticeDisplayed,
      String notes) {}

  /**
   * Opens a recall or withdrawal, quarantining every batch its scope reaches.
   *
   * <p>A batch whose lot or date cannot be ruled out is held rather than cleared: a pack nobody can
   * rule out comes off sale. A RECALL additionally requires a customer notice, because that is the
   * difference between the two kinds, and reaches the buyers: every sale that drew on a pack in
   * scope is found as the recall opens, kept as the record of who was reached, and announced to
   * order-svc one order at a time, with the notice, the remedies the buyer may choose from and
   * where to turn (GPSR arts.35–37). A RECALL therefore needs at least one remedy and a contact;
   * where the EU's rule binds any country the business trades in, it needs two remedies unless a
   * reason is given for one, and a notice that does not play the risk down.
   *
   * @param cmd the kind, hazard, scope lines, customer notice, remedies and contact
   * @return the opened recall with its scope, held batches, actions and reach
   * @throws ApiException {@code RECALL_SCOPE_REQUIRED} (400) with no scope lines; {@code
   *     RECALL_SCOPE_TOO_LARGE} (400) beyond the line cap; {@code RECALL_NOTICE_REQUIRED}, {@code
   *     RECALL_REMEDIES_REQUIRED}, {@code RECALL_CONTACT_REQUIRED} or {@code
   *     RECALL_CONTACT_URL_INVALID} (400) for a RECALL without them; {@code
   *     RECALL_REMEDIES_INSUFFICIENT} or {@code RECALL_NOTICE_MINIMISES_RISK} (400) where GPSR
   *     binds; 503 when the jurisdiction rules cannot be read
   */
  public Detail open(OpenRecall cmd) {
    if (cmd.scope().isEmpty()) {
      throw ApiException.badRequest("RECALL_SCOPE_REQUIRED", "A recall needs at least one item");
    }
    if (cmd.scope().size() > MAX_SCOPE_LINES) {
      throw ApiException.badRequest(
          "RECALL_SCOPE_TOO_LARGE", "A recall takes at most " + MAX_SCOPE_LINES + " items");
    }
    String notice = blankToNull(cmd.customerNotice());
    if (cmd.kind().tellsCustomers() && notice == null) {
      throw ApiException.badRequest(
          "RECALL_NOTICE_REQUIRED",
          "A recall tells customers what to do, so it needs the notice displayed in store");
    }
    List<Scope> scope = cmd.scope().stream().map(RecallService::toScope).toList();
    Offer offer = cmd.kind().tellsCustomers() ? offerFor(cmd, notice) : Offer.NONE;
    var header =
        new Header(
            Ids.newId(),
            cmd.tenantId(),
            cmd.reference().trim(),
            cmd.kind(),
            cmd.hazard(),
            cmd.reason().trim(),
            notice,
            cmd.source(),
            blankToNull(cmd.sourceReference()),
            Status.OPEN,
            cmd.actorId(),
            Instant.now(),
            null,
            null,
            null,
            offer.remedies(),
            offer.singleRemedyReason(),
            offer.contactPhone(),
            offer.contactUrl(),
            cmd.soldFrom());
    return repo.open(
        header,
        scope,
        stores ->
            new OutboxRow(
                "RecallOpened",
                TOPIC_RECALL_OPENED,
                cmd.tenantId(),
                header.id(),
                Events.recallOpened(header, stores)),
        order ->
            new OutboxRow(
                "RecallSaleAffected",
                TOPIC_RECALL_SALE_AFFECTED,
                cmd.tenantId(),
                order.orderId(),
                Events.recallSaleAffected(header, order)));
  }

  /**
   * What a RECALL offers its buyers, checked against what the law asks. The EU's rule is asked of
   * every country the business trades in: a British business's German shop is bound.
   */
  private Offer offerFor(OpenRecall cmd, String notice) {
    Set<Remedy> remedies = cmd.remedies() == null ? Set.of() : cmd.remedies();
    if (remedies.isEmpty()) {
      throw ApiException.badRequest(
          "RECALL_REMEDIES_REQUIRED",
          "A recall offers its buyers a remedy: repair, replacement or a refund");
    }
    String phone = blankToNull(cmd.contactPhone());
    String url = blankToNull(cmd.contactUrl());
    if (phone == null && url == null) {
      throw ApiException.badRequest(
          "RECALL_CONTACT_REQUIRED",
          "A recall notice names a free number or an online service where a buyer gets more");
    }
    if (url != null && !CONTACT_URL.matcher(url).matches()) {
      throw ApiException.badRequest(
          "RECALL_CONTACT_URL_INVALID", "contactUrl must be an http or https address");
    }
    String singleRemedyReason = blankToNull(cmd.singleRemedyReason());
    if (jurisdictions.inForceWhereTrading(
        cmd.tenantId(), null, GPSR_RECALL_NOTICE, LocalDate.now(ZoneOffset.UTC))) {
      if (!Recall.remediesSufficient(remedies, singleRemedyReason)) {
        throw ApiException.badRequest(
            "RECALL_REMEDIES_INSUFFICIENT",
            "GPSR art.37 asks for at least two of repair, replacement and refund, or the reason"
                + " only one can be offered");
      }
      Recall.minimisingPhrase(notice)
          .ifPresent(
              phrase -> {
                throw new ApiException(
                    400,
                    "RECALL_NOTICE_MINIMISES_RISK",
                    "GPSR art.36 forbids a recall notice to play the risk down; remove \""
                        + phrase
                        + "\"",
                    List.of(phrase));
              });
    }
    return new Offer(Set.copyOf(remedies), singleRemedyReason, phone, url);
  }

  /**
   * Cursor-paginated recalls for the tenant, with the counts a manager scans for.
   *
   * @param tenantId owning tenant
   * @param status restrict to one status, or {@code null} for all
   * @param after cursor from the previous page, or {@code null} to start
   * @param limit page size; clamped to the platform bounds
   * @return the page of summaries and its next cursor
   */
  public Cursor.Page<Summary> list(UUID tenantId, Status status, String after, Integer limit) {
    int lim = Cursor.clampLimit(limit);
    var rows = repo.list(tenantId, status, Cursor.decodeCreatedAtId(after), lim + 1);
    return Cursor.page(rows, lim, s -> s.header().openedAt() + "|" + s.header().id());
  }

  /**
   * One recall in full: header, scope, held batches and store actions.
   *
   * @param tenantId owning tenant
   * @param recallId the recall to read
   * @return the recall detail
   * @throws ApiException {@code RECALL_NOT_FOUND} (404) when no such recall exists in this tenant
   */
  public Detail get(UUID tenantId, UUID recallId) {
    return repo.find(tenantId, recallId)
        .orElseThrow(() -> ApiException.notFound("RECALL_NOT_FOUND", "No such recall"));
  }

  /**
   * Every variant currently under an open recall — what the till checks before selling.
   *
   * @param tenantId owning tenant
   * @return the actively recalled items
   */
  public List<ActiveItem> active(UUID tenantId) {
    return repo.listActive(tenantId);
  }

  /**
   * Every variant under an open recall, and under one closed or cancelled at or after {@code
   * endedSince}: what order-svc judges a till sale replayed from an offline queue against, since a
   * recall that has ended since still covered the sale when it was rung up.
   *
   * @param tenantId owning tenant
   * @param endedSince the earliest end to include
   * @return the lines, each saying when its recall opened and, once ended, when and how
   */
  public List<ActiveItem> openOrEndedSince(UUID tenantId, Instant endedSince) {
    return repo.listOpenOrEndedSince(tenantId, endedSince);
  }

  /**
   * @param requireStoreAccess refuses a caller not assigned to the store; passed in so this class
   *     stays free of the request context
   */
  public StoreAction recordStoreAction(RecordStoreAction cmd, Consumer<UUID> requireStoreAccess) {
    requireStoreAccess.accept(cmd.storeId());
    if (cmd.qtyFound().signum() < 0 || cmd.qtyFound().stripTrailingZeros().scale() > 3) {
      throw ApiException.badRequest(
          "RECALL_QTY_INVALID", "qtyFound must be zero or more, to at most three decimal places");
    }
    var action =
        new StoreAction(
            Ids.newId(),
            cmd.storeId(),
            cmd.qtyFound(),
            BigDecimal.ZERO,
            cmd.disposition(),
            cmd.noticeDisplayed(),
            blankToNull(cmd.notes()),
            cmd.actorId(),
            Instant.now());
    return repo.recordStoreAction(
        cmd.tenantId(),
        cmd.recallId(),
        action,
        (variantId, delta) ->
            new OutboxRow(
                "StockAdjusted",
                TOPIC_STOCK_ADJUSTED,
                cmd.tenantId(),
                variantId,
                Events.stockAdjusted(cmd.tenantId(), cmd.storeId(), variantId, delta)));
  }

  /**
   * Releases one quarantined batch back to sale after it has been checked.
   *
   * <p>Only a batch the recall could not positively place in scope may be released — an {@code
   * IN_SCOPE} batch stays held.
   *
   * @param tenantId owning tenant
   * @param actorId the user releasing it, recorded against the release
   * @param recallId the recall holding the batch
   * @param batchId the batch to release
   * @param reason why it was cleared; required
   * @param requireStoreAccess refuses a caller not assigned to the batch's store; passed in so this
   *     class stays free of the request context
   * @return the recall as it now stands
   * @throws ApiException {@code RECALL_NOT_FOUND} (404) when no such recall exists; a conflict when
   *     the batch is in scope and therefore not releasable
   */
  public Detail release(
      UUID tenantId,
      UUID actorId,
      UUID recallId,
      UUID batchId,
      String reason,
      Consumer<UUID> requireStoreAccess) {
    repo.release(tenantId, recallId, batchId, reason.trim(), actorId, requireStoreAccess);
    return get(tenantId, recallId);
  }

  /**
   * Closes a recall once every affected store has accounted for its stock.
   *
   * @param tenantId owning tenant
   * @param actorId the user closing it
   * @param recallId the recall to close
   * @param notes closing notes, or {@code null}
   * @return the closed recall
   * @throws ApiException {@code RECALL_NOT_FOUND} (404) when no such recall exists; a conflict when
   *     stores are still outstanding
   */
  public Detail close(UUID tenantId, UUID actorId, UUID recallId, String notes) {
    repo.close(tenantId, recallId, actorId, blankToNull(notes));
    return get(tenantId, recallId);
  }

  /**
   * Cancels a recall raised in error, releasing everything it held.
   *
   * <p>Distinct from closing it: cancelling says the recall should never have been raised, so the
   * stock was never actually affected.
   *
   * @param tenantId owning tenant
   * @param actorId the user cancelling it
   * @param recallId the recall to cancel
   * @param reason why it was raised in error; required
   * @return the cancelled recall
   * @throws ApiException {@code RECALL_NOT_FOUND} (404) when no such recall exists; a conflict when
   *     stock has already been disposed of under it
   */
  public Detail cancel(UUID tenantId, UUID actorId, UUID recallId, String reason) {
    repo.cancel(tenantId, recallId, actorId, reason.trim());
    return get(tenantId, recallId);
  }

  static Scope toScope(ScopeLine line) {
    if (line.expiryFrom() != null
        && line.expiryTo() != null
        && line.expiryFrom().isAfter(line.expiryTo())) {
      throw ApiException.badRequest(
          "RECALL_DATES_INVERTED", "expiryFrom must not be after expiryTo");
    }
    return new Scope(
        Ids.newId(),
        line.variantId(),
        blankToNull(line.batchNo()),
        line.expiryFrom(),
        line.expiryTo());
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
