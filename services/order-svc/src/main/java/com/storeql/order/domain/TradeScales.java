package com.storeql.order.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Whether the weighing instrument a sold-by-weight line names may weigh for trade at the order's
 * store: registered in that store's register in tenant-svc, which owns it, and certified there
 * today. order-svc asks this of every line that names one, behind the till's picker, which offers
 * only certified scales, so a direct API call or another client cannot record a sale on a scale the
 * register would not allow.
 *
 * <p>Certified is the register's own word, derived there from the instrument's status and its
 * verification history (in service, latest entry a pass, not yet due again); this reads it and
 * never works it out a second way. What the certificate is under — which law, which country — is
 * the register's business too: nothing here assumes one.
 *
 * <p>A till sale replayed from the till's offline queue within the grace is never refused here
 * ({@link OfflineReplay}): the sale has been made, and refusing the record would only lose the
 * money and the stock. A line weighed on a scale that was not fit for trade at the moment it was
 * rung up is written on the audit trail for a manager instead ({@link #soldOffline}) — and so is
 * one weighed on a scale the register cannot show was fit then, since the outcome is only a flag
 * and doubt must lead to one, never to silence ({@link Entry#atSale}).
 */
public final class TradeScales {

  private TradeScales() {}

  /**
   * Why the register cannot show that a scale was fit for trade when a replayed sale was rung up,
   * in words that finish "…cannot be shown, because …".
   */
  public enum Doubt {
    /** The register's latest entry for it came after the sale; the one before is not read here. */
    CHECKED_SINCE("its latest check was recorded after the sale"),
    /** The register did not say when its latest entry was recorded. */
    CHECK_UNDATED("the register does not say when its latest check was recorded"),
    /**
     * It was changed after the sale. The register keeps no time of a change of status, only of the
     * last change of any kind, so it may have been out of service when the sale was made.
     */
    CHANGED_SINCE(
        "it was changed in the register after the sale, and the register does not keep when its"
            + " status changed"),
    /** The register did not say when it was last changed. */
    CHANGE_UNDATED("the register does not say when it was last changed"),
    /**
     * It is overdue now and the register did not give the date it fell due, so the lapse cannot be
     * placed before the sale.
     */
    DUE_UNDATED("the register does not say when it fell due for re-verification"),
    /**
     * The register could not be asked about it at all when the replay arrived: tenant-svc not
     * located, timed out or failing, or an answer that could not be read. A sale made now goes
     * ahead unchecked; a replay is flagged, since the outcome is only a flag.
     */
    REGISTER_UNREADABLE("the register could not be read when the sale was synced");

    private final String words;

    Doubt(String words) {
      this.words = words;
    }

    public String words() {
      return words;
    }
  }

  /**
   * How a scale stood when a sale was rung up, as far as the register can show.
   *
   * @param fit whether it could weigh for trade then, shown beyond doubt
   * @param doubt why that cannot be shown, when it cannot; null when the register shows it either
   *     way
   */
  public record AtSale(boolean fit, Doubt doubt) {

    static final AtSale FIT = new AtSale(true, null);
    static final AtSale UNFIT = new AtSale(false, null);

    static AtSale unknown(Doubt doubt) {
      return new AtSale(false, doubt);
    }
  }

  /**
   * One instrument as the order's store's register answers for it.
   *
   * @param registered false when the register does not hold it at this store — unknown, another
   *     store's, or another business's, which tenant-svc answers alike
   * @param identifier the name the store gives it, e.g. {@code Deli 1}; null when not registered
   * @param certified whether it may weigh for trade today
   * @param standing the register's word for it: {@code CERTIFIED}, or why not — {@code
   *     NEVER_VERIFIED}, {@code FAILED}, {@code REPAIRED_SINCE}, {@code OVERDUE}, {@code
   *     OUT_OF_SERVICE}, {@code RETIRED}
   * @param checkedAt when the register's latest history entry for it was recorded; null when it has
   *     none or did not say
   * @param nextDue that entry's due date, or null
   * @param changedAt when the instrument was last changed in the register, any change; null when
   *     the register did not say
   */
  public record Entry(
      UUID instrumentId,
      boolean registered,
      String identifier,
      boolean certified,
      String standing,
      Instant checkedAt,
      LocalDate nextDue,
      Instant changedAt) {

    /** An entry whose history the register did not give: fit or unfit now, and that is all. */
    public Entry(
        UUID instrumentId,
        boolean registered,
        String identifier,
        boolean certified,
        String standing) {
      this(instrumentId, registered, identifier, certified, standing, null, null, null);
    }

    /** The register does not hold this instrument at the order's store. */
    public static Entry notRegistered(UUID instrumentId) {
      return new Entry(instrumentId, false, null, false, null, null, null, null);
    }

    /**
     * An instrument the register could not be asked about: nothing is known of it but its id. It is
     * kept as registered only so that it is never worded as "not in this store's register", which
     * nobody said; it is never fit, and only a replay is judged on it, in doubt ({@link
     * Doubt#REGISTER_UNREADABLE}).
     */
    public static Entry unread(UUID instrumentId) {
      return new Entry(instrumentId, true, null, false, null, null, null, null);
    }

    /** Whether a sale may be weighed on it now. */
    public boolean fitForTrade() {
      return registered && certified;
    }

    /**
     * How it stood when a sale was rung up at {@code rungUpAt}: for a sale made now (null), whether
     * it is fit for trade today; for a replayed till sale, what the register can show of that
     * moment. The register keeps the time of its latest history entry and of the instrument's last
     * change, nothing finer, so a replay is judged fit only when both came before the sale and the
     * entry then in force was a pass not yet due; unfit when the register shows it was unfit then;
     * and in doubt otherwise — which a replay's entry on the trail says, since the outcome is only
     * a flag.
     *
     * <ul>
     *   <li>Not held at this store, or never verified: unfit, however early the sale.
     *   <li>Certified now: fit when its latest verification and its last change both came before
     *       the sale — a pass then in force, due no earlier than today, in service since — else in
     *       doubt.
     *   <li>{@code OVERDUE}: unfit from the end of its due date, the register counting a scale
     *       certified through the day it is due by the date in UTC, when that entry came before the
     *       sale; in doubt when after, because another entry with a later due date may have been
     *       the one in force then, and in doubt when the register did not give the due date; before
     *       the end of it, judged as a certified scale is.
     *   <li>{@code FAILED}, {@code REPAIRED_SINCE}: unfit when that entry came before the sale; in
     *       doubt when after, because the entry in force then is not read here.
     *   <li>{@code OUT_OF_SERVICE}, {@code RETIRED}: unfit when it was last changed before the
     *       sale; in doubt when after, since the register keeps no time of the status change itself
     *       and any edit moves the last change.
     * </ul>
     */
    public AtSale atSale(Instant rungUpAt) {
      if (rungUpAt == null) return fitForTrade() ? AtSale.FIT : AtSale.UNFIT;
      if (!registered) return AtSale.UNFIT;
      if (certified) return inForceAndUnchanged(rungUpAt);
      String s = standing == null ? "" : standing.strip().toUpperCase(Locale.ROOT);
      return switch (s) {
        case "OVERDUE" -> overdueAt(rungUpAt);
        case "FAILED", "REPAIRED_SINCE" -> unfitOrInDoubt(checkedSince(rungUpAt));
        case "OUT_OF_SERVICE", "RETIRED" -> unfitOrInDoubt(changedSince(rungUpAt));
        default -> AtSale.UNFIT;
      };
    }

    /**
     * Unfit from the end of its due date; before that, judged as a certified scale is. The due date
     * is that of the latest entry now, which may not be the one in force at the sale: an entry
     * performed earlier but recorded later becomes the latest and brings its own, earlier due date.
     * So the lapse is only shown to come before the sale when that entry was already recorded by
     * then; otherwise, as when the register gave no due date, it is in doubt, never a definite
     * unfit.
     */
    private AtSale overdueAt(Instant rungUpAt) {
      if (nextDue == null) return AtSale.unknown(Doubt.DUE_UNDATED);
      if (rungUpAt.isBefore(endOf(nextDue))) return inForceAndUnchanged(rungUpAt);
      return unfitOrInDoubt(checkedSince(rungUpAt));
    }

    /** Unfit, unless there is a doubt about the moment of the sale. */
    private static AtSale unfitOrInDoubt(Doubt doubt) {
      return doubt == null ? AtSale.UNFIT : AtSale.unknown(doubt);
    }

    /** A pass in force at the sale, and the instrument unchanged since: fit; else in doubt. */
    private AtSale inForceAndUnchanged(Instant rungUpAt) {
      Doubt d = checkedSince(rungUpAt);
      if (d == null) d = changedSince(rungUpAt);
      return d == null ? AtSale.FIT : AtSale.unknown(d);
    }

    /** Why the latest entry cannot be taken as the one in force at the sale; null when it can. */
    private Doubt checkedSince(Instant rungUpAt) {
      if (checkedAt == null) return Doubt.CHECK_UNDATED;
      return checkedAt.isAfter(rungUpAt) ? Doubt.CHECKED_SINCE : null;
    }

    /** Why its status now cannot be taken as its status at the sale; null when it can. */
    private Doubt changedSince(Instant rungUpAt) {
      if (changedAt == null) return Doubt.CHANGE_UNDATED;
      return changedAt.isAfter(rungUpAt) ? Doubt.CHANGED_SINCE : null;
    }
  }

  /** The first moment after a day, as the register counts days: in UTC. */
  private static Instant endOf(LocalDate day) {
    return day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  /**
   * A line weighed on an instrument that may not weigh for trade here, or — for a replayed till
   * sale — one the register cannot show was fit when the sale was rung up.
   *
   * @param doubt why it cannot be shown; null when the register shows it was unfit
   */
  public record Unfit(int line, Entry entry, Doubt doubt) {

    /** A line weighed on an instrument the register shows unfit. */
    public Unfit(int line, Entry entry) {
      this(line, entry, null);
    }

    /** Whether the register cannot show how the scale stood when the sale was rung up. */
    public boolean unknownAtSale() {
      return doubt != null;
    }
  }

  /**
   * Every line whose instrument the register refuses, in the order's own order. A line with no
   * instrument, or one the register could not be asked about, is not among them: a sale made now
   * fails open.
   *
   * @param instruments each line's instrument, null for a line sold by the each
   * @param read what the register said of each instrument it answered for
   */
  public static List<Unfit> unfit(List<UUID> instruments, Map<UUID, Entry> read) {
    return unfit(instruments, read, null);
  }

  /**
   * Every line whose instrument the register refuses for a sale rung up at {@code rungUpAt}: now
   * (null), or for a replayed till sale, as at the moment it was rung up — a line whose scale the
   * register cannot show was fit then included, saying why ({@link Entry#atSale}).
   *
   * <p>A line whose instrument is not in {@code read} is one the register could not be asked about.
   * A sale made now is not judged on it: an unreadable register never refuses a sale. A replay is
   * flagged for it as in doubt ({@link Doubt#REGISTER_UNREADABLE}): it is never refused anyway, and
   * a register nobody could read is the plainest doubt there is.
   *
   * @param read what the register said of each instrument it answered for
   */
  public static List<Unfit> unfit(List<UUID> instruments, Map<UUID, Entry> read, Instant rungUpAt) {
    List<Unfit> out = new ArrayList<>();
    for (int i = 0; i < instruments.size(); i++) {
      UUID id = instruments.get(i);
      if (id == null) continue;
      Entry e = read.get(id);
      if (e == null) {
        if (rungUpAt != null) {
          out.add(new Unfit(i, Entry.unread(id), Doubt.REGISTER_UNREADABLE));
        }
        continue;
      }
      AtSale then = e.atSale(rungUpAt);
      if (!then.fit()) out.add(new Unfit(i, e, then.doubt()));
    }
    return List.copyOf(out);
  }

  /** What the refusal says, in words a cashier can act on. */
  public static String refusal(List<Unfit> unfit) {
    return refusal(unfit, false);
  }

  /**
   * What the refusal says. A replayed till sale is refused only when its capture time is not
   * honoured, and then it is judged as a sale made now; the goods have gone, so there is nothing to
   * weigh again: it is for a manager.
   *
   * @param replayed whether the sale was rung up earlier, on a till that was offline
   */
  public static String refusal(List<Unfit> unfit, boolean replayed) {
    List<String> parts = new ArrayList<>();
    for (Unfit u : unfit) {
      parts.add("item " + (u.line() + 1) + " was weighed on " + scaleWords(u.entry()));
    }
    if (replayed) {
      return OfflineReplay.JUDGED_NOW
          + ", and it was weighed on a scale that may not be used for trade here: "
          + String.join("; ", parts)
          + ". Hand the sale to a manager.";
    }
    return "Sold by weight on a scale that may not be used for trade here: "
        + String.join("; ", parts)
        + ". Weigh "
        + (unfit.size() == 1 ? "it" : "them")
        + " again on a scale certified at this store.";
  }

  /** One line per refused item, naming the order line and the instrument. */
  public static List<String> details(List<Unfit> unfit) {
    return unfit.stream()
        .map(
            u ->
                "items["
                    + u.line()
                    + "].weighingInstrumentId "
                    + u.entry().instrumentId()
                    + ": "
                    + registerWords(u.entry()))
        .toList();
  }

  /**
   * What the audit trail says of a line a replayed till sale weighed on a scale that was not fit
   * for trade at the order's store when it was rung up: which item, which scale and why.
   */
  public static String soldOffline(Unfit u) {
    Entry e = u.entry();
    String item = "Sold while the till was offline: item " + (u.line() + 1) + " was weighed on ";
    if (u.doubt() == Doubt.REGISTER_UNREADABLE) {
      // Nothing is known of the scale but its id, so nothing is said of what it is now.
      return item
          + "a scale; whether it could be used for trade here when the sale was rung up cannot be"
          + " shown, because "
          + u.doubt().words()
          + ".";
    }
    if (!e.registered()) return item + scaleWords(e) + ".";
    String name = e.identifier() == null || e.identifier().isBlank() ? "a scale" : e.identifier();
    if (u.unknownAtSale()) {
      return item
          + name
          + ", which "
          + standingWords(e.standing())
          + " now; whether it could be used for trade here when the sale was rung up cannot be"
          + " shown, because "
          + u.doubt().words()
          + ".";
    }
    return item
        + name
        + " when it could not be used for trade here (it "
        + standingWords(e.standing())
        + ").";
  }

  private static String registerWords(Entry e) {
    return e.registered() ? "standing " + e.standing() : "not in this store's register";
  }

  static String scaleWords(Entry e) {
    if (!e.registered()) {
      return "a scale that is not in this store's register of weighing instruments";
    }
    String name = e.identifier() == null || e.identifier().isBlank() ? "a scale" : e.identifier();
    return name + ", which " + standingWords(e.standing());
  }

  /** The register's standing, as the till's register screen words it. */
  static String standingWords(String standing) {
    String s = standing == null ? "" : standing.strip().toUpperCase(Locale.ROOT);
    return switch (s) {
      case "NEVER_VERIFIED" -> "has never been verified";
      case "FAILED" -> "failed its last check";
      case "REPAIRED_SINCE" -> "has been repaired since it was last verified";
      case "OVERDUE" -> "is overdue for re-verification";
      case "OUT_OF_SERVICE" -> "is out of service";
      case "RETIRED" -> "is retired";
      case "CERTIFIED" -> "is certified for trade";
      default -> "is not certified for trade";
    };
  }
}
