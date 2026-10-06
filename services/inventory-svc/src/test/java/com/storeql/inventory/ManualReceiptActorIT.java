package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.inventory.service.InventoryService;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who received the stock. A shrinkage investigation opens with "what did this member of staff
 * touch?", and a manual receipt is a person's act as much as an adjustment is: the movement it
 * writes names the signed-in user (stock_movements.actor_id), where a receipt an event caused names
 * nobody and cites the record that caused it instead (ref_type / ref_id).
 */
@HelidonTest
class ManualReceiptActorIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  @Inject WebTarget target;
  @Inject InventoryService service;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Invocation.Builder as(
      String path, String tenant, String user, String role, String idempotencyKey) {
    Invocation.Builder b =
        WebTargets.at(target, path).request().header("X-Tenant-Id", tenant).header("X-Roles", role);
    if (user != null) b = b.header("X-User-Id", user);
    if (idempotencyKey != null) b = b.header("Idempotency-Key", idempotencyKey);
    return b;
  }

  private Response receive(
      String tenant, String user, String store, String variant, int qty, String key) {
    return as("/admin/inventory/receive", tenant, user, "OWNER", key)
        .post(
            Entity.entity(
                "{\"storeId\":\""
                    + store
                    + "\",\"variantId\":\""
                    + variant
                    + "\",\"qty\":"
                    + qty
                    + ",\"batchNo\":\"L-"
                    + variant.substring(variant.length() - 6)
                    + "\"}",
                MediaType.APPLICATION_JSON));
  }

  /** The actor of every RECEIVE movement of the variant, as {@code actor|ref_type} rows. */
  private static String receipts(String tenant, String variant) {
    return Envelopes.scalar(
        PG,
        "SELECT coalesce(string_agg(coalesce(actor_id::text, 'NULL') || '|' || ref_type, ','"
            + " ORDER BY created_at), '') FROM inventory.stock_movements WHERE tenant_id = '"
            + tenant
            + "' AND variant_id = '"
            + variant
            + "' AND type = 'RECEIVE'");
  }

  private static int movements(String tenant) {
    return Integer.parseInt(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM inventory.stock_movements WHERE tenant_id = '" + tenant + "'"));
  }

  // ── the receipt names its person ──────────────────────────────────────────

  @Test
  @DisplayName("A manual receipt names the staff member who entered it, on the ledger and the API")
  void aManualReceiptNamesTheStaffMemberWhoEnteredIt() {
    String tenant = Ids.newId().toString();
    String staff = Ids.newId().toString();
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();

    Response r = receive(tenant, staff, store, variant, 12, null);
    assertThat(r.readEntity(String.class), r.getStatus(), is(201));

    assertThat(receipts(tenant, variant), is(staff + "|MANUAL"));

    JsonArray listed =
        Envelopes.okArray(
            as(
                    "/admin/inventory/movements?variant=" + variant + "&type=RECEIVE",
                    tenant,
                    staff,
                    "OWNER",
                    null)
                .get());
    assertThat(listed.size(), is(1));
    assertThat(listed.getJsonObject(0).getString("actorId"), is(staff));
  }

  @Test
  @DisplayName("A bulk receipt names the staff member on every line it books")
  void aBulkReceiptNamesTheStaffMemberOnEveryLine() {
    String tenant = Ids.newId().toString();
    String staff = Ids.newId().toString();
    String store = Ids.newId().toString();
    String first = Ids.newId().toString();
    String second = Ids.newId().toString();
    String body =
        "{\"items\":[{\"storeId\":\""
            + store
            + "\",\"variantId\":\""
            + first
            + "\",\"qty\":3},{\"storeId\":\""
            + store
            + "\",\"variantId\":\""
            + second
            + "\",\"qty\":4}]}";

    JsonObject done =
        Envelopes.ok(
            as("/admin/inventory/receive/batch", tenant, staff, "OWNER", null)
                .post(Entity.entity(body, MediaType.APPLICATION_JSON)));
    assertThat(done.getInt("received"), is(2));

    assertThat(receipts(tenant, first), is(staff + "|MANUAL"));
    assertThat(receipts(tenant, second), is(staff + "|MANUAL"));
  }

  @Test
  @DisplayName("A retried receipt is the same receipt: one movement, and it keeps its first actor")
  void aRetriedReceiptKeepsItsFirstActor() {
    String tenant = Ids.newId().toString();
    String first = Ids.newId().toString();
    String second = Ids.newId().toString();
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String key = Ids.newId().toString();

    JsonObject original = Envelopes.created(receive(tenant, first, store, variant, 5, key));
    JsonObject replay = Envelopes.created(receive(tenant, second, store, variant, 5, key));

    assertThat(replay.getString("id"), is(original.getString("id")));
    assertThat(receipts(tenant, variant), is(first + "|MANUAL"));
  }

  @Test
  @DisplayName("A receipt with no signed-in user names nobody, and is still booked")
  void aReceiptWithNoSignedInUserNamesNobody() {
    String tenant = Ids.newId().toString();
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();

    Response r = receive(tenant, null, store, variant, 2, null);
    assertThat(r.readEntity(String.class), r.getStatus(), is(201));

    assertThat(receipts(tenant, variant), is("NULL|MANUAL"));
  }

  @Test
  @DisplayName("A receipt an event caused names nobody: it cites the goods receipt instead")
  void aReceiptAnEventCausedNamesNobody() {
    String tenant = Ids.newId().toString();
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    // The path the GoodsReceived consumer takes for each line of a goods receipt.
    assertThat(
        service.receiveOnce(
            Ids.newId(),
            "goods-received",
            Ids.parse(tenant),
            Ids.parse(store),
            Ids.parse(variant),
            new java.math.BigDecimal("6"),
            null,
            new java.math.BigDecimal("2.50"),
            null,
            "GRN",
            Ids.newId()),
        is(true));

    assertThat(receipts(tenant, variant), is("NULL|GRN"));
  }

  // ── another business ──────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Another business's staff, of every role, never see who received our stock, and move none")
  void anotherBusinessNeverSeesWhoReceivedOurStock() {
    String tenant = Ids.newId().toString();
    String other = Ids.newId().toString();
    String staff = Ids.newId().toString();
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    assertThat(receive(tenant, staff, store, variant, 9, null).getStatus(), is(201));
    int ours = movements(tenant);

    Set<String> roles =
        Set.of("PLATFORM_ADMIN", "OWNER", "MANAGER", "STOREKEEPER", "CASHIER", "CUSTOMER");
    for (String role : roles) {
      for (String path :
          List.of(
              "/admin/inventory/movements?variant=" + variant,
              "/admin/inventory/movements?store=" + store,
              "/admin/inventory/movements")) {
        Response r = as(path, other, Ids.newId().toString(), role, null).get();
        String body = r.readEntity(String.class);
        String label = role + " " + path + ": " + body;
        assertThat(label, body, not(containsString(staff)));
        assertThat(label, body, not(containsString(variant)));
        if (r.getStatus() == 200) {
          assertThat(label, Envelopes.parse(body).getJsonArray("data").size(), is(0));
        } else {
          assertThat(label, r.getStatus(), is(403));
        }
      }
    }

    // Naming our store and our variant, they stock their own: ours is where it was, and it is
    // still our staff member's.
    String theirStaff = Ids.newId().toString();
    assertThat(receive(other, theirStaff, store, variant, 1, null).getStatus(), is(201));
    assertThat(receipts(other, variant), is(theirStaff + "|MANUAL"));
    assertThat(movements(tenant), is(ours));
    assertThat(receipts(tenant, variant), is(staff + "|MANUAL"));
  }
}
