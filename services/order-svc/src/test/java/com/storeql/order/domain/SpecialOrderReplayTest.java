package com.storeql.order.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.SpecialOrder;
import com.storeql.order.domain.Domain.SpecialOrderItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When a retry of a special order under one Idempotency-Key is the same request and when it is
 * another: pure, written before the code. The first is answered with the order that stands, the
 * second is refused; "the same" is judged as the tables keep an order, so a price typed with more
 * places than its column holds is still the request that was made.
 */
class SpecialOrderReplayTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID CUSTOMER = Ids.newId();
  private static final UUID APPLES = Ids.newId();
  private static final UUID PEARS = Ids.newId();

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  private static SpecialOrderItem line(UUID variant, String qty, String price, String notes) {
    return new SpecialOrderItem(
        Ids.newId(), TENANT, Ids.newId(), variant, d(qty), d(price), d(price), notes);
  }

  /** An order as the request would write it, with the store and notes under test. */
  private static SpecialOrder order(UUID store, UUID customer, String name, String notes) {
    return new SpecialOrder(
        Ids.newId(),
        TENANT,
        store,
        customer,
        name,
        "+441632960000",
        "sam@example.org",
        "1 Park Row",
        LocalDate.of(2026, 11, 3),
        notes,
        SpecialOrder.STATUS_PENDING,
        d("10.00"),
        d("10.00"),
        "GBP",
        Ids.newId().toString(),
        Instant.parse("2026-10-01T09:00:00Z"),
        Instant.parse("2026-10-01T09:00:00Z"));
  }

  private static SpecialOrder order() {
    return order(STORE, CUSTOMER, "Sam", "ring first");
  }

  private static List<SpecialOrderItem> lines() {
    return List.of(line(APPLES, "2", "3.00", null), line(PEARS, "1", "4.00", "ripe"));
  }

  @Test
  @DisplayName("The same request again, with new ids and a new moment, is the same request")
  void theSameRequestIsTheSameRequest() {
    assertThat(SpecialOrderReplay.isSameRequest(order(), lines(), order(), lines()), is(true));
  }

  @Test
  @DisplayName("What the order has become since, and its own ids, are not part of the request")
  void whatTheOrderHasBecomeIsNotPartOfIt() {
    SpecialOrder made = order();
    SpecialOrder confirmed =
        new SpecialOrder(
            made.id(),
            made.tenantId(),
            made.storeId(),
            made.customerId(),
            made.customerName(),
            made.customerPhone(),
            made.customerEmail(),
            made.deliveryAddress(),
            made.requestedDeliveryDate(),
            made.notes(),
            SpecialOrder.STATUS_CONFIRMED,
            made.subtotal(),
            made.total(),
            made.currency(),
            made.idempotencyKey(),
            made.createdAt(),
            Instant.parse("2026-10-02T09:00:00Z"));
    assertThat(SpecialOrderReplay.isSameRequest(confirmed, lines(), order(), lines()), is(true));
  }

  @Test
  @DisplayName("The lines may arrive in another order; two of a kind stay two")
  void theOrderOfTheLinesDoesNotMatter() {
    List<SpecialOrderItem> reversed =
        List.of(line(PEARS, "1", "4.00", "ripe"), line(APPLES, "2", "3.00", null));
    assertThat(SpecialOrderReplay.isSameRequest(order(), lines(), order(), reversed), is(true));

    List<SpecialOrderItem> twice =
        List.of(line(APPLES, "1", "3.00", null), line(APPLES, "1", "3.00", null));
    List<SpecialOrderItem> once = List.of(line(APPLES, "1", "3.00", null));
    assertThat(SpecialOrderReplay.isSameRequest(order(), twice, order(), twice), is(true));
    assertThat(SpecialOrderReplay.isSameRequest(order(), twice, order(), once), is(false));
    assertThat(SpecialOrderReplay.isSameRequest(order(), once, order(), twice), is(false));
  }

  @Test
  @DisplayName("A price or quantity is judged as its column keeps it, whatever the typed scale")
  void figuresAreJudgedAtTheScaleTheTablesKeep() {
    List<SpecialOrderItem> stored = List.of(line(APPLES, "2.000", "3.01", null));
    // 3.005 is kept as 3.01 (round half up); 2 and 2.0 are the same two.
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(), stored, order(), List.of(line(APPLES, "2", "3.005", null))),
        is(true));
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(), stored, order(), List.of(line(APPLES, "2.0", "3.01", null))),
        is(true));
    // 3.004 is kept as 3.00: another price.
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(), stored, order(), List.of(line(APPLES, "2", "3.004", null))),
        is(false));
  }

  @Test
  @DisplayName("Another store, customer, name or note is another request")
  void anotherHeaderIsAnotherRequest() {
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(), lines(), order(Ids.newId(), CUSTOMER, "Sam", "ring first"), lines()),
        is(false));
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(), lines(), order(STORE, Ids.newId(), "Sam", "ring first"), lines()),
        is(false));
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(), lines(), order(STORE, null, "Sam", "ring first"), lines()),
        is(false));
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(), lines(), order(STORE, CUSTOMER, "Sue", "ring first"), lines()),
        is(false));
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(), lines(), order(STORE, CUSTOMER, "Sam", "leave it"), lines()),
        is(false));
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(), lines(), order(STORE, CUSTOMER, "Sam", null), lines()),
        is(false));
  }

  @Test
  @DisplayName("Another contact, address or day for delivery is another request")
  void anotherDeliveryIsAnotherRequest() {
    SpecialOrder base = order();
    assertThat(
        SpecialOrderReplay.isSameRequest(base, lines(), withDay(base, 4), lines()), is(false));
    assertThat(
        SpecialOrderReplay.isSameRequest(base, lines(), withDay(base, null), lines()), is(false));
    assertThat(
        SpecialOrderReplay.isSameRequest(base, lines(), withAddress(base, "2 Park Row"), lines()),
        is(false));
    assertThat(
        SpecialOrderReplay.isSameRequest(base, lines(), withAddress(base, null), lines()),
        is(false));
  }

  @Test
  @DisplayName("Another product, quantity, price or line note is another request")
  void anotherLineIsAnotherRequest() {
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(),
            lines(),
            order(),
            List.of(line(APPLES, "2", "3.00", null), line(APPLES, "1", "4.00", "ripe"))),
        is(false));
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(),
            lines(),
            order(),
            List.of(line(APPLES, "3", "3.00", null), line(PEARS, "1", "4.00", "ripe"))),
        is(false));
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(),
            lines(),
            order(),
            List.of(line(APPLES, "2", "3.50", null), line(PEARS, "1", "4.00", "ripe"))),
        is(false));
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(),
            lines(),
            order(),
            List.of(line(APPLES, "2", "3.00", null), line(PEARS, "1", "4.00", "green"))),
        is(false));
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(),
            lines(),
            order(),
            List.of(line(APPLES, "2", "3.00", null), line(PEARS, "1", "4.00", null))),
        is(false));
    assertThat(
        SpecialOrderReplay.isSameRequest(
            order(), lines(), order(), List.of(line(APPLES, "2", "3.00", null))),
        is(false));
  }

  private static SpecialOrder withDay(SpecialOrder o, Integer dayOfNovember) {
    return new SpecialOrder(
        o.id(),
        o.tenantId(),
        o.storeId(),
        o.customerId(),
        o.customerName(),
        o.customerPhone(),
        o.customerEmail(),
        o.deliveryAddress(),
        dayOfNovember == null ? null : LocalDate.of(2026, 11, dayOfNovember),
        o.notes(),
        o.status(),
        o.subtotal(),
        o.total(),
        o.currency(),
        o.idempotencyKey(),
        o.createdAt(),
        o.updatedAt());
  }

  private static SpecialOrder withAddress(SpecialOrder o, String address) {
    return new SpecialOrder(
        o.id(),
        o.tenantId(),
        o.storeId(),
        o.customerId(),
        o.customerName(),
        o.customerPhone(),
        o.customerEmail(),
        address,
        o.requestedDeliveryDate(),
        o.notes(),
        o.status(),
        o.subtotal(),
        o.total(),
        o.currency(),
        o.idempotencyKey(),
        o.createdAt(),
        o.updatedAt());
  }

  private static SpecialOrder orderIn(String currency) {
    SpecialOrder o = order();
    return new SpecialOrder(
        o.id(),
        o.tenantId(),
        o.storeId(),
        o.customerId(),
        o.customerName(),
        o.customerPhone(),
        o.customerEmail(),
        o.deliveryAddress(),
        o.requestedDeliveryDate(),
        o.notes(),
        o.status(),
        o.subtotal(),
        o.total(),
        currency,
        o.idempotencyKey(),
        o.createdAt(),
        o.updatedAt());
  }

  @Test
  @DisplayName("A dinar price is judged to the fils: its third decimal is part of the request")
  void aDinarPriceIsJudgedToItsThirdDecimal() {
    SpecialOrder kwd = orderIn("KWD");
    List<SpecialOrderItem> stored = List.of(line(APPLES, "2", "3.005", null));
    assertThat(
        SpecialOrderReplay.isSameRequest(
            kwd, stored, kwd, List.of(line(APPLES, "2", "3.0050", null))),
        is(true));
    // Two decimals would have called 3.004 and 3.005 the same 3.00/3.01 pair; in dinars they
    // differ.
    assertThat(
        SpecialOrderReplay.isSameRequest(
            kwd, stored, kwd, List.of(line(APPLES, "2", "3.004", null))),
        is(false));
  }

  @Test
  @DisplayName("A yen price is judged in whole yen, however its column wrote it")
  void aYenPriceIsJudgedInWholeYen() {
    SpecialOrder jpy = orderIn("JPY");
    List<SpecialOrderItem> stored = List.of(line(APPLES, "2", "1234.00", null));
    assertThat(
        SpecialOrderReplay.isSameRequest(
            jpy, stored, jpy, List.of(line(APPLES, "2", "1234", null))),
        is(true));
    assertThat(
        SpecialOrderReplay.isSameRequest(
            jpy, stored, jpy, List.of(line(APPLES, "2", "1235", null))),
        is(false));
  }
}
