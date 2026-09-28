package com.storeql.notification.service;

import com.storeql.ids.Ids;
import com.storeql.notification.domain.Webhooks;
import com.storeql.notification.domain.Webhooks.Attempt;
import com.storeql.notification.domain.Webhooks.Delivery;
import com.storeql.notification.domain.Webhooks.Due;
import com.storeql.notification.domain.Webhooks.Endpoint;
import com.storeql.notification.repo.WebhookRepository;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Sends what is due (22.6). Every few seconds the due deliveries of endpoints still switched on are
 * claimed under a short lease and posted, one HTTP request each, signed under the endpoint's secret
 * to the Standard Webhooks specification; what came back is recorded as a try of its own. A 2xx
 * lands the delivery; anything else — a refusal, a timeout, a connection nobody answered — queues
 * it again after a growing wait, and after the last allowed try it is dead, kept on the record for
 * the business to send again by hand. An endpoint that fails on and on is switched off with the
 * reason, so a receiver that is gone does not cost a request every few minutes for ever.
 */
@ApplicationScoped
public class WebhookDeliverer {

  private static final Logger LOG = System.getLogger(WebhookDeliverer.class.getName());
  private static final String USER_AGENT = "StoreQL-Webhooks/1";
  private static final int SNIPPET_MAX = 8192;
  private static final Duration LEASE = Duration.ofMinutes(2);

  @Inject WebhookRepository repo;
  @Inject WebhookSecrets secrets;

  @Inject
  @ConfigProperty(name = "storeql.webhooks.enabled", defaultValue = "true")
  boolean enabled;

  @Inject
  @ConfigProperty(name = "storeql.webhooks.poll-seconds", defaultValue = "5")
  long pollSeconds;

  @Inject
  @ConfigProperty(name = "storeql.webhooks.batch", defaultValue = "50")
  int batch;

  @Inject
  @ConfigProperty(name = "storeql.webhooks.timeout-seconds", defaultValue = "10")
  long timeoutSeconds;

  @Inject
  @ConfigProperty(name = "storeql.webhooks.max-attempts", defaultValue = "5")
  int maxAttempts;

  @Inject
  @ConfigProperty(name = "storeql.webhooks.disable-after-failures", defaultValue = "20")
  int disableAfterFailures;

  @Inject
  @ConfigProperty(name = "storeql.webhooks.keep-days", defaultValue = "30")
  int keepDays;

  private WebClient http;
  private ScheduledExecutorService scheduler;

  void onStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
    http =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(timeoutSeconds))
            .followRedirects(false)
            .build();
    if (!enabled) {
      LOG.log(Level.INFO, "Webhook delivery is off (storeql.webhooks.enabled=false)");
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "webhook-deliverer");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(this::tickQuietly, pollSeconds, pollSeconds, TimeUnit.SECONDS);
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) scheduler.shutdownNow();
  }

  private void tickQuietly() {
    try {
      tick();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Webhook delivery tick failed: " + e.getMessage(), e);
    }
  }

  /**
   * One pass: everything due is tried once.
   *
   * @return how many deliveries were tried
   */
  public int tick() {
    if (http == null) {
      http =
          WebClient.builder()
              .connectTimeout(Duration.ofSeconds(5))
              .readTimeout(Duration.ofSeconds(timeoutSeconds))
              .followRedirects(false)
              .build();
    }
    Instant now = Instant.now();
    List<Due> due = repo.claimDue(batch, now, LEASE);
    for (Due d : due) attempt(d);
    repo.prune(now.minus(Duration.ofDays(keepDays)));
    return due.size();
  }

  private void attempt(Due due) {
    Delivery d = due.delivery();
    Endpoint e = due.endpoint();
    int attempt = d.attempts() + 1;
    Instant at = Instant.now();
    long started = System.nanoTime();
    Integer status = null;
    String error = null;
    String snippet = null;
    try {
      String body = envelope(d, attempt, at);
      String secret = secrets.open(e.secretSealed());
      String signature = WebhookSigner.sign(secret, d.id().toString(), at.getEpochSecond(), body);
      try (HttpClientResponse resp =
          http.post(e.url())
              .header(HeaderNames.CONTENT_TYPE, "application/json")
              .header(HeaderNames.USER_AGENT, USER_AGENT)
              // Standard Webhooks: the delivery, the second and the signature over all three.
              .header(HeaderNames.create("webhook-id"), d.id().toString())
              .header(HeaderNames.create("webhook-timestamp"), Long.toString(at.getEpochSecond()))
              .header(HeaderNames.create("webhook-signature"), signature)
              .header(HeaderNames.create("X-StoreQL-Event"), d.eventType())
              .header(HeaderNames.create("X-StoreQL-Attempt"), Integer.toString(attempt))
              .submit(body.getBytes(StandardCharsets.UTF_8))) {
        status = resp.status().code();
        snippet = snippet(resp);
      }
    } catch (RuntimeException ex) {
      error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }
    int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000L);
    boolean landed = status != null && status >= 200 && status < 300;
    if (!landed && error == null) error = "HTTP " + status;

    Delivery next;
    if (landed) {
      next = with(d, Webhooks.DELIVERED, attempt, null, at, status, null);
    } else if (attempt >= maxAttempts) {
      next = with(d, Webhooks.DEAD, attempt, null, null, status, error);
    } else {
      next =
          with(
              d,
              Webhooks.PENDING,
              attempt,
              at.plus(Webhooks.backoff(attempt)),
              null,
              status,
              error);
    }
    repo.recordAttempt(
        new Attempt(
            Ids.newId(), d.tenantId(), d.id(), attempt, at, status, error, snippet, durationMs),
        next);
    if (landed) {
      repo.endpointSucceeded(e.id(), at);
    } else {
      boolean switchedOff =
          repo.endpointFailed(
              e.id(),
              disableAfterFailures,
              disableAfterFailures + " deliveries failed in a row; the last: " + error,
              at);
      if (switchedOff) {
        LOG.log(
            Level.WARNING,
            "Webhook endpoint {0} of {1} switched off after {2} failures in a row",
            e.id(),
            e.tenantId(),
            disableAfterFailures);
      }
    }
  }

  private static Delivery with(
      Delivery d,
      String status,
      int attempts,
      Instant nextAttemptAt,
      Instant deliveredAt,
      Integer lastStatus,
      String lastError) {
    return new Delivery(
        d.id(),
        d.tenantId(),
        d.endpointId(),
        d.eventId(),
        d.eventType(),
        d.payload(),
        status,
        attempts,
        nextAttemptAt,
        deliveredAt,
        lastStatus,
        lastError,
        d.createdAt());
  }

  /** What the receiver reads: who, what, when, which try — and the event whole under data. */
  static String envelope(Delivery d, int attempt, Instant at) {
    JsonObjectBuilder b =
        Json.createObjectBuilder()
            .add("id", d.id().toString())
            .add("type", d.eventType())
            .add("eventId", d.eventId().toString())
            .add("tenantId", d.tenantId().toString())
            .add("attempt", attempt)
            .add("sentAt", at.toString());
    JsonObject data = null;
    try (var reader = Json.createReader(new StringReader(d.payload()))) {
      data = reader.readObject();
    } catch (RuntimeException ignored) {
      // A payload that is not an object travels as text.
    }
    if (data != null) {
      b.add("occurredAt", data.getString("occurredAt", at.toString()));
      b.add("data", data);
    } else {
      b.add("occurredAt", at.toString());
      b.add("data", d.payload());
    }
    return b.build().toString();
  }

  private static String snippet(HttpClientResponse resp) {
    try {
      String text = resp.as(String.class);
      if (text == null) return null;
      return text.length() > SNIPPET_MAX ? text.substring(0, SNIPPET_MAX) : text;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
