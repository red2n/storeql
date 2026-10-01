package com.storeql.events.contract;

import com.storeql.events.EventPayload;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * An owned metric crossed a rule's threshold (defined by intent/exception-alerts.md). Producers:
 * order, inventory, payment, customer, iam, tenant, notification; consumers: notification-svc (the
 * inbox), reporting-svc (counts). Carries ids only, never a name, address or a person's amount.
 * {@code aggregateId} is the rule's id.
 */
public final class ExceptionAlertRaised {

  public static final String TYPE = "ExceptionAlertRaised";
  public static final String TOPIC = "storeql.alerts.exception-alert-raised";

  public static final String RULE_ID = "ruleId";
  public static final String METRIC = "metric";
  public static final String STORE_ID = "storeId";
  public static final String SUBJECT_KIND = "subjectKind";
  public static final String SUBJECT_KEY = "subjectKey";
  public static final String OBSERVED = "observed";
  public static final String THRESHOLD = "threshold";
  public static final String WINDOW_MINUTES = "windowMinutes";
  public static final String UNIT = "unit";
  public static final String CURRENCY = "currency";
  public static final String EVIDENCE = "evidence";
  public static final String EVIDENCE_KIND = "kind";
  public static final String EVIDENCE_ID = "id";

  public static final String UNIT_COUNT = "COUNT";
  public static final String UNIT_MONEY = "MONEY";
  public static final String UNIT_PERCENT = "PERCENT";

  public static final EventContract CONTRACT =
      new EventContract(TYPE, TOPIC, TenantScope.REQUIRED, ExceptionAlertRaised::read);

  private ExceptionAlertRaised() {}

  /** A pointer at a record that explains the alert ({@code RETURN}, {@code ORDER}, ...). */
  public record Evidence(String kind, UUID id) {}

  public record Read(
      Envelope envelope,
      UUID ruleId,
      String metric,
      Optional<UUID> storeId,
      String subjectKind,
      String subjectKey,
      BigDecimal observed,
      BigDecimal threshold,
      int windowMinutes,
      String unit,
      Optional<String> currency,
      List<Evidence> evidence) {
    public Read {
      evidence = List.copyOf(evidence);
    }
  }

  /** {@code storeId} null = business-wide; {@code currency} is set when {@code unit} is MONEY. */
  public static String payload(
      UUID tenantId,
      UUID ruleId,
      String metric,
      UUID storeId,
      String subjectKind,
      String subjectKey,
      BigDecimal observed,
      BigDecimal threshold,
      int windowMinutes,
      String unit,
      String currency,
      List<Evidence> evidence) {
    StringBuilder ev = new StringBuilder("[");
    String sep = "";
    for (Evidence e : evidence == null ? List.<Evidence>of() : evidence) {
      ev.append(sep)
          .append("{\"" + EVIDENCE_KIND + "\":")
          .append(JsonFields.quote(e.kind()))
          .append(",\"" + EVIDENCE_ID + "\":\"")
          .append(e.id())
          .append("\"}");
      sep = ",";
    }
    ev.append(']');
    return new JsonFields(EventPayload.base(TYPE, tenantId, ruleId))
        .uuid(RULE_ID, ruleId)
        .str(METRIC, metric)
        .optUuid(STORE_ID, storeId)
        .str(SUBJECT_KIND, subjectKind)
        .str(SUBJECT_KEY, subjectKey)
        .num(OBSERVED, observed)
        .num(THRESHOLD, threshold)
        .integer(WINDOW_MINUTES, windowMinutes)
        .str(UNIT, unit)
        .optStr(CURRENCY, currency)
        .raw(EVIDENCE, ev.toString())
        .close();
  }

  public static Read read(String json) {
    EventReader r = EventReader.open(json, TYPE);
    return new Read(
        r.envelope(TenantScope.REQUIRED),
        r.uuid(RULE_ID),
        r.string(METRIC),
        r.optUuid(STORE_ID),
        r.string(SUBJECT_KIND),
        r.string(SUBJECT_KEY),
        r.decimal(OBSERVED),
        r.decimal(THRESHOLD),
        r.integer(WINDOW_MINUTES),
        r.string(UNIT),
        r.optString(CURRENCY),
        r.objects(EVIDENCE).stream()
            .map(
                o ->
                    new Evidence(
                        o.getString(EVIDENCE_KIND),
                        com.storeql.ids.Ids.parse(o.getString(EVIDENCE_ID))))
            .toList());
  }
}
