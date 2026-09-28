package com.storeql.notification.channel;

import java.util.UUID;

/**
 * A pluggable outbound delivery channel. The active implementation is chosen at startup by {@link
 * NotificationChannelProducer} from {@code storeql.notification.channel} — {@link AppChannel}
 * (default, in-app feed) or a {@link CompositeChannel} of APP + {@link SmtpChannel} / {@link
 * MqttChannel} when email or MQTT is configured. Adding SMS later is a new implementation, no
 * change to the consumers.
 */
public interface NotificationChannel {

  /** Channel id recorded in {@code notification_log} (e.g. {@code APP}, {@code SMTP}). */
  String name();

  /**
   * Whether this channel can deliver to the recipient at all: an email channel reaches an email
   * address, not a store's id that a store alert is addressed to. Every channel but email reaches
   * whatever it is given.
   */
  default boolean reaches(String recipient) {
    return true;
  }

  /** The channel that actually carries a message to this recipient, as the log records it. */
  default String nameFor(String recipient) {
    return name();
  }

  /**
   * Deliver the message. {@code tenantId} is required (not just carried for the audit record):
   * channels backed by a shared broker (e.g. {@link MqttChannel}) scope the topic by it so one
   * tenant's devices can never receive another tenant's push. Throws on failure so the caller can
   * retry (nothing is recorded).
   */
  void send(UUID tenantId, String recipient, String subject, String body);
}
