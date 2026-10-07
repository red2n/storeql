package com.storeql.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.CardSettlement.LinkFacts;
import com.storeql.payment.domain.CardSettlement.Standing;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where a card payment on a terminal stands, and so whether that terminal may start another: the
 * server's rule that a till cannot charge a card twice, whatever the till remembers.
 */
class CardSettlementTest {

  private static final BigDecimal TEN = new BigDecimal("10.00");
  private static final BigDecimal NONE = BigDecimal.ZERO;

  private static Standing sale(String state, String decision, boolean recorded, String committed) {
    return CardSettlement.standing(
        Terminals.SALE, state, decision, recorded, TEN, new BigDecimal(committed));
  }

  @Test
  @DisplayName("A card still at the machine holds the machine")
  void atTheMachine() {
    assertEquals(Standing.AT_MACHINE, sale(Terminals.REQUESTED, null, false, "0"));
    assertTrue(CardSettlement.blocksTheMachine(Standing.AT_MACHINE));
  }

  @Test
  @DisplayName("An approval that is neither recorded nor put back holds the machine")
  void anUnrecordedApproval() {
    assertEquals(Standing.APPROVED_UNRECORDED, sale(Terminals.APPROVED, null, false, "0"));
    assertEquals(
        Standing.APPROVED_UNRECORDED,
        sale(Terminals.APPROVED, null, false, "9.99"),
        "a penny still on the card is still on the card");
    assertTrue(CardSettlement.blocksTheMachine(Standing.APPROVED_UNRECORDED));
  }

  @Test
  @DisplayName("An approval recorded on its order, or put back in full, is settled")
  void aSettledApproval() {
    assertEquals(Standing.SETTLED, sale(Terminals.APPROVED, null, true, "0"));
    assertEquals(Standing.SETTLED, sale(Terminals.APPROVED, null, false, "10.00"));
    assertEquals(Standing.SETTLED, sale(Terminals.APPROVED, null, false, "12.00"));
    assertFalse(CardSettlement.blocksTheMachine(Standing.SETTLED));
  }

  @Test
  @DisplayName("A timeout nobody has looked at holds the machine; a person's word decides it")
  void aTimeout() {
    assertEquals(Standing.UNDECIDED, sale(Terminals.TIMED_OUT, null, false, "0"));
    assertEquals(Standing.SETTLED, sale(Terminals.TIMED_OUT, CardSettlement.NOT_TAKEN, false, "0"));
    // Seen approved, it is an approval like any other: to be recorded or put back.
    assertEquals(
        Standing.APPROVED_UNRECORDED,
        sale(Terminals.TIMED_OUT, CardSettlement.SEEN_APPROVED, false, "0"));
    assertEquals(
        Standing.SETTLED, sale(Terminals.TIMED_OUT, CardSettlement.SEEN_APPROVED, true, "0"));
  }

  @Test
  @DisplayName("A decline, a cancellation and a fault took nothing")
  void nothingTaken() {
    for (String state : new String[] {Terminals.DECLINED, Terminals.CANCELLED, Terminals.FAILED}) {
      assertEquals(Standing.SETTLED, sale(state, null, false, "0"), state);
      assertFalse(CardSettlement.tookMoney(state, null), state);
      assertFalse(CardSettlement.mayHaveMovedMoney(state, null), state);
    }
  }

  @Test
  @DisplayName("What may have moved money: at the machine, approved, or a timeout not ruled out")
  void mayHaveMovedMoney() {
    assertTrue(CardSettlement.mayHaveMovedMoney(Terminals.REQUESTED, null));
    assertTrue(CardSettlement.mayHaveMovedMoney(Terminals.APPROVED, null));
    assertTrue(CardSettlement.mayHaveMovedMoney(Terminals.TIMED_OUT, null));
    assertTrue(CardSettlement.mayHaveMovedMoney(Terminals.TIMED_OUT, CardSettlement.SEEN_APPROVED));
    assertFalse(CardSettlement.mayHaveMovedMoney(Terminals.TIMED_OUT, CardSettlement.NOT_TAKEN));
    assertTrue(CardSettlement.tookMoney(Terminals.TIMED_OUT, CardSettlement.SEEN_APPROVED));
    assertFalse(CardSettlement.tookMoney(Terminals.TIMED_OUT, null));
  }

  @Test
  @DisplayName("A refund attempt is never an approval waiting to be recorded")
  void aRefundAttempt() {
    assertEquals(
        Standing.SETTLED,
        CardSettlement.standing(Terminals.REFUND, Terminals.APPROVED, null, false, TEN, NONE));
    assertEquals(
        Standing.AT_MACHINE,
        CardSettlement.standing(Terminals.REFUND, Terminals.REQUESTED, null, false, TEN, NONE));
    assertEquals(
        Standing.UNDECIDED,
        CardSettlement.standing(Terminals.REFUND, Terminals.TIMED_OUT, null, false, TEN, NONE));
  }

  @Test
  @DisplayName(
      "A refund still at the machine, or timed out with nobody's word on it, has not gone back: the"
          + " sale it would reverse still holds the machine, and so does the refund — though it"
          + " still counts against what else may be put back")
  void aRefundNotYetBack() {
    BigDecimal ten = TEN;
    for (CardSettlement.RefundSeen open :
        List.of(
            new CardSettlement.RefundSeen(Terminals.REQUESTED, null, ten),
            new CardSettlement.RefundSeen(Terminals.TIMED_OUT, null, ten))) {
      BigDecimal back = CardSettlement.wentBack(NONE, List.of(open));
      assertEquals(0, back.signum(), open.state());
      assertEquals(
          Standing.APPROVED_UNRECORDED,
          CardSettlement.standing(Terminals.SALE, Terminals.APPROVED, null, false, ten, back),
          "the sale is not settled by a refund nobody can account for: " + open.state());
      assertTrue(
          CardSettlement.blocksTheMachine(
              CardSettlement.standing(
                  Terminals.REFUND, open.state(), open.decision(), false, ten, NONE)),
          "the refund holds the machine itself: " + open.state());
      assertEquals(
          0,
          CardSettlement.mayHaveGoneBack(NONE, List.of(open)).compareTo(ten),
          "but it may have gone through, so nothing more is put back on top of it");
    }
  }

  @Test
  @DisplayName(
      "A refund that took nothing (refused, failed, or seen not taken) neither went back nor caps"
          + " what may; one approved or seen approved went back")
  void whatARefundPutBack() {
    for (CardSettlement.RefundSeen none :
        List.of(
            new CardSettlement.RefundSeen(Terminals.DECLINED, null, TEN),
            new CardSettlement.RefundSeen(Terminals.FAILED, null, TEN),
            new CardSettlement.RefundSeen(Terminals.TIMED_OUT, CardSettlement.NOT_TAKEN, TEN))) {
      assertEquals(0, CardSettlement.wentBack(NONE, List.of(none)).signum(), none.state());
      assertEquals(0, CardSettlement.mayHaveGoneBack(NONE, List.of(none)).signum(), none.state());
    }
    CardSettlement.RefundSeen approved =
        new CardSettlement.RefundSeen(Terminals.APPROVED, null, new BigDecimal("4.00"));
    CardSettlement.RefundSeen seen =
        new CardSettlement.RefundSeen(
            Terminals.TIMED_OUT, CardSettlement.SEEN_APPROVED, new BigDecimal("6.00"));
    BigDecimal part = CardSettlement.wentBack(NONE, List.of(approved));
    assertEquals(0, part.compareTo(new BigDecimal("4.00")));
    assertEquals(
        Standing.APPROVED_UNRECORDED,
        CardSettlement.standing(Terminals.SALE, Terminals.APPROVED, null, false, TEN, part),
        "six still on the card");
    assertEquals(
        Standing.SETTLED,
        CardSettlement.standing(
            Terminals.SALE,
            Terminals.APPROVED,
            null,
            false,
            TEN,
            CardSettlement.wentBack(NONE, List.of(approved, seen))),
        "all of it put back");
    // What is owed back is counted by both: it is reconciled from the list of what is owed.
    assertEquals(
        0, CardSettlement.wentBack(new BigDecimal("3.00"), List.of(approved)).compareTo(seven()));
    assertEquals(
        0,
        CardSettlement.mayHaveGoneBack(
                new BigDecimal("3.00"),
                List.of(new CardSettlement.RefundSeen(Terminals.REQUESTED, null, TEN)))
            .compareTo(new BigDecimal("13.00")));
  }

  private static BigDecimal seven() {
    return new BigDecimal("7.00");
  }

  @Test
  @DisplayName("What is still on the card is never negative")
  void stillOnCard() {
    assertEquals(
        0, CardSettlement.stillOnCard(TEN, new BigDecimal("4")).compareTo(BigDecimal.valueOf(6)));
    assertEquals(0, CardSettlement.stillOnCard(TEN, new BigDecimal("11")).signum());
    assertEquals(0, CardSettlement.stillOnCard(TEN, null).compareTo(TEN));
  }

  // ── recording an approval as its order's tender ──────────────────────────────

  private static final UUID ORDER = Ids.newId();

  private static LinkFacts link(
      String kind,
      String state,
      String decision,
      UUID order,
      String amount,
      UUID recordedAs,
      String committed) {
    return new LinkFacts(
        kind,
        state,
        decision,
        ORDER,
        order,
        TEN,
        new BigDecimal(amount),
        recordedAs,
        new BigDecimal(committed));
  }

  @Test
  @DisplayName("An approval is recorded once, on its own order, at its own amount")
  void recordingAnApproval() {
    assertNull(
        CardSettlement.linkRefusal(
            link(Terminals.SALE, Terminals.APPROVED, null, ORDER, "10.00", null, "0")));
    assertNull(
        CardSettlement.linkRefusal(
            link(Terminals.SALE, Terminals.APPROVED, null, ORDER, "10", null, "0")),
        "the same money however it is written");
    assertNull(
        CardSettlement.linkRefusal(
            link(
                Terminals.SALE,
                Terminals.TIMED_OUT,
                CardSettlement.SEEN_APPROVED,
                ORDER,
                "10.00",
                null,
                "0")),
        "a timeout a person saw approved is recorded like an approval");

    assertEquals(
        "TERMINAL_ATTEMPT_OTHER_ORDER",
        CardSettlement.linkRefusal(
            link(Terminals.SALE, Terminals.APPROVED, null, Ids.newId(), "10.00", null, "0")));
    assertEquals(
        "TERMINAL_NOT_APPROVED",
        CardSettlement.linkRefusal(
            link(Terminals.SALE, Terminals.DECLINED, null, ORDER, "10.00", null, "0")));
    assertEquals(
        "TERMINAL_NOT_APPROVED",
        CardSettlement.linkRefusal(
            link(Terminals.SALE, Terminals.TIMED_OUT, null, ORDER, "10.00", null, "0")));
    assertEquals(
        "TERMINAL_ATTEMPT_ALREADY_RECORDED",
        CardSettlement.linkRefusal(
            link(Terminals.SALE, Terminals.APPROVED, null, ORDER, "10.00", Ids.newId(), "0")));
    assertEquals(
        "TERMINAL_ATTEMPT_REFUNDED",
        CardSettlement.linkRefusal(
            link(Terminals.SALE, Terminals.APPROVED, null, ORDER, "10.00", null, "1.00")));
    assertEquals(
        "TERMINAL_AMOUNT_MISMATCH",
        CardSettlement.linkRefusal(
            link(Terminals.SALE, Terminals.APPROVED, null, ORDER, "9.99", null, "0")));
    assertEquals(
        "TERMINAL_NOT_A_SALE",
        CardSettlement.linkRefusal(
            link(Terminals.REFUND, Terminals.APPROVED, null, ORDER, "10.00", null, "0")));
  }

  // ── money owed back to a card ───────────────────────────────────────────────

  @Test
  @DisplayName("What the platform owes stays owed until the machine puts it back")
  void whatThePlatformOwes() {
    String owed = CardSettlement.FROM_ORDER_EVENT;
    assertEquals(
        CardSettlement.REFUNDED, CardSettlement.dueStateAfter(owed, Terminals.APPROVED, null));
    assertNull(
        CardSettlement.dueStateAfter(owed, Terminals.REQUESTED, null), "still at the machine");
    for (String refused :
        new String[] {
          Terminals.DECLINED, Terminals.CANCELLED, Terminals.FAILED, Terminals.TIMED_OUT
        }) {
      assertEquals(
          CardSettlement.NEEDS_ATTENTION,
          CardSettlement.dueStateAfter(owed, refused, null),
          refused);
    }
    assertEquals(
        CardSettlement.REFUNDED,
        CardSettlement.dueStateAfter(owed, Terminals.TIMED_OUT, CardSettlement.SEEN_APPROVED));
    assertEquals(
        CardSettlement.NEEDS_ATTENTION,
        CardSettlement.dueStateAfter(owed, Terminals.TIMED_OUT, CardSettlement.NOT_TAKEN));
  }

  @Test
  @DisplayName("A person's refund the machine refused is not owed after; one it may have made is")
  void whatAPersonAsked() {
    String person = CardSettlement.FROM_PERSON;
    assertEquals(
        CardSettlement.REFUNDED, CardSettlement.dueStateAfter(person, Terminals.APPROVED, null));
    assertEquals(
        CardSettlement.NOT_REFUNDED,
        CardSettlement.dueStateAfter(person, Terminals.DECLINED, null));
    assertEquals(
        CardSettlement.NEEDS_ATTENTION,
        CardSettlement.dueStateAfter(person, Terminals.TIMED_OUT, null),
        "a refund that may have gone through waits for a person's word");
    assertEquals(
        CardSettlement.NOT_REFUNDED,
        CardSettlement.dueStateAfter(person, Terminals.TIMED_OUT, CardSettlement.NOT_TAKEN));
  }

  @Test
  @DisplayName("Owed and waiting for a person both still hold the money; the rest do not")
  void whatStillHolds() {
    assertTrue(CardSettlement.dueStillOwed(CardSettlement.OWED));
    assertTrue(CardSettlement.dueStillOwed(CardSettlement.NEEDS_ATTENTION));
    assertFalse(CardSettlement.dueStillOwed(CardSettlement.REFUNDED));
    assertFalse(CardSettlement.dueStillOwed(CardSettlement.NOT_REFUNDED));
  }

  // ── a refund the machine put back after its due was settled without it ──────

  @Test
  @DisplayName(
      "A refund that put money back lands with its due once: with the due while it is owed, waits"
          + " for a person, or was taken to be refused; beside it when another refund already put"
          + " it back; and not again when it is the one that did")
  void whereARefundThatPutMoneyBackLands() {
    UUID mine = Ids.newId();
    UUID other = Ids.newId();
    assertEquals(
        CardSettlement.Landing.WITH_THE_DUE,
        CardSettlement.landing(CardSettlement.OWED, null, mine));
    assertEquals(
        CardSettlement.Landing.WITH_THE_DUE,
        CardSettlement.landing(CardSettlement.NEEDS_ATTENTION, other, mine));
    assertEquals(
        CardSettlement.Landing.WITH_THE_DUE,
        CardSettlement.landing(CardSettlement.NOT_REFUNDED, mine, mine),
        "a person said the machine did not put it back, and then the machine said it did");
    assertEquals(
        CardSettlement.Landing.ALREADY,
        CardSettlement.landing(CardSettlement.REFUNDED, mine, mine),
        "its own late answer after a person said it was approved: once");
    assertEquals(
        CardSettlement.Landing.BESIDE_THE_DUE,
        CardSettlement.landing(CardSettlement.REFUNDED, other, mine),
        "asked again after a person's 'not taken', and both went through: twice on the card");
    assertEquals(
        CardSettlement.Landing.BESIDE_THE_DUE,
        CardSettlement.landing(CardSettlement.ANOTHER_WAY, mine, mine),
        "refunded another way after a person's 'not taken', and then the machine said it did put"
            + " it back: the money left twice, and the late answer is never taken for written");
    assertEquals(
        CardSettlement.Landing.BESIDE_THE_DUE,
        CardSettlement.landing(CardSettlement.ANOTHER_WAY, other, mine));
  }

  // ── money owed back that no machine can put back ─────────────────────────────

  private static final String REF = "ACQ-REFUND-1";

  private static String anotherWay(
      String dueState, UUID paymentId, String method, CardSettlement.Facts... asked) {
    return CardSettlement.anotherWayRefusal(dueState, paymentId, method, List.of(asked));
  }

  @Test
  @DisplayName(
      "Money owed back to a card is refunded another way only once the machine was asked and did"
          + " not put it back, with no refund of it still unaccounted for")
  void whenMoneyOwedBackGoesAnotherWay() {
    UUID tender = Ids.newId();
    assertNull(anotherWay(CardSettlement.NEEDS_ATTENTION, tender, "CASH"));
    assertNull(
        anotherWay(
            CardSettlement.NEEDS_ATTENTION,
            tender,
            "CASH",
            facts(Terminals.REFUND, Terminals.DECLINED, null, null, "0"),
            facts(Terminals.REFUND, Terminals.FAILED, null, null, "0"),
            facts(Terminals.REFUND, Terminals.TIMED_OUT, CardSettlement.NOT_TAKEN, null, "0")),
        "refused, unreachable, and a timeout a person ruled out: none of them moved money");
    assertEquals(
        "CARD_REFUND_DUE_NOT_TRIED",
        anotherWay(CardSettlement.OWED, tender, "CASH"),
        "the machine first: it goes back on the card that paid when it can");
    for (String settled :
        List.of(CardSettlement.REFUNDED, CardSettlement.NOT_REFUNDED, CardSettlement.ANOTHER_WAY)) {
      assertEquals("CARD_REFUND_DUE_SETTLED", anotherWay(settled, tender, "CASH"), settled);
    }
    assertEquals(
        "TERMINAL_REQUEST_IN_FLIGHT",
        anotherWay(
            CardSettlement.NEEDS_ATTENTION,
            tender,
            "CASH",
            facts(Terminals.REFUND, Terminals.REQUESTED, null, null, "0")),
        "a refund of it is at the machine and may yet put it back");
    assertEquals(
        "TERMINAL_REFUND_UNDECIDED",
        anotherWay(
            CardSettlement.NEEDS_ATTENTION,
            tender,
            "CASH",
            facts(Terminals.REFUND, Terminals.TIMED_OUT, null, null, "0")),
        "one timed out with nobody's word on it: it may already be back on the card");
    for (CardSettlement.Facts went :
        List.of(
            facts(Terminals.REFUND, Terminals.APPROVED, null, null, "0"),
            facts(
                Terminals.REFUND, Terminals.TIMED_OUT, CardSettlement.SEEN_APPROVED, null, "0"))) {
      assertEquals(
          "CARD_REFUND_DUE_SETTLED",
          anotherWay(CardSettlement.NEEDS_ATTENTION, tender, "CASH", went),
          "a refund of it went back on the card: it is not given back a second way");
    }
  }

  @Test
  @DisplayName(
      "A card payment never recorded on a sale has nothing in the books to give back in cash: it"
          + " goes back on the card, through the acquirer")
  void anUnrecordedApprovalGoesBackOnTheCardOnly() {
    assertNull(anotherWay(CardSettlement.NEEDS_ATTENTION, null, "CARD"));
    for (String method : List.of("CASH", "UPI", "WALLET")) {
      assertEquals(
          "CARD_REFUND_DUE_NOT_IN_BOOKS",
          anotherWay(CardSettlement.NEEDS_ATTENTION, null, method),
          method);
    }
  }

  @Test
  @DisplayName(
      "Another way is money that left the business by a way the books know: cash, the acquirer"
          + " (with its reference), or a transfer — never value issued, never a blank reference")
  void whichOtherWays() {
    assertNull(CardSettlement.anotherWayInvalid("CASH", null));
    assertNull(CardSettlement.anotherWayInvalid("UPI", " "));
    assertNull(CardSettlement.anotherWayInvalid("WALLET", null));
    assertNull(CardSettlement.anotherWayInvalid("CARD", REF));
    for (String blank : new String[] {null, "", "  "}) {
      assertEquals(
          "CARD_REFUND_REFERENCE_REQUIRED", CardSettlement.anotherWayInvalid("CARD", blank));
    }
    for (String issued :
        new String[] {"GIFT_CARD", "STORE_CREDIT", "VOUCHER", "EXCHANGE", "", null}) {
      assertEquals(
          "CARD_REFUND_METHOD_INVALID", CardSettlement.anotherWayInvalid(issued, REF), issued);
    }
  }

  @Test
  @DisplayName(
      "The last machine of its vendor at a store is not retired while cards its vendor's machines"
          + " took there are owed money back; with another in service, it is")
  void retiringAMachineThatOwes() {
    assertFalse(CardSettlement.retiringStrands(0, true));
    assertFalse(CardSettlement.retiringStrands(0, false));
    assertFalse(
        CardSettlement.retiringStrands(2, true),
        "another machine of the vendor at the store puts them back");
    assertTrue(CardSettlement.retiringStrands(1, false));
  }

  @Test
  @DisplayName(
      "A due holds against its card what it is for while it counts, and what its own refunds put"
          + " back when that is more — so a late approval counts against the next refund")
  void whatADueHolds() {
    BigDecimal ten = new BigDecimal("10.00");
    assertEquals(ten, CardSettlement.dueHolds(CardSettlement.OWED, ten, NONE));
    assertEquals(ten, CardSettlement.dueHolds(CardSettlement.NEEDS_ATTENTION, ten, NONE));
    assertEquals(ten, CardSettlement.dueHolds(CardSettlement.REFUNDED, ten, ten));
    assertEquals(
        NONE,
        CardSettlement.dueHolds(CardSettlement.NOT_REFUNDED, ten, NONE),
        "a person's refund the machine refused holds nothing");
    assertEquals(
        ten,
        CardSettlement.dueHolds(CardSettlement.NOT_REFUNDED, ten, ten),
        "unless the machine put it back after all");
    assertEquals(
        new BigDecimal("20.00"),
        CardSettlement.dueHolds(CardSettlement.REFUNDED, ten, new BigDecimal("20.00")),
        "two refunds of it went through: both are off the card");
    assertEquals(
        ten,
        CardSettlement.dueHolds(CardSettlement.ANOTHER_WAY, ten, NONE),
        "given back another way, it is not given back on the card as well");
    assertEquals(
        new BigDecimal("20.00"),
        CardSettlement.dueHolds(CardSettlement.ANOTHER_WAY, ten, ten),
        "and when a refund of it went through after all, both left the business");
  }

  @Test
  @DisplayName(
      "Another refund of a sale waits while one of it is still at the machine, or timed out with"
          + " nobody's word on it; one the machine answered stops nothing")
  void anotherRefundWaits() {
    assertEquals(
        "TERMINAL_REFUND_IN_FLIGHT", CardSettlement.anotherRefundRefusal(Standing.AT_MACHINE));
    assertEquals(
        "TERMINAL_REFUND_UNDECIDED", CardSettlement.anotherRefundRefusal(Standing.UNDECIDED));
    assertNull(CardSettlement.anotherRefundRefusal(Standing.SETTLED));
  }

  // ── a machine's answer that comes late, and a request left at the machine ────

  private static final List<String> TOOK_NOTHING =
      List.of(Terminals.DECLINED, Terminals.CANCELLED, Terminals.FAILED);

  @Test
  @DisplayName(
      "The machine's first answer is always written; a late one is kept when it says more about the"
          + " money: an approval beats a cancel or a person's word, a timeout beats 'took nothing'")
  void aLateAnswer() {
    for (String answer : Terminals.STATES) {
      if (Terminals.REQUESTED.equals(answer)) continue;
      assertTrue(CardSettlement.answerApplies(Terminals.REQUESTED, answer), answer);
    }
    for (String before :
        List.of(Terminals.DECLINED, Terminals.CANCELLED, Terminals.FAILED, Terminals.TIMED_OUT)) {
      assertTrue(
          CardSettlement.answerApplies(before, Terminals.APPROVED),
          "an approval is never dropped for " + before);
    }
    for (String before : TOOK_NOTHING) {
      assertTrue(
          CardSettlement.answerApplies(before, Terminals.TIMED_OUT),
          "a timeout may have taken money that " + before + " said was not");
    }
    assertFalse(
        CardSettlement.answerApplies(Terminals.APPROVED, Terminals.APPROVED),
        "the first approval stands");
    assertFalse(
        CardSettlement.answerApplies(Terminals.APPROVED, Terminals.TIMED_OUT),
        "nothing undoes an approval");
    assertFalse(CardSettlement.answerApplies(Terminals.TIMED_OUT, Terminals.TIMED_OUT));
    for (String answer : TOOK_NOTHING) {
      for (String before :
          List.of(
              Terminals.APPROVED,
              Terminals.TIMED_OUT,
              Terminals.DECLINED,
              Terminals.CANCELLED,
              Terminals.FAILED)) {
        assertFalse(
            CardSettlement.answerApplies(before, answer),
            "'" + answer + "' late never replaces " + before);
      }
    }
  }

  @Test
  @DisplayName(
      "A person says what the machine shows for a timeout, or for a request the machine has had its"
          + " time to answer and has not; never while it may still answer")
  void whatAPersonMayDecide() {
    Instant asked = Instant.parse("2026-10-02T10:00:00Z");
    Duration within = Duration.ofMinutes(3);
    assertNull(
        CardSettlement.decideRefusal(Terminals.TIMED_OUT, asked, asked.plusSeconds(1), within));
    assertEquals(
        "TERMINAL_REQUEST_IN_FLIGHT",
        CardSettlement.decideRefusal(Terminals.REQUESTED, asked, asked.plusSeconds(179), within),
        "the cardholder may still be entering a PIN");
    assertNull(
        CardSettlement.decideRefusal(Terminals.REQUESTED, asked, asked.plusSeconds(180), within),
        "left at the machine: the call that asked it is gone");
    assertNull(
        CardSettlement.decideRefusal(
            Terminals.REQUESTED, asked, asked.plus(Duration.ofDays(1)), within));
    for (String answered :
        List.of(Terminals.APPROVED, Terminals.DECLINED, Terminals.CANCELLED, Terminals.FAILED)) {
      assertEquals(
          "TERMINAL_NOT_TIMED_OUT",
          CardSettlement.decideRefusal(answered, asked, asked.plus(Duration.ofDays(1)), within),
          answered);
    }
    assertEquals(asked.plus(within), CardSettlement.decidableFrom(asked, within));
  }

  // ── an order given up ───────────────────────────────────────────────────────

  private static CardSettlement.Facts facts(
      String kind, String state, String decision, UUID paymentId, String committed) {
    Terminals.Attempt a =
        new Terminals.Attempt(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            ORDER,
            TEN,
            "GBP",
            kind,
            Terminals.REFUND.equals(kind) ? Ids.newId() : null,
            state,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            paymentId,
            Instant.now(),
            Ids.newId(),
            Terminals.REQUESTED.equals(state) ? null : Instant.now(),
            null,
            null);
    CardSettlement.Decision d =
        decision == null
            ? null
            : new CardSettlement.Decision(
                Ids.newId(),
                a.tenantId(),
                a.storeId(),
                a.id(),
                decision,
                "looked",
                Ids.newId().toString(),
                Ids.newId(),
                Instant.now());
    return new CardSettlement.Facts(a, d, new BigDecimal(committed));
  }

  @Test
  @DisplayName(
      "An order given up owes back what an unrecorded sale took and still has on its card, whenever"
          + " that becomes known; nothing for what took nothing, or is recorded, or is back")
  void whatAGivenUpOrderOwes() {
    assertEquals(
        0,
        CardSettlement.owedWhenGivenUp(facts(Terminals.SALE, Terminals.APPROVED, null, null, "0"))
            .compareTo(TEN));
    assertEquals(
        0,
        CardSettlement.owedWhenGivenUp(
                facts(Terminals.SALE, Terminals.TIMED_OUT, CardSettlement.SEEN_APPROVED, null, "4"))
            .compareTo(new BigDecimal("6")),
        "a timeout a person saw approved, less what already went back");
    for (CardSettlement.Facts none :
        List.of(
            facts(Terminals.SALE, Terminals.REQUESTED, null, null, "0"),
            facts(Terminals.SALE, Terminals.TIMED_OUT, null, null, "0"),
            facts(Terminals.SALE, Terminals.TIMED_OUT, CardSettlement.NOT_TAKEN, null, "0"),
            facts(Terminals.SALE, Terminals.DECLINED, null, null, "0"),
            facts(Terminals.SALE, Terminals.APPROVED, null, Ids.newId(), "0"),
            facts(Terminals.SALE, Terminals.APPROVED, null, null, "10.00"),
            facts(Terminals.REFUND, Terminals.APPROVED, null, null, "0"))) {
      assertEquals(
          0,
          CardSettlement.owedWhenGivenUp(none).signum(),
          none.attempt().kind() + " " + none.attempt().state() + " " + none.outcome());
    }
  }

  @Test
  @DisplayName(
      "A card a machine may have taken for an order given up is never recorded on it; one that"
          + " took nothing does not stand in the way")
  void aGivenUpOrderTakesNoCard() {
    assertTrue(
        CardSettlement.machineMayHaveTaken(
            List.of(
                facts(Terminals.SALE, Terminals.DECLINED, null, null, "0"),
                facts(Terminals.SALE, Terminals.APPROVED, null, null, "10.00"))));
    assertTrue(
        CardSettlement.machineMayHaveTaken(
            List.of(facts(Terminals.SALE, Terminals.TIMED_OUT, null, null, "0"))));
    assertTrue(
        CardSettlement.machineMayHaveTaken(
            List.of(facts(Terminals.SALE, Terminals.REQUESTED, null, null, "0"))));
    assertFalse(
        CardSettlement.machineMayHaveTaken(
            List.of(
                facts(Terminals.SALE, Terminals.DECLINED, null, null, "0"),
                facts(Terminals.SALE, Terminals.TIMED_OUT, CardSettlement.NOT_TAKEN, null, "0"))));
    assertFalse(CardSettlement.machineMayHaveTaken(List.of()));
    assertFalse(
        CardSettlement.machineMayHaveTaken(
            List.of(facts(Terminals.REFUND, Terminals.APPROVED, null, null, "0"))),
        "a refund is not a card taken");
  }
}
