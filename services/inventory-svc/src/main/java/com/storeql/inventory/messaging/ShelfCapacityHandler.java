package com.storeql.inventory.messaging;

import com.storeql.ids.Ids;
import com.storeql.inventory.config.Jsons;
import com.storeql.inventory.repo.ShelfTargetRepository;
import com.storeql.inventory.repo.ShelfTargetRepository.Target;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Keeps the shelf-target projection from product-svc's merchandising events (07.17).
 *
 * <p>{@code ShelfCapacityPublished} carries a whole fixture's layout, not a delta, so the
 * projection is <em>set</em> to what the event says and a repeat leaves the same rows. The version
 * guard in the repository handles the other half of at-least-once delivery: an older layout
 * re-delivered after a newer one is a no-op rather than a shelf going backwards.
 *
 * <p>{@code FixtureRetired} clears the fixture. Without it a bay that has been taken out would keep
 * a capacity for ever and replenishment would fill furniture nobody can see.
 *
 * <p>A malformed payload is logged and dropped, never guessed at: a projection built from a
 * half-read event is worse than one missing a fixture, because nothing about it looks wrong.
 */
@ApplicationScoped
public class ShelfCapacityHandler {

  private static final Logger LOG = System.getLogger(ShelfCapacityHandler.class.getName());

  @Inject ShelfTargetRepository targets;

  /**
   * Projects one merchandising event. Public because the projection is worth driving from an
   * integration test directly: an integration test here runs without a broker, and what matters is
   * what the SQL does with what arrives, not the delivery.
   */
  public void handle(String json) {
    JsonObject obj;
    String eventType;
    UUID tenantId;
    UUID storeId;
    UUID fixtureId;
    try (var reader = Jsons.createReader(new StringReader(json))) {
      obj = reader.readObject();
      eventType = obj.getString("eventType", "");
      tenantId = Ids.parse(obj.getString("tenantId"));
      storeId = Ids.parse(obj.getString("storeId"));
      fixtureId = Ids.parse(obj.getString("fixtureId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed merchandising event skipped: " + e.getMessage());
      return;
    }
    switch (eventType) {
      case "ShelfCapacityPublished" -> project(obj, tenantId, storeId, fixtureId);
      case "FixtureRetired" -> targets.clearFixture(tenantId, fixtureId);
      default -> {
        // Not a capacity move: nothing to project.
      }
    }
  }

  private void project(JsonObject obj, UUID tenantId, UUID storeId, UUID fixtureId) {
    UUID planogramId;
    int version;
    List<Target> rows = new ArrayList<>();
    try {
      planogramId = Ids.parse(obj.getString("planogramId"));
      // No version, no projection. It can only come from a build that never shipped, and assuming
      // one would let a stale layout overwrite a current shelf — the exact thing the guard is for.
      version = obj.getInt("version");
      for (JsonObject pos : obj.getJsonArray("positions").getValuesAs(JsonObject.class)) {
        rows.add(
            new Target(
                Ids.parse(pos.getString("variantId")),
                pos.getInt("capacity"),
                pos.getInt("minPresentation", 0)));
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed shelf capacity event skipped: " + e.getMessage());
      return;
    }
    if (rows.isEmpty()) {
      LOG.log(Level.WARNING, "Shelf capacity event with no positions skipped for " + fixtureId);
      return;
    }
    targets.project(tenantId, storeId, fixtureId, planogramId, version, rows, Instant.now());
  }
}
