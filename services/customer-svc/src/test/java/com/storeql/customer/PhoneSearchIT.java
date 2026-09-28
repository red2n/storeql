package com.storeql.customer;

import static com.storeql.test.Envelopes.created;
import static com.storeql.test.Envelopes.exec;
import static com.storeql.test.Envelopes.ok;
import static com.storeql.test.Envelopes.scalar;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import com.storeql.customer.repo.PrivacyRepository;
import com.storeql.customer.service.PhoneBackfillService;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phones to E.164, read in each business's own country — never a country, prefix or zone named
 * here: a Polish business, a British one and an Indian one, each finding only its own customer, by
 * however the number is typed. Every number below is one libphonenumber's own isValidNumber accepts
 * under that business's country (checked directly, not assumed) — never one merely
 * isPossibleNumber, which would risk the exact cross-country mistake this feature exists to avoid.
 */
@HelidonTest
class PhoneSearchIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("customer");

  private static final String PL = Ids.newId().toString();
  private static final String GB = Ids.newId().toString();
  private static final String IN = Ids.newId().toString();
  private static final String OWNER = Ids.newId().toString();

  /** National form, then the E.164 libphonenumber itself derives it to. */
  private static final String PL_NATIONAL = "512 345 678";

  private static final String PL_E164 = "+48512345678";

  // Not 07700 900xxx: Ofcom's reserved "drama" mobile block, which is only isPossibleNumber, never
  // isValidNumber. The k6 suite's own GB example, so both agree.
  private static final String GB_NATIONAL = "07400 123456";

  private static final String GB_E164 = "+447400123456";
  private static final String IN_NATIONAL = "98765 43210";
  private static final String IN_E164 = "+919876543210";

  static {
    // storeql.consul.enabled / storeql.kafka.enabled are already off, via PG.wire() above.
    TenantSvcStub.start().with(PL, "PLN", "PL").with(GB, "GBP", "GB").with(IN, "INR", "IN");
  }

  @Inject WebTarget target;
  @Inject PrivacyRepository privacy;
  @Inject PhoneBackfillService backfill;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @Test
  @DisplayName(
      "Each business finds its own customer by the national form, the international form, and"
          + " with spaces — never another business's, even typing its exact E.164")
  void eachBusinessFindsOnlyItsOwnCustomer() {
    String pl = register(PL, "ada@example.pl", "Ada", "Kowalska", PL_NATIONAL);
    String gb = register(GB, "bob@example.co.uk", "Bob", "Smith", GB_NATIONAL);
    String in = register(IN, "chandra@example.in", "Chandra", "Rao", IN_NATIONAL);

    // National, international and spaced-differently forms all resolve to the one customer.
    assertFoundByQ(PL, "512 345 678", pl);
    assertFoundByQ(PL, "+48512345678", pl);
    assertFoundByQ(PL, "+48 512 345 678", pl);
    assertFoundByQ(PL, "512345678", pl);

    assertFoundByQ(GB, "07400 123456", gb);
    assertFoundByQ(GB, "+447400123456", gb);
    assertFoundByQ(GB, "+44 7400 123456", gb);
    assertFoundByQ(GB, "07400123456", gb);

    assertFoundByQ(IN, "98765 43210", in);
    assertFoundByQ(IN, "+919876543210", in);
    assertFoundByQ(IN, "+91 98765 43210", in);
    assertFoundByQ(IN, "9876543210", in);

    // Never another business's — not even typing its exact E.164 form.
    assertThat(ids(search(GB, PL_E164)), is(empty()));
    assertThat(ids(search(GB, IN_E164)), is(empty()));
    assertThat(ids(search(IN, PL_E164)), is(empty()));
    assertThat(ids(search(IN, GB_E164)), is(empty()));
    assertThat(ids(search(PL, GB_E164)), is(empty()));
    assertThat(ids(search(PL, IN_E164)), is(empty()));

    // The E.164 form was actually stored, tenant-scoped.
    assertThat(
        scalar(PG, "SELECT phone_e164 FROM customer.customers WHERE id = '" + pl + "'"),
        is(PL_E164));
    assertThat(
        scalar(PG, "SELECT phone_e164 FROM customer.customers WHERE id = '" + gb + "'"),
        is(GB_E164));
    assertThat(
        scalar(PG, "SELECT phone_e164 FROM customer.customers WHERE id = '" + in + "'"),
        is(IN_E164));
  }

  @Test
  @DisplayName("Lookup by phone resolves the same way, and never crosses a tenant")
  void lookupByPhoneTheSameWay() {
    String pl = register(PL, "ewa@example.pl", "Ewa", "Nowak", "600 111 222");
    for (String q : List.of("600 111 222", "+48600111222", "+48 600 111 222", "600111222")) {
      assertThat(q, data(lookup(PL, q)).getString("id"), is(pl));
    }
    for (String tenant : List.of(GB, IN)) {
      assertThat(lookup(tenant, "+48600111222").getStatus(), is(404));
    }
  }

  @Test
  @DisplayName("The start-up backfill fills phone_e164 for a row written before this feature")
  void backfillFillsPreExistingRows() {
    String id = Ids.newId().toString();
    // A row exactly as it would have looked before phone_e164 existed: written directly, as a
    // migration-era customer would have been, with the column left untouched.
    exec(
        PG,
        "INSERT INTO customer.customers (id, tenant_id, email, phone, first_name, last_name,"
            + " status, created_at, updated_at)"
            + " VALUES ('"
            + id
            + "', '"
            + PL
            + "', 'legacy@example.pl', '512 345 679', 'Legacy', 'Row', 'ACTIVE', now(), now())");
    assertThat(
        scalar(PG, "SELECT phone_e164 FROM customer.customers WHERE id = '" + id + "'"),
        is((String) null));

    int fixedFirst = backfill.run();
    assertThat("at least this row", fixedFirst >= 1, is(true));
    assertThat(
        scalar(PG, "SELECT phone_e164 FROM customer.customers WHERE id = '" + id + "'"),
        is("+48512345679"));
    assertThat(
        "checked, so a genuine miss would not be retried forever",
        scalar(
            PG,
            "SELECT phone_e164_checked_at IS NOT NULL FROM customer.customers WHERE id = '"
                + id
                + "'"),
        is("true"));

    // Idempotent: running it again touches this row no further (it is no longer a candidate).
    backfill.run();
    assertThat(
        scalar(PG, "SELECT phone_e164 FROM customer.customers WHERE id = '" + id + "'"),
        is("+48512345679"));
  }

  @Test
  @DisplayName("A reachability read hands on the E.164 form alongside the phone as typed")
  void reachabilityHandsOnE164() {
    String phone = "790 123 456";
    String id = register(PL, "reachable@example.pl", "Rea", "Chable", phone);
    var reachable = privacy.reachable(Ids.parse(PL), List.of(Ids.parse(id)), 10);
    assertThat(reachable, hasSize(1));
    assertThat(reachable.get(0).phone(), is(phone));
    assertThat(reachable.get(0).phoneE164(), is("+48790123456"));
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private void assertFoundByQ(String tenant, String q, String expectedId) {
    assertThat(q, ids(search(tenant, q)), is(List.of(expectedId)));
  }

  private String register(String tenant, String email, String first, String last, String phone) {
    String json =
        "{\"email\":\""
            + email
            + "\",\"firstName\":\""
            + first
            + "\",\"lastName\":\""
            + last
            + "\",\"phone\":\""
            + phone
            + "\"}";
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

  private Response search(String tenant, String q) {
    return WebTargets.at(target, "/customers")
        .queryParam("q", q)
        .queryParam("limit", 100)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get();
  }

  private Response lookup(String tenant, String phone) {
    return WebTargets.at(target, "/customers/lookup")
        .queryParam("phone", phone)
        .request(MediaType.APPLICATION_JSON)
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .get();
  }

  private static JsonObject data(Response r) {
    return ok(r);
  }

  private static List<String> ids(Response r) {
    return ok(r).getJsonArray("items").getValuesAs(JsonObject.class).stream()
        .map(o -> o.getString("id"))
        .toList();
  }
}
