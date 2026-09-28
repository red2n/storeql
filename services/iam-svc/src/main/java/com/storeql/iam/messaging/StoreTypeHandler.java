package com.storeql.iam.messaging;

import com.storeql.iam.repo.StoreTypeRepository;
import com.storeql.ids.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Business handler for the {@code type} a {@code StoreStatusChanged} carries: keeps iam-svc's
 * projection of each store's type current, so a till session is refused at a dark store
 * (ship-from-store and dark-store picking). An event from before the type was announced names none
 * and is left alone; a malformed one is logged and skipped, since it will never parse on
 * redelivery.
 */
@ApplicationScoped
public class StoreTypeHandler {

  private static final Logger LOG = System.getLogger(StoreTypeHandler.class.getName());

  @Inject StoreTypeRepository storeTypes;

  public void handle(String json) {
    UUID tenantId;
    UUID storeId;
    String type;
    Instant occurredAt;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      if (!obj.containsKey("type") || obj.isNull("type")) {
        return; // announced before the type rode with the status: nothing to keep
      }
      tenantId = Ids.parse(obj.getString("tenantId"));
      storeId = Ids.parse(obj.getString("storeId"));
      type = obj.getString("type").toUpperCase(Locale.ROOT);
      occurredAt =
          obj.containsKey("occurredAt") && !obj.isNull("occurredAt")
              ? Instant.parse(obj.getString("occurredAt"))
              : Instant.now();
    } catch (RuntimeException e) {
      LOG.log(
          Level.WARNING,
          "Malformed StoreStatusChanged payload skipped (store type handler): " + e.getMessage());
      return;
    }
    storeTypes.upsert(storeId, tenantId, type, occurredAt);
  }
}
