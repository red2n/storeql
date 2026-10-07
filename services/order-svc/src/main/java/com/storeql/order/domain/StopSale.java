package com.storeql.order.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Stop-sale at checkout: whether an order line is stock that an open recall or withdrawal says must
 * not be sold. order-svc asks this of every order, at the till and online alike, behind the till's
 * own check ({@code pos_recall_check.dart}), so a direct API call or another client cannot sell
 * what the till would have stopped.
 *
 * <p>The rule is the till's, line for line, so the two never disagree about a pack. A recall line
 * that names no lot and no dates covers every pack of its variant. One that names a lot, dates or
 * both covers a pack only when the pack said what it is (a GS1 2D code carries its lot and expiry)
 * and falls inside every half the recall names; an absent half means "any". The lot is compared as
 * inventory-svc compares it, trimmed and case aside, since the same lot is printed both ways.
 *
 * <p>What the till does not refuse outright is not refused here either. A pack that said nothing
 * about itself, against a recall scoped to lots or dates, is the cashier's to check: the till shows
 * what to look for and lets the sale go on when the pack is not affected. Online, the recall has
 * already quarantined every batch it might be, so no hold can draw one.
 *
 * <p>A recall belongs to the whole business in inventory-svc: it names no store, so a line is
 * judged the same at every store of the business, and never by another business's recalls.
 *
 * <p>A till sale replayed from the till's offline queue within the grace is never refused here
 * ({@link OfflineReplay}): the sale has been made, and refusing the record would only lose the
 * money, the stock and the buyer the recall must reach. What the recalls open when it was rung up
 * would have stopped is written on the audit trail for a manager instead ({@link #soldOffline}), a
 * recall closed or cancelled since included: it covered the sale when the sale was made.
 */
public final class StopSale {

  private StopSale() {}

  /**
   * One scope line of an open recall, as inventory-svc's active list gives it.
   *
   * @param kind {@code WITHDRAWAL} (taken off sale) or {@code RECALL} (customers are told as well)
   * @param hazard what is wrong with it, e.g. {@code ALLERGEN}
   * @param batchNo the lot recalled, or null for any lot
   * @param expiryFrom the first expiry recalled, inclusive, or null for no lower bound
   * @param expiryTo the last expiry recalled, inclusive, or null for no upper bound
   * @param openedAt when the recall was opened, or null when inventory-svc did not say — then it is
   *     taken as open before any sale
   * @param endedAt when it was closed or cancelled, or null while it is open. Only the read made
   *     for a replayed till sale carries ended recalls: one that ended after the sale was rung up
   *     still covered it then
   * @param endedAs how it ended — {@code CLOSED}, or {@code CANCELLED} as raised in error — or null
   *     while it is open
   */
  public record ActiveRecall(
      UUID recallId,
      String reference,
      String kind,
      String hazard,
      UUID variantId,
      String batchNo,
      LocalDate expiryFrom,
      LocalDate expiryTo,
      Instant openedAt,
      Instant endedAt,
      String endedAs) {

    public ActiveRecall {
      batchNo = batchNo == null || batchNo.isBlank() ? null : batchNo.strip();
    }

    /** A recall line whose opening nobody said: open before any sale. */
    public ActiveRecall(
        UUID recallId,
        String reference,
        String kind,
        String hazard,
        UUID variantId,
        String batchNo,
        LocalDate expiryFrom,
        LocalDate expiryTo) {
      this(recallId, reference, kind, hazard, variantId, batchNo, expiryFrom, expiryTo, null);
    }

    /** An open recall line, saying when it opened. */
    public ActiveRecall(
        UUID recallId,
        String reference,
        String kind,
        String hazard,
        UUID variantId,
        String batchNo,
        LocalDate expiryFrom,
        LocalDate expiryTo,
        Instant openedAt) {
      this(
          recallId,
          reference,
          kind,
          hazard,
          variantId,
          batchNo,
          expiryFrom,
          expiryTo,
          openedAt,
          null,
          null);
    }

    /** Whether it opened after a sale rung up at {@code rungUpAt}; never for a sale made now. */
    public boolean openedAfter(Instant rungUpAt) {
      return rungUpAt != null && openedAt != null && openedAt.isAfter(rungUpAt);
    }

    /**
     * Whether it had already ended when a sale was rung up at {@code rungUpAt}; for a sale made now
     * (null), whether it has ended at all. One that ended at the very moment of the sale is taken
     * as open then: a replay is flagged when in doubt, never passed.
     */
    public boolean endedBefore(Instant rungUpAt) {
      return endedAt != null && (rungUpAt == null || endedAt.isBefore(rungUpAt));
    }

    /** True when the recall names no lot and no dates: every pack is affected. */
    public boolean coversEveryPack() {
      return batchNo == null && expiryFrom == null && expiryTo == null;
    }

    /**
     * Whether this line covers a pack whose lot and expiry are as given. Both halves must agree; a
     * half the recall does not name is "any", and one it names but the pack did not say is not a
     * match — that pack is for the cashier to read, not for this rule to guess about.
     */
    public boolean covers(String lot, LocalDate expiry) {
      if (batchNo != null && (lot == null || !batchNo.equalsIgnoreCase(lot))) return false;
      if (expiryFrom != null || expiryTo != null) {
        if (expiry == null) return false;
        if (expiryFrom != null && expiry.isBefore(expiryFrom)) return false;
        if (expiryTo != null && expiry.isAfter(expiryTo)) return false;
      }
      return true;
    }
  }

  /**
   * One order line as the pack described itself.
   *
   * @param line the line's position in the order, from zero
   * @param batchNo the lot the pack declared, or null when it declared none
   * @param expiry the expiry the pack declared, or null when it declared none
   */
  public record Pack(int line, UUID variantId, String batchNo, LocalDate expiry) {

    public Pack {
      batchNo = batchNo == null || batchNo.isBlank() ? null : batchNo.strip();
    }
  }

  /** A line that must not be sold, and the recall that says so. */
  public record Stopped(Pack pack, ActiveRecall recall) {}

  /**
   * The recall that stops this pack, if any: first one covering every pack of the variant, then one
   * scoped to the lot or dates the pack itself declared.
   *
   * <p>A replay is judged against recalls ended since as well, and leaves one entry per line. When
   * several covered the pack, the entry names one that is still open, so a manager reading it is
   * not told the recall has been closed while another still stops the product and still has to
   * reach the buyer. An ended one is named only when no open one covers the pack.
   */
  public static Optional<ActiveRecall> stopping(List<ActiveRecall> active, Pack pack) {
    List<ActiveRecall> matching =
        active.stream().filter(r -> r.variantId().equals(pack.variantId())).toList();
    Optional<ActiveRecall> open =
        coveringFirst(matching.stream().filter(r -> r.endedAt() == null).toList(), pack);
    if (open.isPresent()) return open;
    return coveringFirst(matching.stream().filter(r -> r.endedAt() != null).toList(), pack);
  }

  /** The first of {@code recalls} covering every pack, else the first covering what it declared. */
  private static Optional<ActiveRecall> coveringFirst(List<ActiveRecall> recalls, Pack pack) {
    for (ActiveRecall r : recalls) {
      if (r.coversEveryPack()) return Optional.of(r);
    }
    if (pack.batchNo() == null && pack.expiry() == null) return Optional.empty();
    return recalls.stream().filter(r -> r.covers(pack.batchNo(), pack.expiry())).findFirst();
  }

  /**
   * The recalls that were open when a sale was rung up: for a sale made now (null), every one not
   * ended; for a replayed till sale, those opened by then and not yet ended then — one closed or
   * cancelled since still covered the sale when it was made.
   */
  public static List<ActiveRecall> openWhen(List<ActiveRecall> active, Instant rungUpAt) {
    return active.stream()
        .filter(r -> !r.openedAfter(rungUpAt) && !r.endedBefore(rungUpAt))
        .toList();
  }

  /** Every line of an order that must not be sold, in the order's own order. */
  public static List<Stopped> stopped(List<ActiveRecall> active, List<Pack> packs) {
    List<Stopped> out = new ArrayList<>();
    if (active.isEmpty()) return out;
    for (Pack p : packs) {
      stopping(active, p).ifPresent(r -> out.add(new Stopped(p, r)));
    }
    return List.copyOf(out);
  }

  /**
   * What the refusal says, in words a cashier can act on: which item, which recall and why, what it
   * covers, and what to do with the item.
   */
  public static String refusal(List<Stopped> stopped) {
    return refusal(stopped, false);
  }

  /**
   * What the refusal says. A replayed till sale is refused only when its capture time is not
   * honoured, and then it is judged as a sale made now; the goods have gone, so there is nothing to
   * take out of it: it is for a manager.
   *
   * @param replayed whether the sale was rung up earlier, on a till that was offline
   */
  public static String refusal(List<Stopped> stopped, boolean replayed) {
    List<String> parts = new ArrayList<>();
    for (Stopped s : stopped) {
      ActiveRecall r = s.recall();
      parts.add(
          "item "
              + (s.pack().line() + 1)
              + " is under "
              + kindWords(r.kind())
              + " "
              + r.reference()
              + " ("
              + hazardWords(r.hazard())
              + "), "
              + scopeWords(r));
    }
    boolean one = stopped.size() == 1;
    if (replayed) {
      return OfflineReplay.JUDGED_NOW
          + ", and its stock is stopped from sale: "
          + String.join("; ", parts)
          + ". Hand the sale to a manager.";
    }
    return "This sale has stock that must not be sold: "
        + String.join("; ", parts)
        + ". Take "
        + (one ? "it" : "them")
        + " out of the sale and hand "
        + (one ? "it" : "them")
        + " to a supervisor.";
  }

  /** One line per refused item, naming the order line and the variant for a client to find it. */
  public static List<String> details(List<Stopped> stopped) {
    return stopped.stream()
        .map(
            s ->
                "items["
                    + s.pack().line()
                    + "]: variant "
                    + s.pack().variantId()
                    + " — "
                    + recallWords(s.recall()))
        .toList();
  }

  /**
   * What the audit trail says of a line a replayed till sale sold under a recall that covered it
   * when it was rung up: which item, what the pack said of itself, and which recall.
   */
  public static String soldOffline(Stopped s) {
    ActiveRecall r = s.recall();
    return "Sold while the till was offline: when it was rung up, item "
        + (s.pack().line() + 1)
        + packWords(s.pack())
        + " was under "
        + kindWords(r.kind())
        + " "
        + r.reference()
        + " ("
        + hazardWords(r.hazard())
        + "), "
        + scopeWords(r)
        + "."
        + endedWords(r);
  }

  /** How a recall that covered the sale has ended since, for a manager reading the entry. */
  private static String endedWords(ActiveRecall r) {
    if (r.endedAt() == null) return "";
    return "CANCELLED".equals(upper(r.endedAs()))
        ? " The recall has since been cancelled as raised in error."
        : " The recall has since been closed.";
  }

  /** The lot and expiry a pack declared, in brackets, or nothing when it declared neither. */
  private static String packWords(Pack p) {
    List<String> parts = new ArrayList<>();
    if (p.batchNo() != null) parts.add("lot " + p.batchNo());
    if (p.expiry() != null) parts.add("best before " + p.expiry());
    return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
  }

  private static String recallWords(ActiveRecall r) {
    return kindWords(r.kind()) + " " + r.reference() + ", " + scopeWords(r);
  }

  /** What the recall covers, in the words printed on the pack. */
  static String scopeWords(ActiveRecall r) {
    if (r.coversEveryPack()) return "every pack";
    List<String> parts = new ArrayList<>();
    if (r.batchNo() != null) parts.add("lot " + r.batchNo());
    if (r.expiryFrom() != null && r.expiryTo() != null) {
      parts.add("best before " + r.expiryFrom() + " to " + r.expiryTo());
    } else if (r.expiryFrom() != null) {
      parts.add("best before " + r.expiryFrom() + " or later");
    } else if (r.expiryTo() != null) {
      parts.add("best before " + r.expiryTo() + " or earlier");
    }
    return String.join(", ", parts);
  }

  static String kindWords(String kind) {
    return "WITHDRAWAL".equals(upper(kind)) ? "product withdrawal" : "product recall";
  }

  /** The hazard as the till names it. */
  static String hazardWords(String hazard) {
    return switch (upper(hazard)) {
      case "MICROBIOLOGICAL" -> "microbiological contamination";
      case "ALLERGEN" -> "undeclared allergen";
      case "FOREIGN_BODY" -> "foreign body";
      case "CHEMICAL" -> "chemical contamination";
      case "LABELLING" -> "labelling error";
      case "QUALITY" -> "quality defect";
      default -> "safety issue";
    };
  }

  private static String upper(String s) {
    return s == null ? "" : s.strip().toUpperCase(Locale.ROOT);
  }
}
