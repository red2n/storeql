package com.storeql.events.contract;

import com.storeql.events.EventPayload;
import java.util.Optional;
import java.util.UUID;

/**
 * A rule was saved or removed (intent/exception-alerts.md): every {@code ExceptionRules} reader
 * drops its cached copy. Producer: notification-svc. {@code aggregateId} is the rule's id.
 */
public final class ExceptionRuleChanged {

  public static final String TYPE = "ExceptionRuleChanged";
  public static final String TOPIC = "storeql.alerts.exception-rule-changed";

  public static final String METRIC = "metric";
  public static final String STORE_ID = "storeId";
  public static final String VERSION = "version";

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC, TenantScope.REQUIRED, ExceptionRuleChanged::read);

  private ExceptionRuleChanged() {}

  public record Read(Envelope envelope, String metric, Optional<UUID> storeId, long version) {}

  /** {@code storeId} null = the rule for the whole business. */
  public static String payload(
      UUID tenantId, UUID ruleId, String metric, UUID storeId, long version) {
    return new JsonFields(EventPayload.base(TYPE, tenantId, ruleId))
        .str(METRIC, metric)
        .optUuid(STORE_ID, storeId)
        .integer(VERSION, version)
        .close();
  }

  public static Read read(String json) {
    EventReader r = EventReader.open(json, TYPE);
    return new Read(
        r.envelope(TenantScope.REQUIRED),
        r.string(METRIC),
        r.optUuid(STORE_ID),
        r.integer(VERSION));
  }
}
