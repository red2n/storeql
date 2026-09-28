package com.storeql.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.notification.channel.Channels;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Notifier delivers once per (eventId, type): it sends + records on success, skips when already
 * notified or when there's no recipient, and on a channel failure propagates without recording (so
 * the consumer loop retries). notification-svc has no mocking framework, so fakes stand in.
 */
class NotifierTest {

  private static final UUID EVENT = Ids.newId();
  private static final UUID TENANT = Ids.newId();

  private RecordingChannel channel;
  private OnceRepo repo;
  private Notifier notifier;

  @BeforeEach
  void setUp() {
    channel = new RecordingChannel();
    repo = new OnceRepo();
    notifier = new Notifier();
    notifier.channel = channel;
    notifier.repo = repo;
    notifier.businesses = businesses;
  }

  /** Whether the business is a sandbox, as tenant-svc would say. */
  private boolean sandbox;

  private final Businesses businesses =
      new Businesses() {
        @Override
        public java.util.Optional<String> country(UUID tenantId) {
          return java.util.Optional.of("GB");
        }

        @Override
        public java.util.Optional<String> name(UUID tenantId) {
          return java.util.Optional.of("Hollins Grocers");
        }

        @Override
        public boolean sandbox(UUID tenantId) {
          return sandbox;
        }
      };

  // ── a sandbox (22.8) ───────────────────────────────────────────────────────
  // Nothing a sandbox does reaches a real person: no email, text or push leaves it. The message is
  // still written to the log, marked, so an integrator sees what would have been sent and to whom.

  @Test
  void aSandboxSendsNothingOutsideAndRecordsItAsSuppressed() {
    sandbox = true;
    notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, "a@b.com", "Hi", "body");
    assertEquals(0, channel.sends());
    assertEquals(1, repo.records);
    assertEquals("SUPPRESSED", repo.lastStatus);
    // And it is still once per event: the record stands in for the send.
    notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, "a@b.com", "Hi", "body");
    assertEquals(1, repo.records);
  }

  @Test
  void aSandboxStillWritesToTheAppItself() {
    sandbox = true;
    channel.channelName = "APP";
    notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, "a@b.com", "Hi", "body");
    assertEquals(1, channel.sends());
    assertEquals("SENT", repo.lastStatus);
  }

  @Test
  void aSandboxSuppressesTheNamedChannelsToo() {
    sandbox = true;
    RecordingChannel sms = new RecordingChannel();
    sms.channelName = "SMS";
    notifier.channels =
        new Channels() {
          @Override
          public com.storeql.notification.channel.NotificationChannel forName(String name) {
            return sms;
          }
        };
    notifier.notifyOnce(EVENT, "OTP", TENANT, null, "+447700900000", "Code", "1234", "SMS");
    assertEquals(0, sms.sends());
    assertEquals("SUPPRESSED", repo.lastStatus);
  }

  @Test
  void aLiveBusinessSendsAsBefore() {
    sandbox = false;
    notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, "a@b.com", "Hi", "body");
    assertEquals(1, channel.sends());
    assertEquals("SENT", repo.lastStatus);
  }

  @Test
  void sendsAndRecordsOnFirstDelivery() {
    notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, "a@b.com", "Hi", "body");

    assertEquals(1, channel.sends());
    assertEquals(1, repo.records);
    assertEquals(TENANT, channel.lastTenantId, "channel must receive the tenant for scoping");
  }

  @Test
  void skipsWhenAlreadyNotified() {
    repo.notified = true;
    notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, "a@b.com", "Hi", "body");

    assertEquals(0, channel.sends());
    assertEquals(0, repo.records);
  }

  @Test
  void skipsWhenNoRecipient() {
    notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, null, "Hi", "body");

    assertEquals(0, channel.sends());
    assertEquals(0, repo.records);
  }

  @Test
  void channelFailurePropagatesAndIsNotRecorded() {
    channel.fail = true;

    assertThrows(
        RuntimeException.class,
        () -> notifier.notifyOnce(EVENT, "WELCOME", TENANT, null, "a@b.com", "Hi", "body"));
    assertEquals(0, repo.records, "a failed send must not be recorded (so the consumer retries)");
  }
}
