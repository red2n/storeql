package com.storeql.notification.channel;

import java.util.UUID;

/**
 * Fan-out channel: always runs the in-app (APP) path so the notification feed stays populated, then
 * the external channel (SMTP). External failure propagates so callers retry; APP never fails the
 * send. Recorded channel name is the external one when present (e.g. {@code SMTP}), else {@code
 * APP}.
 */
public final class CompositeChannel implements NotificationChannel {

  private final NotificationChannel inApp;
  private final NotificationChannel external;

  /**
   * @param inApp the APP channel, always run and never allowed to fail the send
   * @param external the outbound channel (e.g. SMTP) whose failures propagate to the caller
   */
  public CompositeChannel(NotificationChannel inApp, NotificationChannel external) {
    this.inApp = inApp;
    this.external = external;
  }

  /**
   * {@inheritDoc}
   *
   * @return the external channel's name, which is what gets recorded against the delivery
   */
  @Override
  public String name() {
    return external.name();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Runs the in-app path first so the feed is populated even if the external send then throws.
   *
   * @throws RuntimeException whatever the external channel raises, so the caller retries
   */
  /**
   * {@inheritDoc}
   *
   * <p>The external channel's name when it reaches the recipient, else the in-app one: a store
   * alert on an email deployment is delivered in-app only, and the log says so.
   */
  @Override
  public String nameFor(String recipient) {
    return external.reaches(recipient) ? external.name() : inApp.name();
  }

  @Override
  public void send(UUID tenantId, String recipient, String subject, String body) {
    // In-app first (no-op + debug log); never blocks external delivery.
    inApp.send(tenantId, recipient, subject, body);
    // Only where the external channel can reach: a recipient it cannot address (a store's id to
    // an email server) is the in-app feed's alone, never a failure that loses the alert.
    if (external.reaches(recipient)) {
      external.send(tenantId, recipient, subject, body);
    }
  }

  /** For {@link NotificationChannelProducer}'s shutdown disposer only. */
  NotificationChannel external() {
    return external;
  }
}
