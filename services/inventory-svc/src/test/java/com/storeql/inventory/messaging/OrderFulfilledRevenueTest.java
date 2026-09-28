package com.storeql.inventory.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.Reservation;
import com.storeql.inventory.service.InventoryService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A fulfilled line's revenue reaches the deduction it pays for (19.7), and a bad figure never stops
 * the deduction.
 */
class OrderFulfilledRevenueTest {

  private static final UUID EVENT = Ids.parse("01a090ae-611e-705b-8bb0-8fccd45e4202");
  private static final UUID TENANT = Ids.parse("01a090ae-611e-700a-9f77-b94950c4c25b");
  private static final UUID ORDER = Ids.parse("01a090ae-611e-700e-89dd-b0cb0b3011aa");
  private static final UUID STORE = Ids.parse("01a090ae-611e-7010-be82-c788cf35ea1d");
  private static final UUID VARIANT = Ids.parse("01a090ae-611e-7012-a14d-4f924d594f01");

  /** Records each deduction with the revenue it was given; nothing is held for the order. */
  static final class RecordingService extends InventoryService {
    final List<String> deducted = new ArrayList<>();

    @Override
    public List<Reservation> heldReservationsByOrder(UUID tenantId, UUID orderId) {
      return List.of();
    }

    @Override
    public boolean deductSaleFromOrderOnce(
        UUID dedupeId,
        String consumerName,
        UUID tenantId,
        UUID storeId,
        UUID variantId,
        BigDecimal qty,
        UUID orderId,
        BigDecimal netAmount) {
      deducted.add(qty.toPlainString() + " " + (netAmount == null ? "unpriced" : netAmount));
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
    // No wave picked these lines: the ordinary path runs, and the projection is not consulted.
    handler.waves =
        new com.storeql.inventory.service.WaveService() {
          @Override
          public BigDecimal pickedByWave(
              UUID dedupeId,
              String consumerName,
              UUID tenantId,
              UUID storeId,
              UUID variantId,
              BigDecimal qty,
              UUID orderId,
              BigDecimal netAmount) {
            return BigDecimal.ZERO;
          }

          @Override
          public void fulfilledByHand(
              UUID tenantId, UUID orderId, UUID variantId, BigDecimal qty) {}
        };
  }

  private void fulfil(String netAmountJson) {
    handler.handle(
        "{\"eventId\":\""
            + EVENT
            + "\",\"eventType\":\"OrderFulfilled\",\"tenantId\":\""
            + TENANT
            + "\",\"orderId\":\""
            + ORDER
            + "\",\"storeId\":\""
            + STORE
            + "\",\"items\":[{\"variantId\":\""
            + VARIANT
            + "\",\"qty\":2"
            + netAmountJson
            + "}]}");
  }

  @Test
  @DisplayName("The line's net revenue is recorded with its deduction, at the scale it was sent")
  void revenueTravelsWithTheDeduction() {
    fulfil(",\"netAmount\":1250");
    fulfil(",\"netAmount\":19.90");
    assertEquals(List.of("2 1250", "2 19.90"), service.deducted);
  }

  @Test
  @DisplayName("A line from before 19.7, or with a null, is deducted as unpriced")
  void absentRevenueIsUnpriced() {
    fulfil("");
    fulfil(",\"netAmount\":null");
    assertEquals(List.of("2 unpriced", "2 unpriced"), service.deducted);
  }

  @Test
  @DisplayName("A forged or malformed revenue figure is dropped; the stock is still deducted")
  void badRevenueNeverBlocksTheDeduction() {
    fulfil(",\"netAmount\":\"19.90\"");
    fulfil(",\"netAmount\":-5");
    fulfil(",\"netAmount\":{\"amount\":1}");
    fulfil(",\"netAmount\":\"1e999999999\"");
    assertEquals(List.of("2 unpriced", "2 unpriced", "2 unpriced", "2 unpriced"), service.deducted);
  }
}
