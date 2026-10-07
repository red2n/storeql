package com.storeql.order.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.order.dto.Dtos.OrderItemRequest;
import com.storeql.order.dto.Dtos.PlaceOrderRequest;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The request's JSON must not change by a byte whichever way it is built (stage 0.12). */
class PlaceOrderRequestWireTest {

  private static final Jsonb JSON = JsonbBuilder.create();
  private static final String STORE = "0198a000-0000-7000-8000-000000000001";
  private static final String VARIANT = "0198a000-0000-7000-8000-000000000002";

  private static List<OrderItemRequest> items() {
    return List.of(
        new OrderItemRequest(
            VARIANT, BigDecimal.ONE, BigDecimal.TEN, null, null, null, null, null));
  }

  @Test
  void positionalAndBuiltRequestsSerialiseIdentically() {
    PlaceOrderRequest positional =
        new PlaceOrderRequest(
            STORE,
            null,
            "POS",
            "INSTORE",
            items(),
            null,
            new BigDecimal("1.50"),
            "loyal",
            "USD",
            "n",
            List.of("A"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "555",
            "CASH",
            null,
            null,
            true,
            null,
            null,
            null,
            null,
            null);
    PlaceOrderRequest built =
        PlaceOrderRequest.builder()
            .storeId(STORE)
            .channel("POS")
            .fulfilmentType("INSTORE")
            .items(items())
            .discountAmount(new BigDecimal("1.50"))
            .discountReason("loyal")
            .currency("USD")
            .notes("n")
            .couponCodes(List.of("A"))
            .contactPhone("555")
            .paymentMethod("CASH")
            .allowSubstitutions(true)
            .build();
    assertEquals(positional, built);
    assertEquals(JSON.toJson(positional), JSON.toJson(built));
    assertEquals(positional, built.toBuilder().build());
  }

  @Test
  void anOldJsonBodyStillDeserialises() {
    String old =
        "{\"storeId\":\""
            + STORE
            + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"DELIVERY\",\"items\":[{\"variantId\":\""
            + VARIANT
            + "\",\"qty\":2}],\"deliveryLine1\":\"1 High St\",\"deliveryCity\":\"Leeds\","
            + "\"slotWindowId\":\"w\",\"slotStartsAt\":\"2026-10-01T09:00:00Z\","
            + "\"rungUpBy\":null}";
    PlaceOrderRequest r = JSON.fromJson(old, PlaceOrderRequest.class);
    assertEquals(STORE, r.storeId());
    assertEquals("DELIVERY", r.fulfilmentType());
    assertEquals(0, new BigDecimal("2").compareTo(r.items().get(0).qty()));
    assertEquals("1 High St", r.deliveryLine1());
    assertEquals("w", r.slotWindowId());
    assertNull(r.rungUpBy());
    assertNull(r.customerId());
    assertTrue(JSON.toJson(r).contains("\"deliveryCity\":\"Leeds\""));
    assertFalse(JSON.toJson(r).contains("customerId"));
  }
}
