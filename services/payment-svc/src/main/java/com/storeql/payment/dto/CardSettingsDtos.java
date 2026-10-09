package com.storeql.payment.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The till's card rule per store: whether a standalone card machine is allowed there. */
public final class CardSettingsDtos {

  private CardSettingsDtos() {}

  @Schema(name = "StandaloneCardRequest")
  public record StandaloneCardRequest(
      @Schema(
              description =
                  "True lets the store record a card taken on a machine StoreQL does not drive, with"
                      + " that machine's receipt reference, even where it has a registered machine.")
          @NotNull
          Boolean allowed) {}

  @Schema(name = "StandaloneCardResponse")
  public record StandaloneCardResponse(
      String storeId,
      boolean allowed,
      @Schema(description = "Who last changed it; null before anyone has.") String changedBy,
      String changedAt,
      @Schema(description = "CARD tenders at the store in the last 30 days.") long cardTenders30d,
      @Schema(description = "...of which were typed from a standalone machine's receipt.")
          long standaloneTenders30d,
      @Schema(description = "Newest first.") List<Change> changes) {}

  @Schema(name = "StandaloneCardChange")
  public record Change(boolean allowed, String changedBy, String changedAt) {}
}
