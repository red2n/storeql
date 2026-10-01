package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Serial registration stores exactly what it answers: a generated number never clashes silently, a
 * supplied number already registered (or repeated) refuses the whole call, and a race for one
 * number has one winner.
 */
@HelidonTest
class SerialRegistrationIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  private static final String T = Ids.newId().toString();
  private static final String OTHER_T = Ids.newId().toString();
  private static final String STORE = Ids.newId().toString();
  private static final String USER = Ids.newId().toString();
  private static final String STRANGER = Ids.newId().toString();
  private static final String BASE = "/admin/inventory";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response send(
      String method, String path, String body, String tenant, String role, String stores) {
    var b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", T.equals(tenant) ? USER : STRANGER)
            .header("X-Roles", role)
            .header("Idempotency-Key", Ids.newId().toString());
    if (stores != null) b = b.header("X-Store-Ids", stores);
    return "GET".equals(method)
        ? b.get()
        : b.post(Entity.entity(body == null ? "" : body, MediaType.APPLICATION_JSON));
  }

  private static String tail() {
    String s = Ids.newId().toString();
    return s.substring(s.length() - 12).toUpperCase(java.util.Locale.ROOT);
  }

  /** A fresh batch of a fresh variant of ours; its ids. */
  private record Fixture(String batchId, String variantId) {}

  private Fixture batch() {
    String variant = Ids.newId().toString();
    Response r =
        send(
            "POST",
            BASE + "/receive",
            "{\"storeId\":\""
                + STORE
                + "\",\"variantId\":\""
                + variant
                + "\",\"qty\":500,\"batchNo\":\"SR"
                + tail()
                + "\",\"costPrice\":\"2.00\"}",
            T,
            "OWNER",
            null);
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(201));
    return new Fixture(Envelopes.parse(text).getJsonObject("data").getString("id"), variant);
  }

  private String body(Fixture f, String serialsJsonOrNull, int autoQty, String prefix) {
    return "{\"batchId\":\""
        + f.batchId()
        + "\",\"storeId\":\""
        + STORE
        + "\",\"variantId\":\""
        + f.variantId()
        + "\""
        + (serialsJsonOrNull == null
            ? ",\"autoQty\":" + autoQty
            : ",\"serials\":" + serialsJsonOrNull)
        + (prefix == null ? "" : ",\"prefix\":\"" + prefix + "\"")
        + "}";
  }

  private Response register(Fixture f, String serialsJsonOrNull, int autoQty, String prefix) {
    return send(
        "POST",
        BASE + "/serials/register",
        body(f, serialsJsonOrNull, autoQty, prefix),
        T,
        "OWNER",
        null);
  }

  private static String json(List<String> nos) {
    return "[" + String.join(",", nos.stream().map(s -> "\"" + s + "\"").toList()) + "]";
  }

  private long count(String sql, Object... args) throws Exception {
    try (var c = PG.dataSource().getConnection();
        var ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long serials(Fixture f) throws Exception {
    return count(
        "SELECT count(*) FROM inventory.serial_numbers WHERE tenant_id = ? AND batch_id = ?",
        Ids.parse(T),
        Ids.parse(f.batchId()));
  }

  private long movements(Fixture f) throws Exception {
    return count(
        "SELECT count(*) FROM inventory.serial_movements m WHERE m.tenant_id = ? AND m.serial_id IN"
            + " (SELECT s.id FROM inventory.serial_numbers s WHERE s.tenant_id = ? AND s.batch_id = ?)",
        Ids.parse(T),
        Ids.parse(T),
        Ids.parse(f.batchId()));
  }

  private long events(Fixture f) throws Exception {
    return count(
        "SELECT count(*) FROM inventory.outbox WHERE tenant_id = ? AND aggregate_id = ?"
            + " AND event_type = 'SerialsRegistered'",
        Ids.parse(T),
        Ids.parse(f.batchId()));
  }

  private static List<String> details(String text) {
    JsonObject e = Envelopes.parse(text);
    JsonObject err = e.containsKey("error") && !e.isNull("error") ? e.getJsonObject("error") : e;
    List<String> out = new ArrayList<>();
    if (err.containsKey("details") && !err.isNull("details")) {
      JsonArray a = err.getJsonArray("details");
      for (int i = 0; i < a.size(); i++) out.add(a.getString(i));
    }
    return out;
  }

  @Test
  @DisplayName(
      "A supplied number that exists in the business refuses the whole call, nothing moves")
  void suppliedNumberAlreadyRegisteredIsRefused() throws Exception {
    Fixture f = batch();
    String have = "HAVE-" + tail();
    String fresh = "NEW-" + tail();
    Response first = register(f, json(List.of(have)), 0, null);
    assertThat(first.readEntity(String.class), first.getStatus(), is(201));
    long s = serials(f);
    long m = movements(f);
    long e = events(f);

    Response r = register(f, json(List.of(fresh, have)), 0, null);
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(409));
    assertThat(text, containsString("SERIAL_ALREADY_REGISTERED"));
    assertThat(details(text), containsInAnyOrder(have));
    assertThat(serials(f), is(s));
    assertThat(movements(f), is(m));
    assertThat(events(f), is(e));
    assertThat(
        send("GET", BASE + "/serials/lookup?serial_no=" + fresh, null, T, "OWNER", null)
            .getStatus(),
        is(404));

    // A number is judged after trimming, and exactly (case counts).
    assertThat(register(f, json(List.of(" " + have + " ")), 0, null).getStatus(), is(409));
    assertThat(
        register(f, json(List.of(have.toLowerCase(java.util.Locale.ROOT))), 0, null).getStatus(),
        is(201));
  }

  @Test
  @DisplayName("A number repeated within one request refuses the call, nothing is written")
  void repeatWithinOneRequestIsRefused() throws Exception {
    Fixture f = batch();
    String dup = "DUP-" + tail();
    Response r = register(f, json(List.of(dup, "OK-" + tail(), dup)), 0, null);
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(409));
    assertThat(text, containsString("SERIAL_ALREADY_REGISTERED"));
    assertThat(details(text), containsInAnyOrder(dup));
    assertThat(serials(f), is(0L));
    assertThat(movements(f), is(0L));
    assertThat(events(f), is(0L));
  }

  @Test
  @DisplayName("Concurrent generated registrations store exactly what they answer")
  void concurrentGeneratedRegistrationsAreAllStored() throws Exception {
    Fixture f = batch();
    int threads = 8;
    int each = 25;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<List<String>>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      Callable<List<String>> job =
          () -> {
            go.await();
            Response r = register(f, null, each, "K6");
            String text = r.readEntity(String.class);
            assertThat(text, r.getStatus(), is(201));
            JsonArray data = Envelopes.parse(text).getJsonArray("data");
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) ids.add(data.getJsonObject(i).getString("id"));
            return ids;
          };
      futures.add(pool.submit(job));
    }
    go.countDown();
    Set<String> returned = new HashSet<>();
    int total = 0;
    for (Future<List<String>> fu : futures) {
      List<String> ids = fu.get();
      total += ids.size();
      returned.addAll(ids);
    }
    pool.shutdown();
    assertThat(total, is(threads * each));
    assertThat(returned, hasSize(threads * each));
    for (String id : returned) {
      Response r = send("GET", BASE + "/serials/" + id, null, T, "OWNER", null);
      assertThat(id, r.getStatus(), is(200));
      r.close();
    }
    assertThat(serials(f), is((long) total));
    assertThat(movements(f), is((long) total));
    assertThat(
        count(
            "SELECT count(*) FROM inventory.serial_movements m WHERE m.tenant_id = ? AND m.serial_id"
                + " IN (SELECT s.id FROM inventory.serial_numbers s WHERE s.tenant_id = ? AND"
                + " s.batch_id = ?) AND m.serial_id NOT IN (SELECT id FROM inventory.serial_numbers)",
            Ids.parse(T),
            Ids.parse(T),
            Ids.parse(f.batchId())),
        is(0L));
    assertThat(events(f), is((long) threads));
  }

  @Test
  @DisplayName("Two requests racing for one new number: one 201, one 409")
  void racingForTheSameSuppliedNumberHasOneWinner() throws Exception {
    Fixture f = batch();
    String same = "RACE-" + tail();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    for (int t = 0; t < 2; t++) {
      futures.add(
          pool.submit(
              () -> {
                go.await();
                Response r = register(f, json(List.of(same, "X" + tail())), 0, null);
                r.readEntity(String.class);
                return r.getStatus();
              }));
    }
    go.countDown();
    List<Integer> statuses = List.of(futures.get(0).get(), futures.get(1).get());
    pool.shutdown();
    assertThat(statuses, containsInAnyOrder(201, 409));
    assertThat(serials(f), is(2L));
    assertThat(movements(f), is(2L));
    assertThat(events(f), is(1L));
  }

  @Test
  @DisplayName("Another business, whatever the role, cannot register against our batch")
  void anotherBusinessCannotRegisterAgainstOurBatch() throws Exception {
    Fixture f = batch();
    for (String role : List.of("OWNER", "MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER")) {
      Response r =
          send("POST", BASE + "/serials/register", body(f, null, 3, "X"), OTHER_T, role, null);
      String text = r.readEntity(String.class);
      assertThat(role + ": " + text, r.getStatus(), is(oneOf(403, 404)));
    }
    // A keeper of a store that is not ours is refused at the door too.
    Response held =
        send(
            "POST",
            BASE + "/serials/register",
            body(f, null, 3, "X"),
            T,
            "STOREKEEPER",
            Ids.newId().toString());
    assertThat(held.getStatus(), is(403));
    held.close();
    assertThat(serials(f), is(0L));
    assertThat(movements(f), is(0L));
    assertThat(events(f), is(0L));
    assertThat(
        count(
            "SELECT count(*) FROM inventory.serial_numbers WHERE tenant_id = ?",
            Ids.parse(OTHER_T)),
        is(0L));
  }
}
