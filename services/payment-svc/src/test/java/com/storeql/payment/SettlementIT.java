package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.payment.domain.Domain.PaymentTender;
import com.storeql.payment.domain.Domain.RefundTender;
import com.storeql.payment.repo.PaymentRepository;
import com.storeql.service.OutboxRow;
import com.storeql.test.Concurrency;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reconciliation against the acquirer's settlement file (11.10), over HTTP and a real database: a
 * file in which everything matches is reconciled at once and the ledger told store by store; what
 * does not match waits for a decision and the batch for a sign-off; a chargeback settles against
 * the dispute on file without its fee being booked twice; and what is refused — a file that does
 * not add up, a payout imported twice, another business's payment, a line decided after the books
 * have it, a cashier anywhere near it.
 */
@HelidonTest
class SettlementIT {

  private static final UUID SHOP = Ids.newId();
  private static final UUID HIGH_STREET = Ids.newId();
  private static final UUID RETAIL_PARK = Ids.newId();
  private static final UUID RIVAL = Ids.newId();
  private static final UUID EURO_SHOP = Ids.newId();

  private static final PostgresSupport PG = PostgresSupport.start().wire("payment");

  static {
    TenantSvcStub.start()
        .with(SHOP.toString(), "GBP", "GB")
        .withStore(SHOP.toString(), HIGH_STREET.toString(), "GB")
        .withStore(SHOP.toString(), RETAIL_PARK.toString(), "GB")
        .with(RIVAL.toString(), "GBP", "GB")
        .with(EURO_SHOP.toString(), "EUR", "IE");
  }

  private static final String SETTLEMENTS = "/admin/settlements";
  private static final String HEADER = "type,reference,original_reference,gross,fee,net\n";

  @Inject WebTarget target;
  @Inject PaymentRepository payments;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private UUID card(UUID tenantId, UUID storeId, String reference, String amount, Instant at) {
    UUID id = Ids.newId();
    payments.createTender(
        new PaymentTender(
            id,
            tenantId,
            Ids.newId(),
            new BigDecimal(amount),
            PaymentTender.METHOD_CARD,
            reference,
            null,
            PaymentTender.STATUS_CAPTURED,
            null,
            at,
            storeId),
        new OutboxRow("PaymentCaptured", "storeql.payment.payment-captured", tenantId, id, "{}"));
    return id;
  }

  private UUID card(UUID storeId, String reference, String amount) {
    return card(SHOP, storeId, reference, amount, Instant.now());
  }

  private UUID refund(UUID paymentId, String reference, String amount) {
    UUID id = Ids.newId();
    payments.createRefundGuarded(
        new RefundTender(
            id,
            SHOP,
            payments.findTender(SHOP, paymentId).orElseThrow().orderId(),
            paymentId,
            new BigDecimal(amount),
            PaymentTender.METHOD_CARD,
            reference,
            null,
            "changed their mind",
            Instant.now()),
        new OutboxRow("PaymentRefunded", "storeql.payment.payment-refunded", SHOP, id, "{}"),
        // The fixture is nobody's request: who may refund where is BackOfficeRefundIT's.
        store -> {});
    return id;
  }

  private static String payout(String reference, String declaredNet, String content) {
    return Json.createObjectBuilder()
        .add("provider", "Worldpay")
        .add("format", "STOREQL")
        .add("reference", reference)
        .add("payoutDate", LocalDate.now(ZoneOffset.UTC).toString())
        .add("declaredNet", new BigDecimal(declaredNet))
        .add("content", content)
        .build()
        .toString();
  }

  private Answer importFile(Caller who, String body, String key) {
    return ItCalls.call(target, "POST", SETTLEMENTS, who, body, key);
  }

  private Answer get(String path, Caller who) {
    return ItCalls.get(target, path, who);
  }

  private Answer post(String path, Caller who, String json) {
    return ItCalls.post(target, path, who, json);
  }

  private static JsonObject line(Answer file, String reference) {
    for (JsonValue v : file.data().getJsonArray("lines")) {
      if (reference.equals(v.asJsonObject().getString("reference", null))) return v.asJsonObject();
    }
    throw new AssertionError("no line " + reference + " in " + file.body());
  }

  private Answer resolve(
      Caller who, String batchId, String lineId, String resolution, UUID t, String note) {
    var body = Json.createObjectBuilder().add("resolution", resolution);
    if (t != null) body.add("targetId", t.toString());
    if (note != null) body.add("note", note);
    return post(
        SETTLEMENTS + "/" + batchId + "/lines/" + lineId + "/resolve",
        who,
        body.build().toString());
  }

  /** The one event a reconciled batch announced, or null when it has announced none. */
  private static JsonObject announced(String batchId) {
    String count =
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM payment.outbox WHERE event_type = 'SettlementReconciled'"
                + " AND aggregate_id = '"
                + batchId
                + "'");
    if ("0".equals(count)) return null;
    assertThat("a batch is announced once", count, is("1"));
    return Envelopes.parse(
        Envelopes.scalar(
            PG,
            "SELECT payload FROM payment.outbox WHERE event_type = 'SettlementReconciled'"
                + " AND aggregate_id = '"
                + batchId
                + "'"));
  }

  /** A field the answer leaves out, or gives as null: both say there is none. */
  private static boolean absent(JsonObject o, String field) {
    return !o.containsKey(field) || o.isNull(field);
  }

  private static JsonObject storeOf(JsonObject event, UUID storeId) {
    for (JsonValue v : event.getJsonArray("stores")) {
      JsonObject s = v.asJsonObject();
      String id = s.getString("storeId", null);
      if (storeId == null ? id == null : storeId.toString().equals(id)) return s;
    }
    throw new AssertionError("no figures for store " + storeId + " in " + event);
  }

  private static void figures(
      JsonObject store, String bank, String fees, String clearing, String unallocated) {
    figure(store, "bank", bank);
    figure(store, "fees", fees);
    figure(store, "clearing", clearing);
    figure(store, "unallocated", unallocated);
    // Whatever the figures, a store's journal balances.
    BigDecimal in = amount(store, "clearing").add(amount(store, "unallocated"));
    assertThat(
        store.toString(), amount(store, "bank").add(amount(store, "fees")).compareTo(in), is(0));
  }

  private static void figure(JsonObject store, String name, String expected) {
    assertThat(
        name + " in " + store, amount(store, name).compareTo(new BigDecimal(expected)), is(0));
  }

  private static BigDecimal amount(JsonObject store, String name) {
    return store.getJsonNumber(name).bigDecimalValue();
  }

  // ── everything matches ─────────────────────────────────────────────────────

  @Test
  @DisplayName("A file in which everything matches is reconciled at once, store by store")
  void aCleanFileReconcilesAtOnce() {
    Caller me = Caller.owner(SHOP);
    UUID first = card(HIGH_STREET, "CLEAN-A1", "45.99");
    card(RETAIL_PARK, "CLEAN-B1", "120.00");
    refund(first, "CLEAN-R1", "5.99");
    String file =
        HEADER
            + "SALE,CLEAN-A1,,45.99,0.69,45.30\n"
            + "SALE,CLEAN-B1,,120.00,1.80,118.20\n"
            + "REFUND,CLEAN-R1,CLEAN-A1,-5.99,0,-5.99\n"
            + "FEE,TERMINAL-RENTAL,,0,15.00,-15.00\n";
    String key = Ids.newId().toString();

    Answer imported = importFile(me, payout("WP-CLEAN-1", "142.51", file), key);

    assertThat(imported.body().toString(), imported.status(), is(201));
    JsonObject batch = imported.data();
    assertThat(batch.getString("status"), is("RECONCILED"));
    assertThat(batch.getString("provider"), is("WORLDPAY"));
    assertThat(batch.getInt("openExceptions"), is(0));
    assertThat("nobody had to decide anything", absent(batch, "reconciledBy"), is(true));
    assertThat(
        batch.getJsonNumber("salesAmount").bigDecimalValue(), is(new BigDecimal("165.9900")));
    assertThat(batch.getJsonNumber("refundAmount").bigDecimalValue(), is(new BigDecimal("5.9900")));
    assertThat(batch.getJsonNumber("feeAmount").bigDecimalValue(), is(new BigDecimal("17.4900")));
    assertThat(batch.getJsonNumber("netAmount").bigDecimalValue(), is(new BigDecimal("142.5100")));
    String id = batch.getString("id");

    JsonObject event = announced(id);
    assertThat(event.getString("reference"), is("WP-CLEAN-1"));
    assertThat(event.getJsonArray("stores").size(), is(3));
    // The refund belongs to the store of the payment it gave back.
    figures(storeOf(event, HIGH_STREET), "39.3100", "0.6900", "40.0000", "0.0000");
    figures(storeOf(event, RETAIL_PARK), "118.2000", "1.8000", "120.0000", "0.0000");
    // A rental is nobody's sale: it is the business's own cost.
    figures(storeOf(event, null), "-15.0000", "15.0000", "0", "0.0000");

    // The same request again is the same batch; the same payout from another request is refused.
    Answer replay = importFile(me, payout("WP-CLEAN-1", "142.51", file), key);
    assertThat(replay.status(), is(201));
    assertThat(replay.data().getString("id"), is(id));
    Answer again = importFile(me, payout("WP-CLEAN-1", "142.51", file), Ids.newId().toString());
    assertThat(again.status(), is(409));
    assertThat(again.code(), is("SETTLEMENT_ALREADY_IMPORTED"));
    assertThat("still announced once", announced(id).getString("batchId"), is(id));

    // The lines page in file order.
    Answer page = get(SETTLEMENTS + "/" + id + "?limit=3", me);
    JsonArray lines = page.data().getJsonArray("lines");
    assertThat(lines.size(), is(3));
    assertThat(lines.getJsonObject(0).getInt("lineNo"), is(1));
    assertThat(lines.getJsonObject(0).getString("matchStatus"), is("MATCHED"));
    assertThat(lines.getJsonObject(0).getString("tenderId"), is(first.toString()));
    Answer rest = get(SETTLEMENTS + "/" + id + "?limit=3&after=" + page.nextCursor(), me);
    assertThat(rest.data().getJsonArray("lines").size(), is(1));
    assertThat(
        rest.data().getJsonArray("lines").getJsonObject(0).getString("matchStatus"),
        is("NOT_APPLICABLE"));
    assertThat(rest.nextCursor(), is(nullValue()));
    assertThat(
        get(SETTLEMENTS + "/" + id + "?open=true", me).data().getJsonArray("lines").size(), is(0));
    assertThat(get(SETTLEMENTS + "/" + id + "?after=nonsense", me).status(), is(400));

    // Reconciled means closed.
    assertThat(
        post(SETTLEMENTS + "/" + id + "/reconcile", me, null).code(), is("SETTLEMENT_RECONCILED"));
    String lineId = lines.getJsonObject(0).getString("id");
    assertThat(
        resolve(me, id, lineId, "UNALLOCATED", null, "second thoughts").code(),
        is("SETTLEMENT_RECONCILED"));
  }

  // ── what does not match ────────────────────────────────────────────────────

  @Test
  @DisplayName("What does not match waits for a decision, and the batch for a sign-off")
  void exceptionsAreDecidedAndTheBatchSignedOff() {
    Caller me = Caller.owner(SHOP);
    Caller manager = me.as("MANAGER");
    // An authorisation code is six characters and repeats: two payments, one code, two sums.
    UUID morning = card(HIGH_STREET, "EX-0421", "10.00");
    UUID evening = card(HIGH_STREET, "EX-0421", "32.50");
    UUID short5 = card(HIGH_STREET, "EX-SHORT", "50.00");
    UUID typed = card(RETAIL_PARK, "EX-TYPED-WRONG", "18.00");
    String file =
        HEADER
            + "SALE,EX-0421,,32.50,0.50,32.00\n"
            + "SALE,EX-0421,,10.00,0.20,9.80\n"
            + "SALE,EX-SHORT,,45.00,0.70,44.30\n"
            + "SALE,EX-TYPED-RIGHT,,18.00,0.30,17.70\n"
            + "SALE,EX-NOBODYS,,30.00,0.45,29.55\n"
            + "ADJUSTMENT,RESERVE-HELD,,-100.00,,\n";

    Answer imported = importFile(me, payout("WP-EX-1", "33.35", file), null);

    assertThat(imported.body().toString(), imported.status(), is(201));
    String id = imported.data().getString("id");
    assertThat(imported.data().getString("status"), is("EXCEPTIONS"));
    assertThat(imported.data().getInt("openExceptions"), is(4));
    assertThat("nothing is announced while anything is open", announced(id), is(nullValue()));

    Answer all = get(SETTLEMENTS + "/" + id, me);
    JsonArray lines = all.data().getJsonArray("lines");
    assertThat(
        "the same sum wins", lines.getJsonObject(0).getString("tenderId"), is(evening.toString()));
    assertThat(lines.getJsonObject(1).getString("tenderId"), is(morning.toString()));
    JsonObject shortPaid = line(all, "EX-SHORT");
    assertThat(shortPaid.getString("matchStatus"), is("AMOUNT_MISMATCH"));
    assertThat(
        shortPaid.getJsonNumber("expectedAmount").bigDecimalValue(), is(new BigDecimal("50.0000")));
    assertThat(shortPaid.getString("tenderId"), is(short5.toString()));
    assertThat(line(all, "EX-TYPED-RIGHT").getString("matchStatus"), is("UNMATCHED"));
    assertThat(line(all, "EX-NOBODYS").getString("matchStatus"), is("UNMATCHED"));
    assertThat(line(all, "RESERVE-HELD").getBoolean("open"), is(true));
    assertThat(
        get(SETTLEMENTS + "/" + id + "?open=true", me).data().getJsonArray("lines").size(), is(4));
    assertThat(get(SETTLEMENTS + "?status=EXCEPTIONS", me).list().toString(), containsString(id));
    assertThat(get(SETTLEMENTS + "?status=nonsense", me).code(), is("SETTLEMENT_STATUS_UNKNOWN"));

    // Not yet.
    Answer early = post(SETTLEMENTS + "/" + id + "/reconcile", me, null);
    assertThat(early.status(), is(409));
    assertThat(early.code(), is("SETTLEMENT_HAS_EXCEPTIONS"));

    // What cannot be decided that way.
    String typedLine = line(all, "EX-TYPED-RIGHT").getString("id");
    String nobodys = line(all, "EX-NOBODYS").getString("id");
    String shortLine = shortPaid.getString("id");
    String reserve = line(all, "RESERVE-HELD").getString("id");
    String matched = lines.getJsonObject(0).getString("id");
    assertThat(
        resolve(me, id, typedLine, "FORGIVEN", null, "x").code(),
        is("SETTLEMENT_RESOLUTION_UNKNOWN"));
    assertThat(
        resolve(me, id, typedLine, "MATCHED_BY_HAND", null, null).code(),
        is("SETTLEMENT_TARGET_REQUIRED"));
    assertThat(
        resolve(me, id, nobodys, "UNALLOCATED", null, null).code(), is("SETTLEMENT_NOTE_REQUIRED"));
    assertThat(
        resolve(me, id, typedLine, "MATCHED_BY_HAND", Ids.newId(), null).code(),
        is("SETTLEMENT_TARGET_NOT_FOUND"));
    UUID rivals = card(RIVAL, null, "EX-RIVALS", "18.00", Instant.now());
    assertThat(
        "another business's payment is not there to be pointed at",
        resolve(me, id, typedLine, "MATCHED_BY_HAND", rivals, null).code(),
        is("SETTLEMENT_TARGET_NOT_FOUND"));
    assertThat(
        "a payment a line already has",
        resolve(me, id, typedLine, "MATCHED_BY_HAND", morning, null).code(),
        is("SETTLEMENT_ALREADY_SETTLED"));
    UUID cash = Ids.newId();
    payments.createTender(
        new PaymentTender(
            cash,
            SHOP,
            Ids.newId(),
            new BigDecimal("18.00"),
            "CASH",
            null,
            null,
            "CAPTURED",
            null,
            Instant.now(),
            HIGH_STREET),
        new OutboxRow("PaymentCaptured", "storeql.payment.payment-captured", SHOP, cash, "{}"));
    assertThat(
        "cash never settles through an acquirer",
        resolve(me, id, typedLine, "MATCHED_BY_HAND", cash, null).code(),
        is("SETTLEMENT_TARGET_NOT_FOUND"));
    UUID other = card(HIGH_STREET, "EX-OTHER", "19.00");
    Answer differs = resolve(me, id, typedLine, "MATCHED_BY_HAND", other, null);
    assertThat(differs.status(), is(409));
    assertThat(differs.code(), is("SETTLEMENT_AMOUNT_DIFFERS"));
    assertThat(
        resolve(me, id, matched, "UNALLOCATED", null, "why not").code(),
        is("SETTLEMENT_LINE_NOT_AN_EXCEPTION"));
    assertThat(
        resolve(me, id, nobodys, "DIFFERENCE_ACCEPTED", null, "it is fine").code(),
        is("SETTLEMENT_NOTHING_TO_ACCEPT"));
    assertThat(
        resolve(me, id, reserve, "MATCHED_BY_HAND", morning, null).code(),
        is("SETTLEMENT_LINE_NOT_AN_EXCEPTION"));
    assertThat(
        resolve(me, id, Ids.newId().toString(), "UNALLOCATED", null, "x").code(),
        is("SETTLEMENT_LINE_NOT_FOUND"));
    assertThat(
        resolve(me, Ids.newId().toString(), typedLine, "UNALLOCATED", null, "x").code(),
        is("SETTLEMENT_NOT_FOUND"));

    // What can.
    Answer byHand = resolve(manager, id, typedLine, "MATCHED_BY_HAND", typed, null);
    assertThat(byHand.body().toString(), byHand.status(), is(200));
    assertThat("answers what is still open", byHand.data().getJsonArray("lines").size(), is(3));
    assertThat(byHand.data().getJsonObject("batch").getString("status"), is("EXCEPTIONS"));
    assertThat(
        resolve(manager, id, shortLine, "DIFFERENCE_ACCEPTED", null, "Tip adjusted at the terminal")
            .status(),
        is(200));
    assertThat(
        resolve(manager, id, nobodys, "UNALLOCATED", null, "Not ours: asked Worldpay").status(),
        is(200));
    Answer last =
        resolve(manager, id, reserve, "UNALLOCATED", null, "Rolling reserve, see the contract");
    assertThat(last.data().getJsonObject("batch").getString("status"), is("READY"));
    assertThat(last.data().getJsonArray("lines").size(), is(0));
    // A decision may be changed until the batch is signed off.
    assertThat(
        resolve(manager, id, nobodys, "UNALLOCATED", null, "Not ours: Worldpay agrees").status(),
        is(200));
    assertThat("still nothing announced", announced(id), is(nullValue()));

    Answer signed = post(SETTLEMENTS + "/" + id + "/reconcile", manager, null);
    assertThat(signed.body().toString(), signed.status(), is(200));
    assertThat(signed.data().getString("status"), is("RECONCILED"));
    assertThat(signed.data().getString("reconciledBy"), is(manager.userId().toString()));
    assertThat(
        post(SETTLEMENTS + "/" + id + "/reconcile", manager, null).code(),
        is("SETTLEMENT_RECONCILED"));

    JsonObject event = announced(id);
    // 32.00 + 9.80 + 44.30 in; clearing gives up what was held — 50.00 for the short-paid one — and
    // the 5.00 it was short by is left in unallocated for the books to explain.
    figures(storeOf(event, HIGH_STREET), "86.1000", "1.4000", "92.5000", "-5.0000");
    figures(storeOf(event, RETAIL_PARK), "17.7000", "0.3000", "18.0000", "0.0000");
    // Nobody's 30.00 and the reserve held back belong to no store.
    figures(storeOf(event, null), "-70.4500", "0.4500", "0", "-70.0000");
    JsonObject kept = line(get(SETTLEMENTS + "/" + id, me), "EX-NOBODYS");
    assertThat(kept.getString("resolution"), is("UNALLOCATED"));
    assertThat(kept.getString("note"), is("Not ours: Worldpay agrees"));
    assertThat(kept.getString("resolvedBy"), is(manager.userId().toString()));

    // The same payment in a later file is a duplicate, not a second settlement.
    Answer later =
        importFile(me, payout("WP-EX-2", "9.80", HEADER + "SALE,EX-0421,,10.00,0.20,9.80\n"), null);
    assertThat(later.data().getString("status"), is("EXCEPTIONS"));
    JsonObject dup = line(get(SETTLEMENTS + "/" + later.data().getString("id"), me), "EX-0421");
    assertThat(dup.getString("matchStatus"), is("DUPLICATE"));
    assertThat("and it holds no payment: the first line does", absent(dup, "tenderId"), is(true));
  }

  // ── chargebacks ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A chargeback settles against the dispute on file, and its fee is not booked twice")
  void aChargebackSettlesAgainstItsDispute() {
    Caller me = Caller.owner(SHOP);
    UUID disputed = card(HIGH_STREET, "CB-PAID", "40.00");
    card(HIGH_STREET, "CB-FINE", "60.00");
    UUID unknown = card(HIGH_STREET, "CB-UNTOLD", "25.00");
    Answer dispute =
        post(
            "/admin/disputes",
            me,
            "{\"paymentId\":\""
                + disputed
                + "\",\"feeAmount\":15.00,\"currency\":\"GBP\",\"reason\":\"fraudulent\","
                + "\"caseReference\":\"WP-CASE-77\",\"evidenceDueBy\":\""
                + Instant.now().plusSeconds(864_000)
                + "\"}");
    assertThat(dispute.body().toString(), dispute.status(), is(201));
    String file =
        HEADER
            + "SALE,CB-FINE,,60.00,0.90,59.10\n"
            + "CHARGEBACK,WP-CASE-77,CB-PAID,-40.00,15.00,-55.00\n"
            + "CHARGEBACK,WP-CASE-78,CB-UNTOLD,-25.00,15.00,-40.00\n";

    Answer imported = importFile(me, payout("WP-CB-1", "-35.90", file), null);

    assertThat(imported.body().toString(), imported.status(), is(201));
    String id = imported.data().getString("id");
    assertThat(imported.data().getInt("openExceptions"), is(1));
    assertThat(
        imported.data().getJsonNumber("chargebackAmount").bigDecimalValue(),
        is(new BigDecimal("65.0000")));
    Answer all = get(SETTLEMENTS + "/" + id, me);
    JsonObject known = line(all, "WP-CASE-77");
    assertThat(known.getString("matchStatus"), is("MATCHED"));
    assertThat(known.getString("disputeId"), is(dispute.data().getString("id")));
    JsonObject untold = line(all, "WP-CASE-78");
    assertThat(
        "a chargeback nobody recorded is an exception",
        untold.getString("matchStatus"),
        is("UNMATCHED"));

    // Record it, as 11.9 has it, and point the line at it.
    Answer recorded =
        post(
            "/admin/disputes",
            me,
            "{\"paymentId\":\""
                + unknown
                + "\",\"feeAmount\":10.00,\"currency\":\"GBP\",\"reason\":\"general\","
                + "\"caseReference\":\"WP-CASE-78\",\"evidenceDueBy\":\""
                + Instant.now().plusSeconds(864_000)
                + "\"}");
    UUID recordedId = Ids.parse(recorded.data().getString("id"));
    Answer differs = resolve(me, id, untold.getString("id"), "MATCHED_BY_HAND", recordedId, null);
    assertThat(
        "recorded with a fee of 10, settled with 15",
        differs.code(),
        is("SETTLEMENT_AMOUNT_DIFFERS"));
    assertThat(
        resolve(
                me,
                id,
                untold.getString("id"),
                "DIFFERENCE_ACCEPTED",
                recordedId,
                "Worldpay raised its fee")
            .status(),
        is(200));
    assertThat(post(SETTLEMENTS + "/" + id + "/reconcile", me, null).status(), is(200));

    // Clearing gives up 60.00 and takes back the 55.00 and the 35.00 the ledger moved when the
    // disputes took the money (11.9); neither dispute's fee is a processing fee here; the 5.00 more
    // that Worldpay took than was recorded is left in unallocated.
    figures(storeOf(announced(id), HIGH_STREET), "-35.9000", "0.9000", "-30.0000", "-5.0000");
  }

  // ── what is refused ────────────────────────────────────────────────────────

  @Test
  @DisplayName("A file that cannot be believed is refused whole, and nothing of it is kept")
  void whatCannotBeImportedIsRefused() {
    Caller me = Caller.owner(EURO_SHOP);
    String good = HEADER + "SALE,BAD-1,,10.00,0.10,9.90\n";
    String today = LocalDate.now(ZoneOffset.UTC).toString();

    Answer unbalanced =
        importFile(me, payout("BAD-1", "99.00", good).replace("Worldpay", "Elavon"), null);
    assertThat(unbalanced.status(), is(400));
    assertThat(unbalanced.code(), is("SETTLEMENT_OUT_OF_BALANCE"));
    assertThat(
        importFile(me, payout("BAD-2", "9.90", good).replace("STOREQL", "WORLDLINE"), null).code(),
        is("SETTLEMENT_FORMAT_UNKNOWN"));
    assertThat(
        importFile(me, payout("BAD-3", "9.90", "when,what\nnow,this\n"), null).code(),
        is("SETTLEMENT_FILE_WRONG_LAYOUT"));
    Answer wrongSign =
        importFile(me, payout("BAD-4", "5.00", HEADER + "REFUND,R,,5.00,0,5.00\n"), null);
    assertThat(wrongSign.code(), is("SETTLEMENT_FILE_INVALID"));
    assertThat(wrongSign.body().toString(), containsString("Line 2"));
    assertThat(
        importFile(me, payout("", "9.90", good), null).code(), is("SETTLEMENT_REFERENCE_MISSING"));
    assertThat(
        importFile(me, payout("BAD-5", "9.90", good).replace(today, ""), null).code(),
        is("SETTLEMENT_PAYOUT_DATE_MISSING"));
    assertThat(
        importFile(me, payout("BAD-6", "9.90", good).replace(today, "17/09/2026"), null).code(),
        is("SETTLEMENT_PAYOUT_DATE_INVALID"));
    assertThat(
        importFile(me, payout("BAD-7", "9.90", good).replace(today, "2099-01-01"), null).code(),
        is("SETTLEMENT_PAYOUT_DATE_INVALID"));
    assertThat(
        "a euro business is not paid out in sterling",
        importFile(
                me,
                payout("BAD-8", "9.90", good)
                    .replace("\"format\"", "\"currency\":\"GBP\",\"format\""),
                null)
            .code(),
        is("SETTLEMENT_CURRENCY_MIXED"));
    assertThat(
        importFile(
                me,
                payout("BAD-9", "9.90", good)
                    .replace("\"format\"", "\"storeId\":\"" + Ids.newId() + "\",\"format\""),
                null)
            .code(),
        is("SETTLEMENT_STORE_UNKNOWN"));
    assertThat(importFile(me, payout("BAD-10", "9.90", ""), null).status(), is(400));
    assertThat(importFile(me, "{\"provider\":\"x\"}", null).status(), is(400));
    assertThat(importFile(me, payout("BAD\n11", "9.90", good), null).status(), is(400));

    assertThat("nothing of any of them was kept", get(SETTLEMENTS, me).list().size(), is(0));
    assertThat(
        get(SETTLEMENTS + "/formats", me).data().getJsonArray("formats").toString(),
        is("[\"ADYEN\",\"STOREQL\",\"STRIPE\"]"));
  }

  // ── whose it is ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A settlement is its business's and management's; what has not settled is listed")
  void whoseItIsAndWhatHasNotSettled() {
    Caller me = Caller.owner(SHOP);
    Caller rival = Caller.owner(RIVAL);
    Instant lastWeek = Instant.now().minusSeconds(6 * 86_400L);
    UUID waiting = card(SHOP, RETAIL_PARK, "OLD-WAITING", "75.00", lastWeek);
    card(SHOP, RETAIL_PARK, "OLD-PAID", "20.00", lastWeek);
    UUID fresh = card(RETAIL_PARK, "OLD-TODAY", "12.00");
    card(RIVAL, null, "OLD-PAID", "20.00", lastWeek);
    Answer imported =
        importFile(
            me, payout("WP-OLD-1", "19.70", HEADER + "SALE,OLD-PAID,,20.00,0.30,19.70\n"), null);
    assertThat(imported.data().getString("status"), is("RECONCILED"));
    String id = imported.data().getString("id");

    // The rival sees neither the batch nor, in its own file, the shop's payment.
    assertThat(get(SETTLEMENTS + "/" + id, rival).status(), is(404));
    assertThat(get(SETTLEMENTS, rival).list().size(), is(0));
    assertThat(post(SETTLEMENTS + "/" + id + "/reconcile", rival, null).status(), is(404));
    Answer theirs =
        importFile(
            rival,
            payout("WP-OLD-1", "74.00", HEADER + "SALE,OLD-WAITING,,75.00,1.00,74.00\n"),
            null);
    assertThat("the same payout number is another business's own", theirs.status(), is(201));
    assertThat(theirs.data().getInt("openExceptions"), is(1));

    for (String role : List.of("CASHIER", "STOREKEEPER", "CUSTOMER")) {
      Caller staff = me.as(role);
      assertThat(role, get(SETTLEMENTS, staff).status(), is(403));
      assertThat(role, get(SETTLEMENTS + "/" + id, staff).status(), is(403));
      assertThat(role, get(SETTLEMENTS + "/unsettled", staff).status(), is(403));
      assertThat(role, get(SETTLEMENTS + "/formats", staff).status(), is(403));
      assertThat(role, importFile(staff, payout("WP-X", "1", HEADER), null).status(), is(403));
      assertThat(role, post(SETTLEMENTS + "/" + id + "/reconcile", staff, null).status(), is(403));
      assertThat(
          role,
          resolve(staff, id, Ids.newId().toString(), "UNALLOCATED", null, "x").status(),
          is(403));
    }

    // Taken a week ago and in no payout: worth asking about. Today's is not, yet.
    String old = get(SETTLEMENTS + "/unsettled?storeId=" + RETAIL_PARK, me).list().toString();
    assertThat(old, containsString(waiting.toString()));
    assertThat(old, containsString("\"daysOutstanding\":6"));
    assertThat(old, not(containsString("OLD-PAID")));
    assertThat(old, not(containsString(fresh.toString())));
    assertThat(
        get(SETTLEMENTS + "/unsettled?olderThanDays=0&storeId=" + RETAIL_PARK, me)
            .list()
            .toString(),
        containsString(fresh.toString()));
    assertThat(get(SETTLEMENTS + "/unsettled?storeId=" + Ids.newId(), me).list().size(), is(0));
    assertThat(get(SETTLEMENTS + "/unsettled?olderThanDays=-1", me).status(), is(400));
    assertThat(get(SETTLEMENTS + "/unsettled?olderThanDays=9999", me).status(), is(400));
    assertThat(get(SETTLEMENTS + "/unsettled?storeId=not-a-uuid", me).status(), is(400));
  }

  // ── abuse ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Twenty imports of one payout at once make one batch and one announcement")
  void twentyImportsAtOnceMakeOneBatch() throws Exception {
    Caller me = Caller.owner(SHOP);
    card(HIGH_STREET, "RACE-1", "15.00");
    String body = payout("WP-RACE-1", "14.80", HEADER + "SALE,RACE-1,,15.00,0.20,14.80\n");

    List<Integer> answers =
        Concurrency.inParallel(20, () -> importFile(me, body, Ids.newId().toString()).status());

    assertThat(answers.toString(), answers.stream().filter(s -> s == 201).count(), is(1L));
    assertThat(answers.toString(), answers.stream().filter(s -> s == 409).count(), is(19L));
    String id =
        Envelopes.scalar(
            PG, "SELECT id FROM payment.settlement_batches WHERE reference = 'WP-RACE-1'");
    assertThat(announced(id).getString("batchId"), is(id));
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM payment.settlement_lines WHERE matched_tender_id IS NOT NULL"
                + " AND reference = 'RACE-1'"),
        is("1"));
  }
}
