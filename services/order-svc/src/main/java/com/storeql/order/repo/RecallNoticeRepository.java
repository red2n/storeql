package com.storeql.order.repo;

import com.storeql.order.domain.RecallNotice.Choice;
import com.storeql.order.domain.RecallNotice.ChosenVia;
import com.storeql.order.domain.RecallNotice.Detail;
import com.storeql.order.domain.RecallNotice.Line;
import com.storeql.order.domain.RecallNotice.Notice;
import com.storeql.order.domain.RecallNotice.Progress;
import com.storeql.order.domain.RecallNotice.Remedy;
import com.storeql.order.domain.RecallNotice.Resolution;
import com.storeql.order.domain.RecallNotice.Settlement;
import com.storeql.order.domain.RecallNotice.Status;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A recall's notices to buyers. A notice is issued once per (recall, order); its lines are
 * append-only; what changes afterwards is the buyer's choice of remedy and how it was settled.
 * Every query filters tenant_id first.
 */
@ApplicationScoped
public class RecallNoticeRepository extends BaseOutboxRepository {

  private static final String COLUMNS =
      "n.id, n.tenant_id, n.recall_id, n.reference, n.hazard, n.reason, n.customer_notice,"
          + " n.remedies, n.single_remedy_reason, n.contact_phone, n.contact_url, n.order_id,"
          + " n.store_id, n.channel, n.customer_id, n.login_id, n.buyer_phone, n.buyer_identified,"
          + " n.sold_at, n.status,"
          + " n.issued_at, n.remedy, n.remedy_chosen_at, n.remedy_chosen_by, n.remedy_chosen_via,"
          + " n.resolution, n.resolved_at, n.resolved_by, n.return_id, n.resolution_notes";

  /**
   * Issues a notice once: the event's dedupe mark, the notice, its lines and its announcement
   * commit together, so a redelivered event tells nobody twice and a crashed write is retried. A
   * second event for the same (recall, order) finds the notice already there and issues nothing.
   *
   * @param event the announcement for notification-svc, or null when there is nobody to tell
   * @return whether the notice was issued now
   */
  public boolean issueOnce(
      UUID eventId, String consumer, Notice n, List<Line> lines, OutboxRow event) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumer)) {
            return false;
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO recall_notices (id, tenant_id, recall_id, reference, hazard, reason,"
                      + " customer_notice, remedies, single_remedy_reason, contact_phone,"
                      + " contact_url, order_id, store_id, channel, customer_id, login_id,"
                      + " buyer_phone, buyer_identified, sold_at, status)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, recall_id, order_id) DO NOTHING")) {
            ps.setObject(1, n.id());
            ps.setObject(2, n.tenantId());
            ps.setObject(3, n.recallId());
            ps.setString(4, n.reference());
            ps.setString(5, n.hazard());
            ps.setString(6, n.reason());
            ps.setString(7, n.customerNotice());
            ps.setString(8, Remedy.csv(n.remedies()));
            ps.setString(9, n.singleRemedyReason());
            ps.setString(10, n.contactPhone());
            ps.setString(11, n.contactUrl());
            ps.setObject(12, n.orderId());
            ps.setObject(13, n.storeId());
            ps.setString(14, n.channel());
            ps.setObject(15, n.customerId());
            ps.setObject(16, n.loginId());
            ps.setString(17, n.buyerPhone());
            ps.setBoolean(18, n.buyerIdentified());
            ps.setObject(19, utc(n.soldAt()));
            ps.setString(20, n.status().name());
            if (ps.executeUpdate() == 0) {
              return false;
            }
          }
          for (Line line : lines) {
            insertLine(c, n, line);
          }
          if (event != null) {
            insertOutbox(c, event);
          }
          return true;
        },
        "issue recall notice");
  }

  private static void insertLine(Connection c, Notice n, Line line) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO recall_notice_lines (id, tenant_id, notice_id, variant_id, product_name,"
                + " sku, batch_no, expiry_date, qty, match_type) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, line.id());
      ps.setObject(2, n.tenantId());
      ps.setObject(3, n.id());
      ps.setObject(4, line.variantId());
      ps.setString(5, line.productName());
      ps.setString(6, line.sku());
      ps.setString(7, line.batchNo());
      ps.setObject(8, line.expiryDate());
      ps.setBigDecimal(9, line.qty());
      ps.setString(10, line.match());
      ps.executeUpdate();
    }
  }

  // ── reads ──────────────────────────────────────────────────────────────────

  /**
   * One notice with its lines.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param id the notice
   * @return the notice, or empty when it does not exist in this tenant
   */
  public Optional<Detail> find(UUID tenantId, UUID id) {
    return query(
            "SELECT " + COLUMNS + " FROM recall_notices n WHERE n.tenant_id = ? AND n.id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            RecallNoticeRepository::mapNotice,
            "find recall notice")
        .stream()
        .findFirst()
        .map(
            n -> new Detail(n, linesOf(tenantId, List.of(n.id())).getOrDefault(n.id(), List.of())));
  }

  /**
   * The notices issued to one login, newest first: what the shopper sees on the storefront. A login
   * is the only key a shopper's token carries (SJ-D44).
   */
  public List<Detail> listMine(UUID tenantId, UUID loginId) {
    List<Notice> notices =
        query(
            "SELECT "
                + COLUMNS
                + " FROM recall_notices n WHERE n.tenant_id = ? AND n.login_id = ?"
                + " ORDER BY n.issued_at DESC, n.id DESC LIMIT 100",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, loginId);
            },
            RecallNoticeRepository::mapNotice,
            "list own recall notices");
    return withLines(tenantId, notices);
  }

  /**
   * Keyset page of notices, newest first, of one recall and/or one order, optionally by status.
   *
   * @param recallId the recall, or {@code null} for the notices of every recall
   * @param orderId the order, or {@code null} for every order
   * @param storeScope the stores the caller is held to, or {@code null} for all; a notice at any
   *     other store is not returned
   * @param limitPlusOne page size plus one, so the caller can detect a next page
   */
  public List<Detail> list(
      UUID tenantId,
      UUID recallId,
      UUID orderId,
      java.util.Set<UUID> storeScope,
      Status status,
      Cursor.CreatedAtId after,
      int limitPlusOne) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(COLUMNS)
            .append(" FROM recall_notices n WHERE n.tenant_id = ?");
    if (recallId != null) sql.append(" AND n.recall_id = ?");
    if (orderId != null) sql.append(" AND n.order_id = ?");
    if (storeScope != null) sql.append(" AND n.store_id = ANY (?)");
    if (status != null) sql.append(" AND n.status = ?");
    if (after != null) sql.append(" AND (n.issued_at, n.id) < (?, ?)");
    sql.append(" ORDER BY n.issued_at DESC, n.id DESC LIMIT ?");
    List<Notice> notices =
        query(
            sql.toString(),
            ps -> {
              int i = 1;
              ps.setObject(i++, tenantId);
              if (recallId != null) ps.setObject(i++, recallId);
              if (orderId != null) ps.setObject(i++, orderId);
              if (storeScope != null) {
                ps.setArray(i++, ps.getConnection().createArrayOf("uuid", storeScope.toArray()));
              }
              if (status != null) ps.setString(i++, status.name());
              if (after != null) {
                ps.setObject(i++, utc(after.createdAt()));
                ps.setObject(i++, after.id());
              }
              ps.setInt(i, limitPlusOne);
            },
            RecallNoticeRepository::mapNotice,
            "list recall notices");
    return withLines(tenantId, notices);
  }

  /** How a recall's buyers stand. */
  public Progress progress(UUID tenantId, UUID recallId) {
    return query(
            "SELECT COUNT(*) AS notices,"
                + " COUNT(*) FILTER (WHERE NOT n.buyer_identified) AS unidentified,"
                + " COUNT(*) FILTER (WHERE n.remedy IS NOT NULL) AS remedy_chosen,"
                + " COUNT(*) FILTER (WHERE n.status = 'RESOLVED') AS resolved,"
                + " COUNT(*) FILTER (WHERE n.remedy = 'REFUND') AS refund,"
                + " COUNT(*) FILTER (WHERE n.remedy = 'REPLACEMENT') AS replacement,"
                + " COUNT(*) FILTER (WHERE n.remedy = 'REPAIR') AS repair"
                + " FROM recall_notices n WHERE n.tenant_id = ? AND n.recall_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, recallId);
            },
            rs -> {
              Map<Remedy, Integer> chosen = new EnumMap<>(Remedy.class);
              chosen.put(Remedy.REFUND, rs.getInt("refund"));
              chosen.put(Remedy.REPLACEMENT, rs.getInt("replacement"));
              chosen.put(Remedy.REPAIR, rs.getInt("repair"));
              int notices = rs.getInt("notices");
              int unidentified = rs.getInt("unidentified");
              return new Progress(
                  notices,
                  notices - unidentified,
                  unidentified,
                  rs.getInt("remedy_chosen"),
                  rs.getInt("resolved"),
                  chosen);
            },
            "recall notice progress")
        .get(0);
  }

  private List<Detail> withLines(UUID tenantId, List<Notice> notices) {
    Map<UUID, List<Line>> lines = linesOf(tenantId, notices.stream().map(Notice::id).toList());
    List<Detail> out = new ArrayList<>(notices.size());
    for (Notice n : notices) {
      out.add(new Detail(n, lines.getOrDefault(n.id(), List.of())));
    }
    return out;
  }

  private Map<UUID, List<Line>> linesOf(UUID tenantId, Collection<UUID> noticeIds) {
    Map<UUID, List<Line>> out = new LinkedHashMap<>();
    if (noticeIds.isEmpty()) {
      return out;
    }
    query(
        "SELECT l.notice_id, l.id, l.variant_id, l.product_name, l.sku, l.batch_no, l.expiry_date,"
            + " l.qty, l.match_type FROM recall_notice_lines l"
            + " WHERE l.tenant_id = ? AND l.notice_id = ANY (?) ORDER BY l.id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setArray(2, ps.getConnection().createArrayOf("uuid", noticeIds.toArray()));
        },
        rs -> {
          out.computeIfAbsent(rs.getObject("notice_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new Line(
                      rs.getObject("id", UUID.class),
                      rs.getObject("variant_id", UUID.class),
                      rs.getString("product_name"),
                      rs.getString("sku"),
                      rs.getString("batch_no"),
                      rs.getObject("expiry_date", LocalDate.class),
                      rs.getBigDecimal("qty"),
                      rs.getString("match_type")));
          return null;
        },
        "list recall notice lines");
    return out;
  }

  // ── the buyer's choice, and the settlement ─────────────────────────────────

  /**
   * Records the remedy a buyer chose, once, under the row lock.
   *
   * @param mayAct whether the caller may act on the notice; a notice they may not is not found, so
   *     a notice id cannot be probed
   * @throws ApiException 404 {@code RECALL_NOTICE_NOT_FOUND}; 409 as {@link
   *     Notice#requireCanChoose}
   */
  public Detail chooseRemedy(
      UUID tenantId, UUID id, Remedy remedy, UUID actor, ChosenVia via, Predicate<Notice> mayAct) {
    inTx(
        c -> {
          Notice n = lock(c, tenantId, id);
          if (!mayAct.test(n)) {
            throw notFound();
          }
          n.requireCanChoose(remedy);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE recall_notices SET remedy = ?, remedy_chosen_at = now(),"
                      + " remedy_chosen_by = ?, remedy_chosen_via = ?, status = 'REMEDY_CHOSEN'"
                      + " WHERE tenant_id = ? AND id = ?")) {
            ps.setString(1, remedy.name());
            ps.setObject(2, actor);
            ps.setString(3, via.name());
            ps.setObject(4, tenantId);
            ps.setObject(5, id);
            ps.executeUpdate();
          }
          return null;
        },
        "choose recall remedy");
    return find(tenantId, id).orElseThrow(RecallNoticeRepository::notFound);
  }

  /**
   * Settles a notice: the buyer was refunded, given a replacement, had the product repaired, or
   * wanted nothing. A remedy nobody had chosen is recorded as the one the settlement implies.
   *
   * @param guard run on the locked notice before anything is written; it throws when the caller may
   *     not act at the notice's store
   * @throws ApiException 404 {@code RECALL_NOTICE_NOT_FOUND}; whatever {@code guard} throws; 409
   *     {@code RECALL_NOTICE_RESOLVED}
   */
  public Detail resolve(
      UUID tenantId,
      UUID id,
      Resolution resolution,
      String notes,
      UUID actor,
      java.util.function.Consumer<Notice> guard) {
    inTx(
        c -> {
          Notice n = lock(c, tenantId, id);
          guard.accept(n);
          n.requireOpen();
          settle(c, tenantId, id, resolution, null, notes, actor);
          return null;
        },
        "resolve recall notice");
    return find(tenantId, id).orElseThrow(RecallNoticeRepository::notFound);
  }

  /**
   * Settles a notice with the refund that returns the goods, in the return's own transaction, so a
   * refund can never be recorded without the notice knowing and a notice never settled by a refund
   * that was rolled back.
   *
   * @throws ApiException 404 {@code RECALL_NOTICE_NOT_FOUND}; 409 {@code
   *     RECALL_NOTICE_ORDER_MISMATCH} when the notice is about another order; 409 {@code
   *     RECALL_NOTICE_RESOLVED}
   */
  public static void resolveByReturnTx(
      Connection c, UUID tenantId, UUID noticeId, UUID orderId, UUID returnId, UUID actor)
      throws SQLException {
    Notice n = lock(c, tenantId, noticeId);
    if (!n.orderId().equals(orderId)) {
      throw ApiException.conflict(
          "RECALL_NOTICE_ORDER_MISMATCH", "This recall notice is about a different order");
    }
    n.requireOpen();
    settle(c, tenantId, noticeId, Resolution.REFUNDED, returnId, null, actor);
  }

  private static void settle(
      Connection c,
      UUID tenantId,
      UUID id,
      Resolution resolution,
      UUID returnId,
      String notes,
      UUID actor)
      throws SQLException {
    Remedy implied =
        switch (resolution) {
          case REFUNDED -> Remedy.REFUND;
          case REPLACED -> Remedy.REPLACEMENT;
          case REPAIRED -> Remedy.REPAIR;
          case DECLINED -> null;
        };
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE recall_notices SET status = 'RESOLVED', resolution = ?, resolved_at = now(),"
                + " resolved_by = ?, return_id = ?, resolution_notes = ?,"
                + " remedy = COALESCE(remedy, ?),"
                + " remedy_chosen_at = CASE WHEN remedy IS NULL AND ? IS NOT NULL THEN now()"
                + "   ELSE remedy_chosen_at END,"
                + " remedy_chosen_by = CASE WHEN remedy IS NULL AND ? IS NOT NULL THEN ?"
                + "   ELSE remedy_chosen_by END,"
                + " remedy_chosen_via = CASE WHEN remedy IS NULL AND ? IS NOT NULL THEN 'STAFF'"
                + "   ELSE remedy_chosen_via END"
                + " WHERE tenant_id = ? AND id = ?")) {
      String impliedName = implied == null ? null : implied.name();
      ps.setString(1, resolution.name());
      ps.setObject(2, actor);
      ps.setObject(3, returnId);
      ps.setString(4, notes);
      ps.setString(5, impliedName);
      ps.setString(6, impliedName);
      ps.setString(7, impliedName);
      ps.setObject(8, actor);
      ps.setString(9, impliedName);
      ps.setObject(10, tenantId);
      ps.setObject(11, id);
      ps.executeUpdate();
    }
  }

  /**
   * Forgets the number a buyer left at checkout once they are erased (SJ-D43): the notice stays, as
   * the record that the recall reached an order, but can no longer reach the person.
   */
  static void redactBuyerTx(Connection c, UUID tenantId, UUID customerId, UUID loginId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE recall_notices SET buyer_phone = NULL WHERE tenant_id = ?"
                + " AND (customer_id = ? OR login_id = ?) AND buyer_phone IS NOT NULL")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, customerId);
      ps.setObject(3, loginId);
      ps.executeUpdate();
    }
  }

  private static Notice lock(Connection c, UUID tenantId, UUID id) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT "
                + COLUMNS
                + " FROM recall_notices n WHERE n.tenant_id = ? AND n.id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw notFound();
        }
        return mapNotice(rs);
      }
    }
  }

  private static ApiException notFound() {
    return ApiException.notFound("RECALL_NOTICE_NOT_FOUND", "No such recall notice");
  }

  private static Notice mapNotice(ResultSet rs) throws SQLException {
    String remedy = rs.getString("remedy");
    String resolution = rs.getString("resolution");
    return new Notice(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("recall_id", UUID.class),
        rs.getString("reference"),
        rs.getString("hazard"),
        rs.getString("reason"),
        rs.getString("customer_notice"),
        Remedy.parse(rs.getString("remedies")),
        rs.getString("single_remedy_reason"),
        rs.getString("contact_phone"),
        rs.getString("contact_url"),
        rs.getObject("order_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getString("channel"),
        rs.getObject("customer_id", UUID.class),
        rs.getObject("login_id", UUID.class),
        rs.getString("buyer_phone"),
        rs.getBoolean("buyer_identified"),
        instant(rs, "sold_at"),
        Status.valueOf(rs.getString("status")),
        instant(rs, "issued_at"),
        remedy == null
            ? null
            : new Choice(
                Remedy.valueOf(remedy),
                instant(rs, "remedy_chosen_at"),
                rs.getObject("remedy_chosen_by", UUID.class),
                ChosenVia.valueOf(rs.getString("remedy_chosen_via"))),
        resolution == null
            ? null
            : new Settlement(
                Resolution.valueOf(resolution),
                instant(rs, "resolved_at"),
                rs.getObject("resolved_by", UUID.class),
                rs.getObject("return_id", UUID.class),
                rs.getString("resolution_notes")));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
    return odt == null ? null : odt.toInstant();
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
