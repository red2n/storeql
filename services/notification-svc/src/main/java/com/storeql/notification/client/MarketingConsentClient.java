package com.storeql.notification.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.notification.config.ServiceConfig;
import com.storeql.notification.dto.Dtos.MarketingAllowance;
import com.storeql.notification.json.Jsons;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.time.Duration;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;

/**
 * Asks customer-svc whether one marketing message may lawfully be sent (PECR reg.22), and gets back
 * the opt-out token the message must carry (reg.23).
 *
 * <p><strong>Fail-closed, and this is the whole point of the class.</strong> Every other client
 * here degrades to "carry on without it" because a missing lookup costs a nicety. This one cannot:
 * sending marketing without being able to show consent is the offence itself, so an unreachable
 * customer-svc, a timeout or an open circuit all mean <em>do not send</em>. A marketing message
 * delayed is a marketing message; a marketing message sent without consent is a fine.
 */
@ApplicationScoped
public class MarketingConsentClient {

  private static final System.Logger LOG = System.getLogger(MarketingConsentClient.class.getName());

  private static final String CUSTOMER_SERVICE = "customer-svc";

  /** See {@link CustomerClient}: an internal call names a role rather than relying on a bypass. */
  private static final String INTERNAL_ROLE = "CASHIER";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .build();
  }

  /**
   * @param tenantId the shop sending the message
   * @param customerId the person it would go to
   * @param channel the channel it would go out on, e.g. {@code EMAIL}
   * @return the decision, with the unsubscribe token when it is yes
   */
  @Retry(maxRetries = 2, delay = 200)
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  @Fallback(fallbackMethod = "refuse")
  public MarketingAllowance allowance(UUID tenantId, UUID customerId, String channel) {
    ServiceInstance instance = registry.resolve(CUSTOMER_SERVICE).orElse(null);
    if (instance == null) {
      return refuse(tenantId, customerId, channel);
    }
    // queryParam, never a "?" in the path: the WebClient percent-encodes it into the path and
    // customer-svc answers 404 — which this client correctly reads as "refuse", so every marketing
    // send was being refused. Found by driving the running stack, not by the tests.
    try (HttpClientResponse res =
        webClient
            .get(instance.baseUri() + "/customers/" + customerId + "/marketing/allowance")
            .queryParam("channel", channel)
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(HeaderNames.create(HttpHeaders.ROLES), INTERNAL_ROLE)
            .request()) {
      if (res.status().code() != 200) {
        return new MarketingAllowance(
            false, "NONE", "customer-svc answered " + res.status().code(), null);
      }
      try (JsonReader reader = Jsons.reader(new StringReader(res.as(String.class)))) {
        JsonObject data = reader.readObject().getJsonObject("data");
        if (data == null) {
          return new MarketingAllowance(false, "NONE", "customer-svc returned no decision", null);
        }
        return new MarketingAllowance(
            data.getBoolean("allowed", false),
            data.getString("basis", "NONE"),
            data.containsKey("reason") && !data.isNull("reason") ? data.getString("reason") : null,
            data.containsKey("unsubscribeToken") && !data.isNull("unsubscribeToken")
                ? data.getString("unsubscribeToken")
                : null);
      }
    }
  }

  // The @Fallback target, and also called directly when Consul knows no instance. Refusing is the
  // safe answer: see the class note. tenantId and channel stay in the signature because Fault
  // Tolerance matches a fallback by parameter types, not by what it uses.
  @SuppressWarnings("PMD.UnusedFormalParameter")
  private MarketingAllowance refuse(UUID tenantId, UUID customerId, String channel) {
    LOG.log(
        System.Logger.Level.WARNING,
        "marketing send to {0} refused: consent could not be checked (customer-svc unreachable or"
            + " circuit open)",
        customerId);
    return new MarketingAllowance(false, "NONE", "consent could not be checked", null);
  }
}
