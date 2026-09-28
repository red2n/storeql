package com.storeql.purchase.mapper;

import com.storeql.purchase.domain.Rfq;
import com.storeql.purchase.dto.RfqDtos;
import com.storeql.purchase.repo.RfqRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Requests for quotation onto the wire. */
public final class RfqMappers {

  private RfqMappers() {}

  public static RfqDtos.RfqSummaryResponse toDto(RfqRepository.Summary s) {
    Rfq.Header h = s.header();
    return new RfqDtos.RfqSummaryResponse(
        h.id(),
        h.reference(),
        h.title(),
        h.storeId(),
        h.status(),
        h.neededBy(),
        h.closesOn(),
        s.lines(),
        s.suppliers(),
        s.quotes(),
        h.createdAt());
  }

  /**
   * @param grades each supplier's scorecard grade, by supplier id; absent means nothing to judge
   */
  public static RfqDtos.RfqResponse toDto(Rfq.Detail d, Map<UUID, String> grades) {
    Rfq.Header h = d.header();
    Map<UUID, UUID> variantByLine = new HashMap<>();
    for (Rfq.Line l : d.lines()) variantByLine.put(l.id(), l.variantId());
    Map<UUID, String> nameBySupplier = new HashMap<>();
    for (Rfq.Bid b : d.bids()) nameBySupplier.put(b.supplierId(), b.supplierName());

    List<RfqDtos.RfqBidResponse> bids = new ArrayList<>(d.bids().size());
    for (Rfq.Bid b : d.bids()) {
      List<RfqDtos.RfqPriceResponse> prices = new ArrayList<>();
      for (Rfq.Line l : d.lines()) {
        if (b.prices().containsKey(l.id())) {
          prices.add(new RfqDtos.RfqPriceResponse(l.variantId(), b.prices().get(l.id())));
        }
      }
      bids.add(
          new RfqDtos.RfqBidResponse(
              b.supplierId(),
              b.supplierName(),
              b.status(),
              b.currency(),
              b.leadTimeDays(),
              b.validUntil(),
              b.notes(),
              b.quotedAt(),
              grades.get(b.supplierId()),
              prices));
    }

    Rfq.Comparison c = d.comparison();
    List<RfqDtos.RfqLineComparisonResponse> lines = new ArrayList<>(c.lines().size());
    for (Rfq.LineComparison lc : c.lines()) {
      lines.add(
          new RfqDtos.RfqLineComparisonResponse(
              lc.variantId(),
              lc.qty(),
              lc.prices().stream()
                  .map(
                      p ->
                          new RfqDtos.RfqPriceComparisonResponse(
                              p.supplierId(),
                              p.unitPrice(),
                              p.currency(),
                              p.homeUnitPrice(),
                              p.lineTotal(),
                              p.homeLineTotal(),
                              p.lowest()))
                  .toList()));
    }
    List<RfqDtos.RfqBidSummaryResponse> summaries =
        c.bids().stream()
            .map(
                b ->
                    new RfqDtos.RfqBidSummaryResponse(
                        b.supplierId(),
                        nameBySupplier.get(b.supplierId()),
                        b.status(),
                        b.complete(),
                        b.total(),
                        b.currency(),
                        b.homeTotal(),
                        b.rank()))
            .toList();

    Set<UUID> poIds = new LinkedHashSet<>();
    List<RfqDtos.RfqAwardResponse> awards = new ArrayList<>(d.awards().size());
    for (Rfq.Award a : d.awards()) {
      poIds.add(a.poId());
      awards.add(
          new RfqDtos.RfqAwardResponse(
              variantByLine.get(a.lineId()),
              a.supplierId(),
              a.poId(),
              a.unitPrice(),
              a.currency()));
    }
    return new RfqDtos.RfqResponse(
        h.id(),
        h.reference(),
        h.title(),
        h.storeId(),
        h.status(),
        h.neededBy(),
        h.closesOn(),
        h.notes(),
        h.createdAt(),
        h.issuedAt(),
        h.awardedAt(),
        h.cancelledAt(),
        h.cancelledReason(),
        d.lines().stream()
            .map(l -> new RfqDtos.RfqLineResponse(l.id(), l.variantId(), l.qty(), l.notes()))
            .toList(),
        bids,
        new RfqDtos.RfqComparisonResponse(c.homeCurrency(), lines, summaries),
        awards,
        new ArrayList<>(poIds));
  }
}
