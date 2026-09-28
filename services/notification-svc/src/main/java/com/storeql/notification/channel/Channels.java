package com.storeql.notification.channel;

import com.storeql.notification.domain.Domain.Channel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Locale;

/**
 * The channels a message may be sent on (13.7), by name. EMAIL is the deployment's configured
 * default channel — in-app alone, or SMTP or MQTT with in-app beside it — which is what every
 * message used before there was a choice; APP is the in-app feed alone, whatever the deployment
 * sends besides; SMS and PUSH are the two this adds.
 */
@ApplicationScoped
public class Channels {

  @Inject NotificationChannel configured;
  @Inject SmsChannel sms;
  @Inject PushChannel push;

  /** The in-app feed alone: its delivery is the log row the notifier writes. Stateless. */
  private final NotificationChannel inApp = new AppChannel();

  /**
   * @param name EMAIL, SMS, PUSH or APP in any case; null or blank means EMAIL
   * @return the channel, or null when the name is not one of them
   */
  public NotificationChannel forName(String name) {
    String n =
        name == null || name.isBlank() ? Channel.EMAIL : name.trim().toUpperCase(Locale.ROOT);
    return switch (n) {
      // In-app asked for by name is the feed alone, whatever else the deployment sends: a message a
      // caller meant for the feed must not go out by email because email is switched on.
      case Channel.APP -> inApp;
      case Channel.EMAIL -> configured;
      case Channel.SMS -> sms;
      case Channel.PUSH -> push;
      default -> null;
    };
  }

  public NotificationChannel configured() {
    return configured;
  }

  public SmsChannel sms() {
    return sms;
  }

  public PushChannel push() {
    return push;
  }
}
