package com.storeql.order.service;

import com.storeql.ids.Ids;
import com.storeql.order.client.RecallClient;
import com.storeql.order.client.StaffClient;
import com.storeql.order.client.TenantClient;
import com.storeql.order.config.ServiceConfig;
import com.storeql.order.domain.Domain.OfflineSaleFlag;
import com.storeql.order.domain.OfflineReplay;
import com.storeql.order.domain.OfflineReplay.Timing;
import com.storeql.order.domain.StopSale;
import com.storeql.order.domain.StopSale.ActiveRecall;
import com.storeql.order.domain.TradeScales;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * What order-svc checks of an order's lines before the order stands, besides price and stock: that
 * none is stock an open recall says must not be sold, and that every line sold by weight was
 * weighed on a scale certified at the order's store. The till checks both first — against its own
 * list of recalls, and by offering only certified scales — and order-svc checks them again, so a
 * direct API call or another client cannot sell what the till would have stopped.
 *
 * <p>Both fail open. When inventory-svc or tenant-svc cannot answer, the sale goes ahead and a
 * warning is logged: an unreadable answer never refuses a sale, the till has checked the line, and
 * a checkout that stopped whenever a neighbour was slow would close every shop in the business. A
 * replay within the grace goes ahead too, and a line weighed on a scale whose register could not be
 * read is flagged as one nobody can show was fit, since a replay is never refused and doubt leads
 * to a flag. A recall list that cannot be read leaves no entry, only the warning: an entry names
 * the recall that covered the line, and there is none to name.
 *
 * <p>A business's open recalls are kept for {@link ServiceConfig#recallCacheSeconds()}, ten seconds
 * unless configured: a busy till does not ask inventory-svc on every sale, and a recall opened now
 * still reaches every checkout within seconds. A failed read is not kept. The register of scales is
 * read afresh for each order that names one, once per scale, because a scale's standing changes the
 * moment a repair is recorded, and weighed lines are few.
 *
 * <p>A till sale replayed from the till's offline queue has already happened: the goods have gone
 * and the money was taken. Captured no further back than {@link
 * ServiceConfig#offlineReplayGraceHours()} and no later than now — give or take {@link
 * ServiceConfig#offlineReplayClockSkewSeconds()} for a till clock a little ahead — it is never
 * refused here ({@link OfflineReplay}); each line a recall covered, or a scale not fit for trade
 * weighed, at the moment it was rung up is returned to be written on the audit trail with the
 * order, and a warning names it. The outcome is only a flag, so doubt leads to one: a replay is
 * judged against every recall open when it was rung up, one closed or cancelled since included, and
 * a scale the register cannot show was fit then is flagged as such. A replay whose capture time is
 * outside those bounds is judged as a sale made now, and refused as one in words for a manager.
 *
 * <p>Each entry names who rang the sale up as the till recorded it, and only when that is a login
 * of the business allowed at the store ({@link OfflineReplay#cashier}) — the sender's own sign-in
 * settles it for a till naming the sender; otherwise iam-svc's staff directory is asked, once per
 * flagged replay. It also keeps who sent it.
 */
@ApplicationScoped
public class SaleChecks {

  private static final Logger LOG = System.getLogger(SaleChecks.class.getName());

  @Inject RecallClient recalls;
  @Inject TenantClient tenants;
  @Inject StaffClient staff;
  @Inject ServiceConfig config;

  /**
   * A business's recalls as last read.
   *
   * @param since for the list a replay is judged against, the earliest end it includes; null for
   *     the open ones only
   */
  private record Kept(List<ActiveRecall> recalls, Instant readAt, Instant since) {}

  private final Map<UUID, Kept> kept = new ConcurrentHashMap<>();
  private final Map<UUID, Kept> keptSince = new ConcurrentHashMap<>();
  private Clock clock = Clock.systemUTC();
  private Duration keepFor;
  private Duration grace;
  private Duration skew;

  /**
   * One order's lines as the checks read them.
   *
   * @param tenantId the business, from the caller's token: only its own recalls and register are
   *     asked about
   * @param storeId the store the order is placed at
   * @param orderId the order being placed, for the entries a replayed sale leaves on the trail
   * @param rungUpBy who the till says rang a replayed sale up, as it recorded at the sale; null
   *     when it did not say, and for every other sale
   * @param sender who is placing it now, as the caller's sign-in says; null when unknown
   * @param senderIsStaffHere whether the caller's own sign-in is staff of this business at this
   *     store
   * @param packs each line as the pack described itself
   * @param instruments each line's weighing instrument, null for a line sold by the each
   */
  public record Sale(
      UUID tenantId,
      UUID storeId,
      UUID orderId,
      UUID rungUpBy,
      UUID sender,
      boolean senderIsStaffHere,
      List<StopSale.Pack> packs,
      List<UUID> instruments) {}

  /** For tests: the two clients, how long a list of recalls is kept, and a clock to age it with. */
  static SaleChecks forTest(
      RecallClient recalls, TenantClient tenants, Duration keepFor, Clock clock) {
    return forTest(recalls, tenants, keepFor, clock, Duration.ofHours(24));
  }

  /** For tests: as above, and how far back a replayed till sale's capture time is honoured. */
  static SaleChecks forTest(
      RecallClient recalls, TenantClient tenants, Duration keepFor, Clock clock, Duration grace) {
    return forTest(recalls, tenants, null, keepFor, clock, grace, Duration.ZERO);
  }

  /**
   * For tests: as above, with the staff directory (null: nobody but the sender is ever named) and
   * how far after now a capture time is taken as a till clock a little ahead.
   */
  static SaleChecks forTest(
      RecallClient recalls,
      TenantClient tenants,
      StaffClient staff,
      Duration keepFor,
      Clock clock,
      Duration grace,
      Duration skew) {
    SaleChecks c = new SaleChecks();
    c.recalls = recalls;
    c.tenants = tenants;
    c.staff = staff;
    c.keepFor = keepFor;
    c.clock = clock;
    c.grace = grace;
    c.skew = skew;
    return c;
  }

  /**
   * How a sale is judged, for both checks.
   *
   * @param capturedAt when a replayed till sale says the cashier completed it; null for a sale made
   *     now, and for every online order
   * @return {@link Timing#LIVE} for a sale made now; a replay within the grace, honoured at its
   *     capture time — or at now, for one a little ahead of our clock; any other replay {@link
   *     Timing#NOT_HONOURED}
   */
  public Timing timing(Instant capturedAt) {
    return OfflineReplay.timing(capturedAt, clock.instant(), grace(), skew());
  }

  /**
   * Both checks of one order, recalls first.
   *
   * @param capturedAt when a replayed till sale says the cashier completed it; null for a sale made
   *     now, and for every online order
   * @return for a replay within the grace, the entries its placement writes on the audit trail —
   *     one per line a recall covered, and one per line weighed on a scale not fit for trade, when
   *     it was rung up; empty for every other sale, which is refused instead
   * @throws ApiException {@code 409 ORDER_LINE_RECALLED} or {@code 409 ORDER_SCALE_NOT_CERTIFIED}
   *     for a sale made now, or a replay whose capture time is not honoured
   */
  public List<OfflineSaleFlag> check(Sale sale, Instant capturedAt) {
    Timing timing = timing(capturedAt);
    List<StopSale.Stopped> recalled = checkRecalledStock(sale.tenantId(), sale.packs(), timing);
    List<TradeScales.Unfit> unfit =
        checkScales(sale.tenantId(), sale.storeId(), sale.instruments(), timing);
    if (!timing.honoured() || (recalled.isEmpty() && unfit.isEmpty())) return List.of();
    OfflineReplay.Replayed replayed =
        new OfflineReplay.Replayed(
            sale.tenantId(),
            sale.orderId(),
            sale.storeId(),
            cashierOf(sale),
            sale.sender(),
            timing.rungUpAt());
    List<OfflineSaleFlag> flags = new ArrayList<>();
    for (StopSale.Stopped s : recalled) {
      flags.add(replayed.recalledItem(Ids.newId(), s));
    }
    for (TradeScales.Unfit u : unfit) {
      flags.add(replayed.unfitScale(Ids.newId(), sale.packs().get(u.line()).variantId(), u));
    }
    return List.copyOf(flags);
  }

  /**
   * Who a flagged replay's entries name as having rung it up ({@link OfflineReplay#cashier}): the
   * member of staff the till recorded, when the sender's own sign-in or the staff directory shows
   * the business holds them at the store; null, an unknown member of staff, otherwise.
   */
  UUID cashierOf(Sale sale) {
    UUID named =
        OfflineReplay.cashier(
            sale.rungUpBy(),
            sale.sender(),
            sale.senderIsStaffHere(),
            id ->
                staff == null
                    ? Optional.empty()
                    : staff.isStaffAt(sale.tenantId(), sale.storeId(), id));
    if (named == null) {
      LOG.log(
          Level.WARNING,
          "offline till sale {0} for {1}: who rang it up ({2}) is not shown to be staff the"
              + " business holds at {3}; its entries name an unknown member of staff",
          sale.orderId(),
          sale.tenantId(),
          sale.rungUpBy(),
          sale.storeId());
    }
    return named;
  }

  /**
   * Refuses an order with a line that an open recall says must not be sold.
   *
   * @param tenantId the business, from the caller's token: only its own recalls are asked for
   * @param packs each line as the pack described itself
   * @throws ApiException {@code 409 ORDER_LINE_RECALLED}, naming each such line, the recall and
   *     what it covers
   */
  public void refuseRecalledStock(UUID tenantId, List<StopSale.Pack> packs) {
    checkRecalledStock(tenantId, packs, Timing.LIVE);
  }

  /**
   * Checks an order's lines against the business's recalls, as {@code timing} judges the sale.
   *
   * @param timing from {@link #timing}: a sale made now, or a replay whose capture time is not
   *     honoured, is refused over any line an open recall covers; a replay within the grace never
   *     is
   * @return for a replay within the grace, the lines a recall covered when it was rung up — one
   *     closed or cancelled since included — each logged; a recall opened since is logged and not
   *     returned. Empty for every other sale
   * @throws ApiException {@code 409 ORDER_LINE_RECALLED}, naming each such line, the recall and
   *     what it covers
   */
  public List<StopSale.Stopped> checkRecalledStock(
      UUID tenantId, List<StopSale.Pack> packs, Timing timing) {
    if (packs.isEmpty()) return List.of();
    Optional<List<ActiveRecall>> known =
        timing.honoured() ? recallsSince(tenantId, timing.rungUpAt()) : activeRecalls(tenantId);
    if (known.isEmpty()) {
      LOG.log(Level.WARNING, "recalls unreadable for {0}; sale placed unchecked", tenantId);
      return List.of();
    }
    List<StopSale.Stopped> now = StopSale.stopped(StopSale.openWhen(known.get(), null), packs);
    if (!timing.honoured()) {
      if (!now.isEmpty()) {
        String why = StopSale.refusal(now, timing.replayed());
        throw new ApiException(409, "ORDER_LINE_RECALLED", why, StopSale.details(now));
      }
      return List.of();
    }
    Instant rungUpAt = timing.rungUpAt();
    List<StopSale.Stopped> then = StopSale.stopped(StopSale.openWhen(known.get(), rungUpAt), packs);
    Set<Integer> flagged = then.stream().map(s -> s.pack().line()).collect(Collectors.toSet());
    for (StopSale.Stopped s : then) {
      LOG.log(
          Level.WARNING,
          "offline till sale for {0} rung up at {1} under recall {2}: item {3} placed and flagged"
              + " for a manager",
          tenantId,
          rungUpAt,
          s.recall().reference(),
          s.pack().line() + 1);
    }
    for (StopSale.Stopped late : now) {
      if (flagged.contains(late.pack().line())) continue;
      LOG.log(
          Level.WARNING,
          "offline till sale for {0} rung up at {1}, before recall {2} opened: item {3} kept",
          tenantId,
          rungUpAt,
          late.recall().reference(),
          late.pack().line() + 1);
    }
    return then;
  }

  /**
   * Refuses an order with a line weighed on a scale the order's store may not weigh for trade on:
   * one its register does not hold (unknown, another store's, another business's), or one that is
   * not certified today.
   *
   * @param tenantId the business, from the caller's token
   * @param storeId the store the order is placed at
   * @param instruments each line's instrument, null for a line sold by the each
   * @throws ApiException {@code 409 ORDER_SCALE_NOT_CERTIFIED}, naming each such line and why
   */
  public void refuseUnfitScales(UUID tenantId, UUID storeId, List<UUID> instruments) {
    checkScales(tenantId, storeId, instruments, Timing.LIVE);
  }

  /**
   * Checks each weighed line's scale against the order's store's register, as {@code timing} judges
   * the sale.
   *
   * @param timing from {@link #timing}: a sale made now, or a replay whose capture time is not
   *     honoured, is refused over any line weighed on a scale not fit for trade today; a replay
   *     within the grace never is
   * @return for a replay within the grace, the lines weighed on a scale not fit for trade when it
   *     was rung up — or one the register cannot show was fit then, or could not be read for at all
   *     — each logged; one shown to have lapsed since is logged and not returned. Empty for every
   *     other sale
   * @throws ApiException {@code 409 ORDER_SCALE_NOT_CERTIFIED}, naming each such line and why
   */
  public List<TradeScales.Unfit> checkScales(
      UUID tenantId, UUID storeId, List<UUID> instruments, Timing timing) {
    Map<UUID, TradeScales.Entry> read = new LinkedHashMap<>();
    for (UUID id : instruments.stream().filter(Objects::nonNull).distinct().toList()) {
      Optional<TradeScales.Entry> entry = tenants.weighingInstrument(tenantId, storeId, id);
      if (entry.isEmpty()) {
        LOG.log(
            Level.WARNING,
            timing.honoured()
                ? "register unreadable; scale {0} at {1} flagged as in doubt on the replay"
                : "register unreadable; scale {0} at {1} not checked",
            id,
            storeId);
      }
      entry.ifPresent(e -> read.put(id, e));
    }
    List<TradeScales.Unfit> now = TradeScales.unfit(instruments, read);
    if (!timing.honoured()) {
      if (!now.isEmpty()) {
        String why = TradeScales.refusal(now, timing.replayed());
        throw new ApiException(409, "ORDER_SCALE_NOT_CERTIFIED", why, TradeScales.details(now));
      }
      return List.of();
    }
    Instant rungUpAt = timing.rungUpAt();
    List<TradeScales.Unfit> then = TradeScales.unfit(instruments, read, rungUpAt);
    Set<Integer> flagged = then.stream().map(TradeScales.Unfit::line).collect(Collectors.toSet());
    for (TradeScales.Unfit u : then) {
      LOG.log(
          Level.WARNING,
          "offline till sale for {0} rung up at {1} on scale {2} ({3}): item {4} placed and"
              + " flagged for a manager",
          tenantId,
          rungUpAt,
          u.entry().instrumentId(),
          standingOf(u),
          u.line() + 1);
    }
    for (TradeScales.Unfit late : now) {
      if (flagged.contains(late.line())) continue;
      LOG.log(
          Level.WARNING,
          "offline till sale for {0} rung up at {1}, before scale {2} lapsed ({3}): item {4} kept",
          tenantId,
          rungUpAt,
          late.entry().instrumentId(),
          late.entry().standing(),
          late.line() + 1);
    }
    return then;
  }

  /** A scale's word on the trail and in the log: why it was unfit, or that nobody can say. */
  private static String standingOf(TradeScales.Unfit u) {
    if (u.unknownAtSale()) return OfflineSaleFlag.UNKNOWN_AT_SALE;
    return u.entry().registered() ? u.entry().standing() : OfflineSaleFlag.NOT_REGISTERED;
  }

  /** The business's open recalls, kept a few seconds; empty when inventory-svc cannot say. */
  Optional<List<ActiveRecall>> activeRecalls(UUID tenantId) {
    Instant now = clock.instant();
    Kept hit = kept.get(tenantId);
    if (hit != null && hit.readAt().plus(keepFor()).isAfter(now)) {
      return Optional.of(hit.recalls());
    }
    Optional<List<ActiveRecall>> read = recalls.active(tenantId);
    read.ifPresent(list -> kept.put(tenantId, new Kept(list, now, null)));
    return read;
  }

  /**
   * The business's recalls a replay rung up at {@code rungUpAt} is judged against: every one open
   * now and every one closed or cancelled within the grace, each saying when it opened and ended.
   * Kept as the open list is; one read serves every replay the grace still honours, since each was
   * rung up no earlier than the end it reaches back to. When it cannot be read the open list is
   * used instead: a recall ended since is then missed, never an open one.
   */
  Optional<List<ActiveRecall>> recallsSince(UUID tenantId, Instant rungUpAt) {
    Instant now = clock.instant();
    Kept hit = keptSince.get(tenantId);
    if (hit != null
        && hit.readAt().plus(keepFor()).isAfter(now)
        && !hit.since().isAfter(rungUpAt)) {
      return Optional.of(hit.recalls());
    }
    Instant since = now.minus(grace());
    if (since.isAfter(rungUpAt)) since = rungUpAt;
    Optional<List<ActiveRecall>> read = recalls.openOrEndedSince(tenantId, since);
    if (read.isPresent()) {
      keptSince.put(tenantId, new Kept(read.get(), now, since));
      return read;
    }
    LOG.log(
        Level.WARNING,
        "recalls ended since {1} unreadable for {0}; the replay is judged by the open ones",
        tenantId,
        since);
    return activeRecalls(tenantId);
  }

  private Duration keepFor() {
    return keepFor != null ? keepFor : Duration.ofSeconds(config.recallCacheSeconds());
  }

  private Duration grace() {
    return grace != null ? grace : Duration.ofHours(config.offlineReplayGraceHours());
  }

  private Duration skew() {
    return skew != null ? skew : Duration.ofSeconds(config.offlineReplayClockSkewSeconds());
  }
}
