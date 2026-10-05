package com.storeql.payment.repo;

import static com.storeql.payment.repo.ScriptedDb.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.domain.Domain.RefundTender;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A refund written in the books ({@code POST /payments/by-order/{orderId}/refunds}) is the store's
 * where its tender was taken: the tender's row is taken first, another business's is not found, and
 * the caller is asked whether they may act at that store — before a replay is answered, before
 * anything is counted and before anything is written. What the guard decides is {@code
 * RefundStoreGuardTest}'s; that Postgres keeps it is {@code BackOfficeRefundIT}'s.
 */
class BooksRefundGuardTest {

  private static final UUID BIZ = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID ORDER = Ids.newId();
  private static final UUID TENDER = Ids.newId();
  private static final OffsetDateTime THEN = Instant.now().atOffset(ZoneOffset.UTC);

  private static final String[] TENDER_LOCK = {"FROM payment_tenders", "FOR UPDATE"};
  private static final String[] BY_KEY = {"FROM refund_tenders", "idempotency_key=?"};
  private static final String[] SPOKEN_FOR = {"refund_tenders", "card_refund_dues", "spoken_for"};
  private static final String[] BOOK = {"INSERT INTO refund_tenders"};
  private static final String[] ANNOUNCE = {"INSERT INTO outbox"};

  private static PaymentRepository over(ScriptedDb db) {
    return new PaymentRepository() {
      {
        dataSource = db.dataSource();
      }
    };
  }

  private static Map<String, Object> tender(UUID store) {
    return row(
        "id", TENDER,
        "tenant_id", BIZ,
        "order_id", ORDER,
        "amount", new BigDecimal("30.00"),
        "method", PaymentTender.METHOD_CASH,
        "status", PaymentTender.STATUS_CAPTURED,
        "created_at", THEN,
        "store_id", store);
  }

  private static Map<String, Object> refundRow(UUID id, UUID tender, UUID order, String key) {
    return row(
        "id", id,
        "tenant_id", BIZ,
        "order_id", order,
        "payment_id", tender,
        "amount", new BigDecimal("5.00"),
        "method", PaymentTender.METHOD_CASH,
        "idempotency_key", key,
        "reason", "damaged",
        "created_at", THEN);
  }

  private static RefundTender refund(String amount, String key) {
    return new RefundTender(
        Ids.newId(),
        BIZ,
        ORDER,
        TENDER,
        new BigDecimal(amount),
        PaymentTender.METHOD_CASH,
        null,
        key,
        "damaged",
        Instant.now());
  }

  private static OutboxRow announced() {
    return new OutboxRow(
        "PaymentRefunded", "storeql.payment.payment-refunded", BIZ, Ids.newId(), "{}");
  }

  private static PaymentRepository.StoreGuard refusing() {
    return store -> {
      throw ApiException.forbidden("STORE_ACCESS_DENIED", "Caller is not assigned to this store");
    };
  }

  @Test
  @DisplayName(
      "The tender's row is taken first, then the caller is asked about its store, then the books"
          + " are read and written")
  void theTenderThenTheStoreThenTheBooks() {
    ScriptedDb db =
        new ScriptedDb()
            .answer(List.of(TENDER_LOCK), tender(STORE))
            .answer(List.of(SPOKEN_FOR), row("spoken_for", new BigDecimal("10.00")));
    List<UUID> asked = new ArrayList<>();
    List<Integer> statementsWhenAsked = new ArrayList<>();

    RefundTender written =
        over(db)
            .createRefundGuarded(
                refund("20.00", Ids.newId().toString()),
                announced(),
                store -> {
                  asked.add(store);
                  statementsWhenAsked.add(db.asked().size());
                });

    assertEquals(new BigDecimal("20.00"), written.amount());
    assertEquals(0, db.firstOf(TENDER_LOCK), "the tender's row first: " + db.asked());
    assertEquals(List.of(STORE), asked, "asked once, about the store the tender was taken at");
    assertEquals(List.of(1), statementsWhenAsked, "asked before anything else is read");
    assertTrue(db.firstOf(BY_KEY) > 0, db.asked().toString());
    assertEquals(1, db.count(SPOKEN_FOR), "refunded and owed back are read in one statement");
    assertEquals(
        0,
        db.asked().stream()
            .filter(sql -> sql.contains("SUM(amount)") && !sql.contains("spoken_for"))
            .count(),
        "and never as two: " + db.asked());
    assertEquals(1, db.count(BOOK));
    assertEquals(1, db.count(ANNOUNCE));
    assertTrue(db.firstOf(SPOKEN_FOR) < db.firstOf(BOOK));
    assertTrue(db.committed());
  }

  @Test
  @DisplayName(
      "A caller who may not act at the tender's store is refused before a replay is answered or"
          + " anything is counted or written")
  void aCallerHeldElsewhereMovesNothing() {
    String key = Ids.newId().toString();
    ScriptedDb db =
        new ScriptedDb()
            .answer(List.of(TENDER_LOCK), tender(STORE))
            // Even a refund already made under this key is not shown to them.
            .answer(List.of(BY_KEY), refundRow(Ids.newId(), TENDER, ORDER, key));

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> over(db).createRefundGuarded(refund("5.00", key), announced(), refusing()));

    assertEquals(403, e.status());
    assertEquals("STORE_ACCESS_DENIED", e.code());
    assertEquals(1, db.asked().size(), "only the tender was read: " + db.asked());
    assertEquals(0, db.count(BY_KEY));
    assertEquals(0, db.count(BOOK));
    assertEquals(0, db.count(ANNOUNCE));
    assertTrue(db.rolledBack());
    assertFalse(db.committed());
  }

  @Test
  @DisplayName(
      "Another business's tender is not found, and nobody is asked about a store: 404 before 403")
  void anotherBusinesssTenderIsNotFound() {
    // The tender is read with the caller's own tenant first in the WHERE, so another business's
    // answers no row.
    ScriptedDb db = new ScriptedDb();
    List<UUID> asked = new ArrayList<>();

    ApiException e =
        assertThrows(
            ApiException.class,
            () ->
                over(db)
                    .createRefundGuarded(
                        refund("5.00", Ids.newId().toString()), announced(), asked::add));

    assertEquals(404, e.status());
    assertEquals("PAYMENT_NOT_FOUND", e.code());
    assertTrue(asked.isEmpty(), "no store to ask about");
    assertTrue(db.asked().get(0).contains("WHERE tenant_id=? AND id=?"), db.asked().toString());
    assertEquals(0, db.count(BOOK));
    assertEquals(0, db.count(ANNOUNCE));
  }

  @Test
  @DisplayName("A tender with no store is asked about as no store: the guard decides, not the repo")
  void aTenderWithNoStoreIsAskedAboutAsNone() {
    ScriptedDb db = new ScriptedDb().answer(List.of(TENDER_LOCK), tender(null));
    List<UUID> asked = new ArrayList<>();

    over(db).createRefundGuarded(refund("5.00", null), announced(), asked::add);

    assertEquals(1, asked.size());
    assertEquals(null, asked.get(0));
    assertEquals(1, db.count(BOOK));
  }

  @Test
  @DisplayName("A replay under the same key answers the first refund and writes nothing")
  void aReplayAnswersTheFirst() {
    String key = Ids.newId().toString();
    UUID first = Ids.newId();
    ScriptedDb db =
        new ScriptedDb()
            .answer(List.of(TENDER_LOCK), tender(STORE))
            .answer(List.of(BY_KEY), refundRow(first, TENDER, ORDER, key));

    RefundTender again =
        over(db).createRefundGuarded(refund("5.00", key), announced(), store -> {});

    assertEquals(first, again.id());
    assertEquals(0, db.count(BOOK));
    assertEquals(0, db.count(ANNOUNCE));
  }

  @Test
  @DisplayName(
      "A key already used for a refund of another tender is refused, never answered with that"
          + " other refund")
  void aKeyUsedForAnotherTenderIsRefused() {
    String key = Ids.newId().toString();
    ScriptedDb db =
        new ScriptedDb()
            .answer(List.of(TENDER_LOCK), tender(STORE))
            .answer(List.of(BY_KEY), refundRow(Ids.newId(), Ids.newId(), Ids.newId(), key));

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> over(db).createRefundGuarded(refund("5.00", key), announced(), store -> {}));

    assertEquals(409, e.status());
    assertEquals("IDEMPOTENCY_KEY_REUSED", e.code());
    assertEquals(0, db.count(BOOK));
    assertEquals(0, db.count(ANNOUNCE));
  }

  @Test
  @DisplayName(
      "More than is left on the tender, counting what is owed back to its card, is refused")
  void moreThanIsLeftIsRefused() {
    ScriptedDb db =
        new ScriptedDb()
            .answer(List.of(TENDER_LOCK), tender(STORE))
            .answer(List.of(SPOKEN_FOR), row("spoken_for", new BigDecimal("30.00")));

    ApiException e =
        assertThrows(
            ApiException.class,
            () -> over(db).createRefundGuarded(refund("0.01", null), announced(), store -> {}));

    assertEquals("REFUND_EXCEEDS_PAYMENT", e.code());
    assertEquals(0, db.count(BOOK));
  }
}
