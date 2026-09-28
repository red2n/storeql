package com.storeql.order.messaging;

import com.storeql.ids.Ids;
import com.storeql.order.dto.Dtos.FulfilLine;
import com.storeql.order.dto.Dtos.FulfilRequest;
import com.storeql.order.repo.OrderRepository;
import com.storeql.order.service.OrderService;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A wave picked at a store fulfils the orders it names for the quantities picked — exactly as the
 * Fulfil button does, with no person at the screen. Each order is deduped on the event, so a
 * redelivered wave fulfils nothing twice; an order that cannot be fulfilled (cancelled meanwhile,
 * already handed over) is skipped and the rest still are.
 *
 * <p>Expected payload: {@code {eventId, eventType, tenantId, waveId, storeId, orders: [{orderId,
 * lines: [{variantId, qty}]}]}}.
 */
@ApplicationScoped
public class WavePickedHandler {

  private static final Logger LOG = System.getLogger(WavePickedHandler.class.getName());
  static final String CONSUMER = "order-svc/wave-picked";

  @Inject OrderRepository repo;
  @Inject OrderService svc;

  public void handle(String json) {
    UUID eventId;
    UUID tenantId;
    JsonArray orders;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      orders = obj.getJsonArray("orders");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed WavePicked event skipped: " + e.getMessage());
      return;
    }
    if (orders == null) return;
    for (int i = 0; i < orders.size(); i++) {
      JsonObject o = orders.getJsonObject(i);
      UUID orderId;
      List<FulfilLine> lines = new ArrayList<>();
      try {
        orderId = Ids.parse(o.getString("orderId"));
        JsonArray items = o.getJsonArray("lines");
        for (int j = 0; j < items.size(); j++) {
          JsonObject l = items.getJsonObject(j);
          lines.add(
              new FulfilLine(l.getString("variantId"), new BigDecimal(l.get("qty").toString())));
        }
      } catch (RuntimeException e) {
        LOG.log(Level.WARNING, "WavePicked order skipped as malformed: " + e.getMessage());
        continue;
      }
      // One dedupe per order on the event, written on the handover's own transaction: a
      // redelivery hands over only the orders this event never did, and a failure after the mark
      // cannot lose a handover, because there is no mark without one.
      UUID dedupe = Ids.derived(eventId, orderId.toString());
      try {
        if (!svc.fulfilOrderOnce(dedupe, CONSUMER, tenantId, orderId, new FulfilRequest(lines))) {
          continue;
        }
        LOG.log(
            Level.INFO, "WavePicked: order {0} fulfilled for {1} line(s)", orderId, lines.size());
      } catch (ApiException e) {
        if (e.status() >= 500) throw e;
        // Refused for a reason that will not change (cancelled meanwhile, already handed over):
        // remembered, so a redelivery is quiet about it.
        repo.markProcessedIfNew(dedupe, CONSUMER);
        LOG.log(
            Level.WARNING,
            "WavePicked: order {0} not fulfilled ({1}): {2}",
            orderId,
            e.code(),
            e.getMessage());
      }
    }
  }
}
