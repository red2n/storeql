package com.storeql.inventory.repo;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.Batch;
import com.storeql.inventory.domain.Domain.MoveType;
import com.storeql.inventory.domain.Domain.MovementAttribution;
import com.storeql.inventory.domain.Domain.YieldOutputSpec;
import com.storeql.inventory.domain.Domain.YieldRun;
import com.storeql.inventory.domain.Domain.YieldRunOutput;
import com.storeql.inventory.domain.Domain.YieldTemplate;
import com.storeql.inventory.domain.Provenance.Drawn;
import com.storeql.inventory.domain.Yield;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Yield templates and the breakdowns made from them. A breakdown is one transaction: the primal
 * drawn (a {@code YIELD} movement out of its batches), a batch per cut under the primal's lot with
 * its apportioned cost (a {@code YIELD} movement in, a TRANSFORM link in the genealogy), the run
 * recorded, and the events announced. Every statement filters by {@code tenant_id} first.
 */
@ApplicationScoped
public class YieldRepository extends BaseOutboxRepository {

  @Inject InventoryRepository inventory;

  // ── Templates ──────────────────────────────────────────────────────────────

  public YieldTemplate create(YieldTemplate t) {
    return inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO yield_templates (id, tenant_id, name, input_variant_id, unit, notes,"
                      + " active, created_by, created_at) VALUES (?,?,?,?,?,?,TRUE,?,?)")) {
            ps.setObject(1, t.id());
            ps.setObject(2, t.tenantId());
            ps.setString(3, t.name());
            ps.setObject(4, t.inputVariantId());
            ps.setString(5, t.unit());
            ps.setString(6, t.notes());
            ps.setObject(7, t.createdBy());
            ps.setObject(8, t.createdAt().atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO yield_template_outputs (id, tenant_id, template_id, variant_id,"
                      + " expected_pct, cost_share, shelf_life_days, sort_order)"
                      + " VALUES (?,?,?,?,?,?,?,?)")) {
            int i = 0;
            for (YieldOutputSpec o : t.outputs()) {
              ps.setObject(1, Ids.newId());
              ps.setObject(2, t.tenantId());
              ps.setObject(3, t.id());
              ps.setObject(4, o.variantId());
              ps.setBigDecimal(5, o.expectedPct());
              ps.setBigDecimal(6, o.costShare());
              if (o.shelfLifeDays() == null) ps.setNull(7, java.sql.Types.INTEGER);
              else ps.setInt(7, o.shelfLifeDays());
              ps.setInt(8, i++);
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return t;
        },
        "create yield template");
  }

  public Optional<YieldTemplate> find(UUID tenantId, UUID id) {
    List<YieldTemplate> heads =
        query(
            TEMPLATE_SELECT + " WHERE tenant_id = ? AND id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, id);
            },
            YieldRepository::mapTemplate,
            "find yield template");
    if (heads.isEmpty()) return Optional.empty();
    return Optional.of(withOutputs(tenantId, heads).get(0));
  }

  public List<YieldTemplate> list(UUID tenantId) {
    List<YieldTemplate> heads =
        query(
            TEMPLATE_SELECT + " WHERE tenant_id = ? ORDER BY active DESC, name, id",
            ps -> ps.setObject(1, tenantId),
            YieldRepository::mapTemplate,
            "list yield templates");
    return withOutputs(tenantId, heads);
  }

  /** Ends a template; returns false when the business has no live one by that id. */
  public boolean end(UUID tenantId, UUID id) {
    int[] rows = new int[1];
    inTx(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE yield_templates SET active = FALSE, ended_at = now()"
                      + " WHERE tenant_id = ? AND id = ? AND active")) {
            ps.setObject(1, tenantId);
            ps.setObject(2, id);
            rows[0] = ps.executeUpdate();
          }
          return null;
        },
        "end yield template");
    return rows[0] > 0;
  }

  private static final String TEMPLATE_SELECT =
      "SELECT id, tenant_id, name, input_variant_id, unit, notes, active, created_by, created_at"
          + " FROM yield_templates";

  private static YieldTemplate mapTemplate(ResultSet rs) throws SQLException {
    return new YieldTemplate(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("name"),
        rs.getObject("input_variant_id", UUID.class),
        rs.getString("unit"),
        rs.getString("notes"),
        rs.getBoolean("active"),
        rs.getObject("created_by", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        List.of());
  }

  private List<YieldTemplate> withOutputs(UUID tenantId, List<YieldTemplate> heads) {
    if (heads.isEmpty()) return heads;
    Map<UUID, List<YieldOutputSpec>> outputs = new LinkedHashMap<>();
    for (YieldTemplate t : heads) outputs.put(t.id(), new ArrayList<>());
    query(
        "SELECT template_id, variant_id, expected_pct, cost_share, shelf_life_days"
            + " FROM yield_template_outputs WHERE tenant_id = ? ORDER BY template_id, sort_order",
        ps -> ps.setObject(1, tenantId),
        rs -> {
          List<YieldOutputSpec> list = outputs.get(rs.getObject("template_id", UUID.class));
          if (list != null) {
            // wasNull speaks of the last column read: ask it right after the one that may be null.
            int days = rs.getInt("shelf_life_days");
            Integer shelfLife = rs.wasNull() ? null : days;
            list.add(
                new YieldOutputSpec(
                    rs.getObject("variant_id", UUID.class),
                    rs.getBigDecimal("expected_pct"),
                    rs.getBigDecimal("cost_share"),
                    shelfLife));
          }
          return null;
        },
        "load yield template outputs");
    List<YieldTemplate> full = new ArrayList<>(heads.size());
    for (YieldTemplate t : heads) {
      full.add(
          new YieldTemplate(
              t.id(),
              t.tenantId(),
              t.name(),
              t.inputVariantId(),
              t.unit(),
              t.notes(),
              t.active(),
              t.createdBy(),
              t.createdAt(),
              List.copyOf(outputs.get(t.id()))));
    }
    return full;
  }

  // ── Runs ───────────────────────────────────────────────────────────────────

  /**
   * Makes the breakdown: draws the primal, makes a batch per cut at its apportioned cost, records
   * the run and announces it — on one transaction.
   *
   * @param events what to announce once the run is costed
   * @return the run as recorded, with each cut's cost and batch
   * @throws ApiException 422 {@code INVENTORY_YIELD_INSUFFICIENT_INPUT} when less of the primal is
   *     on the shelf than the run takes; 409 {@code INVENTORY_YIELD_INPUT_NOT_OWNED} when the
   *     primal drawn is the supplier's consignment stock
   */
  public YieldRun record(YieldRun run, Function<YieldRun, List<OutboxRow>> events) {
    return inTx(
        c -> {
          MovementAttribution by =
              run.recordedBy() == null
                  ? MovementAttribution.system()
                  : MovementAttribution.by(run.recordedBy(), null);
          List<Drawn> drawn = draw(c, run, by);
          for (Drawn d : drawn) {
            if (!Batch.OWNERSHIP_OWNED.equals(d.ownership())) {
              throw ApiException.conflict(
                  "INVENTORY_YIELD_INPUT_NOT_OWNED",
                  "batch "
                      + d.batchId()
                      + " of the primal is the supplier's consignment stock, not the business's to"
                      + " break down");
            }
          }
          BigDecimal inputCost = costOf(drawn);
          BigDecimal lossAtCost =
              inputCost == null
                  ? null
                  : inputCost
                      .multiply(run.lossQty())
                      .divide(run.inputQty(), Yield.COST_SCALE, RoundingMode.HALF_UP);
          List<Yield.Share> shares =
              run.outputs().stream().map(o -> new Yield.Share(o.qty(), o.costShare())).toList();
          List<BigDecimal> unitCosts = Yield.apportion(inputCost, shares);
          Drawn first = drawn.get(0);
          List<YieldRunOutput> made = new ArrayList<>(run.outputs().size());
          for (int i = 0; i < run.outputs().size(); i++) {
            YieldRunOutput o = run.outputs().get(i);
            if (o.qty().signum() <= 0) {
              made.add(o.made(null, null));
              continue;
            }
            Batch cut = cut(run, o, first, unitCosts.get(i));
            inventory.insertBatch(c, cut);
            InventoryRepository.insertMovement(
                c,
                run.tenantId(),
                run.storeId(),
                o.variantId(),
                cut.id(),
                MoveType.YIELD,
                o.qty(),
                "YIELD",
                run.id(),
                by);
            for (Drawn d : drawn) {
              inventory.insertGenealogy(
                  c,
                  run.tenantId(),
                  d.batchId(),
                  cut.id(),
                  o.qty()
                      .multiply(d.qty())
                      .divide(run.inputQty(), Yield.QTY_SCALE, RoundingMode.HALF_UP),
                  "TRANSFORM",
                  "YIELD " + run.id());
            }
            made.add(o.made(unitCosts.get(i), cut.id()));
          }
          YieldRun costed = run.costed(inputCost, lossAtCost, made);
          insertRun(c, costed);
          for (OutboxRow e : events.apply(costed)) insertOutbox(c, e);
          return costed;
        },
        "record yield run");
  }

  private List<Drawn> draw(Connection c, YieldRun run, MovementAttribution by) throws SQLException {
    try {
      return inventory.deductBatches(
          c,
          run.tenantId(),
          run.storeId(),
          run.inputVariantId(),
          run.inputQty(),
          MoveType.YIELD,
          "YIELD",
          run.id(),
          null,
          null,
          null,
          by);
    } catch (ApiException e) {
      if ("INSUFFICIENT_STOCK".equals(e.code())) {
        throw new ApiException(
            422,
            "INVENTORY_YIELD_INSUFFICIENT_INPUT",
            "less than "
                + run.inputQty().toPlainString()
                + " of the primal is on the shelf at the store",
            List.of(),
            e);
      }
      throw e;
    }
  }

  /** The primal's cost over what was drawn; null when any batch drawn had none. */
  private static BigDecimal costOf(List<Drawn> drawn) {
    BigDecimal total = BigDecimal.ZERO;
    for (Drawn d : drawn) {
      if (d.costPrice() == null) return null;
      total = total.add(d.qty().multiply(d.costPrice()));
    }
    return total.setScale(Yield.COST_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * The batch a cut becomes: under the primal's lot, dated by its own shelf life or the primal's.
   */
  private static Batch cut(YieldRun run, YieldRunOutput o, Drawn first, BigDecimal unitCost) {
    LocalDate expiry =
        o.shelfLifeDays() == null
            ? first.expiryDate()
            : LocalDate.now(ZoneOffset.UTC).plusDays(o.shelfLifeDays());
    return new Batch(
        Ids.newId(),
        run.tenantId(),
        run.storeId(),
        o.variantId(),
        first.batchNo(),
        o.qty(),
        o.qty(),
        unitCost,
        expiry,
        Instant.now(),
        Batch.STATUS_ACTIVE,
        Batch.MATERIAL_AVAILABLE,
        null,
        null,
        null,
        Batch.OWNERSHIP_OWNED,
        null,
        Batch.DUTY_PAID);
  }

  private static void insertRun(Connection c, YieldRun r) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO yield_runs (id, tenant_id, store_id, template_id, input_variant_id,"
                + " input_qty, input_cost, output_qty, loss_qty, expected_loss_qty, loss_at_cost,"
                + " reference, notes, recorded_by, recorded_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, r.id());
      ps.setObject(2, r.tenantId());
      ps.setObject(3, r.storeId());
      ps.setObject(4, r.templateId());
      ps.setObject(5, r.inputVariantId());
      ps.setBigDecimal(6, r.inputQty());
      ps.setBigDecimal(7, r.inputCost());
      ps.setBigDecimal(8, r.outputQty());
      ps.setBigDecimal(9, r.lossQty());
      ps.setBigDecimal(10, r.expectedLossQty());
      ps.setBigDecimal(11, r.lossAtCost());
      ps.setString(12, r.reference());
      ps.setString(13, r.notes());
      ps.setObject(14, r.recordedBy());
      ps.setObject(15, r.recordedAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO yield_run_outputs (id, tenant_id, run_id, variant_id, qty, expected_qty,"
                + " unit_cost, batch_id) VALUES (?,?,?,?,?,?,?,?)")) {
      for (YieldRunOutput o : r.outputs()) {
        ps.setObject(1, Ids.newId());
        ps.setObject(2, r.tenantId());
        ps.setObject(3, r.id());
        ps.setObject(4, o.variantId());
        ps.setBigDecimal(5, o.qty());
        ps.setBigDecimal(6, o.expectedQty());
        ps.setBigDecimal(7, o.unitCost());
        ps.setObject(8, o.batchId());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  /** The runs of a period, newest first, at one store or all of them. */
  public List<YieldRun> findRuns(UUID tenantId, UUID storeId, LocalDate from, LocalDate to) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT r.id, r.tenant_id, r.store_id, r.template_id, t.name AS template_name,"
                + " r.input_variant_id, r.input_qty, r.input_cost, r.output_qty, r.loss_qty,"
                + " r.expected_loss_qty, r.loss_at_cost, r.reference, r.notes, r.recorded_by,"
                + " r.recorded_at FROM yield_runs r JOIN yield_templates t ON t.id = r.template_id"
                + " AND t.tenant_id = r.tenant_id WHERE r.tenant_id = ?"
                + " AND r.recorded_at >= ?::date AND r.recorded_at < (?::date + 1)");
    if (storeId != null) sql.append(" AND r.store_id = ?");
    sql.append(" ORDER BY r.recorded_at DESC, r.id DESC");
    List<YieldRun> heads =
        query(
            sql.toString(),
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, from);
              ps.setObject(3, to);
              if (storeId != null) ps.setObject(4, storeId);
            },
            YieldRepository::mapRun,
            "list yield runs");
    if (heads.isEmpty()) return heads;
    Map<UUID, List<YieldRunOutput>> outputs = new LinkedHashMap<>();
    for (YieldRun r : heads) outputs.put(r.id(), new ArrayList<>());
    query(
        "SELECT o.run_id, o.variant_id, o.qty, o.expected_qty, o.unit_cost, o.batch_id"
            + " FROM yield_run_outputs o JOIN yield_runs r ON r.id = o.run_id"
            + " AND r.tenant_id = o.tenant_id WHERE o.tenant_id = ?"
            + " AND r.recorded_at >= ?::date AND r.recorded_at < (?::date + 1) ORDER BY o.id",
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, from);
          ps.setObject(3, to);
        },
        rs -> {
          List<YieldRunOutput> list = outputs.get(rs.getObject("run_id", UUID.class));
          if (list != null) {
            list.add(
                new YieldRunOutput(
                    rs.getObject("variant_id", UUID.class),
                    rs.getBigDecimal("qty"),
                    rs.getBigDecimal("expected_qty"),
                    null,
                    null,
                    rs.getBigDecimal("unit_cost"),
                    rs.getObject("batch_id", UUID.class)));
          }
          return null;
        },
        "load yield run outputs");
    List<YieldRun> full = new ArrayList<>(heads.size());
    for (YieldRun r : heads) {
      full.add(r.costed(r.inputCost(), r.lossAtCost(), List.copyOf(outputs.get(r.id()))));
    }
    return full;
  }

  private static YieldRun mapRun(ResultSet rs) throws SQLException {
    return new YieldRun(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("template_id", UUID.class),
        rs.getString("template_name"),
        rs.getObject("input_variant_id", UUID.class),
        rs.getBigDecimal("input_qty"),
        rs.getBigDecimal("input_cost"),
        rs.getBigDecimal("output_qty"),
        rs.getBigDecimal("loss_qty"),
        rs.getBigDecimal("expected_loss_qty"),
        rs.getBigDecimal("loss_at_cost"),
        rs.getString("reference"),
        rs.getString("notes"),
        rs.getObject("recorded_by", UUID.class),
        rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
        List.of());
  }
}
