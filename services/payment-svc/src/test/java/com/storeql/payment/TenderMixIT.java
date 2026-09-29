package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.domain.Domain.RefundTender;
import com.storeql.payment.domain.Domain.TenderMixRow;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.payment.service.TenderMixService;
import com.storeql.service.OutboxRow;
import com.storeql.test.PostgresSupport;
import com.storeql.web.ApiException;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the tender-mix report against real Postgres.
 *
 * <p>The report is almost entirely one SQL statement, and the parts of it worth pinning are the
 * ones a unit test could not reach: that a method appearing on only one side of the UNION still
 * produces a row, that a failed tender counts without contributing money, and that the shares add
 * up to the whole.
 */
@HelidonTest
class TenderMixIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "payment");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject TenderMixService service;
  @Inject PaymentRepository repo;
  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  /**
   * The shape of a real trading day: cash and card taken, one card sale refunded, one card attempt
   * declined. Each of those has to land in a different column.
   */
  @Test
  void splitsTheTakeByMethodAndSubtractsRefundsWithinTheirOwnMethod() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();

    UUID card = capture(tenant, order, "100.00", PaymentTender.METHOD_CARD, "CAPTURED");
    capture(tenant, order, "60.00", PaymentTender.METHOD_CASH, "CAPTURED");
    // A declined card attempt: it happened, but no money moved.
    capture(tenant, Ids.newId(), "45.00", PaymentTender.METHOD_CARD, "FAILED");
    refund(tenant, order, card, "40.00", PaymentTender.METHOD_CARD);

    List<TenderMixRow> rows = service.tenderMix(tenant, null, null, null);
    assertThat(rows, hasSize(2));

    TenderMixRow cardRow = row(rows, PaymentTender.METHOD_CARD);
    assertThat(cardRow.capturedAmount().compareTo(new BigDecimal("100")), is(0));
    assertThat(cardRow.capturedCount(), is(1L));
    assertThat(cardRow.refundedAmount().compareTo(new BigDecimal("40")), is(0));
    assertThat(cardRow.refundedCount(), is(1L));
    // The decline is counted and contributes nothing to either money column.
    assertThat(cardRow.failedCount(), is(1L));
    assertThat(cardRow.netAmount().compareTo(new BigDecimal("60")), is(0));

    TenderMixRow cashRow = row(rows, PaymentTender.METHOD_CASH);
    assertThat(cashRow.netAmount().compareTo(new BigDecimal("60")), is(0));
    assertThat(cashRow.failedCount(), is(0L));

    // Net 60 each out of 120: the shares are halves and they add to the whole.
    assertThat(cardRow.shareOfNet().compareTo(new BigDecimal("50.0")), is(0));
    assertThat(cashRow.shareOfNet().compareTo(new BigDecimal("50.0")), is(0));

    // Ordered by net descending — the tie breaks on method name.
    assertThat(rows.get(0).method(), is(PaymentTender.METHOD_CARD));
  }

  /**
   * The case the UNION exists for: a refund issued through a method that took no money in the
   * window. A join between the two tables would have dropped this row, and it is the one an
   * accountant chases.
   */
  @Test
  void aMethodWithRefundsButNoCapturesStillAppears() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();

    UUID card = capture(tenant, order, "80.00", PaymentTender.METHOD_CARD, "CAPTURED");
    // Refunded onto a voucher rather than back to the card.
    refund(tenant, order, card, "25.00", PaymentTender.METHOD_VOUCHER);

    List<TenderMixRow> rows = service.tenderMix(tenant, null, null, null);
    TenderMixRow voucher = row(rows, PaymentTender.METHOD_VOUCHER);
    assertThat(voucher.capturedAmount().compareTo(BigDecimal.ZERO), is(0));
    assertThat(voucher.refundedAmount().compareTo(new BigDecimal("25")), is(0));
    assertThat(voucher.netAmount().compareTo(new BigDecimal("-25")), is(0));

    // A negative row still gets a share, of a total that is still positive: 80 - 25 = 55.
    assertThat(voucher.shareOfNet().compareTo(new BigDecimal("-45.5")), is(0));
  }

  /**
   * A window in which more went out than came in is a real day — the one after a recall. A share of
   * a non-positive total is meaningless, so it is null rather than a misleading percentage.
   */
  @Test
  void shareIsNullWhenThereIsNoPositiveTotalToShare() {
    UUID tenant = Ids.newId();
    UUID order = Ids.newId();

    UUID card = capture(tenant, order, "30.00", PaymentTender.METHOD_CARD, "CAPTURED");
    refund(tenant, order, card, "30.00", PaymentTender.METHOD_CARD);

    List<TenderMixRow> rows = service.tenderMix(tenant, null, null, null);
    assertThat(row(rows, PaymentTender.METHOD_CARD).shareOfNet(), is(nullValue()));
  }

  /** The window bounds both sides of the UNION, and is validated before either is read. */
  @Test
  void isBoundedByItsWindowAndTenantScoped() {
    UUID tenant = Ids.newId();
    UUID other = Ids.newId();
    capture(tenant, Ids.newId(), "10.00", PaymentTender.METHOD_CASH, "CAPTURED");
    capture(other, Ids.newId(), "999.00", PaymentTender.METHOD_CASH, "CAPTURED");

    Instant now = Instant.now();
    // A window that closed before any of this happened sees none of it.
    assertThat(
        service.tenderMix(
            tenant, null, now.minus(10, ChronoUnit.DAYS), now.minus(9, ChronoUnit.DAYS)),
        hasSize(0));
    // One that contains it sees exactly its own tenant's 10.00.
    List<TenderMixRow> inWindow =
        service.tenderMix(
            tenant, null, now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));
    assertThat(inWindow, hasSize(1));
    assertThat(
        row(inWindow, PaymentTender.METHOD_CASH).netAmount().compareTo(BigDecimal.TEN), is(0));

    try {
      service.tenderMix(tenant, null, now, now.minus(1, ChronoUnit.HOURS));
      throw new AssertionError("a backwards window should be rejected");
    } catch (ApiException e) {
      assertThat(e.code(), is("PAYMENT_INVALID_PERIOD"));
    }
  }

  // ── access control (SJ-D74 shape): a store named is checked, none named reads what the ──────
  // ── caller is held to, added together, and never a store the caller does not keep ────────────

  /**
   * A manager held to one store, naming none, sees only that store's take — never another store's
   * or the whole business's.
   */
  @Test
  void aManagerHeldToOneStoreNamingNoneSeesOnlyThatStore() {
    UUID tenant = Ids.newId();
    UUID storeA = Ids.newId();
    UUID storeB = Ids.newId();
    UUID storeC = Ids.newId();
    capture(tenant, Ids.newId(), storeA, "10.00", PaymentTender.METHOD_CASH, "CAPTURED");
    capture(tenant, Ids.newId(), storeB, "20.00", PaymentTender.METHOD_CASH, "CAPTURED");
    capture(tenant, Ids.newId(), storeC, "30.00", PaymentTender.METHOD_CASH, "CAPTURED");

    Caller manager = new Caller(tenant, Ids.newId(), "MANAGER", storeA);
    Answer answer = ItCalls.get(target, "/admin/reports/tender-mix", manager);
    assertThat(answer.body().toString(), answer.status(), is(200));
    JsonArray rows = answer.list();
    assertThat(rows, hasSize(1));
    BigDecimal captured = rows.getJsonObject(0).getJsonNumber("capturedAmount").bigDecimalValue();
    assertThat(captured.compareTo(new BigDecimal("10.00")), is(0));
  }

  /** A manager held to one store naming another is refused, never shown its take. */
  @Test
  void aManagerHeldToOneStoreNamingAnotherIsRefused() {
    UUID tenant = Ids.newId();
    UUID storeA = Ids.newId();
    UUID storeB = Ids.newId();
    capture(tenant, Ids.newId(), storeA, "10.00", PaymentTender.METHOD_CASH, "CAPTURED");
    capture(tenant, Ids.newId(), storeB, "20.00", PaymentTender.METHOD_CASH, "CAPTURED");

    Caller manager = new Caller(tenant, Ids.newId(), "MANAGER", storeA);
    Answer answer = ItCalls.get(target, "/admin/reports/tender-mix?storeId=" + storeB, manager);
    assertThat(answer.status(), is(403));
    assertThat(answer.code(), is("STORE_ACCESS_DENIED"));
  }

  /**
   * A manager held to two stores, naming none, sees exactly those two stores' take added together —
   * including a refund whose own {@code store_id} is unset and is resolved back to the store of the
   * payment it refunds — and never a third store's.
   */
  @Test
  void aManagerHeldToTwoStoresSeesExactlyThoseAddedTogether() {
    UUID tenant = Ids.newId();
    UUID storeA = Ids.newId();
    UUID storeB = Ids.newId();
    UUID storeC = Ids.newId();
    UUID saleAtA = Ids.newId();
    UUID cardAtA = capture(tenant, saleAtA, storeA, "10.00", PaymentTender.METHOD_CARD, "CAPTURED");
    capture(tenant, Ids.newId(), storeB, "20.00", PaymentTender.METHOD_CASH, "CAPTURED");
    capture(tenant, Ids.newId(), storeC, "999.00", PaymentTender.METHOD_CASH, "CAPTURED");
    // A refund of the store-A card sale: refund_tenders carries no store_id of its own, so this
    // must still count towards store A through the payment it refunds.
    refund(tenant, saleAtA, cardAtA, "4.00", PaymentTender.METHOD_CARD);

    Caller manager = twoStoreManager(tenant, storeA, storeB);
    Answer answer = ItCalls.get(target, "/admin/reports/tender-mix", manager);
    assertThat(answer.body().toString(), answer.status(), is(200));
    JsonArray rows = answer.list();
    assertThat(rows, hasSize(2));
    TenderMixRow cardRow = fromJson(rows, PaymentTender.METHOD_CARD);
    assertThat(cardRow.capturedAmount().compareTo(new BigDecimal("10.00")), is(0));
    assertThat(cardRow.refundedAmount().compareTo(new BigDecimal("4.00")), is(0));
    TenderMixRow cashRow = fromJson(rows, PaymentTender.METHOD_CASH);
    // Store C's 999.00 must not leak in.
    assertThat(cashRow.capturedAmount().compareTo(new BigDecimal("20.00")), is(0));
  }

  /** An owner, held to no store, still sees the whole business — unchanged by this fix. */
  @Test
  void anOwnerSeesTheWholeBusiness() {
    UUID tenant = Ids.newId();
    UUID storeA = Ids.newId();
    UUID storeB = Ids.newId();
    capture(tenant, Ids.newId(), storeA, "10.00", PaymentTender.METHOD_CASH, "CAPTURED");
    capture(tenant, Ids.newId(), storeB, "20.00", PaymentTender.METHOD_CASH, "CAPTURED");

    Answer answer = ItCalls.get(target, "/admin/reports/tender-mix", Caller.owner(tenant));
    assertThat(answer.body().toString(), answer.status(), is(200));
    JsonArray rows = answer.list();
    assertThat(rows, hasSize(1));
    BigDecimal captured = rows.getJsonObject(0).getJsonNumber("capturedAmount").bigDecimalValue();
    assertThat(captured.compareTo(new BigDecimal("30.00")), is(0));
  }

  /**
   * Another tenant's staff, of every role this tier admits, sees nothing of ours — even naming one
   * of our own store ids. STOREKEEPER and CASHIER never reach the report at all: the authorization
   * filter refuses the whole {@code /admin/reports} subtree to any role but management.
   */
  @Test
  void anotherTenantsStaffSeesNothingOfOurs() {
    UUID tenant = Ids.newId();
    UUID storeA = Ids.newId();
    capture(tenant, Ids.newId(), storeA, "10.00", PaymentTender.METHOD_CASH, "CAPTURED");

    UUID stranger = Ids.newId();
    for (String role : new String[] {"PLATFORM_ADMIN", "OWNER", "MANAGER"}) {
      Caller their = new Caller(stranger, Ids.newId(), role, storeA);
      Answer answer = ItCalls.get(target, "/admin/reports/tender-mix", their);
      assertThat(role + ": " + answer.body(), answer.status(), is(200));
      assertThat(role, answer.list(), hasSize(0));
    }
    for (String role : new String[] {"STOREKEEPER", "CASHIER"}) {
      Caller their = new Caller(stranger, Ids.newId(), role, storeA);
      Answer answer = ItCalls.get(target, "/admin/reports/tender-mix", their);
      assertThat(role, answer.status(), is(403));
    }
  }

  /** A caller of a tier this report refuses never reaches the store check at all. */
  @Test
  void staffTiersBelowManagementAreRefusedBeforeAnyStoreCheck() {
    UUID tenant = Ids.newId();
    for (String role : new String[] {"STOREKEEPER", "CASHIER"}) {
      Caller their = new Caller(tenant, Ids.newId(), role);
      assertThat(role, ItCalls.get(target, "/admin/reports/tender-mix", their).status(), is(403));
    }
  }

  private static Caller twoStoreManager(UUID tenantId, UUID first, UUID second) {
    return Caller.heldTo(tenantId, "MANAGER", first, second);
  }

  private static TenderMixRow fromJson(JsonArray rows, String method) {
    for (JsonObject r : rows.getValuesAs(JsonObject.class)) {
      if (r.getString("method").equals(method)) {
        return new TenderMixRow(
            method,
            r.getJsonNumber("capturedAmount").bigDecimalValue(),
            r.getJsonNumber("capturedCount").longValue(),
            r.getJsonNumber("refundedAmount").bigDecimalValue(),
            r.getJsonNumber("refundedCount").longValue(),
            r.getJsonNumber("failedCount").longValue(),
            r.getJsonNumber("netAmount").bigDecimalValue(),
            r.isNull("shareOfNet") ? null : r.getJsonNumber("shareOfNet").bigDecimalValue());
      }
    }
    throw new AssertionError(method + " not in " + rows);
  }

  private static TenderMixRow row(List<TenderMixRow> rows, String method) {
    return rows.stream()
        .filter(r -> r.method().equals(method))
        .findFirst()
        .orElseThrow(() -> new AssertionError(method + " not in " + rows));
  }

  private UUID capture(UUID tenantId, UUID orderId, String amount, String method, String status) {
    return capture(tenantId, orderId, null, amount, method, status);
  }

  private UUID capture(
      UUID tenantId, UUID orderId, UUID storeId, String amount, String method, String status) {
    UUID id = Ids.newId();
    repo.createTender(
        new PaymentTender(
            id,
            tenantId,
            orderId,
            new BigDecimal(amount),
            method,
            null,
            null,
            status,
            null,
            Instant.now(),
            storeId),
        new OutboxRow("PaymentCaptured", "storeql.payment.payment-captured", tenantId, id, "{}"));
    return id;
  }

  private void refund(UUID tenantId, UUID orderId, UUID paymentId, String amount, String method) {
    UUID id = Ids.newId();
    repo.createRefundGuarded(
        new RefundTender(
            id,
            tenantId,
            orderId,
            paymentId,
            new BigDecimal(amount),
            method,
            null,
            null,
            "test",
            Instant.now()),
        new OutboxRow("PaymentRefunded", "storeql.payment.payment-refunded", tenantId, id, "{}"));
  }
}
