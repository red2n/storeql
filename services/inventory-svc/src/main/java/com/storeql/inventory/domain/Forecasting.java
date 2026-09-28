package com.storeql.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The statistical demand forecast (06.x) as pure arithmetic over one item's daily demand at one
 * store: a zero-filled series, oldest first, ending on the last day of history.
 *
 * <p>Two methods, chosen by the shape of the demand as the replenishment literature classifies it
 * (Syntetos and Boylan): a series with demand on most days is <b>smooth</b> and gets simple
 * exponential smoothing with a day-of-week profile once there are four weeks to learn one from; a
 * series with demand on fewer than three days in four (average inter-demand interval above 1.32) is
 * <b>intermittent</b> and gets Croston's method with the Syntetos–Boylan approximation, which
 * forecasts the demand per day as the smoothed size over the smoothed interval, corrected for
 * Croston's bias. Fewer than fourteen days of history is a plain mean and says so.
 *
 * <p>The smoothing constant is not a setting: it is chosen from a small grid by forecasting the
 * last quarter of the history (at least a week, at most four) from the rest and keeping the alpha
 * that missed it least. The same hold-out gives the forecast's own report on itself — MAPE over the
 * days that had demand, bias as a percentage of what was sold, and MASE against a naïve
 * one-day-back forecast — each null rather than zero when it cannot be honestly computed.
 */
public final class Forecasting {

  public static final String METHOD_MEAN = "MEAN";
  public static final String METHOD_SES = "SES";
  public static final String METHOD_CROSTON_SBA = "CROSTON_SBA";

  /** Below this, the only honest forecast is the mean, with no claim of accuracy. */
  public static final int MIN_HISTORY_DAYS = 14;

  /** A day-of-week profile needs four weeks to say anything about a Saturday. */
  static final int PROFILE_MIN_DAYS = 28;

  /** Average days between demands above which demand is intermittent (Syntetos–Boylan). */
  static final double INTERMITTENT_ADI = 1.32;

  private static final double[] ALPHAS = {0.05, 0.1, 0.2, 0.3, 0.5};
  private static final int SCALE = 4;
  private static final double PROFILE_FLOOR = 0.2;
  private static final double PROFILE_CEILING = 3.0;

  /** Thirteen months: every calendar month seen at least once, so the year has a shape. */
  public static final int SEASONAL_MIN_DAYS = 395;

  /** A week of promoted days, and four weeks of ordinary ones, before an uplift is claimed. */
  static final int UPLIFT_MIN_PROMOTED_DAYS = 7;

  static final int UPLIFT_MIN_BASELINE_DAYS = 28;

  private static final double INDEX_FLOOR = 0.25;
  private static final double INDEX_CEILING = 4.0;
  private static final double UPLIFT_CEILING = 10.0;

  private Forecasting() {}

  /**
   * Which days a promotion ran on — one flag per history day, oldest first — and which days one
   * will run on, one per horizon day. Nothing marked is the ordinary calendar.
   */
  @SuppressWarnings("PMD.UseVarargs") // two arrays of flags, one per day; neither is a varargs
  public static final class Calendar {
    private final boolean[] history;
    private final boolean[] ahead;

    public Calendar(boolean[] promotedHistory, boolean[] promotedAhead) {
      history = promotedHistory.clone();
      ahead = promotedAhead.clone();
    }

    /** No promotion, past or planned. */
    public static Calendar none(int historyDays, int horizonDays) {
      return new Calendar(new boolean[historyDays], new boolean[horizonDays]);
    }

    public boolean promotedOn(int historyIndex) {
      return history[historyIndex];
    }

    public boolean promotedAheadOn(int horizonIndex) {
      return ahead[horizonIndex];
    }

    public int historyDays() {
      return history.length;
    }

    public int horizonDays() {
      return ahead.length;
    }

    public int promotedHistoryDays() {
      return count(history);
    }

    public int promotedAheadDays() {
      return count(ahead);
    }

    private static int count(boolean... marks) {
      int n = 0;
      for (boolean m : marks) {
        if (m) n++;
      }
      return n;
    }
  }

  /**
   * The shape of demand beyond the week: twelve monthly indices, January first (empty when the
   * history is too short for a year to have a shape), and the lift a promotion gives, with where it
   * was measured — {@code ITEM} from this item's own promoted days, {@code STORE} pooled across the
   * store's — or null when none could be.
   */
  public record Shape(List<BigDecimal> seasonalIndices, BigDecimal uplift, String upliftSource) {

    public static final String UPLIFT_ITEM = "ITEM";
    public static final String UPLIFT_STORE = "STORE";
    public static final Shape FLAT = new Shape(List.of(), null, null);

    public Shape {
      seasonalIndices = List.copyOf(seasonalIndices);
    }
  }

  /**
   * What an uplift is measured from: deseasonalised demand summed over the promoted days and over
   * the ordinary ones, with the day counts, so a store's items can be pooled by adding these up.
   */
  public record UpliftFacts(
      double promotedSum, int promotedDays, double baselineSum, int baselineDays) {

    public static final UpliftFacts NONE = new UpliftFacts(0, 0, 0, 0);

    public UpliftFacts plus(UpliftFacts other) {
      return new UpliftFacts(
          promotedSum + other.promotedSum,
          promotedDays + other.promotedDays,
          baselineSum + other.baselineSum,
          baselineDays + other.baselineDays);
    }
  }

  /** How the forecast did on the hold-out; each figure null when it cannot be honestly computed. */
  public record Accuracy(int holdoutDays, BigDecimal mape, BigDecimal bias, BigDecimal mase) {}

  /**
   * The forecast for one item at one store: {@code points} is one expected quantity per day from
   * {@code fromDay}; {@code weekdayProfile} is seven multipliers, Monday first, or empty.
   */
  public record Forecast(
      String method,
      boolean intermittent,
      BigDecimal alpha,
      BigDecimal level,
      List<BigDecimal> weekdayProfile,
      LocalDate fromDay,
      List<BigDecimal> points,
      Accuracy accuracy,
      int historyDays,
      List<BigDecimal> seasonalIndices,
      BigDecimal uplift,
      String upliftSource,
      int promotedHistoryDays,
      int promotedAheadDays) {

    public Forecast {
      weekdayProfile = List.copyOf(weekdayProfile);
      points = List.copyOf(points);
      seasonalIndices = List.copyOf(seasonalIndices);
    }

    /** A forecast with no season and no promotion in it. */
    public Forecast(
        String method,
        boolean intermittent,
        BigDecimal alpha,
        BigDecimal level,
        List<BigDecimal> weekdayProfile,
        LocalDate fromDay,
        List<BigDecimal> points,
        Accuracy accuracy,
        int historyDays) {
      this(
          method,
          intermittent,
          alpha,
          level,
          weekdayProfile,
          fromDay,
          points,
          accuracy,
          historyDays,
          List.of(),
          null,
          null,
          0,
          0);
    }

    /** Expected demand over the next {@code days} days (or as many as the horizon holds). */
    public BigDecimal expectedOver(int days) {
      BigDecimal sum = BigDecimal.ZERO;
      for (int i = 0; i < Math.min(days, points.size()); i++) {
        sum = sum.add(points.get(i));
      }
      return sum.setScale(SCALE, RoundingMode.HALF_UP);
    }
  }

  /** A fitted model: the smoothed daily level and, for smooth demand, a day-of-week profile. */
  private record Model(double level, double[] profile) {}

  /**
   * Forecasts {@code horizonDays} from the day after {@code lastDay}, with no season and no
   * promotion in it.
   *
   * @param daily demand per day, zero-filled, oldest first, ending on {@code lastDay}
   * @param lastDay the last day the history covers
   * @param horizonDays how many days to forecast
   * @return the forecast, with its own report on itself
   */
  public static Forecast forecast(List<BigDecimal> daily, LocalDate lastDay, int horizonDays) {
    return forecast(
        daily, lastDay, horizonDays, Shape.FLAT, Calendar.none(daily.size(), horizonDays));
  }

  /**
   * Forecasts {@code horizonDays} from the day after {@code lastDay}, the year's shape and the
   * promotions taken out of the history before the level is fitted and put back into the days
   * ahead: a December day is forecast at the December index, a day a promotion will run on at the
   * uplift. The hold-out is judged on real demand, so a forecast that knows about a promotion is
   * rewarded only if the promotion actually sold.
   *
   * @param daily demand per day, zero-filled, oldest first, ending on {@code lastDay}
   * @param lastDay the last day the history covers
   * @param horizonDays how many days to forecast
   * @param shape the monthly indices and the uplift to use; {@link Shape#FLAT} for none
   * @param calendar which history and horizon days a promotion ran or will run on
   * @return the forecast, with its own report on itself
   * @throws IllegalArgumentException when the calendar does not match the history and horizon
   */
  public static Forecast forecast(
      List<BigDecimal> daily, LocalDate lastDay, int horizonDays, Shape shape, Calendar calendar) {
    double[] y = daily.stream().mapToDouble(BigDecimal::doubleValue).toArray();
    int n = y.length;
    if (calendar.historyDays() != n || calendar.horizonDays() != horizonDays) {
      throw new IllegalArgumentException("the calendar must cover the history and the horizon");
    }
    LocalDate fromDay = lastDay.plusDays(1);
    double[] monthIndex = indexArray(shape.seasonalIndices());
    double lift = shape.uplift() == null ? 1.0 : shape.uplift().doubleValue();
    double[] factor = new double[n];
    double[] adjusted = new double[n];
    for (int i = 0; i < n; i++) {
      LocalDate day = lastDay.minusDays(n - 1L - i);
      factor[i] = monthFactor(monthIndex, day) * (calendar.promotedOn(i) ? lift : 1.0);
      adjusted[i] = y[i] / factor[i];
    }
    double[] ahead = new double[horizonDays];
    for (int i = 0; i < horizonDays; i++) {
      ahead[i] =
          monthFactor(monthIndex, fromDay.plusDays(i)) * (calendar.promotedAheadOn(i) ? lift : 1.0);
    }
    int withDemand = 0;
    for (double v : y) {
      if (v > 0) withDemand++;
    }
    if (n < MIN_HISTORY_DAYS || withDemand == 0) {
      double mean = n == 0 ? 0 : sum(adjusted) / n;
      return new Forecast(
          METHOD_MEAN,
          false,
          null,
          dec(mean, SCALE),
          List.of(),
          fromDay,
          points(new Model(mean, null), fromDay, ahead),
          new Accuracy(0, null, null, null),
          n,
          shape.seasonalIndices(),
          shape.uplift(),
          shape.uplift() == null ? null : shape.upliftSource(),
          calendar.promotedHistoryDays(),
          calendar.promotedAheadDays());
    }
    boolean intermittent = (double) n / withDemand > INTERMITTENT_ADI;
    int holdout = Math.max(7, Math.min(28, n / 4));
    double[] train = Arrays.copyOfRange(adjusted, 0, n - holdout);
    double[] trainActual = Arrays.copyOfRange(y, 0, n - holdout);
    double[] held = Arrays.copyOfRange(y, n - holdout, n);
    double[] heldFactor = Arrays.copyOfRange(factor, n - holdout, n);
    LocalDate trainLast = lastDay.minusDays(holdout);

    double bestAlpha = ALPHAS[0];
    double bestMae = Double.MAX_VALUE;
    double[] bestPrediction = null;
    for (double alpha : ALPHAS) {
      Model m = fit(train, trainLast, alpha, intermittent);
      double[] prediction = predict(m, trainLast.plusDays(1), heldFactor);
      double mae = meanAbsoluteError(held, prediction);
      if (mae < bestMae) {
        bestMae = mae;
        bestAlpha = alpha;
        bestPrediction = prediction;
      }
    }
    Accuracy accuracy = accuracy(trainActual, held, bestPrediction);
    Model model = fit(adjusted, lastDay, bestAlpha, intermittent);
    return new Forecast(
        intermittent ? METHOD_CROSTON_SBA : METHOD_SES,
        intermittent,
        dec(bestAlpha, 3),
        dec(model.level(), SCALE),
        profileOf(model),
        fromDay,
        points(model, fromDay, ahead),
        accuracy,
        n,
        shape.seasonalIndices(),
        shape.uplift(),
        shape.uplift() == null ? null : shape.upliftSource(),
        calendar.promotedHistoryDays(),
        calendar.promotedAheadDays());
  }

  // ── the shape of a year, and what a promotion does ─────────────────────────

  /**
   * Twelve monthly indices, January first, from a long history: each calendar month's average daily
   * demand against the whole period's, on the days no promotion ran, clamped and normalised so the
   * year still adds up to the level. Empty under thirteen months — every month must have been seen
   * at least once for the year to have a shape — or when nothing sold.
   *
   * @param daily demand per day, zero-filled, oldest first, ending on {@code lastDay}
   * @param lastDay the last day the history covers
   * @param promoted one flag per day, true where a promotion ran; those days are left out
   * @return twelve indices, or an empty list
   */
  public static List<BigDecimal> seasonalIndices(
      List<BigDecimal> daily, LocalDate lastDay, boolean... promoted) {
    int n = daily.size();
    if (n < SEASONAL_MIN_DAYS) {
      return List.of();
    }
    double[] sums = new double[12];
    int[] counts = new int[12];
    double total = 0;
    int days = 0;
    for (int i = 0; i < n; i++) {
      if (promoted != null && promoted[i]) {
        continue;
      }
      int m = lastDay.minusDays(n - 1L - i).getMonthValue() - 1;
      double v = daily.get(i).doubleValue();
      sums[m] += v;
      counts[m]++;
      total += v;
      days++;
    }
    double mean = days == 0 ? 0 : total / days;
    if (mean <= 0) {
      return List.of();
    }
    double[] index = new double[12];
    for (int m = 0; m < 12; m++) {
      double raw = counts[m] == 0 ? 1.0 : (sums[m] / counts[m]) / mean;
      index[m] = Math.min(INDEX_CEILING, Math.max(INDEX_FLOOR, raw));
    }
    double norm = sum(index) / 12;
    List<BigDecimal> out = new ArrayList<>(12);
    for (double v : index) {
      out.add(dec(v / norm, SCALE));
    }
    return out;
  }

  /**
   * The numbers an uplift is measured from: deseasonalised demand on the promoted days and on the
   * ordinary ones, with their counts.
   *
   * @param daily demand per day, zero-filled, oldest first, ending on {@code lastDay}
   * @param lastDay the last day the history covers
   * @param promoted one flag per day, true where a promotion ran
   * @param seasonalIndices twelve monthly indices to take out first, or empty
   * @return the sums and counts
   */
  public static UpliftFacts upliftFacts(
      List<BigDecimal> daily,
      LocalDate lastDay,
      boolean[] promoted,
      List<BigDecimal> seasonalIndices) {
    double[] monthIndex = indexArray(seasonalIndices);
    int n = daily.size();
    double promotedSum = 0;
    int promotedDays = 0;
    double baselineSum = 0;
    int baselineDays = 0;
    for (int i = 0; i < n; i++) {
      double v =
          daily.get(i).doubleValue() / monthFactor(monthIndex, lastDay.minusDays(n - 1L - i));
      if (promoted != null && promoted[i]) {
        promotedSum += v;
        promotedDays++;
      } else {
        baselineSum += v;
        baselineDays++;
      }
    }
    return new UpliftFacts(promotedSum, promotedDays, baselineSum, baselineDays);
  }

  /**
   * The lift a promotion gives: the average promoted day over the average ordinary day. Null under
   * a week of promoted days or four weeks of ordinary ones, when nothing ordinary sold, or when the
   * promoted days did not sell more — a promotion that lifted nothing is no uplift, not a cut.
   *
   * @param facts the sums and counts, for one item or pooled over a store
   * @return the uplift, at least 1 and at most 10, or null
   */
  public static BigDecimal upliftOf(UpliftFacts facts) {
    if (facts.promotedDays() < UPLIFT_MIN_PROMOTED_DAYS
        || facts.baselineDays() < UPLIFT_MIN_BASELINE_DAYS) {
      return null;
    }
    double baseline = facts.baselineSum() / facts.baselineDays();
    if (baseline <= 0) {
      return null;
    }
    double ratio = (facts.promotedSum() / facts.promotedDays()) / baseline;
    if (ratio <= 1.0) {
      return null;
    }
    return dec(Math.min(UPLIFT_CEILING, ratio), SCALE);
  }

  /** The twelve indices as doubles, or no indices (an empty array) when there is no shape. */
  private static double[] indexArray(List<BigDecimal> seasonalIndices) {
    if (seasonalIndices == null || seasonalIndices.size() != 12) {
      return new double[0];
    }
    double[] out = new double[12];
    for (int i = 0; i < 12; i++) {
      out[i] = seasonalIndices.get(i).doubleValue();
    }
    return out;
  }

  private static double monthFactor(double[] monthIndex, LocalDate day) {
    if (monthIndex.length != 12) {
      return 1.0;
    }
    double f = monthIndex[day.getMonthValue() - 1];
    return f <= 0 ? 1.0 : f;
  }

  // ── fitting ─────────────────────────────────────────────────────────────────

  private static Model fit(double[] y, LocalDate lastDay, double alpha, boolean intermittent) {
    if (intermittent) {
      return new Model(crostonSba(y, alpha), null);
    }
    double[] profile = y.length >= PROFILE_MIN_DAYS ? weekdayProfile(y, lastDay) : null;
    return new Model(ses(y, alpha), profile);
  }

  /** Simple exponential smoothing; the level starts at the mean of the first week. */
  private static double ses(double[] y, double alpha) {
    int warm = Math.min(7, y.length);
    double level = sum(Arrays.copyOfRange(y, 0, warm)) / warm;
    for (double v : y) {
      level = alpha * v + (1 - alpha) * level;
    }
    return Math.max(0, level);
  }

  /**
   * Croston's method with the Syntetos–Boylan approximation: demand sizes and the intervals between
   * them smoothed separately at each demand, forecast as size over interval, times (1 − α/2) to
   * take Croston's bias off.
   */
  private static double crostonSba(double[] y, double alpha) {
    double size = -1;
    double interval = -1;
    int sinceLast = 0;
    for (double v : y) {
      sinceLast++;
      if (v <= 0) {
        continue;
      }
      if (size < 0) {
        size = v;
        interval = sinceLast;
      } else {
        size = alpha * v + (1 - alpha) * size;
        interval = alpha * sinceLast + (1 - alpha) * interval;
      }
      sinceLast = 0;
    }
    if (size < 0 || interval <= 0) {
      return 0;
    }
    return Math.max(0, (1 - alpha / 2) * size / interval);
  }

  /**
   * Seven multipliers, Monday first: each weekday's average against the overall mean, clamped and
   * normalised so the week still adds up to the level times seven.
   */
  private static double[] weekdayProfile(double[] y, LocalDate lastDay) {
    double[] sums = new double[7];
    int[] counts = new int[7];
    for (int i = 0; i < y.length; i++) {
      int dow = lastDay.minusDays(y.length - 1L - i).getDayOfWeek().getValue() - 1;
      sums[dow] += y[i];
      counts[dow]++;
    }
    double mean = sum(y) / y.length;
    double[] profile = new double[7];
    if (mean <= 0) {
      Arrays.fill(profile, 1.0);
      return profile;
    }
    for (int d = 0; d < 7; d++) {
      double index = counts[d] == 0 ? 1.0 : (sums[d] / counts[d]) / mean;
      profile[d] = Math.min(PROFILE_CEILING, Math.max(PROFILE_FLOOR, index));
    }
    double norm = sum(profile) / 7;
    for (int d = 0; d < 7; d++) {
      profile[d] /= norm;
    }
    return profile;
  }

  // ── forecasting from a model ───────────────────────────────────────────────

  /**
   * One expected quantity per day from {@code from}: the level, the weekday, and that day's factor.
   */
  private static double[] predict(Model m, LocalDate from, double... dayFactor) {
    double[] out = new double[dayFactor.length];
    for (int i = 0; i < dayFactor.length; i++) {
      out[i] = Math.max(0, m.level() * multiplier(m, from.plusDays(i)) * dayFactor[i]);
    }
    return out;
  }

  private static List<BigDecimal> points(Model m, LocalDate from, double... dayFactor) {
    List<BigDecimal> out = new ArrayList<>(dayFactor.length);
    for (double v : predict(m, from, dayFactor)) {
      out.add(dec(v, SCALE));
    }
    return out;
  }

  private static double multiplier(Model m, LocalDate day) {
    return m.profile() == null ? 1.0 : m.profile()[day.getDayOfWeek().getValue() - 1];
  }

  private static List<BigDecimal> profileOf(Model m) {
    if (m.profile() == null) {
      return List.of();
    }
    List<BigDecimal> out = new ArrayList<>(7);
    for (double v : m.profile()) {
      out.add(dec(v, SCALE));
    }
    return out;
  }

  // ── the forecast's report on itself ─────────────────────────────────────────

  private static Accuracy accuracy(double[] train, double[] held, double... prediction) {
    double mae = meanAbsoluteError(held, prediction);
    double absPctSum = 0;
    int withDemand = 0;
    double errorSum = 0;
    double actualSum = 0;
    for (int i = 0; i < held.length; i++) {
      errorSum += prediction[i] - held[i];
      actualSum += held[i];
      if (held[i] > 0) {
        absPctSum += Math.abs(held[i] - prediction[i]) / held[i];
        withDemand++;
      }
    }
    double naive = 0;
    for (int i = 1; i < train.length; i++) {
      naive += Math.abs(train[i] - train[i - 1]);
    }
    naive = train.length > 1 ? naive / (train.length - 1) : 0;
    return new Accuracy(
        held.length,
        withDemand == 0 ? null : dec(100 * absPctSum / withDemand, 2),
        actualSum <= 0 ? null : dec(100 * errorSum / actualSum, 2),
        naive <= 0 ? null : dec(mae / naive, 3));
  }

  private static double meanAbsoluteError(double[] actual, double... prediction) {
    double sum = 0;
    for (int i = 0; i < actual.length; i++) {
      sum += Math.abs(actual[i] - prediction[i]);
    }
    return actual.length == 0 ? 0 : sum / actual.length;
  }

  private static double sum(double... values) {
    double s = 0;
    for (double v : values) {
      s += v;
    }
    return s;
  }

  private static BigDecimal dec(double v, int scale) {
    return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
  }
}
