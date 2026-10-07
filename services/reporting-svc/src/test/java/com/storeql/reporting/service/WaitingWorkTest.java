package com.storeql.reporting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.reporting.domain.PendingWork.Customer;
import com.storeql.reporting.domain.PendingWork.Item;
import com.storeql.reporting.domain.PendingWork.Kind;
import com.storeql.reporting.domain.PendingWork.Payment;
import com.storeql.reporting.domain.PendingWork.Purchase;
import com.storeql.reporting.domain.PendingWork.Readings;
import com.storeql.reporting.domain.PendingWork.Report;
import com.storeql.web.PendingWorkCount;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The waiting-work answer is assembled from what three services said, and from nothing else: items
 * in a fixed order, a count that could not be had is null (never zero) and named, and the one note
 * the screen needs for a zero that does not mean "nothing waits".
 */
class WaitingWorkTest {

  private static final Instant NOW = Instant.parse("2026-10-07T09:30:00Z");

  private static final Map<Kind, String> OPENS = new EnumMap<>(Kind.class);

  static {
    for (Kind kind : Kind.values()) OPENS.put(kind, "/test/" + kind.name().toLowerCase());
  }

  private static Optional<Purchase> purchase(long po, long runs, long invoices, long syncs) {
    return Optional.of(new Purchase(po, runs, invoices, syncs, true));
  }

  private static Readings everything() {
    return new Readings(
        purchase(3, 2, 5, 1), Optional.of(new Payment(4)), Optional.of(new Customer(7)));
  }

  private static Item item(Report report, Kind kind) {
    return report.items().stream().filter(i -> i.kind() == kind).findFirst().orElseThrow();
  }

  private static List<Kind> kinds(Report report) {
    return report.items().stream().map(Item::kind).toList();
  }

  @Test
  @DisplayName("The six kinds are always answered, in the order the screen lists them")
  void itemsAreInAFixedOrder() {
    assertEquals(
        List.of(
            Kind.PURCHASE_ORDER_APPROVAL,
            Kind.PAYMENT_RUN,
            Kind.SUPPLIER_INVOICE,
            Kind.ACCOUNTING_SYNC,
            Kind.CARD_REFUND,
            Kind.PRIVACY_REQUEST),
        kinds(WaitingWork.assemble(NOW, everything(), OPENS)));
    assertEquals(
        kinds(WaitingWork.assemble(NOW, everything(), OPENS)),
        kinds(
            WaitingWork.assemble(
                NOW, new Readings(Optional.empty(), Optional.empty(), Optional.empty()), OPENS)));
  }

  @Test
  @DisplayName("All reachable: each count, label and route is its own; nothing is unreachable")
  void allReachable() {
    Report report = WaitingWork.assemble(NOW, everything(), OPENS);

    assertEquals(NOW, report.generatedAt());
    assertEquals(List.of(), report.unreachable());
    assertEquals(
        List.of(3L, 2L, 5L, 1L, 4L, 7L), report.items().stream().map(Item::count).toList());
    for (Item item : report.items()) {
      assertEquals("/test/" + item.kind().name().toLowerCase(), item.opens());
      assertNull(item.note());
    }
    assertEquals(
        "Purchase orders waiting for approval", item(report, Kind.PURCHASE_ORDER_APPROVAL).label());
  }

  @Test
  @DisplayName("Every kind has words of its own for a label, never a code")
  void labelsAreWords() {
    HashSet<String> seen = new HashSet<>();
    for (Item item : WaitingWork.assemble(NOW, everything(), OPENS).items()) {
      assertNotNull(item.label());
      assertTrue(item.label().contains(" "), item.label());
      assertTrue(seen.add(item.label()), "two kinds share the label " + item.label());
    }
  }

  @Test
  @DisplayName("A reachable source that holds none says zero; zero is not null")
  void zeroIsZero() {
    Report report =
        WaitingWork.assemble(
            NOW,
            new Readings(
                purchase(0, 0, 0, 0), Optional.of(new Payment(0)), Optional.of(new Customer(0))),
            OPENS);

    for (Item item : report.items()) assertEquals(0L, item.count(), item.kind().name());
    assertEquals(List.of(), report.unreachable());
  }

  @Test
  @DisplayName("purchase-svc unreachable: its four kinds are null and named, the others answered")
  void purchaseUnreachable() {
    Report report =
        WaitingWork.assemble(
            NOW,
            new Readings(
                Optional.empty(), Optional.of(new Payment(4)), Optional.of(new Customer(7))),
            OPENS);

    assertEquals(
        List.of(
            Kind.PURCHASE_ORDER_APPROVAL,
            Kind.PAYMENT_RUN,
            Kind.SUPPLIER_INVOICE,
            Kind.ACCOUNTING_SYNC),
        report.unreachable());
    for (Kind kind : report.unreachable()) assertNull(item(report, kind).count(), kind.name());
    assertEquals(4L, item(report, Kind.CARD_REFUND).count());
    assertEquals(7L, item(report, Kind.PRIVACY_REQUEST).count());
    // The route is still given: a person can open the screen even when the figure is missing.
    assertEquals("/test/payment_run", item(report, Kind.PAYMENT_RUN).opens());
  }

  @Test
  @DisplayName("payment-svc unreachable: only the card refunds are null and named")
  void paymentUnreachable() {
    Report report =
        WaitingWork.assemble(
            NOW,
            new Readings(purchase(3, 2, 5, 1), Optional.empty(), Optional.of(new Customer(7))),
            OPENS);

    assertEquals(List.of(Kind.CARD_REFUND), report.unreachable());
    assertNull(item(report, Kind.CARD_REFUND).count());
    assertEquals(3L, item(report, Kind.PURCHASE_ORDER_APPROVAL).count());
    assertEquals(7L, item(report, Kind.PRIVACY_REQUEST).count());
  }

  @Test
  @DisplayName("customer-svc unreachable: only the privacy requests are null and named")
  void customerUnreachable() {
    Report report =
        WaitingWork.assemble(
            NOW,
            new Readings(purchase(3, 2, 5, 1), Optional.of(new Payment(4)), Optional.empty()),
            OPENS);

    assertEquals(List.of(Kind.PRIVACY_REQUEST), report.unreachable());
    assertNull(item(report, Kind.PRIVACY_REQUEST).count());
    assertEquals(4L, item(report, Kind.CARD_REFUND).count());
  }

  @Test
  @DisplayName("Nothing reachable: every count is null and every kind is named, never six zeros")
  void nothingReachable() {
    Report report =
        WaitingWork.assemble(
            NOW, new Readings(Optional.empty(), Optional.empty(), Optional.empty()), OPENS);

    assertEquals(List.of(Kind.values()), report.unreachable());
    for (Item item : report.items()) assertNull(item.count(), item.kind().name());
  }

  @Test
  @DisplayName(
      "No approval limits set: a note on purchase orders alone, whose zero is still a zero")
  void approvalsNotRoutedGetsANote() {
    Report report =
        WaitingWork.assemble(
            NOW,
            new Readings(
                Optional.of(new Purchase(0, 2, 5, 1, false)),
                Optional.of(new Payment(4)),
                Optional.of(new Customer(7))),
            OPENS);

    Item orders = item(report, Kind.PURCHASE_ORDER_APPROVAL);
    assertEquals("Approval limits are not set, so orders are not held for approval", orders.note());
    assertEquals(0L, orders.count());
    for (Item other : report.items()) {
      if (other.kind() != Kind.PURCHASE_ORDER_APPROVAL)
        assertNull(other.note(), other.kind().name());
    }
    assertEquals(List.of(), report.unreachable());
  }

  @Test
  @DisplayName("Approval limits set: no note")
  void approvalsRoutedHasNoNote() {
    assertNull(
        item(WaitingWork.assemble(NOW, everything(), OPENS), Kind.PURCHASE_ORDER_APPROVAL).note());
  }

  @Test
  @DisplayName(
      "A note is only for an answer that was received: an unreachable purchase-svc has none")
  void noNoteWhenPurchaseUnreachable() {
    Report report =
        WaitingWork.assemble(
            NOW, new Readings(Optional.empty(), Optional.empty(), Optional.empty()), OPENS);

    assertNull(item(report, Kind.PURCHASE_ORDER_APPROVAL).note());
  }

  @Test
  @DisplayName("A count that stopped at the cap, or is past it, is capped; one below it is not")
  void aCountAtTheCapIsCapped() {
    int cap = PendingWorkCount.CAP;
    Report report =
        WaitingWork.assemble(
            NOW,
            new Readings(
                Optional.of(new Purchase(cap - 1, cap, cap + 1, 0, true)),
                Optional.of(new Payment(cap)),
                Optional.of(new Customer(cap - 1))),
            OPENS);

    assertFalse(item(report, Kind.PURCHASE_ORDER_APPROVAL).capped());
    assertTrue(item(report, Kind.PAYMENT_RUN).capped());
    assertTrue(item(report, Kind.SUPPLIER_INVOICE).capped());
    assertFalse(item(report, Kind.ACCOUNTING_SYNC).capped());
    assertTrue(item(report, Kind.CARD_REFUND).capped());
    assertFalse(item(report, Kind.PRIVACY_REQUEST).capped());
    // The figure is still the figure: capping is said beside it, never done to it.
    assertEquals((long) cap, item(report, Kind.PAYMENT_RUN).count());
    assertEquals(cap + 1L, item(report, Kind.SUPPLIER_INVOICE).count());
  }

  @Test
  @DisplayName("Nothing waiting, a few waiting and a source that did not answer are never capped")
  void smallAndUnknownCountsAreNotCapped() {
    for (Item item : WaitingWork.assemble(NOW, everything(), OPENS).items()) {
      assertFalse(item.capped(), item.kind().name());
    }
    Report zero =
        WaitingWork.assemble(
            NOW,
            new Readings(
                purchase(0, 0, 0, 0), Optional.of(new Payment(0)), Optional.of(new Customer(0))),
            OPENS);
    for (Item item : zero.items()) assertFalse(item.capped(), item.kind().name());

    Report none =
        WaitingWork.assemble(
            NOW, new Readings(Optional.empty(), Optional.empty(), Optional.empty()), OPENS);
    for (Item item : none.items()) {
      assertNull(item.count(), item.kind().name());
      assertFalse(item.capped(), item.kind().name());
    }
  }

  @Test
  @DisplayName("A kind with no screen to open has no route: the tile is shown, not linked")
  void aKindWithNoScreenHasNoRoute() {
    Map<Kind, String> noScreen = new EnumMap<>(OPENS);
    noScreen.remove(Kind.CARD_REFUND);

    Report report = WaitingWork.assemble(NOW, everything(), noScreen);

    assertNull(item(report, Kind.CARD_REFUND).opens());
    assertEquals(4L, item(report, Kind.CARD_REFUND).count());
    assertEquals("/test/payment_run", item(report, Kind.PAYMENT_RUN).opens());
  }
}
