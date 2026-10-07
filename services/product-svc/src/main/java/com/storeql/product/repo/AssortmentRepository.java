package com.storeql.product.repo;

import com.storeql.ids.Ids;
import com.storeql.product.domain.Assortment;
import com.storeql.product.domain.Assortment.Change;
import com.storeql.product.domain.Assortment.Cluster;
import com.storeql.product.domain.Assortment.Line;
import com.storeql.product.domain.Assortment.Review;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Store clusters, dated assortment changes, and range reviews (07.18).
 *
 * <p>{@code product_stores} is not replaced: it stays the range as it stands, which is what the
 * till and the storefront ask. This is the decision log in front of it, and {@link #apply} is the
 * one place the two meet — the log is the intent, {@code product_stores} is the state, and applying
 * is a step with a date rather than a side effect of typing.
 */
@ApplicationScoped
public class AssortmentRepository extends BaseOutboxRepository {

  // ── clusters ────────────────────────────────────────────────────────────────

  private static final String CLUSTER_COLUMNS =
      "SELECT id, tenant_id, code, name, note, status, created_at, updated_at FROM store_clusters";

  public Cluster addCluster(Cluster c, UUID actorId) {
    return inTx(
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO store_clusters (id, tenant_id, code, name, note, status, created_at,"
                      + " created_by, updated_at) VALUES (?,?,?,?,?,'ACTIVE',?,?,?)")) {
            ps.setObject(1, c.id());
            ps.setObject(2, c.tenantId());
            ps.setString(3, c.code());
            ps.setString(4, c.name());
            ps.setString(5, c.note());
            ps.setObject(6, c.createdAt().atOffset(ZoneOffset.UTC));
            ps.setObject(7, actorId);
            ps.setObject(8, c.createdAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          return c;
        },
        "add a store cluster");
  }

  /** Adds stores to a cluster. Already-present stores are left alone rather than raising. */
  public void addMembers(UUID tenantId, UUID clusterId, List<UUID> storeIds) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO store_cluster_members (cluster_id, store_id, tenant_id, added_at)"
                      + " VALUES (?,?,?,?) ON CONFLICT (cluster_id, store_id) DO NOTHING")) {
            for (UUID storeId : storeIds) {
              ps.setObject(1, clusterId);
              ps.setObject(2, storeId);
              ps.setObject(3, tenantId);
              ps.setObject(4, Instant.now().atOffset(ZoneOffset.UTC));
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return null;
        },
        "add stores to a cluster");
  }

  public List<Cluster> clusters(UUID tenantId) {
    return query(
            CLUSTER_COLUMNS + " WHERE tenant_id = ? ORDER BY code",
            ps -> ps.setObject(1, tenantId),
            AssortmentRepository::readCluster,
            "store clusters")
        .stream()
        .map(c -> withMembers(tenantId, c))
        .toList();
  }

  public Optional<Cluster> cluster(UUID tenantId, UUID id) {
    return query(
            CLUSTER_COLUMNS + " WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            AssortmentRepository::readCluster,
            "a store cluster")
        .stream()
        .findFirst()
        .map(c -> withMembers(tenantId, c));
  }

  private Cluster withMembers(UUID tenantId, Cluster c) {
    List<UUID> stores =
        query(
            "SELECT store_id FROM store_cluster_members WHERE tenant_id = ? AND cluster_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, c.id());
            },
            rs -> rs.getObject("store_id", UUID.class),
            "a cluster's stores");
    return new Cluster(
        c.id(),
        c.tenantId(),
        c.code(),
        c.name(),
        c.note(),
        c.status(),
        c.createdAt(),
        c.updatedAt(),
        stores);
  }

  // ── changes ─────────────────────────────────────────────────────────────────

  /** One statement for both writers: a single decision, and the batch a closing review produces. */
  private static final String INSERT_CHANGE =
      "INSERT INTO assortment_changes (id, tenant_id, product_id, store_id, cluster_id, action,"
          + " effective_from, reason, decided_by, created_at, review_id, held_to_stores)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";

  private static void bindChange(PreparedStatement ps, Change ch) throws SQLException {
    ps.setObject(1, ch.id());
    ps.setObject(2, ch.tenantId());
    ps.setObject(3, ch.productId());
    ps.setObject(4, ch.storeId());
    ps.setObject(5, ch.clusterId());
    ps.setString(6, ch.action());
    ps.setObject(7, ch.effectiveFrom());
    ps.setString(8, ch.reason());
    ps.setObject(9, ch.decidedBy());
    ps.setObject(10, ch.createdAt().atOffset(ZoneOffset.UTC));
    ps.setObject(11, ch.reviewId());
    ps.setBoolean(12, ch.heldToStores());
  }

  private static final String CHANGE_COLUMNS =
      "SELECT id, tenant_id, product_id, store_id, cluster_id, action, effective_from, reason,"
          + " decided_by, created_at, applied_at, review_id, held_to_stores, refused_at,"
          + " refusal_code, refusal_detail FROM assortment_changes";

  /**
   * A change still open: neither applied nor closed as refused. The same predicate as the partial
   * index idx_assortment_changes_pending in V16__assortment_and_range_review.sql.
   */
  private static final String UNSETTLED = " applied_at IS NULL AND refused_at IS NULL";

  public Change record(Change ch) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(INSERT_CHANGE)) {
            bindChange(ps, ch);
            ps.executeUpdate();
          }
          return ch;
        },
        "record an assortment change");
  }

  /** A product's range history, newest decision first. */
  public List<Change> changesOf(UUID tenantId, UUID productId) {
    return query(
        CHANGE_COLUMNS
            + " WHERE tenant_id = ? AND product_id = ? ORDER BY effective_from DESC, created_at DESC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, productId);
        },
        AssortmentRepository::readChange,
        "a product's assortment changes");
  }

  /**
   * Everything dated on or before a day and still open (neither applied nor closed as refused),
   * oldest first so order is respected.
   */
  public List<Change> due(UUID tenantId, LocalDate asOf) {
    return query(
        CHANGE_COLUMNS
            + " WHERE tenant_id = ? AND"
            + UNSETTLED
            + " AND effective_from <= ?"
            + " ORDER BY effective_from, created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, asOf);
        },
        AssortmentRepository::readChange,
        "assortment changes due");
  }

  /**
   * Every open change of the lines named, whatever its day, in the sweep's order: the plan a new
   * change joins when it is recorded.
   *
   * @param productIds the lines
   * @return their changes neither applied nor closed, oldest first
   */
  public List<Change> pending(UUID tenantId, java.util.Collection<UUID> productIds) {
    if (productIds.isEmpty()) return List.of();
    return query(
        CHANGE_COLUMNS
            + " WHERE tenant_id = ? AND product_id = ANY(?) AND"
            + UNSETTLED
            + " ORDER BY effective_from, created_at",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setArray(
              2,
              ps.getConnection().createArrayOf("uuid", java.util.Set.copyOf(productIds).toArray()));
        },
        AssortmentRepository::readChange,
        "the open assortment changes of products");
  }

  /**
   * Every business with a change due on or before a day.
   *
   * <p>Deliberately not tenant-scoped: this is the sweeper's own question, asked once for the whole
   * deployment so a nightly run does not have to be told which businesses to look at. Each
   * business's changes are then read and applied under its own id, as every other read here is.
   */
  public List<UUID> tenantsWithDue(LocalDate asOf) {
    return query(
        "SELECT DISTINCT tenant_id FROM assortment_changes WHERE"
            + UNSETTLED
            + " AND effective_from <= ? ORDER BY tenant_id",
        ps -> ps.setObject(1, asOf),
        rs -> rs.getObject("tenant_id", UUID.class),
        "businesses with a range change due");
  }

  /**
   * What applying one change came to.
   *
   * @param applied true when this call put it into the range and marked it applied
   * @param refusal why it was not applied, when it was judged and refused; with {@link
   *     Assortment.Refusal#staysDue} false the change is now closed as refused. Both unset: it was
   *     already applied or closed when the product row was locked, and nothing was written
   */
  public record ApplyResult(boolean applied, Assortment.Refusal refusal) {

    public static final ApplyResult APPLIED = new ApplyResult(true, null);

    /** Already applied or closed by another sweep: nothing to do. */
    public static final ApplyResult SETTLED = new ApplyResult(false, null);

    public static ApplyResult refused(Assortment.Refusal refusal) {
      return new ApplyResult(false, refusal);
    }
  }

  /**
   * Applies one change to {@code product_stores} and marks it applied — one transaction — or, when
   * it is judged not to apply, leaves it due or closes it as refused.
   *
   * <p>Applied and marked together on purpose: a change applied without being marked would be
   * applied again on the next run, and a change marked without being applied would silently never
   * happen. Neither is recoverable by looking at the data afterwards, because both leave the log
   * and the state disagreeing with no way to tell which is right.
   *
   * <p>Decided on the range as it stands inside the same transaction, with the product row locked
   * as {@code ProductRepository.setStoresForProduct} locks it: a range read before it could be one
   * a PUT of the product's stores, or another change, had already replaced, and the change would be
   * judged on a range that is gone. Every write to a change's state (applied, closed) is made under
   * that lock, so the change is read again once it is held: one that another sweep settled
   * meanwhile is left alone rather than written twice.
   *
   * <p>A refusal that stays due writes nothing. One that does not ({@link
   * Assortment.Refusal#staysDue} false) closes the change as refused, with its code and sentence,
   * in the same transaction that judged it, so it is never due again.
   *
   * @param stores the stores the change resolves to — one, or a cluster's members, expanded by the
   *     caller because a cluster's membership is a fact about today and not about the day it was
   *     decided
   * @param judge sees the stores the product is sold at now (empty for every store) and says why
   *     the change is not applied, or nothing when it may be
   * @return what was done
   * @throws ApiException 404 {@code PRODUCT_NOT_FOUND} when the product is not the tenant's
   */
  public ApplyResult apply(
      UUID tenantId,
      Change ch,
      List<UUID> stores,
      java.util.function.Function<List<UUID>, Optional<Assortment.Refusal>> judge) {
    return inTx(
        c -> {
          List<UUID> current = lockedRangeTx(c, tenantId, ch.productId());
          if (!stillOpenTx(c, tenantId, ch.id())) return ApplyResult.SETTLED;
          Optional<Assortment.Refusal> refusal = judge.apply(current);
          if (refusal.isPresent()) {
            if (!refusal.get().staysDue()) closeTx(c, tenantId, ch.id(), refusal.get());
            return ApplyResult.refused(refusal.get());
          }
          if (Assortment.LIST.equals(ch.action())) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "INSERT INTO product_stores (id, tenant_id, product_id, store_id)"
                        + " VALUES (?,?,?,?) ON CONFLICT (tenant_id, product_id, store_id)"
                        + " DO NOTHING")) {
              for (UUID storeId : stores) {
                ps.setObject(1, Ids.newId());
                ps.setObject(2, tenantId);
                ps.setObject(3, ch.productId());
                ps.setObject(4, storeId);
                ps.addBatch();
              }
              ps.executeBatch();
            }
          } else {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "DELETE FROM product_stores WHERE tenant_id = ? AND product_id = ?"
                        + " AND store_id = ANY(?)")) {
              ps.setObject(1, tenantId);
              ps.setObject(2, ch.productId());
              ps.setArray(3, ps.getConnection().createArrayOf("uuid", stores.toArray()));
              ps.executeUpdate();
            }
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE assortment_changes SET applied_at = ?"
                      + " WHERE tenant_id = ? AND id = ? AND"
                      + UNSETTLED)) {
            ps.setObject(1, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(2, tenantId);
            ps.setObject(3, ch.id());
            return ps.executeUpdate() == 1 ? ApplyResult.APPLIED : ApplyResult.SETTLED;
          }
        },
        "apply an assortment change");
  }

  /** Whether a change is still open, read under the product lock its every settlement takes. */
  private static boolean stillOpenTx(java.sql.Connection c, UUID tenantId, UUID changeId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT 1 FROM assortment_changes WHERE tenant_id = ? AND id = ? AND" + UNSETTLED)) {
      ps.setObject(1, tenantId);
      ps.setObject(2, changeId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /** Closes a change as refused for good, with the refusal it was judged to have. */
  private static void closeTx(
      java.sql.Connection c, UUID tenantId, UUID changeId, Assortment.Refusal refusal)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE assortment_changes SET refused_at = ?, refusal_code = ?, refusal_detail = ?"
                + " WHERE tenant_id = ? AND id = ? AND"
                + UNSETTLED)) {
      ps.setObject(1, Instant.now().atOffset(ZoneOffset.UTC));
      ps.setString(2, refusal.code());
      ps.setString(3, refusal.detail());
      ps.setObject(4, tenantId);
      ps.setObject(5, changeId);
      ps.executeUpdate();
    }
  }

  /**
   * Locks the product row and reads the stores it is sold at, through the transaction's own
   * connection.
   *
   * @return the stores; empty for every store
   * @throws ApiException 404 {@code PRODUCT_NOT_FOUND}
   */
  private static List<UUID> lockedRangeTx(java.sql.Connection c, UUID tenantId, UUID productId)
      throws SQLException {
    // The same lock ProductRepository's range changes take (FOR NO KEY UPDATE): range changes to
    // one product are serialised, and a variant insert's foreign-key check is not blocked.
    try (PreparedStatement lock =
        c.prepareStatement(
            "SELECT id FROM products WHERE tenant_id = ? AND id = ? FOR NO KEY UPDATE")) {
      lock.setObject(1, tenantId);
      lock.setObject(2, productId);
      try (ResultSet rs = lock.executeQuery()) {
        if (!rs.next()) {
          throw ApiException.notFound("PRODUCT_NOT_FOUND", "No such product");
        }
      }
    }
    var stores = new java.util.ArrayList<UUID>();
    try (PreparedStatement read =
        c.prepareStatement(
            "SELECT store_id FROM product_stores WHERE tenant_id = ? AND product_id = ?"
                + " ORDER BY id")) {
      read.setObject(1, tenantId);
      read.setObject(2, productId);
      try (ResultSet rs = read.executeQuery()) {
        while (rs.next()) stores.add(rs.getObject("store_id", UUID.class));
      }
    }
    return stores;
  }

  /**
   * Where each of the business's products named is sold now, for judging a change when it is
   * recorded (it is judged again, on the range as it stands, when it is applied: {@link #apply}).
   *
   * @param productIds the products
   * @return each product of this tenant's, with the stores it is sold at (empty for every store); a
   *     product that is not the tenant's is absent
   */
  public Map<UUID, List<UUID>> rangesOf(UUID tenantId, java.util.Collection<UUID> productIds) {
    Map<UUID, List<UUID>> ranges = new java.util.LinkedHashMap<>();
    if (productIds.isEmpty()) return ranges;
    record Row(UUID productId, UUID storeId) {}
    for (Row r :
        query(
            "SELECT p.id AS product_id, ps.store_id FROM products p"
                + " LEFT JOIN product_stores ps"
                + " ON ps.tenant_id = p.tenant_id AND ps.product_id = p.id"
                + " WHERE p.tenant_id = ? AND p.id = ANY(?) ORDER BY p.id, ps.id",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setArray(
                  2,
                  ps.getConnection()
                      .createArrayOf("uuid", java.util.Set.copyOf(productIds).toArray()));
            },
            rs ->
                new Row(
                    rs.getObject("product_id", UUID.class), rs.getObject("store_id", UUID.class)),
            "the ranges of products")) {
      List<UUID> stores = ranges.computeIfAbsent(r.productId(), k -> new java.util.ArrayList<>());
      if (r.storeId() != null) stores.add(r.storeId());
    }
    ranges.replaceAll((k, v) -> List.copyOf(v));
    return ranges;
  }

  // ── reviews ─────────────────────────────────────────────────────────────────

  private static final String REVIEW_COLUMNS =
      "SELECT id, tenant_id, category_id, name, period_from, period_to, status, note, created_at,"
          + " decided_at FROM range_reviews";

  private static final String LINE_COLUMNS =
      "SELECT id, tenant_id, review_id, variant_id, units_sold, revenue, margin, currency,"
          + " rank_in_category, decision, decision_note, own_brand FROM range_review_lines";

  public Review openReview(Review r, UUID actorId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO range_reviews (id, tenant_id, category_id, name, period_from,"
                      + " period_to, status, note, created_at, created_by)"
                      + " VALUES (?,?,?,?,?,?,'OPEN',?,?,?)")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setObject(3, r.categoryId());
            ps.setString(4, r.name());
            ps.setObject(5, r.periodFrom());
            ps.setObject(6, r.periodTo());
            ps.setString(7, r.note());
            ps.setObject(8, r.createdAt().atOffset(ZoneOffset.UTC));
            ps.setObject(9, actorId);
            ps.executeUpdate();
          }
          return r;
        },
        "open a range review");
  }

  /**
   * Adds the lines under review, with the figures they are judged on.
   *
   * <p>One statement per line in a batch, and {@code ON CONFLICT} updates: a buyer re-running the
   * figures partway through a review should refresh them rather than be told the line is already
   * there.
   */
  public void addLines(UUID tenantId, UUID reviewId, List<Line> lines) {
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO range_review_lines (id, tenant_id, review_id, variant_id, units_sold,"
                      + " revenue, margin, currency, rank_in_category, own_brand)"
                      + " VALUES (?,?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (review_id, variant_id) DO UPDATE SET"
                      + " units_sold = EXCLUDED.units_sold, revenue = EXCLUDED.revenue,"
                      + " margin = EXCLUDED.margin, currency = EXCLUDED.currency,"
                      + " rank_in_category = EXCLUDED.rank_in_category,"
                      + " own_brand = EXCLUDED.own_brand")) {
            for (Line l : lines) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, tenantId);
              ps.setObject(3, reviewId);
              ps.setObject(4, l.variantId());
              ps.setBigDecimal(5, l.unitsSold());
              ps.setBigDecimal(6, l.revenue());
              ps.setBigDecimal(7, l.margin());
              ps.setString(8, l.currency());
              ps.setObject(9, l.rankInCategory(), java.sql.Types.INTEGER);
              ps.setBoolean(10, l.ownBrand());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return null;
        },
        "add lines to a range review");
  }

  public boolean decideLine(
      UUID tenantId, UUID reviewId, UUID variantId, String decision, String note) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE range_review_lines SET decision = ?, decision_note = ?"
                      + " WHERE tenant_id = ? AND review_id = ? AND variant_id = ?")) {
            ps.setString(1, decision);
            ps.setString(2, note);
            ps.setObject(3, tenantId);
            ps.setObject(4, reviewId);
            ps.setObject(5, variantId);
            return ps.executeUpdate() == 1;
          }
        },
        "decide a range review line");
  }

  /** Closes a review and records the changes its decisions produced — one transaction. */
  public boolean close(UUID tenantId, UUID reviewId, UUID actorId, List<Change> produced) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE range_reviews SET status = 'DECIDED', decided_at = ?, decided_by = ?"
                      + " WHERE tenant_id = ? AND id = ? AND status = 'OPEN'")) {
            ps.setObject(1, Instant.now().atOffset(ZoneOffset.UTC));
            ps.setObject(2, actorId);
            ps.setObject(3, tenantId);
            ps.setObject(4, reviewId);
            if (ps.executeUpdate() != 1) return false;
          }
          try (PreparedStatement ps = c.prepareStatement(INSERT_CHANGE)) {
            for (Change ch : produced) {
              bindChange(ps, ch);
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return true;
        },
        "close a range review");
  }

  public boolean abandon(UUID tenantId, UUID reviewId) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE range_reviews SET status = 'ABANDONED'"
                      + " WHERE tenant_id = ? AND id = ? AND status = 'OPEN'")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, reviewId);
            return ps.executeUpdate() == 1;
          }
        },
        "abandon a range review");
  }

  public Optional<Review> review(UUID tenantId, UUID id) {
    return query(
            REVIEW_COLUMNS + " WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            AssortmentRepository::readReview,
            "a range review")
        .stream()
        .findFirst()
        .map(r -> withLines(tenantId, r));
  }

  public List<Review> reviews(UUID tenantId) {
    return query(
        REVIEW_COLUMNS + " WHERE tenant_id = ? ORDER BY period_from DESC, created_at DESC",
        ps -> ps.setObject(1, tenantId),
        AssortmentRepository::readReview,
        "range reviews");
  }

  private Review withLines(UUID tenantId, Review r) {
    List<Line> lines =
        query(
            LINE_COLUMNS
                + " WHERE tenant_id = ? AND review_id = ?"
                + " ORDER BY rank_in_category NULLS LAST, variant_id",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, r.id());
            },
            AssortmentRepository::readLine,
            "a review's lines");
    return new Review(
        r.id(),
        r.tenantId(),
        r.categoryId(),
        r.name(),
        r.periodFrom(),
        r.periodTo(),
        r.status(),
        r.note(),
        r.createdAt(),
        r.decidedAt(),
        lines);
  }

  /** A variant and the product it belongs to, so a line's decision reaches the right range row. */
  public record VariantProduct(UUID variantId, UUID productId) {}

  /** The product each variant belongs to. Variants of other tenants are simply not returned. */
  public List<VariantProduct> productsOfVariants(UUID tenantId, List<UUID> variantIds) {
    if (variantIds.isEmpty()) return List.of();
    return query(
        "SELECT id, product_id FROM product_variants WHERE tenant_id = ? AND id = ANY(?)",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setArray(2, ps.getConnection().createArrayOf("uuid", variantIds.toArray()));
        },
        rs ->
            new VariantProduct(
                rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class)),
        "products of variants");
  }

  /**
   * How many live variants each product has, for the rule that a product is de-listed only when the
   * review covered all of them. Soft-deleted variants are left out: a line already gone should not
   * stand in the way of a de-list.
   */
  public Map<UUID, Integer> variantCounts(UUID tenantId, List<UUID> productIds) {
    if (productIds.isEmpty()) return Map.of();
    Map<UUID, Integer> counts = new HashMap<>();
    for (VariantProduct vp :
        query(
            "SELECT id, product_id FROM product_variants"
                + " WHERE tenant_id = ? AND status = 'ACTIVE' AND product_id = ANY(?)",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setArray(2, ps.getConnection().createArrayOf("uuid", productIds.toArray()));
            },
            rs ->
                new VariantProduct(
                    rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class)),
            "live variant counts")) {
      counts.merge(vp.productId(), 1, Integer::sum);
    }
    return counts;
  }

  // ── readers ─────────────────────────────────────────────────────────────────

  private static Cluster readCluster(ResultSet rs) throws SQLException {
    return new Cluster(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("note"),
        rs.getString("status"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        List.of());
  }

  private static Change readChange(ResultSet rs) throws SQLException {
    return new Change(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("product_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("cluster_id", UUID.class),
        rs.getString("action"),
        rs.getObject("effective_from", LocalDate.class),
        rs.getString("reason"),
        rs.getObject("decided_by", UUID.class),
        instant(rs, "created_at"),
        instant(rs, "applied_at"),
        rs.getObject("review_id", UUID.class),
        rs.getBoolean("held_to_stores"),
        instant(rs, "refused_at"),
        rs.getString("refusal_code"),
        rs.getString("refusal_detail"));
  }

  private static Review readReview(ResultSet rs) throws SQLException {
    return new Review(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("category_id", UUID.class),
        rs.getString("name"),
        rs.getObject("period_from", LocalDate.class),
        rs.getObject("period_to", LocalDate.class),
        rs.getString("status"),
        rs.getString("note"),
        instant(rs, "created_at"),
        instant(rs, "decided_at"),
        List.of());
  }

  private static Line readLine(ResultSet rs) throws SQLException {
    return new Line(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("review_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("units_sold"),
        rs.getBigDecimal("revenue"),
        rs.getBigDecimal("margin"),
        rs.getString("currency"),
        rs.getObject("rank_in_category", Integer.class),
        rs.getString("decision"),
        rs.getString("decision_note"),
        rs.getBoolean("own_brand"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }

  /** A duplicated cluster code is a business conflict, not a server fault. */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState())
        && e.getMessage() != null
        && e.getMessage().contains("uq_store_clusters_code")) {
      return ApiException.conflict(
          "CLUSTER_CODE_TAKEN", "Another active cluster already has that code");
    }
    return super.handleTxSqlException(what, e);
  }
}
