package com.storeql.product.mapper;

import com.storeql.product.domain.Assortment;
import com.storeql.product.domain.Assortment.Change;
import com.storeql.product.domain.Assortment.Cluster;
import com.storeql.product.domain.Assortment.Line;
import com.storeql.product.domain.Assortment.Review;
import com.storeql.product.dto.AssortmentDtos;
import com.storeql.product.service.AssortmentService;
import com.storeql.service.Fx;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Range decisions on the wire (07.18). Money and quantities go out as strings, scale intact. */
public final class AssortmentMappers {

  private AssortmentMappers() {}

  public static AssortmentDtos.ClusterResponse toDto(Cluster c) {
    return new AssortmentDtos.ClusterResponse(
        c.id().toString(),
        c.code(),
        c.name(),
        c.note(),
        c.status(),
        c.storeIds().stream().map(UUID::toString).toList(),
        text(c.createdAt()));
  }

  public static List<AssortmentDtos.ClusterResponse> clusters(List<Cluster> all) {
    return all.stream().map(AssortmentMappers::toDto).toList();
  }

  public static AssortmentDtos.ChangeResponse toDto(Change ch) {
    return new AssortmentDtos.ChangeResponse(
        ch.id().toString(),
        ch.productId().toString(),
        text(ch.storeId()),
        text(ch.clusterId()),
        ch.action(),
        text(ch.effectiveFrom()),
        ch.reason(),
        text(ch.decidedBy()),
        text(ch.createdAt()),
        text(ch.appliedAt()),
        text(ch.reviewId()),
        text(ch.refusedAt()),
        ch.refusalCode(),
        ch.refusalDetail());
  }

  public static List<AssortmentDtos.ChangeResponse> changes(List<Change> all) {
    return all.stream().map(AssortmentMappers::toDto).toList();
  }

  public static AssortmentDtos.SweepResponse toDto(AssortmentService.SweepResult r) {
    return new AssortmentDtos.SweepResponse(
        r.applied(),
        r.notApplied().stream()
            .map(
                n ->
                    new AssortmentDtos.NotAppliedResponse(
                        n.changeId().toString(),
                        n.productId().toString(),
                        n.code(),
                        n.detail(),
                        n.stillDue()))
            .toList());
  }

  public static AssortmentDtos.LineResponse toDto(Line l) {
    return new AssortmentDtos.LineResponse(
        l.variantId().toString(),
        text(l.unitsSold()),
        money(l.revenue(), l.currency()),
        money(l.margin(), l.currency()),
        l.currency(),
        l.rankInCategory(),
        l.decision(),
        l.decisionNote(),
        l.ownBrand());
  }

  public static AssortmentDtos.ReviewResponse toDto(Review r) {
    return new AssortmentDtos.ReviewResponse(
        r.id().toString(),
        r.categoryId().toString(),
        r.name(),
        text(r.periodFrom()),
        text(r.periodTo()),
        r.status(),
        r.note(),
        r.undecided(),
        text(r.createdAt()),
        text(r.decidedAt()),
        r.lines().stream().map(AssortmentMappers::toDto).toList());
  }

  public static List<AssortmentDtos.ReviewResponse> reviews(List<Review> all) {
    return all.stream().map(AssortmentMappers::toDto).toList();
  }

  public static AssortmentDtos.ReviewResultResponse toDto(AssortmentService.ReviewResult r) {
    return new AssortmentDtos.ReviewResultResponse(
        toDto(r.review()),
        changes(r.changes()),
        r.leftAlone().stream().map(AssortmentMappers::toDto).toList());
  }

  private static AssortmentDtos.LeftAloneResponse toDto(Assortment.LeftAlone l) {
    return new AssortmentDtos.LeftAloneResponse(l.productId().toString(), l.reason());
  }

  private static String text(BigDecimal v) {
    return v == null ? null : v.toPlainString();
  }

  /**
   * Money as its currency writes it: to its minor units ({@link Fx#minorUnits}), so a yen figure
   * has no decimals and a dinar three, whatever scale the column keeps it at. Exact for every
   * figure taken since 2 Oct 2026, which are refused with more; half up for one kept before.
   */
  private static String money(BigDecimal v, String currency) {
    return v == null
        ? null
        : v.setScale(Fx.minorUnits(currency), java.math.RoundingMode.HALF_UP).toPlainString();
  }

  private static String text(Instant at) {
    return at == null ? null : at.toString();
  }

  private static String text(LocalDate day) {
    return day == null ? null : day.toString();
  }

  private static String text(UUID id) {
    return id == null ? null : id.toString();
  }
}
