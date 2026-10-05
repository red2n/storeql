package com.storeql.payment.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Terminals;
import com.storeql.payment.provider.CardTerminal;
import com.storeql.payment.repo.TerminalRepository;
import jakarta.enterprise.inject.Instance;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A second press that arrives while the first is still at the device must get the first attempt
 * back, still pending, and must never ask the device again: the device stub counts the requests.
 */
class TerminalReplayWhileAtDeviceTest {

  private final UUID tenant = Ids.newId();
  private final UUID store = Ids.newId();
  private final UUID actor = Ids.newId();

  @Test
  @DisplayName("A replay of a REQUESTED attempt returns it and the device is asked exactly once")
  void aReplayWhileTheFirstPressIsAtTheDeviceDoesNotAskTheDeviceAgain() throws Exception {
    Terminals.Terminal terminal =
        new Terminals.Terminal(
            Ids.newId(),
            tenant,
            store,
            "till",
            "SIMULATED",
            null,
            Terminals.ACTIVE,
            null,
            Instant.now(),
            Instant.now());
    AtomicInteger deviceRequests = new AtomicInteger();
    CountDownLatch atDevice = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Terminals.Outcome approved =
        new Terminals.Outcome(
            Terminals.APPROVED,
            "VISA",
            "4242",
            "AUTH01",
            "A0",
            "VISA",
            "CHIP",
            "PIN",
            "ref-1",
            null);

    CardTerminal device = mock(CardTerminal.class);
    when(device.vendor()).thenReturn("SIMULATED");
    when(device.sale(any()))
        .thenAnswer(
            inv -> {
              deviceRequests.incrementAndGet();
              atDevice.countDown();
              release.await();
              return approved;
            });

    @SuppressWarnings("unchecked")
    Instance<CardTerminal> devices = mock(Instance.class);
    when(devices.iterator()).thenAnswer(inv -> List.of(device).iterator());

    TerminalRepository repo = mock(TerminalRepository.class);
    when(repo.find(tenant, terminal.id())).thenReturn(java.util.Optional.of(terminal));
    Terminals.Attempt[] first = new Terminals.Attempt[1];
    when(repo.claim(any(), anyString()))
        .thenAnswer(
            inv -> {
              synchronized (first) {
                if (first[0] == null) {
                  first[0] = inv.getArgument(0);
                  return first[0];
                }
                return first[0];
              }
            });
    when(repo.settle(any(), any(), any()))
        .thenReturn(
            new com.storeql.payment.domain.CardSettlement.Answered(
                com.storeql.payment.domain.Terminals.REQUESTED, true, false));
    when(repo.attempt(any(), any())).thenAnswer(inv -> java.util.Optional.ofNullable(first[0]));

    TerminalService svc = new TerminalService();
    svc.repo = repo;
    svc.terminals = devices;

    UUID order = Ids.newId();
    String key = Ids.newId().toString();
    CompletableFuture<Terminals.Attempt> firstPress =
        CompletableFuture.supplyAsync(
            () ->
                svc.sale(tenant, terminal.id(), order, new BigDecimal("12.50"), "GBP", actor, key));
    atDevice.await();

    Terminals.Attempt replay =
        svc.sale(tenant, terminal.id(), order, new BigDecimal("12.50"), "GBP", actor, key);
    assertThat("the replay is the first attempt", replay.id(), is(first[0].id()));
    assertThat("still pending, the client waits", replay.state(), is(Terminals.REQUESTED));
    assertThat("the device has been asked once", deviceRequests.get(), is(1));

    release.countDown();
    firstPress.get();
    assertThat("and still once after the first press settled", deviceRequests.get(), is(1));
  }
}
