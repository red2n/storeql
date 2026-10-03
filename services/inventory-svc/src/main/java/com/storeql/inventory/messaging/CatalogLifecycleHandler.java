package com.storeql.inventory.messaging;

import com.storeql.ids.Ids;
import com.storeql.inventory.config.Jsons;
import com.storeql.inventory.repo.CatalogLinesOutRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keeps the lines-out projection from product-svc's lifecycle events (item lifecycle). A product
 * discontinued or delisted takes its variants out of replenishment; one launched or reinstated
 * brings them back. Idempotent: the projection is set to the event's state, however often it is
 * seen. A payload without the variants (an older {@code ProductDelisted}) is skipped, not guessed.
 */
@ApplicationScoped
public class CatalogLifecycleHandler {

  private static final Logger LOG = System.getLogger(CatalogLifecycleHandler.class.getName());

  static final String DISCONTINUED = "DISCONTINUED";
  static final String DELISTED = "DELISTED";

  @Inject CatalogLinesOutRepository linesOut;

  void handle(String json) {
    String eventType;
    UUID tenantId;
    UUID productId;
    List<UUID> variantIds = new ArrayList<>();
    try (var reader = Jsons.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventType = obj.getString("eventType", "");
      tenantId = Ids.parse(obj.getString("tenantId"));
      productId = Ids.parse(obj.getString("aggregateId", obj.getString("productId", "")));
      JsonArray ids =
          obj.containsKey("variantIds") && !obj.isNull("variantIds")
              ? obj.getJsonArray("variantIds")
              : null;
      if (ids != null) {
        for (var v : ids.getValuesAs(JsonString.class)) variantIds.add(Ids.parse(v.getString()));
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed catalogue lifecycle event skipped: " + e.getMessage());
      return;
    }
    switch (eventType) {
      case "ProductDiscontinued" ->
          linesOut.markOut(tenantId, productId, variantIds, DISCONTINUED, Instant.now());
      case "ProductDelisted" -> {
        if (variantIds.isEmpty()) return;
        linesOut.markOut(tenantId, productId, variantIds, DELISTED, Instant.now());
      }
      case "ProductLaunched", "ProductReinstated" -> linesOut.markIn(tenantId, productId);
      default -> {
        // Not a lifecycle move: nothing to project.
      }
    }
  }
}
