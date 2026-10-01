package com.storeql.tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
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
}
