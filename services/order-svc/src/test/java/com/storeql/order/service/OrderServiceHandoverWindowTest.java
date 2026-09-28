package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.storeql.ids.Ids;
import com.storeql.order.repo.OrderRepository;
import com.storeql.web.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Handed over when (ship-from-store): a window on the handover time is a question about handed-over
 * orders, so on its own it means handover=DONE, and asked of the orders not yet handed over it is
 * refused before anything is read; the creation-time bounds pass through untouched.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceHandoverWindowTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final Instant MIDNIGHT = Instant.parse("2026-09-25T23:00:00Z");
  private static final Instant NEXT_MIDNIGHT = Instant.parse("2026-09-26T23:00:00Z");

  @Mock OrderRepository repo;

  private OrderService svc;

  @BeforeEach
  void setUp() {
    svc = new OrderService();
    svc.repo = repo;
  }

  @Test
  void aWindowAloneMeansHandedOver() {
    svc.listOrders(
        TENANT, STORE, null, null, null, null, null, null, MIDNIGHT, null, null, null, null, 20);
    verify(repo)
        .listOrders(
            TENANT, STORE, null, null, null, null, null, true, MIDNIGHT, null, null, null, null,
            null, 21, false);
  }

  @Test
  void aWindowWithDoneKeepsBothBoundsAndLeavesCreationAlone() {
    svc.listOrders(
        TENANT,
        STORE,
        null,
        null,
        null,
        null,
        null,
        true,
        MIDNIGHT,
        NEXT_MIDNIGHT,
        MIDNIGHT,
        null,
        null,
        20);
    verify(repo)
        .listOrders(
            TENANT,
            STORE,
            null,
            null,
            null,
            null,
            null,
            true,
            MIDNIGHT,
            NEXT_MIDNIGHT,
            MIDNIGHT,
            null,
            null,
            null,
            21,
            false);
  }

  @Test
  void aWindowOnTheOrdersNotYetHandedOverIsRefusedBeforeAnyRead() {
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.listOrders(
                    TENANT, STORE, null, null, null, null, null, false, null, MIDNIGHT, null, null,
                    null, 20));
    assertEquals(400, e.status());
    assertEquals("ORDER_HANDOVER_FILTER_INVALID", e.code());
    verifyNoInteractions(repo);
  }

  @Test
  void noWindowLeavesTheHandoverFilterAsAsked() {
    svc.listOrders(
        TENANT, STORE, null, null, null, null, null, false, null, null, null, null, null, 20);
    verify(repo)
        .listOrders(
            TENANT, STORE, null, null, null, null, null, false, null, null, null, null, null, null,
            21, false);
  }
}
