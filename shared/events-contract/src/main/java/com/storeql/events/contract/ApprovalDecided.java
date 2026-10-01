package com.storeql.events.contract;

import com.storeql.events.EventPayload;
import java.util.Optional;
import java.util.UUID;

/**
 * A request ended APPROVED or REJECTED (intent/approvals.md). Consumers: notification-svc (tells
 * the maker), reporting-svc (counts overrides per person and store). {@code aggregateId} is the
 * request's id. {@code executed} is true only when the action ran on the deciding transaction.
 * Carries the measure so an override can be counted by value without a call back.
 */
public final class ApprovalDecided {

  public static final String TYPE = "ApprovalDecided";
  public static final String TOPIC = "storeql.approvals.decided";

  public static final String ACTION_KEY = "actionKey";
  public static final String STORE_ID = "storeId";
  public static final String MAKER_ID = "makerId";
  public static final String DECISION = "decision";
  public static final String APPROVER_ID = "approverId";
  public static final String EXECUTED = "executed";

  public static final String APPROVED = "APPROVED";
  public static final String REJECTED = "REJECTED";

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC, TenantScope.OPTIONAL, ApprovalDecided::read);

  private ApprovalDecided() {}

  public record Read(
      Envelope envelope,
      String actionKey,
      Optional<UUID> storeId,
      UUID makerId,
      String decision,
      UUID approverId,
      boolean executed,
      ApprovalValue value) {}

  public static String payload(
      UUID tenantId,
      UUID requestId,
      String actionKey,
      UUID storeId,
      UUID makerId,
      String decision,
      UUID approverId,
      boolean executed,
      ApprovalValue value) {
    if (!APPROVED.equals(decision) && !REJECTED.equals(decision)) {
      throw new IllegalArgumentException("decision must be APPROVED or REJECTED");
    }
    return value
        .writeTo(
            new JsonFields(EventPayload.baseOptionalTenant(TYPE, tenantId, requestId))
                .str(ACTION_KEY, actionKey)
                .optUuid(STORE_ID, storeId)
                .uuid(MAKER_ID, makerId)
                .str(DECISION, decision)
                .uuid(APPROVER_ID, approverId)
                .bool(EXECUTED, executed))
        .close();
  }

  public static Read read(String json) {
    EventReader r = EventReader.open(json, TYPE);
    return new Read(
        r.envelope(TenantScope.OPTIONAL),
        r.string(ACTION_KEY),
        r.optUuid(STORE_ID),
        r.uuid(MAKER_ID),
        r.string(DECISION),
        r.uuid(APPROVER_ID),
        r.bool(EXECUTED),
        ApprovalValue.readFrom(r));
  }
}
