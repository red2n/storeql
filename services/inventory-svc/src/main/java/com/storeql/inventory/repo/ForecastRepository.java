package com.storeql.inventory.repo;

import com.storeql.inventory.domain.Domain.DemandForecast;
import com.storeql.inventory.domain.Domain.FreshProfile;
import com.storeql.inventory.domain.Forecasting;
import com.storeql.inventory.domain.Forecasting.Accuracy;
import com.storeql.inventory.domain.Forecasting.Forecast;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** The demand forecasts (06.x): the daily history they are read from and the rows they become. */
@ApplicationScoped
public class ForecastRepository extends BaseJdbcRepository {

  private static final String COLUMNS =
      "id, tenant_id, store_id, variant_id, method, intermittent, alpha, level, weekday_profile,"
          + " history_from, history_to, history_days, horizon_days, from_day, points, holdout_days,"
          + " mape, bias, mase, computed_at, fresh, shelf_life_days, waste_rate, max_cover_days,"
          + " seasonal_indices, uplift, uplift_source, promoted_history_days, promoted_ahead_days";

  private record DailyDemandRow(UUID variantId, LocalDate day, BigDecimal qty) {}

  /** What the batches and write-offs say about one variant's life at the store. */
  public record FreshFacts(Double shelfLifeDays, BigDecimal wasted) {}

  private record FreshRow(UUID variantId, Double shelfLife, BigDecimal qty) {}

  /**
   * Per variant: the median days from receipt to expiry over the dated batches received since
   * {@code from}, and what went out of date unsold — batches past their date with stock left, plus
   * stock written off as EXPIRY.
   *
   * @param tenantId owning tenant; the first condition of both queries
   * @param storeId the store
   * @param from the first receipt day to read
   * @return variant → facts, only for variants with dated batches or expiry write-offs
   */
  public Map<UUID, FreshFacts> freshFacts(UUID tenantId, UUID storeId, LocalDate from) {
    List<FreshRow> batches =
        query(
            "SELECT variant_id,"
                + " percentile_cont(0.5) WITHIN GROUP (ORDER BY (expiry_date - created_at::date)) AS shelf_life,"
                + " COALESCE(SUM(remaining_qty) FILTER (WHERE expiry_date < CURRENT_DATE AND remaining_qty > 0), 0) AS qty"
                + " FROM inventory_batches"
                + " WHERE tenant_id = ? AND store_id = ? AND expiry_date IS NOT NULL AND created_at >= ?"
                + " GROUP BY variant_id",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, OffsetDateTime.of(from.atStartOfDay(), ZoneOffset.UTC));
            },
            rs ->
                new FreshRow(
                    rs.getObject("variant_id", UUID.class),
                    rs.getObject("shelf_life") == null ? null : rs.getDouble("shelf_life"),
                    rs.getBigDecimal("qty")),
            "shelf life by variant");
    List<FreshRow> writtenOff =
        query(
            "SELECT variant_id, COALESCE(SUM(-qty), 0) AS qty FROM stock_movements"
                + " WHERE tenant_id = ? AND store_id = ? AND type = 'ADJUST' AND reason_code = 'EXPIRY'"
                + " AND qty < 0 AND created_at >= ?"
                + " GROUP BY variant_id",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, OffsetDateTime.of(from.atStartOfDay(), ZoneOffset.UTC));
            },
            rs ->
                new FreshRow(rs.getObject("variant_id", UUID.class), null, rs.getBigDecimal("qty")),
            "expiry write-offs by variant");
    Map<UUID, FreshFacts> out = new LinkedHashMap<>();
    for (FreshRow b : batches) {
      out.put(b.variantId(), new FreshFacts(b.shelfLife(), b.qty()));
    }
    for (FreshRow w : writtenOff) {
      FreshFacts f = out.get(w.variantId());
      out.put(
          w.variantId(),
          new FreshFacts(
              f == null ? null : f.shelfLifeDays(),
              (f == null ? BigDecimal.ZERO : f.wasted()).add(w.qty())));
    }
    return out;
  }

  /**
   * Daily demand buckets at a store between two days, per variant and in day order.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store
   * @param variantId one variant, or null for every variant with a bucket
   * @param from the first day, inclusive
   * @param to the last day, inclusive
   * @return variant → (day → quantity), days with no bucket absent
   */
  public Map<UUID, Map<LocalDate, BigDecimal>> dailyDemand(
      UUID tenantId, UUID storeId, UUID variantId, LocalDate from, LocalDate to) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT variant_id, bucket_date, demand_qty FROM demand_history"
                + " WHERE tenant_id = ? AND store_id = ? AND bucket_type = 'DAY'"
                + " AND bucket_date >= ? AND bucket_date <= ?");
    if (variantId != null) {
      sb.append(" AND variant_id = ?");
    }
    sb.append(" ORDER BY variant_id, bucket_date");
    List<DailyDemandRow> rows =
        query(
            sb.toString(),
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, from);
              ps.setObject(4, to);
              if (variantId != null) {
                ps.setObject(5, variantId);
              }
            },
            rs ->
                new DailyDemandRow(
                    rs.getObject("variant_id", UUID.class),
                    rs.getObject("bucket_date", LocalDate.class),
                    rs.getBigDecimal("demand_qty")),
            "daily demand");
    Map<UUID, Map<LocalDate, BigDecimal>> out = new LinkedHashMap<>();
    for (DailyDemandRow r : rows) {
      out.computeIfAbsent(r.variantId(), k -> new TreeMap<>()).put(r.day(), r.qty());
    }
    return out;
  }

  /** Writes a run's forecasts, replacing each (store, variant)'s earlier one; the id stays. */
  public void upsertAll(List<DemandForecast> rows) {
    if (rows.isEmpty()) {
      return;
    }
    inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "INSERT INTO demand_forecasts ("
                      + COLUMNS
                      + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                      + " ON CONFLICT (tenant_id, store_id, variant_id) DO UPDATE SET"
                      + " method = EXCLUDED.method, intermittent = EXCLUDED.intermittent,"
                      + " alpha = EXCLUDED.alpha, level = EXCLUDED.level,"
                      + " weekday_profile = EXCLUDED.weekday_profile,"
                      + " history_from = EXCLUDED.history_from, history_to = EXCLUDED.history_to,"
                      + " history_days = EXCLUDED.history_days, horizon_days = EXCLUDED.horizon_days,"
                      + " from_day = EXCLUDED.from_day, points = EXCLUDED.points,"
                      + " holdout_days = EXCLUDED.holdout_days, mape = EXCLUDED.mape,"
                      + " bias = EXCLUDED.bias, mase = EXCLUDED.mase,"
                      + " computed_at = EXCLUDED.computed_at, fresh = EXCLUDED.fresh,"
                      + " shelf_life_days = EXCLUDED.shelf_life_days, waste_rate = EXCLUDED.waste_rate,"
                      + " max_cover_days = EXCLUDED.max_cover_days,"
                      + " seasonal_indices = EXCLUDED.seasonal_indices, uplift = EXCLUDED.uplift,"
                      + " uplift_source = EXCLUDED.uplift_source,"
                      + " promoted_history_days = EXCLUDED.promoted_history_days,"
                      + " promoted_ahead_days = EXCLUDED.promoted_ahead_days")) {
            for (DemandForecast r : rows) {
              Forecast f = r.forecast();
              ps.setObject(1, r.id());
              ps.setObject(2, r.tenantId());
              ps.setObject(3, r.storeId());
              ps.setObject(4, r.variantId());
              ps.setString(5, f.method());
              ps.setBoolean(6, f.intermittent());
              ps.setBigDecimal(7, f.alpha());
              ps.setBigDecimal(8, f.level());
              if (f.weekdayProfile().isEmpty()) {
                ps.setNull(9, Types.ARRAY);
              } else {
                ps.setArray(
                    9, c.createArrayOf("numeric", f.weekdayProfile().toArray(new BigDecimal[0])));
              }
              ps.setObject(10, r.historyFrom());
              ps.setObject(11, r.historyTo());
              ps.setInt(12, f.historyDays());
              ps.setInt(13, r.horizonDays());
              ps.setObject(14, f.fromDay());
              ps.setArray(15, c.createArrayOf("numeric", f.points().toArray(new BigDecimal[0])));
              ps.setInt(16, f.accuracy().holdoutDays());
              ps.setBigDecimal(17, f.accuracy().mape());
              ps.setBigDecimal(18, f.accuracy().bias());
              ps.setBigDecimal(19, f.accuracy().mase());
              ps.setObject(20, OffsetDateTime.ofInstant(r.computedAt(), ZoneOffset.UTC));
              FreshProfile fresh = r.fresh() == null ? FreshProfile.KEEPS : r.fresh();
              ps.setBoolean(21, fresh.fresh());
              if (fresh.shelfLifeDays() == null) {
                ps.setNull(22, Types.INTEGER);
              } else {
                ps.setInt(22, fresh.shelfLifeDays());
              }
              ps.setBigDecimal(23, fresh.wasteRate());
              if (fresh.maxCoverDays() == null) {
                ps.setNull(24, Types.INTEGER);
              } else {
                ps.setInt(24, fresh.maxCoverDays());
              }
              if (f.seasonalIndices().isEmpty()) {
                ps.setNull(25, Types.ARRAY);
              } else {
                ps.setArray(
                    25, c.createArrayOf("numeric", f.seasonalIndices().toArray(new BigDecimal[0])));
              }
              ps.setBigDecimal(26, f.uplift());
              ps.setString(27, f.uplift() == null ? null : f.upliftSource());
              ps.setInt(28, f.promotedHistoryDays());
              ps.setInt(29, f.promotedAheadDays());
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return null;
        },
        "upsert forecasts");
  }

  /**
   * The forecasts at a store, newest run first.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store
   * @param variantId one variant, or null for all
   * @param limit at most this many rows
   * @return the matching rows
   */
  public List<DemandForecast> list(UUID tenantId, UUID storeId, UUID variantId, int limit) {
    StringBuilder sb =
        new StringBuilder(
            "SELECT " + COLUMNS + " FROM demand_forecasts WHERE tenant_id = ? AND store_id = ?");
    if (variantId != null) {
      sb.append(" AND variant_id = ?");
    }
    sb.append(" ORDER BY computed_at DESC, variant_id LIMIT ?");
    return query(
        sb.toString(),
        ps -> {
          int i = 1;
          ps.setObject(i++, tenantId);
          ps.setObject(i++, storeId);
          if (variantId != null) {
            ps.setObject(i++, variantId);
          }
          ps.setInt(i, limit);
        },
        ForecastRepository::map,
        "list forecasts");
  }

  /**
   * One item's forecast at one store.
   *
   * @param tenantId owning tenant; the first condition of the query
   * @param storeId the store
   * @param variantId the variant
   * @return the forecast, or empty when none has been run
   */
  public Optional<DemandForecast> find(UUID tenantId, UUID storeId, UUID variantId) {
    return query(
            "SELECT "
                + COLUMNS
                + " FROM demand_forecasts WHERE tenant_id = ? AND store_id = ? AND variant_id = ?",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setObject(2, storeId);
              ps.setObject(3, variantId);
            },
            ForecastRepository::map,
            "find forecast")
        .stream()
        .findFirst();
  }

  private static DemandForecast map(ResultSet rs) throws SQLException {
    Forecast f =
        new Forecast(
            rs.getString("method"),
            rs.getBoolean("intermittent"),
            rs.getBigDecimal("alpha"),
            rs.getBigDecimal("level"),
            decimals(rs.getArray("weekday_profile")),
            rs.getObject("from_day", LocalDate.class),
            decimals(rs.getArray("points")),
            new Accuracy(
                rs.getInt("holdout_days"),
                rs.getBigDecimal("mape"),
                rs.getBigDecimal("bias"),
                rs.getBigDecimal("mase")),
            rs.getInt("history_days"),
            decimals(rs.getArray("seasonal_indices")),
            rs.getBigDecimal("uplift"),
            rs.getString("uplift_source"),
            rs.getInt("promoted_history_days"),
            rs.getInt("promoted_ahead_days"));
    return new DemandForecast(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("store_id", UUID.class),
        rs.getObject("variant_id", UUID.class),
        rs.getObject("history_from", LocalDate.class),
        rs.getObject("history_to", LocalDate.class),
        rs.getInt("horizon_days"),
        f,
        rs.getObject("computed_at", OffsetDateTime.class).toInstant(),
        new FreshProfile(
            rs.getObject("shelf_life_days") == null ? null : rs.getInt("shelf_life_days"),
            rs.getBigDecimal("waste_rate")));
  }

  private static List<BigDecimal> decimals(Array array) throws SQLException {
    if (array == null) {
      return List.of();
    }
    List<BigDecimal> out = new ArrayList<>();
    for (Object o : (Object[]) array.getArray()) {
      out.add((BigDecimal) o);
    }
    return out;
  }

  /** Kept so the method catalogue reads in one place. */
  static List<String> methods() {
    return List.of(Forecasting.METHOD_MEAN, Forecasting.METHOD_SES, Forecasting.METHOD_CROSTON_SBA);
  }
}
