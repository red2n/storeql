package com.storeql.notification.channel;

import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client;
import com.storeql.notification.json.Jsons;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Device-facing push over MQTT: POS terminals, kiosk/back-store displays, and the platform console
 * subscribe to their own {@code storeql/notifications/{tenantId}/{recipient}} topic and receive
 * alerts (e.g. StockBelowThreshold) in real time instead of polling {@code
 * /admin/notifications/shortage-alerts}. Selected via {@code storeql.notification.channel=mqtt}.
 * Not for customer-facing push — browsers/phones don't speak MQTT natively; use SMTP/SMS for that.
 *
 * <p>The connection is established lazily on first send (not in the constructor) so a broker that
 * isn't up yet at boot does not fail service startup — see ARCHITECTURE §17 (services start in any
 * order). Once connected, the client reconnects automatically on drops; a publish attempted while
 * disconnected throws, so the caller (via {@link com.storeql.notification.service.Notifier}) does
 * not record the send and the Kafka consumer redelivers and retries.
 */
public final class MqttChannel implements NotificationChannel {

  private final Mqtt5BlockingClient client;
  private volatile boolean connected;
  private final java.util.concurrent.locks.ReentrantLock connectLock =
      new java.util.concurrent.locks.ReentrantLock();

  /**
   * Builds the client without opening a connection — see the class note on lazy connect.
   *
   * @param host broker hostname
   * @param port broker port
   * @param clientId MQTT client identifier this service registers under
   * @param username broker account; blank or {@code null} disables authentication
   * @param password password for {@code username}, from the secret store — never committed
   * @param tls whether to connect over TLS
   */
  public MqttChannel(
      String host, int port, String clientId, String username, String password, boolean tls) {
    var builder =
        Mqtt5Client.builder()
            .identifier(clientId)
            .serverHost(host)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig();
    if (tls) {
      builder = builder.sslWithDefaultConfig();
    }
    if (username != null && !username.isBlank()) {
      builder =
          builder
              .simpleAuth()
              .username(username)
              .password(password == null ? new byte[0] : password.getBytes(StandardCharsets.UTF_8))
              .applySimpleAuth();
    }
    this.client = builder.buildBlocking();
  }

  /**
   * {@inheritDoc}
   *
   * @return always {@code MQTT}
   */
  @Override
  public String name() {
    return "MQTT";
  }

  /**
   * {@inheritDoc}
   *
   * <p>Connects on first use, then publishes at QoS 1 to the recipient's own tenant-scoped topic.
   *
   * @throws IllegalStateException when the broker is unreachable or the publish is rejected, so the
   *     delivery is not recorded and the Kafka consumer redelivers
   */
  @Override
  public void send(UUID tenantId, String recipient, String subject, String body) {
    ensureConnected();
    String topic = topic(tenantId, recipient);
    try {
      client
          .publishWith()
          .topic(topic)
          .qos(MqttQos.AT_LEAST_ONCE)
          .payload(payload(subject, body))
          .send();
    } catch (RuntimeException e) {
      throw new IllegalStateException("MQTT publish to " + topic + " failed: " + e.getMessage(), e);
    }
  }

  private void ensureConnected() {
    if (connected) {
      return;
    }
    // A ReentrantLock, not synchronized: the connect blocks on the network, and a monitor would pin
    // the virtual thread's carrier (and every sender queued behind it) until the broker answered.
    connectLock.lock();
    try {
      if (!connected) {
        client.connect();
        connected = true;
      }
    } finally {
      connectLock.unlock();
    }
  }

  /**
   * Recipient is the topic key chosen by the caller (e.g. a store id) — devices subscribe to
   * exactly their own scope.
   */
  static String topic(UUID tenantId, String recipient) {
    return "storeql/notifications/" + tenantId + "/" + recipient;
  }

  static byte[] payload(String subject, String body) {
    StringWriter out = new StringWriter();
    try (var writer = Jsons.writer(out)) {
      writer.writeObject(
          Jsons.object()
              .add("subject", subject)
              .add("body", body)
              .add("sentAt", Instant.now().toString())
              .build());
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** Called by {@link NotificationChannelProducer}'s disposer on application shutdown. */
  void close() {
    if (connected) {
      client.disconnect();
    }
  }
}
