package com.storeql.order.messaging;

import com.storeql.ids.Ids;
import com.storeql.order.config.Json;
import com.storeql.service.TenantStatusRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Projects a tenant's trading currency onto this service's local {@code tenant_status} row.
 *
 * <p>Two events carry the same fact and are projected identically: {@code TenantCreated}, which
 * announces it once at onboarding, and {@code TenantCurrencyDeclared}, which tenant-svc re-emits on
 * demand to repair a projection that predates its consumer. Only the consumer identity differs, and
 * it has to — the dedupe key is (eventId, consumer), so sharing one identity between the two would
 * let a replay be swallowed as already-seen.
 *
 * <p>Kept apart from both handlers so the parse-validate-project sequence has one definition: the
 * validation below is the last thing standing between a malformed event and a wrong currency
 * stamped onto money, and two copies of it would eventually disagree.
 */
@ApplicationScoped
class TenantCurrencyProjector {

  private static final Logger LOG = System.getLogger(TenantCurrencyProjector.class.getName());
  private static final int ISO_4217_LENGTH = 3;

  @Inject TenantStatusRepository tenantStatus;

  /**
   * @param json the raw event payload
   * @param eventType the event name, used only in log messages so a failure names its source
   * @param consumerName the dedupe identity to record this event under
   */
  void project(String json, String eventType, String consumerName) {
    UUID eventId;
    UUID tenantId;
    String currency;
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject obj = reader.readObject();
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      currency = obj.getString("currency", null);
    } catch (RuntimeException e) {
      // Malformed payload will never parse on redelivery either — log and skip.
      LOG.log(Level.WARNING, "Malformed " + eventType + " payload skipped: " + e.getMessage());
      return;
    }

    // tenant-svc validates currency as a 3-character code before it ever reaches the outbox, so
    // anything else here means a producer contract change. Skip rather than project a value that
    // would then be stamped onto orders — the configured default is the safer fallback.
    if (currency == null || currency.trim().length() != ISO_4217_LENGTH) {
      LOG.log(
          Level.WARNING,
          eventType + " for tenant {0} carried no usable currency ({1}) — not projected",
          tenantId,
          currency);
      return;
    }

    boolean projected =
        tenantStatus.projectTenantCurrencyOnce(eventId, consumerName, tenantId, currency.trim());
    if (projected) {
      LOG.log(Level.INFO, "Projected currency {0} for tenant {1}", currency, tenantId);
    }
  }
}
