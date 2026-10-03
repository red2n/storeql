package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.order.dto.Dtos.ExchangeNewItemRequest;
import com.storeql.order.dto.Dtos.OrderItemRequest;
import com.storeql.order.dto.Dtos.PlaceOrderRequest;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A sale's quantities as they are counted, before anything is priced, held or written: three
 * places, never rounded — on any channel — except a till's floating-point noise and a label's finer
 * weight, which the till's line is counted at the gram below.
 */
class OrderQuantitiesTest {

  private static OrderItemRequest line(String qty) {
    return new OrderItemRequest(
        Ids.newId().toString(),
        new BigDecimal(qty),
        new BigDecimal("1.99"),
        "note",
        null,
        null,
        null,
        null);
  }

  private static PlaceOrderRequest sale(String channel, String... qty) {
    return PlaceOrderRequest.builder()
        .storeId(Ids.newId().toString())
        .channel(channel)
        .fulfilmentType("INSTORE")
        .items(java.util.Arrays.stream(qty).map(OrderQuantitiesTest::line).toList())
        .discountAmount(new BigDecimal("0.10"))
        .discountReason("scuffed")
        .build();
  }

  @Test
  void quantitiesToThreePlacesLeaveTheRequestAsItWas() {
    PlaceOrderRequest pos = sale("POS", "1", "0.375", "2.50");
    assertSame(pos, OrderService.countedQuantities(pos));
    PlaceOrderRequest online = sale("ONLINE", "3", "0.001");
    assertSame(online, OrderService.countedQuantities(online));
  }

  /** Two weighings of 0.1 kg and 0.2 kg added on the till post as 0.30000000000000004. */
  @Test
  void aTillsAddedWeighingsAreTheQuantityTheyStandFor() {
    PlaceOrderRequest pos = sale("POS", "1", "0.30000000000000004");

    PlaceOrderRequest counted = OrderService.countedQuantities(pos);

    assertEquals(new BigDecimal("0.300"), counted.items().get(1).qty());
    assertSame(pos.items().get(0), counted.items().get(0));
    // Everything else about the line and the sale is as sent.
    assertEquals(pos.items().get(1).variantId(), counted.items().get(1).variantId());
    assertEquals(pos.items().get(1).unitPrice(), counted.items().get(1).unitPrice());
    assertEquals("note", counted.items().get(1).notes());
    assertEquals(pos.storeId(), counted.storeId());
    assertEquals(pos.discountAmount(), counted.discountAmount());
    assertEquals(pos.discountReason(), counted.discountReason());
  }

  /**
   * A pack's label says 0.37512 kg (GS1 AI 3105) and the till sells the line at that reading: the
   * line is 0.375 kg, the gram below, before anything is priced, held or written — and the sale, or
   * its offline replay, is not refused over a label.
   */
  @Test
  void aTillsLabelWeightIsCountedAtTheGramBelow() {
    PlaceOrderRequest pos = sale("POS", "1", "0.37512", "0.3755");

    PlaceOrderRequest counted = OrderService.countedQuantities(pos);

    assertEquals(new BigDecimal("0.375"), counted.items().get(1).qty());
    assertEquals(new BigDecimal("0.375"), counted.items().get(2).qty());
    assertSame(pos.items().get(0), counted.items().get(0));
  }

  @Test
  void aQuantityFinerThanThreePlacesIsRefusedOnEveryChannelNeverRounded() {
    for (String[] c :
        List.of(
            // Past a reading's six places, and no double's noise around one.
            new String[] {"POS", "0.3755123"},
            new String[] {"POS", "1.0000001"},
            // Under a gram is nothing at the gram.
            new String[] {"POS", "0.0004"},
            new String[] {"ONLINE", "0.3755"},
            // Online is no till: a label's finer weight is nobody's there.
            new String[] {"ONLINE", "0.37512"},
            // Online is no till: a double's noise there is a quantity nobody counted.
            new String[] {"ONLINE", "0.30000000000000004"})) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> OrderService.countedQuantities(sale(c[0], "1", c[1])),
              c[0] + " " + c[1]);
      assertEquals(400, e.status());
      assertEquals("VALIDATION_FAILED", e.code());
      assertEquals(true, e.details().get(0).startsWith("items[1].qty:"), e.details().toString());
    }
  }

  // ── an exchange's new items: the till's scanner, as on a sale ────────────

  private static ExchangeNewItemRequest bought(String qty) {
    return new ExchangeNewItemRequest(
        Ids.newId().toString(), new BigDecimal(qty), null, null, null, null);
  }

  /**
   * The till builds an exchange's new items with its scanner, as it builds a sale's (the Returns
   * screen's scanBarcode: a pack's label weight, 0.37512, as read), and the new sale is a till
   * sale: each is counted as a till's line — at the gram below — not refused.
   */
  @Test
  void anExchangesNewItemIsCountedAsATillsLine() {
    List<ExchangeNewItemRequest> sent =
        List.of(bought("2"), bought("0.37512"), bought("0.30000000000000004"), bought("0.375"));

    List<OrderItemRequest> lines = OrderService.exchangeSaleLines(sent);

    assertEquals(new BigDecimal("2"), lines.get(0).qty());
    assertEquals(new BigDecimal("0.375"), lines.get(1).qty());
    assertEquals(new BigDecimal("0.300"), lines.get(2).qty());
    assertEquals(new BigDecimal("0.375"), lines.get(3).qty());
    for (int i = 0; i < sent.size(); i++) {
      assertEquals(sent.get(i).variantId(), lines.get(i).variantId());
      // The new sale is priced by the server, never by anything the exchange carries.
      assertEquals(null, lines.get(i).unitPrice());
    }
  }

  /** Finer than any reading, or under a gram: refused, naming the exchange's own field. */
  @Test
  void anExchangesNewItemFinerThanAReadingIsRefusedNamingIt() {
    for (String qty : List.of("0.3755123", "1.0000001", "0.0004")) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> OrderService.exchangeSaleLines(List.of(bought("1"), bought(qty))),
              qty);
      assertEquals(400, e.status());
      assertEquals("VALIDATION_FAILED", e.code());
      assertEquals(true, e.details().get(0).startsWith("newItems[1].qty:"), e.details().toString());
    }
  }
}
