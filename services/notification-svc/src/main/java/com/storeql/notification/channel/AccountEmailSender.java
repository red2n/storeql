package com.storeql.notification.channel;

/**
 * Sends one account-level email — a password reset link, today — by SMTP alone, never through the
 * deployment's configured in-app, MQTT or SMS channel: a link to reset a login must never reach a
 * feed a business's own console can read. Wired directly from the same {@code
 * storeql.notification.smtp.*} keys {@link SmtpChannel} reads, rather than through {@link
 * NotificationChannelProducer}'s single configured {@link NotificationChannel}, so a deployment
 * whose default channel is {@code app} or {@code mqtt} never silently routes an account email
 * through the wrong transport.
 */
public interface AccountEmailSender {

  /**
   * Whether this deployment has SMTP as its transport ({@code storeql.notification.channel} is
   * {@code email} or {@code smtp}). False elsewhere — including the default, {@code app} — so
   * nothing is even attempted against a mail server nobody configured.
   */
  boolean live();

  /**
   * Attempts delivery. Never throws: a transport failure is caught and reported as {@code false} so
   * the caller writes its log row once, as NOT_SENT, rather than retrying into a possible second
   * send.
   *
   * @return true when the message was handed to the transport; false when there was none to hand it
   *     to, or it refused
   */
  boolean send(String recipient, String subject, String body);
}
