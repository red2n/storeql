package com.storeql.order.domain;

import com.storeql.order.domain.Domain.OfflineSaleFlag;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * When a till sale was rung up, and what that means for the checks that would stop it: open recalls
 * ({@link StopSale}) and certified scales ({@link TradeScales}).
 *
 * <p>A till that loses its network completes the sale anyway — the customer pays and leaves with
 * the goods — and replays it later from its offline queue, saying when the cashier completed it. As
 * at every till that sells offline, that sale has already happened: refusing its record cannot
 * unsell the jar, it only loses the money, the stock and the buyer a recall is trying to reach. So
 * a replay within the grace is recorded whatever the recall list or the scale register says, and
 * each line a recall covered, or a scale not fit for trade weighed, at the moment it was rung up is
 * put in front of a manager on the audit trail ({@link OfflineSaleFlag}) instead.
 *
 * <p>That moment is the till's word, so it is bounded: never further back than the grace allowed,
 * and never later than now beyond a small tolerance for a till whose clock runs a little ahead,
 * which is taken as captured now. A capture time outside those bounds cannot be told from a forged
 * one, so that sale is judged as a sale made now — refused as any sale is, in words for a manager,
 * and the till parks it for one.
 *
 * <p>Each entry names who rang the sale up as the till recorded it, not whoever's session sends the
 * queue — a queue outlives a sign-out, and a manager may press Sync now — and only when that is a
 * login the business holds at the store ({@link #cashier}); it also keeps who sent it.
 */
public final class OfflineReplay {

  /**
   * The start of the refusal of a replay whose capture time is not honoured, before what stopped
   * it. It says why a sale the till made offline is refused at all, so a manager is not left
   * wondering why other replays went through.
   */
  public static final String JUDGED_NOW =
      "This sale was rung up while the till was offline, at a time too long ago to be taken on the"
          + " till's word (or one later than now), so it is judged as a sale made now";

  private OfflineReplay() {}

  /**
   * How a sale is judged.
   *
   * @param rungUpAt when a replay within the grace was rung up; null for a sale judged as made now
   * @param replayed whether the till sent it from its offline queue, saying when it was rung up
   */
  public record Timing(Instant rungUpAt, boolean replayed) {

    /** A sale made now: at a till that is online, and every online order. */
    public static final Timing LIVE = new Timing(null, false);

    /** A replay whose capture time is not honoured: judged as made now, refused for a manager. */
    public static final Timing NOT_HONOURED = new Timing(null, true);

    public Timing {
      if (rungUpAt != null && !replayed) {
        throw new IllegalArgumentException("only a replayed sale is rung up before now");
      }
    }

    /** A replay within the grace, rung up at {@code rungUpAt}. */
    public static Timing honouredAt(Instant rungUpAt) {
      return new Timing(Objects.requireNonNull(rungUpAt, "rungUpAt"), true);
    }

    /** Whether the sale is recorded whatever the checks say, and flagged for a manager instead. */
    public boolean honoured() {
      return rungUpAt != null;
    }
  }

  /**
   * How a sale is judged, with no tolerance for a till clock ahead of ours.
   *
   * @see #timing(Instant, Instant, Duration, Duration)
   */
  public static Timing timing(Instant capturedAt, Instant now, Duration grace) {
    return timing(capturedAt, now, grace, Duration.ZERO);
  }

  /**
   * How a sale is judged.
   *
   * <p>A sale that says it was rung up a moment ago is honoured and flagged like any replay, even
   * when it was in fact made now at a till that is online. That is accepted on purpose: its lines
   * are recorded, and every one a recall or an unfit scale would have stopped is put in front of a
   * manager with who rang it up and who sent it — the flag is the control. What the bounds stop is
   * a claim that cannot be told from a forged one: further back than the grace, or later than now
   * beyond the tolerance.
   *
   * @param capturedAt when the till says the cashier completed it, or null for a sale made now
   * @param now the present
   * @param grace how far back a capture time is honoured; zero honours none
   * @param skew how far after now a capture time is still taken as a till clock a little ahead of
   *     ours, and honoured as captured now; zero takes none
   * @return {@link Timing#LIVE} for a sale made now; a replay captured no further back than the
   *     grace and no later than now, honoured at its capture time; one captured after now but
   *     within the tolerance, honoured at now; any other replay {@link Timing#NOT_HONOURED}
   */
  public static Timing timing(Instant capturedAt, Instant now, Duration grace, Duration skew) {
    if (capturedAt == null) return Timing.LIVE;
    if (grace.isZero() || grace.isNegative()) return Timing.NOT_HONOURED;
    if (capturedAt.isAfter(now)) {
      Duration ahead = skew == null || skew.isNegative() ? Duration.ZERO : skew;
      return capturedAt.isAfter(now.plus(ahead)) ? Timing.NOT_HONOURED : Timing.honouredAt(now);
    }
    if (capturedAt.isBefore(now.minus(grace))) return Timing.NOT_HONOURED;
    return Timing.honouredAt(capturedAt);
  }

  /**
   * Who a replayed sale's entries name as having rung it up: the member of staff the till recorded
   * at the sale, and only when that is a login of this business allowed at this store — else
   * nobody, and the entry says an unknown member of staff rang it up. Never the sender by default:
   * the queue outlives a sign-out, and whoever presses Sync now did not necessarily make the sale.
   *
   * @param rungUpBy who the till says rang it up; null when it did not say (a queue kept by an
   *     older till)
   * @param sender who is sending the replay now
   * @param senderIsStaffHere whether the sender's own sign-in is staff of this business at this
   *     store — then a till naming the sender needs no second question
   * @param staffHere asks the business's staff directory whether a login is its staff at this
   *     store; empty when that cannot be told, and the entry then names nobody
   * @return the member of staff to name, or null for an unknown member of staff
   */
  public static UUID cashier(
      UUID rungUpBy,
      UUID sender,
      boolean senderIsStaffHere,
      Function<UUID, Optional<Boolean>> staffHere) {
    if (rungUpBy == null) return null;
    if (rungUpBy.equals(sender) && senderIsStaffHere) return rungUpBy;
    return staffHere.apply(rungUpBy).orElse(false) ? rungUpBy : null;
  }

  /**
   * Where and when a replay within the grace was rung up, for the entries it leaves on the audit
   * trail.
   *
   * @param cashierId who rang it up ({@link #cashier}); null for an unknown member of staff
   * @param replayedBy who sent it from the queue, as the sender's sign-in says
   */
  public record Replayed(
      UUID tenantId,
      UUID orderId,
      UUID storeId,
      UUID cashierId,
      UUID replayedBy,
      Instant rungUpAt) {

    /** The entry for a line a recall covered when it was rung up. */
    public OfflineSaleFlag recalledItem(UUID id, StopSale.Stopped stopped) {
      StopSale.Pack pack = stopped.pack();
      StopSale.ActiveRecall recall = stopped.recall();
      return new OfflineSaleFlag(
          id,
          tenantId,
          orderId,
          storeId,
          OfflineSaleFlag.KIND_RECALLED_ITEM,
          pack.line() + 1,
          pack.variantId(),
          pack.batchNo(),
          pack.expiry(),
          recall.recallId(),
          recall.reference(),
          null,
          null,
          rungUpAt,
          StopSale.soldOffline(stopped),
          cashierId,
          replayedBy);
    }

    /**
     * The entry for a line weighed, when it was rung up, on a scale not fit for trade here — or on
     * one the register cannot show was fit then, kept as {@link OfflineSaleFlag#UNKNOWN_AT_SALE}.
     */
    public OfflineSaleFlag unfitScale(UUID id, UUID variantId, TradeScales.Unfit unfit) {
      TradeScales.Entry scale = unfit.entry();
      return new OfflineSaleFlag(
          id,
          tenantId,
          orderId,
          storeId,
          OfflineSaleFlag.KIND_UNFIT_SCALE,
          unfit.line() + 1,
          variantId,
          null,
          null,
          null,
          null,
          scale.instrumentId(),
          standingOf(unfit),
          rungUpAt,
          TradeScales.soldOffline(unfit),
          cashierId,
          replayedBy);
    }

    private static String standingOf(TradeScales.Unfit unfit) {
      if (unfit.unknownAtSale()) return OfflineSaleFlag.UNKNOWN_AT_SALE;
      TradeScales.Entry scale = unfit.entry();
      return scale.registered() ? scale.standing() : OfflineSaleFlag.NOT_REGISTERED;
    }
  }
}
