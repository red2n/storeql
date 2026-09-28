package com.storeql.notification.service;

import com.storeql.ids.Ids;
import com.storeql.notification.domain.Webhooks;
import com.storeql.notification.domain.Webhooks.Attempt;
import com.storeql.notification.domain.Webhooks.Delivery;
import com.storeql.notification.domain.Webhooks.Endpoint;
import com.storeql.notification.dto.WebhookDtos;
import com.storeql.notification.repo.WebhookRepository;
import com.storeql.service.Egress;
import com.storeql.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A business's webhook endpoints (22.6): registered by the owner for the events it wants, held to
 * the same address rule as an identity provider — HTTPS on a public address, no credentials —
 * because the URL is typed by a business and this service sits inside the cluster. The secret is
 * minted here, sealed for the database and shown once; rotating it shows the new one once.
 */
@ApplicationScoped
public class WebhookService {

  private static final int DESCRIPTION_MAX = 120;
  private static final int URL_MAX = 2048;
  private static final int EVENTS_MAX = 50;

  private static final SecureRandom RANDOM = new SecureRandom();

  @Inject WebhookRepository repo;
  @Inject WebhookSecrets secrets;

  @Inject
  @ConfigProperty(name = "storeql.webhooks.insecure-hosts")
  Optional<String> insecureHosts;

  private Egress egress;

  @PostConstruct
  void init() {
    Set<String> own = new LinkedHashSet<>();
    if (insecureHosts != null) {
      insecureHosts.ifPresent(
          hosts -> {
            for (String h : hosts.split(",")) {
              if (!h.isBlank()) own.add(h.trim().toLowerCase(Locale.ROOT));
            }
          });
    }
    egress = new Egress(own);
  }

  /** An endpoint as made, with the one showing of its secret. */
  public record Made(Endpoint endpoint, String secret) {}

  public Made register(UUID tenantId, UUID by, WebhookDtos.CreateRequest req) {
    if (!secrets.isConfigured()) {
      throw new ApiException(
          503,
          "WEBHOOKS_NOT_CONFIGURED",
          "Webhooks are not switched on for this deployment: no sealing key",
          List.of());
    }
    String url = checkedUrl(req.url());
    String description = checkedDescription(req.description());
    List<String> events = checkedEvents(req.events());
    Instant now = Instant.now();
    String secret = newSecret();
    Endpoint e =
        new Endpoint(
            Ids.newId(),
            tenantId,
            url,
            description,
            secrets.seal(secret),
            events,
            true,
            null,
            0,
            null,
            by,
            now,
            now);
    repo.insertEndpoint(e);
    return new Made(e, secret);
  }

  public List<Endpoint> list(UUID tenantId) {
    return repo.endpoints(tenantId);
  }

  public Endpoint get(UUID tenantId, UUID id) {
    return repo.endpoint(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("WEBHOOK_ENDPOINT_NOT_FOUND", "No such endpoint"));
  }

  public Endpoint update(UUID tenantId, UUID id, WebhookDtos.UpdateRequest req) {
    Endpoint current = get(tenantId, id);
    String url = req.url() == null ? current.url() : checkedUrl(req.url());
    String description =
        req.description() == null ? current.description() : checkedDescription(req.description());
    List<String> events = req.events() == null ? current.events() : checkedEvents(req.events());
    boolean enabled = req.enabled() == null ? current.enabled() : req.enabled();
    // Switched on again by hand: the run of failures and the reason it was switched off are over.
    boolean revived = enabled && !current.enabled();
    Endpoint changed =
        new Endpoint(
            current.id(),
            current.tenantId(),
            url,
            description,
            current.secretSealed(),
            events,
            enabled,
            revived ? null : current.disabledReason(),
            revived ? 0 : current.consecutiveFailures(),
            current.lastDeliveredAt(),
            current.createdBy(),
            current.createdAt(),
            Instant.now());
    if (!repo.updateEndpoint(changed)) {
      throw ApiException.notFound("WEBHOOK_ENDPOINT_NOT_FOUND", "No such endpoint");
    }
    return changed;
  }

  public void delete(UUID tenantId, UUID id) {
    if (!repo.deleteEndpoint(tenantId, id)) {
      throw ApiException.notFound("WEBHOOK_ENDPOINT_NOT_FOUND", "No such endpoint");
    }
  }

  /** A new secret, sealed for the database and returned once; the old one signs nothing more. */
  public String rotateSecret(UUID tenantId, UUID id) {
    get(tenantId, id);
    String secret = newSecret();
    if (!repo.rotateSecret(tenantId, id, secrets.seal(secret), Instant.now())) {
      throw ApiException.notFound("WEBHOOK_ENDPOINT_NOT_FOUND", "No such endpoint");
    }
    return secret;
  }

  /** A delivery made by hand, to prove the wiring: the endpoint hears from us, signed, now. */
  public UUID ping(UUID tenantId, UUID id) {
    Endpoint e = get(tenantId, id);
    Instant now = Instant.now();
    UUID eventId = Ids.newId();
    String payload =
        Json.createObjectBuilder()
            .add("eventId", eventId.toString())
            .add("eventType", Webhooks.PING)
            .add("tenantId", tenantId.toString())
            .add("aggregateId", e.id().toString())
            .add("occurredAt", now.toString())
            .add("message", "A test delivery from StoreQL to " + e.description())
            .build()
            .toString();
    Delivery d =
        new Delivery(
            Ids.newId(),
            tenantId,
            e.id(),
            eventId,
            Webhooks.PING,
            payload,
            Webhooks.PENDING,
            0,
            now,
            null,
            null,
            null,
            now);
    repo.insertDelivery(d);
    return d.id();
  }

  /** A page of deliveries, newest first, and where the next page starts. */
  public record Page(List<Delivery> items, String nextCursor) {}

  public Page deliveries(UUID tenantId, UUID endpointId, String status, UUID after, int limit) {
    if (status != null && !Webhooks.STATUSES.contains(status)) {
      throw ApiException.badRequest(
          "WEBHOOK_STATUS_INVALID", "A status is PENDING, DELIVERED or DEAD");
    }
    int size = Math.max(1, Math.min(limit, 100));
    List<Delivery> found = repo.deliveries(tenantId, endpointId, status, after, size + 1);
    if (found.size() <= size) return new Page(found, null);
    List<Delivery> page = found.subList(0, size);
    return new Page(page, page.get(size - 1).id().toString());
  }

  /** A delivery with every try. */
  public record Detail(Delivery delivery, List<Attempt> attempts) {}

  public Detail delivery(UUID tenantId, UUID id) {
    Delivery d =
        repo.delivery(tenantId, id)
            .orElseThrow(
                () -> ApiException.notFound("WEBHOOK_DELIVERY_NOT_FOUND", "No such delivery"));
    return new Detail(d, repo.attempts(d.id()));
  }

  /** Sent again by hand, now, whatever state it was in; the tries so far stay on the record. */
  public Delivery redeliver(UUID tenantId, UUID id) {
    if (!repo.redeliver(tenantId, id, Instant.now())) {
      throw ApiException.notFound("WEBHOOK_DELIVERY_NOT_FOUND", "No such delivery");
    }
    return repo.delivery(tenantId, id).orElseThrow();
  }

  /**
   * A secret as the Standard Webhooks specification writes it: {@code whsec_} and 32 random bytes
   * in base64.
   */
  static String newSecret() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Webhooks.SECRET_PREFIX + Base64.getEncoder().encodeToString(bytes);
  }

  // ── what is held before anything is kept ───────────────────────────────────

  private String checkedUrl(String url) {
    if (url == null || url.isBlank() || url.length() > URL_MAX) {
      throw ApiException.badRequest("WEBHOOK_URL_INVALID", "An endpoint is a URL");
    }
    try {
      return egress.check(url.trim()).toString();
    } catch (Egress.Refused e) {
      throw new ApiException(
          400,
          "WEBHOOK_URL_INVALID",
          "An endpoint is HTTPS on a public address, with no credentials: " + e.getMessage(),
          List.of(),
          e);
    }
  }

  private static String checkedDescription(String description) {
    String d = description == null ? "" : description.trim();
    if (d.isEmpty() || d.length() > DESCRIPTION_MAX) {
      throw ApiException.badRequest(
          "WEBHOOK_DESCRIPTION_INVALID",
          "An endpoint has a description of up to " + DESCRIPTION_MAX + " characters");
    }
    return d;
  }

  private static List<String> checkedEvents(List<String> events) {
    if (events == null || events.isEmpty()) {
      throw ApiException.badRequest(
          "WEBHOOK_EVENTS_EMPTY", "An endpoint asks for at least one event type");
    }
    if (events.size() > EVENTS_MAX) {
      throw ApiException.badRequest("WEBHOOK_EVENTS_EMPTY", "Too many event types");
    }
    List<String> distinct = new ArrayList<>(new LinkedHashSet<>(events));
    for (String type : distinct) {
      if (type == null || Webhooks.eventType(type).isEmpty()) {
        throw ApiException.badRequest(
            "WEBHOOK_EVENT_UNKNOWN",
            "No such event type: " + type + "; see GET /admin/webhooks/events");
      }
    }
    return distinct;
  }
}
