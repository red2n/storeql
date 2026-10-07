package com.storeql.pricing.repo;

import com.storeql.ids.Ids;
import com.storeql.pricing.domain.Domain.AppliedPrice;
import com.storeql.pricing.domain.Domain.PriceEvaluation;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The applied-price ledger and its evaluation queue (03.12). The ledger is append-only in the
 * schema; this class only ever inserts into it.
 *
 * <p>Every moment written here is the database's own clock, so an evaluation's moment and the
 * moment a price list, promotion or price was made or switched are read on one clock.
 */
@ApplicationScoped
public class AppliedPriceRepository extends BaseJdbcRepository {

  private static final String LEDGER_COLUMNS =
      "id, tenant_id, variant_id, channel, store_id, priced, price, net_price, regular_price,"
          + " promotion_name, currency, applied_from, uncertain_since, recorded_at, cause";

  /** What recording one evaluated offer did. */
  public enum Outcome {
    /** The offer was already the latest row: nothing appended. */
    UNCHANGED,
    /** A row was appended. */
    APPENDED,
    /**
     * The evaluation is older than one already worked for this key, so it was recorded as
     * uncertain; the key needs evaluating again.
     */
    OUT_OF_ORDER
  }

  /** The evaluations still due for a tenant: every variant, or these. */
  public static final class Pending {
    private final boolean everyVariant;
    private final Set<UUID> variants;

    Pending(boolean everyVariant, Set<UUID> variants) {
      this.everyVariant = everyVariant;
      this.variants = Set.copyOf(variants);
    }

    public boolean covers(UUID variantId) {
      return everyVariant || variants.contains(variantId);
    }

    public boolean any() {
      return everyVariant || !variants.isEmpty();
    }
  }

  // ── the queue ───────────────────────────────────────────────────────────────

  /**
   * Queues an evaluation inside the caller's transaction, so the change and the promise to record
   * its effect commit together. The moment is never earlier than the change itself.
   *
   * @param variantId the variant, or null for every priced variant of the tenant
   * @param asOf a scheduled moment to evaluate at, or null (or a past moment) for now
   * @param overwrites whether the change overwrote something an evaluation cannot read as of an
   *     earlier moment — a VAT rate, a VAT category, the catalogue
   */
  public static void enqueue(
      Connection c, UUID tenantId, UUID variantId, Instant asOf, String cause, boolean overwrites)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO price_evaluations"
                + " (id, tenant_id, variant_id, as_of, due_at, cause, overwrites, enqueued_at)"
                + " VALUES (?, ?, ?, GREATEST(?::timestamptz, clock_timestamp()),"
                + " GREATEST(?::timestamptz, clock_timestamp()), ?, ?, clock_timestamp())")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, variantId);
      ps.setObject(4, asOf == null ? null : utc(asOf));
      ps.setObject(5, asOf == null ? null : utc(asOf));
      ps.setString(6, cause);
      ps.setBoolean(7, overwrites);
      ps.executeUpdate();
    }
  }

  /** As {@link #enqueue(Connection, UUID, UUID, Instant, String, boolean)}, for a change kept. */
  public static void enqueue(
      Connection c, UUID tenantId, UUID variantId, Instant asOf, String cause) throws SQLException {
    enqueue(c, tenantId, variantId, asOf, cause, false);
  }

  /** Queues an evaluation in its own transaction. */
  public void enqueue(UUID tenantId, UUID variantId, Instant asOf, String cause) {
    inTx(
        c -> {
          enqueue(c, tenantId, variantId, asOf, cause, false);
          return null;
        },
        "enqueue price evaluation");
  }

  /**
   * Takes up to {@code limit} evaluations that are due, pushing each one's due time on by {@code
   * lease}: a worker that dies leaves them to be taken again, and two workers never take the same.
   * An evaluation is taken only when nothing earlier for the same variant — or for the whole tenant
   * — is still queued, so each variant's changes are recorded in the order they happened, whichever
   * worker takes them.
   */
  public List<PriceEvaluation> claimDue(int limit, Duration lease) {
    return claim(null, limit, lease);
  }

  /** As {@link #claimDue}, for one tenant's variant-level evaluations only. */
  public List<PriceEvaluation> claimDueFor(UUID tenantId, int limit, Duration lease) {
    return claim(tenantId, limit, lease);
  }

  private List<PriceEvaluation> claim(UUID tenantId, int limit, Duration lease) {
    return inTx(
        c -> {
          List<PriceEvaluation> out = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE price_evaluations SET due_at = clock_timestamp() + (? * interval '1 second')"
                      + " WHERE id IN (SELECT e.id FROM price_evaluations e WHERE "
                      + (tenantId == null
                          ? ""
                          : "e.tenant_id = ? AND e.variant_id IS NOT NULL AND ")
                      + "e.due_at <= clock_timestamp()"
                      + " AND NOT EXISTS (SELECT 1 FROM price_evaluations o"
                      + " WHERE o.tenant_id = e.tenant_id"
                      + " AND (o.variant_id = e.variant_id OR o.variant_id IS NULL OR e.variant_id IS NULL)"
                      + " AND (o.as_of < e.as_of OR (o.as_of = e.as_of AND o.id < e.id)))"
                      + " ORDER BY e.due_at, e.id LIMIT ? FOR UPDATE OF e SKIP LOCKED)"
                      + " RETURNING id, tenant_id, variant_id, as_of, cause, enqueued_at")) {
            int i = 1;
            ps.setLong(i++, lease.toSeconds());
            if (tenantId != null) ps.setObject(i++, tenantId);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(
                    new PriceEvaluation(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("variant_id", UUID.class),
                        rs.getObject("as_of", OffsetDateTime.class).toInstant(),
                        rs.getString("cause"),
                        rs.getObject("enqueued_at", OffsetDateTime.class).toInstant()));
              }
            }
          }
          return out;
        },
        "claim price evaluations");
  }

  /**
   * Pushes a claimed evaluation's due time on again, so a long whole-tenant run is not taken a
   * second time by another worker while it is still being worked.
   *
   * @param tenantId the evaluation's tenant; the first condition of the query
   * @param id the evaluation being worked
   * @param lease how far from now it stays ours
   */
  public void renewLease(UUID tenantId, UUID id, Duration lease) {
    exec(
        "UPDATE price_evaluations SET due_at = clock_timestamp() + (? * interval '1 second')"
            + " WHERE tenant_id = ? AND id = ?",
        ps -> {
          ps.setLong(1, lease.toSeconds());
          ps.setObject(2, tenantId);
          ps.setObject(3, id);
        },
        "renew price evaluation lease");
  }

  /** An evaluation done. */
  public void complete(UUID id) {
    exec(
        "DELETE FROM price_evaluations WHERE id = ?",
        ps -> ps.setObject(1, id),
        "complete price evaluation");
  }

  /**
   * Evaluations due and not yet done for a tenant.
   *
   * @param tenantId owning tenant; the first condition of the query
   */
  public int pending(UUID tenantId) {
    return query(
            "SELECT count(*) FROM price_evaluations WHERE tenant_id = ? AND as_of <= clock_timestamp()",
            ps -> ps.setObject(1, tenantId),
            rs -> rs.getInt(1),
            "pending price evaluations")
        .get(0);
  }

  /**
   * Whether an evaluation that covers the variant is due and not yet done: until it is, the ledger
   * may not show a price applied since.
   *
   * @param tenantId owning tenant; the first condition of the query
   */
  public boolean pendingFor(UUID tenantId, UUID variantId) {
    return query(
            "SELECT EXISTS (SELECT 1 FROM price_evaluations WHERE tenant_id = ?"
                + " AND (variant_id = ? OR variant_id IS NULL) AND as_of <= clock_timestamp())",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
            },
            rs -> rs.getBoolean(1),
            "price evaluation pending")
        .get(0);
  }

  /**
   * Which of a tenant's variants have evaluations due and not yet done.
   *
   * @param tenantId owning tenant; the first condition of the query
   */
  public Pending pendingVariants(UUID tenantId) {
    List<Optional<UUID>> rows =
        query(
            "SELECT DISTINCT variant_id FROM price_evaluations WHERE tenant_id = ?"
                + " AND as_of <= clock_timestamp()",
            ps -> ps.setObject(1, tenantId),
            rs -> Optional.ofNullable(rs.getObject(1, UUID.class)),
            "pending price evaluation variants");
    Set<UUID> variants = new HashSet<>();
    boolean every = false;
    for (Optional<UUID> row : rows) {
      if (row.isEmpty()) every = true;
      else variants.add(row.get());
    }
    return new Pending(every, Set.copyOf(variants));
  }

  /**
   * Whether something an evaluation cannot read as of an earlier moment was overwritten for the
   * variant after {@code asOf}: that change's own evaluation is queued, so what the earlier moment
   * offered cannot be known. Asked after the offer is computed, so a change that landed while it
   * was computed is seen.
   *
   * @param tenantId owning tenant; the first condition of the query
   */
  public boolean overwrittenSince(UUID tenantId, UUID variantId, Instant asOf) {
    return query(
            "SELECT EXISTS (SELECT 1 FROM price_evaluations WHERE tenant_id = ?"
                + " AND (variant_id = ? OR variant_id IS NULL) AND overwrites"
                + " AND as_of > ? AND as_of <= clock_timestamp() AND enqueued_at > ?)",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, variantId);
              ps.setObject(3, utc(asOf));
              ps.setObject(4, utc(asOf));
            },
            rs -> rs.getBoolean(1),
            "overwritten since")
        .get(0);
  }

  // ── the ledger ──────────────────────────────────────────────────────────────

  /**
   * Records what an evaluation found for one key, under a lock on the key so two evaluations of one
   * variant cannot interleave. A row is appended when the offer changed; when the finding is
   * uncertain and no uncertain span on this offer is open already; and when a certain finding
   * closes an open uncertain span. A row never starts before the one it follows. An evaluation
   * older than one already worked for the key is recorded as uncertain from its own moment.
   */
  public Outcome recordIfChanged(AppliedPrice row) {
    return inTx(
        c -> {
          try (PreparedStatement lock =
              c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            lock.setString(
                1,
                row.tenantId() + "|" + row.variantId() + "|" + row.channel() + "|" + row.storeId());
            lock.execute();
          }
          Instant mark = mark(c, row);
          boolean outOfOrder = mark != null && mark.isAfter(row.appliedFrom());
          Instant uncertainSince = outOfOrder ? row.appliedFrom() : row.uncertainSince();
          Optional<AppliedPrice> latest = latest(c, row);
          boolean append = latest.isEmpty() || appends(latest.get(), row, uncertainSince);
          if (append) {
            Instant from =
                latest.isPresent() && latest.get().appliedFrom().isAfter(row.appliedFrom())
                    ? latest.get().appliedFrom()
                    : row.appliedFrom();
            insert(c, row, from, uncertainSince);
          }
          advanceMark(c, row, mark);
          if (outOfOrder) return Outcome.OUT_OF_ORDER;
          return append ? Outcome.APPENDED : Outcome.UNCHANGED;
        },
        "record applied price");
  }

  static boolean appends(AppliedPrice latest, AppliedPrice row, Instant uncertainSince) {
    boolean same = sameOffer(latest, row);
    if (uncertainSince != null) {
      return !same
          || latest.uncertainSince() == null
          || uncertainSince.isBefore(latest.uncertainSince());
    }
    return !same || latest.uncertainSince() != null;
  }

  private static void insert(Connection c, AppliedPrice row, Instant from, Instant uncertainSince)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO applied_prices ("
                + LEDGER_COLUMNS
                + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,now(),?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, row.tenantId());
      ps.setObject(3, row.variantId());
      ps.setString(4, row.channel());
      ps.setObject(5, row.storeId());
      ps.setBoolean(6, row.priced());
      ps.setBigDecimal(7, row.price());
      ps.setBigDecimal(8, row.netPrice());
      ps.setBigDecimal(9, row.regularPrice());
      ps.setString(10, row.promotionName());
      ps.setString(11, row.currency());
      ps.setObject(12, utc(from));
      ps.setObject(13, uncertainSince == null ? null : utc(uncertainSince));
      ps.setString(14, row.cause());
      ps.executeUpdate();
    }
  }

  private static String keyWhere(AppliedPrice row) {
    return " WHERE tenant_id = ? AND variant_id = ? AND channel = ?"
        + (row.storeId() == null ? " AND store_id IS NULL" : " AND store_id = ?");
  }

  private static int bindKey(PreparedStatement ps, AppliedPrice row, int from) throws SQLException {
    int i = from;
    ps.setObject(i++, row.tenantId());
    ps.setObject(i++, row.variantId());
    ps.setString(i++, row.channel());
    if (row.storeId() != null) ps.setObject(i++, row.storeId());
    return i;
  }

  private static Instant mark(Connection c, AppliedPrice row) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT evaluated_through FROM applied_price_marks" + keyWhere(row) + " LIMIT 1")) {
      bindKey(ps, row, 1);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getObject(1, OffsetDateTime.class).toInstant() : null;
      }
    }
  }

  private static void advanceMark(Connection c, AppliedPrice row, Instant mark)
      throws SQLException {
    if (mark == null) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO applied_price_marks"
                  + " (id, tenant_id, variant_id, channel, store_id, evaluated_through)"
                  + " VALUES (?, ?, ?, ?, ?, ?)")) {
        ps.setObject(1, Ids.newId());
        ps.setObject(2, row.tenantId());
        ps.setObject(3, row.variantId());
        ps.setString(4, row.channel());
        ps.setObject(5, row.storeId());
        ps.setObject(6, utc(row.appliedFrom()));
        ps.executeUpdate();
      }
    } else if (row.appliedFrom().isAfter(mark)) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE applied_price_marks SET evaluated_through = ?" + keyWhere(row))) {
        ps.setObject(1, utc(row.appliedFrom()));
        bindKey(ps, row, 2);
        ps.executeUpdate();
      }
    }
  }

  /**
   * The rows for one variant, channel and store that were in force at any time since {@code since}:
   * those applied since, and the last one applied before, oldest first.
   *
   * @param tenantId owning tenant; the first condition of the query
   */
  public List<AppliedPrice> ledger(
      UUID tenantId, UUID variantId, String channel, UUID storeId, Instant since) {
    String key =
        " WHERE tenant_id = ? AND variant_id = ? AND channel = ?"
            + (storeId == null ? " AND store_id IS NULL" : " AND store_id = ?");
    return query(
        "SELECT "
            + LEDGER_COLUMNS
            + " FROM ("
            + "(SELECT "
            + LEDGER_COLUMNS
            + " FROM applied_prices"
            + key
            + " AND applied_from < ?"
            + " ORDER BY applied_from DESC, id DESC LIMIT 1)"
            + " UNION ALL "
            + "(SELECT "
            + LEDGER_COLUMNS
            + " FROM applied_prices"
            + key
            + " AND applied_from >= ?"
            + " ORDER BY applied_from, id LIMIT 5000)"
            + ") rows ORDER BY applied_from, id",
        ps -> {
          int i = 1;
          for (int part = 0; part < 2; part++) {
            ps.setObject(i++, tenantId);
            ps.setObject(i++, variantId);
            ps.setString(i++, channel);
            if (storeId != null) ps.setObject(i++, storeId);
            ps.setObject(i++, utc(since));
          }
        },
        AppliedPriceRepository::map,
        "applied price ledger");
  }

  /**
   * The latest row of every key on a channel whose offer is below its regular price: the reductions
   * on offer now, as far as the ledger has recorded.
   *
   * @param tenantId owning tenant; the first condition of the query
   */
  public List<AppliedPrice> currentReductions(UUID tenantId, String channel, int limit) {
    return query(
        "SELECT "
            + LEDGER_COLUMNS
            + " FROM (SELECT DISTINCT ON (variant_id, store_id) "
            + LEDGER_COLUMNS
            + " FROM applied_prices WHERE tenant_id = ? AND channel = ?"
            + " ORDER BY variant_id, store_id, applied_from DESC, id DESC) latest"
            + " WHERE priced AND price < regular_price"
            + " ORDER BY variant_id, store_id LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, channel);
          ps.setInt(3, limit);
        },
        AppliedPriceRepository::map,
        "current reductions");
  }

  /**
   * When the tenant's ledger last had a row appended; null before its first.
   *
   * @param tenantId owning tenant; the first condition of the query
   */
  public Instant lastRecorded(UUID tenantId) {
    return query(
            "SELECT max(recorded_at) FROM applied_prices WHERE tenant_id = ?",
            ps -> ps.setObject(1, tenantId),
            rs -> Optional.ofNullable(rs.getObject(1, OffsetDateTime.class)),
            "ledger last recorded")
        .get(0)
        .map(OffsetDateTime::toInstant)
        .orElse(null);
  }

  /**
   * The latest rows for one variant, newest first, for the admin history.
   *
   * @param tenantId owning tenant; the first condition of the query
   */
  public List<AppliedPrice> history(UUID tenantId, UUID variantId, int limit) {
    return query(
        "SELECT "
            + LEDGER_COLUMNS
            + " FROM applied_prices WHERE tenant_id = ? AND variant_id = ?"
            + " ORDER BY applied_from DESC, id DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, variantId);
          ps.setInt(3, limit);
        },
        AppliedPriceRepository::map,
        "applied price history");
  }

  /**
   * Every variant with a price on any list, for an evaluation of the whole tenant.
   *
   * @param tenantId owning tenant; the first condition of the query
   */
  public List<UUID> pricedVariants(UUID tenantId) {
    return query(
        "SELECT DISTINCT variant_id FROM price_list_items WHERE tenant_id = ? ORDER BY variant_id",
        ps -> ps.setObject(1, tenantId),
        rs -> rs.getObject(1, UUID.class),
        "priced variants");
  }

  /**
   * The stores a promotion of the tenant is scoped to: the only places an offer can differ by
   * store.
   *
   * @param tenantId owning tenant; the first condition of the query
   */
  public List<UUID> promotionStores(UUID tenantId) {
    return query(
        "SELECT DISTINCT store_id FROM promotions WHERE tenant_id = ? AND store_id IS NOT NULL"
            + " ORDER BY store_id",
        ps -> ps.setObject(1, tenantId),
        rs -> rs.getObject(1, UUID.class),
        "promotion stores");
  }

  /** Tenants with prices and no ledger yet: the ones whose history starts now. */
  public List<UUID> tenantsWithoutLedger() {
    return query(
        "SELECT DISTINCT pli.tenant_id FROM price_list_items pli WHERE NOT EXISTS"
            + " (SELECT 1 FROM applied_prices a WHERE a.tenant_id = pli.tenant_id)"
            + " AND NOT EXISTS (SELECT 1 FROM price_evaluations e WHERE e.tenant_id = pli.tenant_id)",
        ps -> {},
        rs -> rs.getObject(1, UUID.class),
        "tenants without a ledger");
  }

  private static Optional<AppliedPrice> latest(Connection c, AppliedPrice row) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT "
                + LEDGER_COLUMNS
                + " FROM applied_prices"
                + keyWhere(row)
                + " ORDER BY applied_from DESC, id DESC LIMIT 1")) {
      bindKey(ps, row, 1);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    }
  }

  static boolean sameOffer(AppliedPrice a, AppliedPrice b) {
    if (a.priced() != b.priced()) return false;
    if (!a.priced()) return true;
    return a.price().compareTo(b.price()) == 0
        && a.regularPrice().compareTo(b.regularPrice()) == 0
        && a.netPrice().compareTo(b.netPrice()) == 0
        && Objects.equals(a.promotionName(), b.promotionName())
        && Objects.equals(a.currency(), b.currency());
  }

  private static AppliedPrice map(ResultSet rs) throws SQLException {
    OffsetDateTime uncertain = rs.getObject("uncertain_since", OffsetDateTime.class);
    return new AppliedPrice(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("channel"),
        rs.getObject("store_id", UUID.class),
        rs.getBoolean("priced"),
        rs.getBigDecimal("price"),
        rs.getBigDecimal("net_price"),
        rs.getBigDecimal("regular_price"),
        rs.getString("promotion_name"),
        rs.getString("currency"),
        rs.getObject("applied_from", OffsetDateTime.class).toInstant(),
        uncertain == null ? null : uncertain.toInstant(),
        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
        rs.getString("cause"));
  }

  private static OffsetDateTime utc(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
