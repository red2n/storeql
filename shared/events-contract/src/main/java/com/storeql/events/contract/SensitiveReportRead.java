package com.storeql.events.contract;

import com.storeql.events.EventPayload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Someone read a sensitive report's first page (intent/report-integrity.md). Published by the
 * serving service (inventory, pricing, purchase, order, and reporting for its own audit view) on
 * its own topic {@code storeql.<service>.report-read}; consumer: reporting-svc appends once per
 * event id. {@code aggregateId} is the id of this read. {@code filters}, {@code from} and {@code
 * to} are carried as the text the caller sent.
 */
public final class SensitiveReportRead {

  public static final String TYPE = "SensitiveReportRead";
  public static final String TOPIC_TEMPLATE =
      "storeql." + EventContract.SERVICE_PLACEHOLDER + ".report-read";

  public static final String USER_ID = "userId";
  public static final String ROLE = "role";
  public static final String REPORT_KEY = "reportKey";
  public static final String FILTERS = "filters";
  public static final String FROM = "from";
  public static final String TO = "to";
  public static final String STORES = "stores";

  /** The report keys the page names. */
  public static final List<String> REPORT_KEYS =
      List.of(
          "margin",
          "valuation",
          "vat-return",
          "tax-summary",
          "trial-balance",
          "nominal-ledger",
          "audit-trail",
          "exceptions",
          "audit-view");

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC_TEMPLATE, TenantScope.REQUIRED, SensitiveReportRead::read);

  private SensitiveReportRead() {}

  /** The topic the serving {@code service} (e.g. {@code inventory}) publishes on. */
  public static String topicFor(String service) {
    return CONTRACT.topicFor(service);
  }

  public record Read(
      Envelope envelope,
      UUID userId,
      String role,
      String reportKey,
      Optional<String> filters,
      Optional<String> from,
      Optional<String> to,
      List<UUID> stores) {
    public Read {
      stores = List.copyOf(stores);
    }
  }

  public static String payload(
      UUID tenantId,
      UUID readId,
      UUID userId,
      String role,
      String reportKey,
      String filters,
      String from,
      String to,
      List<UUID> stores) {
    return new JsonFields(EventPayload.base(TYPE, tenantId, readId))
        .uuid(USER_ID, userId)
        .str(ROLE, role)
        .str(REPORT_KEY, reportKey)
        .optStr(FILTERS, filters)
        .optStr(FROM, from)
        .optStr(TO, to)
        .uuids(STORES, stores == null ? List.of() : stores)
        .close();
  }

  public static Read read(String json) {
    EventReader r = EventReader.open(json, TYPE);
    return new Read(
        r.envelope(TenantScope.REQUIRED),
        r.uuid(USER_ID),
        r.string(ROLE),
        r.string(REPORT_KEY),
        r.optString(FILTERS),
        r.optString(FROM),
        r.optString(TO),
        r.uuids(STORES));
  }
}
