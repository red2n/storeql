package com.storeql.order.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.order.dto.Dtos.FulfilRequest;
import com.storeql.order.repo.OrderRepository;
import com.storeql.order.service.OrderService;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A wave picked at the store fulfils the orders it names, for the quantities picked, once — exactly
 * as the Fulfil button does. Written before the code.
 */
@ExtendWith(MockitoExtension.class)
class WavePickedHandlerTest {

  private static final UUID EVENT = Ids.newId();
  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID WAVE = Ids.newId();
  private static final UUID ORDER_1 = Ids.newId();
  private static final UUID ORDER_2 = Ids.newId();
  private static final UUID APPLES = Ids.newId();
  private static final UUID PEARS = Ids.newId();

  @Mock OrderRepository repo;
  @Mock OrderService svc;
  private WavePickedHandler handler;

  @BeforeEach
  void setUp() {
    handler = new WavePickedHandler();
    handler.repo = repo;
    handler.svc = svc;
  }

  private static String payload() {
    return "{\"eventId\":\""
        + EVENT
        + "\",\"eventType\":\"WavePicked\",\"tenantId\":\""
        + TENANT
        + "\",\"aggregateId\":\""
        + WAVE
        + "\",\"waveId\":\""
        + WAVE
        + "\",\"storeId\":\""
        + STORE
        + "\",\"orders\":[{\"orderId\":\""
        + ORDER_1
        + "\",\"lines\":[{\"variantId\":\""
        + APPLES
        + "\",\"qty\":3},{\"variantId\":\""
        + PEARS
        + "\",\"qty\":2}]},{\"orderId\":\""
        + ORDER_2
        + "\",\"lines\":[{\"variantId\":\""
        + APPLES
        + "\",\"qty\":3}]}]}";
  }

  @Test
  void eachOrderNamedIsFulfilledForThePickedQuantitiesOnce() {
    when(svc.fulfilOrderOnce(any(), eq(WavePickedHandler.CONSUMER), eq(TENANT), any(), any()))
        .thenReturn(true);
    handler.handle(payload());
    ArgumentCaptor<FulfilRequest> req = ArgumentCaptor.forClass(FulfilRequest.class);
    UUID dedupe1 = Ids.derived(EVENT, ORDER_1.toString());
    verify(svc)
        .fulfilOrderOnce(
            eq(dedupe1), eq(WavePickedHandler.CONSUMER), eq(TENANT), eq(ORDER_1), req.capture());
    verify(svc)
        .fulfilOrderOnce(
            eq(Ids.derived(EVENT, ORDER_2.toString())),
            eq(WavePickedHandler.CONSUMER),
            eq(TENANT),
            eq(ORDER_2),
            any());
    org.junit.jupiter.api.Assertions.assertEquals(2, req.getValue().lines().size());
    org.junit.jupiter.api.Assertions.assertEquals(
        APPLES.toString(), req.getValue().lines().get(0).variantId());
    org.junit.jupiter.api.Assertions.assertEquals(
        new BigDecimal("3"), req.getValue().lines().get(0).qty());
    // The dedupe is the service's, on the handover's own transaction — never a separate mark
    // written before the handover, which a failure after it would turn into a lost order.
    verify(repo, never()).markProcessedIfNew(any(), any());
    // Told again, the service says it already did it, and nothing else happens.
    when(svc.fulfilOrderOnce(any(), eq(WavePickedHandler.CONSUMER), eq(TENANT), any(), any()))
        .thenReturn(false);
    handler.handle(payload());
    verify(svc, times(2))
        .fulfilOrderOnce(
            eq(dedupe1), eq(WavePickedHandler.CONSUMER), eq(TENANT), eq(ORDER_1), any());
  }

  @Test
  void anOrderThatCannotBeFulfilledIsSkippedRememberedAndTheRestStillAre() {
    when(svc.fulfilOrderOnce(any(), eq(WavePickedHandler.CONSUMER), eq(TENANT), eq(ORDER_1), any()))
        .thenThrow(ApiException.conflict("ORDER_NOT_FULFILLABLE", "cancelled meanwhile"));
    when(svc.fulfilOrderOnce(any(), eq(WavePickedHandler.CONSUMER), eq(TENANT), eq(ORDER_2), any()))
        .thenReturn(true);
    handler.handle(payload());
    verify(svc)
        .fulfilOrderOnce(any(), eq(WavePickedHandler.CONSUMER), eq(TENANT), eq(ORDER_2), any());
    // A refusal that will not change is remembered, so a redelivery is quiet about it.
    verify(repo)
        .markProcessedIfNew(
            eq(Ids.derived(EVENT, ORDER_1.toString())), eq(WavePickedHandler.CONSUMER));
  }

  @Test
  void aTransientFailureIsRethrownSoTheEventIsRedelivered() {
    when(svc.fulfilOrderOnce(any(), eq(WavePickedHandler.CONSUMER), eq(TENANT), eq(ORDER_1), any()))
        .thenThrow(
            new ApiException(503, "DB_UNAVAILABLE", "database unavailable", java.util.List.of()));
    org.junit.jupiter.api.Assertions.assertThrows(
        ApiException.class, () -> handler.handle(payload()));
    verify(repo, never()).markProcessedIfNew(any(), any());
  }

  @Test
  void aMalformedEventIsSkippedWithoutTouchingAnOrder() {
    handler.handle("{\"eventType\":\"WavePicked\",\"tenantId\":\"not-an-id\"}");
    verify(svc, never()).fulfilOrderOnce(any(), any(), any(), any(), any());
  }
}
