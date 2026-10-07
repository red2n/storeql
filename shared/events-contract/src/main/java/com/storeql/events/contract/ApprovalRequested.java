package com.storeql.events.contract;

import com.storeql.events.EventPayload;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A gated action is waiting for a second person (intent/approvals.md). Producer: each adopting
 * service, on the transaction that writes the request; consumer: notification-svc (approvers).
 * {@code aggregateId} is the request's id. A platform-scope request (slice 10) names no business:
 * {@code tenantId} is absent and {@code scope} is PLATFORM ({@code targetTenantId} may name one).
 */
public final class ApprovalRequested {

  public static final String TYPE = "ApprovalRequested";
  public static final String TOPIC = "storeql.approvals.requested";

  public static final String ACTION_KEY = "actionKey";
  public static final String STORE_ID = "storeId";
  public static final String SUBJECT_TYPE = "subjectType";
  public static final String SUBJECT_ID = "subjectId";
  public static final String MAKER_ID = "makerId";
  public static final String MAKER_TIER = "makerTier";
  public static final String APPROVER_PERMISSION = "approverPermission";
  public static final String APPROVALS_NEEDED = "approvalsNeeded";
  public static final String EXPIRES_AT = "expiresAt";
  public static final String SCOPE = "scope";
  public static final String TARGET_TENANT_ID = "targetTenantId";

  public static final String SCOPE_BUSINESS = "BUSINESS";
  public static final String SCOPE_PLATFORM = "PLATFORM";

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC, TenantScope.OPTIONAL, ApprovalRequested::read);

  private ApprovalRequested() {}

  public record Read(
      Envelope envelope,
      String actionKey,
      Optional<UUID> storeId,
      String subjectType,
      UUID subjectId,
      UUID makerId,
      Optional<String> makerTier,
      ApprovalValue value,
      String approverPermission,
      int approvalsNeeded,
      Optional<Instant> expiresAt,
      String scope,
      Optional<UUID> targetTenantId) {}

  /**
   * {@code tenantId} null only for scope PLATFORM; {@code storeId}/{@code expiresAt} may be null.
   */
  public static String payload(
      UUID tenantId,
      UUID requestId,
      String actionKey,
      UUID storeId,
      String subjectType,
      UUID subjectId,
      UUID makerId,
      String makerTier,
      ApprovalValue value,
      String approverPermission,
      int approvalsNeeded,
      Instant expiresAt,
      String scope,
      UUID targetTenantId) {
    if (tenantId == null && !SCOPE_PLATFORM.equals(scope)) {
      throw new IllegalArgumentException("tenantId is required unless scope is PLATFORM");
    }
    return value
        .writeTo(
            new JsonFields(EventPayload.baseOptionalTenant(TYPE, tenantId, requestId))
                .str(ACTION_KEY, actionKey)
                .optUuid(STORE_ID, storeId)
                .str(SUBJECT_TYPE, subjectType)
                .uuid(SUBJECT_ID, subjectId)
                .uuid(MAKER_ID, makerId)
                .optStr(MAKER_TIER, makerTier))
        .str(APPROVER_PERMISSION, approverPermission)
        .integer(APPROVALS_NEEDED, approvalsNeeded)
        .optInstant(EXPIRES_AT, expiresAt)
        .str(SCOPE, scope == null ? SCOPE_BUSINESS : scope)
        .optUuid(TARGET_TENANT_ID, targetTenantId)
        .close();
  }

  public static Read read(String json) {
    EventReader r = EventReader.open(json, TYPE);
    return new Read(
        r.envelope(TenantScope.OPTIONAL),
        r.string(ACTION_KEY),
        r.optUuid(STORE_ID),
        r.string(SUBJECT_TYPE),
        r.uuid(SUBJECT_ID),
        r.uuid(MAKER_ID),
        r.optString(MAKER_TIER),
        ApprovalValue.readFrom(r),
        r.string(APPROVER_PERMISSION),
        r.integer(APPROVALS_NEEDED),
        r.optInstant(EXPIRES_AT),
        r.optString(SCOPE).orElse(SCOPE_BUSINESS),
        r.optUuid(TARGET_TENANT_ID));
  }
}
