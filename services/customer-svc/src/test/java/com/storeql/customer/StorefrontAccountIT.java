package com.storeql.customer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shopper's own profile and address book on the storefront (12.10): keyed on the token's login,
 * so a caller reaches nothing but their own — with the wrong login, the wrong tenant, the wrong
 * input, a full book and a race for its last place tried beside the right ones.
 */
@HelidonTest
class StorefrontAccountIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "customer");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  private static final String T = "01a090e1-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a090e1-1111-7000-8000-000000000002";

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  // ── fixtures ──────────────────────────────────────────────────────────────

  private record Shopper(String login, String email) {}

  private static Shopper shopper() {
    String id = Ids.newId().toString();
    return new Shopper(id, "shopper-" + id.substring(24) + "@example.com");
  }

  private Invocation.Builder as(String path, String tenant, Shopper who) {
    var b =
        target
            .path(path)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "CUSTOMER");
    if (who != null) b = b.header("X-User-Id", who.login()).header("X-User-Email", who.email());
    return b;
  }

  private Shopper claimed() {
    Shopper s = shopper();
    Response r = as("/customers/me", T, s).post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    assertThat(r.readEntity(String.class), r.getStatus(), is(200));
    return s;
  }

  private static String addr(String line1, boolean dflt) {
    return "{\"type\":\"HOME\",\"line1\":\""
        + line1
        + "\",\"city\":\"London\",\"country\":\"GB\",\"pincode\":\"EC1A 1BB\",\"isDefault\":"
        + dflt
        + "}";
  }

  private Response add(Shopper who, String json) {
    return as("/customers/me/addresses", T, who)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static JsonObject json(String body) {
    try (var r = Json.createReader(new StringReader(body))) {
      return r.readObject();
    }
  }

  private JsonArray book(Shopper who) {
    Response r = as("/customers/me/addresses", T, who).get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return json(body).getJsonArray("data");
  }

  private static JsonObject byId(JsonArray rows, String id) {
    for (JsonValue v : rows) {
      if (id.equals(v.asJsonObject().getString("id"))) return v.asJsonObject();
    }
    throw new AssertionError(id + " not in " + rows);
  }

  private static String idOf(Response created) {
    String body = created.readEntity(String.class);
    assertThat(body, created.getStatus(), is(201));
    return json(body).getJsonObject("data").getString("id");
  }

  // ── the shopper's own ─────────────────────────────────────────────────────

  @Test
  @DisplayName("A shopper edits their own profile and keeps their own address book")
  void profileAndAddressBookAreTheShoppersOwn() {
    Shopper me = claimed();

    Response updated =
        as("/customers/me", T, me)
            .put(
                Entity.entity(
                    "{\"firstName\":\"  Chris \",\"lastName\":\"Carter\",\"phone\":\"07700900123\"}",
                    MediaType.APPLICATION_JSON));
    String body = updated.readEntity(String.class);
    assertThat(body, updated.getStatus(), is(200));
    assertThat("trimmed", body, containsString("\"firstName\":\"Chris\""));
    assertThat("the email is the login's and untouched", body, containsString(me.email()));
    assertThat(as("/customers/me", T, me).get(String.class), containsString("Carter"));

    // The language their messages are written in (13.x): set, kept by a profile save that does
    // not mention it, refused when it is not a language, cleared by an empty one.
    String profile = "{\"firstName\":\"Chris\",\"lastName\":\"Carter\"";
    Response polish =
        as("/customers/me", T, me)
            .put(
                Entity.entity(
                    profile + ",\"preferredLanguage\":\"PL\"}", MediaType.APPLICATION_JSON));
    assertThat(polish.readEntity(String.class), containsString("\"preferredLanguage\":\"pl\""));
    Response unsaid =
        as("/customers/me", T, me).put(Entity.entity(profile + "}", MediaType.APPLICATION_JSON));
    assertThat(
        "a client that knows nothing of languages keeps the one chosen",
        unsaid.readEntity(String.class),
        containsString("\"preferredLanguage\":\"pl\""));
    for (String bad : new String[] {"polish", "p1", "../"}) {
      Response refused =
          as("/customers/me", T, me)
              .put(
                  Entity.entity(
                      profile + ",\"preferredLanguage\":\"" + bad + "\"}",
                      MediaType.APPLICATION_JSON));
      assertThat(bad, refused.getStatus(), is(400));
    }
    Response cleared =
        as("/customers/me", T, me)
            .put(
                Entity.entity(
                    profile + ",\"preferredLanguage\":\"\"}", MediaType.APPLICATION_JSON));
    assertThat(cleared.readEntity(String.class).contains("preferredLanguage\":\"pl"), is(false));

    String home = idOf(add(me, addr("12 High Street", false)));
    String work = idOf(add(me, addr("1 Office Park", true)));
    JsonArray two = book(me);
    assertThat(two.size(), is(2));
    assertThat(byId(two, work).getBoolean("isDefault"), is(true));
    assertThat(byId(two, home).getBoolean("isDefault"), is(false));

    // Making one the default clears the other.
    Response made =
        as("/customers/me/addresses/" + home, T, me)
            .put(Entity.entity(addr("12 High Street", true), MediaType.APPLICATION_JSON));
    assertThat(made.getStatus(), is(200));
    JsonArray after = book(me);
    assertThat(byId(after, home).getBoolean("isDefault"), is(true));
    assertThat(byId(after, work).getBoolean("isDefault"), is(false));

    assertThat(as("/customers/me/addresses/" + work, T, me).delete().getStatus(), is(204));
    assertThat(book(me).size(), is(1));
    Response gone = as("/customers/me/addresses/" + work, T, me).delete();
    assertThat(gone.getStatus(), is(404));
    assertThat(gone.readEntity(String.class), containsString("ADDRESS_NOT_FOUND"));
  }

  @Test
  @DisplayName("Nothing is reachable without a record, and nothing without a login")
  void nothingWithoutARecordOrALogin() {
    Shopper stranger = shopper();
    assertThat(as("/customers/me", T, stranger).get().getStatus(), is(404));
    Response put =
        as("/customers/me", T, stranger)
            .put(
                Entity.entity(
                    "{\"firstName\":\"A\",\"lastName\":\"B\"}", MediaType.APPLICATION_JSON));
    assertThat(put.getStatus(), is(404));
    assertThat(put.readEntity(String.class), containsString("CUSTOMER_NOT_FOUND"));
    assertThat(as("/customers/me/addresses", T, stranger).get().getStatus(), is(404));
    assertThat(add(stranger, addr("9 Nowhere", false)).getStatus(), is(404));
    // And a stranger's failed attempts created nothing.
    assertThat(as("/customers/me", T, stranger).get().getStatus(), is(404));

    // No login at all: refused before any record is looked for.
    assertThat(
        as("/customers/me", T, null)
            .put(
                Entity.entity(
                    "{\"firstName\":\"A\",\"lastName\":\"B\"}", MediaType.APPLICATION_JSON))
            .getStatus(),
        anyOf(is(401), is(403)));
    assertThat(as("/customers/me/addresses", T, null).get().getStatus(), anyOf(is(401), is(403)));
  }

  @Test
  @DisplayName("Another shopper's address is not found, and a record is per shop")
  void anotherShoppersAddressIsOutOfReach() {
    Shopper alice = claimed();
    Shopper bob = claimed();
    String bobs = idOf(add(bob, addr("7 Bob Lane", true)));

    Response steal =
        as("/customers/me/addresses/" + bobs, T, alice)
            .put(Entity.entity(addr("Alice was here", true), MediaType.APPLICATION_JSON));
    assertThat(steal.getStatus(), is(404));
    assertThat(as("/customers/me/addresses/" + bobs, T, alice).delete().getStatus(), is(404));
    assertThat(book(alice).toString(), not(containsString(bobs)));

    // Bob's book is exactly as he left it.
    JsonArray bobBook = book(bob);
    assertThat(bobBook.size(), is(1));
    assertThat(byId(bobBook, bobs).getString("line1"), is("7 Bob Lane"));

    // The same login at another shop has no record there.
    assertThat(as("/customers/me", OTHER_T, alice).get().getStatus(), is(404));
    assertThat(as("/customers/me/addresses", OTHER_T, alice).get().getStatus(), is(404));
  }

  @Test
  @DisplayName("An address already linked to another login is not taken over")
  void anEmailLinkedToAnotherLoginIsRefused() {
    Shopper first = claimed();
    Shopper second = new Shopper(Ids.newId().toString(), first.email());

    Response refused =
        as("/customers/me", T, second).post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    String body = refused.readEntity(String.class);
    assertThat(body, refused.getStatus(), is(409));
    assertThat(body, containsString("CUSTOMER_EMAIL_LINKED_ELSEWHERE"));

    assertThat(
        "the first login's record is still its own",
        as("/customers/me", T, first).get(String.class),
        containsString(first.email()));
    assertThat(
        "the second login got no record",
        as("/customers/me", T, second).get().getStatus(),
        is(404));
  }

  // ── the wrong input ───────────────────────────────────────────────────────

  @Test
  @DisplayName("Bad input is refused by name, and free text is bounded")
  void badInputIsRefusedAndBounded() {
    Shopper me = claimed();
    assertThat(
        add(me, "{\"type\":\"HOME\",\"city\":\"London\",\"country\":\"GB\"}").getStatus(), is(400));
    assertThat(add(me, "{\"type\":\"HOME\",\"line1\":\"12 High Street\"}").getStatus(), is(400));
    assertThat(add(me, addr("x".repeat(121), false)).getStatus(), is(400));
    assertThat(
        add(
                me,
                "{\"type\":\"HOME\",\"line1\":\"12 High Street\",\"line2\":\""
                    + "y".repeat(5000)
                    + "\",\"country\":\"GB\"}")
            .getStatus(),
        is(400));
    assertThat(
        as("/customers/me", T, me)
            .put(
                Entity.entity(
                    "{\"firstName\":\"\",\"lastName\":\"Carter\"}", MediaType.APPLICATION_JSON))
            .getStatus(),
        is(400));
    assertThat(
        as("/customers/me", T, me)
            .put(
                Entity.entity(
                    "{\"firstName\":\"Chris\",\"lastName\":\"Carter\",\"phone\":\""
                        + "1".repeat(33)
                        + "\"}",
                    MediaType.APPLICATION_JSON))
            .getStatus(),
        is(400));
    assertThat(
        as("/customers/me/addresses/" + Ids.newId(), T, me)
            .put(Entity.entity(addr("Ghost", false), MediaType.APPLICATION_JSON))
            .getStatus(),
        is(404));
    // An id that is not one never reaches the service: the shared filter admits the book by
    // shape, and a segment that is not a UUID falls to default-deny for a shopper.
    assertThat(as("/customers/me/addresses/not-an-id", T, me).delete().getStatus(), is(403));
    assertThat("nothing of that was kept", book(me).size(), is(0));
  }

  // ── abuse ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("The address book is capped at ten, and the cap holds under a race")
  void theAddressBookIsCappedEvenUnderARace() throws Exception {
    Shopper me = claimed();
    for (int i = 0; i < 10; i++) {
      assertThat("address " + i, add(me, addr("Street " + i, false)).getStatus(), is(201));
    }
    Response eleventh = add(me, addr("One too many", false));
    assertThat(eleventh.getStatus(), is(409));
    assertThat(eleventh.readEntity(String.class), containsString("CUSTOMER_ADDRESS_LIMIT"));
    assertThat(book(me).size(), is(10));

    // Twenty at once on an empty book: exactly ten get in.
    Shopper racer = claimed();
    int callers = 20;
    CountDownLatch start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(callers);
    List<Integer> statuses = new ArrayList<>();
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        String line = "Race " + i;
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  Response r = add(racer, addr(line, false));
                  int status = r.getStatus();
                  r.close();
                  return status;
                }));
      }
      start.countDown();
      for (Future<Integer> f : results) statuses.add(f.get(60, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
    assertThat(statuses.toString(), statuses.stream().filter(s -> s == 201).count(), is(10L));
    assertThat(statuses.toString(), statuses.stream().filter(s -> s == 409).count(), is(10L));
    assertThat(book(racer).size(), is(10));
  }

  @Test
  @DisplayName("Hammering the profile without a record creates nothing")
  void hammeringWithoutARecordCreatesNothing() {
    Shopper stranger = shopper();
    for (int i = 0; i < 30; i++) {
      Response r =
          as("/customers/me", T, stranger)
              .put(
                  Entity.entity(
                      "{\"firstName\":\"A" + i + "\",\"lastName\":\"B\"}",
                      MediaType.APPLICATION_JSON));
      assertThat("attempt " + i, r.getStatus(), is(404));
      r.close();
    }
    assertThat(as("/customers/me", T, stranger).get().getStatus(), is(404));
  }
}
