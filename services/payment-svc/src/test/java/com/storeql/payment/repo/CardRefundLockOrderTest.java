package com.storeql.payment.repo;

import static com.storeql.payment.repo.ScriptedDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.CardSettlement;
import com.storeql.payment.domain.CardSettlement.Due;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.domain.Domain.RefundTender;
import com.storeql.service.OutboxRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A card refund a machine approved is written in the books with its tender's row taken first.
 *
 * <p>Everything that counts what is left on a tender — a back-office refund, an order event's
 * refund, a manager's refund on a machine — holds that tender's row while it reads what is refunded
 * and what is owed back. The machine's approval moves a sum from "owed back" to "refunded" on one
 * transaction; unless that transaction takes the same row, a reader between its two reads sees the
 * money in neither place and gives it back a second time. So the order of the statements is the
 * rule, and it is held here; that the lock then keeps the two apart is the integration test's
 * ({@code TerminalSettlementIT.aMachinesApprovalWaitsForTheTendersRow}).
 */
class CardRefundLockOrderTest {

  private static final UUID BIZ = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID ORDER = Ids.newId();
  private static final UUID SALE = Ids.newId();
  private static final UUID TENDER = Ids.newId();
  private static final OffsetDateTime THEN = Instant.now().atOffset(ZoneOffset.UTC);

  private static TerminalRepository over(ScriptedDb db) {
    return new TerminalRepository() {
      {
        dataSource = db.dataSource();
      }
    };
  }

  private static Map<String, Object> due(UUID id, String state, UUID tender, UUID putBackBy) {
    return row(
        "id", id,
        "tenant_id", BIZ,
        "store_id", STORE,
        "order_id", ORDER,
        "sale_attempt_id", SALE,
        "payment_id", tender,
        "amount", new BigDecimal("30.00"),
        "currency", "GBP",
        "reason", "the sale changed",
        "source", CardSettlement.FROM_PERSON,
        "idempotency_key", Ids.newId().toString(),
        "state", state,
        "refund_attempt_id", putBackBy,
        "created_at", THEN,
        "updated_at", THEN);
  }

  private static RefundTender book(UUID refundAttempt, UUID tender) {
    return new RefundTender(
        Ids.newId(),
        BIZ,
        ORDER,
        tender,
        new BigDecimal("30.00"),
        PaymentTender.METHOD_CARD,
        "GATE-R-1",
        Ids.derived(refundAttempt, "card-refund-book").toString(),
        "the sale changed",
        Instant.now());
  }

  private static OutboxRow announced() {
    return new OutboxRow(
        "PaymentRefunded", "storeql.payment.payment-refunded", BIZ, Ids.newId(), "{}");
  }

  private static final String[] DUE_LOCK = {"FROM card_refund_dues", "FOR UPDATE"};
  private static final String[] TENDER_LOCK = {"FROM payment_tenders", "FOR UPDATE"};
  private static final String[] BOOKED_YET = {"SELECT id FROM refund_tenders"};
  private static final String[] BOOK = {"INSERT INTO refund_tenders"};
  private static final String[] ANNOUNCE = {"INSERT INTO outbox"};
  private static final String[] MOVE_DUE = {"UPDATE card_refund_dues"};

  @Test
  @DisplayName(
      "A machine's approval takes the due's row, then the tender's, before it reads or writes the"
          + " books: nothing counts that tender between the due moving and the refund landing")
  void anApprovalTakesTheTendersRowBeforeTheBooks() {
    UUID dueId = Ids.newId();
    UUID refundAttempt = Ids.newId();
    ScriptedDb db =
        new ScriptedDb()
            .answer(List.of(DUE_LOCK), due(dueId, CardSettlement.OWED, TENDER, null))
            .answer(List.of(TENDER_LOCK), row("id", TENDER));

    Due after =
        over(db)
            .completeDue(
                BIZ,
                dueId,
                refundAttempt,
                CardSettlement.REFUNDED,
                null,
                book(refundAttempt, TENDER),
                STORE,
                announced());

    assertEquals(dueId, after.id());
    int dueLock = db.firstOf(DUE_LOCK);
    int tenderLock = db.firstOf(TENDER_LOCK);
    assertTrue(dueLock >= 0, "the due's row is taken: " + db.asked());
    assertTrue(tenderLock > dueLock, "the tender's row is taken after the due's: " + db.asked());
    assertTrue(
        tenderLock < db.firstOf(BOOKED_YET),
        "…before the books are asked whether the refund is there: " + db.asked());
    assertTrue(tenderLock < db.firstOf(BOOK), "…before the books' refund is written");
    assertTrue(tenderLock < db.firstOf(ANNOUNCE), "…before it is announced");
    assertTrue(tenderLock < db.firstOf(MOVE_DUE), "…and before the due stops being owed");
    assertEquals(1, db.count(TENDER_LOCK), "taken once");
    assertEquals(1, db.count(BOOK), "written once");
    assertTrue(db.committed());
  }

  @Test
  @DisplayName(
      "The same for a refund that answers after a person said it was not made, and for one that"
          + " lands beside a due another refund already put back")
  void aLateApprovalTakesTheTendersRowToo() {
    for (String state :
        new String[] {
          CardSettlement.NOT_REFUNDED, CardSettlement.REFUNDED, CardSettlement.ANOTHER_WAY
        }) {
      UUID dueId = Ids.newId();
      UUID refundAttempt = Ids.newId();
      // A REFUNDED due put back by another refund: this one lands beside it.
      UUID putBackBy = CardSettlement.REFUNDED.equals(state) ? Ids.newId() : null;
      ScriptedDb db =
          new ScriptedDb()
              .answer(List.of(DUE_LOCK), due(dueId, state, TENDER, putBackBy))
              .answer(List.of(TENDER_LOCK), row("id", TENDER));

      over(db)
          .completeDue(
              BIZ,
              dueId,
              refundAttempt,
              CardSettlement.REFUNDED,
              null,
              book(refundAttempt, TENDER),
              STORE,
              announced());

      int tenderLock = db.firstOf(TENDER_LOCK);
      assertTrue(tenderLock > db.firstOf(DUE_LOCK), state + ": " + db.asked());
      assertTrue(tenderLock < db.firstOf(BOOKED_YET), state + ": " + db.asked());
      assertTrue(tenderLock < db.firstOf(BOOK), state + ": " + db.asked());
      assertEquals(1, db.count(BOOK), state);
    }
  }

  @Test
  @DisplayName(
      "A refund the books already hold is not written again, with the row taken all the same")
  void aRefundAlreadyInTheBooksIsNotWrittenAgain() {
    UUID dueId = Ids.newId();
    UUID refundAttempt = Ids.newId();
    ScriptedDb db =
        new ScriptedDb()
            .answer(List.of(DUE_LOCK), due(dueId, CardSettlement.OWED, TENDER, null))
            .answer(List.of(TENDER_LOCK), row("id", TENDER))
            .answer(List.of(BOOKED_YET), row("id", Ids.newId()));

    over(db)
        .completeDue(
            BIZ,
            dueId,
            refundAttempt,
            CardSettlement.REFUNDED,
            null,
            book(refundAttempt, TENDER),
            STORE,
            announced());

    assertTrue(db.firstOf(TENDER_LOCK) > db.firstOf(DUE_LOCK), db.asked().toString());
    assertTrue(db.firstOf(TENDER_LOCK) < db.firstOf(BOOKED_YET), db.asked().toString());
    assertEquals(0, db.count(BOOK));
    assertEquals(0, db.count(ANNOUNCE));
    assertEquals(1, db.count(MOVE_DUE), "the due is put back by it all the same");
  }

  @Test
  @DisplayName(
      "The refund that already put its due back answers again: nothing is taken, read or written"
          + " beyond the due")
  void theRefundThatPutItBackIsLeftAlone() {
    UUID dueId = Ids.newId();
    UUID refundAttempt = Ids.newId();
    ScriptedDb db =
        new ScriptedDb()
            .answer(List.of(DUE_LOCK), due(dueId, CardSettlement.REFUNDED, TENDER, refundAttempt));

    over(db)
        .completeDue(
            BIZ,
            dueId,
            refundAttempt,
            CardSettlement.REFUNDED,
            null,
            book(refundAttempt, TENDER),
            STORE,
            announced());

    assertEquals(0, db.count(TENDER_LOCK), db.asked().toString());
    assertEquals(0, db.count(BOOK));
    assertEquals(0, db.count(MOVE_DUE));
  }

  @Test
  @DisplayName(
      "Money owed back on an approval no tender records has no tender to take: the due moves, and"
          + " the books are not touched")
  void anUnrecordedApprovalHasNoTenderToTake() {
    UUID dueId = Ids.newId();
    ScriptedDb db =
        new ScriptedDb().answer(List.of(DUE_LOCK), due(dueId, CardSettlement.OWED, null, null));

    over(db).completeDue(BIZ, dueId, Ids.newId(), CardSettlement.REFUNDED, null, null, null, null);

    assertEquals(0, db.count(TENDER_LOCK), db.asked().toString());
    assertEquals(0, db.count(BOOK));
    assertEquals(1, db.count(MOVE_DUE));
  }

  @Test
  @DisplayName(
      "A refund the machine did not make moves no money: the due waits for a person, or is not owed"
          + " after, and neither the tender nor the books are touched")
  void aRefundNotMadeTakesNoTender() {
    for (String next : new String[] {CardSettlement.NEEDS_ATTENTION, CardSettlement.NOT_REFUNDED}) {
      UUID dueId = Ids.newId();
      ScriptedDb db =
          new ScriptedDb().answer(List.of(DUE_LOCK), due(dueId, CardSettlement.OWED, TENDER, null));

      over(db).completeDue(BIZ, dueId, Ids.newId(), next, "declined", null, null, null);

      assertEquals(0, db.count(TENDER_LOCK), next + ": " + db.asked());
      assertEquals(0, db.count(BOOK), next);
      assertEquals(1, db.count(MOVE_DUE), next);
    }
  }
}
