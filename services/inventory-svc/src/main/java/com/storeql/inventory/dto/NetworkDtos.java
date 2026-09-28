package com.storeql.inventory.dto;

import com.storeql.inventory.dto.Dtos.TransferOrderResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The wire contract for depot / DC replenishment. */
public final class NetworkDtos {

  private NetworkDtos() {}

  @Schema(
      name = "ServingRequest",
      description = "Which warehouse serves a shop, and how many days a delivery takes.")
  public record ServingRequest(
      @NotBlank String storeId, @NotBlank String warehouseId, @NotNull Integer leadTimeDays) {}

  @Schema(name = "ServingResponse", description = "A shop, its warehouse and what it buys direct.")
  public record ServingResponse(
      UUID id,
      UUID storeId,
      UUID warehouseId,
      int leadTimeDays,
      @Schema(description = "Products the shop buys direct from its supplier.") List<UUID> direct,
      Instant updatedAt) {}

  @Schema(name = "ServedDemandResponse")
  public record ServedDemandResponse(
      UUID variantId,
      @Schema(description = "What the shops served are expected to sell over 28 days.")
          BigDecimal next28,
      @Schema(description = "Their average daily demand, summed.") BigDecimal avgDailyDemand,
      @Schema(description = "Already promised to shops and not yet shipped.") BigDecimal committed,
      int shops) {}

  @Schema(
      name = "SourcingResponse",
      description =
          "How a store is supplied: a shop's warehouse and what it buys direct, or a warehouse's"
              + " shops and what they are expected to need.")
  public record SourcingResponse(
      UUID storeId,
      boolean warehouse,
      UUID servedBy,
      Integer leadTimeDays,
      List<UUID> direct,
      List<UUID> shops,
      List<ServedDemandResponse> demand) {}

  @Schema(
      name = "TransferProposalRequest",
      description = "Propose transfers to a warehouse's shops.")
  public record TransferProposalRequest(
      @NotBlank String warehouseId,
      @Schema(description = "Days of cover beyond the lead time; 7 when omitted.")
          Integer coverDays) {}

  @Schema(name = "TransferProposalRunResponse")
  public record TransferProposalRunResponse(
      UUID id,
      UUID warehouseId,
      Instant runAt,
      int coverDays,
      int shops,
      int transfers,
      int lines,
      @Schema(description = "Lines cut because the warehouse was short.") int shortLines,
      List<UUID> transferIds,
      @Schema(description = "The DRAFT transfers raised, with a reason on every line.")
          List<TransferOrderResponse> transferOrders) {}

  @Schema(
      name = "NeedShareResponse",
      description = "A shop's need for a product now, and its fair share of the quantity asked.")
  public record NeedShareResponse(UUID storeId, BigDecimal need, BigDecimal qty) {}

  @Schema(
      name = "OwedResponse",
      description = "What a purchase order still owes a shop of a product, to cross the dock.")
  public record OwedResponse(UUID warehouseId, UUID storeId, UUID variantId, BigDecimal qty) {}

  @Schema(name = "StoreStockResponse")
  public record StoreStockResponse(UUID storeId, UUID variantId, BigDecimal available) {}

  @Schema(
      name = "StockByStoreResponse",
      description = "What each store can give of the products asked, and which are dropship.")
  public record StockByStoreResponse(List<StoreStockResponse> levels, List<UUID> dropship) {}
}
