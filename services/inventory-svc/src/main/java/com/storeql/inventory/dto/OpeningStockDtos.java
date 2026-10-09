package com.storeql.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Opening stock: what a business already holds when it starts (catalogue import). */
public final class OpeningStockDtos {

  private OpeningStockDtos() {}

  @Schema(
      name = "OpeningStockRequest",
      description =
          "Opens a store's stock of up to 500 items, once each. Idempotent by job: the same job"
              + " asking again gets the original answer; another job asking for an item already"
              + " opened gets ALREADY_OPENED for that line and nothing is written.")
  public record OpeningStockRequest(
      @Schema(description = "The import job this opening belongs to.") @NotBlank String jobId,
      @Schema(description = "The store whose shelves hold the stock.") @NotBlank String storeId,
      @NotEmpty @Valid List<Line> lines) {}

  @Schema(name = "OpeningStockLine")
  public record Line(
      @NotBlank String variantId,
      @Schema(description = "Quantity on hand, to three places.")
          @NotNull
          @Positive
          @Fits(integer = 15, fraction = 3)
          BigDecimal qty,
      @Schema(
              description =
                  "Unit cost in the business's currency, to four places; absent when not held.")
          @PositiveOrZero
          @Fits(integer = 14, fraction = 4)
          BigDecimal unitCost,
      @Schema(description = "The last day of sale, ISO date, if perishable.") String expiryDate,
      @Schema(description = "The lot the stock carries, so a recall can find it.") @Size(max = 64)
          String batchNo) {}

  @Schema(name = "OpeningStockResult")
  public record OpeningStockResult(
      int loaded, int replayed, int alreadyOpened, List<LineResult> lines) {}

  @Schema(name = "OpeningStockLineResult")
  public record LineResult(
      String variantId,
      @Schema(
              description =
                  "LOADED, REPLAYED (this job did it before) or ALREADY_OPENED (another"
                      + " job did).")
          String outcome,
      String batchId,
      @Schema(description = "A recall holds this stock; it is not for sale.") boolean held) {}

  @Schema(name = "OpeningStockSummary", description = "What a job opened, by store.")
  public record OpeningStockSummary(String jobId, List<StoreTotals> stores) {}

  @Schema(name = "OpeningStockStoreTotals")
  public record StoreTotals(
      String storeId,
      int lines,
      String qty,
      @Schema(description = "Quantity times unit cost over the costed lines, unrounded.")
          String value,
      @Schema(description = "Lines loaded with no cost held; they are left out of the value.")
          int uncostedLines) {}
}
