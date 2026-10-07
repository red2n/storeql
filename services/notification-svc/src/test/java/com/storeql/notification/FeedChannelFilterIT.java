package com.storeql.notification;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.notification.repo.NotificationRepository;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The staff delivery log read by channel, on a deployment whose default channel is email (13.7).
 *
 * <p>The log records the carrier that carried a message (SMTP, APP, MQTT, SMS, PUSH), never EMAIL;
 * EMAIL is the name a caller uses for the deployment's default channel (the channels list, the
 * answer of a send). So {@code ?channel=EMAIL} has to find the rows of that channel's carrier, and
 * a carrier's own name has to keep finding itself. Rows are written straight to the log: nothing
 * here sends, so no mail server is needed.
 */
@HelidonTest
@AddConfig(key = "storeql.notification.channel", value = "email")
class FeedChannelFilterIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("notification");

  private static final String T = "01a090f1-2222-7000-8000-000000000001";
  private static final String OTHER = "01a090f1-2222-7000-8000-000000000002";

  private static final String MAIL = "buyer-mail@example.com";
  private static final String STORE_ALERT = "01a090f1-2222-7000-8000-0000000000a1";
  private static final String TEXT = "+447700900321";
  private static final String PUSHED = "01a090f1-2222-7000-8000-0000000000b1";
  private static final String DEVICE_DISPLAY = "01a090f1-2222-7000-8000-0000000000c1";
  private static final String THEIR_MAIL = "their-mail@example.com";
  private static final String THEIR_TEXT = "+48600100200";

  @Inject WebTarget target;
  @Inject NotificationRepository log;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  /** Fixed, so seeding twice (every test seeds) writes each row once: the log dedupes on it. */
  private static final UUID SEED = Ids.parse("01a090f1-2222-7000-8000-0000000000ff");

  private void row(String tenant, String type, String carrier, String recipient) {
    log.recordNotification(
        Ids.parse(tenant),
        null,
        Ids.derived(SEED, tenant + "/" + type + "/" + recipient),
        type,
        carrier,
        recipient,
        "Subject " + type,
        "Body for " + recipient,
        "SENT");
  }

  private void seed() {
    // A deployment that emails: the mail carrier is SMTP, and what it cannot address by email (a
    // store's id) is kept in the in-app feed alone, logged APP. The rest are other carriers.
    row(T, "ORDER_CONFIRMATION", "SMTP", MAIL);
    row(T, "SHORTAGE_ALERT", "APP", STORE_ALERT);
    row(T, "ORDER_READY", "SMS", TEXT);
    row(T, "ORDER_READY", "PUSH", PUSHED);
    row(T, "STORE_ALERT", "MQTT", DEVICE_DISPLAY);
    row(OTHER, "ORDER_CONFIRMATION", "SMTP", THEIR_MAIL);
    row(OTHER, "ORDER_READY", "SMS", THEIR_TEXT);
  }

  private Response feed(String tenant, String role, String channel, String recipient) {
    WebTarget t = target.path("/admin/notifications");
    if (channel != null) t = t.queryParam("channel", channel);
    if (recipient != null) t = t.queryParam("recipient", recipient);
    return t.request().header("X-Tenant-Id", tenant).header("X-Roles", role).get();
  }

  /** The recipients of the rows a read returned, and nothing else about them. */
  private List<String> recipients(String tenant, String role, String channel) {
    Response r = feed(tenant, role, channel, null);
    var rows = Envelopes.okArray(r);
    return rows.getValuesAs(JsonObject.class).stream().map(o -> o.getString("recipient")).toList();
  }

  private String count(String tenant) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM notification.notification_log WHERE tenant_id = '" + tenant + "'");
  }

  @Test
  @DisplayName("EMAIL finds the rows the email carrier carried, and a carrier's name finds itself")
  void emailIsTheDefaultChannelsCarrier() {
    seed();

    assertThat(recipients(T, "OWNER", "EMAIL"), is(List.of(MAIL)));
    assertThat(
        "any case, as a caller types it", recipients(T, "OWNER", "email"), is(List.of(MAIL)));
    assertThat(" padded", recipients(T, "MANAGER", "  Email "), is(List.of(MAIL)));
    assertThat("the carrier by its own name", recipients(T, "OWNER", "SMTP"), is(List.of(MAIL)));

    assertThat(recipients(T, "OWNER", "SMS"), is(List.of(TEXT)));
    assertThat(recipients(T, "OWNER", "PUSH"), is(List.of(PUSHED)));
    assertThat(recipients(T, "OWNER", "MQTT"), is(List.of(DEVICE_DISPLAY)));
    assertThat(
        "the in-app feed is its own channel, not email",
        recipients(T, "OWNER", "APP"),
        is(List.of(STORE_ALERT)));
    assertThat(
        "a name nobody sends on finds nothing", recipients(T, "OWNER", "PIGEON"), is(empty()));
    assertThat(
        "no channel named: the whole feed",
        recipients(T, "OWNER", null),
        containsInAnyOrder(MAIL, STORE_ALERT, TEXT, PUSHED, DEVICE_DISPLAY));
  }

  @Test
  @DisplayName(
      "Another business reads its own rows by channel, never ours, even naming our address")
  void anotherBusinessNeverReadsOurRows() {
    seed();

    assertThat(recipients(OTHER, "OWNER", "EMAIL"), is(List.of(THEIR_MAIL)));
    assertThat(recipients(OTHER, "MANAGER", "SMTP"), is(List.of(THEIR_MAIL)));
    assertThat(recipients(OTHER, "OWNER", "SMS"), is(List.of(THEIR_TEXT)));
    assertThat(recipients(OTHER, "OWNER", "APP"), is(empty()));
    assertThat(recipients(OTHER, "OWNER", "PUSH"), is(empty()));

    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (String channel : new String[] {"EMAIL", "SMTP", "SMS", null}) {
        for (String ours : new String[] {MAIL, TEXT, STORE_ALERT, PUSHED}) {
          Response r = feed(OTHER, role, channel, ours);
          var rows = Envelopes.okArray(r);
          assertThat(role + " " + channel + " naming " + ours, rows.size(), is(0));
        }
      }
    }
    // And ours do not show theirs.
    assertThat(recipients(T, "OWNER", "EMAIL"), not(hasItem(THEIR_MAIL)));
    assertThat(recipients(T, "OWNER", "SMS"), not(hasItem(THEIR_TEXT)));
  }

  @Test
  @DisplayName(
      "Staff below management and a shopper are refused, of either business; nothing moves")
  void staffBelowManagementAndShoppersReadNothing() {
    seed();
    String ours = count(T);
    String theirs = count(OTHER);
    for (String role : new String[] {"STOREKEEPER", "CASHIER", "CUSTOMER"}) {
      for (String business : new String[] {T, OTHER}) {
        for (String channel : new String[] {"EMAIL", "SMTP", null}) {
          Response r = feed(business, role, channel, null);
          String body = r.readEntity(String.class);
          assertThat(
              role + " of " + business + " " + channel + ": " + body, r.getStatus(), is(403));
          assertThat(role, body.contains(MAIL), is(false));
          assertThat(role, body.contains(THEIR_MAIL), is(false));
        }
      }
    }
    assertThat("a read moves nothing of ours", count(T), is(ours));
    assertThat("nor of theirs", count(OTHER), is(theirs));
  }
}
