package com.storeql.payment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.CardSettlement;
import com.storeql.payment.domain.Domain.RefundTender;
import com.storeql.payment.domain.Terminals;
import com.storeql.payment.provider.CardTerminal;
import com.storeql.payment.repo.TerminalRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.inject.Instance;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The server's side of "a till cannot charge a card twice": a terminal holding a card payment that
 * is not settled starts no new one and the machine is never asked; money owed back to a card is
 * written in the books only once the machine has put it back.
 */
class TerminalSettlementServiceTest {

  private final UUID tenant = Ids.newId();
  private final UUID store = Ids.newId();
  private final UUID actor = Ids.newId();
  private final Terminals.Terminal terminal =
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

  private TerminalRepository repo;
  private CardTerminal device;
  private TerminalService svc;

  @BeforeEach
  void wire() {
    device = mock(CardTerminal.class);
    when(device.vendor()).thenReturn("SIMULATED");
    @SuppressWarnings("unchecked")
    Instance<CardTerminal> devices = mock(Instance.class);
    when(devices.iterator()).thenAnswer(inv -> List.of(device).iterator());
    repo = mock(TerminalRepository.class);
    when(repo.find(tenant, terminal.id())).thenReturn(Optional.of(terminal));
    svc = new TerminalService();
    svc.repo = repo;
    svc.terminals = devices;
    svc.answerWithinSeconds = 180;
  }

  private Terminals.Attempt attempt(
      String kind, String state, String amount, UUID refundOf, UUID paymentId, UUID dueId) {
    return new Terminals.Attempt(
        Ids.newId(),
        tenant,
        store,
        terminal.id(),
        Ids.newId(),
        new BigDecimal(amount),
        "GBP",
        kind,
        refundOf,
        state,
        null,
        "VISA",
        "4242",
        "AUTH01",
        "A0",
        "VISA",
        "CHIP",
        "PIN",
        "SIM-1",
        paymentId,
        Instant.now(),
        actor,
        Instant.now(),
        Terminals.REFUND.equals(kind) ? "faulty" : null,
        dueId);
  }

  @Test
  @DisplayName(
      "A held machine refuses a new press 409 with each unsettled payment named, and is not asked")
  void aHeldMachineIsNotAsked() {
    Terminals.Attempt held =
        attempt(Terminals.SALE, Terminals.APPROVED, "12.5000", null, null, null);
    // The refund of it that timed out with nobody's word: it holds the machine too, and what it
    // may have put back is not counted as back on the card.
    Terminals.Attempt refund =
        attempt(Terminals.REFUND, Terminals.TIMED_OUT, "2.5000", held.id(), null, null);
    when(repo.claim(any(), anyString()))
        .thenThrow(
            new CardSettlement.MachineHeld(
                List.of(
                    new CardSettlement.Facts(held, null, new BigDecimal("2.50")),
                    new CardSettlement.Facts(refund, null, BigDecimal.ZERO))));

    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.sale(
                    tenant,
                    terminal.id(),
                    Ids.newId(),
                    new BigDecimal("7.00"),
                    "GBP",
                    actor,
                    Ids.newId().toString()));

    assertEquals(409, e.status());
    assertEquals("TERMINAL_UNSETTLED_APPROVAL", e.code());
    assertEquals(
        List.of(
            "attemptId="
                + held.id()
                + ";orderId="
                + held.orderId()
                + ";amount=12.50;currency=GBP;onCard=10.00;state=APPROVED;standing="
                + "APPROVED_UNRECORDED;kind=SALE",
            "attemptId="
                + refund.id()
                + ";orderId="
                + refund.orderId()
                + ";amount=2.50;currency=GBP;onCard=0.00;state=TIMED_OUT;standing=UNDECIDED"
                + ";kind=REFUND;refundOf="
                + held.id()),
        e.details(),
        "said at the currency's own units, with what is still on the card (a refund holds"
            + " nothing on it) and which sale a refund would reverse");
    verify(device, never()).sale(any());
  }

  @Test
  @DisplayName("A refund needs a reason, and one of at most 500 characters")
  void aRefundNeedsAReason() {
    Terminals.Attempt sale = attempt(Terminals.SALE, Terminals.APPROVED, "10.00", null, null, null);
    when(repo.attempt(tenant, sale.id())).thenReturn(Optional.of(sale));
    for (String bad : new String[] {null, " ", "x".repeat(501)}) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () ->
                  svc.refund(
                      tenant, sale.id(), BigDecimal.ONE, actor, Ids.newId().toString(), bad));
      assertEquals("TERMINAL_REASON_REQUIRED", e.code());
    }
    verify(repo, never()).claimRefund(any(), any());
    verify(device, never()).refund(any(), any());
  }

  @Test
  @DisplayName("A timeout nobody has looked at is not put back until somebody says what it shows")
  void anUndecidedTimeoutIsNotPutBack() {
    Terminals.Attempt sale =
        attempt(Terminals.SALE, Terminals.TIMED_OUT, "10.00", null, null, null);
    when(repo.attempt(tenant, sale.id())).thenReturn(Optional.of(sale));
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.refund(
                    tenant,
                    sale.id(),
                    BigDecimal.ONE,
                    actor,
                    Ids.newId().toString(),
                    "customer says so"));
    assertEquals("TERMINAL_NOT_APPROVED", e.code());
    verify(device, never()).refund(any(), any());
  }

  private CardSettlement.Due due(String source, UUID paymentId, String amount) {
    return new CardSettlement.Due(
        Ids.newId(),
        tenant,
        store,
        Ids.newId(),
        Ids.newId(),
        paymentId,
        new BigDecimal(amount),
        "GBP",
        "Order cancelled",
        source,
        Ids.newId().toString(),
        null,
        "ORIGINAL",
        null,
        null,
        CardSettlement.OWED,
        null,
        null,
        null,
        null,
        Instant.now(),
        Instant.now());
  }

  /** Wires an owed due whose sale's machine answers its refund with {@code answer}. */
  private CardSettlement.Due owed(String source, UUID paymentId, Terminals.Outcome answer) {
    CardSettlement.Due owed = due(source, paymentId, "20.00");
    Terminals.Attempt sale =
        attempt(Terminals.SALE, Terminals.APPROVED, "20.00", null, paymentId, null);
    owed =
        new CardSettlement.Due(
            owed.id(),
            tenant,
            store,
            sale.orderId(),
            sale.id(),
            paymentId,
            owed.amount(),
            "GBP",
            owed.reason(),
            source,
            owed.idempotencyKey(),
            null,
            "ORIGINAL",
            null,
            null,
            CardSettlement.OWED,
            null,
            null,
            null,
            null,
            owed.createdAt(),
            owed.updatedAt());
    final CardSettlement.Due theDue = owed;
    when(repo.owedOn(tenant, sale.orderId())).thenReturn(List.of(theDue));
    when(repo.due(tenant, theDue.id())).thenReturn(Optional.of(theDue));
    AtomicReference<Terminals.Attempt> asked = new AtomicReference<>();
    when(repo.claimRefund(any(), anyString()))
        .thenAnswer(
            inv -> {
              asked.set(inv.getArgument(0));
              return asked.get();
            });
    when(repo.settle(any(), any(), any()))
        .thenReturn(new CardSettlement.Answered(Terminals.REQUESTED, true, false));
    when(repo.attempt(any(), any()))
        .thenAnswer(
            inv -> {
              UUID id = inv.getArgument(1);
              if (id.equals(sale.id())) return Optional.of(sale);
              Terminals.Attempt a = asked.get();
              return Optional.of(
                  new Terminals.Attempt(
                      a.id(),
                      a.tenantId(),
                      a.storeId(),
                      a.terminalId(),
                      a.orderId(),
                      a.amount(),
                      a.currency(),
                      a.kind(),
                      a.refundOf(),
                      answer.state(),
                      answer.detail(),
                      answer.scheme(),
                      answer.panLast4(),
                      answer.authCode(),
                      answer.aid(),
                      answer.applicationLabel(),
                      answer.entryMode(),
                      answer.verification(),
                      answer.providerRef(),
                      null,
                      a.requestedAt(),
                      a.requestedBy(),
                      Instant.now(),
                      a.reason(),
                      a.dueId()));
            });
    when(device.refund(any(), any())).thenReturn(answer);
    return theDue;
  }

  private static final Terminals.Outcome PUT_BACK =
      new Terminals.Outcome(
          Terminals.APPROVED,
          "VISA",
          "4242",
          "AUTH02",
          "A0",
          "VISA",
          "CHIP",
          "NONE",
          "SIM-2",
          null);

  @Test
  @DisplayName(
      "Owed back on a recorded tender: once the machine has put it back, the books' refund and its"
          + " PaymentRefunded are written with the due, on the card's own method")
  void putBackIsThenInTheBooks() {
    UUID tender = Ids.newId();
    CardSettlement.Due owed = owed(CardSettlement.FROM_ORDER_EVENT, tender, PUT_BACK);

    svc.putBackOwed(tenant, owed.orderId());

    ArgumentCaptor<Terminals.Attempt> claimed = ArgumentCaptor.forClass(Terminals.Attempt.class);
    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(repo).claimRefund(claimed.capture(), key.capture());
    assertEquals(owed.id(), claimed.getValue().dueId(), "the refund says which due it is for");
    assertNull(claimed.getValue().requestedBy(), "nobody asked: the platform owes it");
    assertEquals(
        Ids.derived(owed.id(), "card-refund").toString(),
        key.getValue(),
        "a key of its own, so a redelivery never reaches the machine twice");
    ArgumentCaptor<RefundTender> book = ArgumentCaptor.forClass(RefundTender.class);
    ArgumentCaptor<OutboxRow> event = ArgumentCaptor.forClass(OutboxRow.class);
    verify(repo)
        .completeDue(
            eq(tenant),
            eq(owed.id()),
            eq(claimed.getValue().id()),
            eq(CardSettlement.REFUNDED),
            isNull(),
            book.capture(),
            eq(store),
            event.capture());
    assertEquals("CARD", book.getValue().method());
    assertEquals(tender, book.getValue().paymentId());
    assertEquals(0, book.getValue().amount().compareTo(new BigDecimal("20.00")));
    assertEquals("PaymentRefunded", event.getValue().eventType());
    assertTrue(event.getValue().payload().contains("\"refundMethod\":\"ORIGINAL\""));
    assertTrue(event.getValue().payload().contains("\"method\":\"CARD\""));
  }

  @Test
  @DisplayName(
      "A machine that refuses leaves it owed and flagged, with nothing in the books; a person's own"
          + " refund it refused is simply not owed")
  void refusedIsFlaggedNeverBooked() {
    Terminals.Outcome declined =
        Terminals.refused(Terminals.DECLINED, "SIM-3", "DECLINED — refund refused by the issuer");
    CardSettlement.Due owed = owed(CardSettlement.FROM_ORDER_EVENT, Ids.newId(), declined);

    svc.putBackOwed(tenant, owed.orderId());

    ArgumentCaptor<String> attention = ArgumentCaptor.forClass(String.class);
    verify(repo)
        .completeDue(
            eq(tenant),
            eq(owed.id()),
            any(),
            eq(CardSettlement.NEEDS_ATTENTION),
            attention.capture(),
            isNull(),
            isNull(),
            isNull());
    assertNotNull(attention.getValue());
    assertTrue(attention.getValue().contains("refused by the issuer"), attention.getValue());
  }

  /** The sale's own machine, retired. */
  private void retireTheMachine() {
    when(repo.find(tenant, terminal.id()))
        .thenReturn(
            Optional.of(
                new Terminals.Terminal(
                    terminal.id(),
                    tenant,
                    store,
                    "till",
                    "SIMULATED",
                    null,
                    Terminals.RETIRED,
                    "stolen",
                    Instant.now(),
                    Instant.now())));
  }

  /** Another machine of the same vendor in service at the same store: the replacement. */
  private Terminals.Terminal replacement() {
    Terminals.Terminal other =
        new Terminals.Terminal(
            Ids.newId(),
            tenant,
            store,
            "till 2",
            "SIMULATED",
            "SN-2",
            Terminals.ACTIVE,
            null,
            Instant.now(),
            Instant.now());
    when(repo.standIn(tenant, store, "SIMULATED", terminal.id())).thenReturn(Optional.of(other));
    return other;
  }

  @Test
  @DisplayName(
      "A retired machine's card goes back through another machine of its vendor at its store,"
          + " linked to the sale by the vendor's own reference")
  void aRetiredMachinesCardGoesBackThroughAnother() {
    UUID tender = Ids.newId();
    CardSettlement.Due owed = owed(CardSettlement.FROM_ORDER_EVENT, tender, PUT_BACK);
    retireTheMachine();
    Terminals.Terminal other = replacement();

    svc.putBackOwed(tenant, owed.orderId());

    ArgumentCaptor<Terminals.Attempt> claimed = ArgumentCaptor.forClass(Terminals.Attempt.class);
    verify(repo).claimRefund(claimed.capture(), anyString());
    assertEquals(other.id(), claimed.getValue().terminalId(), "asked of the machine in service");
    assertEquals(store, claimed.getValue().storeId());
    assertEquals(owed.saleAttemptId(), claimed.getValue().refundOf(), "of the sale it reverses");
    ArgumentCaptor<CardTerminal.Request> asked =
        ArgumentCaptor.forClass(CardTerminal.Request.class);
    verify(device).refund(asked.capture(), eq("SIM-1"));
    assertEquals(other.id(), asked.getValue().terminalId());
    assertEquals("SN-2", asked.getValue().terminalSerial());
    verify(repo)
        .completeDue(
            eq(tenant),
            eq(owed.id()),
            eq(claimed.getValue().id()),
            eq(CardSettlement.REFUNDED),
            isNull(),
            any(RefundTender.class),
            eq(store),
            any(OutboxRow.class));
  }

  @Test
  @DisplayName(
      "A manager's refund of a card a retired machine took goes through another of its vendor"
          + " too; with none in service it is refused 409 naming the machine, and nothing is asked")
  void aManagersRefundOnARetiredMachine() {
    Terminals.Attempt sale = attempt(Terminals.SALE, Terminals.APPROVED, "30.00", null, null, null);
    when(repo.attempt(tenant, sale.id())).thenReturn(Optional.of(sale));
    retireTheMachine();

    ApiException none =
        assertThrows(
            ApiException.class,
            () ->
                svc.refund(
                    tenant,
                    sale.id(),
                    new BigDecimal("5.00"),
                    actor,
                    Ids.newId().toString(),
                    "faulty"));
    assertEquals(409, none.status());
    assertEquals("TERMINAL_RETIRED", none.code());
    assertEquals(
        List.of("terminalId=" + terminal.id() + ";vendor=SIMULATED;storeId=" + store),
        none.details());
    verify(repo, never()).claimRefund(any(), any());

    Terminals.Terminal other = replacement();
    AtomicReference<Terminals.Attempt> asked = new AtomicReference<>();
    when(repo.claimRefund(any(), anyString()))
        .thenAnswer(
            inv -> {
              asked.set(inv.getArgument(0));
              return asked.get();
            });
    when(device.refund(any(), any())).thenReturn(PUT_BACK);
    when(repo.settle(any(), any(), any()))
        .thenReturn(new CardSettlement.Answered(Terminals.REQUESTED, true, false));
    when(repo.attempt(
            eq(tenant), org.mockito.ArgumentMatchers.argThat(id -> !sale.id().equals(id))))
        .thenAnswer(inv -> Optional.of(answered(asked.get(), PUT_BACK)));

    Terminals.Attempt back =
        svc.refund(
            tenant, sale.id(), new BigDecimal("5.00"), actor, Ids.newId().toString(), "faulty");

    assertEquals(Terminals.APPROVED, back.state());
    assertEquals(other.id(), asked.get().terminalId());
    ArgumentCaptor<CardTerminal.Request> request =
        ArgumentCaptor.forClass(CardTerminal.Request.class);
    verify(device).refund(request.capture(), eq("SIM-1"));
    assertEquals(other.id(), request.getValue().terminalId());
  }

  @Test
  @DisplayName(
      "A retired machine with no other of its vendor at its store leaves the money owed and says"
          + " why, for a manager to give back another way")
  void aRetiredMachineLeavesItOwed() {
    CardSettlement.Due owed = owed(CardSettlement.FROM_ORDER_EVENT, Ids.newId(), PUT_BACK);
    retireTheMachine();

    svc.putBackOwed(tenant, owed.orderId());

    ArgumentCaptor<String> attention = ArgumentCaptor.forClass(String.class);
    verify(repo)
        .completeDue(
            eq(tenant),
            eq(owed.id()),
            isNull(),
            eq(CardSettlement.NEEDS_ATTENTION),
            attention.capture(),
            isNull(),
            isNull(),
            isNull());
    assertTrue(attention.getValue().contains("TERMINAL_RETIRED"), attention.getValue());
    assertTrue(attention.getValue().contains("another way"), attention.getValue());
    verify(repo).standIn(tenant, store, "SIMULATED", terminal.id());
    verify(device, never()).refund(any(), any());
  }

  @Test
  @DisplayName(
      "Asked again by a manager with no machine to ask, money still only owed is left waiting for a"
          + " person and says why — so it can be given back another way — and the refusal stands")
  void aRetryWithNoMachineLeavesItForAPerson() {
    CardSettlement.Due owed = owed(CardSettlement.FROM_ORDER_EVENT, Ids.newId(), PUT_BACK);
    when(repo.attemptsOfDue(tenant, owed.id())).thenReturn(List.of());
    retireTheMachine();

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.retryDue(tenant, owed.id(), actor, Ids.newId().toString()));

    assertEquals(409, e.status());
    assertEquals("TERMINAL_RETIRED", e.code());
    ArgumentCaptor<String> attention = ArgumentCaptor.forClass(String.class);
    verify(repo)
        .completeDue(
            eq(tenant),
            eq(owed.id()),
            isNull(),
            eq(CardSettlement.NEEDS_ATTENTION),
            attention.capture(),
            isNull(),
            isNull(),
            isNull());
    assertTrue(attention.getValue().contains("TERMINAL_RETIRED"), attention.getValue());
    verify(device, never()).refund(any(), any());
  }

  @Test
  @DisplayName(
      "A retry refused because a refund of it is not accounted for changes nothing about the due")
  void aRetryRefusedForARefundInFlightLeavesTheDueAlone() {
    CardSettlement.Due owed = owed(CardSettlement.FROM_ORDER_EVENT, Ids.newId(), PUT_BACK);
    when(repo.attemptsOfDue(tenant, owed.id())).thenReturn(List.of());
    org.mockito.Mockito.doThrow(
            ApiException.conflict("TERMINAL_REQUEST_IN_FLIGHT", "already at the card machine"))
        .when(repo)
        .claimRefund(any(), anyString());

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> svc.retryDue(tenant, owed.id(), actor, Ids.newId().toString()));

    assertEquals("TERMINAL_REQUEST_IN_FLIGHT", e.code());
    verify(repo, never()).completeDue(any(), any(), any(), any(), any(), any(), any(), any());
  }

  // ── a cancel, a late answer, and an order given up ──────────────────────────

  /** An attempt still at the machine: no answer, no reference, no receipt. */
  private Terminals.Attempt atMachine(String kind, UUID orderId) {
    return Terminals.requested(
        Ids.newId(),
        tenant,
        store,
        terminal.id(),
        orderId,
        new BigDecimal("12.50"),
        "GBP",
        kind,
        Terminals.REFUND.equals(kind) ? Ids.newId() : null,
        actor,
        Instant.now(),
        Terminals.REFUND.equals(kind) ? "faulty" : null,
        null);
  }

  /** The same attempt as the machine left it after {@code answer}. */
  private static Terminals.Attempt answered(Terminals.Attempt a, Terminals.Outcome answer) {
    return new Terminals.Attempt(
        a.id(),
        a.tenantId(),
        a.storeId(),
        a.terminalId(),
        a.orderId(),
        a.amount(),
        a.currency(),
        a.kind(),
        a.refundOf(),
        answer.state(),
        answer.detail(),
        answer.scheme(),
        answer.panLast4(),
        answer.authCode(),
        answer.aid(),
        answer.applicationLabel(),
        answer.entryMode(),
        answer.verification(),
        answer.providerRef(),
        null,
        a.requestedAt(),
        a.requestedBy(),
        Instant.now(),
        a.reason(),
        a.dueId());
  }

  private static final Terminals.Outcome TAKEN =
      new Terminals.Outcome(
          Terminals.APPROVED,
          "VISA",
          "4242",
          "AUTH77",
          "A0",
          "VISA",
          "CONTACTLESS",
          "DEVICE",
          "SIM-77",
          null);

  @Test
  @DisplayName(
      "A cancel asks the machine to stop but never settles a card it has not answered for: the"
          + " attempt stays at the machine, holding it, whatever the driver says")
  void aCancelLeavesTheCardAtTheMachine() {
    Terminals.Attempt pending = atMachine(Terminals.SALE, Ids.newId());
    when(repo.attempt(tenant, pending.id())).thenReturn(Optional.of(pending));
    when(device.cancel(any()))
        .thenReturn(Terminals.refused(Terminals.CANCELLED, null, "Cancelled at the till"));

    Terminals.Attempt after = svc.cancel(tenant, pending.id());

    assertEquals(
        Terminals.REQUESTED, after.state(), "the machine's answer settles it, not a cancel");
    verify(device).cancel(isNull());
    verify(repo, never()).settle(any(), any(), any());
  }

  @Test
  @DisplayName("A cancel that the driver cannot send still leaves the card at the machine")
  void aCancelThatFailsLeavesItAtTheMachine() {
    Terminals.Attempt pending = atMachine(Terminals.SALE, Ids.newId());
    when(repo.attempt(tenant, pending.id())).thenReturn(Optional.of(pending));
    when(device.cancel(any())).thenThrow(new IllegalStateException("pinpad unplugged"));

    assertEquals(Terminals.REQUESTED, svc.cancel(tenant, pending.id()).state());
    verify(repo, never()).settle(any(), any(), any());
  }

  @Test
  @DisplayName("A refund is not taken at the pinpad, so it is not cancelled there")
  void aRefundIsNotCancelled() {
    Terminals.Attempt refund = atMachine(Terminals.REFUND, Ids.newId());
    when(repo.attempt(tenant, refund.id())).thenReturn(Optional.of(refund));

    ApiException e = assertThrows(ApiException.class, () -> svc.cancel(tenant, refund.id()));

    assertEquals(409, e.status());
    assertEquals("TERMINAL_NOT_A_SALE", e.code());
    verify(device, never()).cancel(any());
    verify(repo, never()).settle(any(), any(), any());
  }

  /** Wires a sale press whose machine answers {@code answer} and whose settle says {@code how}. */
  private Terminals.Attempt press(Terminals.Outcome answer, CardSettlement.Answered how) {
    UUID order = Ids.newId();
    AtomicReference<Terminals.Attempt> claimed = new AtomicReference<>();
    when(repo.claim(any(), anyString()))
        .thenAnswer(
            inv -> {
              claimed.set(inv.getArgument(0));
              return claimed.get();
            });
    when(device.sale(any())).thenReturn(answer);
    when(repo.settle(any(), any(), any())).thenReturn(how);
    when(repo.attempt(eq(tenant), any()))
        .thenAnswer(inv -> Optional.of(answered(claimed.get(), answer)));
    when(repo.owedOn(tenant, order)).thenReturn(List.of());
    return svc.sale(
        tenant,
        terminal.id(),
        order,
        new BigDecimal("12.50"),
        "GBP",
        actor,
        Ids.newId().toString());
  }

  /** What the service logged while {@code run} ran. */
  private static List<LogRecord> logged(Runnable run) {
    Logger log = Logger.getLogger(TerminalService.class.getName());
    List<LogRecord> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
    Handler h =
        new Handler() {
          @Override
          public void publish(LogRecord r) {
            seen.add(r);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    log.addHandler(h);
    try {
      run.run();
    } finally {
      log.removeHandler(h);
    }
    return seen;
  }

  @Test
  @DisplayName(
      "An approval that comes after a person settled the attempt is kept, and said aloud with its"
          + " reference and authorisation code")
  void aLateApprovalIsKeptAndSaid() {
    AtomicReference<Terminals.Attempt> out = new AtomicReference<>();
    List<LogRecord> logs =
        logged(
            () ->
                out.set(
                    press(TAKEN, new CardSettlement.Answered(Terminals.TIMED_OUT, true, false))));

    assertEquals(Terminals.APPROVED, out.get().state());
    assertTrue(
        logs.stream()
            .anyMatch(
                r ->
                    r.getLevel().intValue() >= Level.WARNING.intValue()
                        && r.getMessage().contains(out.get().id().toString())
                        && r.getMessage().contains("APPROVED")
                        && r.getMessage().contains("SIM-77")
                        && r.getMessage().contains("AUTH77")),
        "never dropped without a trace");
  }

  @Test
  @DisplayName("A late answer that is not kept is still said aloud, never dropped in silence")
  void aLateAnswerNotKeptIsSaid() {
    List<LogRecord> logs =
        logged(
            () ->
                press(
                    Terminals.refused(Terminals.DECLINED, "SIM-9", "DECLINED"),
                    new CardSettlement.Answered(Terminals.TIMED_OUT, false, false)));
    assertTrue(
        logs.stream()
            .anyMatch(
                r ->
                    r.getLevel().intValue() >= Level.WARNING.intValue()
                        && r.getMessage().contains("DECLINED")
                        && r.getMessage().contains("TIMED_OUT")),
        logs.toString());
  }

  @Test
  @DisplayName(
      "A card the machine took for an order already given up is put back through that machine at"
          + " once")
  void anApprovalOnAGivenUpOrderIsPutBack() {
    Terminals.Attempt taken =
        press(TAKEN, new CardSettlement.Answered(Terminals.REQUESTED, true, true));
    verify(repo).owedOn(tenant, taken.orderId());
  }

  @Test
  @DisplayName("An approval on an order that stands asks nothing more of the machine")
  void anApprovalOnAnOrderThatStands() {
    press(TAKEN, new CardSettlement.Answered(Terminals.REQUESTED, true, false));
    verify(repo, never()).owedOn(any(), any());
    verify(device, never()).refund(any(), any());
  }

  @Test
  @DisplayName(
      "A person's word is weighed against the machine's time to answer, and an approval they see"
          + " on an order given up is put back")
  void aDecisionOnAGivenUpOrderIsPutBack() {
    Terminals.Attempt sale = attempt(Terminals.SALE, Terminals.TIMED_OUT, "9.03", null, null, null);
    when(repo.attempt(tenant, sale.id())).thenReturn(Optional.of(sale));
    when(repo.decide(any(), any(), any()))
        .thenAnswer(inv -> new CardSettlement.Decided(inv.getArgument(0), true));
    when(repo.owedOn(tenant, sale.orderId())).thenReturn(List.of());
    when(repo.facts(any()))
        .thenAnswer(inv -> new CardSettlement.Facts(inv.getArgument(0), null, BigDecimal.ZERO));

    String key = Ids.newId().toString();
    svc.decide(tenant, sale.id(), "approved", "the slip says approved", actor, key);

    ArgumentCaptor<CardSettlement.Decision> d =
        ArgumentCaptor.forClass(CardSettlement.Decision.class);
    ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
    verify(repo).decide(d.capture(), now.capture(), eq(Duration.ofSeconds(180)));
    assertEquals(CardSettlement.SEEN_APPROVED, d.getValue().outcome());
    assertEquals(key, d.getValue().idempotencyKey());
    assertTrue(Duration.between(now.getValue(), Instant.now()).abs().getSeconds() < 5);
    verify(repo).owedOn(tenant, sale.orderId());
  }

  // ── retiring a machine ──────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A machine holding a card payment that is not settled is not retired: 409 with each payment"
          + " named as the guard names it")
  void aHeldMachineIsNotRetired() {
    Terminals.Attempt held =
        attempt(Terminals.SALE, Terminals.APPROVED, "12.5000", null, null, null);
    when(repo.retire(eq(tenant), eq(terminal.id()), any()))
        .thenThrow(
            new CardSettlement.MachineHeld(
                List.of(new CardSettlement.Facts(held, null, BigDecimal.ZERO))));

    ApiException e =
        assertThrows(ApiException.class, () -> svc.retire(tenant, terminal.id(), "cracked"));

    assertEquals(409, e.status());
    assertEquals("TERMINAL_UNSETTLED_APPROVAL", e.code());
    assertEquals(
        List.of(
            "attemptId="
                + held.id()
                + ";orderId="
                + held.orderId()
                + ";amount=12.50;currency=GBP;onCard=12.50;state=APPROVED;standing="
                + "APPROVED_UNRECORDED;kind=SALE"),
        e.details());
    assertTrue(e.getMessage().contains("not retired"), e.getMessage());
  }

  @Test
  @DisplayName("A machine retired by another call meanwhile is refused as already retired")
  void aRetirementRacedIsAlreadyRetired() {
    when(repo.retire(eq(tenant), eq(terminal.id()), any())).thenReturn(false);
    ApiException e =
        assertThrows(ApiException.class, () -> svc.retire(tenant, terminal.id(), null));
    assertEquals("TERMINAL_ALREADY_RETIRED", e.code());
  }

  // ── one refund of a card at a time, and one that answers late ───────────────

  @Test
  @DisplayName(
      "Another refund of a sale while one is at the machine is refused 409 naming it, and the"
          + " machine is not asked")
  void aSecondRefundWaitsForTheFirst() {
    Terminals.Attempt sale = attempt(Terminals.SALE, Terminals.APPROVED, "30.00", null, null, null);
    when(repo.attempt(tenant, sale.id())).thenReturn(Optional.of(sale));
    when(repo.decisionOf(any(), any())).thenReturn(Optional.empty());
    for (String[] c :
        new String[][] {
          {Terminals.REQUESTED, "TERMINAL_REFUND_IN_FLIGHT"},
          {Terminals.TIMED_OUT, "TERMINAL_REFUND_UNDECIDED"}
        }) {
      Terminals.Attempt first =
          attempt(Terminals.REFUND, c[0], "10.0000", sale.id(), null, Ids.newId());
      org.mockito.Mockito.doThrow(new CardSettlement.RefundOutstanding(c[1], first))
          .when(repo)
          .claimRefund(any(), anyString());
      ApiException e =
          assertThrows(
              ApiException.class,
              () ->
                  svc.refund(
                      tenant,
                      sale.id(),
                      new BigDecimal("5.00"),
                      actor,
                      Ids.newId().toString(),
                      "another"));
      assertEquals(409, e.status(), c[0]);
      assertEquals(c[1], e.code(), c[0]);
      assertEquals(
          List.of("attemptId=" + first.id() + ";state=" + c[0] + ";amount=10.00;currency=GBP"),
          e.details(),
          c[0]);
    }
    verify(device, never()).refund(any(), any());
  }

  /**
   * A manager's refund of a recorded sale whose machine answers APPROVED late — after a person said
   * what the silent machine showed — with its due standing as {@code dueState} (answered last by
   * the refund {@code byThis} says, or another).
   */
  private List<LogRecord> lateRefund(
      String dueState, boolean byThis, AtomicReference<Terminals.Attempt> asked) {
    return lateRefund(dueState, byThis, asked, BigDecimal.ZERO);
  }

  /** As above, with {@code backOnSale} what has gone back on the sale's card afterwards. */
  private List<LogRecord> lateRefund(
      String dueState,
      boolean byThis,
      AtomicReference<Terminals.Attempt> asked,
      BigDecimal backOnSale) {
    when(repo.facts(any()))
        .thenAnswer(inv -> new CardSettlement.Facts(inv.getArgument(0), null, backOnSale));
    UUID tender = Ids.newId();
    Terminals.Attempt sale =
        attempt(Terminals.SALE, Terminals.APPROVED, "30.00", null, tender, null);
    UUID dueId = Ids.newId();
    UUID otherRefund = Ids.newId();
    when(repo.attempt(tenant, sale.id())).thenReturn(Optional.of(sale));
    when(repo.claimRefund(any(), anyString()))
        .thenAnswer(
            inv -> {
              asked.set(Terminals.forDue(inv.getArgument(0), dueId));
              return asked.get();
            });
    when(device.refund(any(), any())).thenReturn(PUT_BACK);
    // A person had said the refund was not made; the machine's approval beats it.
    when(repo.settle(any(), any(), any()))
        .thenReturn(new CardSettlement.Answered(Terminals.TIMED_OUT, true, false));
    when(repo.attempt(
            eq(tenant), org.mockito.ArgumentMatchers.argThat(id -> !sale.id().equals(id))))
        .thenAnswer(inv -> Optional.of(answered(asked.get(), PUT_BACK)));
    when(repo.decisionOf(eq(tenant), any()))
        .thenAnswer(
            inv ->
                sale.id().equals(inv.getArgument(1))
                    ? Optional.empty()
                    : Optional.of(
                        new CardSettlement.Decision(
                            Ids.newId(),
                            tenant,
                            store,
                            inv.getArgument(1),
                            CardSettlement.NOT_TAKEN,
                            "the machine showed nothing",
                            Ids.newId().toString(),
                            actor,
                            Instant.now())));
    when(repo.due(tenant, dueId))
        .thenAnswer(
            inv ->
                Optional.of(
                    new CardSettlement.Due(
                        dueId,
                        tenant,
                        store,
                        sale.orderId(),
                        sale.id(),
                        tender,
                        new BigDecimal("30.00"),
                        "GBP",
                        "price match",
                        CardSettlement.FROM_PERSON,
                        Ids.newId().toString(),
                        null,
                        null,
                        null,
                        null,
                        dueState,
                        null,
                        byThis ? asked.get().id() : otherRefund,
                        null,
                        actor,
                        Instant.now(),
                        Instant.now())));
    return logged(
        () ->
            svc.refund(
                tenant,
                sale.id(),
                new BigDecimal("30.00"),
                actor,
                Ids.newId().toString(),
                "price match"));
  }

  private void verifyBookedOnce(AtomicReference<Terminals.Attempt> asked) {
    ArgumentCaptor<RefundTender> book = ArgumentCaptor.forClass(RefundTender.class);
    ArgumentCaptor<OutboxRow> event = ArgumentCaptor.forClass(OutboxRow.class);
    verify(repo)
        .completeDue(
            eq(tenant),
            any(),
            eq(asked.get().id()),
            eq(CardSettlement.REFUNDED),
            isNull(),
            book.capture(),
            eq(store),
            event.capture());
    assertEquals(
        Ids.derived(asked.get().id(), "card-refund-book").toString(),
        book.getValue().idempotencyKey(),
        "keyed by the refund itself, so however often it is told it is written once");
    assertEquals(0, book.getValue().amount().compareTo(new BigDecimal("30.00")));
    assertEquals("PaymentRefunded", event.getValue().eventType());
  }

  @Test
  @DisplayName(
      "A refund a person said was not made, approved by the machine afterwards, is put back after"
          + " all: in the books once, and said aloud")
  void aLateRefundApprovalIsPutBackAfterAll() {
    AtomicReference<Terminals.Attempt> asked = new AtomicReference<>();
    List<LogRecord> logs = lateRefund(CardSettlement.NOT_REFUNDED, false, asked);
    verifyBookedOnce(asked);
    assertTrue(
        logs.stream()
            .anyMatch(
                r ->
                    r.getLevel().intValue() >= Level.WARNING.intValue()
                        && r.getMessage().contains(asked.get().id().toString())
                        && r.getMessage().contains("after all")),
        logs.toString());
  }

  @Test
  @DisplayName(
      "A late approval of a refund whose due another refund already put back is in the books"
          + " beside it, and said aloud as a card refunded twice")
  void aLateRefundBesideAnotherIsBookedAndSaid() {
    AtomicReference<Terminals.Attempt> asked = new AtomicReference<>();
    List<LogRecord> logs = lateRefund(CardSettlement.REFUNDED, false, asked);
    verifyBookedOnce(asked);
    assertTrue(
        logs.stream()
            .anyMatch(
                r ->
                    r.getLevel() == Level.SEVERE
                        && r.getMessage().contains("refunded twice")
                        && r.getMessage().contains("SIM-2")),
        logs.toString());
  }

  @Test
  @DisplayName(
      "Put back after all when another refund was made meanwhile: more has gone back than the card"
          + " paid, and that is said aloud as a card refunded twice")
  void aLateRefundAfterAnotherIsSaidAsTwice() {
    AtomicReference<Terminals.Attempt> asked = new AtomicReference<>();
    List<LogRecord> logs =
        lateRefund(CardSettlement.NOT_REFUNDED, false, asked, new BigDecimal("60.00"));
    verifyBookedOnce(asked);
    assertTrue(
        logs.stream()
            .anyMatch(
                r ->
                    r.getLevel() == Level.SEVERE
                        && r.getMessage().contains("refunded twice")
                        && r.getMessage().contains("60.00")),
        logs.toString());
  }

  @Test
  @DisplayName("The late answer of the refund the due was put back by writes nothing more")
  void aLateAnswerOfTheRefundThatPutItBackIsOnce() {
    AtomicReference<Terminals.Attempt> asked = new AtomicReference<>();
    lateRefund(CardSettlement.REFUNDED, true, asked);
    verify(repo, never()).completeDue(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "A late approval of a refund whose due a person gave back another way is in the books beside"
          + " it, and said aloud: the customer was refunded twice")
  void aLateRefundAfterAnotherWayIsBookedAndSaid() {
    AtomicReference<Terminals.Attempt> asked = new AtomicReference<>();
    // The due names this very refund as the last the machine did not make: never taken for the
    // one that wrote it.
    List<LogRecord> logs = lateRefund(CardSettlement.ANOTHER_WAY, true, asked);
    verifyBookedOnce(asked);
    assertTrue(
        logs.stream()
            .anyMatch(
                r ->
                    r.getLevel() == Level.SEVERE
                        && r.getMessage().contains("another way")
                        && r.getMessage().contains("refunded twice")),
        logs.toString());
  }

  // ── a refund asked before its sale was recorded, answered after ─────────────

  /**
   * A manager's refund of an approval no tender records yet, so it is claimed for no due, which the
   * machine answers as {@code answer} — late, after a person said it was not made, when {@code how}
   * says so.
   */
  private void refundOfUnrecorded(
      Terminals.Attempt sale,
      Terminals.Outcome answer,
      CardSettlement.Answered how,
      AtomicReference<Terminals.Attempt> asked) {
    refundOfUnrecorded(sale, answer, how, asked, new BigDecimal("30.00"));
  }

  /** As above, with {@code backOnSale} what has gone back on the sale's card afterwards. */
  private void refundOfUnrecorded(
      Terminals.Attempt sale,
      Terminals.Outcome answer,
      CardSettlement.Answered how,
      AtomicReference<Terminals.Attempt> asked,
      BigDecimal backOnSale) {
    when(repo.attempt(tenant, sale.id())).thenReturn(Optional.of(sale));
    when(repo.claimRefund(any(), anyString()))
        .thenAnswer(
            inv -> {
              asked.set(inv.getArgument(0));
              return asked.get();
            });
    when(device.refund(any(), any())).thenReturn(answer);
    when(repo.settle(any(), any(), any())).thenReturn(how);
    when(repo.attempt(
            eq(tenant), org.mockito.ArgumentMatchers.argThat(id -> !sale.id().equals(id))))
        .thenAnswer(inv -> Optional.of(answered(asked.get(), answer)));
    when(repo.facts(any()))
        .thenAnswer(inv -> new CardSettlement.Facts(inv.getArgument(0), null, backOnSale));
    svc.refund(
        tenant, sale.id(), new BigDecimal("30.00"), actor, Ids.newId().toString(), "price match");
  }

  /** An approval of 30.00 that no tender records yet. */
  private Terminals.Attempt unrecordedSale() {
    return attempt(Terminals.SALE, Terminals.APPROVED, "30.00", null, null, null);
  }

  /** The sale as a tender naming it left it: recorded. */
  private Terminals.Attempt recorded(Terminals.Attempt sale, UUID tender) {
    return new Terminals.Attempt(
        sale.id(),
        sale.tenantId(),
        sale.storeId(),
        sale.terminalId(),
        sale.orderId(),
        sale.amount(),
        sale.currency(),
        sale.kind(),
        sale.refundOf(),
        sale.state(),
        sale.outcomeDetail(),
        sale.scheme(),
        sale.panLast4(),
        sale.authCode(),
        sale.aid(),
        sale.applicationLabel(),
        sale.entryMode(),
        sale.verification(),
        sale.providerRef(),
        tender,
        sale.requestedAt(),
        sale.requestedBy(),
        sale.settledAt(),
        sale.reason(),
        sale.dueId());
  }

  @Test
  @DisplayName(
      "A refund asked before its sale was recorded, which the machine approves after the sale was"
          + " recorded on a person's 'not made': the books give it back too, against that tender,"
          + " once — keyed by the refund — and it is said aloud")
  void aLateRefundOfASaleRecordedSinceIsBooked() {
    UUID tender = Ids.newId();
    Terminals.Attempt sale = unrecordedSale();
    AtomicReference<Terminals.Attempt> asked = new AtomicReference<>();
    AtomicReference<TerminalRepository.Booking> written = new AtomicReference<>();
    when(repo.bookRefundWithoutDue(eq(tenant), any(), any()))
        .thenAnswer(
            inv -> {
              java.util.function.BiFunction<
                      Terminals.Attempt, Terminals.Attempt, TerminalRepository.Booking>
                  books = inv.getArgument(2);
              // As the repository finds them under the sale's lock: the sale recorded since, and
              // the refund as the machine left it.
              written.set(books.apply(recorded(sale, tender), answered(asked.get(), PUT_BACK)));
              return Optional.of(written.get().row());
            });

    List<LogRecord> logs =
        logged(
            () ->
                refundOfUnrecorded(
                    sale,
                    PUT_BACK,
                    new CardSettlement.Answered(Terminals.TIMED_OUT, true, false),
                    asked));

    assertNull(asked.get().dueId(), "asked for nothing owed: the sale was not in the books then");
    verify(repo).bookRefundWithoutDue(eq(tenant), eq(asked.get().id()), any());
    RefundTender row = written.get().row();
    assertEquals(tender, row.paymentId(), "against the tender the sale became");
    assertEquals(sale.orderId(), row.orderId());
    assertEquals("CARD", row.method());
    assertEquals(0, row.amount().compareTo(new BigDecimal("30.00")));
    assertEquals("SIM-2", row.reference(), "the machine's own reference for the refund");
    assertEquals("price match", row.reason());
    assertEquals(
        Ids.derived(asked.get().id(), "card-refund-book").toString(),
        row.idempotencyKey(),
        "keyed by the refund itself, so however often it is told it is written once");
    OutboxRow event = written.get().event();
    assertEquals("PaymentRefunded", event.eventType());
    assertTrue(event.payload().contains("\"paymentId\":\"" + tender + "\""), event.payload());
    assertTrue(event.payload().contains("\"method\":\"CARD\""), event.payload());
    assertTrue(event.payload().contains("\"storeId\":\"" + store + "\""), event.payload());
    assertTrue(event.payload().contains("\"currency\":\"GBP\""), event.payload());
    assertTrue(
        logs.stream()
            .anyMatch(
                r ->
                    r.getLevel().intValue() >= Level.WARNING.intValue()
                        && r.getMessage().contains(asked.get().id().toString())
                        && r.getMessage().contains("written in the books")),
        logs.toString());
    verify(repo, never()).completeDue(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "Booked late beside another refund made meanwhile: more went back than the card paid, and"
          + " that is said aloud as a card refunded twice")
  void aLateRefundOfASaleRecordedSinceBesideAnother() {
    AtomicReference<Terminals.Attempt> asked = new AtomicReference<>();
    when(repo.bookRefundWithoutDue(eq(tenant), any(), any()))
        .thenAnswer(
            inv ->
                Optional.of(
                    new RefundTender(
                        Ids.newId(),
                        tenant,
                        Ids.newId(),
                        Ids.newId(),
                        new BigDecimal("30.00"),
                        "CARD",
                        "SIM-2",
                        Ids.newId().toString(),
                        "price match",
                        Instant.now())));
    List<LogRecord> logs =
        logged(
            () -> {
              refundOfUnrecorded(
                  unrecordedSale(),
                  PUT_BACK,
                  new CardSettlement.Answered(Terminals.TIMED_OUT, true, false),
                  asked,
                  new BigDecimal("60.00"));
            });
    assertTrue(
        logs.stream()
            .anyMatch(
                r ->
                    r.getLevel() == Level.SEVERE
                        && r.getMessage().contains("refunded twice")
                        && r.getMessage().contains("60.00")),
        logs.toString());
  }

  @Test
  @DisplayName(
      "A refund of a sale that is still not recorded has nothing in the books to give back: the"
          + " question is asked under the sale's lock, nothing is written and nothing is said")
  void aRefundOfASaleNeverRecordedBooksNothing() {
    AtomicReference<Terminals.Attempt> asked = new AtomicReference<>();
    when(repo.bookRefundWithoutDue(eq(tenant), any(), any())).thenReturn(Optional.empty());
    List<LogRecord> logs =
        logged(
            () ->
                refundOfUnrecorded(
                    unrecordedSale(),
                    PUT_BACK,
                    new CardSettlement.Answered(Terminals.REQUESTED, true, false),
                    asked));
    verify(repo).bookRefundWithoutDue(eq(tenant), eq(asked.get().id()), any());
    assertTrue(
        logs.stream().noneMatch(r -> r.getMessage().contains("written in the books")),
        logs.toString());
  }

  @Test
  @DisplayName(
      "Money that went back on a card and could not be written in the books is said aloud with its"
          + " reference, never dropped, and the request fails so its retry writes it")
  void aBookingThatFailsIsSaidAloud() {
    AtomicReference<Terminals.Attempt> asked = new AtomicReference<>();
    when(repo.bookRefundWithoutDue(eq(tenant), any(), any()))
        .thenThrow(new IllegalStateException("database unreachable"));
    AtomicReference<RuntimeException> thrown = new AtomicReference<>();
    List<LogRecord> logs =
        logged(
            () -> {
              try {
                refundOfUnrecorded(
                    unrecordedSale(),
                    PUT_BACK,
                    new CardSettlement.Answered(Terminals.TIMED_OUT, true, false),
                    asked);
              } catch (RuntimeException e) {
                thrown.set(e);
              }
            });
    assertNotNull(thrown.get(), "the request fails, so a retry under its key finishes it");
    assertTrue(
        logs.stream()
            .anyMatch(
                r ->
                    r.getLevel() == Level.SEVERE
                        && r.getMessage().contains(asked.get().id().toString())
                        && r.getMessage().contains("SIM-2")
                        && r.getMessage().contains("30.00")),
        logs.toString());
  }

  @Test
  @DisplayName("A refund for nothing owed that the machine refused is never asked of the books")
  void aRefusedRefundOfAnUnrecordedSaleBooksNothing() {
    AtomicReference<Terminals.Attempt> asked = new AtomicReference<>();
    refundOfUnrecorded(
        unrecordedSale(),
        Terminals.refused(Terminals.DECLINED, "SIM-3", "refund refused by the issuer"),
        new CardSettlement.Answered(Terminals.REQUESTED, true, false),
        asked);
    verify(repo, never()).bookRefundWithoutDue(any(), any(), any());
  }

  @Test
  @DisplayName(
      "A person seeing a refund for nothing owed approved asks the books too, as the machine's own"
          + " approval does")
  void aRefundSeenApprovedAsksTheBooks() {
    Terminals.Attempt refund =
        attempt(Terminals.REFUND, Terminals.TIMED_OUT, "9.00", Ids.newId(), null, null);
    when(repo.attempt(tenant, refund.id())).thenReturn(Optional.of(refund));
    when(repo.decide(any(), any(), any()))
        .thenAnswer(inv -> new CardSettlement.Decided(inv.getArgument(0), false));
    when(repo.decisionOf(tenant, refund.id()))
        .thenReturn(
            Optional.of(
                new CardSettlement.Decision(
                    Ids.newId(),
                    tenant,
                    store,
                    refund.id(),
                    CardSettlement.SEEN_APPROVED,
                    "the slip shows the refund",
                    Ids.newId().toString(),
                    actor,
                    Instant.now())));
    when(repo.facts(any()))
        .thenAnswer(inv -> new CardSettlement.Facts(inv.getArgument(0), null, BigDecimal.ZERO));

    svc.decide(
        tenant,
        refund.id(),
        "APPROVED",
        "the slip shows the refund",
        actor,
        Ids.newId().toString());

    verify(repo).bookRefundWithoutDue(eq(tenant), eq(refund.id()), any());
  }

  // ── retiring the last machine while cards are owed money back ───────────────

  @Test
  @DisplayName(
      "The last machine of its vendor at a store is not retired while money is owed back to cards:"
          + " 409 naming each sum owed and its sale")
  void theLastMachineOwingCardsIsNotRetired() {
    CardSettlement.Due owed = due(CardSettlement.FROM_ORDER_EVENT, Ids.newId(), "20.0000");
    when(repo.retire(eq(tenant), eq(terminal.id()), any()))
        .thenThrow(new CardSettlement.OwedOnMachine(List.of(owed)));

    ApiException e =
        assertThrows(ApiException.class, () -> svc.retire(tenant, terminal.id(), "cracked"));

    assertEquals(409, e.status());
    assertEquals("TERMINAL_REFUNDS_OWED", e.code());
    assertEquals(
        List.of(
            "dueId="
                + owed.id()
                + ";attemptId="
                + owed.saleAttemptId()
                + ";orderId="
                + owed.orderId()
                + ";amount=20.00;currency=GBP;state=OWED"),
        e.details(),
        "said at the currency's own units, with the sale each goes back to");
    assertTrue(e.getMessage().contains("SIMULATED"), e.getMessage());
    assertTrue(e.getMessage().contains("another way"), e.getMessage());
  }

  // ── money owed back that no machine can put back ────────────────────────────

  /** A due a machine was asked for and did not put back, as the register reads it. */
  private CardSettlement.Due waiting(UUID paymentId) {
    CardSettlement.Due d = due(CardSettlement.FROM_ORDER_EVENT, paymentId, "20.0000");
    CardSettlement.Due waiting =
        new CardSettlement.Due(
            d.id(),
            tenant,
            store,
            d.orderId(),
            d.saleAttemptId(),
            paymentId,
            d.amount(),
            "GBP",
            d.reason(),
            d.source(),
            d.idempotencyKey(),
            null,
            "ORIGINAL",
            null,
            null,
            CardSettlement.NEEDS_ATTENTION,
            "The card machine could not be asked (TERMINAL_RETIRED)",
            null,
            null,
            null,
            d.createdAt(),
            d.updatedAt());
    when(repo.due(tenant, waiting.id())).thenReturn(Optional.of(waiting));
    when(repo.attemptsOfDue(tenant, waiting.id())).thenReturn(List.of());
    when(repo.closeAnotherWay(any(), any(), any())).thenReturn(waiting);
    return waiting;
  }

  @Test
  @DisplayName(
      "Another way is cash, the acquirer's own refund with its reference, or a transfer, and takes"
          + " a reason: anything else is refused 400 before anything is written")
  void anotherWayIsAWayTheBooksKnow() {
    CardSettlement.Due owed = waiting(Ids.newId());
    String key = Ids.newId().toString();
    for (String[] bad :
        new String[][] {
          {"GIFT_CARD", "x", "why", "CARD_REFUND_METHOD_INVALID"},
          {"STORE_CREDIT", null, "why", "CARD_REFUND_METHOD_INVALID"},
          {null, null, "why", "CARD_REFUND_METHOD_INVALID"},
          {"CARD", null, "why", "CARD_REFUND_REFERENCE_REQUIRED"},
          {"CARD", "  ", "why", "CARD_REFUND_REFERENCE_REQUIRED"},
          {"CARD", "4242 4242 4242 4242", "why", "TERMINAL_CARD_DATA_NOT_ACCEPTED"},
          {"CASH", null, " ", "TERMINAL_REASON_REQUIRED"},
          {"CASH", null, "x".repeat(501), "TERMINAL_REASON_REQUIRED"}
        }) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> svc.refundedAnotherWay(tenant, owed.id(), bad[0], bad[1], bad[2], actor, key),
              bad[3]);
      assertEquals(400, e.status(), bad[3]);
      assertEquals(bad[3], e.code());
    }
    verify(repo, never()).closeAnotherWay(any(), any(), any());
  }

  @Test
  @DisplayName(
      "Money owed back against a recorded tender, given back in cash: the books' refund in cash"
          + " and its PaymentRefunded go with the closure, keyed by the due so it is once")
  void givenBackAnotherWayIsInTheBooks() {
    UUID tender = Ids.newId();
    CardSettlement.Due owed = waiting(tender);
    String key = Ids.newId().toString();

    svc.refundedAnotherWay(tenant, owed.id(), " cash ", null, "the machine is gone", actor, key);

    ArgumentCaptor<CardSettlement.Closure> closure =
        ArgumentCaptor.forClass(CardSettlement.Closure.class);
    ArgumentCaptor<RefundTender> book = ArgumentCaptor.forClass(RefundTender.class);
    ArgumentCaptor<OutboxRow> event = ArgumentCaptor.forClass(OutboxRow.class);
    verify(repo).closeAnotherWay(closure.capture(), book.capture(), event.capture());
    assertEquals(owed.id(), closure.getValue().dueId());
    assertEquals("CASH", closure.getValue().method());
    assertNull(closure.getValue().reference());
    assertEquals("the machine is gone", closure.getValue().reason());
    assertEquals(key, closure.getValue().idempotencyKey());
    assertEquals(actor, closure.getValue().closedBy());
    assertEquals(store, closure.getValue().storeId());
    assertEquals(tender, book.getValue().paymentId());
    assertEquals(owed.orderId(), book.getValue().orderId());
    assertEquals("CASH", book.getValue().method());
    assertEquals(0, book.getValue().amount().compareTo(new BigDecimal("20.00")));
    assertEquals(
        Ids.derived(owed.id(), "card-refund-another-way").toString(),
        book.getValue().idempotencyKey());
    assertEquals("Order cancelled", book.getValue().reason(), "why the money went back");
    assertEquals("PaymentRefunded", event.getValue().eventType());
    assertTrue(event.getValue().payload().contains("\"method\":\"CASH\""));
    assertTrue(event.getValue().payload().contains("\"refundMethod\":\"ORIGINAL\""));
    assertTrue(event.getValue().payload().contains("\"storeId\":\"" + store + "\""));
    verify(device, never()).refund(any(), any());
  }

  @Test
  @DisplayName(
      "An approval never recorded, put back by the acquirer: the closure keeps its reference and"
          + " the books, which never had the money, are given nothing")
  void anUnrecordedApprovalGivenBackByTheAcquirer() {
    CardSettlement.Due owed = waiting(null);

    svc.refundedAnotherWay(
        tenant,
        owed.id(),
        "CARD",
        "ACQ-REF-77",
        "refunded in the acquirer's portal",
        actor,
        Ids.newId().toString());

    ArgumentCaptor<CardSettlement.Closure> closure =
        ArgumentCaptor.forClass(CardSettlement.Closure.class);
    verify(repo).closeAnotherWay(closure.capture(), isNull(), isNull());
    assertEquals("CARD", closure.getValue().method());
    assertEquals("ACQ-REF-77", closure.getValue().reference());
  }

  @Test
  @DisplayName(
      "A refund of it that went back on the card but was never finished is finished first, so the"
          + " money is not given back a second way")
  void aRefundThatWentBackIsFinishedNotGivenAgain() {
    UUID tender = Ids.newId();
    CardSettlement.Due owed = waiting(tender);
    Terminals.Attempt went =
        attempt(
            Terminals.REFUND, Terminals.APPROVED, "20.00", owed.saleAttemptId(), null, owed.id());
    when(repo.attemptsOfDue(tenant, owed.id()))
        .thenReturn(List.of(new CardSettlement.Facts(went, null, BigDecimal.ZERO)));
    when(repo.closeAnotherWay(any(), any(), any()))
        .thenThrow(
            new ApiException(
                409,
                "CARD_REFUND_DUE_SETTLED",
                "Nothing is owed back on this any more",
                List.of()));

    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.refundedAnotherWay(
                    tenant, owed.id(), "CASH", null, "gone", actor, Ids.newId().toString()));

    assertEquals("CARD_REFUND_DUE_SETTLED", e.code());
    verify(repo)
        .completeDue(
            eq(tenant),
            eq(owed.id()),
            eq(went.id()),
            eq(CardSettlement.REFUNDED),
            isNull(),
            any(RefundTender.class),
            eq(store),
            any(OutboxRow.class));
  }

  @Test
  @DisplayName("Another business's money owed back is not there to give back: 404, nothing written")
  void anotherBusinesssDueIsNotFound() {
    UUID theirs = Ids.newId();
    when(repo.due(tenant, theirs)).thenReturn(Optional.empty());
    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                svc.refundedAnotherWay(
                    tenant, theirs, "CASH", null, "gone", actor, Ids.newId().toString()));
    assertEquals(404, e.status());
    assertEquals("CARD_REFUND_DUE_NOT_FOUND", e.code());
    verify(repo, never()).closeAnotherWay(any(), any(), any());
  }
}
