package com.storeql.notification.service;

import com.storeql.notification.channel.Channels;
import com.storeql.notification.channel.NotificationChannel;
import com.storeql.notification.domain.Domain;
import com.storeql.notification.repo.NotificationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Delivers one outbound notification, exactly once per {@code (eventId, type)}. A missing recipient
 * is skipped. On a channel failure the exception propagates (nothing is recorded) so the consumer
 * loop retries and re-sends; on success the send is recorded so a redelivered event is a no-op.
 */
@ApplicationScoped
public class Notifier {

  private static final Logger LOG = System.getLogger(Notifier.class.getName());

  @Inject NotificationChannel channel;
  @Inject Channels channels;
  @Inject NotificationRepository repo;
  @Inject Messages messages;
  @Inject Businesses businesses;

  /** What the log says of a message a sandbox would have sent (22.8). */
  static final String SUPPRESSED = "SUPPRESSED";

  /**
   * A business's message, written in its words and the reader's language (13.x), on the configured
   * default channel. Written only once it is known to be due: a redelivered event reads nothing.
   *
   * @param subjectId the customer, supplier or account the message is about; null when none
   */
  public void notifyOnce(
      UUID eventId,
      String type,
      UUID tenantId,
      UUID subjectId,
      String recipient,
      Messages.Message message) {
    deliver(eventId, type, tenantId, subjectId, recipient, message, channel);
  }

  /** The same, on a named channel: EMAIL or APP, SMS, PUSH. */
  public void notifyOnce(
      UUID eventId,
      String type,
      UUID tenantId,
      UUID subjectId,
      String recipient,
      Messages.Message message,
      String channelName) {
    NotificationChannel c = channels.forName(channelName);
    if (c == null) {
      throw new IllegalArgumentException("unknown channel " + channelName);
    }
    deliver(eventId, type, tenantId, subjectId, recipient, message, c);
  }

  private void deliver(
      UUID eventId,
      String type,
      UUID tenantId,
      UUID subjectId,
      String recipient,
      Messages.Message message,
      NotificationChannel c) {
    if (recipient == null || recipient.isBlank()) {
      LOG.log(Level.DEBUG, "No recipient for {0} {1} — skipped", type, eventId);
      return;
    }
    if (repo.alreadyNotified(eventId, type)) {
      return;
    }
    Messages.Composed m = messages.compose(tenantId, message);
    boolean suppressed = suppressed(tenantId, c, recipient);
    if (!suppressed) {
      c.send(tenantId, recipient, m.subject(), m.body());
    }
    repo.recordNotification(
        tenantId,
        subjectId,
        eventId,
        type,
        c.nameFor(recipient),
        recipient,
        m.subject(),
        m.body(),
        suppressed ? SUPPRESSED : "SENT",
        m.language(),
        m.template());
  }

  /**
   * Whether a message must not leave (22.8): a sandbox reaches no real person — no email, text or
   * push — but its own in-app log still shows what would have gone and to whom, which is what an
   * integrator testing against it needs to see.
   */
  private boolean suppressed(UUID tenantId, NotificationChannel c, String recipient) {
    if (Domain.Channel.APP.equals(c.nameFor(recipient))) {
      return false;
    }
    boolean sandbox = businesses.sandbox(tenantId);
    if (sandbox) {
      LOG.log(Level.DEBUG, "Sandbox {0}: {1} message suppressed", tenantId, c.name());
    }
    return sandbox;
  }

  /**
   * @param subjectId the customer or account the message is about, so it can be found and erased
   *     later; null only when there is none
   */
  public void notifyOnce(
      UUID eventId,
      String type,
      UUID tenantId,
      UUID subjectId,
      String recipient,
      String subject,
      String body) {
    if (recipient == null || recipient.isBlank()) {
      LOG.log(Level.DEBUG, "No recipient for {0} {1} — skipped", type, eventId);
      return;
    }
    if (repo.alreadyNotified(eventId, type)) {
      return;
    }
    // Send first: a failure here throws and is NOT recorded, so the consumer redelivers and
    // retries.
    boolean suppressed = suppressed(tenantId, channel, recipient);
    if (!suppressed) {
      channel.send(tenantId, recipient, subject, body);
    }
    repo.recordNotification(
        tenantId,
        subjectId,
        eventId,
        type,
        channel.nameFor(recipient),
        recipient,
        subject,
        body,
        suppressed ? SUPPRESSED : "SENT");
  }

  /**
   * The same, on a named channel (13.7): EMAIL or APP for the configured default, SMS to a number,
   * PUSH to a login's devices. Idempotent on (eventId, type) like the default path.
   *
   * @throws IllegalArgumentException for a channel name that is not one of them
   */
  public void notifyOnce(
      UUID eventId,
      String type,
      UUID tenantId,
      UUID subjectId,
      String recipient,
      String subject,
      String body,
      String channelName) {
    NotificationChannel c = channels.forName(channelName);
    if (c == null) {
      throw new IllegalArgumentException("unknown channel " + channelName);
    }
    if (recipient == null || recipient.isBlank()) {
      LOG.log(Level.DEBUG, "No recipient for {0} {1} — skipped", type, eventId);
      return;
    }
    if (repo.alreadyNotified(eventId, type)) {
      return;
    }
    boolean suppressed = suppressed(tenantId, c, recipient);
    if (!suppressed) {
      c.send(tenantId, recipient, subject, body);
    }
    repo.recordNotification(
        tenantId,
        subjectId,
        eventId,
        type,
        c.nameFor(recipient),
        recipient,
        subject,
        body,
        suppressed ? SUPPRESSED : "SENT");
  }
}
