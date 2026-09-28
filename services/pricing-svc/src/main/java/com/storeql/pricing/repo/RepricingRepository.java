package com.storeql.pricing.repo;

import com.storeql.pricing.domain.Domain.CompetitorPrice;
import com.storeql.pricing.domain.Domain.PriceListItem;
import com.storeql.pricing.domain.Domain.PriceZone;
import com.storeql.pricing.domain.Domain.RepricingProposal;
import com.storeql.pricing.domain.Domain.RepricingRule;
import com.storeql.pricing.domain.Repricing;
import com.storeql.pricing.domain.Repricing.Observation;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Price zones, competitor price observations, repricing rules and their proposals (03.x). Every
 * statement filters by {@code tenant_id} first; the store a zone names is tenant-svc's and is
 * referenced, never joined.
 */
@ApplicationScoped
public class RepricingRepository extends BaseOutboxRepository {

  // ── Price zones ────────────────────────────────────────────────────────────

  public PriceZone createZone(PriceZone z) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO price_zones (id, tenant_id, name, description, created_at)"
                      + " VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, z.id());
            ps.setObject(2, z.tenantId());
            ps.setString(3, z.name());
            ps.setString(4, z.description());
            ps.setObject(5, odt(z.createdAt()));
            ps.executeUpdate();
          } catch (SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState())) {
              throw new ApiException(
                  409,
                  "PRICING_ZONE_NAME_EXISTS",
                  "Price zone '" + z.name() + "' already exists",
                  List.of(),
                  sqle);
            }
            throw sqle;
          }
          return z;
        },
        "create price zone");
  }

  private static final String ZONE_COLUMNS =
      "SELECT z.id, z.tenant_id, z.name, z.description, z.created_at,"
          + " COALESCE(ARRAY_AGG(s.store_id ORDER BY s.store_id) FILTER (WHERE s.store_id IS NOT"
          + " NULL), '{}') AS store_ids"
          + " FROM price_zones z LEFT JOIN price_zone_stores s"
          + " ON s.tenant_id = z.tenant_id AND s.zone_id = z.id"
          + " WHERE z.tenant_id = ?";

  public List<PriceZone> findZones(UUID tenantId) {
    return query(
        ZONE_COLUMNS + " GROUP BY z.id ORDER BY z.created_at, z.id",
        ps -> ps.setObject(1, tenantId),
        RepricingRepository::mapZone,
        "list price zones");
  }

  public Optional<PriceZone> findZone(UUID tenantId, UUID id) {
    var rows =
        query(
            ZONE_COLUMNS + " AND z.id = ? GROUP BY z.id",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            RepricingRepository::mapZone,
            "find price zone");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Replaces a zone's stores. A store is in one zone at most, so a store named here leaves any
   * other zone; the applied-price ledger is told, since the price at that store may have changed.
   */
  public List<UUID> assignStores(UUID tenantId, UUID zoneId, Collection<UUID> storeIds) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "DELETE FROM price_zone_stores WHERE tenant_id = ? AND zone_id = ?")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, zoneId);
            ps.executeUpdate();
          }
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO price_zone_stores (tenant_id, zone_id, store_id) VALUES (?, ?, ?)"
                      + " ON CONFLICT (tenant_id, store_id) DO UPDATE SET zone_id ="
                      + " EXCLUDED.zone_id")) {
            for (UUID storeId : storeIds) {
              ps.setObject(1, tenantId);
              ps.setObject(2, zoneId);
              ps.setObject(3, storeId);
              ps.addBatch();
            }
            ps.executeBatch();
          }
          AppliedPriceRepository.enqueue(c, tenantId, null, null, "PRICE_ZONE_STORES_CHANGED");
          return new ArrayList<>(storeIds);
        },
        "assign price zone stores");
  }

  private static PriceZone mapZone(ResultSet rs) throws SQLException {
    UUID[] stores = (UUID[]) rs.getArray("store_ids").getArray();
    return new PriceZone(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getString("description"),
        Arrays.asList(stores),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Competitor prices ──────────────────────────────────────────────────────

  public List<CompetitorPrice> recordCompetitorPrices(List<CompetitorPrice> seen) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO competitor_prices (id, tenant_id, variant_id, competitor, price,"
                      + " currency, zone_id, observed_on, source, recorded_by, recorded_at)"
                      + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (CompetitorPrice cp : seen) {
              ps.setObject(1, cp.id());
              ps.setObject(2, cp.tenantId());
              ps.setObject(3, cp.variantId());
              ps.setString(4, cp.competitor());
              ps.setBigDecimal(5, cp.price());
              ps.setString(6, cp.currency());
              ps.setObject(7, cp.zoneId());
              ps.setObject(8, cp.observedOn());
              ps.setString(9, cp.source());
              ps.setObject(10, cp.recordedBy());
              ps.setObject(11, odt(cp.recordedAt()));
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return seen;
        },
        "record competitor prices");
  }

  public List<CompetitorPrice> findCompetitorPrices(
      UUID tenantId, UUID variantId, UUID zoneId, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, tenant_id, variant_id, competitor, price, currency, zone_id, observed_on,"
                + " source, recorded_by, recorded_at FROM competitor_prices WHERE tenant_id = ?");
    if (variantId != null) sql.append(" AND variant_id = ?");
    if (zoneId != null) sql.append(" AND zone_id = ?");
    sql.append(" ORDER BY observed_on DESC, recorded_at DESC, id DESC LIMIT ?");
    return query(
        sql.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          if (variantId != null) ps.setObject(i++, variantId);
          if (zoneId != null) ps.setObject(i++, zoneId);
          ps.setInt(i, limit);
        },
        RepricingRepository::mapCompetitorPrice,
        "list competitor prices");
  }

  /**
   * The observations a rule on a list may answer, by variant: those seen in the list's zone or
   * everywhere for a zoned list, those seen everywhere for a tenant-wide one, none older than
   * {@code oldest}.
   */
  public Map<UUID, List<Observation>> observationsFor(
      UUID tenantId, UUID zoneId, LocalDate oldest) {
    Map<UUID, List<Observation>> out = new HashMap<>();
    query(
        "SELECT variant_id, competitor, price, observed_on FROM competitor_prices"
            + " WHERE tenant_id = ? AND observed_on >= ?"
            + (zoneId == null ? " AND zone_id IS NULL" : " AND (zone_id IS NULL OR zone_id = ?)")
            + " ORDER BY variant_id, observed_on",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, oldest);
          if (zoneId != null) ps.setObject(3, zoneId);
        },
        rs -> {
          out.computeIfAbsent(rs.getObject("variant_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new Observation(
                      rs.getString("competitor"),
                      rs.getBigDecimal("price"),
                      rs.getObject("observed_on", LocalDate.class)));
          return Boolean.TRUE;
        },
        "read competitor observations");
    return out;
  }

  /** Each variant's single-unit price on a list: the item with the lowest minimum quantity. */
  public Map<UUID, BigDecimal> currentPrices(UUID tenantId, UUID priceListId) {
    Map<UUID, BigDecimal> out = new LinkedHashMap<>();
    query(
        "SELECT DISTINCT ON (variant_id) variant_id, price FROM price_list_items"
            + " WHERE tenant_id = ? AND price_list_id = ? ORDER BY variant_id, min_qty ASC",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, priceListId);
        },
        rs -> {
          out.put(rs.getObject("variant_id", UUID.class), rs.getBigDecimal("price"));
          return Boolean.TRUE;
        },
        "read list prices");
    return out;
  }

  private static CompetitorPrice mapCompetitorPrice(ResultSet rs) throws SQLException {
    OffsetDateTime recordedAt = rs.getObject("recorded_at", OffsetDateTime.class);
    return new CompetitorPrice(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getString("competitor"),
        rs.getBigDecimal("price"),
        rs.getString("currency"),
        rs.getObject("zone_id", UUID.class),
        rs.getObject("observed_on", LocalDate.class),
        rs.getString("source"),
        rs.getObject("recorded_by", UUID.class),
        recordedAt == null ? null : recordedAt.toInstant());
  }

  // ── Repricing rules ────────────────────────────────────────────────────────

  public RepricingRule createRule(RepricingRule r) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO repricing_rules (id, tenant_id, name, price_list_id, strategy,"
                      + " value, floor_percent, rounding, max_age_days, active, created_at)"
                      + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, r.id());
            ps.setObject(2, r.tenantId());
            ps.setString(3, r.name());
            ps.setObject(4, r.priceListId());
            ps.setString(5, r.rule().strategy().name());
            ps.setBigDecimal(6, r.rule().value());
            ps.setBigDecimal(7, r.rule().floorPercent());
            ps.setString(8, r.rule().rounding().name());
            ps.setInt(9, r.rule().maxAgeDays());
            ps.setBoolean(10, r.active());
            ps.setObject(11, odt(r.createdAt()));
            ps.executeUpdate();
          } catch (SQLException sqle) {
            if (UNIQUE_VIOLATION.equals(sqle.getSQLState())) {
              throw new ApiException(
                  409,
                  "REPRICING_RULE_NAME_EXISTS",
                  "Repricing rule '" + r.name() + "' already exists",
                  List.of(),
                  sqle);
            }
            throw sqle;
          }
          return r;
        },
        "create repricing rule");
  }

  private static final String RULE_COLUMNS =
      "SELECT r.id, r.tenant_id, r.name, r.price_list_id, pl.zone_id, r.strategy, r.value,"
          + " r.floor_percent, r.rounding, r.max_age_days, r.active, r.created_at"
          + " FROM repricing_rules r JOIN price_lists pl"
          + " ON pl.tenant_id = r.tenant_id AND pl.id = r.price_list_id"
          + " WHERE r.tenant_id = ?";

  public List<RepricingRule> findRules(UUID tenantId) {
    return query(
        RULE_COLUMNS + " ORDER BY r.created_at, r.id",
        ps -> ps.setObject(1, tenantId),
        RepricingRepository::mapRule,
        "list repricing rules");
  }

  public Optional<RepricingRule> findRule(UUID tenantId, UUID id) {
    var rows =
        query(
            RULE_COLUMNS + " AND r.id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            RepricingRepository::mapRule,
            "find repricing rule");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private static RepricingRule mapRule(ResultSet rs) throws SQLException {
    return new RepricingRule(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getObject("price_list_id", UUID.class),
        rs.getObject("zone_id", UUID.class),
        new Repricing.Rule(
            Repricing.Strategy.valueOf(rs.getString("strategy")),
            rs.getBigDecimal("value"),
            rs.getBigDecimal("floor_percent"),
            Repricing.Rounding.valueOf(rs.getString("rounding")),
            rs.getInt("max_age_days")),
        rs.getBoolean("active"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  // ── Proposals ──────────────────────────────────────────────────────────────

  /** Opens a proposal, or refreshes the rule's open proposal for the variant. */
  public RepricingProposal upsertOpenProposal(RepricingProposal p) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO repricing_proposals (id, tenant_id, rule_id, price_list_id, zone_id,"
                      + " variant_id, current_price, competitor, competitor_price, observed_on,"
                      + " proposed_price, currency, status, proposed_at)"
                      + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROPOSED', ?)"
                      + " ON CONFLICT (tenant_id, rule_id, variant_id) WHERE status = 'PROPOSED'"
                      + " DO UPDATE SET current_price = EXCLUDED.current_price,"
                      + " competitor = EXCLUDED.competitor,"
                      + " competitor_price = EXCLUDED.competitor_price,"
                      + " observed_on = EXCLUDED.observed_on,"
                      + " proposed_price = EXCLUDED.proposed_price,"
                      + " proposed_at = EXCLUDED.proposed_at"
                      + " RETURNING id")) {
            ps.setObject(1, p.id());
            ps.setObject(2, p.tenantId());
            ps.setObject(3, p.ruleId());
            ps.setObject(4, p.priceListId());
            ps.setObject(5, p.zoneId());
            ps.setObject(6, p.variantId());
            ps.setBigDecimal(7, p.currentPrice());
            ps.setString(8, p.competitor());
            ps.setBigDecimal(9, p.competitorPrice());
            ps.setObject(10, p.observedOn());
            ps.setBigDecimal(11, p.proposedPrice());
            ps.setString(12, p.currency());
            ps.setObject(13, odt(p.proposedAt()));
            try (var rs = ps.executeQuery()) {
              UUID id = rs.next() ? rs.getObject("id", UUID.class) : p.id();
              return withId(p, id);
            }
          }
        },
        "open repricing proposal");
  }

  /** Withdraws a rule's open proposals for variants a run no longer proposes anything for. */
  public int withdrawOpenProposalsExcept(UUID tenantId, UUID ruleId, Collection<UUID> keep) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE repricing_proposals SET status = 'DISMISSED', decided_at = now()"
                      + " WHERE tenant_id = ? AND rule_id = ? AND status = 'PROPOSED'"
                      + " AND NOT (variant_id = ANY (?))")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, ruleId);
            ps.setArray(3, c.createArrayOf("uuid", keep.toArray()));
            return ps.executeUpdate();
          }
        },
        "withdraw repricing proposals");
  }

  private static final String PROPOSAL_COLUMNS =
      "SELECT id, tenant_id, rule_id, price_list_id, zone_id, variant_id, current_price,"
          + " competitor, competitor_price, observed_on, proposed_price, currency, status,"
          + " proposed_at, decided_at, decided_by FROM repricing_proposals WHERE tenant_id = ?";

  public List<RepricingProposal> findProposals(UUID tenantId, String status, int limit) {
    return query(
        PROPOSAL_COLUMNS + " AND status = ? ORDER BY proposed_at DESC, id DESC LIMIT ?",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setString(2, status);
          ps.setInt(3, limit);
        },
        RepricingRepository::mapProposal,
        "list repricing proposals");
  }

  public Optional<RepricingProposal> findProposal(UUID tenantId, UUID id) {
    var rows =
        query(
            PROPOSAL_COLUMNS + " AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            RepricingRepository::mapProposal,
            "find repricing proposal");
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * Decides an open proposal. Applying it also writes the price into the list, on the same
   * transaction, with the price-changed event the item write owes; a proposal already decided is
   * refused, so two managers cannot apply it twice.
   */
  public RepricingProposal decide(
      RepricingProposal p, String status, UUID decidedBy, PriceListItem item, OutboxRow event) {
    return inTx(
        c -> {
          int n;
          try (var ps =
              c.prepareStatement(
                  "UPDATE repricing_proposals SET status = ?, decided_at = now(), decided_by = ?"
                      + " WHERE tenant_id = ? AND id = ? AND status = 'PROPOSED'")) {
            ps.setString(1, status);
            ps.setObject(2, decidedBy);
            ps.setObject(3, p.tenantId());
            ps.setObject(4, p.id());
            n = ps.executeUpdate();
          }
          if (n == 0) {
            throw ApiException.conflict(
                "REPRICING_PROPOSAL_DECIDED", "proposal " + p.id() + " was already decided");
          }
          if (item != null) {
            PricingRepository.writePriceListItemTx(c, item);
            insertOutbox(c, event);
          }
          return decided(p, status, decidedBy);
        },
        "decide repricing proposal");
  }

  private static RepricingProposal mapProposal(ResultSet rs) throws SQLException {
    OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
    return new RepricingProposal(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("rule_id", UUID.class),
        rs.getObject("price_list_id", UUID.class),
        rs.getObject("zone_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getBigDecimal("current_price"),
        rs.getString("competitor"),
        rs.getBigDecimal("competitor_price"),
        rs.getObject("observed_on", LocalDate.class),
        rs.getBigDecimal("proposed_price"),
        rs.getString("currency"),
        rs.getString("status"),
        rs.getObject("proposed_at", OffsetDateTime.class).toInstant(),
        decidedAt == null ? null : decidedAt.toInstant(),
        rs.getObject("decided_by", UUID.class));
  }

  private static RepricingProposal withId(RepricingProposal p, UUID id) {
    return new RepricingProposal(
        id,
        p.tenantId(),
        p.ruleId(),
        p.priceListId(),
        p.zoneId(),
        p.variantId(),
        p.currentPrice(),
        p.competitor(),
        p.competitorPrice(),
        p.observedOn(),
        p.proposedPrice(),
        p.currency(),
        p.status(),
        p.proposedAt(),
        p.decidedAt(),
        p.decidedBy());
  }

  private static RepricingProposal decided(RepricingProposal p, String status, UUID by) {
    return new RepricingProposal(
        p.id(),
        p.tenantId(),
        p.ruleId(),
        p.priceListId(),
        p.zoneId(),
        p.variantId(),
        p.currentPrice(),
        p.competitor(),
        p.competitorPrice(),
        p.observedOn(),
        p.proposedPrice(),
        p.currency(),
        status,
        p.proposedAt(),
        Instant.now(),
        by);
  }

  private static OffsetDateTime odt(Instant at) {
    return at == null ? null : at.atOffset(java.time.ZoneOffset.UTC);
  }
}
