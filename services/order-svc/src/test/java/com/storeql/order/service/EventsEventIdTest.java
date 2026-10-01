package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.ContainerRefund;
import com.storeql.order.domain.Domain.GiftCard;
import com.storeql.order.domain.Domain.GiftCardTransaction;
import com.storeql.order.domain.Domain.Order;
import com.storeql.order.domain.Domain.OrderItem;
import com.storeql.order.domain.Domain.RestockLine;
import com.storeql.order.domain.Domain.Return;
import com.storeql.order.domain.Domain.ReturnItem;
import com.storeql.order.domain.RecallNotice;
import com.storeql.service.OutboxRow;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every event this service announces carries a UUIDv7 {@code eventId}: consumers dedupe on it, and
 * one that finds none skips the event as malformed (notification-svc's webhook fan-out dropped
 * {@code OrderPlaced} that way). Each builder is called and read back, and a builder added to
 * {@link Events} without a line here fails the last assertion, so the rule holds for the next one.
 */
class EventsEventIdTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID ORDER = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();
  private static final UUID LOGIN = Ids.newId();
  private static final UUID VARIANT = Ids.newId();
  private static final UUID RETURN = Ids.newId();

  private final Set<String> built = new HashSet<>();

  private void carriesEventId(String builder, OutboxRow row) {
    built.add(builder);
    JsonObject json = Json.createReader(new StringReader(row.payload())).readObject();
    assertTrue(json.containsKey("eventId"), builder + " has no eventId: " + row.payload());
    // Read as the platform reads every id: canonical form, version 7.
    UUID id = Ids.parse(json.getString("eventId"));
    assertNotEquals(TENANT, id, builder);
  }

  private static Order order() {
    return new Order(
        ORDER,
        TENANT,
        STORE,
        CUSTOMER,
        LOGIN,
        "ONLINE",
        "PICKUP",
        "CONFIRMED",
        BigDecimal.TEN,
        BigDecimal.ONE,
        BigDecimal.ZERO,
        new BigDecimal("11.00"),
        "USD",
        null,
        null,
        Instant.now(),
        Instant.now(),
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        BigDecimal.ZERO,
        null);
  }

  @Test
  void everyBuilderCarriesAV7EventId() {
    var item =
        new OrderItem(
            Ids.newId(),
            TENANT,
            ORDER,
            VARIANT,
            BigDecimal.ONE,
            BigDecimal.TEN,
            BigDecimal.TEN,
            null,
            null);
    var returned =
        new ReturnItem(
            Ids.newId(), TENANT, RETURN, VARIANT, BigDecimal.ONE, BigDecimal.TEN, "SEALED");
    var card =
        new GiftCard(
            Ids.newId(),
            TENANT,
            STORE,
            "ABCD-EFGH-JKLM-NPQR",
            BigDecimal.TEN,
            BigDecimal.TEN,
            GiftCard.STATUS_ACTIVE,
            "USD",
            Instant.now(),
            null);
    var tx =
        new GiftCardTransaction(
            Ids.newId(),
            TENANT,
            card.id(),
            GiftCardTransaction.TX_ISSUE,
            BigDecimal.TEN,
            BigDecimal.ZERO,
            BigDecimal.TEN,
            null,
            null,
            Instant.now());

    // The OrderPlaced payload that carried no eventId, in each of its shapes.
    carriesEventId(
        "orderPlaced", Events.orderPlaced(TENANT, ORDER, "ONLINE", CUSTOMER, LOGIN, STORE));
    carriesEventId(
        "orderPlaced", Events.orderPlaced(TENANT, ORDER, "ONLINE", null, null, STORE, Ids.newId()));
    carriesEventId(
        "orderPlaced",
        Events.orderPlaced(
            TENANT,
            ORDER,
            "ONLINE",
            CUSTOMER,
            LOGIN,
            STORE,
            null,
            Instant.now(),
            Instant.now().plusSeconds(3600),
            "Asia/Tokyo"));
    carriesEventId("layawayCreated", Events.layawayCreated(TENANT, Ids.newId()));
    carriesEventId("layawayCompleted", Events.layawayCompleted(TENANT, Ids.newId()));
    carriesEventId("layawayCancelled", Events.layawayCancelled(TENANT, Ids.newId()));

    carriesEventId("orderCancelled", Events.orderCancelled(TENANT, ORDER, "x"));
    carriesEventId(
        "orderCancelled", Events.orderCancelled(TENANT, ORDER, null, "ONLINE", "PICKUP"));
    carriesEventId(
        "orderConfirmed",
        Events.orderConfirmed(
            TENANT,
            ORDER,
            STORE,
            "ONLINE",
            CUSTOMER,
            BigDecimal.TEN,
            BigDecimal.ONE,
            "USD",
            List.of(item)));
    carriesEventId("orderFulfilled", Events.orderFulfilled(TENANT, ORDER, STORE, List.of(item)));
    carriesEventId(
        "orderReturned",
        Events.orderReturned(
            TENANT,
            ORDER,
            RETURN,
            STORE,
            List.of(returned),
            BigDecimal.TEN,
            "ORIGINAL",
            "USD",
            CUSTOMER,
            null,
            false,
            null));
    carriesEventId(
        "orderVoided",
        Events.orderVoided(
            TENANT, ORDER, STORE, CUSTOMER, List.of(new RestockLine(VARIANT, BigDecimal.ONE))));
    carriesEventId("giftCardLoaded", Events.giftCardLoaded(card, tx, "CASH"));
    carriesEventId(
        "giftCardLoadedByHand", Events.giftCardLoadedByHand(card, tx, "GOODWILL", "a note"));
    carriesEventId("giftCardLoadedBySale", Events.giftCardLoadedBySale(card, tx, "CASH"));
    carriesEventId(
        "orderPriceOverdue",
        Events.orderPriceOverdue(
            TENANT, ORDER, STORE, java.time.Instant.now(), new BigDecimal("12.00")));
    carriesEventId("giftCardLoadedByReturn", Events.giftCardLoadedByReturn(card, tx, RETURN));
    carriesEventId("giftCardRedeemed", Events.giftCardRedeemed(card, tx, order()));
    carriesEventId(
        "orderDispatched",
        Events.orderDispatched(TENANT, ORDER, STORE, CUSTOMER, LOGIN, "DHL", "JD01", 2));
    carriesEventId(
        "orderCollected",
        Events.orderCollected(TENANT, ORDER, STORE, CUSTOMER, LOGIN, "A. Shopper"));
    carriesEventId(
        "orderLineShortClosed",
        Events.orderLineShortClosed(order(), VARIANT, "Milk", BigDecimal.ONE, BigDecimal.TEN));
    carriesEventId(
        "orderLineSubstituted",
        Events.orderLineSubstituted(
            order(),
            VARIANT,
            "Milk",
            Ids.newId(),
            "Oat milk",
            BigDecimal.ONE,
            BigDecimal.TEN,
            BigDecimal.ONE));
    carriesEventId(
        "containerDepositRefunded",
        Events.containerDepositRefunded(
            new ContainerRefund(
                Ids.newId(),
                TENANT,
                STORE,
                null,
                "USD",
                2,
                new BigDecimal("0.20"),
                "NATIONAL",
                Ids.newId().toString(),
                Ids.newId(),
                Instant.now(),
                List.of())));
    var ret =
        new Return(
            RETURN,
            TENANT,
            null,
            STORE,
            "no receipt",
            BigDecimal.TEN,
            "STORE_CREDIT",
            "COMPLETED",
            Instant.now(),
            Instant.now(),
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            List.of(),
            null,
            null,
            true,
            CUSTOMER,
            null);
    carriesEventId(
        "noReceiptReturnRecorded",
        Events.noReceiptReturnRecorded(
            ret,
            "USD",
            BigDecimal.ONE,
            List.of(
                new ReturnItem(
                    Ids.newId(),
                    TENANT,
                    RETURN,
                    VARIANT,
                    BigDecimal.ONE,
                    BigDecimal.TEN,
                    "SEALED",
                    BigDecimal.TEN,
                    BigDecimal.ONE)),
            null));
    var notice =
        new RecallNotice.Notice(
            Ids.newId(),
            TENANT,
            Ids.newId(),
            "R-1",
            "choking",
            "a small part",
            "stop using it",
            Set.of(RecallNotice.Remedy.values()[0]),
            null,
            null,
            null,
            ORDER,
            STORE,
            "ONLINE",
            CUSTOMER,
            LOGIN,
            null,
            true,
            Instant.now(),
            RecallNotice.Status.values()[0],
            Instant.now(),
            null,
            null);
    carriesEventId(
        "recallNoticeIssued",
        Events.recallNoticeIssued(
            notice,
            List.of(
                new RecallNotice.Line(
                    Ids.newId(),
                    VARIANT,
                    "Toy",
                    "SKU-1",
                    "LOT1",
                    LocalDate.now(),
                    BigDecimal.ONE,
                    "BATCH"))));

    // A builder added to Events must be added above: the names it declares are the names built.
    Set<String> declared = new TreeSet<>();
    for (Method m : Events.class.getDeclaredMethods()) {
      if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == OutboxRow.class) {
        if (!Modifier.isPrivate(m.getModifiers())) declared.add(m.getName());
      }
    }
    Set<String> missing = new TreeSet<>(declared);
    missing.removeAll(built);
    assertEquals(Set.<String>of(), missing, "builders with no eventId check in this test");
  }
}
