package com.storeql.customer.client;

import com.storeql.customer.config.ServiceConfig;
import com.storeql.customer.json.Jsons;
import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.service.ServiceReader;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObjectBuilder;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * notification-svc, as customer-svc tells people things (13.12): a breach intimation to each
 * customer, by email where there is one and by text where there is only a phone.
 */
@ApplicationScoped
public class NotificationClient {

  private static final Logger LOG = System.getLogger(NotificationClient.class.getName());

  static final String NOTIFICATION_SERVICE = "notification-svc";

  /** The role the send is made with: what a manager sending by hand would carry. */
  static final String SENDER_ROLE = "MANAGER";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient web;

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    web =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(10))
            .build();
  }

  /**
   * Sends one message.
   *
   * @param channel EMAIL or SMS
   * @param recipient the address or number
   * @param eventId dedupes a resend of the same message to the same person
   * @return whether notification-svc took it
   */
  public boolean send(
      UUID tenantId,
      UUID actor,
      String channel,
      String recipient,
      String subject,
      String body,
      String type,
      UUID eventId,
      UUID customerId) {
    Optional<String> base = locate();
    if (base.isEmpty()) {
      LOG.log(Level.WARNING, "{0} could not be located", NOTIFICATION_SERVICE);
      return false;
    }
    JsonObjectBuilder json =
        Jsons.object()
            .add("recipient", recipient)
            .add("subject", subject)
            .add("body", body)
            .add("type", type)
            .add("channel", channel)
            .add("eventId", eventId.toString())
            .add("customerId", customerId.toString());
    try (HttpClientResponse res =
        web.post(base.get() + "/notifications/send")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.USER_ID), actor == null ? "" : actor.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), SENDER_ROLE)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(json.build().toString())) {
      int status = res.status().code();
      if (status == 200 || status == 202) return true;
      LOG.log(Level.WARNING, "notification-svc answered HTTP {0} for {1}", status, customerId);
      return false;
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "notification-svc unreachable: {0}", e.getMessage());
      return false;
    }
  }

  private Optional<String> locate() {
    return ServiceReader.configuredUrl(NOTIFICATION_SERVICE)
        .or(() -> registry.resolve(NOTIFICATION_SERVICE).map(ServiceInstance::baseUri));
  }
}
