package com.storeql.notification.channel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Chooses the active {@link NotificationChannel} at startup from {@code
 * storeql.notification.channel}:
 *
 * <ul>
 *   <li>{@code app} (default) — in-app feed only ({@link AppChannel})
 *   <li>{@code email} / {@code smtp} — SMTP <em>plus</em> in-app ({@link CompositeChannel}): the
 *       feed still records every send, and the message is emailed
 *   <li>{@code mqtt} — MQTT push <em>plus</em> in-app: for device-facing alerts (POS terminals,
 *       kiosk displays, platform console), not customer-facing notifications ({@link MqttChannel})
 * </ul>
 *
 * SMS is a future channel. Consumers inject {@link NotificationChannel} and never know which
 * transport is live.
 */
@ApplicationScoped
public class NotificationChannelProducer {

  @Inject
  @ConfigProperty(name = "storeql.notification.channel", defaultValue = "app")
  String channelName;

  @Inject
  @ConfigProperty(name = "storeql.notification.from", defaultValue = "no-reply@storeql.local")
  String from;

  @Inject
  @ConfigProperty(name = "storeql.notification.smtp.host", defaultValue = "localhost")
  String smtpHost;

  @Inject
  @ConfigProperty(name = "storeql.notification.smtp.port", defaultValue = "587")
  int smtpPort;

  // Optional so an unset/blank credential is "no auth" rather than a failed injection.
  @Inject
  @ConfigProperty(name = "storeql.notification.smtp.username")
  java.util.Optional<String> smtpUsername;

  @Inject
  @ConfigProperty(name = "storeql.notification.smtp.password")
  java.util.Optional<String> smtpPassword;

  @Inject
  @ConfigProperty(name = "storeql.notification.smtp.starttls", defaultValue = "true")
  boolean startTls;

  @Inject
  @ConfigProperty(name = "storeql.notification.mqtt.host", defaultValue = "localhost")
  String mqttHost;

  @Inject
  @ConfigProperty(name = "storeql.notification.mqtt.port", defaultValue = "1883")
  int mqttPort;

  @Inject
  @ConfigProperty(name = "storeql.notification.mqtt.client-id", defaultValue = "notification-svc")
  String mqttClientId;

  @Inject
  @ConfigProperty(name = "storeql.notification.mqtt.tls", defaultValue = "false")
  boolean mqttTls;

  /** The username the broker's ACL lets publish to every business's topics. */
  static final String MQTT_PUBLISHER = "__publisher__";

  // The publisher's own password for the broker (20.15), from the secret store. It opens the broker
  // and nothing else: this service holds no key that can sign a platform token.
  @Inject
  @ConfigProperty(name = "storeql.notification.smtp.connect-timeout-ms", defaultValue = "5000")
  int smtpConnectTimeoutMs;

  @Inject
  @ConfigProperty(name = "storeql.notification.smtp.read-timeout-ms", defaultValue = "10000")
  int smtpReadTimeoutMs;

  @Inject
  @ConfigProperty(name = "storeql.notification.smtp.write-timeout-ms", defaultValue = "10000")
  int smtpWriteTimeoutMs;

  @Inject
  @ConfigProperty(name = "storeql.notification.smtp.sender-threads", defaultValue = "4")
  int smtpSenderThreads;

  @Inject
  @ConfigProperty(name = "storeql.notification.mqtt.publisher-password")
  java.util.Optional<String> mqttPublisherPassword;

  /**
   * Builds the single application-scoped channel the rest of the service injects.
   *
   * <p>Both external transports are wrapped in a {@link CompositeChannel} alongside {@link
   * AppChannel}, so turning on email or MQTT adds a transport rather than replacing the in-app
   * feed. An unrecognised {@code storeql.notification.channel} falls back to in-app rather than
   * failing startup.
   *
   * @return the configured channel: in-app alone, or in-app composed with SMTP or MQTT
   */
  @Produces
  @ApplicationScoped
  public NotificationChannel channel() {
    AppChannel app = new AppChannel();
    if ("email".equalsIgnoreCase(channelName) || "smtp".equalsIgnoreCase(channelName)) {
      SmtpChannel smtp =
          new SmtpChannel(
              smtpHost,
              smtpPort,
              blankToNull(smtpUsername),
              blankToNull(smtpPassword),
              from,
              startTls,
              new SmtpChannel.Timeouts(
                  smtpConnectTimeoutMs, smtpReadTimeoutMs, smtpWriteTimeoutMs, smtpSenderThreads));
      // Always keep the in-app path so the admin feed is populated when email is on.
      return new CompositeChannel(app, smtp);
    }
    if ("mqtt".equalsIgnoreCase(channelName)) {
      String password =
          mqttPublisherPassword
              .filter(p -> !p.isBlank())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "storeql.notification.mqtt.publisher-password must be set to publish over"
                              + " MQTT"));
      MqttChannel mqtt =
          new MqttChannel(mqttHost, mqttPort, mqttClientId, MQTT_PUBLISHER, password, mqttTls);
      return new CompositeChannel(app, mqtt);
    }
    return app;
  }

  // Disposer, not a destructor call site: releases the MQTT connection on app shutdown /
  // redeploy so no netty threads leak. A no-op for the app/SMTP channels.
  /**
   * Releases the MQTT connection on shutdown or redeploy so no netty threads leak.
   *
   * <p>A no-op for the in-app and SMTP channels, which hold nothing to close.
   *
   * @param channel the channel being destroyed, supplied by CDI
   */
  public void disposeChannel(@Disposes NotificationChannel channel) {
    if (channel instanceof CompositeChannel composite
        && composite.external() instanceof MqttChannel mqtt) {
      mqtt.close();
    }
  }

  private static String blankToNull(java.util.Optional<String> v) {
    return v.filter(s -> !s.isBlank()).orElse(null);
  }
}
