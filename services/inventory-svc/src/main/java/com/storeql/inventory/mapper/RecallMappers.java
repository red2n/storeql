package com.storeql.inventory.mapper;

import com.storeql.inventory.domain.Recall.ActiveItem;
import com.storeql.inventory.domain.Recall.Detail;
import com.storeql.inventory.domain.Recall.Header;
import com.storeql.inventory.domain.Recall.HeldBatch;
import com.storeql.inventory.domain.Recall.Scope;
import com.storeql.inventory.domain.Recall.StoreAction;
import com.storeql.inventory.domain.Recall.Summary;
import com.storeql.inventory.dto.RecallDtos.ActiveRecallItemResponse;
import com.storeql.inventory.dto.RecallDtos.HeldBatchResponse;
import com.storeql.inventory.dto.RecallDtos.RecallResponse;
import com.storeql.inventory.dto.RecallDtos.RecallSummaryResponse;
import com.storeql.inventory.dto.RecallDtos.ScopeLineResponse;
import com.storeql.inventory.dto.RecallDtos.StoreActionResponse;
import com.storeql.inventory.dto.RecallDtos.StoreProgressResponse;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Recall domain objects to their DTOs. */
public final class RecallMappers {

  private RecallMappers() {}

  /**
   * Converts a summary to its wire form.
   *
   * @param s the summary to convert
   * @return its API representation
   */
  public static RecallSummaryResponse toSummary(Summary s) {
    Header h = s.header();
    return new RecallSummaryResponse(
        h.id().toString(),
        h.reference(),
        h.kind().name(),
        h.hazard().name(),
        h.source().name(),
        h.status().name(),
        str(h.openedAt()),
        str(h.endedAt()),
        s.scopeLines(),
        s.storesAffected(),
        s.storesOutstanding(),
        s.qtyHeld(),
        s.reach().ordersAffected(),
        s.reach().qtySold());
  }

  /**
   * Converts a recall to its wire form.
   *
   * @param d the recall to convert
   * @return its API representation
   */
  public static RecallResponse toRecall(Detail d) {
    Header h = d.header();
    return new RecallResponse(
        h.id().toString(),
        h.reference(),
        h.kind().name(),
        h.hazard().name(),
        h.reason(),
        h.customerNotice(),
        h.source().name(),
        h.sourceReference(),
        h.status().name(),
        str(h.openedBy()),
        str(h.openedAt()),
        str(h.endedBy()),
        str(h.endedAt()),
        h.endNotes(),
        d.scope().stream().map(RecallMappers::toScopeLine).toList(),
        d.batches().stream().map(RecallMappers::toHeldBatch).toList(),
        d.actions().stream().map(RecallMappers::toStoreAction).toList(),
        stores(d),
        h.remedies().stream().map(Enum::name).sorted().toList(),
        h.singleRemedyReason(),
        h.contactPhone(),
        h.contactUrl(),
        str(h.soldFrom()),
        d.reach().ordersAffected(),
        d.reach().qtySold());
  }

  /**
   * Converts an active item to its wire form.
   *
   * @param i the active item to convert
   * @return its API representation
   */
  public static ActiveRecallItemResponse toActiveItem(ActiveItem i) {
    Scope s = i.scope();
    return new ActiveRecallItemResponse(
        i.recallId().toString(),
        i.reference(),
        i.kind().name(),
        i.hazard().name(),
        i.customerNotice(),
        str(i.openedAt()),
        s.variantId().toString(),
        s.batchNo(),
        str(s.expiryFrom()),
        str(s.expiryTo()),
        str(i.endedAt()),
        // Only an ended recall says how it ended, so the till's list of open ones reads as before.
        i.endedAt() == null ? null : i.status().name());
  }

  /**
   * Converts a store action to its wire form.
   *
   * @param a the store action to convert
   * @return its API representation
   */
  public static StoreActionResponse toStoreAction(StoreAction a) {
    return new StoreActionResponse(
        a.id().toString(),
        a.storeId().toString(),
        a.qtyFound(),
        a.systemQty(),
        a.disposition().name(),
        a.noticeDisplayed(),
        a.notes(),
        str(a.recordedBy()),
        str(a.recordedAt()));
  }

  private static ScopeLineResponse toScopeLine(Scope s) {
    return new ScopeLineResponse(
        s.id().toString(),
        s.variantId().toString(),
        s.batchNo(),
        str(s.expiryFrom()),
        str(s.expiryTo()),
        s.coversEveryPack());
  }

  private static HeldBatchResponse toHeldBatch(HeldBatch b) {
    var release = b.release();
    return new HeldBatchResponse(
        b.batchId().toString(),
        b.storeId().toString(),
        b.variantId().toString(),
        b.batchNo(),
        str(b.expiryDate()),
        b.match().name(),
        b.qtyAtQuarantine(),
        b.remainingQty(),
        b.quarantinedOn().name(),
        str(b.quarantinedAt()),
        release != null,
        release == null ? null : release.reason(),
        release == null ? null : str(release.releasedBy()),
        release == null ? null : str(release.releasedAt()));
  }

  /**
   * Every store the recall reached — by holding its stock, or by a store recording a find — with
   * the quantity counted in its latest action, since a later count supersedes an earlier one.
   */
  private static List<StoreProgressResponse> stores(Detail d) {
    Map<UUID, BigDecimal> held = new LinkedHashMap<>();
    for (HeldBatch b : d.batches()) {
      if (b.isHeld()) {
        held.merge(b.storeId(), b.qtyAtQuarantine(), BigDecimal::add);
      }
    }
    Map<UUID, BigDecimal> found = new LinkedHashMap<>();
    for (StoreAction a : d.actions()) {
      held.putIfAbsent(a.storeId(), BigDecimal.ZERO);
      found.put(a.storeId(), a.qtyFound());
    }
    Set<UUID> outstanding = d.outstandingStores();
    return held.entrySet().stream()
        .map(
            e ->
                new StoreProgressResponse(
                    e.getKey().toString(),
                    e.getValue(),
                    found.get(e.getKey()),
                    outstanding.contains(e.getKey())))
        .toList();
  }

  private static String str(Object value) {
    return Objects.toString(value, null);
  }
}
