package com.storeql.tenant.mapper;

import com.storeql.tenant.domain.Workforce;
import com.storeql.tenant.domain.Workforce.AttendanceDay;
import com.storeql.tenant.domain.Workforce.Entry;
import com.storeql.tenant.domain.Workforce.Rest;
import com.storeql.tenant.domain.Workforce.Shift;
import com.storeql.tenant.dto.WorkforceDtos;
import com.storeql.tenant.service.WorkforceService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The roster and the clock on the wire. Hours go out as hours, which is how a rota is read. */
public final class WorkforceMappers {

  private WorkforceMappers() {}

  public static WorkforceDtos.ShiftResponse toDto(Shift s) {
    return new WorkforceDtos.ShiftResponse(
        s.id().toString(),
        s.storeId().toString(),
        s.userId().toString(),
        text(s.startsAt()),
        text(s.endsAt()),
        s.duty(),
        s.status(),
        s.note(),
        s.cancelledReason(),
        Workforce.hours(s.length()),
        text(s.createdAt()));
  }

  public static WorkforceDtos.RosterResponse toDto(WorkforceService.Roster r) {
    List<WorkforceDtos.ConcernResponse> concerns = new ArrayList<>();
    r.concerns()
        .forEach(
            (userId, list) -> {
              for (Workforce.Concern c : list) {
                concerns.add(
                    new WorkforceDtos.ConcernResponse(
                        userId.toString(),
                        c.code(),
                        c.detail(),
                        c.severity(),
                        c.source(),
                        c.citation()));
              }
            });
    return new WorkforceDtos.RosterResponse(
        r.shifts().stream().map(WorkforceMappers::toDto).toList(), concerns);
  }

  public static WorkforceDtos.EntryResponse toDto(Entry e) {
    return new WorkforceDtos.EntryResponse(
        e.id().toString(),
        e.storeId().toString(),
        e.userId().toString(),
        text(e.shiftId()),
        text(e.clockedInAt()),
        text(e.clockedOutAt()),
        e.source(),
        e.worked() == null ? null : Workforce.hours(e.worked()),
        Workforce.hours(e.unpaidBreaks()),
        e.adjustedReason(),
        text(e.supersedes()),
        text(e.supersededBy()),
        e.breaks().stream().map(WorkforceMappers::toDto).toList());
  }

  public static List<WorkforceDtos.EntryResponse> entries(List<Entry> all) {
    return all.stream().map(WorkforceMappers::toDto).toList();
  }

  private static WorkforceDtos.BreakResponse toDto(Rest r) {
    return new WorkforceDtos.BreakResponse(
        text(r.startedAt()), text(r.endedAt()), r.kind(), r.paid());
  }

  public static WorkforceDtos.AttendanceResponse toDto(AttendanceDay d) {
    return new WorkforceDtos.AttendanceResponse(
        d.day().toString(),
        d.userId().toString(),
        d.storeId().toString(),
        Workforce.hours(Duration.ofMinutes(d.planned())),
        Workforce.hours(Duration.ofMinutes(d.worked())),
        d.entries(),
        d.openEntry(),
        d.lateByMinutes(),
        d.absent(),
        d.unplanned());
  }

  public static List<WorkforceDtos.AttendanceResponse> attendance(List<AttendanceDay> all) {
    return all.stream().map(WorkforceMappers::toDto).toList();
  }

  public static WorkforceDtos.PayRateResponse toDto(Workforce.PayRate r) {
    return new WorkforceDtos.PayRateResponse(
        r.id().toString(),
        r.userId().toString(),
        r.effectiveFrom().toString(),
        r.hourlyRate().toPlainString(),
        r.currency(),
        r.note(),
        text(r.createdAt()));
  }

  public static List<WorkforceDtos.PayRateResponse> rates(List<Workforce.PayRate> all) {
    return all.stream().map(WorkforceMappers::toDto).toList();
  }

  private static String text(Instant at) {
    return at == null ? null : at.toString();
  }

  private static String text(UUID id) {
    return id == null ? null : id.toString();
  }
}
