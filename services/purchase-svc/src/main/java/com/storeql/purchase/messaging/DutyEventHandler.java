package com.storeql.purchase.messaging;

import com.storeql.ids.Ids;
import com.storeql.purchase.service.DutyService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * {@code DutyReleased} from inventory-svc: duty-suspended goods left bond for home use, and the
 * duty inventory-svc computed on them is now owed. Idempotent on the event id.
 */
@ApplicationScoped
public class DutyEventHandler {

  private static final Logger LOG = System.getLogger(DutyEventHandler.class.getName());

  @Inject DutyService service;

  public void dutyReleased(String json) {
    try {
      JsonObject o = EventJson.parse(json);
      if (!"DutyReleased".equals(o.getString("eventType", ""))) return;
      String occurredAt = o.getString("occurredAt", null);
      LocalDate releasedOn =
          occurredAt == null || occurredAt.isBlank()
              ? LocalDate.now(ZoneOffset.UTC)
              : Instant.parse(occurredAt).atZone(ZoneOffset.UTC).toLocalDate();
      service.record(
          Ids.parse(o.getString("eventId")),
          Ids.parse(o.getString("tenantId")),
          EventJson.optUuid(o, "releaseId"),
          EventJson.optUuid(o, "storeId"),
          Ids.parse(o.getString("variantId")),
          o.getJsonNumber("qty").bigDecimalValue(),
          EventJson.optNumber(o, "dutyPerUnit"),
          EventJson.optNumber(o, "dutyAmount"),
          o.getString("currency"),
          o.containsKey("reference") && !o.isNull("reference") ? o.getString("reference") : null,
          releasedOn);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "DutyReleased not recorded, malformed: " + e.getMessage());
    }
  }
}
