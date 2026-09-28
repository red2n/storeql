package com.storeql.notification.service;

import com.storeql.notification.channel.NotificationChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The channel a test sends on: it keeps what was sent, in order, and can be told to fail.
 * notification-svc has no mocking framework, so this and {@link OnceRepo} stand in wherever a test
 * needs a real {@link Notifier} ({@link NotifierTestSupport#notifierOf}).
 */
public final class RecordingChannel implements NotificationChannel {

  public final List<String> recipients = new ArrayList<>();
  public final List<String> subjects = new ArrayList<>();
  public final List<String> bodies = new ArrayList<>();
  public boolean fail;
  public UUID lastTenantId;

  /** What this channel calls itself; FAKE unless a test names it after a real one. */
  public String channelName = "FAKE";

  @Override
  public String name() {
    return channelName;
  }

  @Override
  public void send(UUID tenantId, String recipient, String subject, String body) {
    if (fail) {
      throw new IllegalStateException("boom");
    }
    lastTenantId = tenantId;
    recipients.add(recipient);
    subjects.add(subject);
    bodies.add(body);
  }

  public int sends() {
    return recipients.size();
  }

  /** The last recipient sent to, or null when nothing was sent. */
  public String recipient() {
    return last(recipients);
  }

  public String subject() {
    return last(subjects);
  }

  public String body() {
    return last(bodies);
  }

  private static String last(List<String> sent) {
    return sent.isEmpty() ? null : sent.get(sent.size() - 1);
  }
}
