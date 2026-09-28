package com.storeql.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The business's exchange rates on the wire (03.x). */
public final class FxDtos {
  private FxDtos() {}

  @Schema(
      name = "SetFxRateRequest",
      description = "A rate: home units per one unit of the currency.")
  public record SetRateRequest(
      @Schema(description = "Home units one unit of the currency buys, e.g. 0.79 GBP per USD.")
          @NotNull
          BigDecimal rate,
      @Schema(description = "The day it applies from, yyyy-MM-dd; today when absent.")
          String effectiveFrom,
      @Schema(description = "Why: the source and the day it was taken.") @NotBlank @Size(max = 500)
          String reason) {}

  @Schema(name = "FxRateResponse")
  public record RateResponse(
      String currency,
      @Schema(description = "Home units per one unit of the currency.") BigDecimal rate,
      String effectiveFrom,
      String reason,
      String setBy,
      String setAt) {}

  @Schema(
      name = "FxRateSheetResponse",
      description = "The home currency and the rate in force today for every other currency kept.")
  public record SheetResponse(
      @Schema(description = "The business's own currency; every rate is into it.") String home,
      List<RateResponse> rates) {}
}
