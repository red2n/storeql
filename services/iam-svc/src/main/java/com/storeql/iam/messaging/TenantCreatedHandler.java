package com.storeql.iam.messaging;

import com.storeql.iam.repo.SandboxRepository;
import com.storeql.iam.repo.UserRepository;
import com.storeql.ids.Ids;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Business handler for {@code storeql.tenant.tenant-created} events. Stamps {@code tenant_id} +
 * OWNER role on the registering user, idempotently. Separated from {@link TenantCreatedConsumer} so
 * Kafka lifecycle and domain logic each have a single reason to change (SRP).
 *
 * <p>The dedupe mark and the bind commit in one transaction (see {@code bindOwnerOnce}); a
 * malformed payload is logged and skipped, while a failed write propagates so the consumer loop
 * redelivers the record instead of losing it.
 */
@ApplicationScoped
class TenantCreatedHandler {

  private static final Logger LOG = System.getLogger(TenantCreatedHandler.class.getName());
  static final String CONSUMER_NAME = "iam-svc/tenant-created";

  @Inject UserRepository users;
  @Inject SandboxRepository sandboxes;

  void handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID ownerUserId;
    boolean sandbox;
    UUID sandboxOf;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      ownerUserId = Ids.parse(obj.getString("ownerUserId"));
      // An event from before sandboxes existed names no mode: it is a live business.
      sandbox = "SANDBOX".equals(obj.getString("mode", "LIVE"));
      sandboxOf =
          obj.containsKey("sandboxOf") && !obj.isNull("sandboxOf")
              ? Ids.parse(obj.getString("sandboxOf"))
              : null;
      if (sandbox && sandboxOf == null) {
        throw new IllegalArgumentException("a sandbox names what it is a sandbox of");
      }
    } catch (RuntimeException e) {
      // Malformed payload will never parse on redelivery either — log and skip.
      LOG.log(Level.WARNING, "Malformed TenantCreated payload skipped: " + e.getMessage());
      return;
    }

    if (sandbox) {
      // A sandbox (22.8): the owner already owns the live business, so nothing is bound; what is
      // kept is the pair, for trading a live owner's token and for minting sandbox keys.
      if (sandboxes.recordOnce(eventId, CONSUMER_NAME, tenantId, sandboxOf)) {
        LOG.log(Level.INFO, "Recorded sandbox {0} of tenant {1}", tenantId, sandboxOf);
      }
      return;
    }
    boolean processed = users.bindOwnerOnce(eventId, CONSUMER_NAME, ownerUserId, tenantId, "OWNER");
    if (processed) {
      LOG.log(Level.INFO, "Bound user {0} as OWNER of tenant {1}", ownerUserId, tenantId);
    }
  }
}
