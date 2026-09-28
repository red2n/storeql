package com.storeql.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The wire contract for wave picking and directed putaway. */
public final class WaveDtos {

  private WaveDtos() {}

  @Schema(name = "AwaitingLineResponse")
  public record AwaitingLineResponse(UUID variantId, BigDecimal qtyOutstanding) {}

  @Schema(
      name = "AwaitingOrderResponse",
      description = "A confirmed online order waiting to be picked.")
  public record AwaitingOrderResponse(
      UUID orderId,
      UUID storeId,
      String fulfilmentType,
      Instant confirmedAt,
      @Schema(description = "The open wave the order is in, or null.") UUID waveId,
      List<AwaitingLineResponse> lines) {}

  @Schema(
      name = "BuildWaveRequest",
      description = "Gather the orders waiting at a store into one wave.")
  public record BuildWaveRequest(
      @NotBlank String storeId,
      @Schema(description = "The orders to gather; every order waiting at the store when omitted.")
          List<String> orderIds) {}

  @Schema(name = "PickLineRequest")
  public record PickLineRequest(
      @NotBlank String lineId, @NotNull @DecimalMin("0") BigDecimal pickedQty) {}

  @Schema(
      name = "RecordPicksRequest",
      description = "What was picked per line, at most what was directed.")
  public record RecordPicksRequest(@NotNull @Valid List<PickLineRequest> lines) {}

  @Schema(name = "WaveOrderShareResponse", description = "How much of a line one order gets.")
  public record WaveOrderShareResponse(UUID orderId, BigDecimal qty, BigDecimal pickedQty) {}

  @Schema(name = "PickWaveLineResponse", description = "One batch to pick from, in walk order.")
  public record PickWaveLineResponse(
      UUID id,
      int walkOrder,
      UUID zoneId,
      UUID batchId,
      String batchNo,
      UUID variantId,
      BigDecimal directedQty,
      BigDecimal pickedQty,
      List<WaveOrderShareResponse> orders) {}

  @Schema(name = "PickWaveResponse")
  public record PickWaveResponse(
      UUID id,
      UUID storeId,
      @Schema(description = "OPEN, COMPLETED or CANCELLED.") String status,
      Instant createdAt,
      Instant completedAt,
      Instant cancelledAt,
      int orderCount,
      List<PickWaveLineResponse> lines) {}

  @Schema(
      name = "PutawayRuleRequest",
      description =
          "Where a product goes when it arrives with no zone; no variant means the store default.")
  public record PutawayRuleRequest(
      @NotBlank String storeId, String variantId, @NotBlank String zoneId) {}

  @Schema(name = "PutawayRuleResponse")
  public record PutawayRuleResponse(
      UUID id, UUID storeId, UUID variantId, UUID zoneId, Instant createdAt) {}

  @Schema(name = "PlaceRequest", description = "The zone the batch was put in.")
  public record PlaceRequest(String zoneId) {}

  @Schema(
      name = "PutawayTaskResponse",
      description = "A batch that arrived with no zone and no rule to place it.")
  public record PutawayTaskResponse(
      UUID id,
      UUID storeId,
      UUID batchId,
      @Schema(description = "The batch's number, as printed on its label; absent if it has none.")
          String batchNo,
      UUID variantId,
      BigDecimal qty,
      UUID suggestedZoneId,
      String status,
      UUID placedZoneId,
      Instant placedAt,
      Instant createdAt) {}
}
