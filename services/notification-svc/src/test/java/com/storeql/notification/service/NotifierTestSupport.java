package com.storeql.notification.service;

import com.storeql.notification.channel.Channels;
import com.storeql.notification.channel.NotificationChannel;
import com.storeql.notification.repo.NotificationRepository;

/**
 * {@link Notifier}'s injection points are package-private by design (only {@link NotifierTest}
 * needs them directly). Tests for other classes that need a real, fake-backed {@link Notifier}
 * (e.g. a messaging handler test) go through this factory instead of widening those fields.
 */
public final class NotifierTestSupport {

  private NotifierTestSupport() {}

  /** A live business, as tenant-svc would say of nearly every business: never a sandbox. */
  public static Businesses live() {
    return new Businesses() {
      @Override
      public java.util.Optional<String> country(java.util.UUID tenantId) {
        return java.util.Optional.of("GB");
      }

      @Override
      public java.util.Optional<String> name(java.util.UUID tenantId) {
        return java.util.Optional.of("Hollins Grocers");
      }

      @Override
      public boolean sandbox(java.util.UUID tenantId) {
        return false;
      }
    };
  }

  public static Notifier notifierOf(NotificationChannel channel, NotificationRepository repo) {
    return notifierOf(channel, repo, null);
  }

  /**
   * The same, with the named channels a handler may send on (SMS, PUSH); a handler that names one
   * without them fails the test rather than silently sending nowhere.
   */
  public static Notifier notifierOf(
      NotificationChannel channel, NotificationRepository repo, Channels channels) {
    return notifierOf(channel, repo, channels, MessagesTestSupport.platformWords());
  }

  /** The same, writing messages with these templates: a business's own words. */
  public static Notifier notifierOf(
      NotificationChannel channel,
      NotificationRepository repo,
      Channels channels,
      Messages messages) {
    Notifier notifier = new Notifier();
    notifier.channel = channel;
    notifier.repo = repo;
    notifier.channels = channels;
    notifier.messages = messages;
    notifier.businesses = live();
    return notifier;
  }
}
