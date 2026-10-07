package com.storeql.events.contract;

import com.storeql.events.EventPayload;
import java.util.UUID;

/**
 * An owner saved or removed an authority rule (intent/approvals.md: "tenant, action, version,
 * who"). Producer: tenant-svc; consumers: notification-svc (tells the other owners) and every
 * {@code Authority} reader (evicts its cache). {@code aggregateId} is the rule's id.
 */
public final class AuthorityRuleChanged {

  public static final String TYPE = "AuthorityRuleChanged";
  public static final String TOPIC = "storeql.tenant.authority-rule-changed";

  public static final String ACTION_KEY = "actionKey";
  public static final String VERSION = "version";
  public static final String CHANGED_BY = "changedBy";

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC, TenantScope.REQUIRED, AuthorityRuleChanged::read);

  private AuthorityRuleChanged() {}

  public record Read(Envelope envelope, String actionKey, long version, UUID changedBy) {}

  public static String payload(
      UUID tenantId, UUID ruleId, String actionKey, long version, UUID changedBy) {
    return new JsonFields(EventPayload.base(TYPE, tenantId, ruleId))
        .str(ACTION_KEY, actionKey)
        .integer(VERSION, version)
        .uuid(CHANGED_BY, changedBy)
        .close();
  }

  public static Read read(String json) {
    EventReader r = EventReader.open(json, TYPE);
    return new Read(
        r.envelope(TenantScope.REQUIRED),
        r.string(ACTION_KEY),
        r.integer(VERSION),
        r.uuid(CHANGED_BY));
  }
}
