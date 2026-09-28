package com.storeql.notification.service;

import com.storeql.ids.Ids;
import com.storeql.notification.repo.NotificationRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The notification log a test writes to: what is recorded for an event and type is already notified
 * the next time, as the real table's unique key has it. {@link #notified} says everything already
 * was.
 */
public final class OnceRepo extends NotificationRepository {

  public boolean notified;
  public int records;

  /** Whom the last record was about; a fresh id until one is written, so a null is a finding. */
  public UUID subjectId = Ids.newId();

  private final Set<String> recorded = new HashSet<>();

  @Override
  public boolean alreadyNotified(UUID eventId, String type) {
    return notified || recorded.contains(eventId + "/" + type);
  }

  /** What the last record said happened: SENT, or SUPPRESSED in a sandbox. */
  public String lastStatus;

  /** What the last message was written in, and from which words. */
  public String language;

  public String template;

  /** The rest of the last record, for a test that needs more than the dedupe guard. */
  public UUID tenantId;

  public String channel;
  public String recipient;
  public String subject;
  public String body;

  @Override
  public void recordNotification(
      UUID tenantId,
      UUID subjectId,
      UUID eventId,
      String type,
      String channel,
      String recipient,
      String subject,
      String body,
      String status) {
    recordNotification(
        tenantId, subjectId, eventId, type, channel, recipient, subject, body, status, null, null);
  }

  @Override
  public void recordNotification(
      UUID tenantId,
      UUID subjectId,
      UUID eventId,
      String type,
      String channel,
      String recipient,
      String subject,
      String body,
      String status,
      String language,
      String template) {
    this.tenantId = tenantId;
    this.channel = channel;
    this.recipient = recipient;
    this.subject = subject;
    this.body = body;
    this.language = language;
    this.template = template;
    this.lastStatus = status;
    records++;
    this.subjectId = subjectId;
    recorded.add(eventId + "/" + type);
  }
}
