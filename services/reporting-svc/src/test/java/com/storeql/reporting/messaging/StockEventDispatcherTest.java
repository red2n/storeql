package com.storeql.reporting.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.storeql.ids.Ids;
import com.storeql.reporting.service.ReportingService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * StockEventDispatcher reads a shipped transfer's lines and its transfer order, and a receipt's
 * transfer order and business, out of the events as inventory-svc writes them, and skips what
 * cannot be read without throwing (so the consumer loop acks it). A capturing subclass stands in
 * for {@link ReportingService}; what the database does with them is {@code TransferSupplyLinesIT}.
 */
class StockEventDispatcherTest {

  private static final String SHIPPED = "storeql.inventory.transfer-order-shipped";
  private static final String RECEIVED = "storeql.inventory.transfer-order-received";

  private static final UUID TENANT = Ids.newId();
  private static final UUID TRANSFER = Ids.newId();
  private static final UUID FROM = Ids.newId();
  private static final UUID TO = Ids.newId();
  private static final UUID EVENT = Ids.newId();
  private static final UUID COLA = Ids.newId();
  private static final UUID CRISPS = Ids.newId();

  private static final class CapturingService extends ReportingService {
    int shipments;
    UUID tenantId;
    UUID transferOrderId;
    UUID eventId;
    String consumer;
    UUID fromStoreId;
    UUID toStoreId;
    List<UUID> variantIds;
    List<BigDecimal> qtys;
    int receipts;
    UUID receivedTenantId;
    UUID receivedTransferOrderId;

    @Override
    public boolean applyTransferShippedOnce(
        UUID tenantId,
        UUID transferOrderId,
        UUID eventId,
        String consumerName,
        UUID fromStoreId,
        UUID toStoreId,
        List<UUID> variantIds,
        List<BigDecimal> qtys) {
      this.shipments++;
      this.tenantId = tenantId;
      this.transferOrderId = transferOrderId;
      this.eventId = eventId;
      this.consumer = consumerName;
      this.fromStoreId = fromStoreId;
      this.toStoreId = toStoreId;
      this.variantIds = variantIds;
      this.qtys = qtys;
      return true;
    }

    @Override
    public void applyTransferReceived(UUID tenantId, UUID transferOrderId) {
      this.receipts++;
      this.receivedTenantId = tenantId;
      this.receivedTransferOrderId = transferOrderId;
    }
  }

  private CapturingService service;
  private StockEventDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    service = new CapturingService();
    dispatcher = new StockEventDispatcher();
    dispatcher.service = service;
  }

  /** The member order inventory-svc writes: the lines, then the transfer's type. */
  private static String shipped(String lines, String typeMember) {
    return "{\"eventId\":\""
        + EVENT
        + "\",\"eventType\":\"TransferOrderShipped\",\"tenantId\":\""
        + TENANT
        + "\",\"aggregateId\":\""
        + TRANSFER
        + "\",\"occurredAt\":\"2026-10-06T09:00:00Z\",\"fromStoreId\":\""
        + FROM
        + "\",\"toStoreId\":\""
        + TO
        + "\",\"lines\":"
        + lines
        + typeMember
        + "}";
  }

  private static String line(UUID variant, String qty) {
    return "{\"variantId\":\"" + variant + "\",\"qty\":" + qty + "}";
  }

  private static final String IN_TRANSIT = ",\"transferType\":\"INTRANSIT\"";

  @Test
  void anInTransitShipmentCarriesItsLinesAndItsTransferToTheProjection() {
    dispatcher.dispatch(
        SHIPPED, shipped("[" + line(COLA, "6") + "," + line(CRISPS, "2.5") + "]", IN_TRANSIT));

    assertEquals(1, service.shipments);
    assertEquals(TENANT, service.tenantId);
    assertEquals(TRANSFER, service.transferOrderId);
    assertEquals(EVENT, service.eventId);
    assertEquals("reporting-svc/stock-events", service.consumer);
    assertEquals(FROM, service.fromStoreId);
    assertEquals(TO, service.toStoreId);
    assertEquals(List.of(COLA, CRISPS), service.variantIds);
    assertEquals(2, service.qtys.size());
    assertEquals(0, new BigDecimal("6").compareTo(service.qtys.get(0)));
    assertEquals(0, new BigDecimal("2.5").compareTo(service.qtys.get(1)));
  }

  @Test
  void aTransferThatLandsAsItShipsOpensNothing() {
    dispatcher.dispatch(
        SHIPPED, shipped("[" + line(COLA, "6") + "]", ",\"transferType\":\"DIRECT\""));
    // inventory-svc before it named the type: not told apart from a DIRECT one, so not opened.
    dispatcher.dispatch(SHIPPED, shipped("[" + line(COLA, "6") + "]", ""));

    assertEquals(0, service.shipments);
  }

  @Test
  void aShipmentWithALineThatCannotBeReadIsSkippedWhole() {
    String[] unreadable = {
      "[" + line(COLA, "6") + ",{\"variantId\":\"not-an-id\",\"qty\":1}]",
      "[" + line(COLA, "6") + ",{\"variantId\":\"" + CRISPS + "\"}]",
      "[" + line(COLA, "6") + ",{\"variantId\":\"" + CRISPS + "\",\"qty\":\"two\"}]",
      "[" + line(COLA, "6") + "," + line(CRISPS, "0") + "]",
      "[" + line(COLA, "6") + "," + line(CRISPS, "-1") + "]",
      // a variant id of another version is no id of ours
      "[{\"variantId\":\"7d444840-9dc0-4f6b-9a1e-1c0f5c2b8a3e\",\"qty\":1}]",
      "\"none\"",
    };
    for (String lines : unreadable) {
      dispatcher.dispatch(SHIPPED, shipped(lines, IN_TRANSIT));
    }
    // no lines at all
    dispatcher.dispatch(SHIPPED, shipped("null", IN_TRANSIT));

    assertEquals(0, service.shipments);
  }

  @Test
  void aShipmentWithoutItsTransferOrBusinessIsSkipped() {
    String noTransfer =
        shipped("[" + line(COLA, "6") + "]", IN_TRANSIT).replace("aggregateId", "x");
    String noTenant = shipped("[" + line(COLA, "6") + "]", IN_TRANSIT).replace("tenantId", "x");

    dispatcher.dispatch(SHIPPED, noTransfer);
    dispatcher.dispatch(SHIPPED, noTenant);

    assertEquals(0, service.shipments);
  }

  @Test
  void aReceiptNamesItsBusinessAndTransferNotItsOwnEventId() {
    UUID receiptEvent = Ids.newId();
    String json =
        "{\"eventId\":\""
            + receiptEvent
            + "\",\"eventType\":\"TransferOrderReceived\",\"tenantId\":\""
            + TENANT
            + "\",\"aggregateId\":\""
            + TRANSFER
            + "\",\"occurredAt\":\"2026-10-06T10:00:00Z\",\"fromStoreId\":\""
            + FROM
            + "\",\"toStoreId\":\""
            + TO
            + "\",\"lines\":["
            + line(COLA, "6")
            + "]}";

    dispatcher.dispatch(RECEIVED, json);

    assertEquals(1, service.receipts);
    assertEquals(TENANT, service.receivedTenantId);
    assertEquals(TRANSFER, service.receivedTransferOrderId);
  }

  @Test
  void aReceiptWithoutItsBusinessOrTransferIsSkipped() {
    String noTenant =
        "{\"eventId\":\"" + Ids.newId() + "\",\"aggregateId\":\"" + TRANSFER + "\",\"lines\":[]}";
    String noTransfer =
        "{\"eventId\":\"" + Ids.newId() + "\",\"tenantId\":\"" + TENANT + "\",\"lines\":[]}";

    dispatcher.dispatch(RECEIVED, noTenant);
    dispatcher.dispatch(RECEIVED, noTransfer);

    assertEquals(0, service.receipts);
    assertNull(service.receivedTenantId);
  }
}
