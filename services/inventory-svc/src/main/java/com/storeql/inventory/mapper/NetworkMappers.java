package com.storeql.inventory.mapper;

import com.storeql.inventory.domain.Domain.TransferProposalRun;
import com.storeql.inventory.dto.NetworkDtos;
import com.storeql.inventory.service.InventoryService.TransferOrderWithLines;
import com.storeql.inventory.service.NetworkService;
import java.util.List;

/** Converts the network and its runs to their wire form. */
public final class NetworkMappers {

  private NetworkMappers() {}

  public static NetworkDtos.ServingResponse toDto(NetworkService.ShopServing s) {
    return new NetworkDtos.ServingResponse(
        s.serving().id(),
        s.serving().storeId(),
        s.serving().warehouseId(),
        s.serving().leadTimeDays(),
        s.direct(),
        s.serving().updatedAt());
  }

  public static NetworkDtos.SourcingResponse toDto(NetworkService.Sourcing s) {
    return new NetworkDtos.SourcingResponse(
        s.storeId(),
        s.warehouse(),
        s.servedBy(),
        s.leadTimeDays(),
        s.direct(),
        s.shops(),
        s.demand().stream()
            .map(
                d ->
                    new NetworkDtos.ServedDemandResponse(
                        d.variantId(), d.next28(), d.avgDailyDemand(), d.committed(), d.shops()))
            .toList());
  }

  public static NetworkDtos.NeedShareResponse toDto(NetworkService.NeedShare n) {
    return new NetworkDtos.NeedShareResponse(n.storeId(), n.need(), n.qty());
  }

  public static NetworkDtos.TransferProposalRunResponse toDto(
      TransferProposalRun r, List<TransferOrderWithLines> transfers) {
    return new NetworkDtos.TransferProposalRunResponse(
        r.id(),
        r.warehouseId(),
        r.runAt(),
        r.coverDays(),
        r.shops(),
        r.transfers(),
        r.lines(),
        r.shortLines(),
        r.transferIds(),
        transfers.stream().map(t -> Mappers.toTransferOrder(t.order(), t.lines())).toList());
  }
}
