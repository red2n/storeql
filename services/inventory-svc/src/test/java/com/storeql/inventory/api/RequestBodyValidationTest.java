package com.storeql.inventory.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.AbcCompileRun;
import com.storeql.inventory.domain.Domain.KanbanCard;
import com.storeql.inventory.domain.Domain.ReorderPointPlan;
import com.storeql.inventory.dto.Dtos.AggregateRequest;
import com.storeql.inventory.dto.Dtos.ComputeSafetyStockRequest;
import com.storeql.inventory.dto.Dtos.RunAbcRequest;
import com.storeql.inventory.dto.Dtos.TriggerKanbanRequest;
import com.storeql.inventory.dto.Dtos.UpdateOrderModifiersRequest;
import com.storeql.inventory.service.InventoryService;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Every request body these routes take goes through common-web {@code Validations.validate} before
 * anything reads it: a number no quantity, threshold or cost could be ({@code 1E+80000000}, twelve
 * characters on the wire) is {@code 400 VALIDATION_FAILED "<field>: is out of range"}, never a 500
 * from the database or a string eighty million digits long, and nothing reaches the service. The
 * role is still asked first (a cashier is told 403, whatever the body says).
 *
 * <p>The real {@link TenantContext} decides; the service is a stub that records what reached it.
 */
class RequestBodyValidationTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();

  /**
   * What no quantity, threshold or cost is: too many whole digits, too many places, an int wrap.
   */
  private static final List<String> ABSURD = List.of("1E+80000000", "1E-80000000", "1E+2147483647");

  private static TenantContext caller(String role) {
    TenantContext ctx = new TenantContext();
    try {
      Method set =
          TenantContext.class.getDeclaredMethod(
              "set", UUID.class, UUID.class, Set.class, Set.class, String.class);
      set.setAccessible(true);
      set.invoke(ctx, TENANT, Ids.newId(), Set.of(role), Set.of(), "req");
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not populate a TenantContext", e);
    }
    return ctx;
  }

  private static ApiException refused(Executable act, int status, String code) {
    ApiException e = assertThrows(ApiException.class, act);
    assertEquals(status, e.status(), e.getMessage() + " " + e.details());
    assertEquals(code, e.code(), e.getMessage() + " " + e.details());
    return e;
  }

  private static void outOfRange(Executable act, String field) {
    ApiException e = refused(act, 400, "VALIDATION_FAILED");
    assertEquals(List.of(field + ": is out of range"), e.details());
  }

  private static void fieldRefused(Executable act, String field) {
    ApiException e = refused(act, 400, "VALIDATION_FAILED");
    assertEquals(1, e.details().size(), e.details().toString());
    assertTrue(e.details().get(0).startsWith(field + ": "), e.details().toString());
  }

  private static BigDecimal n(String text) {
    return new BigDecimal(text);
  }

  /** Records every call that reaches it; a refusal leaves it empty. */
  private static final class Stock extends InventoryService {
    final List<String> acted = new ArrayList<>();

    private static ReorderPointPlan plan(UUID id) {
      return new ReorderPointPlan(
          id,
          TENANT,
          STORE,
          Ids.newId(),
          7,
          BigDecimal.TEN,
          BigDecimal.ONE,
          BigDecimal.TEN,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          Instant.now());
    }

    private static KanbanCard card(UUID id, String notes) {
      return new KanbanCard(
          id,
          TENANT,
          STORE,
          Ids.newId(),
          KanbanCard.PRODUCTION,
          KanbanCard.EMPTY,
          BigDecimal.TEN,
          null,
          null,
          notes,
          null,
          null,
          null,
          Instant.now(),
          null,
          null);
    }

    @Override
    public ReorderPointPlan getRopPlanById(UUID tenantId, UUID id) {
      acted.add("read plan");
      return plan(id);
    }

    @Override
    public ReorderPointPlan updateRopOrderModifiers(
        UUID tenantId, UUID ropId, BigDecimal min, BigDecimal max, BigDecimal lotMult) {
      acted.add("plan modifiers " + min + " " + max + " " + lotMult);
      return plan(ropId);
    }

    @Override
    public KanbanCard getKanbanCard(UUID tenantId, UUID cardId) {
      acted.add("read card");
      return card(cardId, null);
    }

    @Override
    public KanbanCard updateKanbanOrderModifiers(
        UUID tenantId, UUID cardId, BigDecimal min, BigDecimal max, BigDecimal lotMult) {
      acted.add("card modifiers " + min + " " + max + " " + lotMult);
      return card(cardId, null);
    }

    @Override
    public KanbanCard triggerKanbanCard(UUID tenantId, UUID cardId, String notes) {
      acted.add("trigger " + notes);
      return card(cardId, notes);
    }

    @Override
    public AbcCompileResult runAbcCompile(
        UUID tenantId,
        UUID storeId,
        String criteria,
        BigDecimal thresholdA,
        BigDecimal thresholdAB) {
      acted.add("compile " + thresholdA + " " + thresholdAB);
      return new AbcCompileResult(
          new AbcCompileRun(
              Ids.newId(), tenantId, storeId, "VALUE", thresholdA, thresholdAB, 0, Instant.now()),
          List.of());
    }

    @Override
    public int computeSafetyStock(UUID tenantId, UUID storeId, UUID variantId) {
      acted.add("safety stock " + storeId + " " + variantId);
      return 0;
    }

    @Override
    public int aggregateDemand(UUID tenantId, UUID storeId, String bucketType, LocalDate since) {
      acted.add("aggregate " + bucketType);
      return 0;
    }
  }

  private static ReorderPointResource rop(Stock stock, String role) {
    ReorderPointResource r = new ReorderPointResource();
    r.service = stock;
    r.ctx = caller(role);
    return r;
  }

  private static KanbanResource kanban(Stock stock, String role) {
    KanbanResource r = new KanbanResource();
    r.service = stock;
    r.ctx = caller(role);
    return r;
  }

  private static AbcAnalysisResource abc(Stock stock, String role) {
    AbcAnalysisResource r = new AbcAnalysisResource();
    r.service = stock;
    r.ctx = caller(role);
    return r;
  }

  private static SafetyStockResource safety(Stock stock, String role) {
    SafetyStockResource r = new SafetyStockResource();
    r.service = stock;
    r.ctx = caller(role);
    return r;
  }

  private static DemandHistoryResource demand(Stock stock, String role) {
    DemandHistoryResource r = new DemandHistoryResource();
    r.service = stock;
    r.ctx = caller(role);
    return r;
  }

  private static UpdateOrderModifiersRequest modifiers(String min, String max, String lot) {
    return new UpdateOrderModifiersRequest(
        min == null ? null : n(min), max == null ? null : n(max), lot == null ? null : n(lot));
  }

  // ── order modifiers: a plan's and a card's ─────────────────────────────────

  @Test
  @DisplayName(
      "A plan's order modifiers refuse a number no quantity could be, field by field, before the"
          + " plan is even read")
  void aPlansOrderModifiersRefuseAbsurdNumbers() {
    Stock stock = new Stock();
    ReorderPointResource r = rop(stock, "MANAGER");
    UUID plan = Ids.newId();
    for (String absurd : ABSURD) {
      outOfRange(() -> r.updateRopModifiers(plan, modifiers(absurd, "100", "5")), "minOrderQty");
      outOfRange(() -> r.updateRopModifiers(plan, modifiers("5", absurd, "5")), "maxOrderQty");
      outOfRange(() -> r.updateRopModifiers(plan, modifiers("5", "100", absurd)), "lotMultiplier");
    }
    assertEquals(List.of(), stock.acted, "nothing reached the service");
  }

  @Test
  @DisplayName(
      "A plan's order modifiers are quantities as the plan keeps them: never negative, a lot never"
          + " nothing, fifteen whole digits and three places; no body is no request")
  void aPlansOrderModifiersAreHeldToTheColumn() {
    Stock stock = new Stock();
    ReorderPointResource r = rop(stock, "OWNER");
    UUID plan = Ids.newId();
    fieldRefused(() -> r.updateRopModifiers(plan, modifiers("-1", null, null)), "minOrderQty");
    fieldRefused(() -> r.updateRopModifiers(plan, modifiers(null, "-0.001", null)), "maxOrderQty");
    fieldRefused(() -> r.updateRopModifiers(plan, modifiers(null, null, "0")), "lotMultiplier");
    // Sixteen whole digits fails NUMERIC(18,3) as a 500; four places would be rounded unasked.
    fieldRefused(
        () -> r.updateRopModifiers(plan, modifiers("1000000000000000", null, null)), "minOrderQty");
    fieldRefused(() -> r.updateRopModifiers(plan, modifiers("1.0005", null, null)), "minOrderQty");
    refused(() -> r.updateRopModifiers(plan, null), 400, "BODY_REQUIRED");
    assertEquals(List.of(), stock.acted, "nothing reached the service");

    r.updateRopModifiers(plan, modifiers("999999999999999.999", "0", "0.001"));
    assertEquals(List.of("read plan", "plan modifiers 999999999999999.999 0 0.001"), stock.acted);
  }

  @Test
  @DisplayName("Who may set a plan's modifiers is asked before what they sent")
  void theRoleIsAskedBeforeTheBody() {
    Stock stock = new Stock();
    for (String role : new String[] {"STOREKEEPER", "CASHIER"}) {
      refused(
          () ->
              rop(stock, role)
                  .updateRopModifiers(Ids.newId(), modifiers("1E+80000000", null, null)),
          403,
          "FORBIDDEN");
      refused(
          () ->
              kanban(stock, role)
                  .updateKanbanModifiers(Ids.newId(), modifiers("1E+80000000", null, null)),
          403,
          "FORBIDDEN");
    }
    assertEquals(List.of(), stock.acted);
  }

  @Test
  @DisplayName(
      "A kanban card's order modifiers refuse a number no quantity could be, before the card is"
          + " read")
  void aKanbanCardsOrderModifiersRefuseAbsurdNumbers() {
    Stock stock = new Stock();
    KanbanResource r = kanban(stock, "MANAGER");
    UUID card = Ids.newId();
    for (String absurd : ABSURD) {
      outOfRange(() -> r.updateKanbanModifiers(card, modifiers(absurd, null, null)), "minOrderQty");
      outOfRange(() -> r.updateKanbanModifiers(card, modifiers(null, absurd, null)), "maxOrderQty");
      outOfRange(
          () -> r.updateKanbanModifiers(card, modifiers(null, null, absurd)), "lotMultiplier");
    }
    fieldRefused(() -> r.updateKanbanModifiers(card, modifiers(null, null, "0")), "lotMultiplier");
    refused(() -> r.updateKanbanModifiers(card, null), 400, "BODY_REQUIRED");
    assertEquals(List.of(), stock.acted, "nothing reached the service");

    r.updateKanbanModifiers(card, modifiers("3", "30", "3"));
    assertEquals(List.of("read card", "card modifiers 3 30 3"), stock.acted);
  }

  // ── ABC compile, safety stock, kanban trigger, demand ───────────────────────

  @Test
  @DisplayName(
      "An ABC compile refuses a threshold no percentage could be, or finer than the run keeps;"
          + " with no body it compiles on the defaults")
  void anAbcCompileRefusesAbsurdThresholds() {
    Stock stock = new Stock();
    AbcAnalysisResource r = abc(stock, "OWNER");
    String store = STORE.toString();
    for (String absurd : ABSURD) {
      outOfRange(
          () -> r.runAbcCompile(new RunAbcRequest(store, "VALUE", n(absurd), null)), "thresholdA");
      outOfRange(
          () -> r.runAbcCompile(new RunAbcRequest(store, "VALUE", n("70"), n(absurd))),
          "thresholdAB");
    }
    // 99.98 < 99.999 < 100 passes the service, and 99.999 was kept as 100.00, which the run's own
    // check refuses: a 500.
    fieldRefused(
        () -> r.runAbcCompile(new RunAbcRequest(store, "VALUE", n("99.98"), n("99.999"))),
        "thresholdAB");
    refused(
        () ->
            abc(stock, "CASHIER")
                .runAbcCompile(new RunAbcRequest(store, null, n("1E+80000000"), null)),
        403,
        "FORBIDDEN");
    assertEquals(List.of(), stock.acted, "nothing reached the service");

    assertEquals(201, r.runAbcCompile(null).getStatus());
    assertEquals(
        201, r.runAbcCompile(new RunAbcRequest(store, "VALUE", n("70.5"), n("90"))).getStatus());
    assertEquals(List.of("compile null null", "compile 70.5 90"), stock.acted);
  }

  @Test
  @DisplayName(
      "A safety-stock recompute's body is checked when one is sent; none still means the caller's"
          + " whole scope")
  void aSafetyStockRecomputeTakesAnOptionalBody() {
    Stock stock = new Stock();
    SafetyStockResource r = safety(stock, "MANAGER");
    UUID variant = Ids.newId();
    refused(
        () ->
            safety(stock, "STOREKEEPER")
                .computeSafetyStock(new ComputeSafetyStockRequest(null, null)),
        403,
        "FORBIDDEN");
    refused(
        () -> r.computeSafetyStock(new ComputeSafetyStockRequest("1E+80000000", null)),
        400,
        "INVALID_UUID");
    assertEquals(List.of(), stock.acted);

    r.computeSafetyStock(null);
    r.computeSafetyStock(new ComputeSafetyStockRequest(STORE.toString(), variant.toString()));
    assertEquals(
        List.of("safety stock null null", "safety stock " + STORE + " " + variant), stock.acted);
  }

  @Test
  @DisplayName(
      "A kanban trigger's note is held to 2000 characters before the card is read; no body still"
          + " triggers")
  void aKanbanTriggerChecksItsNote() {
    Stock stock = new Stock();
    KanbanResource r = kanban(stock, "STOREKEEPER");
    UUID card = Ids.newId();
    fieldRefused(
        () -> r.triggerKanbanCard(card, new TriggerKanbanRequest("x".repeat(2001))), "notes");
    assertEquals(List.of(), stock.acted, "the card was not even read");

    r.triggerKanbanCard(card, null);
    r.triggerKanbanCard(card, new TriggerKanbanRequest("x".repeat(2000)));
    assertEquals(
        List.of("read card", "trigger null", "read card", "trigger " + "x".repeat(2000)),
        stock.acted);
  }

  @Test
  @DisplayName("A demand aggregation still takes no body, and a body sent is checked")
  void aDemandAggregationTakesAnOptionalBody() {
    Stock stock = new Stock();
    DemandHistoryResource r = demand(stock, "MANAGER");
    r.aggregateDemand(null);
    r.aggregateDemand(new AggregateRequest(null, "DAY", null));
    assertEquals(List.of("aggregate WEEK", "aggregate DAY"), stock.acted);
  }
}
