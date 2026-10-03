package com.storeql.tenant.dto;

import jakarta.json.bind.annotation.JsonbTypeDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The work a shop does every day, and the record that it was done (store operations & workforce).
 */
public final class StoreTaskDtos {

  private StoreTaskDtos() {}

  @Schema(name = "TaskLine", description = "One line of a checklist.")
  public record LineRequest(@NotBlank @Size(max = 300) String text, Boolean required) {}

  @Schema(name = "TaskListLine")
  public record LineResponse(int position, String text, boolean required) {}

  @Schema(
      name = "TaskListRequest",
      description =
          "A piece of work a shop does on a schedule. With lines it is a checklist, finished when every"
              + " required line is ticked; without them it is a single task.")
  public record TemplateRequest(
      @NotBlank @Size(max = 160) String title,
      @Size(max = 2000) String instructions,
      @Schema(description = "OPENING, CLOSING, DAILY, WEEKLY or AD_HOC.") @NotBlank String kind,
      @Schema(description = "One store, or omit for every store the business has.") String storeId,
      @Schema(description = "ISO day numbers, 1 = Monday; empty means every day.")
          @JsonbTypeDeserializer(WholeNumbers.ExactIntSet.class)
          Set<Integer> daysOfWeek,
      @Schema(description = "When it falls due, HH:mm, on the store's own clock.")
          @NotBlank
          @Size(max = 8)
          String dueTime,
      @Schema(
              description =
                  "Minutes after it falls due before it counts as missed; 60 when omitted.")
          @Min(0)
          @Max(1440)
          @JsonbTypeDeserializer(WholeNumbers.ExactInt.class)
          Integer graceMinutes,
      @Schema(description = "Whose job it is, as staff roles are named; omit for anybody on shift.")
          @Size(max = 40)
          String role,
      @Schema(description = "A required list makes the day incomplete until done or explained.")
          Boolean required,
      @Valid @Size(max = 60) List<LineRequest> lines) {}

  @Schema(name = "TaskList")
  public record TemplateResponse(
      String id,
      String storeId,
      String title,
      String instructions,
      String kind,
      List<Integer> daysOfWeek,
      String dueTime,
      int graceMinutes,
      String role,
      boolean required,
      @Schema(description = "ACTIVE or WITHDRAWN.") String status,
      @Schema(description = "True when the list has lines.") boolean checklist,
      List<LineResponse> lines,
      String createdAt,
      String withdrawnAt) {

    public TemplateResponse {
      daysOfWeek = daysOfWeek == null ? List.of() : List.copyOf(daysOfWeek);
      lines = lines == null ? List.of() : List.copyOf(lines);
    }
  }

  @Schema(name = "TaskItem", description = "One line of a task on the day, ticked or not.")
  public record ItemResponse(
      int position, String text, boolean required, String tickedAt, String tickedBy) {}

  @Schema(name = "Task", description = "One occurrence of a list: this store, this business date.")
  public record InstanceResponse(
      String id,
      String storeId,
      String listId,
      String businessDate,
      String dueAt,
      @Schema(description = "OPEN, DONE, SKIPPED or MISSED.") String status,
      String title,
      String kind,
      String role,
      boolean required,
      @Schema(description = "Done after it fell due — late, which is not missed.") boolean late,
      @Schema(description = "Required lines still to tick before the list can be finished.")
          long outstanding,
      String completedAt,
      String completedBy,
      String skippedReason,
      String note,
      List<ItemResponse> items) {

    public InstanceResponse {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  @Schema(name = "TaskDay", description = "What a store's day came to.")
  public record DayResponse(
      String businessDate,
      String storeId,
      int total,
      int required,
      int done,
      int late,
      int skipped,
      int missed,
      int open,
      @Schema(description = "Nothing required is still open or was missed.") boolean settled) {}

  @Schema(name = "RaiseTaskRequest", description = "A list put on a store's day by hand.")
  public record RaiseRequest(
      @NotBlank String listId,
      @NotBlank String storeId,
      @Schema(description = "The store's business date; today on the store's clock when omitted.")
          @Size(max = 10)
          String businessDate) {}

  @Schema(name = "GenerateDayRequest")
  public record GenerateRequest(@NotBlank String storeId, @Size(max = 10) String businessDate) {}

  @Schema(name = "CompleteTaskRequest")
  public record CompleteRequest(@Size(max = 500) String note) {}

  @Schema(name = "SkipTaskRequest")
  public record SkipRequest(@NotBlank @Size(max = 500) String reason) {}
}
