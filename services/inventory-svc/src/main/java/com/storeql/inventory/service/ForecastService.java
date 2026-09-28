package com.storeql.inventory.service;

import com.storeql.ids.Ids;
import com.storeql.inventory.client.PricingClient;
import com.storeql.inventory.domain.Domain.DemandBucket;
import com.storeql.inventory.domain.Domain.DemandForecast;
import com.storeql.inventory.domain.Domain.FreshProfile;
import com.storeql.inventory.domain.Forecasting;
import com.storeql.inventory.domain.Forecasting.Calendar;
import com.storeql.inventory.domain.Forecasting.Forecast;
import com.storeql.inventory.domain.Forecasting.Shape;
import com.storeql.inventory.domain.Forecasting.UpliftFacts;
import com.storeql.inventory.domain.Promotions;
import com.storeql.inventory.domain.Promotions.PromotionWindow;
import com.storeql.inventory.repo.DemandHistoryRepository;
import com.storeql.inventory.repo.ForecastRepository;
import com.storeql.inventory.repo.ForecastRepository.FreshFacts;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The statistical demand forecast (06.x): a run over a store's daily demand history, one forecast
 * per item, kept for the reorder-point computation and the planner to read.
 *
 * <p>A run first folds yesterday's sales into the daily buckets, then reads up to six months of
 * them per variant, zero-fills the days with no sale from the first day the item sold, and hands
 * each series to {@link Forecasting}. The series starts at the item's first sale rather than at the
 * window's edge, so a line listed a month ago is not read as five months of nothing.
 */
@ApplicationScoped
public class ForecastService {

  public static final int DEFAULT_HORIZON_DAYS = 28;
  public static final int MAX_HORIZON_DAYS = 365;

  /** How far back a run reads: long enough for a season's shape, short enough to stay current. */
  static final int HISTORY_DAYS = 182;

  /**
   * A fresh item's level follows its last eight weeks: what sold in spring says little about this
   * week.
   */
  static final int FRESH_HISTORY_DAYS = 56;

  /**
   * How far back a run reads for the year's shape and the promotions that ran: two years, so
   * thirteen months can be seen and a promotion's fortnight measured long after it ended.
   */
  static final int LONG_HISTORY_DAYS = 730;

  /**
   * Four weeks of promoted days across a store before its pooled uplift stands in for an item's.
   */
  static final int STORE_UPLIFT_MIN_PROMOTED_DAYS = 28;

  private static final Logger LOG = System.getLogger(ForecastService.class.getName());

  @Inject ForecastRepository repo;
  @Inject DemandHistoryRepository demandHistoryRepo;
  @Inject PricingClient pricing;

  /** What a run did: how many items, by which method, and how the forecasts rate themselves. */
  public record RunResult(
      UUID storeId,
      int variants,
      Map<String, Integer> byMethod,
      BigDecimal meanMape,
      int horizonDays,
      Instant computedAt,
      int fresh,
      int seasonal,
      int promoted) {
    public RunResult {
      byMethod = Map.copyOf(byMethod);
    }
  }

  /**
   * One item's history, read and shaped, waiting for the store's pooled uplift before it is
   * forecast.
   */
  private record Prepared(
      UUID variantId,
      List<BigDecimal> series,
      LocalDate first,
      FreshProfile fresh,
      List<BigDecimal> indices,
      UpliftFacts facts,
      boolean[] promotedHistory,
      boolean[] promotedAhead) {}

  /**
   * Forecasts every item with demand history at a store (or one item), replacing earlier forecasts.
   *
   * @param tenantId owning tenant
   * @param storeId the store
   * @param variantId one variant, or null for every variant with history
   * @param horizonDays days to forecast, 1 to 365; null for 28
   * @return what the run did
   * @throws ApiException 400 FORECAST_HORIZON_INVALID when the horizon is outside 1..365
   */
  public RunResult run(UUID tenantId, UUID storeId, UUID variantId, Integer horizonDays) {
    int horizon = horizonDays == null ? DEFAULT_HORIZON_DAYS : horizonDays;
    if (horizon < 1 || horizon > MAX_HORIZON_DAYS) {
      throw ApiException.badRequest(
          "FORECAST_HORIZON_INVALID", "horizonDays must be between 1 and " + MAX_HORIZON_DAYS);
    }
    LocalDate to = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    LocalDate longFrom = to.minusDays(LONG_HISTORY_DAYS - 1L);
    LocalDate from = to.minusDays(HISTORY_DAYS - 1L);
    demandHistoryRepo.aggregateDemand(tenantId, storeId, DemandBucket.BUCKET_DAY, longFrom);
    Map<UUID, Map<LocalDate, BigDecimal>> history =
        repo.dailyDemand(tenantId, storeId, variantId, longFrom, to);
    Map<UUID, FreshFacts> freshFacts = repo.freshFacts(tenantId, storeId, from);
    List<PromotionWindow> windows =
        pricing
            .promotionWindows(tenantId, storeId, longFrom)
            .orElseGet(
                () -> {
                  LOG.log(
                      Level.WARNING,
                      "forecasting store {0} of {1} with no promotion windows",
                      storeId,
                      tenantId);
                  return List.of();
                });

    // First pass: read and shape every item, pooling what its promotions sold.
    List<Prepared> prepared = new ArrayList<>();
    UpliftFacts pooled = UpliftFacts.NONE;
    for (Map.Entry<UUID, Map<LocalDate, BigDecimal>> e : history.entrySet()) {
      UUID variant = e.getKey();
      LocalDate first = e.getValue().keySet().iterator().next(); // a TreeMap: the earliest day
      List<BigDecimal> longSeries = zeroFilled(e.getValue(), first, to);
      boolean[] promotedLong = Promotions.days(windows, variant, storeId, first, to);
      List<BigDecimal> indices = Forecasting.seasonalIndices(longSeries, to, promotedLong);
      UpliftFacts facts = Forecasting.upliftFacts(longSeries, to, promotedLong, indices);
      pooled = pooled.plus(facts);
      List<BigDecimal> recent = tail(longSeries, HISTORY_DAYS);
      FreshProfile fresh = freshProfile(freshFacts.get(variant), recent);
      int window = fresh.fresh() ? FRESH_HISTORY_DAYS : HISTORY_DAYS;
      List<BigDecimal> series = tail(longSeries, window);
      boolean[] promotedHistory = tail(promotedLong, window);
      boolean[] promotedAhead =
          Promotions.days(windows, variant, storeId, to.plusDays(1), to.plusDays(horizon));
      prepared.add(
          new Prepared(
              variant,
              series,
              to.minusDays(series.size() - 1L),
              fresh,
              indices,
              facts,
              promotedHistory,
              promotedAhead));
    }
    BigDecimal storeUplift =
        pooled.promotedDays() >= STORE_UPLIFT_MIN_PROMOTED_DAYS
            ? Forecasting.upliftOf(pooled)
            : null;

    // Second pass: forecast, each item's own uplift first, the store's when it has too little.
    Instant now = Instant.now();
    List<DemandForecast> forecasts = new ArrayList<>();
    Map<String, Integer> byMethod = new TreeMap<>();
    BigDecimal mapeSum = BigDecimal.ZERO;
    int mapeCount = 0;
    int freshCount = 0;
    int seasonalCount = 0;
    int promotedCount = 0;
    for (Prepared p : prepared) {
      BigDecimal itemUplift = Forecasting.upliftOf(p.facts());
      Shape shape;
      if (itemUplift != null) {
        shape = new Shape(p.indices(), itemUplift, Shape.UPLIFT_ITEM);
      } else if (storeUplift != null) {
        shape = new Shape(p.indices(), storeUplift, Shape.UPLIFT_STORE);
      } else {
        shape = new Shape(p.indices(), null, null);
      }
      Forecast f =
          Forecasting.forecast(
              p.series(), to, horizon, shape, new Calendar(p.promotedHistory(), p.promotedAhead()));
      forecasts.add(
          new DemandForecast(
              Ids.newId(),
              tenantId,
              storeId,
              p.variantId(),
              p.first(),
              to,
              horizon,
              f,
              now,
              p.fresh()));
      byMethod.merge(f.method(), 1, Integer::sum);
      if (f.accuracy().mape() != null) {
        mapeSum = mapeSum.add(f.accuracy().mape());
        mapeCount++;
      }
      if (p.fresh().fresh()) freshCount++;
      if (!f.seasonalIndices().isEmpty()) seasonalCount++;
      if (f.promotedAheadDays() > 0) promotedCount++;
    }
    repo.upsertAll(forecasts);
    BigDecimal meanMape =
        mapeCount == 0
            ? null
            : mapeSum.divide(BigDecimal.valueOf(mapeCount), 2, RoundingMode.HALF_UP);
    return new RunResult(
        storeId,
        forecasts.size(),
        byMethod,
        meanMape,
        horizon,
        now,
        freshCount,
        seasonalCount,
        promotedCount);
  }

  /** The last {@code days} of the series, or all of it when shorter. */
  static List<BigDecimal> tail(List<BigDecimal> series, int days) {
    return series.size() > days ? series.subList(series.size() - days, series.size()) : series;
  }

  private static boolean[] tail(boolean[] marks, int days) {
    return marks.length > days
        ? Arrays.copyOfRange(marks, marks.length - days, marks.length)
        : marks;
  }

  /**
   * The item's life as the batches tell it: the median shelf life, rounded to whole days, and the
   * share of what was received that went out of date unsold — waste over sold plus waste — null
   * when there was neither.
   */
  static FreshProfile freshProfile(FreshFacts facts, List<BigDecimal> series) {
    if (facts == null) {
      return FreshProfile.KEEPS;
    }
    Integer shelfLife =
        facts.shelfLifeDays() == null ? null : (int) Math.max(1, Math.round(facts.shelfLifeDays()));
    BigDecimal sold = series.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal wasted = facts.wasted() == null ? BigDecimal.ZERO : facts.wasted();
    BigDecimal denominator = sold.add(wasted);
    BigDecimal wasteRate =
        denominator.signum() <= 0 ? null : wasted.divide(denominator, 4, RoundingMode.HALF_UP);
    return new FreshProfile(shelfLife, wasteRate);
  }

  /** The buckets as a day-by-day series from {@code from} to {@code to}, zero where none. */
  static List<BigDecimal> zeroFilled(
      Map<LocalDate, BigDecimal> buckets, LocalDate from, LocalDate to) {
    List<BigDecimal> out = new ArrayList<>();
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      out.add(buckets.getOrDefault(d, BigDecimal.ZERO));
    }
    return out;
  }

  public List<DemandForecast> list(UUID tenantId, UUID storeId, UUID variantId, int limit) {
    return repo.list(tenantId, storeId, variantId, limit);
  }

  /**
   * One item's forecast at one store.
   *
   * @throws ApiException 404 FORECAST_NOT_FOUND when no run has forecast it
   */
  public DemandForecast get(UUID tenantId, UUID storeId, UUID variantId) {
    return repo.find(tenantId, storeId, variantId)
        .orElseThrow(
            () ->
                ApiException.notFound(
                    "FORECAST_NOT_FOUND", "No forecast for this variant at this store yet"));
  }
}
