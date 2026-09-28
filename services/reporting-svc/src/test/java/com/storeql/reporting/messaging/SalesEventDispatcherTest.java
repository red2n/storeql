package com.storeql.reporting.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.storeql.ids.Ids;
import com.storeql.reporting.domain.Domain.SaleLine;
import com.storeql.reporting.service.ReportingService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SalesEventDispatcher routes OrderConfirmed → recordSale, PaymentRefunded → applySalesRefund and
 * OrderVoided → applySaleVoided, and skips malformed/unknown events without throwing (so the
 * consumer loop acks them). reporting-svc has no mocking framework, so a capturing subclass stands
 * in for {@link ReportingService}.
 */
class SalesEventDispatcherTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID ORDER = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();
  private static final UUID EVENT = Ids.newId();

  private static final class CapturingService extends ReportingService {
    int sales;
    int refunds;
    UUID orderId;
    UUID storeId;
    String channel;
    UUID customerId;
    BigDecimal gross;
    String currency;
    List<SaleLine> lines;
    UUID refundEventId;
    BigDecimal refundAmount;
    int voids;
    UUID voidEventId;
    String voidConsumer;
    UUID voidTenantId;
    UUID voidOrderId;

    @Override
    public void recordSale(
        UUID tenantId,
        UUID orderId,
        UUID storeId,
        String channel,
        UUID customerId,
        BigDecimal gross,
        String currency,
        List<SaleLine> lines) {
      this.sales++;
      this.lines = lines;
      this.orderId = orderId;
      this.storeId = storeId;
      this.channel = channel;
      this.customerId = customerId;
      this.gross = gross;
      this.currency = currency;
    }

    @Override
    public void applySalesRefund(
        UUID eventId, String consumer, UUID tenantId, UUID orderId, BigDecimal amount) {
      this.refunds++;
      this.refundEventId = eventId;
      this.refundAmount = amount;
    }

    @Override
    public void applySaleVoided(UUID eventId, String consumer, UUID tenantId, UUID orderId) {
      this.voids++;
      this.voidEventId = eventId;
      this.voidConsumer = consumer;
      this.voidTenantId = tenantId;
      this.voidOrderId = orderId;
    }
  }

  private CapturingService service;
  private SalesEventDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    service = new CapturingService();
    dispatcher = new SalesEventDispatcher();
    dispatcher.service = service;
  }

  @Test
  void orderConfirmedRecordsASale() {
    String json =
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"storeId\":\""
            + STORE
            + "\",\"channel\":\"ONLINE\",\"customerId\":\""
            + CUSTOMER
            + "\",\"total\":100.00,\"currency\":\"GBP\"}";

    dispatcher.dispatch("storeql.order.order-confirmed", json);

    assertEquals(1, service.sales);
    assertEquals(ORDER, service.orderId);
    assertEquals(STORE, service.storeId);
    assertEquals("ONLINE", service.channel);
    assertEquals(CUSTOMER, service.customerId);
    assertEquals(new BigDecimal("100.00"), service.gross);
    assertEquals("GBP", service.currency);
  }

  /** Sales by category needs the sale line by line: each line's variant, quantity and money. */
  @Test
  void orderConfirmedCarriesItsLinesToTheProjection() {
    UUID v1 = Ids.newId();
    UUID v2 = Ids.newId();
    String json =
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"storeId\":\""
            + STORE
            + "\",\"channel\":\"POS\",\"customerId\":null,\"total\":5.50,\"currency\":\"GBP\","
            + "\"lines\":[{\"variantId\":\""
            + v1
            + "\",\"qty\":2,\"unitPrice\":2.00,\"lineTotal\":4.00},{\"variantId\":\""
            + v2
            + "\",\"qty\":1.500,\"lineTotal\":1.50}]}";

    dispatcher.dispatch("storeql.order.order-confirmed", json);

    assertEquals(1, service.sales);
    assertEquals(2, service.lines.size());
    assertEquals(v1, service.lines.get(0).variantId());
    assertEquals(new BigDecimal("2"), service.lines.get(0).qty());
    assertEquals(new BigDecimal("2.00"), service.lines.get(0).unitPrice());
    assertEquals(new BigDecimal("4.00"), service.lines.get(0).lineTotal());
    assertEquals(v2, service.lines.get(1).variantId());
    assertNull(service.lines.get(1).unitPrice(), "a line priced off-platform has no unit price");
    assertEquals(new BigDecimal("1.50"), service.lines.get(1).lineTotal());
  }

  /** An event minted before lines existed is still a sale, with nothing to say by category. */
  @Test
  void anOlderOrderConfirmedWithoutLinesIsASaleWithNone() {
    String json =
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"storeId\":\""
            + STORE
            + "\",\"channel\":\"POS\",\"customerId\":null,\"total\":12.50,\"currency\":\"GBP\"}";

    dispatcher.dispatch("storeql.order.order-confirmed", json);

    assertEquals(1, service.sales);
    assertEquals(List.of(), service.lines);
  }

  @Test
  void guestOrderConfirmedRecordsASaleWithNullCustomer() {
    String json =
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"OrderConfirmed\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"storeId\":\""
            + STORE
            + "\",\"channel\":\"POS\",\"customerId\":null,\"total\":12.50,\"currency\":\"GBP\"}";

    dispatcher.dispatch("storeql.order.order-confirmed", json);

    assertEquals(1, service.sales);
    assertNull(service.customerId);
  }

  @Test
  void paymentRefundedAppliesARefund() {
    String json =
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"PaymentRefunded\",\"tenantId\":\""
            + TENANT
            + "\",\"refundId\":\""
            + Ids.newId()
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"amount\":25.00}";

    dispatcher.dispatch("storeql.payment.payment-refunded", json);

    assertEquals(1, service.refunds);
    assertEquals(EVENT, service.refundEventId);
    assertEquals(new BigDecimal("25.00"), service.refundAmount);
  }

  @Test
  void malformedJsonIsSkippedWithoutThrowing() {
    dispatcher.dispatch("storeql.order.order-confirmed", "{not valid json");

    assertEquals(0, service.sales);
    assertEquals(0, service.refunds);
  }

  private static String orderVoided(String eventId, String tenantId, String orderId) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"OrderVoided\",\"tenantId\":\""
        + tenantId
        + "\",\"orderId\":\""
        + orderId
        + "\",\"storeId\":\""
        + STORE
        + "\",\"items\":[{\"variantId\":\""
        + Ids.newId()
        + "\",\"qty\":2}]}";
  }

  /**
   * A voided till sale leaves the sales projection: the void is keyed on its own event id (so a
   * redelivery is recognised) and names the tenant and order exactly as order-svc published them.
   */
  @Test
  void orderVoidedVoidsTheSale() {
    dispatcher.dispatch(
        "storeql.order.order-voided",
        orderVoided(EVENT.toString(), TENANT.toString(), ORDER.toString()));

    assertEquals(1, service.voids);
    assertEquals(EVENT, service.voidEventId);
    assertEquals("reporting-svc/sales-events", service.voidConsumer);
    assertEquals(TENANT, service.voidTenantId);
    assertEquals(ORDER, service.voidOrderId);
    assertEquals(0, service.sales, "a void records no sale");
    assertEquals(0, service.refunds, "a void is not a refund");
  }

  /** A void that was never handed over restocks nothing, and is still a void. */
  @Test
  void orderVoidedWithNothingToRestockStillVoidsTheSale() {
    String json =
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"OrderVoided\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"storeId\":\""
            + STORE
            + "\",\"items\":[]}";

    dispatcher.dispatch("storeql.order.order-voided", json);

    assertEquals(1, service.voids);
    assertEquals(ORDER, service.voidOrderId);
  }

  /**
   * A void that cannot say which event it is, whose business, or which sale, is skipped rather than
   * guessed at: it could never be recognised on redelivery, nor pinned to one business's sale.
   */
  @Test
  void anUnreadableOrderVoidedIsSkippedWithoutThrowing() {
    String noEventId =
        "{\"eventType\":\"OrderVoided\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\"}";
    dispatcher.dispatch("storeql.order.order-voided", noEventId);
    // Not a UUIDv7 (a v4): read with Ids.parse, refused like everywhere else.
    dispatcher.dispatch(
        "storeql.order.order-voided",
        orderVoided(EVENT.toString(), TENANT.toString(), "3f2b8e1c-9a4d-4c2e-8f7a-1b2c3d4e5f60"));
    dispatcher.dispatch(
        "storeql.order.order-voided",
        orderVoided(EVENT.toString(), "not-a-uuid", ORDER.toString()));
    dispatcher.dispatch("storeql.order.order-voided", "{not valid json");

    assertEquals(0, service.voids);
    assertEquals(0, service.sales);
    assertEquals(0, service.refunds);
  }
}
