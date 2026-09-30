package com.storeql.order.repo;

import com.storeql.order.domain.Domain.AuditEvent;
import com.storeql.order.domain.Domain.AuditReturnLine;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the business audit trail (20.11): the append-only logs this service writes — discounts,
 * voids, no-sales, cancellations, returns, and offline sales flagged for a manager — as one
 * time-ordered stream.
 *
 * <p>Each log keeps its own shape and indexes. The stream is a {@code UNION ALL} of one branch per
 * log, every branch filtered by tenant first, projected onto one set of columns; the filters on
 * store, actor and period sit on the outer query over those columns, where Postgres pushes them
 * into each branch so a branch is answered from its own index. Nothing here writes: the logs are
 * append-only, and this class is the reader the exception report's indexes were built for.
 */
@ApplicationScoped
public class AuditTrailRepository extends BaseJdbcRepository {

  /** One log's contribution to the stream: its type and the branch that projects it. */
  private record Branch(String type, String sql) {}

  /** What every log but a return projects for the columns only a return fills. */
  private static final String NO_APPROVAL =
      " NULL::uuid AS approved_by, NULL::text[] AS outside_policy";

  private static final List<Branch> BRANCHES =
      List.of(
          new Branch(
              AuditEvent.TYPE_DISCOUNT,
              "SELECT id, tenant_id, 'DISCOUNT' AS type, created_at AS occurred_at,"
                  + " granted_by AS actor_id, store_id, order_id, discount_amount AS amount,"
                  + " reason, granted_role AS detail, NULL::uuid AS variant_id,"
                  + " NULL::uuid AS replayed_by,"
                  + NO_APPROVAL
                  + " FROM order_discounts WHERE tenant_id=?"),
          new Branch(
              AuditEvent.TYPE_VOID,
              "SELECT id, tenant_id, 'VOID' AS type, voided_at AS occurred_at,"
                  + " voided_by AS actor_id, store_id, order_id, NULL::numeric AS amount,"
                  + " reason, NULL::text AS detail, NULL::uuid AS variant_id,"
                  + " NULL::uuid AS replayed_by,"
                  + NO_APPROVAL
                  + " FROM pos_void_log WHERE tenant_id=?"),
          new Branch(
              AuditEvent.TYPE_NO_SALE,
              "SELECT id, tenant_id, 'NO_SALE' AS type, logged_at AS occurred_at,"
                  + " cashier_id AS actor_id, store_id, NULL::uuid AS order_id,"
                  + " NULL::numeric AS amount, reason, authorised_by::text AS detail,"
                  + " NULL::uuid AS variant_id, NULL::uuid AS replayed_by,"
                  + NO_APPROVAL
                  + " FROM pos_no_sale_log WHERE tenant_id=?"),
          new Branch(
              AuditEvent.TYPE_CANCEL,
              // The history row has no store; the order it belongs to does. Same service, same
              // schema, so the join is the honest way to put a cancel at its store.
              "SELECT h.id, h.tenant_id, 'CANCEL' AS type, h.changed_at AS occurred_at,"
                  + " h.changed_by AS actor_id, o.store_id, h.order_id, NULL::numeric AS amount,"
                  + " h.reason, h.from_status AS detail, NULL::uuid AS variant_id,"
                  + " NULL::uuid AS replayed_by,"
                  + NO_APPROVAL
                  + " FROM order_status_history h"
                  + " JOIN orders o ON o.tenant_id = h.tenant_id AND o.id = h.order_id"
                  + " WHERE h.tenant_id=? AND h.to_status='CANCELLED'"),
          // A catalogue-mode order given its price by a manager (SJ-D41): the history row of the
          // AWAITING_PRICE to PENDING move names who priced it and when, the order the total it
          // was priced at. Priced once, so one entry per order.
          new Branch(
              AuditEvent.TYPE_PRICED,
              "SELECT h.id, h.tenant_id, 'PRICED' AS type, h.changed_at AS occurred_at,"
                  + " h.changed_by AS actor_id, o.store_id, h.order_id, o.total AS amount,"
                  + " h.reason, NULL::text AS detail, NULL::uuid AS variant_id,"
                  + " NULL::uuid AS replayed_by,"
                  + NO_APPROVAL
                  + " FROM order_status_history h"
                  + " JOIN orders o ON o.tenant_id = h.tenant_id AND o.id = h.order_id"
                  + " WHERE h.tenant_id=? AND h.from_status='AWAITING_PRICE'"
                  + " AND h.to_status='PENDING'"),
          new Branch(
              AuditEvent.TYPE_RETURN,
              "SELECT id, tenant_id, 'RETURN' AS type, created_at AS occurred_at,"
                  + " created_by AS actor_id, store_id, order_id, refund_amount AS amount,"
                  + " reason, refund_method AS detail, NULL::uuid AS variant_id,"
                  + " NULL::uuid AS replayed_by, approved_by, outside_policy"
                  + " FROM returns WHERE tenant_id=?"),
          // An offline sale flagged for a manager is put at the moment it was rung up: the entry
          // is about the sale, and that is when the cashier made it, whenever the till came back.
          // Its actor is who rang it up (null when the till named nobody the business holds at
          // the store), and replayed_by who sent it, which may be somebody else.
          new Branch(
              AuditEvent.TYPE_OFFLINE_SALE_OF_RECALLED_ITEM,
              "SELECT id, tenant_id, 'OFFLINE_SALE_OF_RECALLED_ITEM' AS type,"
                  + " rung_up_at AS occurred_at, cashier_id AS actor_id, store_id, order_id,"
                  + " NULL::numeric AS amount, reason, recall_reference AS detail, variant_id,"
                  + " replayed_by,"
                  + NO_APPROVAL
                  + " FROM offline_sale_flags"
                  + " WHERE tenant_id=? AND kind='OFFLINE_SALE_OF_RECALLED_ITEM'"),
          new Branch(
              AuditEvent.TYPE_OFFLINE_SALE_ON_UNFIT_SCALE,
              "SELECT id, tenant_id, 'OFFLINE_SALE_ON_UNFIT_SCALE' AS type,"
                  + " rung_up_at AS occurred_at, cashier_id AS actor_id, store_id, order_id,"
                  + " NULL::numeric AS amount, reason, instrument_standing AS detail, variant_id,"
                  + " replayed_by,"
                  + NO_APPROVAL
                  + " FROM offline_sale_flags"
                  + " WHERE tenant_id=? AND kind='OFFLINE_SALE_ON_UNFIT_SCALE'"));

  /**
   * One page of the trail, newest first.
   *
   * @param tenantId owning tenant; the first condition of every branch and of the outer query
   * @param storeId one store, or {@code null} for every store the caller may see
   * @param storeScope the stores the caller is limited to, or {@code null} when unrestricted;
   *     applied when {@code storeId} is null so a store-bound manager never reads another store
   * @param actorId one member of staff, or {@code null}
   * @param type one of {@link AuditEvent#TYPES}, or {@code null} for every log
   * @param from inclusive lower bound, or {@code null}
   * @param to exclusive upper bound, or {@code null}
   * @param afterAt cursor timestamp, or {@code null} for the first page
   * @param afterId cursor id, the tiebreaker for equal timestamps
   * @param limit maximum rows; callers pass one more than the page size
   * @return the rows, newest first
   */
  public List<AuditEvent> list(
      UUID tenantId,
      UUID storeId,
      Collection<UUID> storeScope,
      UUID actorId,
      String type,
      Instant from,
      Instant to,
      Instant afterAt,
      UUID afterId,
      int limit) {
    List<Object> params = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, type, occurred_at, actor_id, store_id, order_id, amount,"
                + " reason, detail, variant_id, replayed_by, approved_by, outside_policy FROM (");
    boolean first = true;
    for (Branch b : BRANCHES) {
      if (type != null && !type.equals(b.type())) continue;
      if (!first) sql.append(" UNION ALL ");
      sql.append(b.sql());
      params.add(tenantId);
      first = false;
    }
    sql.append(") e WHERE e.tenant_id=?");
    params.add(tenantId);
    if (storeId != null) {
      sql.append(" AND e.store_id=?");
      params.add(storeId);
    } else if (storeScope != null && !storeScope.isEmpty()) {
      sql.append(" AND e.store_id = ANY(?)");
      params.add(storeScope.toArray(UUID[]::new));
    }
    if (actorId != null) {
      sql.append(" AND e.actor_id=?");
      params.add(actorId);
    }
    if (from != null) {
      sql.append(" AND e.occurred_at >= ?");
      params.add(from);
    }
    if (to != null) {
      sql.append(" AND e.occurred_at < ?");
      params.add(to);
    }
    if (afterAt != null && afterId != null) {
      sql.append(" AND (e.occurred_at, e.id) < (?, ?)");
      params.add(afterAt);
      params.add(afterId);
    }
    sql.append(" ORDER BY e.occurred_at DESC, e.id DESC LIMIT ?");
    params.add(limit);
    List<AuditEvent> page =
        query(sql.toString(), ps -> bindAll(ps, params), AuditTrailRepository::map, "audit trail");
    return withReturnLines(tenantId, page);
  }

  /**
   * Puts each return's lines on its entry. Read for the page only, by the ids on it, rather than
   * inside the stream's branch, where it would be worked out for every return the business has ever
   * made before the page was cut.
   */
  private List<AuditEvent> withReturnLines(UUID tenantId, List<AuditEvent> page) {
    UUID[] returnIds =
        page.stream()
            .filter(e -> AuditEvent.TYPE_RETURN.equals(e.type()))
            .map(AuditEvent::id)
            .toArray(UUID[]::new);
    if (returnIds.length == 0) return page;
    Map<UUID, List<AuditReturnLine>> byReturn = new HashMap<>();
    query(
        "SELECT return_id, variant_id, qty, condition FROM return_items"
            + " WHERE tenant_id=? AND return_id = ANY(?) ORDER BY return_id, id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setArray(2, ps.getConnection().createArrayOf("uuid", returnIds));
        },
        rs -> {
          byReturn
              .computeIfAbsent(rs.getObject("return_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new AuditReturnLine(
                      rs.getObject("variant_id", UUID.class),
                      rs.getBigDecimal("qty"),
                      rs.getString("condition")));
          return Boolean.TRUE;
        },
        "audit return lines");
    return page.stream()
        .map(
            e ->
                AuditEvent.TYPE_RETURN.equals(e.type())
                    ? new AuditEvent(
                        e.id(),
                        e.type(),
                        e.occurredAt(),
                        e.actorId(),
                        e.storeId(),
                        e.orderId(),
                        e.amount(),
                        e.reason(),
                        e.detail(),
                        e.variantId(),
                        e.replayedBy(),
                        e.approvedBy(),
                        e.outsidePolicy(),
                        List.copyOf(byReturn.getOrDefault(e.id(), List.of())))
                    : e)
        .toList();
  }

  private static void bindAll(PreparedStatement ps, List<Object> params) throws SQLException {
    int i = 1;
    for (Object p : params) {
      if (p instanceof Instant instant) {
        ps.setObject(i++, instant.atOffset(ZoneOffset.UTC));
      } else if (p instanceof Integer n) {
        ps.setInt(i++, n);
      } else if (p instanceof UUID[] ids) {
        ps.setArray(i++, ps.getConnection().createArrayOf("uuid", ids));
      } else {
        ps.setObject(i++, p);
      }
    }
  }

  private static AuditEvent map(ResultSet rs) throws SQLException {
    OffsetDateTime at = rs.getObject("occurred_at", OffsetDateTime.class);
    return new AuditEvent(
        rs.getObject("id", UUID.class),
        rs.getString("type"),
        at == null ? null : at.toInstant(),
        rs.getObject("actor_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("order_id", UUID.class),
        rs.getBigDecimal("amount"),
        rs.getString("reason"),
        rs.getString("detail"),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("replayed_by", UUID.class),
        rs.getObject("approved_by", UUID.class),
        textArray(rs.getArray("outside_policy")),
        null);
  }

  private static List<String> textArray(java.sql.Array a) throws SQLException {
    return a == null ? List.of() : List.of((String[]) a.getArray());
  }
}
