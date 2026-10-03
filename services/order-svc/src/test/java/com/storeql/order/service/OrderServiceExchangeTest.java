package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.Order;
import com.storeql.order.dto.Dtos.ExchangeNewItemRequest;
import com.storeql.order.dto.Dtos.ExchangeRequest;
import com.storeql.order.dto.Dtos.OrderItemRequest;
import com.storeql.order.dto.Dtos.ReturnItemRequest;
import com.storeql.order.repo.OrderRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A direct exchange's new basket is a till sale's lines — what each pack said of itself goes with
 * it, so a recalled lot, a sticker and a scale are judged as on a sale — and a request is judged
 * whole (400) before the sale is looked for (404), before the caller's store (403) and before the
 * key's first answer.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceExchangeTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID ORDER = Ids.newId();
  private static final String VARIANT = Ids.newId().toString();
  private static final String SCALE = Ids.newId().toString();
  private static final String STICKER = Ids.newId().toString();

  @Mock OrderRepository repo;
  @Mock TenantContext ctx;

  private OrderService svc;

  @BeforeEach
  void setUp() {
    svc = new OrderService();
    svc.repo = repo;
  }

  private static ExchangeNewItemRequest scanned(
      String variant, String qty, String scale, String sticker, String lot, String expiry) {
    return new ExchangeNewItemRequest(variant, new BigDecimal(qty), scale, sticker, lot, expiry);
  }

  private static ReturnItemRequest back(String variant, String condition) {
    return new ReturnItemRequest(variant, BigDecimal.ONE, condition);
  }

  private static ExchangeRequest request(
      List<ReturnItemRequest> returned, List<ExchangeNewItemRequest> bought, String customer) {
    return new ExchangeRequest("wrong size", returned, bought, customer);
  }

  private static ExchangeRequest good() {
    return request(
        List.of(back(VARIANT, "SEALED")),
        List.of(new ExchangeNewItemRequest(VARIANT, BigDecimal.ONE, null, null, null, null)),
        null);
  }

  // ── the new basket carries what the pack said ────────────────────────────

  /**
   * The till scans the new basket as it scans a sale, and the new sale is a till sale: the lot and
   * expiry a GS1 2D code carried (checked against open recalls), the sticker's markdown (the line's
   * price) and the scale a weighed item was read on (its certificate) each reach the sale's line as
   * sent — never dropped, so a recalled lot cannot leave the shop by way of an exchange.
   */
  @Test
  void aNewItemCarriesWhatItsPackSaidAsATillSalesLineDoes() {
    List<OrderItemRequest> lines =
        OrderService.exchangeSaleLines(
            List.of(
                scanned(VARIANT, "1", null, null, "L42", "2026-10-05"),
                scanned(VARIANT, "0.37512", SCALE, STICKER, null, null)));

    OrderItemRequest lot = lines.get(0);
    assertEquals(VARIANT, lot.variantId());
    assertEquals("L42", lot.batchNo());
    assertEquals("2026-10-05", lot.expiry());
    assertNull(lot.markdownId());
    assertNull(lot.weighingInstrumentId());

    OrderItemRequest weighed = lines.get(1);
    assertEquals(new BigDecimal("0.375"), weighed.qty());
    assertEquals(SCALE, weighed.weighingInstrumentId());
    assertEquals(STICKER, weighed.markdownId());
    assertNull(weighed.batchNo());
    assertNull(weighed.expiry());

    for (OrderItemRequest l : lines) {
      // Priced by the server, as every till sale is; nothing the exchange carries names a price.
      assertNull(l.unitPrice());
      assertNull(l.notes());
    }
  }

  /**
   * Two packs of one product are two lines whenever they said different things of themselves — a
   * lot, an expiry, a sticker — and stay in the order sent: never merged by the variant, so the
   * recalled one is judged by its own lot (and named by its own place) and the other is sold, and a
   * stickered pack is never priced as its unstickered neighbour. The till keeps them apart as its
   * cart does; this is what it may rely on here.
   */
  @Test
  void twoPacksOfOneProductStayTwoLinesEachWithWhatItSaid() {
    List<OrderItemRequest> lines =
        OrderService.exchangeSaleLines(
            List.of(
                scanned(VARIANT, "1", null, null, "L43", "2026-12-31"),
                scanned(VARIANT, "1", null, null, "L42", "2026-12-31"),
                scanned(VARIANT, "1", null, null, "L42", "2027-01-31"),
                scanned(VARIANT, "1", null, STICKER, null, null),
                scanned(VARIANT, "1", null, null, null, null)));

    assertEquals(5, lines.size());
    for (OrderItemRequest l : lines) {
      assertEquals(VARIANT, l.variantId());
      assertEquals(BigDecimal.ONE, l.qty());
    }
    assertEquals(
        List.of("L43", "L42", "L42"),
        lines.subList(0, 3).stream().map(OrderItemRequest::batchNo).toList());
    assertEquals(
        List.of("2026-12-31", "2026-12-31", "2027-01-31"),
        lines.subList(0, 3).stream().map(OrderItemRequest::expiry).toList());
    assertEquals(STICKER, lines.get(3).markdownId());
    assertNull(lines.get(4).markdownId());
    assertNull(lines.get(4).batchNo());
  }

  @Test
  void aNewItemThatSaidNothingOfItsPackCarriesNothing() {
    OrderItemRequest line =
        OrderService.exchangeSaleLines(
                List.of(
                    new ExchangeNewItemRequest(VARIANT, BigDecimal.TEN, null, null, null, null)))
            .get(0);

    assertEquals(BigDecimal.TEN, line.qty());
    assertNull(line.batchNo());
    assertNull(line.expiry());
    assertNull(line.markdownId());
    assertNull(line.weighingInstrumentId());
  }

  /** Each of the pack's fields is judged by the exchange's own name for it. */
  @Test
  void aNewItemsIdsAndExpiryAreRefusedByTheExchangesOwnNames() {
    record Case(ExchangeNewItemRequest item, String code, String field) {}
    for (Case c :
        List.of(
            new Case(scanned("v-1", "1", null, null, null, null), "INVALID_UUID", "variantId"),
            // A version 4 id: not one this platform mints or takes.
            new Case(
                scanned(VARIANT, "1", null, "3f1c2b6e-7d4a-4c1e-9b2a-5e6f7a8b9c0d", null, null),
                "INVALID_UUID",
                "markdownId"),
            new Case(
                scanned(VARIANT, "1", "scale-1", null, null, null),
                "INVALID_UUID",
                "weighingInstrumentId"),
            new Case(
                scanned(VARIANT, "1", null, null, "L42", "05/10/2026"),
                "ORDER_LINE_EXPIRY_INVALID",
                "expiry"))) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () ->
                  OrderService.exchangeSaleLines(
                      List.of(
                          new ExchangeNewItemRequest(
                              VARIANT, BigDecimal.ONE, null, null, null, null),
                          c.item())),
              c.field());
      assertEquals(400, e.status(), c.field());
      assertEquals(c.code(), e.code(), c.field());
      assertTrue(
          e.getMessage().contains("newItems[1]." + c.field()), c.field() + ": " + e.getMessage());
    }
  }

  // ── the order of refusals: 400, then 404, then 403, then the key ─────────

  /**
   * A body that is wrong is wrong whoever sends it and whatever sale it names: refused before the
   * sale is looked for, before the caller's store is judged and before the key is looked up — so it
   * reads nothing, and a sale the caller cannot reach is not how they learn their body is bad.
   */
  @Test
  void aBadBodyIsRefusedBeforeTheSaleTheStoreOrTheKey() {
    record Case(String what, ExchangeRequest req, String code) {}
    for (Case c :
        List.of(
            new Case(
                "nothing back",
                request(List.of(), good().newItems(), null),
                "ORDER_RETURN_NO_ITEMS"),
            new Case(
                "nothing bought",
                request(good().returnItems(), List.of(), null),
                "ORDER_EXCHANGE_NO_NEW_ITEMS"),
            new Case(
                "a new item finer than any reading",
                request(
                    good().returnItems(),
                    List.of(
                        new ExchangeNewItemRequest(
                            VARIANT, new BigDecimal("0.3755123"), null, null, null, null)),
                    null),
                "VALIDATION_FAILED"),
            new Case(
                "a new item's id",
                request(
                    good().returnItems(),
                    List.of(
                        new ExchangeNewItemRequest("v-1", BigDecimal.ONE, null, null, null, null)),
                    null),
                "INVALID_UUID"),
            new Case(
                "a new item's expiry",
                request(
                    good().returnItems(),
                    List.of(scanned(VARIANT, "1", null, null, "L42", "tomorrow")),
                    null),
                "ORDER_LINE_EXPIRY_INVALID"),
            new Case(
                "no condition",
                request(List.of(back(VARIANT, null)), good().newItems(), null),
                "ORDER_RETURN_CONDITION_REQUIRED"),
            new Case(
                "no such condition",
                request(List.of(back(VARIANT, "MINT")), good().newItems(), null),
                "ORDER_RETURN_CONDITION_INVALID"),
            new Case(
                "a returned item's id",
                request(List.of(back("v-1", "SEALED")), good().newItems(), null),
                "INVALID_UUID"),
            new Case(
                "the customer's id",
                request(good().returnItems(), good().newItems(), "c-1"),
                "INVALID_UUID"))) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> svc.exchange(TENANT, ORDER, c.req(), Ids.newId().toString(), ctx),
              c.what());
      assertEquals(400, e.status(), c.what());
      assertEquals(c.code(), e.code(), c.what());
    }
    verifyNoInteractions(repo, ctx);
  }

  /** A good body naming no sale of the business: 404, and no key is looked up. */
  @Test
  void aGoodBodyForNoSuchSaleIsNotFoundBeforeTheKey() {
    when(repo.findOrder(TENANT, ORDER)).thenReturn(Optional.empty());

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.exchange(TENANT, ORDER, good(), Ids.newId().toString(), ctx));

    assertEquals(404, e.status());
    assertEquals("ORDER_NOT_FOUND", e.code());
    verify(repo, never()).findReturnByKey(any(), any());
    verifyNoInteractions(ctx);
  }

  /** A good body for a sale at a store the caller is not held to: 403, and no key is looked up. */
  @Test
  void aGoodBodyAtAnotherStoreIsRefusedBeforeTheKey() {
    when(repo.findOrder(TENANT, ORDER)).thenReturn(Optional.of(sale()));
    doThrow(ApiException.forbidden("STORE_ACCESS_DENIED", "not your store"))
        .when(ctx)
        .requireStoreAccess(STORE);

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.exchange(TENANT, ORDER, good(), Ids.newId().toString(), ctx));

    assertEquals(403, e.status());
    assertEquals("STORE_ACCESS_DENIED", e.code());
    verify(repo, never()).findReturnByKey(any(), any());
  }

  private static Order sale() {
    return new Order(
        ORDER,
        TENANT,
        STORE,
        null,
        null,
        "POS",
        "INSTORE",
        "FULFILLED",
        BigDecimal.TEN,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.TEN,
        "USD",
        null,
        Ids.newId().toString(),
        java.time.Instant.now(),
        java.time.Instant.now(),
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        BigDecimal.ZERO,
        null,
        true);
  }
}
