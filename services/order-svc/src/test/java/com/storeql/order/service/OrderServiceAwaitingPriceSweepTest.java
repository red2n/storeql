package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.Order;
import com.storeql.order.repo.OrderRepository;
import com.storeql.order.repo.OrderRepository.AwaitingPriceRef;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The sweep of orders waiting for a price: flag at the first limit, cancel at the second. */
@ExtendWith(MockitoExtension.class)
class OrderServiceAwaitingPriceSweepTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();

  @Mock OrderRepository repo;

  private OrderService svc;

  @BeforeEach
  void setUp() {
    svc = new OrderService();
    svc.repo = repo;
  }

  private static AwaitingPriceRef due(UUID order, boolean cancel) {
    return new AwaitingPriceRef(
        TENANT, order, STORE, "POS", "INSTORE", BigDecimal.ZERO, Instant.now(), cancel);
  }

  @Test
  void firstLimitFlagsOnceAndWritesTheOverdueEvent() {
    UUID order = Ids.newId();
    when(repo.findAwaitingPriceDue(200)).thenReturn(List.of(due(order, false)));
    when(repo.flagPriceOverdue(eq(TENANT), eq(order), any())).thenReturn(true);

    assertEquals(1, svc.sweepAwaitingPriceOrders(200));

    var event = ArgumentCaptor.forClass(OutboxRow.class);
    verify(repo).flagPriceOverdue(eq(TENANT), eq(order), event.capture());
    assertEquals("OrderPriceOverdue", event.getValue().eventType());
    assertEquals("storeql.order.price-overdue", event.getValue().topic());
    assertEquals(true, event.getValue().payload().contains("\"eventId\""));
    verify(repo, never()).transitionOrderStatus(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void anAlreadyFlaggedOrderIsNotCounted() {
    UUID order = Ids.newId();
    when(repo.findAwaitingPriceDue(200)).thenReturn(List.of(due(order, false)));
    when(repo.flagPriceOverdue(eq(TENANT), eq(order), any())).thenReturn(false);
    assertEquals(0, svc.sweepAwaitingPriceOrders(200));
  }

  @Test
  void secondLimitCancelsThroughTheOrdinaryTransitionAndAnnouncesTheCancel() {
    UUID order = Ids.newId();
    when(repo.findAwaitingPriceDue(200)).thenReturn(List.of(due(order, true)));

    assertEquals(1, svc.sweepAwaitingPriceOrders(200));

    var event = ArgumentCaptor.forClass(OutboxRow.class);
    verify(repo)
        .transitionOrderStatus(
            eq(TENANT),
            eq(order),
            eq(Order.STATUS_AWAITING_PRICE),
            eq(Order.STATUS_CANCELLED),
            eq("PRICE_WAIT_EXPIRED"),
            eq(null),
            event.capture());
    assertEquals("OrderCancelled", event.getValue().eventType());
    assertEquals(true, event.getValue().payload().contains("PRICE_WAIT_EXPIRED"));
    verify(repo, never()).flagPriceOverdue(any(), any(), any());
  }

  @Test
  void anOrderPricedByAManagerAtTheSameMomentIsLeftAlone() {
    UUID order = Ids.newId();
    when(repo.findAwaitingPriceDue(200)).thenReturn(List.of(due(order, true)));
    when(repo.transitionOrderStatus(any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(ApiException.notFound("ORDER_NOT_FOUND_OR_WRONG_STATUS", "priced meanwhile"));
    assertEquals(0, svc.sweepAwaitingPriceOrders(200));
  }
}
