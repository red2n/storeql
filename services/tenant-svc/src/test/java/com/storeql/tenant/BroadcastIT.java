package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Notices to the shop floor over HTTP and a real database (store operations & workforce).
 *
 * <p>What only this test can show: that publishing and announcing are one transaction, one
 * announcement per store reached; that one acknowledgement per person is the constraint's doing;
 * that a manager's reach names who has not read a notice; and that a notice reaches the people it
 * is addressed to and nobody else.
 */
@HelidonTest
class BroadcastIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");
  private static final String ADMIN = "/admin/workforce/broadcasts";
  private static final String READ = "/workforce/broadcasts";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private record Answer(int status, JsonObject body, String text) {
    JsonObject data() {
      return body.getJsonObject("data");
    }

    List<JsonObject> list() {
      return body.getJsonArray("data").getValuesAs(JsonObject.class);
    }

    String code() {
      return body.containsKey("code") ? body.getString("code") : null;
    }
  }

  private Answer call(
      String method, String path, String json, String tenant, String user, String roles) {
    return call(method, path, json, tenant, user, roles, null);
  }

  private Answer call(
      String method,
      String path,
      String json,
      String tenant,
      String user,
      String roles,
      String stores) {
    WebTarget t = target;
    int q = path.indexOf('?');
    if (q < 0) {
      t = t.path(path);
    } else {
      t = t.path(path.substring(0, q));
      for (String pair : path.substring(q + 1).split("&")) {
        int eq = pair.indexOf('=');
        t =
            eq < 0
                ? t.queryParam(pair, "")
                : t.queryParam(pair.substring(0, eq), pair.substring(eq + 1));
      }
    }
    Invocation.Builder b = t.request(MediaType.APPLICATION_JSON);
    if (user != null) b = b.header("X-User-Id", user);
    if (tenant != null) b = b.header("X-Tenant-Id", tenant);
    if (roles != null) b = b.header("X-Roles", roles);
    if (stores != null) b = b.header("X-Store-Ids", stores);
    Response r =
        "GET".equals(method)
            ? b.get()
            : b.post(Entity.entity(json == null ? "{}" : json, MediaType.APPLICATION_JSON));
    String text = r.readEntity(String.class);
    return new Answer(r.getStatus(), asObject(text), text);
  }

  private static JsonObject asObject(String text) {
    if (text == null || text.isBlank()) return JsonObject.EMPTY_JSON_OBJECT;
    try {
      return Json.createReader(new StringReader(text)).readObject();
    } catch (RuntimeException e) {
      return JsonObject.EMPTY_JSON_OBJECT;
    }
  }

  /**
   * A business with two stores; a cashier and a storekeeper at the first, a cashier at the second.
   */
  private record Shop(
      String tenant,
      String manager,
      String storeA,
      String storeB,
      String cashierA,
      String keeperA,
      String cashierB) {}

  private Shop shop() {
    String tenant = TenantOnboarding.onboard(target, "notices", "GB", "GBP");
    String manager = Ids.newId().toString();
    String a = store(tenant, manager, "A");
    String b = store(tenant, manager, "B");
    String cashierA = staff(tenant, manager, a, "CASHIER");
    String keeperA = staff(tenant, manager, a, "STOREKEEPER");
    String cashierB = staff(tenant, manager, b, "CASHIER");
    return new Shop(tenant, manager, a, b, cashierA, keeperA, cashierB);
  }

  private String store(String tenant, String manager, String name) {
    Answer store =
        call(
            "POST",
            "/admin/stores",
            "{\"name\":\"Store "
                + name
                + "\",\"code\":\""
                + name
                + "-"
                + Ids.newId().toString().substring(0, 8)
                + "\",\"line1\":\"1 High Street\",\"city\":\"London\",\"country\":\"GB\",\"pincode\":\"E1 6AN\",\"timezone\":\"Europe/London\"}",
            tenant,
            manager,
            "OWNER");
    assertThat(store.text(), store.status(), is(201));
    return store.data().getString("id");
  }

  private String staff(String tenant, String manager, String storeId, String role) {
    String user = Ids.newId().toString();
    Answer a =
        call(
            "POST",
            "/admin/staff",
            "{\"userId\":\""
                + user
                + "\",\"storeId\":\""
                + storeId
                + "\",\"role\":\""
                + role
                + "\"}",
            tenant,
            manager,
            "OWNER");
    assertThat(a.text(), a.status(), is(201));
    return user;
  }

  private Answer publish(Shop shop, String json) {
    return call("POST", ADMIN, json, shop.tenant(), shop.manager(), "OWNER");
  }

  private List<JsonObject> current(Shop shop, String user, String role, String storeId) {
    Answer a = call("GET", READ + "?storeId=" + storeId, null, shop.tenant(), user, role);
    assertThat(a.text(), a.status(), is(200));
    return a.list();
  }

  private Answer ack(Shop shop, String user, String role, String id, String storeId) {
    return call(
        "POST",
        READ + "/" + id + "/acknowledgement",
        "{\"storeId\":\"" + storeId + "\"}",
        shop.tenant(),
        user,
        role);
  }

  private static List<String> announcements(String tenant) {
    List<String> out = new ArrayList<>();
    try (Connection c = PG.dataSource().getConnection()) {
      c.setSchema("tenant");
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT aggregate_id, payload FROM outbox WHERE tenant_id = ?::uuid AND event_type = 'StoreBroadcastPublished' ORDER BY created_at")) {
        ps.setString(1, tenant);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) out.add(rs.getString(1) + " " + rs.getString(2));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  @Test
  @DisplayName("A notice reaches every store, is read by the staff, and each acknowledges it once")
  void everyStore() {
    Shop shop = shop();
    Answer published =
        publish(
            shop,
            "{\"title\":\"Bananas up 10p\",\"body\":\"From Monday.\",\"priority\":\"INFO\",\"requiresAck\":true}");
    assertThat(published.text(), published.status(), is(201));
    String id = published.data().getString("id");
    // One announcement per store reached, written with the notice: two stores, two rows.
    List<String> told =
        announcements(shop.tenant()).stream().filter(e -> e.startsWith(id)).toList();
    assertThat(told, hasSize(2));
    assertThat(told.get(0), containsString("\"wake\":false"));

    List<JsonObject> mine = current(shop, shop.cashierA(), "CASHIER", shop.storeA());
    assertThat(mine, hasSize(1));
    assertThat(mine.get(0).getString("title"), is("Bananas up 10p"));
    assertThat("not yet acknowledged", mine.get(0).containsKey("acknowledgedAt"), is(false));

    Answer acked = ack(shop, shop.cashierA(), "CASHIER", id, shop.storeA());
    assertThat(acked.text(), acked.status(), is(201));
    assertThat(
        current(shop, shop.cashierA(), "CASHIER", shop.storeA()).get(0).getString("acknowledgedAt"),
        is(acked.body().getString("data")));
    Answer twice = ack(shop, shop.cashierA(), "CASHIER", id, shop.storeA());
    assertThat(
        "the constraint decides, and names it", twice.code(), is("BROADCAST_ALREADY_ACKNOWLEDGED"));

    // The manager sees, per store, who has not read it — named.
    Answer reach =
        call("GET", ADMIN + "/" + id + "/reach", null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(reach.text(), reach.status(), is(200));
    JsonObject a =
        reach.list().stream()
            .filter(r -> shop.storeA().equals(r.getString("storeId")))
            .findFirst()
            .orElseThrow();
    JsonObject b =
        reach.list().stream()
            .filter(r -> shop.storeB().equals(r.getString("storeId")))
            .findFirst()
            .orElseThrow();
    assertThat(a.getInt("addressed"), is(2));
    assertThat(a.getInt("acknowledged"), is(1));
    assertThat(a.getJsonArray("outstanding").getString(0), is(shop.keeperA()));
    assertThat(a.getBoolean("complete"), is(false));
    assertThat(b.getInt("addressed"), is(1));
    assertThat(b.getJsonArray("outstanding").getString(0), is(shop.cashierB()));
  }

  @Test
  @DisplayName("A notice to one store and one role reaches those people and nobody else")
  void addressed() {
    Shop shop = shop();
    Answer keepers =
        publish(
            shop,
            "{\"title\":\"Delivery at 6\",\"body\":\"Be in the yard.\",\"priority\":\"IMPORTANT\",\"storeId\":\""
                + shop.storeA()
                + "\",\"role\":\"STOREKEEPER\"}");
    assertThat(keepers.text(), keepers.status(), is(201));
    String id = keepers.data().getString("id");
    assertThat(
        "one store reached, one announcement",
        announcements(shop.tenant()).stream().filter(e -> e.startsWith(id)).count(),
        is(1L));
    assertThat(current(shop, shop.keeperA(), "STOREKEEPER", shop.storeA()), hasSize(1));
    assertThat(
        "a cashier at the same store is not addressed",
        current(shop, shop.cashierA(), "CASHIER", shop.storeA()),
        hasSize(0));
    assertThat(
        "the other store is not addressed",
        current(shop, shop.cashierB(), "CASHIER", shop.storeB()),
        hasSize(0));
    Answer notMine = ack(shop, shop.cashierA(), "CASHIER", id, shop.storeA());
    assertThat(notMine.status(), is(409));
    assertThat(notMine.code(), is("BROADCAST_NOT_ADDRESSED"));
    // Reach counts only the store and role it went to.
    Answer reach =
        call("GET", ADMIN + "/" + id + "/reach", null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(reach.list(), hasSize(1));
    assertThat(reach.list().get(0).getInt("addressed"), is(1));
  }

  @Test
  @DisplayName(
      "An urgent notice wakes the devices; a withdrawn or expired one is no longer current")
  void urgentWithdrawnExpired() {
    Shop shop = shop();
    Answer urgent =
        publish(
            shop,
            "{\"title\":\"Recall\",\"body\":\"Batch 42 off the shelf now.\",\"priority\":\"URGENT\"}");
    assertThat(urgent.status(), is(201));
    String id = urgent.data().getString("id");
    assertThat(
        announcements(shop.tenant()).stream()
            .filter(e -> e.startsWith(id))
            .findFirst()
            .orElseThrow(),
        containsString("\"wake\":true"));

    Answer noReason =
        call(
            "POST",
            ADMIN + "/" + id + "/withdrawal",
            "{\"reason\":\"\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(noReason.status(), is(400));
    Answer withdrawn =
        call(
            "POST",
            ADMIN + "/" + id + "/withdrawal",
            "{\"reason\":\"wrong batch\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(withdrawn.text(), withdrawn.status(), is(200));
    assertThat(withdrawn.data().getString("status"), is("WITHDRAWN"));
    assertThat(withdrawn.data().getString("withdrawnReason"), is("wrong batch"));
    assertThat(
        call(
                "POST",
                ADMIN + "/" + id + "/withdrawal",
                "{\"reason\":\"again\"}",
                shop.tenant(),
                shop.manager(),
                "OWNER")
            .code(),
        is("BROADCAST_WITHDRAWN"));
    assertThat(
        "gone from what is current",
        current(shop, shop.cashierA(), "CASHIER", shop.storeA()),
        hasSize(0));
    assertThat(
        ack(shop, shop.cashierA(), "CASHIER", id, shop.storeA()).code(),
        is("BROADCAST_NOT_CURRENT"));
    // Still there for management, with why it went.
    assertThat(call("GET", ADMIN, null, shop.tenant(), shop.manager(), "OWNER").list(), hasSize(0));
    assertThat(
        call("GET", ADMIN + "?all=true", null, shop.tenant(), shop.manager(), "OWNER").list(),
        hasSize(1));

    // Expired: published, and already nothing to say.
    String soon = Instant.now().plusSeconds(2).toString();
    Answer brief =
        publish(
            shop,
            "{\"title\":\"Fire drill 10:00\",\"body\":\"Assemble in the yard.\",\"priority\":\"INFO\",\"expiresAt\":\""
                + soon
                + "\"}");
    assertThat(brief.text(), brief.status(), is(201));
    assertThat(current(shop, shop.cashierA(), "CASHIER", shop.storeA()), hasSize(1));
    try {
      Thread.sleep(2500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertThat(
        "a notice about Thursday's drill has nothing to say on Friday",
        current(shop, shop.cashierA(), "CASHIER", shop.storeA()),
        hasSize(0));
    assertThat(
        publish(
                shop,
                "{\"title\":\"Nothing\",\"body\":\"x\",\"priority\":\"INFO\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}")
            .code(),
        is("BROADCAST_INVALID"));
  }

  @Test
  @DisplayName("A notice that tells nobody anything is refused, and who may publish is management")
  void refusalsAndWhoMay() {
    Shop shop = shop();
    assertThat(
        publish(shop, "{\"title\":\"x\",\"body\":\"y\",\"priority\":\"SHOUT\"}").code(),
        is("BROADCAST_INVALID"));
    assertThat(
        publish(
                shop,
                "{\"title\":\"x\",\"body\":\"y\",\"priority\":\"INFO\",\"expiresAt\":\"tomorrow\"}")
            .code(),
        is("BROADCAST_EXPIRY_INVALID"));
    assertThat(
        publish(
                shop,
                "{\"title\":\"x\",\"body\":\"y\",\"priority\":\"INFO\",\"storeId\":\""
                    + Ids.newId()
                    + "\"}")
            .code(),
        is("STORE_NOT_FOUND"));
    assertThat(
        publish(shop, "{\"title\":\"\",\"body\":\"y\",\"priority\":\"INFO\"}").status(), is(400));
    assertThat(
        call(
                "POST",
                ADMIN,
                "{\"title\":\"Sneak\",\"body\":\"y\",\"priority\":\"URGENT\"}",
                shop.tenant(),
                shop.cashierA(),
                "CASHIER")
            .status(),
        is(403));
    String id =
        publish(shop, "{\"title\":\"Hello\",\"body\":\"All.\",\"priority\":\"INFO\"}")
            .data()
            .getString("id");
    assertThat(
        call("GET", ADMIN + "/" + id + "/reach", null, shop.tenant(), shop.cashierA(), "CASHIER")
            .status(),
        is(403));
    // Somebody not on the store's staff is told so, and another business sees nothing.
    Answer stranger = ack(shop, Ids.newId().toString(), "CASHIER", id, shop.storeA());
    assertThat(stranger.code(), is("WORKFORCE_NOT_ASSIGNED"));
    Shop rival = shop();
    Answer theirRead =
        call("GET", ADMIN + "/" + id, null, rival.tenant(), rival.manager(), "OWNER");
    assertThat(theirRead.status(), is(404));
    assertThat(theirRead.code(), is("BROADCAST_NOT_FOUND"));
    Answer theirAck = ack(rival, rival.cashierA(), "CASHIER", id, rival.storeA());
    assertThat(theirAck.status(), is(404));
    assertThat(theirAck.code(), is("BROADCAST_NOT_FOUND"));
    assertThat(current(rival, rival.cashierA(), "CASHIER", rival.storeA()), hasSize(0));
    assertThat(
        call("GET", READ + "?storeId=" + shop.storeA(), null, shop.tenant(), null, null).data(),
        is(nullValue()));
  }

  /** A notice from the owner, to one store or (null) every store; its id. */
  private String noticeTo(Shop shop, String storeId, String title) {
    Answer a =
        publish(
            shop,
            "{\"title\":\""
                + title
                + "\",\"body\":\"Read me.\",\"priority\":\"INFO\",\"requiresAck\":true"
                + (storeId == null ? "" : ",\"storeId\":\"" + storeId + "\"")
                + "}");
    assertThat(a.text(), a.status(), is(201));
    return a.data().getString("id");
  }

  /** The business's notices as its owner sees them: id to status. */
  private java.util.Map<String, String> statuses(Shop shop) {
    Answer all = call("GET", ADMIN + "?all=true", null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(all.text(), all.status(), is(200));
    java.util.Map<String, String> out = new java.util.HashMap<>();
    for (JsonObject n : all.list()) out.put(n.getString("id"), n.getString("status"));
    return out;
  }

  @Test
  @DisplayName(
      "A branch manager publishes, reads and withdraws at their own store, reads every store's"
          + " notices only for their store, and never touches another store's")
  void heldToTheCallersStores() {
    Shop shop = shop();
    String atA = noticeTo(shop, shop.storeA(), "For A");
    String atB = noticeTo(shop, shop.storeB(), "For B");
    String everywhere = noticeTo(shop, null, "For all");
    String m = shop.manager();
    String heldA = shop.storeA();

    // Publishing: their store; never another store, never every store.
    String toA =
        "{\"title\":\"Mop\",\"body\":\"Aisle 3.\",\"priority\":\"INFO\",\"storeId\":\""
            + shop.storeA()
            + "\"}";
    String toB = toA.replace(shop.storeA(), shop.storeB());
    String toAll = "{\"title\":\"Mop\",\"body\":\"Aisle 3.\",\"priority\":\"INFO\"}";
    Answer own = call("POST", ADMIN, toA, shop.tenant(), m, "MANAGER", heldA);
    assertThat(own.text(), own.status(), is(201));
    Answer other = call("POST", ADMIN, toB, shop.tenant(), m, "MANAGER", heldA);
    assertThat(other.text(), other.status(), is(403));
    assertThat(other.code(), is("STORE_ACCESS_DENIED"));
    Answer all = call("POST", ADMIN, toAll, shop.tenant(), m, "MANAGER", heldA);
    assertThat(all.text(), all.status(), is(403));
    assertThat(all.code(), is("BUSINESS_WIDE_ONLY"));
    assertThat("the refusals published nothing", statuses(shop).size(), is(4));

    // The list: A's own and every store's, never B's.
    Answer listed = call("GET", ADMIN + "?all=true", null, shop.tenant(), m, "MANAGER", heldA);
    assertThat(listed.text(), listed.status(), is(200));
    List<String> ids = listed.list().stream().map(n -> n.getString("id")).toList();
    assertThat(ids.contains(atA) && ids.contains(everywhere), is(true));
    assertThat("B's notice is not listed", ids.contains(atB), is(false));

    // One notice and its reach: B's refused; every store's read, its reach only A's rows.
    for (String path : List.of(ADMIN + "/" + atB, ADMIN + "/" + atB + "/reach")) {
      Answer refused = call("GET", path, null, shop.tenant(), m, "MANAGER", heldA);
      assertThat(path + " -> " + refused.text(), refused.status(), is(403));
      assertThat(refused.code(), is("STORE_ACCESS_DENIED"));
      assertThat(refused.text(), not(containsString(shop.cashierB())));
    }
    assertThat(
        call("GET", ADMIN + "/" + everywhere, null, shop.tenant(), m, "MANAGER", heldA).status(),
        is(200));
    Answer reach =
        call("GET", ADMIN + "/" + everywhere + "/reach", null, shop.tenant(), m, "MANAGER", heldA);
    assertThat(reach.text(), reach.status(), is(200));
    assertThat(reach.list(), hasSize(1));
    assertThat(reach.list().get(0).getString("storeId"), is(shop.storeA()));
    assertThat("nobody at B is named", reach.text(), not(containsString(shop.cashierB())));

    // Withdrawing: A's own; B's and every store's are refused and stay published.
    String reason = "{\"reason\":\"sent in error\"}";
    Answer theirs =
        call("POST", ADMIN + "/" + atB + "/withdrawal", reason, shop.tenant(), m, "MANAGER", heldA);
    assertThat(theirs.text(), theirs.status(), is(403));
    assertThat(theirs.code(), is("STORE_ACCESS_DENIED"));
    Answer whole =
        call(
            "POST",
            ADMIN + "/" + everywhere + "/withdrawal",
            reason,
            shop.tenant(),
            m,
            "MANAGER",
            heldA);
    assertThat(whole.text(), whole.status(), is(403));
    assertThat(whole.code(), is("BUSINESS_WIDE_ONLY"));
    assertThat(statuses(shop).get(atB), is("PUBLISHED"));
    assertThat(statuses(shop).get(everywhere), is("PUBLISHED"));
    Answer mine =
        call("POST", ADMIN + "/" + atA + "/withdrawal", reason, shop.tenant(), m, "MANAGER", heldA);
    assertThat(mine.text(), mine.status(), is(200));

    // A manager held to no store: every store's reach, and withdraws any notice.
    Answer wholeReach =
        call("GET", ADMIN + "/" + everywhere + "/reach", null, shop.tenant(), m, "MANAGER", null);
    assertThat(wholeReach.list(), hasSize(2));
    assertThat(
        call("POST", ADMIN + "/" + atB + "/withdrawal", reason, shop.tenant(), m, "MANAGER", null)
            .status(),
        is(200));

    // Another business: its management, held to its own store or to none, finds no such notice;
    // its staff and shoppers are refused outright; its list holds none of ours.
    Shop rival = shop();
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (String held : new String[] {null, rival.storeA()}) {
        for (Answer a :
            List.of(
                call(
                    "GET",
                    ADMIN + "/" + everywhere,
                    null,
                    rival.tenant(),
                    rival.manager(),
                    role,
                    held),
                call(
                    "GET",
                    ADMIN + "/" + everywhere + "/reach",
                    null,
                    rival.tenant(),
                    rival.manager(),
                    role,
                    held),
                call(
                    "POST",
                    ADMIN + "/" + everywhere + "/withdrawal",
                    reason,
                    rival.tenant(),
                    rival.manager(),
                    role,
                    held))) {
          assertThat(role + " -> " + a.text(), a.status(), is(404));
          assertThat(a.code(), is("BROADCAST_NOT_FOUND"));
        }
        Answer theirList =
            call("GET", ADMIN + "?all=true", null, rival.tenant(), rival.manager(), role, held);
        assertThat(theirList.text(), theirList.status(), is(200));
        assertThat(theirList.list(), hasSize(0));
      }
    }
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(
          role,
          call("GET", ADMIN + "/" + everywhere, null, rival.tenant(), rival.cashierA(), role)
              .status(),
          is(403));
      assertThat(
          role,
          call(
                  "POST",
                  ADMIN + "/" + everywhere + "/withdrawal",
                  reason,
                  shop.tenant(),
                  shop.cashierA(),
                  role,
                  shop.storeA())
              .status(),
          is(403));
    }
    assertThat("nobody else withdrew it", statuses(shop).get(everywhere), is("PUBLISHED"));
  }

  /** Acknowledging as a caller of some role, held to some stores (none when null). */
  private Answer ackAs(
      String tenant, String user, String role, String id, String storeId, String heldTo) {
    return call(
        "POST",
        READ + "/" + id + "/acknowledgement",
        "{\"storeId\":\"" + storeId + "\"}",
        tenant,
        user,
        role,
        heldTo);
  }

  private Answer currentAs(String tenant, String user, String role, String storeId, String heldTo) {
    return call("GET", READ + "?storeId=" + storeId, null, tenant, user, role, heldTo);
  }

  private static int acksOf(String tenant) {
    try (Connection c = PG.dataSource().getConnection()) {
      c.setSchema("tenant");
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT count(*) FROM store_broadcast_acks WHERE tenant_id = ?::uuid")) {
        ps.setString(1, tenant);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          return rs.getInt(1);
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  @DisplayName(
      "An owner or a business-wide manager reads and acknowledges a store's notices without being"
          + " assigned there, as they work its task list; staff below stay held to where assigned")
  void businessWideManagementAcknowledgesAtAnyStore() {
    Shop shop = shop();
    String everywhere = noticeTo(shop, null, "For all");
    String atB = noticeTo(shop, shop.storeB(), "For B");
    Answer cashiersOnly =
        publish(
            shop,
            "{\"title\":\"Float\",\"body\":\"Count it.\",\"priority\":\"INFO\",\"requiresAck\":true,"
                + "\"storeId\":\""
                + shop.storeB()
                + "\",\"role\":\"CASHIER\"}");
    assertThat(cashiersOnly.text(), cashiersOnly.status(), is(201));
    String forCashiers = cashiersOnly.data().getString("id");

    // The owner, assigned nowhere: reads B's notices to everybody there, and acknowledges them.
    Answer read = currentAs(shop.tenant(), shop.manager(), "OWNER", shop.storeB(), null);
    assertThat(read.text(), read.status(), is(200));
    List<String> seen = read.list().stream().map(n -> n.getString("id")).toList();
    assertThat(seen.contains(everywhere) && seen.contains(atB), is(true));
    assertThat(
        "a notice to the cashiers is not the owner's", seen.contains(forCashiers), is(false));
    for (String id : List.of(everywhere, atB)) {
      Answer acked = ackAs(shop.tenant(), shop.manager(), "OWNER", id, shop.storeB(), null);
      assertThat(acked.text(), acked.status(), is(201));
    }
    Answer notAddressed =
        ackAs(shop.tenant(), shop.manager(), "OWNER", forCashiers, shop.storeB(), null);
    assertThat(notAddressed.text(), notAddressed.status(), is(409));
    assertThat(notAddressed.code(), is("BROADCAST_NOT_ADDRESSED"));
    Answer twice = ackAs(shop.tenant(), shop.manager(), "OWNER", atB, shop.storeB(), null);
    assertThat(twice.code(), is("BROADCAST_ALREADY_ACKNOWLEDGED"));

    // A business-wide manager, assigned to no store: the same at A.
    String head = Ids.newId().toString();
    Answer assigned =
        call(
            "POST",
            "/admin/staff",
            "{\"userId\":\"" + head + "\",\"businessWide\":true,\"role\":\"MANAGER\"}",
            shop.tenant(),
            shop.manager(),
            "OWNER");
    assertThat(assigned.text(), assigned.status(), is(201));
    Answer headAck = ackAs(shop.tenant(), head, "MANAGER", everywhere, shop.storeA(), null);
    assertThat(headAck.text(), headAck.status(), is(201));

    // A branch manager held to A is told B is not theirs; a cashier whose token names no store is
    // still held to where they are assigned.
    Answer branch = ackAs(shop.tenant(), head, "MANAGER", atB, shop.storeB(), shop.storeA());
    assertThat(branch.text(), branch.status(), is(403));
    assertThat(branch.code(), is("STORE_ACCESS_DENIED"));
    Answer cashierElsewhere =
        ackAs(shop.tenant(), shop.cashierA(), "CASHIER", atB, shop.storeB(), null);
    assertThat(cashierElsewhere.text(), cashierElsewhere.status(), is(409));
    assertThat(cashierElsewhere.code(), is("WORKFORCE_NOT_ASSIGNED"));
    Answer keeperRead =
        currentAs(shop.tenant(), shop.keeperA(), "STOREKEEPER", shop.storeB(), null);
    assertThat(keeperRead.code(), is("WORKFORCE_NOT_ASSIGNED"));
    assertThat("three acknowledgements, all management's", acksOf(shop.tenant()), is(3));

    // The reach still counts B's own staff: the owner was never one of them.
    Answer reach =
        call("GET", ADMIN + "/" + atB + "/reach", null, shop.tenant(), shop.manager(), "OWNER");
    assertThat(reach.list(), hasSize(1));
    assertThat(reach.list().get(0).getInt("addressed"), is(1));
    assertThat(reach.list().get(0).getInt("acknowledged"), is(0));
  }

  @Test
  @DisplayName(
      "Publishing, reading and acknowledging judge the store first: not the business's is 404, not"
          + " the caller's is 403, and nothing is written")
  void theStoreIsJudgedBeforeTheCaller() {
    Shop shop = shop();
    Shop rival = shop();
    String everywhere = noticeTo(shop, null, "For all");
    String unknown = Ids.newId().toString();
    String toUnknown =
        "{\"title\":\"Mop\",\"body\":\"Aisle 3.\",\"priority\":\"INFO\",\"storeId\":\""
            + unknown
            + "\"}";
    String toOurA = toUnknown.replace(unknown, shop.storeA());

    // Our branch manager naming a store that is nobody's: not found (it was 403).
    Answer nowhere =
        call("POST", ADMIN, toUnknown, shop.tenant(), shop.manager(), "MANAGER", shop.storeA());
    assertThat(nowhere.text(), nowhere.status(), is(404));
    assertThat(nowhere.code(), is("STORE_NOT_FOUND"));
    // Another business's management naming our store, held to its own, to ours or to none.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (String held : new String[] {rival.storeA(), shop.storeA(), null}) {
        Answer theirs = call("POST", ADMIN, toOurA, rival.tenant(), rival.manager(), role, held);
        assertThat(role + " -> " + theirs.text(), theirs.status(), is(404));
        assertThat(theirs.code(), is("STORE_NOT_FOUND"));
      }
    }
    assertThat("nothing was published", statuses(shop).size(), is(1));
    assertThat(statuses(rival).size(), is(0));

    // Another business's staff of every role reading or acknowledging at our store: not found —
    // never 403 for a store that is not theirs, never 409 for one they are not assigned at.
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "MANAGER", "OWNER"}) {
      for (String held : new String[] {rival.storeA(), shop.storeA(), null}) {
        Answer read = currentAs(rival.tenant(), rival.cashierA(), role, shop.storeA(), held);
        assertThat(role + " -> " + read.text(), read.status(), is(404));
        assertThat(read.code(), is("STORE_NOT_FOUND"));
        Answer acked =
            ackAs(rival.tenant(), rival.cashierA(), role, everywhere, shop.storeA(), held);
        assertThat(role + " -> " + acked.text(), acked.status(), is(404));
        assertThat(acked.code(), is("STORE_NOT_FOUND"));
      }
    }
    // Our cashier at A naming B: the business's, not theirs.
    Answer ours =
        ackAs(shop.tenant(), shop.cashierA(), "CASHIER", everywhere, shop.storeB(), shop.storeA());
    assertThat(ours.text(), ours.status(), is(403));
    assertThat(ours.code(), is("STORE_ACCESS_DENIED"));
    // A shopper is no staff at all.
    assertThat(
        ackAs(rival.tenant(), Ids.newId().toString(), "CUSTOMER", everywhere, shop.storeA(), null)
            .status(),
        is(403));
    assertThat("nobody acknowledged anything", acksOf(shop.tenant()), is(0));
    assertThat(acksOf(rival.tenant()), is(0));
  }
}
