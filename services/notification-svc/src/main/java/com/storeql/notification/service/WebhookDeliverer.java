package com.storeql.notification.service;

import com.storeql.ids.Ids;
import com.storeql.notification.domain.Webhooks;
import com.storeql.notification.domain.Webhooks.Attempt;
import com.storeql.notification.domain.Webhooks.Delivery;
import com.storeql.notification.domain.Webhooks.Due;
import com.storeql.notification.domain.Webhooks.Endpoint;
import com.storeql.notification.json.Jsons;
import com.storeql.notification.repo.WebhookRepository;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
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

  @Inject
  @ConfigProperty(name = "storeql.webhooks.lease-seconds", defaultValue = "120")
  long leaseSeconds;

  @Inject
  @ConfigProperty(name = "storeql.webhooks.concurrency", defaultValue = "8")
  int concurrency;

  @Inject
  @ConfigProperty(name = "storeql.webhooks.prune-interval-seconds", defaultValue = "3600")
  long pruneIntervalSeconds;

  @Inject
  @ConfigProperty(name = "storeql.webhooks.prune-batch", defaultValue = "1000")
  int pruneBatch;

  @Inject
  @ConfigProperty(name = "storeql.webhooks.prune-max-batches-per-run", defaultValue = "50")
  int pruneMaxBatches;

  private WebClient http;
  private ScheduledExecutorService scheduler;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private volatile Instant lastPrune = Instant.EPOCH;

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
    workers.shutdownNow();
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
    Duration lease = Duration.ofSeconds(leaseSeconds);
    List<Due> due = repo.claimDue(batch, now, lease);
    deliverAll(due, now.plus(lease.dividedBy(2)));
    pruneIfDue(now);
    return due.size();
  }

  /**
   * The claimed deliveries are sent concurrently, but one endpoint's deliveries go one after the
   * other (so its events keep their order), at most {@code concurrency} endpoints at once, so one
   * slow receiver delays only itself. An endpoint whose turn would begin after half the lease has
   * gone is left alone: its deliveries keep the lease and come due again when it ends, rather than
   * being sent a second time by whoever claims them next.
   */
  private void deliverAll(List<Due> due, Instant stopStarting) {
    if (due.isEmpty()) return;
    Map<UUID, List<Due>> byEndpoint = new LinkedHashMap<>();
    for (Due d : due) {
      byEndpoint.computeIfAbsent(d.endpoint().id(), k -> new java.util.ArrayList<>()).add(d);
    }
    Semaphore slots = new Semaphore(Math.max(1, concurrency));
    List<Future<?>> running = new java.util.ArrayList<>();
    for (List<Due> group : byEndpoint.values()) {
      running.add(
          workers.submit(
              () -> {
                try {
                  slots.acquire();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                try {
                  for (Due d : group) {
                    if (Instant.now().isAfter(stopStarting)) return;
                    try {
                      attempt(d);
                    } catch (RuntimeException e) {
                      LOG.log(Level.WARNING, "Webhook attempt failed: " + e.getMessage(), e);
                    }
                  }
                } finally {
                  slots.release();
                }
              }));
    }
    for (Future<?> f : running) {
      try {
        f.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (java.util.concurrent.ExecutionException e) {
        LOG.log(Level.WARNING, "Webhook delivery worker failed: " + e.getMessage(), e);
      }
    }
  }

  /**
   * Settled deliveries past the keep period go in bounded batches, once an interval, not a tick.
   */
  private void pruneIfDue(Instant now) {
    if (Duration.between(lastPrune, now).getSeconds() < pruneIntervalSeconds) return;
    lastPrune = now;
    Instant before = now.minus(Duration.ofDays(keepDays));
    for (int i = 0; i < pruneMaxBatches; i++) {
      if (repo.prune(before, pruneBatch) < pruneBatch) break;
    }
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
          // A URI, never the String form: that escapes a receiver's own query ("?token=…") into
          // the path, and any "%" in it twice.
          http.post()
              .uri(URI.create(e.url()))
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
        Jsons.object()
            .add("id", d.id().toString())
            .add("type", d.eventType())
            .add("eventId", d.eventId().toString())
            .add("tenantId", d.tenantId().toString())
            .add("attempt", attempt)
            .add("sentAt", at.toString());
    JsonObject data = null;
    try (var reader = Jsons.reader(new StringReader(d.payload()))) {
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

  /** At most SNIPPET_MAX bytes of the answer are read: the receiver chooses how long it is. */
  static String snippet(HttpClientResponse resp) {
    try (java.io.InputStream in = resp.entity().inputStream()) {
      byte[] bytes = in.readNBytes(SNIPPET_MAX);
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (java.io.IOException | RuntimeException e) {
      return null;
    }
  }
}
