package com.storeql.events.contract;

import com.storeql.events.EventPayload;
import java.util.Optional;
import java.util.UUID;

/**
 * Two customer records became one (intent/customer-identity.md). Ids only, no personal data.
 * Producer: customer-svc; consumers: order-svc, cart-svc, reporting-svc, iam-svc, each acting once
 * per {@code mergeId}. {@code aggregateId} is the merge id.
 */
public final class CustomersMerged {

  public static final String TYPE = "CustomersMerged";
  public static final String TOPIC = "storeql.customer.customers-merged";

  public static final String MERGE_ID = "mergeId";
  public static final String SURVIVOR_ID = "survivorId";
  public static final String MERGED_ID = "mergedId";
  public static final String LOGIN_ID = "loginId";

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC, TenantScope.REQUIRED, CustomersMerged::read);

  private CustomersMerged() {}

  /** {@code loginId} is the login now linked to the survivor, when either record had one. */
  public record Read(
      Envelope envelope, UUID mergeId, UUID survivorId, UUID mergedId, Optional<UUID> loginId) {}

  public static String payload(
      UUID tenantId, UUID mergeId, UUID survivorId, UUID mergedId, UUID loginId) {
    return new JsonFields(EventPayload.base(TYPE, tenantId, mergeId))
        .uuid(MERGE_ID, mergeId)
        .uuid(SURVIVOR_ID, survivorId)
        .uuid(MERGED_ID, mergedId)
        .optUuid(LOGIN_ID, loginId)
        .close();
  }

  public static Read read(String json) {
    EventReader r = EventReader.open(json, TYPE);
    return new Read(
        r.envelope(TenantScope.REQUIRED),
        r.uuid(MERGE_ID),
        r.uuid(SURVIVOR_ID),
        r.uuid(MERGED_ID),
        r.optUuid(LOGIN_ID));
  }
}
