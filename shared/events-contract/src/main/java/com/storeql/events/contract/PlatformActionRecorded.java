package com.storeql.events.contract;

import com.storeql.events.EventPayload;
import java.util.Optional;
import java.util.UUID;

/**
 * A platform login did something that changes state (intent/platform-administration.md, slice 2).
 * Platform scope: it never carries {@code tenantId}; the business it acted on, if any, is {@code
 * targetTenantId}. Published by any service that executed the action; consumer: tenant-svc writes
 * {@code platform_audit} once per event. The page names the audit columns (actor login, tier,
 * action key, tenant named, reason, approval id, request id, at) but not the member names; these
 * are the members. {@code aggregateId} is the id of the action.
 */
public final class PlatformActionRecorded {

  public static final String TYPE = "PlatformActionRecorded";
  public static final String TOPIC = "storeql.platform.action-recorded";

  public static final String ACTOR_LOGIN_ID = "actorLoginId";
  public static final String TIER = "tier";
  public static final String ACTION_KEY = "actionKey";
  public static final String TARGET_TENANT_ID = "targetTenantId";
  public static final String REASON = "reason";
  public static final String APPROVAL_ID = "approvalId";
  public static final String REQUEST_ID = "requestId";

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC, TenantScope.NONE, PlatformActionRecorded::read);

  private PlatformActionRecorded() {}

  public record Read(
      Envelope envelope,
      UUID actorLoginId,
      String tier,
      String actionKey,
      Optional<UUID> targetTenantId,
      Optional<String> reason,
      Optional<UUID> approvalId,
      Optional<String> requestId) {}

  public static String payload(
      UUID actionId,
      UUID actorLoginId,
      String tier,
      String actionKey,
      UUID targetTenantId,
      String reason,
      UUID approvalId,
      String requestId) {
    return new JsonFields(EventPayload.baseOptionalTenant(TYPE, null, actionId))
        .uuid(ACTOR_LOGIN_ID, actorLoginId)
        .str(TIER, tier)
        .str(ACTION_KEY, actionKey)
        .optUuid(TARGET_TENANT_ID, targetTenantId)
        .optStr(REASON, reason)
        .optUuid(APPROVAL_ID, approvalId)
        .optStr(REQUEST_ID, requestId)
        .close();
  }

  public static Read read(String json) {
    EventReader r = EventReader.open(json, TYPE);
    return new Read(
        r.envelope(TenantScope.NONE),
        r.uuid(ACTOR_LOGIN_ID),
        r.string(TIER),
        r.string(ACTION_KEY),
        r.optUuid(TARGET_TENANT_ID),
        r.optString(REASON),
        r.optUuid(APPROVAL_ID),
        r.optString(REQUEST_ID));
  }
}
