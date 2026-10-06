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

  /**
   * The name {@code notification_log.channel} holds for a channel asked for by name, for reading
   * the log by channel. The log records the carrier that carried a message (APP, SMTP, MQTT, SMS or
   * PUSH), never EMAIL, which is the name of the deployment's configured default channel: so EMAIL
   * stands for that channel's own carrier ({@code SMTP} or {@code MQTT} where the deployment sends
   * outside the app, {@code APP} where it keeps the in-app feed alone), and every other name — SMS,
   * PUSH, APP, or a carrier's own such as SMTP — stands for itself.
   *
   * @param name a channel or carrier name in any case, with or without surrounding spaces
   * @return the name to look for in the log, in upper case; null when no name is given
   */
  public String carrierOf(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String n = name.trim().toUpperCase(Locale.ROOT);
    return Channel.EMAIL.equals(n) ? configured.name() : n;
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
