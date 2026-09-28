package com.storeql.inventory.mapper;

import com.storeql.inventory.domain.Domain.AwaitingOrder;
import com.storeql.inventory.domain.Domain.PickWave;
import com.storeql.inventory.domain.Domain.PickWaveLine;
import com.storeql.inventory.domain.Domain.PutawayRule;
import com.storeql.inventory.domain.Domain.PutawayTask;
import com.storeql.inventory.dto.WaveDtos;

/** Waves and putaway onto the wire. */
public final class WaveMappers {

  private WaveMappers() {}

  public static WaveDtos.AwaitingOrderResponse toDto(AwaitingOrder o) {
    return new WaveDtos.AwaitingOrderResponse(
        o.orderId(),
        o.storeId(),
        o.fulfilmentType(),
        o.confirmedAt(),
        o.waveId(),
        o.lines().stream()
            .map(l -> new WaveDtos.AwaitingLineResponse(l.variantId(), l.qtyOutstanding()))
            .toList());
  }

  public static WaveDtos.PickWaveLineResponse toDto(PickWaveLine l) {
    return new WaveDtos.PickWaveLineResponse(
        l.id(),
        l.walkOrder(),
        l.zoneId(),
        l.batchId(),
        l.batchNo(),
        l.variantId(),
        l.directedQty(),
        l.pickedQty(),
        l.orders().stream()
            .map(a -> new WaveDtos.WaveOrderShareResponse(a.orderId(), a.qty(), a.pickedQty()))
            .toList());
  }

  public static WaveDtos.PickWaveResponse toDto(PickWave w) {
    return new WaveDtos.PickWaveResponse(
        w.id(),
        w.storeId(),
        w.status(),
        w.createdAt(),
        w.completedAt(),
        w.cancelledAt(),
        w.orderCount(),
        w.lines().stream().map(WaveMappers::toDto).toList());
  }

  public static WaveDtos.PutawayRuleResponse toDto(PutawayRule r) {
    return new WaveDtos.PutawayRuleResponse(
        r.id(), r.storeId(), r.variantId(), r.zoneId(), r.createdAt());
  }

  public static WaveDtos.PutawayTaskResponse toDto(PutawayTask t) {
    return new WaveDtos.PutawayTaskResponse(
        t.id(),
        t.storeId(),
        t.batchId(),
        t.batchNo(),
        t.variantId(),
        t.qty(),
        t.suggestedZoneId(),
        t.status(),
        t.placedZoneId(),
        t.placedAt(),
        t.createdAt());
  }
}
