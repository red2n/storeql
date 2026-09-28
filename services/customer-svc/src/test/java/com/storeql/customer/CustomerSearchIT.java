package com.storeql.customer;

import static com.storeql.test.Envelopes.bodyOf;
import static com.storeql.test.Envelopes.created;
import static com.storeql.test.Envelopes.ok;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The staff customer search ({@code GET /customers?q=}): a case-insensitive substring of the name,
 * the email or the phone, within the caller's tenant only, paged by the same cursor as the plain
 * list — with a blank query listing as before, an over-long one refused, wildcards taken literally,
 * erased records never found, and another business's staff of every role, naming our store, finding
 * nothing of ours.
 */
@HelidonTest
class CustomerSearchIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("customer");

  private static final String T = "01a090e2-5ea2-7000-8000-000000000001";
  private static final String OTHER_T = "01a090e2-5ea2-7000-8000-000000000002";
  private static final String OUR_STORE = "01a090e2-5ea2-7000-8000-0000000000a1";
  // The phone tests have businesses of their own, so no other test's numbers share their digits.
  private static final String PHONE_T = "01a090e2-5ea2-7000-8000-000000000003";
  private static final String PHONE_OTHER_T = "01a090e2-5ea2-7000-8000-000000000004";
  private static final String OWNER = "01a090e2-5ea2-7000-8000-0000000000b1";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @Test
  @DisplayName("Part of a name, any case, finds the customer; so does the whole name")
  void findsByPartOfAName() {
    String ada =
        register(T, "ada.lovelace@analytical.example", "Ada", "Lovelace", "+44 7700 900111");
    register(T, "charles.b@analytical.example", "Charles", "Babbage", "+44 7700 900112");

    assertThat(ids(search(T, "OWNER", "love")), is(List.of(ada)));
    assertThat(ids(search(T, "CASHIER", "LOVE")), is(List.of(ada)));
    assertThat("first name", ids(search(T, "MANAGER", "ada")), is(List.of(ada)));
    assertThat("the full name", ids(search(T, "STOREKEEPER", "ada lovel")), is(List.of(ada)));
    assertThat("trimmed", ids(search(T, "OWNER", "   Lovelace  ")), is(List.of(ada)));
    assertThat(ids(search(T, "OWNER", "nobody-by-this-name")), is(empty()));
  }

  @Test
  @DisplayName("Part of an email or a phone number finds the customer")
  void findsByEmailAndByPhone() {
    String grace = register(T, "grace@navy-yard.example", "Grace", "Hopper", "+1 202 555 0147");
    String alan = register(T, "alan@bletchley-park.example", "Alan", "Turing", "+44 1908 640404");

    assertThat(ids(search(T, "OWNER", "GRACE@NAVY-YARD")), is(List.of(grace)));
    assertThat(ids(search(T, "OWNER", "bletchley-park")), is(List.of(alan)));
    assertThat(ids(search(T, "OWNER", "grace@navy-yard.example")), is(List.of(grace)));
    assertThat(ids(search(T, "CASHIER", "555 0147")), is(List.of(grace)));
    assertThat(ids(search(T, "OWNER", "1908 640")), is(List.of(alan)));
  }

  @Test
  @DisplayName(
      "A phone number is found however it is spaced or punctuated, as typed and as stored;"
          + " fewer than four digits, or letters among them, are not a phone number")
  void phoneFormattingIsIgnored() {
    String pip = register(PHONE_T, "pip@formatting.example", "Pip", "Spacing", "07700900111");
    String rex =
        register(PHONE_T, "rex@formatting.example", "Rex", "Brackets", "+44 (0)20 7946 0958");

    for (String q : List.of("07700 900111", "07700900111", "7700 900111", "077-00 9001")) {
      assertThat(q, ids(search(PHONE_T, "CASHIER", q)), is(List.of(pip)));
    }
    for (String q : List.of("020 7946 0958", "02079460958", "7946-0958", "(020) 7946 0958")) {
      assertThat(q, ids(search(PHONE_T, "OWNER", q)), is(List.of(rex)));
    }
    // What was found by raw substring before is still found.
    assertThat(ids(search(PHONE_T, "OWNER", "(0)20 7946")), is(List.of(rex)));
    assertThat("three digits are too few", ids(search(PHONE_T, "OWNER", "0 7 7")), is(empty()));
    assertThat(
        "letters among the digits are not a phone number",
        ids(search(PHONE_T, "OWNER", "Pip 07700 900111")),
        is(empty()));
    assertThat(ids(search(PHONE_T, "OWNER", "07700 900112")), is(empty()));
  }

  @Test
  @DisplayName(
      "Another business's customer with the same digits never matches ours, for any role naming"
          + " our store, however the number is typed")
  void anotherTenantsCustomerWithTheSameDigitsNeverMatches() {
    String ours = register(PHONE_T, "same-digits@ours.example", "Sam", "Ours", "07700 900222");
    String theirs =
        register(PHONE_OTHER_T, "same-digits@theirs.example", "Sam", "Theirs", "07700900222");
    register(PHONE_T, "only-ours@ours.example", "Oona", "Ours", "07700900444");
    String before =
        scalar(PG, "SELECT updated_at::text FROM customer.customers WHERE id = '" + ours + "'");

    List<String> typings = List.of("07700 900222", "07700900222", "7700 900222", "+7700-900-222");
    for (String q : typings) {
      assertThat("ours: " + q, ids(search(PHONE_T, "OWNER", q)), is(List.of(ours)));
    }
    for (String role : List.of("OWNER", "MANAGER", "CASHIER", "STOREKEEPER")) {
      for (String q : typings) {
        assertThat(
            role + " " + q, ids(searchAs(PHONE_OTHER_T, role, q, OUR_STORE)), is(List.of(theirs)));
      }
      for (String q : List.of("07700 900444", "900444", "0770 0900 444")) {
        assertThat(
            role + " a number only we hold: " + q,
            ids(searchAs(PHONE_OTHER_T, role, q, OUR_STORE)),
            is(empty()));
      }
    }
    // A business that has no such customer finds nothing at all by those digits.
    for (String q : typings) {
      assertThat(q, ids(searchAs(OTHER_T, "OWNER", q, OUR_STORE)), is(empty()));
    }
    assertThat(
        scalar(PG, "SELECT updated_at::text FROM customer.customers WHERE id = '" + ours + "'"),
        is(before));
    assertThat(
        scalar(PG, "SELECT phone FROM customer.customers WHERE id = '" + ours + "'"),
        is("07700 900222"));
  }

  @Test
  @DisplayName("A search pages by the same cursor as the list, and every page is a match")
  void pagesWithQ() {
    List<String> pagers = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      pagers.add(register(T, "pager-" + i + "@paging.example", "Pim", "Pager" + i, null));
    }
    register(T, "unrelated@paging-not.example", "Una", "Related", null);

    List<String> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      JsonObject page =
          ok(get(T, "OWNER", "q=pager&limit=2" + (cursor == null ? "" : "&after=" + cursor)));
      List<String> items = ids(page);
      assertThat(items.size() <= 2, is(true));
      seen.addAll(items);
      cursor = page.getString("nextCursor", null);
      pages++;
    } while (cursor != null && pages < 10);

    assertThat(pages, is(3));
    assertThat(seen, hasSize(5));
    assertThat(new HashSet<>(seen), hasSize(5));
    assertThat(seen, containsInAnyOrder(pagers.toArray()));
    // Newest first, exactly as the unfiltered list orders them.
    assertThat(seen, is(pagers.reversed()));
  }

  @Test
  @DisplayName("An empty or blank q lists exactly what no q lists")
  void anEmptyQBehavesAsToday() {
    register(T, "blank-q@blank.example", "Bea", "Blank", null);
    List<String> plain = ids(ok(get(T, "OWNER", "limit=100")));
    assertThat(plain, is(not(empty())));
    assertThat(ids(ok(get(T, "OWNER", "q=&limit=100"))), is(plain));
    Response spaces =
        target
            .path("/customers")
            .queryParam("q", "   ")
            .queryParam("limit", 100)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .get();
    assertThat(ids(ok(spaces)), is(plain));
  }

  @Test
  @DisplayName(
      "q beyond a hundred characters is 400 VALIDATION_FAILED; a hundred after trim is not")
  void anOverLongQIsRefused() {
    Response tooLong = search(T, "OWNER", "x".repeat(101));
    String body = bodyOf(tooLong, 400);
    assertThat(body, containsString("VALIDATION_FAILED"));
    assertThat(body, containsString("q: at most 100 characters"));
    assertThat(ids(ok(search(T, "OWNER", "  " + "x".repeat(100) + "  "))), is(empty()));
  }

  @Test
  @DisplayName("A % or _ in q matches itself, never everything")
  void wildcardsAreLiteral() {
    String odd = register(T, "under_score@wild.example", "Wilma", "Wildcard", null);
    register(T, "underXscore@wild.example", "Walt", "Wildcard", null);

    assertThat(ids(search(T, "OWNER", "%")), is(empty()));
    assertThat(ids(search(T, "OWNER", "under_score")), is(List.of(odd)));
    assertThat(ids(search(T, "OWNER", "\\")), is(empty()));
  }

  @Test
  @DisplayName("An erased customer is never found, by the old name or by the placeholder")
  void anErasedCustomerIsNeverFound() {
    String erin = register(T, "erin@erased.example", "Erin", "Wexford", "+44 7700 900999");
    Response erased =
        target
            .path("/customers/" + erin)
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "OWNER")
            .header("X-User-Id", OWNER)
            .delete();
    assertThat(erased.getStatus(), is(204));
    assertThat(ids(search(T, "OWNER", "Wexford")), is(empty()));
    assertThat(ids(search(T, "OWNER", "Deleted User")), is(empty()));
    assertThat(ids(search(T, "OWNER", "@deleted")), is(empty()));
  }

  @Test
  @DisplayName(
      "Another business's owner, manager, cashier and storekeeper, naming our store, find nothing"
          + " of ours — not even by an exact email — and change nothing")
  void anotherTenantsStaffNeverMatchOurs() {
    String ours = register(T, "twin@isolation.example", "Iris", "Isolde", "+44 7700 900333");
    String theirs =
        register(OTHER_T, "twin@isolation.example", "Iris", "Isolde", "+44 7700 900333");
    register(OTHER_T, "their-own@isolation.example", "Theo", "Theirs", null);
    register(T, "only-ours@isolation.example", "Olga", "Ours", null);
    String before =
        scalar(PG, "SELECT updated_at::text FROM customer.customers WHERE id = '" + ours + "'");

    for (String role : List.of("OWNER", "MANAGER", "CASHIER", "STOREKEEPER")) {
      for (String q : List.of("twin@isolation.example", "Isolde", "900333", "iris isolde")) {
        List<String> found = ids(searchAs(OTHER_T, role, q, OUR_STORE));
        assertThat(role + " " + q, found, is(List.of(theirs)));
      }
      assertThat(
          role + " with an email only we hold",
          ids(searchAs(OTHER_T, role, "only-ours@isolation.example", OUR_STORE)),
          is(empty()));
      List<String> everything = ids(ok(getAs(OTHER_T, role, "limit=100", OUR_STORE)));
      assertThat(role + " unfiltered", everything, everyItem(is(not(ours))));
      assertThat(
          role + " reading ours by id",
          target
              .path("/customers/" + ours)
              .request(MediaType.APPLICATION_JSON)
              .header("X-Tenant-Id", OTHER_T)
              .header("X-Roles", role)
              .header("X-Store-Ids", OUR_STORE)
              .get()
              .getStatus(),
          is(404));
    }

    // And from our side the twin is ours alone.
    assertThat(ids(search(T, "OWNER", "twin@isolation.example")), is(List.of(ours)));
    assertThat(
        scalar(PG, "SELECT updated_at::text FROM customer.customers WHERE id = '" + ours + "'"),
        is(before));
    assertThat(
        scalar(PG, "SELECT tenant_id::text FROM customer.customers WHERE id = '" + ours + "'"),
        is(T));
  }

  @Test
  @DisplayName("A shopper cannot search the customer base, even their own shop's")
  void aShopperCannotSearch() {
    register(T, "shopper-probe@probe.example", "Pat", "Probe", null);
    Response r =
        WebTargets.at(target, "/customers?q=probe")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CUSTOMER")
            .header("X-User-Id", Ids.newId().toString())
            .get();
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(403));
    assertThat(body, not(containsString("probe.example")));
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private String register(String tenant, String email, String first, String last, String phone) {
    String json =
        "{\"email\":\""
            + email
            + "\",\"firstName\":\""
            + first
            + "\",\"lastName\":\""
            + last
            + "\""
            + (phone == null ? "" : ",\"phone\":\"" + phone + "\"")
            + "}";
    Response r =
        target
            .path("/customers")
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", "OWNER")
            .header("X-User-Id", OWNER)
            .post(Entity.entity(json, MediaType.APPLICATION_JSON));
    return created(r).getString("id");
  }

  /** A search, with {@code q} bound as a query parameter so spaces and symbols arrive as typed. */
  private Response search(String tenant, String role, String q) {
    return searchAs(tenant, role, q, null);
  }

  private Response searchAs(String tenant, String role, String q, String storeIds) {
    var b =
        target
            .path("/customers")
            .queryParam("q", q)
            .queryParam("limit", 100)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", role);
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    return b.get();
  }

  private Response get(String tenant, String role, String query) {
    return getAs(tenant, role, query, null);
  }

  private Response getAs(String tenant, String role, String query, String storeIds) {
    var b =
        WebTargets.at(target, "/customers?" + query)
            .request(MediaType.APPLICATION_JSON)
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", role);
    if (storeIds != null) b = b.header("X-Store-Ids", storeIds);
    return b.get();
  }

  private static List<String> ids(Response r) {
    return ids(ok(r));
  }

  private static List<String> ids(JsonObject page) {
    return page.getJsonArray("items").getValuesAs(JsonObject.class).stream()
        .map(o -> o.getString("id"))
        .toList();
  }
}
