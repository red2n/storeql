package com.storeql.notification.service;

import com.storeql.ids.Ids;
import com.storeql.notification.domain.Webhooks;
import com.storeql.notification.domain.Webhooks.Delivery;
import com.storeql.notification.domain.Webhooks.Endpoint;
import com.storeql.notification.repo.WebhookRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An event arrives; every endpoint of its business that asked for its type gets a delivery (22.6).
 *
 * <p>Idempotent on the event and the endpoint, not on the event alone: a delivery is one row per
 * endpoint and event, and a second arrival of the same event finds them already queued and writes
 * nothing. That is what makes it safe to share topics with this service's other consumers, each of
 * which marks the event processed in its own name.
 */
@ApplicationScoped
public class WebhookFanout {

  private static final Logger LOG = System.getLogger(WebhookFanout.class.getName());

  @Inject WebhookRepository repo;

  /** Types already reported as unidentifiable, so the warning is said once. */
  private final Set<String> unidentifiable = ConcurrentHashMap.newKeySet();

  /**
   * @param json an event as published
   * @return how many deliveries were queued
   */
  public int accept(String json) {
    UUID eventId;
    UUID tenantId;
    String type;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      type = obj.getString("eventType");
      // Most topics carry only catalogue events, but a type nobody can subscribe to is expected
      // traffic, not a fault: nothing to say, whatever it does or does not carry.
      if (Webhooks.eventType(type).isEmpty()) return 0;
      tenantId = Ids.parse(obj.getString("tenantId"));
      Optional<UUID> id = WebhookEventIds.of(type, obj);
      if (id.isEmpty()) {
        // A catalogue event that cannot be told from another of its kind: subscribers cannot be
        // sent it once and only once. A real fault (the producer must publish an eventId), said
        // loudly once per type and quietly after, so a run does not bury other warnings in it.
        if (unidentifiable.add(type)) {
          LOG.log(
              Level.WARNING,
              "Webhook fan-out cannot deliver {0}: it carries no eventId and no key to derive one"
                  + " from — its producer must publish an eventId (logged once per type)",
              type);
        } else {
          LOG.log(Level.DEBUG, "Webhook fan-out: {0} carries no eventId", type);
        }
        return 0;
      }
      eventId = id.get();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Event skipped by the webhook fan-out: " + e.getMessage());
      return 0;
    }
    List<Endpoint> endpoints = repo.subscribed(tenantId, type);
    if (endpoints.isEmpty()) return 0;
    Instant now = Instant.now();
    List<Delivery> rows = new ArrayList<>();
    for (Endpoint e : endpoints) {
      rows.add(
          new Delivery(
              Ids.newId(),
              tenantId,
              e.id(),
              eventId,
              type,
              json,
              Webhooks.PENDING,
              0,
              now,
              null,
              null,
              null,
              now));
    }
    return repo.fanout(rows);
  }
}
