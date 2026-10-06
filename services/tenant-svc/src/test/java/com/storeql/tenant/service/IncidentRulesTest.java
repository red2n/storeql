package com.storeql.tenant.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.tenant.domain.Domain.IncidentEvent;
import com.storeql.tenant.domain.Domain.ReportingStage;
import com.storeql.tenant.domain.Domain.SecurityIncident;
import com.storeql.tenant.domain.Domain.StageStatus;
import com.storeql.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The clocks and rules of the security incident register, against the stages V7 seeds. */
class IncidentRulesTest {

  private static final Instant AWARE = Instant.parse("2026-09-14T08:00:00Z");
  private static final UUID ID = Ids.parse("01a090ae-611e-7040-8a4b-1f6d1c3a9e01");

  private static final List<ReportingStage> VULNERABILITY =
      List.of(
          new ReportingStage(
              "EXPLOITED_VULNERABILITY",
              "EARLY_WARNING",
              "AWARE",
              "PT24H",
              1,
              "CRA art.14",
              "Early warning"),
          new ReportingStage(
              "EXPLOITED_VULNERABILITY",
              "NOTIFICATION",
              "AWARE",
              "PT72H",
              2,
              "CRA art.14",
              "Notification"),
          new ReportingStage(
              "EXPLOITED_VULNERABILITY",
              "FINAL_REPORT",
              "MITIGATED",
              "P14D",
              3,
              "CRA art.14",
              "Final report"),
          new ReportingStage(
              "EXPLOITED_VULNERABILITY",
              "TENANT_NOTICE",
              "AWARE",
              null,
              4,
              "CRA art.14",
              "Tell businesses"));

  private static final List<ReportingStage> SEVERE =
      List.of(
          new ReportingStage(
              "SEVERE_INCIDENT",
              "EARLY_WARNING",
              "AWARE",
              "PT24H",
              1,
              "CRA art.14",
              "Early warning"),
          new ReportingStage(
              "SEVERE_INCIDENT", "NOTIFICATION", "AWARE", "PT72H", 2, "CRA art.14", "Notification"),
          new ReportingStage(
              "SEVERE_INCIDENT",
              "FINAL_REPORT",
              "NOTIFIED",
              "P1M",
              3,
              "CRA art.14",
              "Final report"),
          new ReportingStage(
              "SEVERE_INCIDENT",
              "TENANT_NOTICE",
              "AWARE",
              null,
              4,
              "CRA art.14",
              "Tell businesses"));

  private static final List<ReportingStage> BREACH =
      List.of(
          new ReportingStage(
              "PERSONAL_DATA_BREACH",
              "TENANT_NOTICE",
              "AWARE",
              null,
              1,
              "GDPR art.33(2)",
              "Tell businesses"));

  private static SecurityIncident incident(String kind) {
    return new SecurityIncident(ID, kind, "t", "s", AWARE, AWARE, null, true, List.of());
  }

  private static IncidentEvent event(String kind, Instant at) {
    return new IncidentEvent(Ids.newId(), ID, kind, at, at, null, null, null);
  }

  private static StageStatus stage(List<StageStatus> stages, String name) {
    return stages.stream().filter(s -> s.stage().equals(name)).findFirst().orElseThrow();
  }

  private static String code(Runnable r) {
    return assertThrows(ApiException.class, r::run).code();
  }

  @Test
  @DisplayName("A vulnerability's clocks run from awareness; the final report waits on a measure")
  void vulnerabilityClocks() {
    var i = incident("EXPLOITED_VULNERABILITY");
    List<StageStatus> at1h =
        IncidentRules.stages(i, List.of(), VULNERABILITY, AWARE.plus(Duration.ofHours(1)));
    assertEquals(AWARE.plus(Duration.ofHours(24)), stage(at1h, "EARLY_WARNING").dueAt());
    assertEquals("DUE", stage(at1h, "EARLY_WARNING").state());
    assertEquals(AWARE.plus(Duration.ofHours(72)), stage(at1h, "NOTIFICATION").dueAt());
    assertEquals("WAITING", stage(at1h, "FINAL_REPORT").state());
    assertNull(stage(at1h, "FINAL_REPORT").dueAt());
    assertEquals("NO_DEADLINE", stage(at1h, "TENANT_NOTICE").state());

    // A second past the 24 hours is overdue; recorded late, it is still done.
    Instant late = AWARE.plus(Duration.ofHours(24)).plusSeconds(1);
    assertEquals(
        "OVERDUE",
        stage(IncidentRules.stages(i, List.of(), VULNERABILITY, late), "EARLY_WARNING").state());
    var events =
        List.of(
            event("EARLY_WARNING_SENT", late),
            event("MITIGATION_AVAILABLE", AWARE.plus(Duration.ofDays(2))));
    List<StageStatus> after = IncidentRules.stages(i, events, VULNERABILITY, late);
    assertEquals("DONE", stage(after, "EARLY_WARNING").state());
    assertEquals(late, stage(after, "EARLY_WARNING").doneAt());
    assertEquals(AWARE.plus(Duration.ofDays(16)), stage(after, "FINAL_REPORT").dueAt());
    // The soonest unfinished deadline is the notification at 72 hours.
    assertEquals("NOTIFICATION", IncidentRules.next(after).stage());
  }

  @Test
  @DisplayName("A period is added on the calendar, a duration exactly")
  void periodsAndDurations() {
    assertEquals(
        Instant.parse("2027-02-28T10:00:00Z"),
        IncidentRules.plus(Instant.parse("2027-01-31T10:00:00Z"), "P1M"));
    assertEquals(Instant.parse("2026-09-28T08:00:00Z"), IncidentRules.plus(AWARE, "P14D"));
    assertEquals(Instant.parse("2026-09-17T08:00:00Z"), IncidentRules.plus(AWARE, "PT72H"));
  }

  @Test
  @DisplayName("The final report follows the notification, and for a vulnerability a measure too")
  void finalReportOrder() {
    var v = incident("EXPLOITED_VULNERABILITY");
    Instant now = AWARE.plus(Duration.ofDays(3));
    assertEquals(
        "INCIDENT_FINAL_REPORT_TOO_EARLY",
        code(
            () -> IncidentRules.check(v, List.of(), VULNERABILITY, "FINAL_REPORT_SENT", now, now)));
    var notified = List.of(event("NOTIFICATION_SENT", AWARE.plusSeconds(60)));
    assertEquals(
        "INCIDENT_FINAL_REPORT_TOO_EARLY",
        code(() -> IncidentRules.check(v, notified, VULNERABILITY, "FINAL_REPORT_SENT", now, now)));
    var both =
        List.of(
            event("NOTIFICATION_SENT", AWARE.plusSeconds(60)),
            event("MITIGATION_AVAILABLE", AWARE.plusSeconds(120)));
    assertDoesNotThrow(
        () -> IncidentRules.check(v, both, VULNERABILITY, "FINAL_REPORT_SENT", now, now));

    var s = incident("SEVERE_INCIDENT");
    assertDoesNotThrow(
        () -> IncidentRules.check(s, notified, SEVERE, "FINAL_REPORT_SENT", now, now));
    assertEquals(
        AWARE.plusSeconds(60).plus(Duration.ofDays(30)),
        stage(IncidentRules.stages(s, notified, SEVERE, now), "FINAL_REPORT").dueAt());
    assertEquals(
        "INCIDENT_EVENT_NOT_APPLICABLE",
        code(() -> IncidentRules.check(s, List.of(), SEVERE, "MITIGATION_AVAILABLE", now, now)));
  }

  @Test
  @DisplayName(
      "Each stage once; nothing a kind does not have; nothing before awareness or in the future")
  void refusals() {
    var v = incident("EXPLOITED_VULNERABILITY");
    Instant now = AWARE.plus(Duration.ofHours(5));
    var warned = List.of(event("EARLY_WARNING_SENT", AWARE.plusSeconds(60)));
    assertEquals(
        "INCIDENT_STAGE_ALREADY_RECORDED",
        code(() -> IncidentRules.check(v, warned, VULNERABILITY, "EARLY_WARNING_SENT", now, now)));
    assertEquals(
        "INCIDENT_EVENT_UNKNOWN",
        code(() -> IncidentRules.check(v, List.of(), VULNERABILITY, "PANICKED", now, now)));
    assertEquals(
        "INCIDENT_EVENT_BEFORE_AWARE",
        code(
            () ->
                IncidentRules.check(
                    v, List.of(), VULNERABILITY, "NOTE", AWARE.minusSeconds(1), now)));
    assertEquals(
        "INCIDENT_EVENT_IN_FUTURE",
        code(
            () ->
                IncidentRules.check(
                    v, List.of(), VULNERABILITY, "NOTE", now.plus(Duration.ofMinutes(6)), now)));
    assertDoesNotThrow(
        () ->
            IncidentRules.check(
                v, List.of(), VULNERABILITY, "NOTE", now.plus(Duration.ofMinutes(4)), now));
    assertEquals(
        "INCIDENT_EVENT_NOT_APPLICABLE",
        code(() -> IncidentRules.check(v, List.of(), VULNERABILITY, "TENANTS_NOTIFIED", now, now)));

    var b = incident("PERSONAL_DATA_BREACH");
    assertEquals(
        "INCIDENT_EVENT_NOT_APPLICABLE",
        code(() -> IncidentRules.check(b, List.of(), BREACH, "EARLY_WARNING_SENT", now, now)));
    // Notes are never refused on an open incident, however many.
    var notes = new ArrayList<IncidentEvent>(List.of(event("NOTE", now), event("NOTE", now)));
    assertDoesNotThrow(() -> IncidentRules.check(b, notes, BREACH, "NOTE", now, now));
  }

  @Test
  @DisplayName("Closed only when every stage is done, and nothing after")
  void closing() {
    var b = incident("PERSONAL_DATA_BREACH");
    Instant now = AWARE.plus(Duration.ofHours(2));
    assertEquals(
        "INCIDENT_STAGES_OUTSTANDING",
        code(() -> IncidentRules.check(b, List.of(), BREACH, "CLOSED", now, now)));
    var told = List.of(event("TENANTS_NOTIFIED", AWARE.plusSeconds(60)));
    assertDoesNotThrow(() -> IncidentRules.check(b, told, BREACH, "CLOSED", now, now));
    var closed = List.of(told.get(0), event("CLOSED", now));
    assertEquals(
        "INCIDENT_CLOSED", code(() -> IncidentRules.check(b, closed, BREACH, "NOTE", now, now)));
  }
}
