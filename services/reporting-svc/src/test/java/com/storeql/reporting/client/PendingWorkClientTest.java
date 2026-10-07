package com.storeql.reporting.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.reporting.domain.PendingWork.Customer;
import com.storeql.reporting.domain.PendingWork.Payment;
import com.storeql.reporting.domain.PendingWork.Purchase;
import com.storeql.reporting.domain.PendingWork.Readings;
import com.storeql.service.ServiceReader.Reply;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the client makes of three services' answers: a count it cannot read, a refusal, a timeout
 * and a service that is not there are all "unreachable" (empty), never a zero; the three reads go
 * out at once; and each is asked as the caller's business, as a manager.
 */
class PendingWorkClientTest {

  private static final String PURCHASE = "purchase-svc";
  private static final String PAYMENT = "payment-svc";
  private static final String CUSTOMER = "customer-svc";

  private static final String PURCHASE_BODY =
      "{\"data\":{\"purchaseOrdersPendingApproval\":3,\"paymentRunsProposed\":2,"
          + "\"supplierInvoicesFlagged\":5,\"accountingSyncsUncertain\":1,"
          + "\"approvalsRouted\":true}}";
  private static final String PAYMENT_BODY = "{\"data\":{\"cardRefundDuesNeedingAttention\":4}}";
  private static final String CUSTOMER_BODY = "{\"data\":{\"privacyRequestsOpen\":7}}";

  private final PendingWorkClient client = new PendingWorkClient();

  /** No container injects the deadline here; the tests that are about it set their own. */
  @BeforeEach
  void deadline() {
    client.timeoutMillis = 2_000;
  }

  @AfterEach
  void stop() {
    client.close();
  }

  private static Reply ok(String body) {
    return new Reply(200, body);
  }

  private static String purchaseWith(String field, String value) {
    String full = PURCHASE_BODY;
    int at = full.indexOf("\"" + field + "\":");
    int end = at + ("\"" + field + "\":").length();
    int stop = end;
    while (stop < full.length() && ",}".indexOf(full.charAt(stop)) < 0) stop++;
    return full.substring(0, end) + value + full.substring(stop);
  }

  // ── reading each answer ────────────────────────────────────────────────────

  @Test
  @DisplayName("purchase-svc's answer is read field by field")
  void readsPurchase() {
    assertEquals(
        Optional.of(new Purchase(3, 2, 5, 1, true)), PendingWorkClient.purchase(PURCHASE_BODY));
    assertEquals(
        Optional.of(new Purchase(3, 2, 5, 1, false)),
        PendingWorkClient.purchase(purchaseWith("approvalsRouted", "false")));
  }

  @Test
  @DisplayName("payment-svc's and customer-svc's answers are read")
  void readsPaymentAndCustomer() {
    assertEquals(Optional.of(new Payment(4)), PendingWorkClient.payment(PAYMENT_BODY));
    assertEquals(Optional.of(new Customer(7)), PendingWorkClient.customer(CUSTOMER_BODY));
    assertEquals(
        Optional.of(new Customer(0)),
        PendingWorkClient.customer("{\"data\":{\"privacyRequestsOpen\":0}}"));
  }

  @Test
  @DisplayName("A member the contract does not name is ignored, so a service may add to its answer")
  void ignoresMembersItDoesNotKnow() {
    assertEquals(
        Optional.of(new Payment(4)),
        PendingWorkClient.payment(
            "{\"data\":{\"cardRefundDuesNeedingAttention\":4,\"later\":\"x\"},\"meta\":{}}"));
  }

  @Test
  @DisplayName("An answer that is not exactly what was promised is unreadable, never a zero")
  void unreadableAnswersAreEmpty() {
    List<String> bad =
        List.of(
            "",
            " ",
            "not json",
            "{",
            "[]",
            "null",
            "{}",
            "{\"data\":null}",
            "{\"data\":[]}",
            "{\"data\":{}}",
            "{\"error\":{\"code\":\"X\",\"message\":\"y\"}}",
            "{\"data\":{\"cardRefundDuesNeedingAttention\":null}}",
            "{\"data\":{\"cardRefundDuesNeedingAttention\":\"4\"}}",
            "{\"data\":{\"cardRefundDuesNeedingAttention\":4.5}}",
            "{\"data\":{\"cardRefundDuesNeedingAttention\":-1}}",
            "{\"data\":{\"cardRefundDuesNeedingAttention\":true}}",
            "{\"data\":{\"cardRefundDuesNeedingAttention\":99999999999999999999}}");
    for (String body : bad) {
      assertEquals(Optional.empty(), PendingWorkClient.payment(body), "payment: " + body);
    }
    assertEquals(Optional.empty(), PendingWorkClient.payment(null));
    assertEquals(Optional.empty(), PendingWorkClient.customer(PAYMENT_BODY));
    assertEquals(Optional.empty(), PendingWorkClient.purchase(PAYMENT_BODY));
  }

  @Test
  @DisplayName("purchase-svc: any of its five members missing or of the wrong type is unreadable")
  void purchaseNeedsAllFiveMembers() {
    for (String field :
        List.of(
            "purchaseOrdersPendingApproval",
            "paymentRunsProposed",
            "supplierInvoicesFlagged",
            "accountingSyncsUncertain")) {
      assertEquals(
          Optional.empty(),
          PendingWorkClient.purchase(purchaseWith(field, "\"1\"")),
          field + " as text");
      assertEquals(
          Optional.empty(), PendingWorkClient.purchase(purchaseWith(field, "null")), field);
      assertEquals(Optional.empty(), PendingWorkClient.purchase(purchaseWith(field, "-2")), field);
    }
    assertEquals(
        Optional.empty(),
        PendingWorkClient.purchase(purchaseWith("approvalsRouted", "\"false\"")),
        "approvalsRouted as text");
    assertEquals(
        Optional.empty(),
        PendingWorkClient.purchase(purchaseWith("approvalsRouted", "0")),
        "approvalsRouted as a number");
    assertEquals(
        Optional.empty(),
        PendingWorkClient.purchase(PURCHASE_BODY.replace(",\"approvalsRouted\":true", "")),
        "approvalsRouted absent");
  }

  // ── the three reads ────────────────────────────────────────────────────────

  private void answers(Map<String, Reply> byService) {
    client.reads = (service, tenant, path) -> byService.getOrDefault(service, new Reply(0, null));
  }

  @Test
  @DisplayName(
      "Asked for a business, each service is read once at /admin/pending-work, as that business")
  void eachServiceIsAskedOnceForTheCallersBusiness() {
    UUID tenant = Ids.newId();
    List<String> asked = new CopyOnWriteArrayList<>();
    client.reads =
        (service, who, path) -> {
          asked.add(service + " " + who + " " + path);
          return ok(
              switch (service) {
                case PURCHASE -> PURCHASE_BODY;
                case PAYMENT -> PAYMENT_BODY;
                default -> CUSTOMER_BODY;
              });
        };

    Readings readings = client.read(tenant);

    assertEquals(Optional.of(new Purchase(3, 2, 5, 1, true)), readings.purchase());
    assertEquals(Optional.of(new Payment(4)), readings.payment());
    assertEquals(Optional.of(new Customer(7)), readings.customer());
    assertEquals(3, asked.size());
    assertTrue(asked.contains(PURCHASE + " " + tenant + " /admin/pending-work"), asked.toString());
    assertTrue(asked.contains(PAYMENT + " " + tenant + " /admin/pending-work"), asked.toString());
    assertTrue(asked.contains(CUSTOMER + " " + tenant + " /admin/pending-work"), asked.toString());
  }

  @Test
  @DisplayName(
      "A refusal, a missing route, a server error and an unlocatable service are each empty")
  void anythingButAnAnswerIsEmpty() {
    for (int status : new int[] {0, 401, 403, 404, 409, 500, 502, 503}) {
      answers(
          Map.of(
              PURCHASE, new Reply(status, PURCHASE_BODY),
              PAYMENT, ok(PAYMENT_BODY),
              CUSTOMER, new Reply(status, CUSTOMER_BODY)));

      Readings readings = client.read(Ids.newId());

      assertEquals(Optional.empty(), readings.purchase(), "status " + status);
      assertEquals(Optional.empty(), readings.customer(), "status " + status);
      assertEquals(Optional.of(new Payment(4)), readings.payment(), "status " + status);
    }
  }

  @Test
  @DisplayName("A 200 whose body cannot be read is empty, and the others are still answered")
  void garbageIsEmpty() {
    answers(
        Map.of(
            PURCHASE, ok("<html>bad gateway</html>"),
            PAYMENT, ok(PAYMENT_BODY),
            CUSTOMER, ok("{\"data\":{}}")));

    Readings readings = client.read(Ids.newId());

    assertEquals(Optional.empty(), readings.purchase());
    assertEquals(Optional.of(new Payment(4)), readings.payment());
    assertEquals(Optional.empty(), readings.customer());
  }

  @Test
  @DisplayName("A read that throws is empty and does not take the others with it")
  void aThrowingReadIsEmpty() {
    client.reads =
        (service, tenant, path) -> {
          if (PAYMENT.equals(service)) throw new IllegalStateException("connection reset");
          return ok(PURCHASE.equals(service) ? PURCHASE_BODY : CUSTOMER_BODY);
        };

    Readings readings = client.read(Ids.newId());

    assertEquals(Optional.empty(), readings.payment());
    assertEquals(Optional.of(new Purchase(3, 2, 5, 1, true)), readings.purchase());
    assertEquals(Optional.of(new Customer(7)), readings.customer());
  }

  @Test
  @DisplayName("The three reads go out together: none waits for another to finish")
  void readsRunInParallel() {
    CyclicBarrier allThree = new CyclicBarrier(3);
    client.reads =
        (service, tenant, path) -> {
          try {
            // A sequential client never gets the second party here, and every wait times out.
            allThree.await(3, TimeUnit.SECONDS);
          } catch (Exception e) {
            return new Reply(0, null);
          }
          return ok(
              switch (service) {
                case PURCHASE -> PURCHASE_BODY;
                case PAYMENT -> PAYMENT_BODY;
                default -> CUSTOMER_BODY;
              });
        };
    client.timeoutMillis = 5_000;

    Readings readings = client.read(Ids.newId());

    assertTrue(readings.purchase().isPresent());
    assertTrue(readings.payment().isPresent());
    assertTrue(readings.customer().isPresent());
  }

  @Test
  @DisplayName(
      "A service that does not answer in time is empty at the deadline, and its read is cancelled")
  void aSlowReadIsCutOff() throws InterruptedException {
    CountDownLatch cancelled = new CountDownLatch(1);
    client.timeoutMillis = 200;
    client.reads =
        (service, tenant, path) -> {
          if (CUSTOMER.equals(service)) {
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException e) {
              cancelled.countDown();
              Thread.currentThread().interrupt();
            }
            return new Reply(0, null);
          }
          return ok(PAYMENT.equals(service) ? PAYMENT_BODY : PURCHASE_BODY);
        };

    long started = System.nanoTime();
    Readings readings = client.read(Ids.newId());
    long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertEquals(Optional.empty(), readings.customer());
    assertEquals(Optional.of(new Payment(4)), readings.payment());
    assertTrue(readings.purchase().isPresent());
    assertTrue(tookMillis < 2_000, "waited " + tookMillis + " ms for a 200 ms deadline");
    assertTrue(cancelled.await(2, TimeUnit.SECONDS), "the slow read was left running");
  }

  @Test
  @DisplayName("The deadline is shared, not added up: three slow services cost one timeout")
  void threeSlowServicesCostOneTimeout() {
    client.timeoutMillis = 300;
    client.reads =
        (service, tenant, path) -> {
          try {
            Thread.sleep(10_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new Reply(0, null);
        };

    long started = System.nanoTime();
    Readings readings = client.read(Ids.newId());
    long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertEquals(new Readings(Optional.empty(), Optional.empty(), Optional.empty()), readings);
    assertTrue(tookMillis < 1_500, "three 300 ms deadlines took " + tookMillis + " ms");
  }

  @Test
  @DisplayName("Each business is asked as itself: the answer follows the tenant it was asked for")
  void eachBusinessGetsItsOwnAnswer() {
    UUID first = Ids.newId();
    UUID second = Ids.newId();
    Map<UUID, String> payments = new ConcurrentHashMap<>();
    payments.put(first, "{\"data\":{\"cardRefundDuesNeedingAttention\":11}}");
    payments.put(second, "{\"data\":{\"cardRefundDuesNeedingAttention\":22}}");
    client.reads =
        (service, tenant, path) ->
            PAYMENT.equals(service) ? ok(payments.get(tenant)) : new Reply(0, null);

    Readings a = client.read(first);
    Readings b = client.read(second);

    assertEquals(Optional.of(new Payment(11)), a.payment());
    assertEquals(Optional.of(new Payment(22)), b.payment());
  }
}
