package com.storeql.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The wire contract for cross-docking allocations. */
public final class CrossDockDtos {

  private CrossDockDtos() {}

  @Schema(
      name = "AllocationRequest",
      description = "A shop the warehouse serves and how much is for it.")
  public record AllocationRequest(@NotBlank String storeId, @NotNull BigDecimal qty) {}

  @Schema(
      name = "AllocateLineRequest",
      description = "A line's allocations, replacing any it had; an empty list clears them.")
  public record AllocateLineRequest(@NotNull @Valid List<AllocationRequest> allocations) {}

  @Schema(name = "LineAllocationResponse")
  public record LineAllocationResponse(
      UUID id, UUID poLineId, UUID variantId, UUID storeId, BigDecimal qty) {}
}
