package com.storeql.events.contract;

import com.storeql.events.EventPayload;
import java.util.Optional;
import java.util.UUID;

/**
 * A pending request passed its expiry and was closed without running (intent/approvals.md).
 * Consumer: notification-svc (tells the maker and the approvers). {@code aggregateId} is the
 * request's id.
 */
public final class ApprovalExpired {

  public static final String TYPE = "ApprovalExpired";
  public static final String TOPIC = "storeql.approvals.expired";

  public static final String ACTION_KEY = "actionKey";
  public static final String STORE_ID = "storeId";
  public static final String MAKER_ID = "makerId";

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC, TenantScope.OPTIONAL, ApprovalExpired::read);

  private ApprovalExpired() {}

  public record Read(Envelope envelope, String actionKey, Optional<UUID> storeId, UUID makerId) {}

  public static String payload(
      UUID tenantId, UUID requestId, String actionKey, UUID storeId, UUID makerId) {
    return new JsonFields(EventPayload.baseOptionalTenant(TYPE, tenantId, requestId))
        .str(ACTION_KEY, actionKey)
        .optUuid(STORE_ID, storeId)
        .uuid(MAKER_ID, makerId)
        .close();
  }

  public static Read read(String json) {
    EventReader r = EventReader.open(json, TYPE);
    return new Read(
        r.envelope(TenantScope.OPTIONAL),
        r.string(ACTION_KEY),
        r.optUuid(STORE_ID),
        r.uuid(MAKER_ID));
  }
}
