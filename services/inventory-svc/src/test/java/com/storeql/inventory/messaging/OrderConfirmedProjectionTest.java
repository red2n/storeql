package com.storeql.inventory.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.inventory.service.WaveService;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The confirmation that puts an order on the waiting list: a failure to write it propagates so the
 * consumer loop redelivers, while a malformed confirmation is skipped without a word to the
 * projection. Written after the review that found the write failure swallowed.
 */
class OrderConfirmedProjectionTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID ORDER = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID VARIANT = Ids.newId();

  private final List<String> projected = new ArrayList<>();
  private RuntimeException failWith;
  private OrderEventHandler handler;

  @BeforeEach
  void setUp() {
    handler = new OrderEventHandler();
    handler.waves =
        new WaveService() {
          @Override
          public boolean awaitConfirmedOnce(
              UUID eventId,
              UUID tenantId,
              UUID orderId,
              UUID storeId,
              String channel,
              String fulfilmentType,
              Instant confirmedAt,
              Map<UUID, BigDecimal> lines) {
            if (failWith != null) throw failWith;
            projected.add(orderId + " " + confirmedAt + " " + lines);
            return true;
          }
        };
  }

  private static String confirmed(String lines, String occurredAt) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"OrderConfirmed\","
        + (occurredAt == null ? "" : "\"occurredAt\":\"" + occurredAt + "\",")
        + "\"tenantId\":\""
        + TENANT
        + "\",\"orderId\":\""
        + ORDER
        + "\",\"storeId\":\""
        + STORE
        + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"DELIVERY\",\"lines\":["
        + lines
        + "]}";
  }

  @Test
  void aFailureToWriteTheProjectionPropagatesSoTheEventIsRedelivered() {
    failWith = new ApiException(503, "DB_UNAVAILABLE", "database unavailable", List.of());
    assertThrows(
        ApiException.class,
        () ->
            handler.handle(
                confirmed(
                    "{\"variantId\":\"" + VARIANT + "\",\"qty\":2}", "2026-09-20T09:00:00Z")));
  }

  @Test
  void aMalformedConfirmationIsSkippedAndAWellFormedOneKeepsItsTime() {
    assertDoesNotThrow(
        () -> handler.handle(confirmed("{\"variantId\":\"not-an-id\",\"qty\":2}", null)));
    assertEquals(0, projected.size());
    handler.handle(
        confirmed("{\"variantId\":\"" + VARIANT + "\",\"qty\":2}", "2026-09-20T09:00:00Z"));
    assertEquals(1, projected.size());
    assertEquals(ORDER + " 2026-09-20T09:00:00Z {" + VARIANT + "=2}", projected.get(0));
  }
}
