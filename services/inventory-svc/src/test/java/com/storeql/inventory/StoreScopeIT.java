package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SJ-D74: the store scope on a login (and on an API key) is a promise the stock ledger has to keep.
 * A storekeeper assigned to store A books goods in at A and nowhere else, reads A's stock and
 * nobody else's, and moves stock only from a store they keep; an owner, who is assigned to no
 * store, is held to none.
 */
@HelidonTest
class StoreScopeIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  private static final String T = "01a090ae-7c1e-7d2c-a97b-d1b8025478e1";
  private static final String A = "01a090ae-7c1e-7a01-a378-a4972ea461c8";
  private static final String B = "01a090ae-7c1e-7b02-a378-a4972ea461c8";
  private static final String C = "01a090ae-7c1e-7c03-a378-a4972ea461c8";
  private static final String V = "01a090ae-7c1e-7e04-bde4-50df0324c37c";
  private static final String KEEPER_A = "01a090ae-7c1e-7f05-bde4-50df0324c37c";
  private static final String KEEPER_B = "01a090ae-7c1e-7f06-bde4-50df0324c37c";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Invocation.Builder as(String path, String role, String user, String stores) {
    var b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-User-Id", user)
            .header("X-Roles", role)
            .header("Idempotency-Key", Ids.newId().toString());
    return stores == null ? b : b.header("X-Store-Ids", stores);
  }

  private Response keeperOfA(String method, String path, String json) {
    return send(as(path, "STOREKEEPER", KEEPER_A, A), method, json);
  }

  private Response keeperOfB(String method, String path, String json) {
    return send(as(path, "STOREKEEPER", KEEPER_B, B), method, json);
  }

  private Response owner(String method, String path, String json) {
    return send(as(path, "OWNER", KEEPER_A, null), method, json);
  }

  private static Response send(Invocation.Builder b, String method, String json) {
    return switch (method) {
      case "GET" -> b.get();
      case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  private static String receiveBody(String store, int qty, String lot) {
    return "{\"storeId\":\""
        + store
        + "\",\"variantId\":\""
        + V
        + "\",\"qty\":"
        + qty
        + ",\"batchNo\":\""
        + lot
        + "\",\"costPrice\":\"2.00\"}";
  }

  private static String transferBody(String from, String to) {
    return "{\"fromStoreId\":\""
        + from
        + "\",\"toStoreId\":\""
        + to
        + "\",\"transferType\":\"INTRANSIT\",\"lines\":[{\"variantId\":\""
        + V
        + "\",\"requestedQty\":1}]}";
  }

  private static JsonObject data(Response r) {
    String body = r.readEntity(String.class);
    return Json.createReader(new StringReader(body)).readObject().getJsonObject("data");
  }

  private static void refusedForStore(Response r, String what) {
    String body = r.readEntity(String.class);
    assertThat(what + ": " + body, r.getStatus(), is(403));
    assertThat(what, body, containsString("STORE_ACCESS_DENIED"));
  }

  @Test
  @DisplayName("Goods are booked in, adjusted and read only at the stores the caller keeps")
  void receiptsAdjustmentsAndReadsHoldToTheCallersStores() {
    refusedForStore(
        keeperOfA("POST", "/admin/inventory/receive", receiveBody(B, 5, "AT-B")), "receive at B");
    assertThat(
        keeperOfA("POST", "/admin/inventory/receive", receiveBody(A, 5, "AT-A")).getStatus(),
        is(201));
    refusedForStore(
        keeperOfA(
            "POST",
            "/admin/inventory/receive/batch",
            "{\"items\":[" + receiveBody(A, 1, "OK") + "," + receiveBody(B, 1, "NOT") + "]}"),
        "a batch with one line at B");
    refusedForStore(
        keeperOfA(
            "POST",
            "/admin/inventory/adjust",
            "{\"storeId\":\""
                + B
                + "\",\"variantId\":\""
                + V
                + "\",\"delta\":-1,\"reason\":\"DAMAGED\"}"),
        "adjust at B");

    // The owner, assigned to no store, stocks B; the keeper of A sees none of it.
    String atB =
        data(owner("POST", "/admin/inventory/receive", receiveBody(B, 7, "OWNER-B")))
            .getString("id");
    refusedForStore(keeperOfA("GET", "/admin/inventory/levels?store=" + B, null), "levels of B");
    refusedForStore(
        keeperOfA("GET", "/admin/inventory/levels/summary?store=" + B, null), "summary of B");
    refusedForStore(keeperOfA("GET", "/admin/inventory/batches?store=" + B, null), "batches of B");
    refusedForStore(
        keeperOfA("GET", "/admin/inventory/movements?store=" + B, null), "movements of B");
    refusedForStore(
        keeperOfA("GET", "/admin/inventory/batches/" + atB, null), "a batch at B by id");
    refusedForStore(
        keeperOfA(
            "PUT",
            "/admin/inventory/batches/" + atB + "/material-status",
            "{\"materialStatus\":\"QUARANTINE\"}"),
        "quarantining a batch at B");
    assertThat(owner("GET", "/admin/inventory/levels?store=" + B, null).getStatus(), is(200));

    // Naming no store, a keeper of one store reads that store: A's five, not B's seven.
    Response mine = keeperOfA("GET", "/admin/inventory/levels", null);
    String levels = mine.readEntity(String.class);
    assertThat(levels, mine.getStatus(), is(200));
    assertThat(levels, containsString("\"onHand\":5"));
    assertThat(levels, not(containsString("\"onHand\":7")));
  }

  @Test
  @DisplayName("Stock moves only from a store the caller keeps, and arrives only at one they keep")
  void transfersHoldToTheCallersStores() {
    assertThat(
        owner("POST", "/admin/inventory/receive", receiveBody(A, 4, "FOR-TRANSFER")).getStatus(),
        is(201));
    refusedForStore(
        keeperOfA("POST", "/admin/inventory/transfers", transferBody(B, A)), "sending from B");
    refusedForStore(
        keeperOfA(
            "POST",
            "/admin/inventory/move-orders",
            "{\"fromStoreId\":\""
                + B
                + "\",\"toStoreId\":\""
                + B
                + "\",\"lines\":[{\"variantId\":\""
                + V
                + "\",\"requestedQty\":1}]}"),
        "a move within B");
    Response raised = keeperOfA("POST", "/admin/inventory/transfers", transferBody(A, B));
    assertThat(raised.getStatus(), is(201));
    String id = data(raised).getString("id");
    refusedForStore(
        keeperOfB("POST", "/admin/inventory/transfers/" + id + "/ship", ""),
        "B shipping A's stock");
    assertThat(
        keeperOfA("POST", "/admin/inventory/transfers/" + id + "/ship", "").getStatus(), is(200));
    refusedForStore(
        keeperOfA("POST", "/admin/inventory/transfers/" + id + "/receive", ""), "A receiving at B");
    assertThat(
        keeperOfB("POST", "/admin/inventory/transfers/" + id + "/receive", "").getStatus(),
        is(200));
    refusedForStore(
        send(as("/admin/inventory/transfers/" + id, "STOREKEEPER", KEEPER_B, C), "GET", null),
        "a keeper of C reading a transfer between A and B");
    assertThat(keeperOfB("GET", "/admin/inventory/transfers/" + id, null).getStatus(), is(200));
  }
}
