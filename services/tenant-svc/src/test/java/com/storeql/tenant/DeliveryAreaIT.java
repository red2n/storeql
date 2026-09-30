package com.storeql.tenant;

import static com.storeql.tenant.AdminRig.call;
import static com.storeql.tenant.AdminRig.q;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.tenant.AdminRig.Answer;
import com.storeql.tenant.AdminRig.Biz;
import com.storeql.tenant.AdminRig.Who;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Delivery areas (flow catalogue onb-delivery-areas-and-fulfilment-windows gap 1): the postal codes
 * a store delivers to, and the lookup from a code to the store that fulfils it. Create, list and
 * delete; the lookup and its fallback; every refusal; the role gates; store-held managers; and
 * business against business. No country's postal-code format is assumed: codes here are digits,
 * letters with a space, and a script of another kind.
 */
@HelidonTest
class DeliveryAreaIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("tenant");

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private static String areas(String store) {
    return "/admin/stores/" + store + "/delivery-areas";
  }

  private Answer add(Who who, String store, String pincode) {
    return call(target, "POST", areas(store), "{\"pincode\":\"" + pincode + "\"}", who);
  }

  private Answer add(Who who, String store, String pincode, int priority) {
    return call(
        target,
        "POST",
        areas(store),
        "{\"pincode\":\"" + pincode + "\",\"priority\":" + priority + "}",
        who);
  }

  private Answer resolve(Biz b, String pincode) {
    return call(
        target,
        "GET",
        "/fulfilment/resolve" + (pincode == null ? "" : "?pincode=" + q(pincode)),
        null,
        new Who(b.tenant(), null, "CUSTOMER", null, null));
  }

  private List<String> pincodes(Who who, String store) {
    Answer a = call(target, "GET", areas(store), null, who);
    assertThat(a.text(), a.status(), is(200));
    List<String> out = new ArrayList<>();
    for (var v : a.list()) out.add(v.asJsonObject().getString("pincode"));
    return out;
  }

  @Test
  @DisplayName(
      "Create, list, resolve and delete: a code maps to a store, and the lowest priority wins")
  void createListResolveDelete() {
    Biz b = AdminRig.biz(target, "Delivery Basics", "IN", "INR");
    String far = AdminRig.addStore(target, b, "Far", "IN");
    assertThat(pincodes(b.owner(), b.store()), hasSize(0));

    Answer made = add(b.owner(), b.store(), "560001");
    assertThat(made.text(), made.status(), is(201));
    JsonObject area = made.data();
    assertThat(area.getString("pincode"), is("560001"));
    assertThat(area.getString("storeId"), is(b.store()));
    assertThat("priority defaults to 100", area.getInt("priority"), is(100));

    Answer second = add(b.manager(), far, "560001", 10);
    assertThat(second.text(), second.status(), is(201));
    assertThat(second.data().getInt("priority"), is(10));
    assertThat(pincodes(b.owner(), b.store()), is(List.of("560001")));

    // The lookup: the lower number wins, whoever added it.
    Answer hit = resolve(b, "560001");
    assertThat(hit.text(), hit.status(), is(200));
    assertThat(hit.data().getString("storeId"), is(far));
    assertThat(hit.data().getInt("priority"), is(10));

    // Delete the winner: the other store now serves it. Deleting again finds nothing.
    Answer gone =
        call(target, "DELETE", areas(far) + "/" + second.data().getString("id"), null, b.owner());
    assertThat(gone.text(), gone.status(), is(204));
    assertThat(resolve(b, "560001").data().getString("storeId"), is(b.store()));
    Answer again =
        call(target, "DELETE", areas(far) + "/" + second.data().getString("id"), null, b.owner());
    assertThat(again.text(), again.status(), is(404));
    assertThat(again.code(), is("DELIVERY_AREA_NOT_FOUND"));
    // An area is deleted through its own store's path only.
    Answer wrongPath =
        call(target, "DELETE", areas(far) + "/" + area.getString("id"), null, b.owner());
    assertThat(wrongPath.text(), wrongPath.status(), is(404));
    assertThat(pincodes(b.owner(), b.store()), is(List.of("560001")));
  }

  @Test
  @DisplayName(
      "Two stores at the same priority: the one that took the code first serves it, every time")
  void equalPrioritiesResolveTheSameWay() {
    Biz b = AdminRig.biz(target, "Delivery Ties", "IN", "INR");
    String other = AdminRig.addStore(target, b, "Other", "IN");
    assertThat(add(b.owner(), other, "TIE-1", 50).status(), is(201));
    assertThat(add(b.owner(), b.store(), "TIE-1", 50).status(), is(201));
    for (int i = 0; i < 5; i++) {
      assertThat(resolve(b, "TIE-1").data().getString("storeId"), is(other));
    }
  }

  @Test
  @DisplayName(
      "The same code twice for a store is refused whatever its case or spacing, and another store may still have it")
  void duplicatesAreRefused() {
    Biz b = AdminRig.biz(target, "Delivery Duplicates", "GB", "GBP");
    String other = AdminRig.addStore(target, b, "Other", "GB");
    assertThat(add(b.owner(), b.store(), "SW1A 1AA").status(), is(201));
    for (String variant : new String[] {"SW1A 1AA", "sw1a 1aa", "  SW1A   1AA ", "Sw1a 1aA"}) {
      Answer dup = add(b.owner(), b.store(), variant);
      assertThat(variant + ": " + dup.text(), dup.status(), is(409));
      assertThat(dup.code(), is("DELIVERY_AREA_EXISTS"));
    }
    // A code without the space is a different code: no format is assumed, so none is guessed.
    assertThat(add(b.owner(), b.store(), "SW1A1AA").status(), is(201));
    assertThat(
        pincodes(b.owner(), b.store()),
        org.hamcrest.Matchers.containsInAnyOrder("SW1A 1AA", "SW1A1AA"));
    assertThat(add(b.owner(), other, "SW1A 1AA").status(), is(201));
  }

  @Test
  @DisplayName(
      "Any postal-code shape resolves as stored: digits, letters, a space, another script; case and spacing forgiven")
  void anyShapeResolves() {
    Biz b = AdminRig.biz(target, "Delivery Shapes", "JP", "JPY");
    String other = AdminRig.addStore(target, b, "Other", "JP");
    assertThat(add(b.owner(), b.store(), "100-0001").status(), is(201));
    assertThat(add(b.owner(), b.store(), "K1A 0B1").status(), is(201));
    assertThat(add(b.owner(), other, "〒160-0022").status(), is(201));
    assertThat(add(b.owner(), other, "EC1A1BB").status(), is(201));

    assertThat(resolve(b, "100-0001").data().getString("storeId"), is(b.store()));
    assertThat(resolve(b, " k1a   0b1 ").data().getString("storeId"), is(b.store()));
    assertThat(resolve(b, "〒160-0022").data().getString("storeId"), is(other));
    assertThat(resolve(b, "ec1a1bb").data().getString("storeId"), is(other));
    assertThat(resolve(b, "ec1a1bb").data().getString("pincode"), is("EC1A1BB"));
    // Areas exist, so a code nobody covers is a 404, and a space is never invented or removed.
    Answer none = resolve(b, "K1A0B1");
    assertThat(none.text(), none.status(), is(404));
    assertThat(none.code(), is("FULFILMENT_AREA_NOT_COVERED"));
    assertThat(resolve(b, "EC1A 1BB").status(), is(404));
  }

  @Test
  @DisplayName(
      "With no areas the default store serves; once any exists an uncovered code is a 404; removing the last returns to the default")
  void fallbackToTheDefaultStore() {
    Biz b = AdminRig.biz(target, "Delivery Fallback", "IN", "INR");
    // The default store is the first the owner onboarded: the store the fixture added is not marked
    // default, so the tenant falls back to its first store.
    Answer none = resolve(b, "anything");
    assertThat(none.text(), none.status(), is(200));
    assertThat(none.data().getString("storeId"), is(b.store()));
    assertThat(none.data().getInt("priority"), is(0));

    Answer made = add(b.owner(), b.store(), "411001");
    assertThat(made.status(), is(201));
    assertThat(resolve(b, "411001").data().getString("storeId"), is(b.store()));
    Answer uncovered = resolve(b, "anything");
    assertThat(uncovered.text(), uncovered.status(), is(404));
    assertThat(uncovered.code(), is("FULFILMENT_AREA_NOT_COVERED"));

    assertThat(
        call(
                target,
                "DELETE",
                areas(b.store()) + "/" + made.data().getString("id"),
                null,
                b.owner())
            .status(),
        is(204));
    assertThat(resolve(b, "anything").status(), is(200));
    assertThat(resolve(b, "anything").data().getString("storeId"), is(b.store()));

    for (String bad : new String[] {null, "", "   "}) {
      Answer a = resolve(b, bad);
      assertThat("[" + bad + "] " + a.text(), a.status(), is(400));
      assertThat(a.code(), is("FULFILMENT_PINCODE_REQUIRED"));
    }
  }

  @Test
  @DisplayName("Every bad request is refused by name and creates nothing")
  void validationRefusals() {
    Biz b = AdminRig.biz(target, "Delivery Validation", "IN", "INR");
    for (String body :
        new String[] {
          "{}",
          "{\"pincode\":\"\"}",
          "{\"pincode\":\"   \"}",
          "{\"pincode\":\"" + "9".repeat(33) + "\"}",
          "{\"pincode\":null}",
          "{\"pincode\":\"12345\",\"priority\":\"high\"}",
          "not json"
        }) {
      Answer a = call(target, "POST", areas(b.store()), body, b.owner());
      assertThat(body + " -> " + a.text(), a.status(), is(400));
    }
    // The longest allowed is allowed.
    assertThat(add(b.owner(), b.store(), "9".repeat(32)).status(), is(201));
    // A store id that is not one, is another version, or is nobody's.
    assertThat(add(b.owner(), "not-a-uuid", "12345").status(), is(400));
    assertThat(add(b.owner(), "3f2b8c1e-9d4a-4b7e-8c21-5a6d7e8f9012", "12345").status(), is(400));
    Answer nobody = add(b.owner(), Ids.newId().toString(), "12345");
    assertThat(nobody.text(), nobody.status(), is(404));
    assertThat(nobody.code(), is("STORE_NOT_FOUND"));
    assertThat(
        call(target, "GET", areas(Ids.newId().toString()), null, b.owner()).status(), is(404));
    assertThat(
        call(target, "DELETE", areas(b.store()) + "/not-a-uuid", null, b.owner()).status(),
        is(400));
    assertThat(
        call(target, "DELETE", areas(b.store()) + "/" + Ids.newId(), null, b.owner()).status(),
        is(404));
    assertThat(pincodes(b.owner(), b.store()), is(List.of("9".repeat(32))));
  }

  @Test
  @DisplayName(
      "Owners and managers maintain delivery areas; a storekeeper, a cashier and a shopper cannot")
  void roleGates() {
    Biz b = AdminRig.biz(target, "Delivery Roles", "IN", "INR");
    Answer seeded = add(b.owner(), b.store(), "GATE-1");
    assertThat(seeded.status(), is(201));
    String id = seeded.data().getString("id");
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      Who who = b.as(role);
      Answer created = add(who, b.store(), "GATE-2");
      assertThat(role + ": " + created.text(), created.status(), is(403));
      Answer deleted = call(target, "DELETE", areas(b.store()) + "/" + id, null, who);
      assertThat(role + ": " + deleted.text(), deleted.status(), is(403));
    }
    assertThat(pincodes(b.owner(), b.store()), is(List.of("GATE-1")));
    assertThat(add(b.manager(), b.store(), "GATE-3").status(), is(201));
    assertThat(
        call(target, "DELETE", areas(b.store()) + "/" + id, null, b.manager()).status(), is(204));
    assertThat(pincodes(b.manager(), b.store()), is(List.of("GATE-3")));
  }

  @Test
  @DisplayName(
      "A manager held to one store maintains that store's areas and is refused at another's, changing nothing there")
  void storeHeldManager() {
    Biz b = AdminRig.biz(target, "Delivery Held", "IN", "INR");
    String other = AdminRig.addStore(target, b, "Other", "IN");
    Who held = b.manager(b.store());
    Answer mine = add(held, b.store(), "HELD-1");
    assertThat(mine.text(), mine.status(), is(201));
    Answer theirs = add(held, other, "HELD-2");
    assertThat(theirs.text(), theirs.status(), is(403));
    assertThat(theirs.code(), is("STORE_ACCESS_DENIED"));
    assertThat(pincodes(b.owner(), other), hasSize(0));

    Answer seeded = add(b.owner(), other, "HELD-3");
    assertThat(seeded.status(), is(201));
    Answer refusedDelete =
        call(target, "DELETE", areas(other) + "/" + seeded.data().getString("id"), null, held);
    assertThat(refusedDelete.text(), refusedDelete.status(), is(403));
    assertThat(refusedDelete.code(), is("STORE_ACCESS_DENIED"));
    assertThat(pincodes(b.owner(), other), is(List.of("HELD-3")));
    // Their own: delete works.
    assertThat(
        call(target, "DELETE", areas(b.store()) + "/" + mine.data().getString("id"), null, held)
            .status(),
        is(204));
    // Held to both, either.
    assertThat(add(b.manager(b.store(), other), other, "HELD-4").status(), is(201));
  }

  @Test
  @DisplayName(
      "Another business's owner or manager, naming our real store and area ids, reads and changes nothing of ours")
  void otherBusinessesAreShutOut() {
    Biz ours = AdminRig.biz(target, "Delivery Ours", "IN", "INR");
    Biz rival = AdminRig.biz(target, "Delivery Rival", "IN", "INR");
    Answer ourArea = add(ours.owner(), ours.store(), "560001");
    assertThat(ourArea.status(), is(201));
    String ourAreaId = ourArea.data().getString("id");

    for (Who caller :
        new Who[] {
          rival.owner(),
          rival.manager(),
          rival.manager(ours.store()),
          rival.manager(rival.store(), ours.store())
        }) {
      Answer post = add(caller, ours.store(), "666666");
      assertThat(post.text(), post.status(), is(404));
      assertThat(post.code(), is("STORE_NOT_FOUND"));
      Answer list = call(target, "GET", areas(ours.store()), null, caller);
      assertThat(list.text(), list.status(), is(404));
      Answer viaOurStore =
          call(target, "DELETE", areas(ours.store()) + "/" + ourAreaId, null, caller);
      assertThat(viaOurStore.text(), viaOurStore.status(), is(404));
      Answer viaTheirStore =
          call(target, "DELETE", areas(rival.store()) + "/" + ourAreaId, null, rival.owner());
      assertThat(viaTheirStore.text(), viaTheirStore.status(), is(404));
    }
    assertThat(pincodes(ours.owner(), ours.store()), is(List.of("560001")));

    // The rival's own lookup of our code never lands on our store: it has no areas, so its own
    // store answers; once it maps the same code, that is its own.
    assertThat(resolve(rival, "560001").data().getString("storeId"), is(rival.store()));
    assertThat(add(rival.owner(), rival.store(), "560001").status(), is(201));
    assertThat(resolve(rival, "560001").data().getString("storeId"), is(rival.store()));
    assertThat(resolve(ours, "560001").data().getString("storeId"), is(ours.store()));
    assertThat(pincodes(ours.owner(), ours.store()), is(List.of("560001")));
  }
}
