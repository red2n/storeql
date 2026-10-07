package com.storeql.payment.config;

import com.storeql.service.TenantDataSpec;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/**
 * What payment-svc holds for a business, and how it leaves (21.14, EU Data Act ch.VI): payments,
 * refunds, tills, cash movements and Z-reports. Every table and column of the payment schema is
 * exported except what is named here, with the reason the register gives.
 */
@ApplicationScoped
public class ExportableData extends TenantDataSpec {

  @Override
  public String schema() {
    return "payment";
  }

  @Override
  public Map<String, String> excludedTables() {
    return Map.of(
        "payment_webhook_events",
        "payment provider deliveries recorded to refuse replays: delivery machinery, and not tied to a business",
        "events_handled_since",
        "when payment-svc began acting on an event kind (voids): delivery machinery, and not tied to a business");
  }
}
