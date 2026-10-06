package com.storeql.cart.messaging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.cart.domain.Domain.Cart;
import com.storeql.cart.repo.CartRepository;
import com.storeql.ids.Ids;
import com.storeql.service.TenantDataRepository;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A redelivered {@code OrderPlaced} does nothing (golden rule 7), over real Postgres. The first
 * delivery closes the shopper's ACTIVE cart; when the shopper then opens a new one, the same event
 * delivered again leaves it ACTIVE, while a different event still closes it. The event is recorded
 * on the transaction that closes the cart, so a close that fails leaves no record and the
 * redelivery is not swallowed. Another business's event closes nothing of ours.
 */
@HelidonTest
class OrderPlacedRedeliveryIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("cart");

  /** The key the handler records its events under, in processed_events. */
  private static final String CONSUMER = "cart-svc/order-placed";

  @Inject OrderPlacedHandler handler;
  @Inject CartRepository carts;
  @Inject TenantDataRepository data;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** A new ACTIVE cart for the shopper at the store. */
  private UUID openCart(UUID tenant, UUID shopper, UUID store) {
    return carts
        .insert(
            new Cart(
                Ids.newId(),
                tenant,
                shopper,
                null,
                store,
                Cart.STATUS_ACTIVE,
                Instant.now(),
                Instant.now()))
        .id();
  }

  private static String statusOf(UUID cart) {
    return Envelopes.scalar(PG, "SELECT status FROM cart.carts WHERE id = '" + cart + "'");
  }

  private static long recorded(UUID eventId) {
    return Long.parseLong(
        Envelopes.scalar(
            PG,
            "SELECT count(*) FROM cart.processed_events WHERE event_id = '"
                + eventId
                + "' AND consumer = '"
                + CONSUMER
                + "'"));
  }

  /** An online OrderPlaced: the shopper is named by the login that holds the cart. */
  private static String online(UUID tenant, UUID login, UUID store, UUID order, UUID eventId) {
    return "{\"eventType\":\"OrderPlaced\",\"tenantId\":\""
        + tenant
        + "\",\"orderId\":\""
        + order
        + "\",\"channel\":\"ONLINE\",\"storeId\":\""
        + store
        + "\",\"customerId\":null,\"loginId\":\""
        + login
        + "\""
        + eventIdMember(eventId)
        + "}";
  }

  /** A till sale naming the customer; it carries no login. */
  private static String till(UUID tenant, UUID customer, UUID store, UUID order, UUID eventId) {
    return "{\"eventType\":\"OrderPlaced\",\"tenantId\":\""
        + tenant
        + "\",\"orderId\":\""
        + order
        + "\",\"channel\":\"POS\",\"storeId\":\""
        + store
        + "\",\"customerId\":\""
        + customer
        + "\",\"loginId\":null"
        + eventIdMember(eventId)
        + "}";
  }

  /** The member goes last, as order-svc writes it; null leaves it out. */
  private static String eventIdMember(UUID eventId) {
    return eventId == null ? "" : ",\"eventId\":\"" + eventId + "\"";
  }

  // ── redelivery ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A redelivered online OrderPlaced leaves the cart opened since ACTIVE")
  void aRedeliveredOnlineOrderDoesNotCloseTheNewCart() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    UUID store = Ids.newId();
    UUID first = openCart(tenant, shopper, store);
    UUID event = Ids.newId();
    String placed = online(tenant, shopper, Ids.newId(), Ids.newId(), event);

    handler.handle(placed);
    assertThat("the first delivery closes the cart", statusOf(first), is("CHECKED_OUT"));
    assertThat("and records the event once", recorded(event), is(1L));

    UUID second = openCart(tenant, shopper, store);
    handler.handle(placed);
    assertThat("the same event again closes nothing", statusOf(second), is("ACTIVE"));
    assertThat("and is recorded once still", recorded(event), is(1L));

    handler.handle(online(tenant, shopper, Ids.newId(), Ids.newId(), Ids.newId()));
    assertThat("a different event still closes the cart", statusOf(second), is("CHECKED_OUT"));
  }

  @Test
  @DisplayName("A redelivered till sale leaves the customer's new cart at that store ACTIVE")
  void aRedeliveredTillSaleDoesNotCloseTheNewCart() {
    UUID tenant = Ids.newId();
    UUID customer = Ids.newId();
    UUID store = Ids.newId();
    UUID first = openCart(tenant, customer, store);
    UUID event = Ids.newId();
    String sold = till(tenant, customer, store, Ids.newId(), event);

    handler.handle(sold);
    assertThat(statusOf(first), is("CHECKED_OUT"));

    UUID second = openCart(tenant, customer, store);
    handler.handle(sold);
    assertThat("the same event again closes nothing", statusOf(second), is("ACTIVE"));

    handler.handle(till(tenant, customer, store, Ids.newId(), Ids.newId()));
    assertThat("a different sale still closes it", statusOf(second), is("CHECKED_OUT"));
  }

  @Test
  @DisplayName("An event that found no cart to close is recorded, so it cannot close a later one")
  void anEventWithNoCartToCloseIsStillRecorded() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    UUID event = Ids.newId();
    String placed = online(tenant, shopper, Ids.newId(), Ids.newId(), event);

    handler.handle(placed); // the shopper has no cart yet
    UUID later = openCart(tenant, shopper, Ids.newId());
    handler.handle(placed);

    assertThat(recorded(event), is(1L));
    assertThat("a cart opened after the order is not that order's", statusOf(later), is("ACTIVE"));
  }

  @Test
  @DisplayName("Each part of a split order closes the cart, and each part's redelivery does not")
  void eachPartOfASplitOrderIsItsOwnEvent() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    UUID store = Ids.newId();
    UUID partA = Ids.newId();
    UUID partB = Ids.newId();
    String a = online(tenant, shopper, Ids.newId(), Ids.newId(), partA);
    String b = online(tenant, shopper, Ids.newId(), Ids.newId(), partB);
    UUID cart = openCart(tenant, shopper, store);

    handler.handle(a);
    assertThat(statusOf(cart), is("CHECKED_OUT"));
    UUID next = openCart(tenant, shopper, store);
    handler.handle(a); // part A again
    assertThat(statusOf(next), is("ACTIVE"));
    handler.handle(b); // part B is a different event
    assertThat(statusOf(next), is("CHECKED_OUT"));
  }

  // ── transaction ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("The record and the close share one transaction: a close that fails is retried")
  void aFailedCloseLeavesNoRecordSoTheRedeliveryIsNotSwallowed() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    UUID cart = openCart(tenant, shopper, Ids.newId());
    UUID event = Ids.newId();
    String placed = online(tenant, shopper, Ids.newId(), Ids.newId(), event);
    String fn = "cart.fail_checkout_" + tenant.toString().replace('-', '_');
    Envelopes.exec(
        PG,
        "CREATE FUNCTION "
            + fn
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'closed by test';"
            + " END $$");
    Envelopes.exec(
        PG,
        "CREATE TRIGGER fail_checkout BEFORE UPDATE ON cart.carts FOR EACH ROW WHEN"
            + " (NEW.tenant_id = '"
            + tenant
            + "') EXECUTE FUNCTION "
            + fn
            + "()");
    try {
      assertThrows(RuntimeException.class, () -> handler.handle(placed));
      assertThat("nothing was recorded", recorded(event), is(0L));
      assertThat("and the cart is as it was", statusOf(cart), is("ACTIVE"));
    } finally {
      Envelopes.exec(PG, "DROP TRIGGER fail_checkout ON cart.carts");
      Envelopes.exec(PG, "DROP FUNCTION " + fn + "()");
    }

    handler.handle(placed); // the broker redelivers it
    assertThat("the redelivery does the work", statusOf(cart), is("CHECKED_OUT"));
    assertThat(recorded(event), is(1L));
  }

  // ── payloads without an eventId ─────────────────────────────────────────────

  @Test
  @DisplayName("An event with no eventId is told apart by its order, so it is not closed twice")
  void anEventWithoutAnEventIdIsKeyedByItsOrder() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    UUID store = Ids.newId();
    UUID order = Ids.newId();
    String placed = online(tenant, shopper, store, order, null);
    UUID first = openCart(tenant, shopper, store);

    handler.handle(placed);
    assertThat(statusOf(first), is("CHECKED_OUT"));
    UUID second = openCart(tenant, shopper, store);
    handler.handle(placed);
    assertThat("the same order again closes nothing", statusOf(second), is("ACTIVE"));
    handler.handle(online(tenant, shopper, store, Ids.newId(), null));
    assertThat("another order still closes it", statusOf(second), is("CHECKED_OUT"));
  }

  @Test
  @DisplayName("An event naming neither an eventId nor an order is skipped, and closes nothing")
  void anEventWithNoIdentityIsSkipped() {
    UUID tenant = Ids.newId();
    UUID shopper = Ids.newId();
    UUID cart = openCart(tenant, shopper, Ids.newId());
    handler.handle(
        "{\"eventType\":\"OrderPlaced\",\"tenantId\":\""
            + tenant
            + "\",\"channel\":\"ONLINE\",\"loginId\":\""
            + shopper
            + "\"}");
    assertThat(statusOf(cart), is("ACTIVE"));
  }

  // ── another business ────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Another business's OrderPlaced naming our shopper and store closes none of our carts")
  void anotherBusinessesEventClosesNothingOfOurs() {
    UUID tenant = Ids.newId();
    UUID other = Ids.newId();
    UUID shopper = Ids.newId();
    UUID store = Ids.newId();
    UUID ours = openCart(tenant, shopper, store);
    UUID theirs = openCart(other, shopper, store);

    handler.handle(online(other, shopper, store, Ids.newId(), Ids.newId()));
    assertThat(statusOf(theirs), is("CHECKED_OUT"));
    assertThat("online", statusOf(ours), is("ACTIVE"));

    UUID theirsAgain = openCart(other, shopper, store); // theirs is closed, so a new one is allowed
    handler.handle(till(other, shopper, store, Ids.newId(), Ids.newId()));
    assertThat(statusOf(theirsAgain), is("CHECKED_OUT"));
    assertThat("at a till", statusOf(ours), is("ACTIVE"));
  }

  // ── the export catalogue ────────────────────────────────────────────────────

  @Test
  @DisplayName("The dedupe table is delivery machinery: the export catalogue leaves it out")
  void theExportCatalogueLeavesTheDedupeTableOut() {
    var catalog = data.catalog();
    assertThat(
        catalog.problems().stream().map(p -> p.table() + ": " + p.message()).toList().toString(),
        catalog.problems().isEmpty(),
        is(true));
    assertThat(catalog.excludedTables().keySet(), hasItem("processed_events"));
    assertThat(
        "so a table is exported only when it is the business's",
        catalog.table("processed_events").isPresent(),
        is(false));
  }
}
