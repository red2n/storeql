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
import java.util.UUID;

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
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      type = obj.getString("eventType");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Event skipped by the webhook fan-out: " + e.getMessage());
      return 0;
    }
    if (Webhooks.eventType(type).isEmpty()) return 0;
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
