package com.storeql.inventory.service;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.YieldOutputSpec;
import com.storeql.inventory.domain.Domain.YieldRun;
import com.storeql.inventory.domain.Domain.YieldRunOutput;
import com.storeql.inventory.domain.Domain.YieldTemplate;
import com.storeql.inventory.domain.Domain.YieldTotals;
import com.storeql.inventory.domain.Yield;
import com.storeql.inventory.dto.Dtos.YieldOutputSpecRequest;
import com.storeql.inventory.dto.Dtos.YieldRunOutputRequest;
import com.storeql.inventory.dto.Dtos.YieldRunRequest;
import com.storeql.inventory.dto.Dtos.YieldTemplateRequest;
import com.storeql.inventory.repo.YieldRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fresh yield, preparation and butchery loss: what a primal should break into (a template, the
 * business's own figures), and each breakdown made at a store — the primal consumed, a batch per
 * cut at its apportioned cost, the loss recorded against what was expected. The runs of a period
 * are the butchery-loss report.
 */
@ApplicationScoped
public class YieldService {

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  @Inject YieldRepository repo;

  // ── Templates ──────────────────────────────────────────────────────────────

  /**
   * @throws ApiException 400 {@code INVENTORY_YIELD_OUTPUTS_REQUIRED}, {@code
   *     INVENTORY_YIELD_OUTPUT_IS_INPUT}, {@code INVENTORY_YIELD_OUTPUT_DUPLICATE}, {@code
   *     INVENTORY_YIELD_SHARES_INVALID} (the cuts' shares add up to more than the whole)
   */
  public YieldTemplate create(TenantContext ctx, YieldTemplateRequest req) {
    UUID inputVariantId = Parsing.uuid(req.inputVariantId(), "inputVariantId");
    if (req.outputs().isEmpty()) {
      throw ApiException.badRequest(
          "INVENTORY_YIELD_OUTPUTS_REQUIRED",
          "a template names at least one cut the primal yields");
    }
    List<YieldOutputSpec> outputs = new ArrayList<>(req.outputs().size());
    Set<UUID> seen = new HashSet<>();
    BigDecimal sum = BigDecimal.ZERO;
    for (YieldOutputSpecRequest o : req.outputs()) {
      UUID variantId = Parsing.uuid(o.variantId(), "outputs.variantId");
      if (variantId.equals(inputVariantId)) {
        throw ApiException.badRequest(
            "INVENTORY_YIELD_OUTPUT_IS_INPUT", "the primal cannot be one of its own cuts");
      }
      if (!seen.add(variantId)) {
        throw ApiException.badRequest(
            "INVENTORY_YIELD_OUTPUT_DUPLICATE", "variant " + variantId + " is named twice");
      }
      sum = sum.add(o.expectedPct());
      outputs.add(
          new YieldOutputSpec(
              variantId,
              o.expectedPct(),
              o.costShare() == null ? o.expectedPct() : o.costShare(),
              o.shelfLifeDays()));
    }
    if (sum.compareTo(HUNDRED) > 0) {
      throw ApiException.badRequest(
          "INVENTORY_YIELD_SHARES_INVALID",
          "the cuts add up to " + sum.toPlainString() + " % of the primal; at most 100");
    }
    return repo.create(
        new YieldTemplate(
            Ids.newId(),
            ctx.requireTenantId(),
            req.name().trim(),
            inputVariantId,
            blankToNull(req.unit()),
            blankToNull(req.notes()),
            true,
            ctx.userId(),
            Instant.now(),
            List.copyOf(outputs)));
  }

  public List<YieldTemplate> templates(TenantContext ctx) {
    return repo.list(ctx.requireTenantId());
  }

  /**
   * @throws ApiException 404 {@code INVENTORY_YIELD_TEMPLATE_NOT_FOUND}
   */
  public YieldTemplate template(TenantContext ctx, UUID id) {
    return repo.find(ctx.requireTenantId(), id)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "INVENTORY_YIELD_TEMPLATE_NOT_FOUND", "no yield template " + id));
  }

  /**
   * @throws ApiException 404 {@code INVENTORY_YIELD_TEMPLATE_NOT_FOUND} when there is no live one
   */
  public void end(TenantContext ctx, UUID id) {
    if (!repo.end(ctx.requireTenantId(), id)) {
      throw ApiException.notFound(
          "INVENTORY_YIELD_TEMPLATE_NOT_FOUND", "no live yield template " + id);
    }
  }

  // ── Runs ───────────────────────────────────────────────────────────────────

  /**
   * Records a breakdown at a store the caller may act at.
   *
   * @throws ApiException 404 {@code INVENTORY_YIELD_TEMPLATE_NOT_FOUND}; 409 {@code
   *     INVENTORY_YIELD_TEMPLATE_ENDED}; 400 {@code INVENTORY_YIELD_OUTPUT_UNKNOWN} (a cut the
   *     template never named), {@code INVENTORY_YIELD_OUTPUT_DUPLICATE}, {@code
   *     INVENTORY_YIELD_OUTPUT_EXCEEDS_INPUT} (more out than in); 422 {@code
   *     INVENTORY_YIELD_INSUFFICIENT_INPUT}; 409 {@code INVENTORY_YIELD_INPUT_NOT_OWNED}
   */
  public YieldRun record(TenantContext ctx, YieldRunRequest req) {
    UUID tenantId = ctx.requireTenantId();
    UUID storeId = Parsing.uuid(req.storeId(), "storeId");
    UUID templateId = Parsing.uuid(req.templateId(), "templateId");
    ctx.requireStoreAccess(storeId);
    YieldTemplate t = template(ctx, templateId);
    if (!t.active()) {
      throw ApiException.conflict(
          "INVENTORY_YIELD_TEMPLATE_ENDED", "template " + templateId + " has been ended");
    }
    Map<UUID, YieldOutputSpec> specs = new HashMap<>();
    for (YieldOutputSpec s : t.outputs()) specs.put(s.variantId(), s);
    Map<UUID, BigDecimal> given = new HashMap<>();
    for (YieldRunOutputRequest o : req.outputs()) {
      UUID variantId = Parsing.uuid(o.variantId(), "outputs.variantId");
      if (!specs.containsKey(variantId)) {
        throw ApiException.badRequest(
            "INVENTORY_YIELD_OUTPUT_UNKNOWN",
            "variant " + variantId + " is not a cut of template " + t.name());
      }
      if (given.put(variantId, o.qty()) != null) {
        throw ApiException.badRequest(
            "INVENTORY_YIELD_OUTPUT_DUPLICATE", "variant " + variantId + " is given twice");
      }
    }
    // Every cut the template names is on the run, at nothing when it was not given.
    List<YieldRunOutput> outputs = new ArrayList<>(t.outputs().size());
    BigDecimal outputQty = BigDecimal.ZERO;
    for (YieldOutputSpec s : t.outputs()) {
      BigDecimal qty = given.getOrDefault(s.variantId(), BigDecimal.ZERO);
      outputQty = outputQty.add(qty);
      outputs.add(
          new YieldRunOutput(
              s.variantId(),
              qty,
              Yield.expectedQty(req.inputQty(), s.expectedPct()),
              s.costShare(),
              s.shelfLifeDays(),
              null,
              null));
    }
    if (outputQty.compareTo(req.inputQty()) > 0) {
      throw ApiException.badRequest(
          "INVENTORY_YIELD_OUTPUT_EXCEEDS_INPUT",
          "the cuts add up to "
              + outputQty.toPlainString()
              + " out of "
              + req.inputQty().toPlainString()
              + " in; a breakdown makes no more than it took");
    }
    YieldRun draft =
        new YieldRun(
            Ids.newId(),
            tenantId,
            storeId,
            t.id(),
            t.name(),
            t.inputVariantId(),
            req.inputQty(),
            null,
            outputQty,
            req.inputQty().subtract(outputQty),
            Yield.expectedQty(req.inputQty(), t.expectedLossPct()),
            null,
            blankToNull(req.reference()),
            blankToNull(req.notes()),
            ctx.userId(),
            Instant.now(),
            List.copyOf(outputs));
    return repo.record(draft, YieldService::events);
  }

  /**
   * What the rest of the platform hears: the primal gone and each cut arrived, for the stock
   * projections, and the run itself.
   */
  static List<OutboxRow> events(YieldRun r) {
    List<OutboxRow> events = new ArrayList<>();
    events.add(
        new OutboxRow(
            "StockAdjusted",
            "storeql.inventory.stock-adjusted",
            r.tenantId(),
            r.inputVariantId(),
            Events.stockAdjusted(
                r.tenantId(), r.storeId(), r.inputVariantId(), r.inputQty().negate())));
    for (YieldRunOutput o : r.outputs()) {
      if (o.batchId() == null) continue;
      events.add(
          new OutboxRow(
              "StockReceived",
              "storeql.inventory.stock-received",
              r.tenantId(),
              o.batchId(),
              Events.stockReceived(
                  r.tenantId(), r.storeId(), o.variantId(), o.batchId(), o.qty())));
    }
    events.add(
        new OutboxRow(
            "YieldRecorded",
            "storeql.inventory.yield-recorded",
            r.tenantId(),
            r.id(),
            Events.yieldRecorded(r)));
    return events;
  }

  /**
   * @throws ApiException 400 {@code INVENTORY_PERIOD_INVALID} when the period ends before it starts
   */
  public List<YieldRun> runs(TenantContext ctx, UUID storeId, String from, String to) {
    if (storeId != null) ctx.requireStoreAccess(storeId);
    LocalDate f =
        from == null || from.isBlank() ? LocalDate.of(2000, 1, 1) : Parsing.date(from, "from");
    LocalDate t = to == null || to.isBlank() ? LocalDate.now() : Parsing.date(to, "to");
    if (f.isAfter(t)) {
      throw ApiException.badRequest(
          "INVENTORY_PERIOD_INVALID", "the period ends (" + t + ") before it starts (" + f + ")");
    }
    return repo.findRuns(ctx.requireTenantId(), storeId, f, t);
  }

  /** The period added up; the loss at cost only over the runs that had one. */
  public static YieldTotals totals(List<YieldRun> runs) {
    BigDecimal input = BigDecimal.ZERO;
    BigDecimal output = BigDecimal.ZERO;
    BigDecimal loss = BigDecimal.ZERO;
    BigDecimal expected = BigDecimal.ZERO;
    BigDecimal atCost = BigDecimal.ZERO;
    for (YieldRun r : runs) {
      input = input.add(r.inputQty());
      output = output.add(r.outputQty());
      loss = loss.add(r.lossQty());
      expected = expected.add(r.expectedLossQty());
      if (r.lossAtCost() != null) atCost = atCost.add(r.lossAtCost());
    }
    return new YieldTotals(runs.size(), input, output, loss, expected, atCost);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
