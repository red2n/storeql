package com.storeql.order.client;

import com.storeql.discovery.ConsulClient;
import com.storeql.discovery.ServiceInstance;
import com.storeql.discovery.ServiceRegistry;
import com.storeql.order.config.Json;
import com.storeql.order.config.ServiceConfig;
import com.storeql.web.ApiException;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;

/**
 * Sync client for notification-svc's staff send API (golden rule #1/#4). Used to email POS receipts
 * when a cashier records an EMAIL receipt. Forwards the caller's identity so the staff-role gate on
 * {@code POST /notifications/send} accepts the request.
 */
@ApplicationScoped
public class NotificationClient {

  private static final Logger LOG = System.getLogger(NotificationClient.class.getName());
  private static final String NOTIFICATION_SERVICE = "notification-svc";

  @Inject ServiceConfig config;

  private ServiceRegistry registry;
  private WebClient webClient;

  @PostConstruct
  void init() {
    registry = new ConsulClient(config.consulHost(), config.consulPort());
    webClient =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(10))
            .build();
  }

  /**
   * Delivers one notification. Throws {@link ApiException} 503 when notification-svc is unreachable
   * so the cashier sees a clear failure instead of a silent "emailed" success.
   */
  @Retry(
      maxRetries = 2,
      delay = 200,
      abortOn = {ApiException.class})
  @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.6, delay = 5000)
  public void send(
      UUID tenantId,
      UUID userId,
      Set<String> roles,
      String recipient,
      String subject,
      String body,
      String type,
      UUID eventId,
      UUID customerId) {
    ServiceInstance instance =
        registry
            .resolve(NOTIFICATION_SERVICE)
            .orElseThrow(
                () ->
                    unavailable(
                        "notification-svc is not available — receipt was not emailed", null));

    var json =
        Json.createObjectBuilder()
            .add("recipient", recipient)
            .add("subject", subject)
            .add("body", body)
            .add("type", type == null ? "POS_RECEIPT" : type)
            .add("eventId", eventId.toString());
    // SJ-D43: names the customer, so erasing them erases the emailed receipt's log entry too.
    if (customerId != null) {
      json.add("customerId", customerId.toString());
    }
    String payload = json.build().toString();

    String rolesHeader =
        roles == null || roles.isEmpty()
            ? "CASHIER"
            : roles.stream().collect(Collectors.joining(","));

    try (HttpClientResponse res =
        webClient
            .post(instance.baseUri() + "/notifications/send")
            .header(HeaderNames.create(HttpHeaders.TENANT_ID), tenantId.toString())
            .header(
                HeaderNames.create(HttpHeaders.USER_ID), userId != null ? userId.toString() : "")
            .header(HeaderNames.create(HttpHeaders.ROLES), rolesHeader)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .submit(payload)) {
      int status = res.status().code();
      if (status == 202 || status == 200) {
        return;
      }
      String respBody = res.as(String.class);
      LOG.log(Level.WARNING, "notification-svc send returned HTTP {0}: {1}", status, respBody);
      if (status == 400) {
        throw ApiException.badRequest(
            "ORDER_RECEIPT_EMAIL_INVALID", "notification rejected the email: " + respBody);
      }
      if (status == 403) {
        throw ApiException.forbidden(
            "ORDER_RECEIPT_EMAIL_FORBIDDEN", "insufficient role to send receipt email");
      }
      throw unavailable(
          "notification-svc returned HTTP " + status + " — receipt was not emailed", null);
    } catch (ApiException e) {
      throw e;
    } catch (CircuitBreakerOpenException e) {
      throw unavailable("notification-svc circuit open — receipt was not emailed", e);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "notification-svc unreachable: {0}", e.getMessage());
      throw unavailable("notification-svc unreachable — receipt was not emailed", e);
    }
  }

  private static ApiException unavailable(String message, Throwable cause) {
    return new ApiException(
        503, "ORDER_NOTIFICATION_UNAVAILABLE", message, java.util.List.of(), cause);
  }
}
