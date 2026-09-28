package com.storeql.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.notification.service.NotifierTestSupport;
import com.storeql.notification.service.OnceRepo;
import com.storeql.notification.service.RecordingChannel;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A business that is late is told once, at its billing address, in the platform's name: what it
 * owes, by when, the day its service goes, and the link that pays (21.12, SJ-D68); the suspension
 * has its own words; what is not a notice, or is malformed, tells nobody anything.
 */
class DunningNoticeIssuedHandlerTest {

  private static final UUID TENANT = Ids.newId();
  private static final String LINK = "https://app.example/#/pay/9m2xKq1vT8sHc4bYw7Lp3Q";

  private RecordingChannel channel;
  private OnceRepo repo;
  private DunningNoticeIssuedHandler handler;

  @BeforeEach
  void setUp() {
    channel = new RecordingChannel();
    repo = new OnceRepo();
    handler = new DunningNoticeIssuedHandler();
    handler.notifier = NotifierTestSupport.notifierOf(channel, repo);
  }

  private static String event(String step, String suspendOn, String extra) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"DunningNoticeIssued\",\"tenantId\":\""
        + TENANT
        + "\",\"invoiceId\":\""
        + Ids.newId()
        + "\",\"invoiceNumber\":\"INV-2026-000041\",\"step\":\""
        + step
        + "\",\"daysOverdue\":3,\"dueDate\":\"2026-09-15\",\"currency\":\"EUR\","
        + "\"amountDue\":29.00,\"recipient\":\"accounts@weinhaus.example\",\"payUrl\":\""
        + LINK
        + "\",\"platform\":\"StoreQL Platform Ltd\",\"suspendOn\":"
        + suspendOn
        + extra
        + "}";
  }

  @Test
  void aReminderReachesTheBillingAddressOnceWithTheSumTheDayAndTheLink() {
    String payload = event("REMINDER_1", "\"2026-09-29\"", "");

    handler.handle(payload);
    handler.handle(payload);

    assertEquals(1, channel.sends(), "a redelivered event must not remind the business twice");
    assertEquals("accounts@weinhaus.example", channel.recipient());
    assertEquals(TENANT, channel.lastTenantId);
    assertEquals("Invoice INV-2026-000041 is overdue: €29.00", channel.subject());
    assertTrue(channel.body().contains("was due on 15 September 2026"), channel.body());
    assertTrue(channel.body().contains(LINK), channel.body());
    assertTrue(
        channel.body().contains("still unpaid on 29 September 2026, your service will be"),
        channel.body());
    // Signed by the platform, whose notice it is — not by the business, to itself.
    assertTrue(channel.body().endsWith("— StoreQL Platform Ltd"), channel.body());
    // A business, not a person: nothing here for an erasure to find.
    assertNull(repo.subjectId);
  }

  @Test
  void theSuspensionHasItsOwnWordsAndNoDayToWarnOf() {
    handler.handle(event("SUSPENDED", "null", ""));

    assertEquals(1, channel.sends());
    assertEquals(
        "Your service is interrupted: invoice INV-2026-000041 is unpaid", channel.subject());
    assertTrue(channel.body().contains("your staff cannot sign in"), channel.body());
    assertTrue(channel.body().contains(LINK), channel.body());
    assertFalse(channel.body().contains("will be interrupted"), channel.body());
  }

  @Test
  void aLaterReminderIsStillAReminderAndOneWithNoDayToWarnOfSaysSo() {
    handler.handle(event("REMINDER_7", "null", ""));

    assertEquals(1, channel.sends());
    assertEquals("Invoice INV-2026-000041 is overdue: €29.00", channel.subject());
    assertTrue(
        channel.body().contains("Paying it brings your service back at once."), channel.body());
  }

  @Test
  void whatIsNotANoticeOrIsMalformedOrHasNoAddressTellsNobody() {
    handler.handle(event("REMINDER_1", "null", "").replace("DunningNoticeIssued", "DunningRun"));
    handler.handle("{not json");
    handler.handle("{\"eventType\":\"DunningNoticeIssued\",\"eventId\":\"not-a-uuid\"}");
    handler.handle(event("REMINDER_1", "null", "").replace("\"amountDue\":29.00,", ""));
    handler.handle(event("REMINDER_1", "\"next tuesday\"", ""));
    handler.handle(event("REMINDER_1", "null", "").replace("accounts@weinhaus.example", ""));

    assertEquals(0, channel.sends());
    assertEquals(0, repo.records);
  }
}
