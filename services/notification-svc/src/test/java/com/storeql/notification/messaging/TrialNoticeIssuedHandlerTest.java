package com.storeql.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * A business is told its trial is ending — the day, and what the plan then costs — and told once it
 * has ended, with the first invoice and the link that pays it (21.13); each once, in the platform's
 * name; what is malformed or names a stage nobody sends tells nobody anything.
 */
class TrialNoticeIssuedHandlerTest {

  private static final UUID TENANT = Ids.newId();
  private static final String LINK = "https://app.example/#/pay/9m2xKq1vT8sHc4bYw7Lp3Q";

  private RecordingChannel channel;
  private OnceRepo repo;
  private TrialNoticeIssuedHandler handler;

  @BeforeEach
  void setUp() {
    channel = new RecordingChannel();
    repo = new OnceRepo();
    handler = new TrialNoticeIssuedHandler();
    handler.notifier = NotifierTestSupport.notifierOf(channel, repo);
  }

  private static String event(String stage, String invoicePart) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"TrialNoticeIssued\",\"tenantId\":\""
        + TENANT
        + "\",\"subscriptionId\":\""
        + Ids.newId()
        + "\",\"stage\":\""
        + stage
        + "\",\"plan\":\"Starter\",\"trialEnd\":\"2026-10-06\",\"price\":49.00,\"currency\":\"EUR\","
        + "\"interval\":\"MONTH\",\"recipient\":\"accounts@weinhaus.example\","
        + "\"platform\":\"StoreQL Platform Ltd\","
        + invoicePart
        + "}";
  }

  private static final String NO_INVOICE =
      "\"invoiceId\":null,\"invoiceNumber\":null,\"amountDue\":null,\"dueDate\":null,\"payUrl\":null";

  private static final String INVOICE =
      "\"invoiceId\":\""
          + Ids.newId()
          + "\",\"invoiceNumber\":\"INV-2026-000042\",\"amountDue\":60.27,\"dueDate\":\"2026-10-13\","
          + "\"payUrl\":\""
          + LINK
          + "\"";

  @Test
  void theEndingNoticeNamesTheDayAndThePriceOnce() {
    String payload = event("ENDING", NO_INVOICE);
    handler.handle(payload);
    handler.handle(payload);

    assertEquals(1, channel.sends(), "a redelivered event must not tell the business twice");
    assertEquals("accounts@weinhaus.example", channel.recipient());
    assertEquals("Your trial of Starter ends on 6 October 2026", channel.subject());
    assertTrue(channel.body().contains("From then it is €49.00 a month"), channel.body());
    assertTrue(channel.body().endsWith("— StoreQL Platform Ltd"), channel.body());
    assertNull(repo.subjectId);
  }

  @Test
  void theEndedNoticeCarriesTheInvoiceAndTheLink() {
    handler.handle(event("ENDED", INVOICE));

    assertEquals(1, channel.sends());
    assertEquals("Your trial has ended: invoice INV-2026-000042 for €60.27", channel.subject());
    assertTrue(channel.body().contains("is due on 13 October 2026"), channel.body());
    assertTrue(channel.body().contains(LINK), channel.body());
  }

  @Test
  void whatIsMalformedOrUnknownTellsNobody() {
    handler.handle(event("ENDING", NO_INVOICE).replace("TrialNoticeIssued", "TrialStarted"));
    handler.handle("{not json");
    handler.handle(event("PAUSED", NO_INVOICE));
    handler.handle(event("ENDING", NO_INVOICE).replace("\"price\":49.00,", ""));
    handler.handle(event("ENDING", NO_INVOICE).replace("accounts@weinhaus.example", ""));

    assertEquals(0, channel.sends());
    assertEquals(0, repo.records);
  }
}
