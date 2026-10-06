package com.storeql.inventory.repo;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.Batch;
import com.storeql.inventory.domain.Domain.MoveType;
import com.storeql.inventory.domain.Domain.MovementAttribution;
import com.storeql.inventory.domain.Recall;
import com.storeql.inventory.domain.Recall.ActiveItem;
import com.storeql.inventory.domain.Recall.AffectedOrder;
import com.storeql.inventory.domain.Recall.AffectedSale;
import com.storeql.inventory.domain.Recall.Detail;
import com.storeql.inventory.domain.Recall.Disposition;
import com.storeql.inventory.domain.Recall.Hazard;
import com.storeql.inventory.domain.Recall.Header;
import com.storeql.inventory.domain.Recall.HeldBatch;
import com.storeql.inventory.domain.Recall.Kind;
import com.storeql.inventory.domain.Recall.Match;
import com.storeql.inventory.domain.Recall.QuarantinedOn;
import com.storeql.inventory.domain.Recall.Reach;
import com.storeql.inventory.domain.Recall.Release;
import com.storeql.inventory.domain.Recall.Remedy;
import com.storeql.inventory.domain.Recall.Scope;
import com.storeql.inventory.domain.Recall.Source;
import com.storeql.inventory.domain.Recall.Status;
import com.storeql.inventory.domain.Recall.StoreAction;
import com.storeql.inventory.domain.Recall.Summary;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.Cursor;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Recalls, their scope, the batches they hold, and what stores did about them.
 *
 * <p>Scope lines, held batches, releases and store actions are append-only: this class has no
 * UPDATE or DELETE for any of them. The recall's own status is the one mutable fact.
 */
@ApplicationScoped
public class RecallRepository extends BaseOutboxRepository {

  static final String REF_TYPE = "RECALL";
  static final String WRITE_OFF_REASON = "RECALL_WITHDRAWAL";

  private static final String HEADER_COLUMNS =
      "r.id, r.tenant_id, r.reference, r.kind, r.hazard, r.reason, r.customer_notice, r.source,"
          + " r.source_reference, r.status, r.opened_by, r.opened_at, r.ended_by, r.ended_at,"
          + " r.end_notes, r.remedies, r.single_remedy_reason, r.contact_phone, r.contact_url,"
          + " r.sold_from";

  /** How far a recall reached, as subqueries on a recalls row aliased {@code r}. */
  private static final String REACH_COLUMNS =
      " (SELECT COUNT(DISTINCT s.order_id) FROM recall_sales s"
          + "   WHERE s.tenant_id = r.tenant_id AND s.recall_id = r.id) AS orders_affected,"
          + " (SELECT COALESCE(SUM(s.qty), 0) FROM recall_sales s"
          + "   WHERE s.tenant_id = r.tenant_id AND s.recall_id = r.id) AS qty_sold";

  /**
   * Every sale of a scoped variant that drew on a batch, live or archived: which order took it,
   * from which lot and date. A movement this service could not tie to a batch names no lot and is
   * not a sale of any pack in particular, so it is left out.
   */
  private static final String SALES_OF_VARIANTS =
      "SELECT m.ref_id AS order_id, m.store_id, m.variant_id, m.batch_id, ABS(m.qty) AS qty,"
          + " m.created_at, b.batch_no, b.expiry_date"
          + " FROM (SELECT id, tenant_id, store_id, variant_id, batch_id, type, qty, ref_type,"
          + "         ref_id, created_at FROM stock_movements"
          + "       UNION ALL"
          + "       SELECT id, tenant_id, store_id, variant_id, batch_id, type, qty, ref_type,"
          + "         ref_id, created_at FROM stock_movements_archive) m"
          + " JOIN inventory_batches b ON b.tenant_id = m.tenant_id AND b.id = m.batch_id"
          + " WHERE m.tenant_id = ? AND m.variant_id = ANY (?) AND m.type = 'SALE'"
          + " AND m.ref_type = 'ORDER' AND m.ref_id IS NOT NULL"
          + " AND (CAST(? AS date) IS NULL OR m.created_at >= CAST(? AS date))"
          + " ORDER BY m.created_at, m.id";

  /** A recall's scope lines as the till checks an item against them; a WHERE follows. */
  private static final String ACTIVE_ITEMS =
      "SELECT r.id AS recall_id, r.reference, r.kind, r.hazard, r.customer_notice,"
          + " r.opened_at, r.ended_at, r.status, i.id, i.variant_id, i.batch_no, i.expiry_from,"
          + " i.expiry_to"
          + " FROM recalls r JOIN recall_items i ON i.tenant_id = r.tenant_id"
          + " AND i.recall_id = r.id";

  /** A held batch nobody has released. Expects the recall_batches row aliased {@code rb}. */
  private static final String NOT_RELEASED =
      " NOT EXISTS (SELECT 1 FROM recall_batch_releases x WHERE x.tenant_id = rb.tenant_id"
          + " AND x.recall_id = rb.recall_id AND x.batch_id = rb.batch_id)";

  private record Candidate(
      UUID batchId,
      UUID storeId,
      UUID variantId,
      String batchNo,
      LocalDate expiryDate,
      BigDecimal remainingQty,
      String materialStatus) {}

  private record HeldForAction(UUID batchId, UUID variantId, BigDecimal remainingQty) {}

  private record HoldRow(UUID storeId, Match match, String priorStatus, boolean released) {}

  // ── open ───────────────────────────────────────────────────────────────────

  /**
   * Opens a recall and takes every batch in its scope off sale, in one transaction, so no sale or
   * reservation can draw on a batch between the recall existing and the batch being held. The sales
   * that already drew on those packs are found in the same transaction and kept as the record of
   * who was reached; a recall that tells buyers announces each order.
   *
   * @param openedEvent builds the announcement from the stores whose stock was held
   * @param saleAffectedEvent builds the announcement of one order that drew on packs in scope
   */
  public Detail open(
      Header header,
      List<Scope> scope,
      Function<Set<UUID>, OutboxRow> openedEvent,
      Function<AffectedOrder, OutboxRow> saleAffectedEvent) {
    inTx(
        c -> {
          try {
            insertHeader(c, header);
          } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
              throw new ApiException(
                  409,
                  "RECALL_REFERENCE_TAKEN",
                  "A recall with this reference already exists",
                  List.of(),
                  e);
            }
            throw e;
          }
          for (Scope line : scope) {
            insertScope(c, header, line);
          }
          Set<UUID> stores = new TreeSet<>();
          String reason = holdReason(header.reference());
          List<Candidate> candidates = lockCandidates(c, header.tenantId(), scope);
          Map<UUID, List<Recall.Lot>> carried =
              ancestry(c, header.tenantId(), candidates.stream().map(Candidate::batchId).toList());
          for (Candidate batch : candidates) {
            Match match =
                Recall.classify(
                    scope,
                    batch.variantId(),
                    lotsOf(batch.batchNo(), batch.expiryDate(), carried.get(batch.batchId())));
            if (match != null) {
              hold(c, header.tenantId(), header.id(), batch, match, QuarantinedOn.OPEN, reason);
              stores.add(batch.storeId());
            }
          }
          insertOutbox(c, openedEvent.apply(stores));
          List<AffectedSale> sales = findAffectedSales(c, header, scope);
          for (AffectedSale sale : sales) {
            insertSale(c, header, sale);
          }
          if (header.tellsBuyers()) {
            for (AffectedOrder order : byOrder(sales)) {
              insertOutbox(c, saleAffectedEvent.apply(order));
            }
          }
          return null;
        },
        "open recall");
    return find(header.tenantId(), header.id()).orElseThrow();
  }

  /**
   * The sales that drew on packs in a recall's scope, classified as its batches are: a sale from a
   * batch whose lot or date is not known cannot be ruled out, so its buyer is told too. A batch is
   * judged by the lots it carries as well as its own number, at whatever time it was sold: a sale
   * from a batch that was merged into later is attributed to the lots merged into it, because the
   * buyer is then told rather than missed.
   */
  private static List<AffectedSale> findAffectedSales(Connection c, Header h, List<Scope> scope)
      throws SQLException {
    Object[] variants = scope.stream().map(Scope::variantId).distinct().toArray();
    record Sold(
        UUID orderId,
        UUID storeId,
        UUID variantId,
        UUID batchId,
        String batchNo,
        LocalDate expiry,
        BigDecimal qty,
        Instant soldAt) {}
    List<Sold> sold = new ArrayList<>();
    try (PreparedStatement ps = c.prepareStatement(SALES_OF_VARIANTS)) {
      ps.setObject(1, h.tenantId());
      ps.setArray(2, c.createArrayOf("uuid", variants));
      if (h.soldFrom() == null) {
        ps.setNull(3, Types.DATE);
        ps.setNull(4, Types.DATE);
      } else {
        ps.setObject(3, h.soldFrom());
        ps.setObject(4, h.soldFrom());
      }
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          sold.add(
              new Sold(
                  rs.getObject("order_id", UUID.class),
                  rs.getObject("store_id", UUID.class),
                  rs.getObject("variant_id", UUID.class),
                  rs.getObject("batch_id", UUID.class),
                  rs.getString("batch_no"),
                  rs.getObject("expiry_date", LocalDate.class),
                  rs.getBigDecimal("qty"),
                  instant(rs, "created_at")));
        }
      }
    }
    Map<UUID, List<Recall.Lot>> carried =
        ancestry(c, h.tenantId(), sold.stream().map(Sold::batchId).distinct().toList());
    List<AffectedSale> out = new ArrayList<>();
    for (Sold sale : sold) {
      Match match =
          Recall.classify(
              scope,
              sale.variantId(),
              lotsOf(sale.batchNo(), sale.expiry(), carried.get(sale.batchId())));
      if (match != null) {
        out.add(
            new AffectedSale(
                sale.orderId(),
                sale.storeId(),
                sale.variantId(),
                sale.batchId(),
                sale.batchNo(),
                sale.expiry(),
                sale.qty(),
                sale.soldAt(),
                match));
      }
    }
    return out;
  }

  /** The sales grouped by order, in the order they were made. */
  static List<AffectedOrder> byOrder(List<AffectedSale> sales) {
    Map<UUID, List<AffectedSale>> lines = new LinkedHashMap<>();
    for (AffectedSale sale : sales) {
      lines.computeIfAbsent(sale.orderId(), k -> new ArrayList<>()).add(sale);
    }
    List<AffectedOrder> out = new ArrayList<>();
    for (var entry : lines.entrySet()) {
      AffectedSale first = entry.getValue().get(0);
      out.add(
          new AffectedOrder(
              entry.getKey(), first.storeId(), first.soldAt(), List.copyOf(entry.getValue())));
    }
    return out;
  }

  private static void insertSale(Connection c, Header h, AffectedSale s) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO recall_sales (id, tenant_id, recall_id, order_id, store_id, variant_id,"
                + " batch_id, batch_no, expiry_date, qty, sold_at, match_type)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, h.tenantId());
      ps.setObject(3, h.id());
      ps.setObject(4, s.orderId());
      ps.setObject(5, s.storeId());
      ps.setObject(6, s.variantId());
      ps.setObject(7, s.batchId());
      ps.setString(8, s.batchNo());
      ps.setObject(9, s.expiryDate());
      ps.setBigDecimal(10, s.qty());
      ps.setObject(11, utc(s.soldAt()));
      ps.setString(12, s.match().name());
      ps.executeUpdate();
    }
  }

  /**
   * Holds a batch that arrives while an open recall covers it: a delivery of the recalled lot the
   * day after, a transfer from another store, a customer bringing a pack back. Runs inside the
   * caller's transaction, so the batch is never available for a moment.
   *
   * @return the reason the batch is now held, or null when no open recall covers it
   */
  static String holdOnArrival(Connection c, Batch batch) throws SQLException {
    return holdOnArrival(c, batch, List.of());
  }

  /**
   * As above for a batch made from other batches (a split's child): it is judged by its own lot and
   * by the lots of the batches it is made from, and not held by a recall that already let one of
   * them go, because a person checked that stock and found it not affected.
   *
   * @param parents the batches the new one is made from; none for stock that is not
   */
  static String holdOnArrival(Connection c, Batch batch, List<UUID> parents) throws SQLException {
    Map<UUID, List<Scope>> scopeByRecall = new LinkedHashMap<>();
    Map<UUID, String> referenceByRecall = new LinkedHashMap<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT i.id, i.recall_id, r.reference, i.variant_id, i.batch_no, i.expiry_from,"
                + " i.expiry_to FROM recall_items i"
                + " JOIN recalls r ON r.tenant_id = i.tenant_id AND r.id = i.recall_id"
                + " WHERE i.tenant_id = ? AND i.variant_id = ? AND r.status = 'OPEN'"
                + " ORDER BY r.opened_at, i.id")) {
      ps.setObject(1, batch.tenantId());
      ps.setObject(2, batch.variantId());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          UUID recallId = rs.getObject("recall_id", UUID.class);
          referenceByRecall.put(recallId, rs.getString("reference"));
          scopeByRecall.computeIfAbsent(recallId, k -> new ArrayList<>()).add(mapScope(rs));
        }
      }
    }
    if (scopeByRecall.isEmpty()) {
      return null;
    }
    List<Recall.Lot> lots = new ArrayList<>();
    lots.add(new Recall.Lot(batch.batchNo(), batch.expiryDate()));
    Set<UUID> letGo = Set.of();
    if (!parents.isEmpty()) {
      lots.addAll(lotsOfStockBehind(c, batch.tenantId(), parents));
      letGo = recallsThatReleased(c, batch.tenantId(), parents);
    }
    var candidate =
        new Candidate(
            batch.id(),
            batch.storeId(),
            batch.variantId(),
            batch.batchNo(),
            batch.expiryDate(),
            batch.remainingQty(),
            batch.materialStatus() == null ? Batch.MATERIAL_AVAILABLE : batch.materialStatus());
    String heldFor = null;
    for (var entry : scopeByRecall.entrySet()) {
      if (letGo.contains(entry.getKey())) {
        continue;
      }
      Match match = Recall.classify(entry.getValue(), batch.variantId(), lots);
      if (match != null) {
        heldFor = holdReason(referenceByRecall.get(entry.getKey()));
        hold(c, batch.tenantId(), entry.getKey(), candidate, match, QuarantinedOn.ARRIVAL, heldFor);
      }
    }
    return heldFor;
  }

  /**
   * The status a batch had before the open recalls that hold it took it off sale, or null when no
   * open recall holds it. The first recall's record is the one that says so: a recall that joins
   * later carries it.
   */
  static String statusBeforeRecalls(Connection c, UUID tenantId, UUID batchId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT rb.prior_material_status FROM recall_batches rb JOIN recalls r"
                + " ON r.tenant_id = rb.tenant_id AND r.id = rb.recall_id"
                + " WHERE rb.tenant_id = ? AND rb.batch_id = ? AND r.status = 'OPEN' AND"
                + NOT_RELEASED
                + " ORDER BY rb.quarantined_at, rb.recall_id LIMIT 1")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, batchId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  /**
   * Makes {@code to} held by every open recall that holds {@code from} and has not let it go: the
   * stock moved from one batch to the other, so whatever a recall holds of the one it holds of the
   * other. Each recall keeps the source's record of the status to restore, so ending the recall
   * puts the stock back as it was before, not as RECALLED. A recall that already holds {@code to}
   * is left as it is.
   *
   * @param to the batch as it now stands, its quantity being what each recall records as held
   */
  static void inheritHolds(Connection c, UUID tenantId, UUID from, Batch to) throws SQLException {
    record Held(UUID recallId, String reference, Match match, String prior) {}
    List<Held> held = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT rb.recall_id, r.reference, rb.match_type, rb.prior_material_status"
                + " FROM recall_batches rb JOIN recalls r"
                + " ON r.tenant_id = rb.tenant_id AND r.id = rb.recall_id"
                + " WHERE rb.tenant_id = ? AND rb.batch_id = ? AND r.status = 'OPEN' AND"
                + NOT_RELEASED
                + " ORDER BY rb.quarantined_at, rb.recall_id")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, from);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          held.add(
              new Held(
                  rs.getObject(1, UUID.class),
                  rs.getString(2),
                  Match.valueOf(rs.getString(3)),
                  rs.getString(4)));
        }
      }
    }
    for (Held h : held) {
      var candidate =
          new Candidate(
              to.id(),
              to.storeId(),
              to.variantId(),
              to.batchNo(),
              to.expiryDate(),
              to.remainingQty(),
              h.prior());
      hold(
          c,
          tenantId,
          h.recallId(),
          candidate,
          h.match(),
          QuarantinedOn.ARRIVAL,
          holdReason(h.reference()));
    }
  }

  /** The recalls that let any of these batches go, after a person checked them. */
  private static Set<UUID> recallsThatReleased(Connection c, UUID tenantId, List<UUID> batchIds)
      throws SQLException {
    Set<UUID> out = new java.util.HashSet<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT DISTINCT recall_id FROM recall_batch_releases"
                + " WHERE tenant_id = ? AND batch_id = ANY (?)")) {
      ps.setObject(1, tenantId);
      ps.setArray(2, c.createArrayOf("uuid", batchIds.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getObject(1, UUID.class));
        }
      }
    }
    return out;
  }

  /** A batch's own lot followed by the lots it carries from the batches it was made from. */
  private static List<Recall.Lot> lotsOf(
      String batchNo, LocalDate expiry, List<Recall.Lot> carried) {
    List<Recall.Lot> lots = new ArrayList<>();
    lots.add(new Recall.Lot(batchNo, expiry));
    if (carried != null) {
      lots.addAll(carried);
    }
    return lots;
  }

  /**
   * The lots of the stock the given batches hold: each batch's own number and date, and those of
   * the batches it was split or merged from, at any depth.
   */
  private static List<Recall.Lot> lotsOfStockBehind(
      Connection c, UUID tenantId, List<UUID> batchIds) throws SQLException {
    List<Recall.Lot> lots = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT b.batch_no, b.expiry_date FROM inventory_batches b"
                + " WHERE b.tenant_id = ? AND b.id = ANY (?)")) {
      ps.setObject(1, tenantId);
      ps.setArray(2, c.createArrayOf("uuid", batchIds.toArray()));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          lots.add(new Recall.Lot(rs.getString(1), rs.getObject(2, LocalDate.class)));
        }
      }
    }
    for (List<Recall.Lot> behind : ancestry(c, tenantId, batchIds).values()) {
      lots.addAll(behind);
    }
    return lots;
  }

  /**
   * For each batch, the lots of the batches whose stock it holds: those it was split from or merged
   * into it from, and theirs in turn, as the lot genealogy says. Only links between batches of one
   * variant count, and a batch with none has no entry. A transfer, a return and a lot split are
   * written as SPLIT links and a merge as a MERGE one; a cut made from a primal (TRANSFORM) is a
   * different product and is not followed.
   */
  private static Map<UUID, List<Recall.Lot>> ancestry(
      Connection c, UUID tenantId, List<UUID> batchIds) throws SQLException {
    Map<UUID, List<Recall.Lot>> out = new LinkedHashMap<>();
    if (batchIds.isEmpty()) {
      return out;
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "WITH RECURSIVE lineage (batch_id, ancestor_id) AS ("
                + " SELECT g.child_batch_id, g.parent_batch_id FROM lot_genealogy g"
                + " JOIN inventory_batches k ON k.tenant_id = g.tenant_id"
                + "  AND k.id = g.child_batch_id"
                + " JOIN inventory_batches p ON p.tenant_id = g.tenant_id"
                + "  AND p.id = g.parent_batch_id AND p.variant_id = k.variant_id"
                + " WHERE g.tenant_id = ? AND g.child_batch_id = ANY (?)"
                + "  AND g.relation_type IN ('SPLIT', 'MERGE')"
                + " UNION"
                + " SELECT l.batch_id, g.parent_batch_id FROM lineage l"
                + " JOIN lot_genealogy g ON g.tenant_id = ? AND g.child_batch_id = l.ancestor_id"
                + "  AND g.relation_type IN ('SPLIT', 'MERGE')"
                + " JOIN inventory_batches k ON k.tenant_id = g.tenant_id"
                + "  AND k.id = g.child_batch_id"
                + " JOIN inventory_batches p ON p.tenant_id = g.tenant_id"
                + "  AND p.id = g.parent_batch_id AND p.variant_id = k.variant_id)"
                + " SELECT l.batch_id, a.batch_no, a.expiry_date FROM lineage l"
                + " JOIN inventory_batches a ON a.tenant_id = ? AND a.id = l.ancestor_id")) {
      ps.setObject(1, tenantId);
      ps.setArray(2, c.createArrayOf("uuid", batchIds.toArray()));
      ps.setObject(3, tenantId);
      ps.setObject(4, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
              .add(new Recall.Lot(rs.getString(2), rs.getObject(3, LocalDate.class)));
        }
      }
    }
    return out;
  }

  // ── reads ──────────────────────────────────────────────────────────────────

  /**
   * One recall in full: header, scope, held batches and store actions.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param recallId the recall to read
   * @return the recall detail, or empty when it does not exist in this tenant
   */
  public Optional<Detail> find(UUID tenantId, UUID recallId) {
    Optional<Header> header =
        query(
                "SELECT " + HEADER_COLUMNS + " FROM recalls r WHERE r.tenant_id = ? AND r.id = ?",
                ps -> {
                  ps.setObject(1, tenantId);
                  ps.setObject(2, recallId);
                },
                RecallRepository::mapHeader,
                "find recall")
            .stream()
            .findFirst();
    return header.map(
        h ->
            new Detail(
                h,
                listScope(tenantId, recallId),
                listHeldBatches(tenantId, recallId),
                listStoreActions(tenantId, recallId),
                reach(tenantId, recallId)));
  }

  private Reach reach(UUID tenantId, UUID recallId) {
    return query(
            "SELECT" + REACH_COLUMNS + " FROM recalls r WHERE r.tenant_id = ? AND r.id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, recallId);
            },
            RecallRepository::mapReach,
            "recall reach")
        .stream()
        .findFirst()
        .orElse(Reach.NONE);
  }

  /**
   * Keyset page of recall summaries, with the counts a manager scans for.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param status restrict to one status, or {@code null} for all
   * @param after cursor keyset, or {@code null} for the first page
   * @param limitPlusOne page size plus one, so the caller can detect a next page
   * @return the page of summaries
   */
  public List<Summary> list(
      UUID tenantId, Status status, Cursor.CreatedAtId after, int limitPlusOne) {
    StringBuilder sql =
        new StringBuilder("SELECT ")
            .append(HEADER_COLUMNS)
            .append(
                ", (SELECT COUNT(*) FROM recall_items i"
                    + "   WHERE i.tenant_id = r.tenant_id AND i.recall_id = r.id) AS scope_lines,"
                    + " (SELECT COUNT(DISTINCT rb.store_id) FROM recall_batches rb"
                    + "   WHERE rb.tenant_id = r.tenant_id AND rb.recall_id = r.id AND"
                    + NOT_RELEASED
                    + ") AS stores_affected,"
                    + " (SELECT COUNT(DISTINCT rb.store_id) FROM recall_batches rb"
                    + "   JOIN inventory_batches b ON b.tenant_id = rb.tenant_id"
                    + "   AND b.id = rb.batch_id"
                    + "   WHERE rb.tenant_id = r.tenant_id AND rb.recall_id = r.id"
                    + "   AND b.remaining_qty > 0 AND"
                    + NOT_RELEASED
                    + ") AS stores_outstanding,"
                    + " (SELECT COALESCE(SUM(rb.qty_at_quarantine), 0) FROM recall_batches rb"
                    + "   WHERE rb.tenant_id = r.tenant_id AND rb.recall_id = r.id AND"
                    + NOT_RELEASED
                    + ") AS qty_held,"
                    + REACH_COLUMNS
                    + " FROM recalls r WHERE r.tenant_id = ?");
    if (status != null) sql.append(" AND r.status = ?");
    if (after != null) sql.append(" AND (r.opened_at, r.id) < (?, ?)");
    sql.append(" ORDER BY r.opened_at DESC, r.id DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (status != null) ps.setString(i++, status.name());
          if (after != null) {
            ps.setObject(i++, utc(after.createdAt()));
            ps.setObject(i++, after.id());
          }
          ps.setInt(i, limitPlusOne);
        },
        rs ->
            new Summary(
                mapHeader(rs),
                rs.getInt("scope_lines"),
                rs.getInt("stores_affected"),
                rs.getInt("stores_outstanding"),
                rs.getBigDecimal("qty_held"),
                mapReach(rs)),
        "list recalls");
  }

  /** Every scope line of every open recall: what the till checks each item against. */
  public List<ActiveItem> listActive(UUID tenantId) {
    return query(
        ACTIVE_ITEMS + " WHERE r.tenant_id = ? AND r.status = 'OPEN' ORDER BY r.opened_at, i.id",
        ps -> ps.setObject(1, tenantId),
        RecallRepository::mapActiveItem,
        "list active recall items");
  }

  /**
   * Every scope line of every open recall, and of every recall closed or cancelled at or after
   * {@code endedSince}: what a till sale rung up offline at that moment is judged against, since a
   * recall that has ended since still covered it then.
   *
   * @param endedSince the earliest end to include; a recall that ended before it is left out
   */
  public List<ActiveItem> listOpenOrEndedSince(UUID tenantId, Instant endedSince) {
    return query(
        ACTIVE_ITEMS
            + " WHERE r.tenant_id = ? AND (r.status = 'OPEN' OR r.ended_at >= ?)"
            + " ORDER BY r.opened_at, i.id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, utc(endedSince));
        },
        RecallRepository::mapActiveItem,
        "list open or recently ended recall items");
  }

  private static ActiveItem mapActiveItem(ResultSet rs) throws SQLException {
    return new ActiveItem(
        rs.getObject("recall_id", UUID.class),
        rs.getString("reference"),
        Kind.valueOf(rs.getString("kind")),
        Hazard.valueOf(rs.getString("hazard")),
        rs.getString("customer_notice"),
        instant(rs, "opened_at"),
        instant(rs, "ended_at"),
        Status.valueOf(rs.getString("status")),
        mapScope(rs));
  }

  private List<Scope> listScope(UUID tenantId, UUID recallId) {
    return query(
        "SELECT i.id, i.variant_id, i.batch_no, i.expiry_from, i.expiry_to FROM recall_items i"
            + " WHERE i.tenant_id = ? AND i.recall_id = ? ORDER BY i.id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, recallId);
        },
        RecallRepository::mapScope,
        "list recall scope");
  }

  private List<HeldBatch> listHeldBatches(UUID tenantId, UUID recallId) {
    return query(
        "SELECT rb.batch_id, rb.store_id, rb.variant_id, b.batch_no, b.expiry_date, rb.match_type,"
            + " rb.qty_at_quarantine, b.remaining_qty, rb.quarantined_on, rb.quarantined_at,"
            + " x.reason AS release_reason, x.released_by, x.released_at"
            + " FROM recall_batches rb"
            + " JOIN inventory_batches b ON b.tenant_id = rb.tenant_id AND b.id = rb.batch_id"
            + " LEFT JOIN recall_batch_releases x ON x.tenant_id = rb.tenant_id"
            + " AND x.recall_id = rb.recall_id AND x.batch_id = rb.batch_id"
            + " WHERE rb.tenant_id = ? AND rb.recall_id = ?"
            + " ORDER BY rb.store_id, rb.quarantined_at, rb.batch_id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, recallId);
        },
        rs -> {
          String releaseReason = rs.getString("release_reason");
          return new HeldBatch(
              rs.getObject("batch_id", UUID.class),
              rs.getObject("store_id", UUID.class),
              rs.getObject("variant_id", UUID.class),
              rs.getString("batch_no"),
              rs.getObject("expiry_date", LocalDate.class),
              Match.valueOf(rs.getString("match_type")),
              rs.getBigDecimal("qty_at_quarantine"),
              rs.getBigDecimal("remaining_qty"),
              QuarantinedOn.valueOf(rs.getString("quarantined_on")),
              instant(rs, "quarantined_at"),
              releaseReason == null
                  ? null
                  : new Release(
                      releaseReason,
                      rs.getObject("released_by", UUID.class),
                      instant(rs, "released_at")));
        },
        "list recall batches");
  }

  private List<StoreAction> listStoreActions(UUID tenantId, UUID recallId) {
    return query(
        "SELECT a.id, a.store_id, a.qty_found, a.system_qty, a.disposition, a.notice_displayed,"
            + " a.notes, a.recorded_by, a.recorded_at FROM recall_store_actions a"
            + " WHERE a.tenant_id = ? AND a.recall_id = ? ORDER BY a.recorded_at, a.id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, recallId);
        },
        rs ->
            new StoreAction(
                rs.getObject("id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getBigDecimal("qty_found"),
                rs.getBigDecimal("system_qty"),
                Disposition.valueOf(rs.getString("disposition")),
                rs.getBoolean("notice_displayed"),
                rs.getString("notes"),
                rs.getObject("recorded_by", UUID.class),
                instant(rs, "recorded_at")),
        "list recall store actions");
  }

  // ── what stores do ─────────────────────────────────────────────────────────

  /**
   * Records what a store found and did. A final disposition takes the store's held stock off the
   * books in the same transaction — it has left the business — so the movement ledger, stock value
   * and every projection of stock see it go.
   *
   * @param stockAdjusted builds the event for one variant's write-off
   */
  public StoreAction recordStoreAction(
      UUID tenantId,
      UUID recallId,
      StoreAction action,
      BiFunction<UUID, BigDecimal, OutboxRow> stockAdjusted) {
    return inTx(
        c -> {
          lockOpen(c, tenantId, recallId);
          List<HeldForAction> held = lockHeldAtStore(c, tenantId, recallId, action.storeId());
          BigDecimal systemQty =
              held.stream()
                  .map(HeldForAction::remainingQty)
                  .reduce(BigDecimal.ZERO, BigDecimal::add);
          if (action.disposition().isFinal()) {
            writeOff(c, tenantId, recallId, action, held, stockAdjusted);
          }
          var stored =
              new StoreAction(
                  action.id(),
                  action.storeId(),
                  action.qtyFound(),
                  systemQty,
                  action.disposition(),
                  action.noticeDisplayed(),
                  action.notes(),
                  action.recordedBy(),
                  action.recordedAt());
          insertStoreAction(c, tenantId, recallId, stored);
          return stored;
        },
        "record recall store action");
  }

  /**
   * Releases a batch a member of staff checked and found not to be affected. Only a batch the
   * recall could not be sure of can be released; its status is restored once no open recall holds
   * it.
   */
  public void release(
      UUID tenantId,
      UUID recallId,
      UUID batchId,
      String reason,
      UUID actorId,
      Consumer<UUID> requireStoreAccess) {
    inTx(
        c -> {
          lockOpen(c, tenantId, recallId);
          HoldRow hold = findHold(c, tenantId, recallId, batchId);
          requireStoreAccess.accept(hold.storeId());
          if (!hold.match().isReleasable()) {
            throw ApiException.conflict(
                "RECALL_BATCH_IN_SCOPE",
                "This batch's lot and dates are in the recall's scope, so it cannot be released");
          }
          if (hold.released()) {
            throw ApiException.conflict(
                "RECALL_BATCH_ALREADY_RELEASED", "This batch has already been released");
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO recall_batch_releases (id, tenant_id, recall_id, batch_id, reason,"
                      + " released_by) VALUES (?,?,?,?,?,?)")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, recallId);
            ps.setObject(4, batchId);
            ps.setString(5, reason);
            ps.setObject(6, actorId);
            ps.executeUpdate();
          }
          restoreIfNoLongerHeld(c, tenantId, batchId, hold.priorStatus());
          return null;
        },
        "release recalled batch");
  }

  /**
   * Closes a recall once no store still holds stock it took off sale.
   *
   * @throws ApiException 409 {@code RECALL_STORES_OUTSTANDING}, naming the stores
   */
  public void close(UUID tenantId, UUID recallId, UUID actorId, String notes) {
    inTx(
        c -> {
          lockOpen(c, tenantId, recallId);
          List<String> outstanding = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT DISTINCT rb.store_id FROM recall_batches rb"
                      + " JOIN inventory_batches b ON b.tenant_id = rb.tenant_id"
                      + " AND b.id = rb.batch_id"
                      + " WHERE rb.tenant_id = ? AND rb.recall_id = ? AND b.remaining_qty > 0 AND"
                      + NOT_RELEASED
                      + " ORDER BY rb.store_id")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, recallId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                outstanding.add(rs.getObject(1, UUID.class).toString());
              }
            }
          }
          if (!outstanding.isEmpty()) {
            throw new ApiException(
                409,
                "RECALL_STORES_OUTSTANDING",
                "These stores still hold recalled stock with no final disposition recorded",
                outstanding);
          }
          end(c, tenantId, recallId, Status.CLOSED, actorId, notes);
          return null;
        },
        "close recall");
  }

  /**
   * Cancels a recall opened in error and puts its stock back on sale. Refused once any store has
   * disposed of stock under it: that stock has left the books, and cancelling would not bring it
   * back.
   */
  public void cancel(UUID tenantId, UUID recallId, UUID actorId, String reason) {
    inTx(
        c -> {
          lockOpen(c, tenantId, recallId);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT EXISTS (SELECT 1 FROM recall_store_actions a WHERE a.tenant_id = ?"
                      + " AND a.recall_id = ? AND a.disposition <> 'HELD_FOR_COLLECTION')")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, recallId);
            try (ResultSet rs = ps.executeQuery()) {
              if (rs.next() && rs.getBoolean(1)) {
                throw ApiException.conflict(
                    "RECALL_ALREADY_ACTIONED",
                    "Stock has already been disposed of under this recall; close it instead");
              }
            }
          }
          end(c, tenantId, recallId, Status.CANCELLED, actorId, reason);
          List<UUID> batches = new ArrayList<>();
          List<String> priors = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT rb.batch_id, rb.prior_material_status FROM recall_batches rb"
                      + " WHERE rb.tenant_id = ? AND rb.recall_id = ? AND"
                      + NOT_RELEASED
                      + " ORDER BY rb.batch_id")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, recallId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                batches.add(rs.getObject(1, UUID.class));
                priors.add(rs.getString(2));
              }
            }
          }
          for (int i = 0; i < batches.size(); i++) {
            restoreIfNoLongerHeld(c, tenantId, batches.get(i), priors.get(i));
          }
          return null;
        },
        "cancel recall");
  }

  // ── transaction steps ──────────────────────────────────────────────────────

  private static void insertHeader(Connection c, Header h) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO recalls (id, tenant_id, reference, kind, hazard, reason, customer_notice,"
                + " source, source_reference, status, opened_by, opened_at, remedies,"
                + " single_remedy_reason, contact_phone, contact_url, sold_from)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, h.id());
      ps.setObject(2, h.tenantId());
      ps.setString(3, h.reference());
      ps.setString(4, h.kind().name());
      ps.setString(5, h.hazard().name());
      ps.setString(6, h.reason());
      ps.setString(7, h.customerNotice());
      ps.setString(8, h.source().name());
      ps.setString(9, h.sourceReference());
      ps.setString(10, h.status().name());
      ps.setObject(11, h.openedBy());
      ps.setObject(12, utc(h.openedAt()));
      ps.setString(13, Remedy.csv(h.remedies()));
      ps.setString(14, h.singleRemedyReason());
      ps.setString(15, h.contactPhone());
      ps.setString(16, h.contactUrl());
      ps.setObject(17, h.soldFrom());
      ps.executeUpdate();
    }
  }

  private static void insertScope(Connection c, Header h, Scope line) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO recall_items (id, tenant_id, recall_id, variant_id, batch_no, expiry_from,"
                + " expiry_to) VALUES (?,?,?,?,?,?,?)")) {
      ps.setObject(1, line.id());
      ps.setObject(2, h.tenantId());
      ps.setObject(3, h.id());
      ps.setObject(4, line.variantId());
      ps.setString(5, line.batchNo());
      ps.setObject(6, line.expiryFrom());
      ps.setObject(7, line.expiryTo());
      ps.executeUpdate();
    }
  }

  /**
   * Every batch of a scoped variant still holding stock, locked against sale while it is judged.
   */
  private static List<Candidate> lockCandidates(Connection c, UUID tenantId, List<Scope> scope)
      throws SQLException {
    Object[] variants = scope.stream().map(Scope::variantId).distinct().toArray();
    List<Candidate> out = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT b.id, b.store_id, b.variant_id, b.batch_no, b.expiry_date, b.remaining_qty,"
                + " b.material_status FROM inventory_batches b"
                + " WHERE b.tenant_id = ? AND b.variant_id = ANY (?) AND b.remaining_qty > 0"
                + " ORDER BY b.id FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setArray(2, c.createArrayOf("uuid", variants));
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(
              new Candidate(
                  rs.getObject("id", UUID.class),
                  rs.getObject("store_id", UUID.class),
                  rs.getObject("variant_id", UUID.class),
                  rs.getString("batch_no"),
                  rs.getObject("expiry_date", LocalDate.class),
                  rs.getBigDecimal("remaining_qty"),
                  rs.getString("material_status")));
        }
      }
    }
    return out;
  }

  /**
   * Holds one batch for one recall. The status to restore is the one from before any recall held
   * the batch: if another open recall already does, its record of that status is carried, so
   * letting either recall go never restores "RECALLED" as though it were the original.
   */
  private static void hold(
      Connection c,
      UUID tenantId,
      UUID recallId,
      Candidate batch,
      Match match,
      QuarantinedOn on,
      String reason)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO recall_batches (tenant_id, recall_id, batch_id, store_id, variant_id,"
                + " match_type, qty_at_quarantine, prior_material_status, quarantined_on)"
                + " VALUES (?,?,?,?,?,?,?, COALESCE((SELECT rb.prior_material_status"
                + "   FROM recall_batches rb JOIN recalls r ON r.tenant_id = rb.tenant_id"
                + "   AND r.id = rb.recall_id"
                + "   WHERE rb.tenant_id = ? AND rb.batch_id = ? AND r.status = 'OPEN' AND"
                + NOT_RELEASED
                + "   ORDER BY rb.quarantined_at LIMIT 1), ?), ?)"
                + " ON CONFLICT (tenant_id, recall_id, batch_id) DO NOTHING")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, recallId);
      ps.setObject(3, batch.batchId());
      ps.setObject(4, batch.storeId());
      ps.setObject(5, batch.variantId());
      ps.setString(6, match.name());
      ps.setBigDecimal(7, batch.remainingQty());
      ps.setObject(8, tenantId);
      ps.setObject(9, batch.batchId());
      ps.setString(10, batch.materialStatus());
      ps.setString(11, on.name());
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE inventory_batches SET material_status = ?, material_status_reason = ?,"
                + " material_status_changed_at = now() WHERE tenant_id = ? AND id = ?")) {
      ps.setString(1, Batch.MATERIAL_RECALLED);
      ps.setString(2, reason);
      ps.setObject(3, tenantId);
      ps.setObject(4, batch.batchId());
      ps.executeUpdate();
    }
  }

  private static void restoreIfNoLongerHeld(
      Connection c, UUID tenantId, UUID batchId, String priorStatus) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE inventory_batches SET material_status = ?, material_status_reason = NULL,"
                + " material_status_changed_at = now()"
                + " WHERE tenant_id = ? AND id = ? AND material_status = ?"
                + " AND NOT EXISTS (SELECT 1 FROM recall_batches rb JOIN recalls r"
                + "   ON r.tenant_id = rb.tenant_id AND r.id = rb.recall_id"
                + "   WHERE rb.tenant_id = ? AND rb.batch_id = ? AND r.status = 'OPEN' AND"
                + NOT_RELEASED
                + ")")) {
      ps.setString(1, priorStatus);
      ps.setObject(2, tenantId);
      ps.setObject(3, batchId);
      ps.setString(4, Batch.MATERIAL_RECALLED);
      ps.setObject(5, tenantId);
      ps.setObject(6, batchId);
      ps.executeUpdate();
    }
  }

  private static void lockOpen(Connection c, UUID tenantId, UUID recallId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT r.status FROM recalls r WHERE r.tenant_id = ? AND r.id = ? FOR UPDATE")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, recallId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw ApiException.notFound("RECALL_NOT_FOUND", "No such recall");
        }
        if (Status.valueOf(rs.getString(1)) != Status.OPEN) {
          throw ApiException.conflict("RECALL_NOT_OPEN", "This recall is no longer open");
        }
      }
    }
  }

  private static HoldRow findHold(Connection c, UUID tenantId, UUID recallId, UUID batchId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT rb.store_id, rb.match_type, rb.prior_material_status,"
                + " EXISTS (SELECT 1 FROM recall_batch_releases x WHERE x.tenant_id = rb.tenant_id"
                + " AND x.recall_id = rb.recall_id AND x.batch_id = rb.batch_id) AS released"
                + " FROM recall_batches rb"
                + " WHERE rb.tenant_id = ? AND rb.recall_id = ? AND rb.batch_id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, recallId);
      ps.setObject(3, batchId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw ApiException.notFound(
              "RECALL_BATCH_NOT_HELD", "This recall does not hold that batch");
        }
        return new HoldRow(
            rs.getObject("store_id", UUID.class),
            Match.valueOf(rs.getString("match_type")),
            rs.getString("prior_material_status"),
            rs.getBoolean("released"));
      }
    }
  }

  private static List<HeldForAction> lockHeldAtStore(
      Connection c, UUID tenantId, UUID recallId, UUID storeId) throws SQLException {
    List<HeldForAction> out = new ArrayList<>();
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT b.id, b.variant_id, b.remaining_qty FROM recall_batches rb"
                + " JOIN inventory_batches b ON b.tenant_id = rb.tenant_id AND b.id = rb.batch_id"
                + " WHERE rb.tenant_id = ? AND rb.recall_id = ? AND rb.store_id = ? AND"
                + NOT_RELEASED
                + " ORDER BY b.id FOR UPDATE OF b")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, recallId);
      ps.setObject(3, storeId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(
              new HeldForAction(
                  rs.getObject("id", UUID.class),
                  rs.getObject("variant_id", UUID.class),
                  rs.getBigDecimal("remaining_qty")));
        }
      }
    }
    return out;
  }

  private void writeOff(
      Connection c,
      UUID tenantId,
      UUID recallId,
      StoreAction action,
      List<HeldForAction> held,
      BiFunction<UUID, BigDecimal, OutboxRow> stockAdjusted)
      throws SQLException {
    Map<UUID, BigDecimal> deltaByVariant = new LinkedHashMap<>();
    MovementAttribution attribution = MovementAttribution.by(action.recordedBy(), WRITE_OFF_REASON);
    for (HeldForAction batch : held) {
      if (batch.remainingQty().signum() <= 0) {
        continue;
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE inventory_batches SET remaining_qty = 0 WHERE tenant_id = ? AND id = ?")) {
        ps.setObject(1, tenantId);
        ps.setObject(2, batch.batchId());
        ps.executeUpdate();
      }
      InventoryRepository.insertMovement(
          c,
          tenantId,
          action.storeId(),
          batch.variantId(),
          batch.batchId(),
          MoveType.ADJUST,
          batch.remainingQty().negate(),
          REF_TYPE,
          recallId,
          attribution);
      deltaByVariant.merge(batch.variantId(), batch.remainingQty().negate(), BigDecimal::add);
    }
    for (var entry : deltaByVariant.entrySet()) {
      insertOutbox(c, stockAdjusted.apply(entry.getKey(), entry.getValue()));
    }
  }

  private static void insertStoreAction(Connection c, UUID tenantId, UUID recallId, StoreAction a)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO recall_store_actions (id, tenant_id, recall_id, store_id, qty_found,"
                + " system_qty, disposition, notice_displayed, notes, recorded_by, recorded_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, a.id());
      ps.setObject(2, tenantId);
      ps.setObject(3, recallId);
      ps.setObject(4, a.storeId());
      ps.setBigDecimal(5, a.qtyFound());
      ps.setBigDecimal(6, a.systemQty());
      ps.setString(7, a.disposition().name());
      ps.setBoolean(8, a.noticeDisplayed());
      ps.setString(9, a.notes());
      ps.setObject(10, a.recordedBy());
      ps.setObject(11, utc(a.recordedAt()));
      ps.executeUpdate();
    }
  }

  private static void end(
      Connection c, UUID tenantId, UUID recallId, Status status, UUID actorId, String notes)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE recalls SET status = ?, ended_by = ?, ended_at = now(), end_notes = ?"
                + " WHERE tenant_id = ? AND id = ?")) {
      ps.setString(1, status.name());
      ps.setObject(2, actorId);
      ps.setString(3, notes);
      ps.setObject(4, tenantId);
      ps.setObject(5, recallId);
      ps.executeUpdate();
    }
  }

  // ── mapping ────────────────────────────────────────────────────────────────

  private static String holdReason(String reference) {
    return "Recall " + reference;
  }

  private static Header mapHeader(ResultSet rs) throws SQLException {
    return new Header(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("reference"),
        Kind.valueOf(rs.getString("kind")),
        Hazard.valueOf(rs.getString("hazard")),
        rs.getString("reason"),
        rs.getString("customer_notice"),
        Source.valueOf(rs.getString("source")),
        rs.getString("source_reference"),
        Status.valueOf(rs.getString("status")),
        rs.getObject("opened_by", UUID.class),
        instant(rs, "opened_at"),
        rs.getObject("ended_by", UUID.class),
        instant(rs, "ended_at"),
        rs.getString("end_notes"),
        Remedy.parse(rs.getString("remedies")),
        rs.getString("single_remedy_reason"),
        rs.getString("contact_phone"),
        rs.getString("contact_url"),
        rs.getObject("sold_from", LocalDate.class));
  }

  private static Reach mapReach(ResultSet rs) throws SQLException {
    return new Reach(rs.getInt("orders_affected"), rs.getBigDecimal("qty_sold"));
  }

  private static Scope mapScope(ResultSet rs) throws SQLException {
    return new Scope(
        rs.getObject("id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("batch_no"),
        rs.getObject("expiry_from", LocalDate.class),
        rs.getObject("expiry_to", LocalDate.class));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
