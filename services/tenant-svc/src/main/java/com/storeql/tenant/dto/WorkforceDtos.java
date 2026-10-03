package com.storeql.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The roster and the clock (store operations & workforce). */
public final class WorkforceDtos {

  private WorkforceDtos() {}

  @Schema(name = "WorkShift")
  public record ShiftResponse(
      String id,
      String storeId,
      String userId,
      String startsAt,
      String endsAt,
      @Schema(description = "What they are rostered to do, in the business's own words.")
          String duty,
      @Schema(description = "PLANNED, PUBLISHED or CANCELLED.") String status,
      String note,
      String cancelledReason,
      @Schema(description = "Hours, to one decimal place — how a rota is read.") String hours,
      String createdAt) {}

  @Schema(name = "PlanShiftRequest")
  public record PlanShiftRequest(
      @NotBlank String storeId,
      @Schema(description = "Whose shift it is.") @NotBlank String userId,
      @NotBlank @Size(max = 40) String startsAt,
      @NotBlank @Size(max = 40) String endsAt,
      @Size(max = 120) String duty,
      @Size(max = 500) String note) {}

  @Schema(name = "CancelShiftRequest")
  public record CancelShiftRequest(
      @Schema(
              description =
                  "Why it was called off. Required: a rota that changed with no reason is what a"
                      + " week's dispute is made of.")
          @NotBlank
          @Size(max = 300)
          String reason) {}

  /** Something worth saying about one person's rota, with the instrument that asks for it. */
  @Schema(name = "RosterConcern")
  public record ConcernResponse(
      String userId,
      @Schema(description = "DAILY_REST_SHORT, BREAK_EXPECTED or SHIFTS_OVERLAP.") String code,
      String detail,
      @Schema(description = "ADVISORY or UNLAWFUL, as the rule's data says; never refused here.")
          String severity,
      @Schema(description = "LAW (a rule of law, with its citation) or ROSTER (true anywhere).")
          String source,
      @Schema(description = "The instrument the rule comes from; absent for ROSTER.")
          String citation) {}

  @Schema(name = "Roster")
  public record RosterResponse(
      List<ShiftResponse> shifts,
      @Schema(
              description =
                  "Flagged, never refused: the Working Time Directive is implemented member state by"
                      + " member state, and an employer with a derogation may roster against it.")
          List<ConcernResponse> concerns) {}

  @Schema(name = "TimeEntryBreak")
  public record BreakResponse(
      String startedAt,
      String endedAt,
      @Schema(description = "REST or MEAL.") String kind,
      boolean paid) {}

  @Schema(name = "TimeEntry")
  public record EntryResponse(
      String id,
      String storeId,
      String userId,
      @Schema(description = "The rostered shift this answers, when there was one.") String shiftId,
      String clockedInAt,
      String clockedOutAt,
      @Schema(description = "CLOCK when the person did it, MANAGER when it was written for them.")
          String source,
      @Schema(description = "Null while the entry is open: an open entry is not a zero-hour day.")
          String hoursWorked,
      @Schema(description = "Unpaid break time that came off the hours.") String unpaidBreakHours,
      String adjustedReason,
      @Schema(description = "The entry this correction replaced.") String supersedes,
      @Schema(description = "Set once a correction replaced this one.") String supersededBy,
      List<BreakResponse> breaks) {

    public EntryResponse {
      breaks = breaks == null ? List.of() : List.copyOf(breaks);
    }
  }

  @Schema(name = "ClockInRequest")
  public record ClockInRequest(
      @NotBlank String storeId,
      @Schema(description = "The rostered shift being worked, when there is one.")
          String shiftId) {}

  @Schema(name = "StartBreakRequest")
  public record StartBreakRequest(
      @Schema(description = "REST or MEAL.") @Size(max = 10) String kind,
      @Schema(
              description =
                  "Whether this break is paid. The employer's arrangement, kept rather than decided"
                      + " here — unpaid breaks come off the hours and paid ones do not.")
          boolean paid) {}

  @Schema(name = "AdjustTimeEntryRequest")
  public record AdjustRequest(
      @Schema(description = "The corrected clock-in, or left out to keep it.") @Size(max = 40)
          String clockedInAt,
      @Schema(description = "The corrected clock-out — the forgotten one, usually.") @Size(max = 40)
          String clockedOutAt,
      @Schema(description = "Why. Required: a correction without one is an edit with extra steps.")
          @NotBlank
          @Size(max = 300)
          String reason) {}

  @Schema(name = "PayRate")
  public record PayRateResponse(
      String id,
      String userId,
      @Schema(
              description =
                  "The day the rate takes effect; the past keeps the rate it was worked at.")
          String effectiveFrom,
      String hourlyRate,
      String currency,
      String note,
      String createdAt) {}

  @Schema(name = "AddPayRateRequest")
  public record AddPayRateRequest(
      @NotBlank String userId,
      @Schema(description = "The day it takes effect; today when left out.") @Size(max = 10)
          String effectiveFrom,
      @Schema(description = "What an hour costs. Zero is meaningful; less than zero is not.")
          @NotBlank
          @Size(max = 20)
          String hourlyRate,
      @Schema(description = "The business's own currency when left out.") @Size(max = 3)
          String currency,
      @Size(max = 300) String note) {}

  /**
   * One person's day, planned against worked.
   *
   * @param absent rostered and nothing clocked — the case the report exists for
   * @param unplanned worked with nothing rostered, as much a management fact as an absence
   * @param lateByMinutes how late the first clock-in was against the roster; negative for early
   */
  @Schema(name = "AttendanceDay")
  public record AttendanceResponse(
      String day,
      String userId,
      String storeId,
      String plannedHours,
      String workedHours,
      @Schema(
              description =
                  "How many times they clocked on that day. Zero with a plan is an absence.")
          int entries,
      boolean onTheClock,
      Long lateByMinutes,
      boolean absent,
      boolean unplanned) {}
}
