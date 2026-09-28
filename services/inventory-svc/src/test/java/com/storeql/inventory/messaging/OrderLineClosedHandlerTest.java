package com.storeql.inventory.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.storeql.ids.Ids;
import com.storeql.inventory.service.InventoryService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A line closed short or substituted (substitutions for out-of-stock online lines) gives that much
 * of its hold back, on the event's id: the closed line's own variant, or the replaced one's; a
 * malformed or empty event is skipped, not thrown.
 */
class OrderLineClosedHandlerTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID ORDER = Ids.newId();
  private static final UUID APPLES = Ids.newId();
  private static final UUID PEARS = Ids.newId();

  static final class RecordingService extends InventoryService {
    final List<String> closed = new ArrayList<>();

    @Override
    public boolean lineClosedOnce(
        UUID eventId,
        String consumerName,
        UUID tenantId,
        UUID orderId,
        UUID variantId,
        BigDecimal qty) {
      closed.add(
          consumerName
              + " "
              + tenantId
              + " "
              + orderId
              + " "
              + variantId
              + " "
              + qty.toPlainString()
              + " "
              + eventId);
      return true;
    }
  }

  private RecordingService service;
  private OrderEventHandler handler;

  @BeforeEach
  void setUp() {
    service = new RecordingService();
    handler = new OrderEventHandler();
    handler.service = service;
  }

  private static String event(String type, UUID eventId, String lineFields) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\""
        + type
        + "\",\"occurredAt\":\"2026-09-25T10:00:00Z\",\"tenantId\":\""
        + TENANT
        + "\",\"orderId\":\""
        + ORDER
        + "\",\"storeId\":\""
        + Ids.newId()
        + "\",\"customerId\":null,\"loginId\":null,\"currency\":\"GBP\",\"orderTotal\":10.00,"
        + "\"channel\":\"ONLINE\",\"fulfilmentType\":\"DELIVERY\","
        + lineFields
        + "}";
  }

  @Test
  void aClosedLineGivesBackItsOwnVariantAndASubstitutedOneTheReplacedVariant() {
    UUID e1 = Ids.newId();
    handler.handle(
        event(
            "OrderLineShortClosed",
            e1,
            "\"variantId\":\""
                + APPLES
                + "\",\"variantName\":\"Apples\",\"qty\":1.000,\"refundAmount\":2.40"));
    UUID e2 = Ids.newId();
    handler.handle(
        event(
            "OrderLineSubstituted",
            e2,
            "\"fromVariantId\":\""
                + APPLES
                + "\",\"fromName\":\"Apples\",\"toVariantId\":\""
                + PEARS
                + "\",\"toName\":\"Pears\",\"qty\":2,\"chargedAmount\":4.80,\"refundAmount\":0"));
    assertEquals(
        List.of(
            OrderEventHandler.CONSUMER_NAME
                + " "
                + TENANT
                + " "
                + ORDER
                + " "
                + APPLES
                + " 1.000 "
                + e1,
            OrderEventHandler.CONSUMER_NAME
                + " "
                + TENANT
                + " "
                + ORDER
                + " "
                + APPLES
                + " 2 "
                + e2),
        service.closed);
  }

  @Test
  void nothingOrNonsenseIsSkippedNotThrown() {
    handler.handle(
        event("OrderLineShortClosed", Ids.newId(), "\"variantId\":\"" + APPLES + "\",\"qty\":0"));
    handler.handle(event("OrderLineShortClosed", Ids.newId(), "\"qty\":1"));
    handler.handle(
        event("OrderLineSubstituted", Ids.newId(), "\"fromVariantId\":\"apples\",\"qty\":1"));
    handler.handle("{\"eventType\":\"OrderLineShortClosed\"}");
    assertEquals(List.of(), service.closed);
  }
}
