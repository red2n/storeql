package com.storeql.events.contract;

import com.storeql.events.EventPayload;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One service finished erasing a person's data (intent/privacy-requests.md, slice 4; also used by
 * platform-administration for a shopper login). Published by every service with a {@code
 * SubjectDataSpec} on {@code storeql.<service>.subject-erasure-completed}; consumers: customer-svc
 * and tenant-svc (they write a receipt once per service and subject). {@code aggregateId} is the
 * subject's id. {@code tenantId} is absent for a SHOPPER_LOGIN (it belongs to no business).
 *
 * <p>The page names the time member {@code at}; here it is the envelope's {@code occurredAt}, the
 * one time every event carries.
 */
public final class SubjectErasureCompleted {

  public static final String TYPE = "SubjectErasureCompleted";
  public static final String TOPIC_TEMPLATE =
      "storeql." + EventContract.SERVICE_PLACEHOLDER + ".subject-erasure-completed";

  public static final String SUBJECT_KIND = "subjectKind";
  public static final String SUBJECT_ID = "subjectId";
  public static final String SERVICE = "service";
  public static final String COUNTS = "counts";
  public static final String ERASE = "ERASE";
  public static final String ANONYMISE = "ANONYMISE";
  public static final String RETAIN = "RETAIN";

  public static final String KIND_CUSTOMER = "CUSTOMER";
  public static final String KIND_SHOPPER_LOGIN = "SHOPPER_LOGIN";

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC_TEMPLATE, TenantScope.OPTIONAL, SubjectErasureCompleted::read);

  private SubjectErasureCompleted() {}

  public static String topicFor(String service) {
    return CONTRACT.topicFor(service);
  }

  /** Rows dealt with in one table, by what was done to them. */
  public record Counts(long erased, long anonymised, long retained) {}

  public record Read(
      Envelope envelope,
      String subjectKind,
      UUID subjectId,
      String service,
      Map<String, Counts> counts) {
    public Read {
      counts = Map.copyOf(counts);
    }
  }

  /** {@code tenantId} must be null exactly for a SHOPPER_LOGIN. */
  public static String payload(
      UUID tenantId,
      String subjectKind,
      UUID subjectId,
      String service,
      Map<String, Counts> counts) {
    if ((tenantId == null) != KIND_SHOPPER_LOGIN.equals(subjectKind)) {
      throw new IllegalArgumentException(
          "tenantId is absent for a SHOPPER_LOGIN and present for a CUSTOMER");
    }
    StringBuilder c = new StringBuilder("{");
    String sep = "";
    for (var e : counts.entrySet()) {
      c.append(sep)
          .append(JsonFields.quote(e.getKey()))
          .append(":{\"ERASE\":")
          .append(e.getValue().erased())
          .append(",\"ANONYMISE\":")
          .append(e.getValue().anonymised())
          .append(",\"RETAIN\":")
          .append(e.getValue().retained())
          .append('}');
      sep = ",";
    }
    c.append('}');
    return new JsonFields(EventPayload.baseOptionalTenant(TYPE, tenantId, subjectId))
        .str(SUBJECT_KIND, subjectKind)
        .uuid(SUBJECT_ID, subjectId)
        .str(SERVICE, service)
        .raw(COUNTS, c.toString())
        .close();
  }

  public static Read read(String json) {
    EventReader r = EventReader.open(json, TYPE);
    Map<String, Counts> counts = new LinkedHashMap<>();
    r.object(COUNTS)
        .forEach(
            (table, v) -> {
              var o = v.asJsonObject();
              counts.put(
                  table,
                  new Counts(
                      o.getJsonNumber(ERASE) == null ? 0 : o.getJsonNumber(ERASE).longValue(),
                      o.getJsonNumber(ANONYMISE) == null
                          ? 0
                          : o.getJsonNumber(ANONYMISE).longValue(),
                      o.getJsonNumber(RETAIN) == null ? 0 : o.getJsonNumber(RETAIN).longValue()));
            });
    return new Read(
        r.envelope(TenantScope.OPTIONAL),
        r.string(SUBJECT_KIND),
        r.uuid(SUBJECT_ID),
        r.string(SERVICE),
        Map.copyOf(counts));
  }
}
