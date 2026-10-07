package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.service.OutboxRow;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The controls round the chargeback register (catalogue RFD-13 to RFD-16): a dispute accepted is
 * closed as the business's own loss and announced for the ledger; an amount over the tender is
 * refused with nothing recorded; the register is management's and no cashier, storekeeper or
 * shopper reaches any of it; and another business's staff, of every role and naming our ids, find
 * nothing and move nothing.
 */
@HelidonTest
class DisputeControlsIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("payment");

  @Inject WebTarget target;
  @Inject PaymentRepository payments;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Answer post(String path, Caller who, String json) {
    return ItCalls.post(target, path, who, json);
  }

  private Answer get(String path, Caller who) {
    return ItCalls.get(target, path, who);
  }

  private UUID card(UUID tenantId, String amount) {
    UUID id = Ids.newId();
    payments.createTender(
        new PaymentTender(
            id,
            tenantId,
            Ids.newId(),
            new BigDecimal(amount),
            PaymentTender.METHOD_CARD,
            "auth-9",
            null,
            PaymentTender.STATUS_CAPTURED,
            null,
            Instant.now(),
            null),
        new OutboxRow("PaymentCaptured", "storeql.payment.payment-captured", tenantId, id, "{}"));
    return id;
  }

  private static String chargeback(UUID paymentId, String amount) {
    return "{\"paymentId\":\""
        + paymentId
        + "\""
        + (amount == null ? "" : ",\"amount\":" + amount)
        + ",\"feeAmount\":10.00,\"currency\":\"INR\",\"reason\":\"fraudulent\","
        + "\"caseReference\":\"CASE-"
        + Ids.newId()
        + "\",\"evidenceDueBy\":\""
        + Instant.now().plusSeconds(864_000)
        + "\"}";
  }

  private String open(UUID tenant, UUID payment, String amount) {
    Answer a =
        post("/admin/disputes", Caller.owner(tenant).as("MANAGER"), chargeback(payment, amount));
    assertThat(a.body().toString(), a.status(), is(201));
    return a.data().getString("id");
  }

  private String count(String sql) {
    return Envelopes.scalar(PG, sql);
  }

  private String status(String disputeId) {
    return Envelopes.scalar(
        PG, "SELECT status FROM payment.disputes WHERE id = '" + disputeId + "'");
  }

  @Test
  @DisplayName(
      "RFD-13: accepting closes the dispute as ACCEPTED, announced as a loss for the ledger")
  void acceptingClosesAsAcceptedAndIsAnnounced() {
    UUID tenant = Ids.newId();
    Caller manager = Caller.owner(tenant).as("MANAGER");
    String id = open(tenant, card(tenant, "20.00"), null);

    Answer accepted = post("/admin/disputes/" + id + "/accept", manager, null);
    assertThat(accepted.body().toString(), accepted.status(), is(200));
    assertThat(accepted.data().getJsonObject("dispute").getString("status"), is("ACCEPTED"));
    assertThat(status(id), is("ACCEPTED"));

    String closed =
        Envelopes.scalar(
            PG,
            "SELECT payload FROM payment.outbox WHERE aggregate_id = '"
                + id
                + "' AND event_type = 'PaymentDisputeClosed'");
    assertThat(closed, containsString("\"outcome\":\"ACCEPTED\""));
    assertThat(closed, containsString("\"fundsWithdrawn\":true"));
    assertThat(
        count(
            "SELECT count(*) FROM payment.outbox WHERE aggregate_id = '"
                + id
                + "' AND event_type = 'PaymentDisputeClosed'"),
        is("1"));
    // Closed is closed: accepting again, or calling it won, is refused and announces nothing more.
    assertThat(
        post("/admin/disputes/" + id + "/accept", manager, null).code(), is("DISPUTE_CLOSED"));
    assertThat(
        post("/admin/disputes/" + id + "/resolve", manager, "{\"outcome\":\"WON\"}").code(),
        is("DISPUTE_CLOSED"));
    assertThat(status(id), is("ACCEPTED"));
  }

  @Test
  @DisplayName("RFD-14: a dispute for more than the tender is 400 and records nothing")
  void anAmountOverTheTenderIsRefusedAndNothingIsRecorded() {
    UUID tenant = Ids.newId();
    Caller manager = Caller.owner(tenant).as("MANAGER");
    UUID payment = card(tenant, "20.00");

    Answer over = post("/admin/disputes", manager, chargeback(payment, "25.00"));
    assertThat(over.body().toString(), over.status(), is(400));
    assertThat(over.code(), is("DISPUTE_AMOUNT_EXCEEDS_PAYMENT"));
    assertThat(
        count("SELECT count(*) FROM payment.disputes WHERE tenant_id = '" + tenant + "'"), is("0"));
    assertThat(
        count(
            "SELECT count(*) FROM payment.outbox WHERE tenant_id = '"
                + tenant
                + "' AND event_type = 'PaymentDisputeOpened'"),
        is("0"));
    // The tender's own amount is the most it can be.
    assertThat(post("/admin/disputes", manager, chargeback(payment, "20.00")).status(), is(201));
  }

  @Test
  @DisplayName("RFD-15: a cashier, storekeeper or shopper cannot record, read, answer or resolve")
  void noOneBelowManagementReachesTheRegister() {
    UUID tenant = Ids.newId();
    UUID payment = card(tenant, "20.00");
    String id = open(tenant, payment, "5.00");
    UUID second = card(tenant, "9.00");
    String eventsBefore =
        count("SELECT count(*) FROM payment.dispute_events WHERE dispute_id = '" + id + "'");

    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      Caller who = new Caller(tenant, Ids.newId(), role);
      assertThat(role, post("/admin/disputes", who, chargeback(second, null)).status(), is(403));
      assertThat(role, get("/admin/disputes", who).status(), is(403));
      assertThat(role, get("/admin/disputes/" + id, who).status(), is(403));
      assertThat(
          role,
          post("/admin/disputes/" + id + "/evidence", who, "{\"notes\":\"x\"}").status(),
          is(403));
      assertThat(role, post("/admin/disputes/" + id + "/accept", who, null).status(), is(403));
      assertThat(
          role,
          post("/admin/disputes/" + id + "/resolve", who, "{\"outcome\":\"WON\"}").status(),
          is(403));
    }
    assertThat("nothing moved", status(id), is("NEEDS_RESPONSE"));
    assertThat(
        count("SELECT count(*) FROM payment.disputes WHERE tenant_id = '" + tenant + "'"), is("1"));
    assertThat(
        count("SELECT count(*) FROM payment.dispute_events WHERE dispute_id = '" + id + "'"),
        is(eventsBefore));
  }

  @Test
  @DisplayName("RFD-16: another business's staff of every role find nothing and move nothing")
  void anotherBusinessesStaffFindNothing() {
    UUID tenant = Ids.newId();
    UUID stranger = Ids.newId();
    UUID payment = card(tenant, "20.00");
    String id = open(tenant, payment, null);
    String eventsBefore =
        count("SELECT count(*) FROM payment.dispute_events WHERE tenant_id = '" + tenant + "'");

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Caller who = new Caller(stranger, Ids.newId(), role);
      assertThat(role, get("/admin/disputes/" + id, who).status(), is(404));
      assertThat(
          role,
          post("/admin/disputes/" + id + "/evidence", who, "{\"notes\":\"x\"}").status(),
          is(404));
      assertThat(role, post("/admin/disputes/" + id + "/accept", who, null).status(), is(404));
      assertThat(
          role,
          post("/admin/disputes/" + id + "/resolve", who, "{\"outcome\":\"LOST\"}").status(),
          is(404));
      // Naming our payment does not make it theirs to dispute.
      assertThat(role, post("/admin/disputes", who, chargeback(payment, null)).status(), is(404));
      assertThat(role, get("/admin/disputes", who).list().size(), is(0));
    }
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      Caller who = new Caller(stranger, Ids.newId(), role);
      assertThat(role, get("/admin/disputes/" + id, who).status(), is(403));
      assertThat(role, post("/admin/disputes/" + id + "/accept", who, null).status(), is(403));
      assertThat(role, post("/admin/disputes", who, chargeback(payment, null)).status(), is(403));
    }
    assertThat(status(id), is("NEEDS_RESPONSE"));
    assertThat(
        count("SELECT count(*) FROM payment.dispute_events WHERE tenant_id = '" + tenant + "'"),
        is(eventsBefore));
    assertThat(
        count("SELECT count(*) FROM payment.disputes WHERE tenant_id = '" + stranger + "'"),
        is("0"));
    // And ours is still ours to read.
    assertThat(get("/admin/disputes/" + id, Caller.owner(tenant)).status(), is(200));
  }
}
