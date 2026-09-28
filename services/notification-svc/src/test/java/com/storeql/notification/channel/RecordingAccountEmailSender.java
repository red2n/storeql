package com.storeql.notification.channel;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@link AccountEmailSender} a test sends on: it keeps what was sent, in order, and can be told
 * there is no transport ({@link #live} = false) or that the transport itself failed ({@link
 * #fail}). notification-svc has no mocking framework, so this stands in wherever a test needs a
 * real {@link com.storeql.notification.messaging.PasswordResetRequestedHandler} — mirrors {@link
 * com.storeql.notification.service.RecordingChannel}'s shape exactly, so the two channels a
 * password reset must never use (in-app, MQTT) can be told apart from the one it must.
 */
public final class RecordingAccountEmailSender implements AccountEmailSender {

  public boolean live = true;
  public boolean fail;

  public final List<String> recipients = new ArrayList<>();
  public final List<String> subjects = new ArrayList<>();
  public final List<String> bodies = new ArrayList<>();

  @Override
  public boolean live() {
    return live;
  }

  @Override
  public boolean send(String recipient, String subject, String body) {
    if (!live || fail) {
      return false;
    }
    recipients.add(recipient);
    subjects.add(subject);
    bodies.add(body);
    return true;
  }

  public int sends() {
    return recipients.size();
  }

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
