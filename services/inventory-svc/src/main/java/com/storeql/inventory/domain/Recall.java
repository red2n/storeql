package com.storeql.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Product withdrawals and recalls: what is in scope, what was taken off sale, what stores did. */
public final class Recall {

  private Recall() {}

  /**
   * Batch numbers this service writes itself when stock moves, is counted or comes back. They name
   * the movement, not the supplier's lot, so a recall cannot rule such a batch out by its number.
   */
  private static final Pattern SYSTEM_BATCH_NO =
      Pattern.compile("^(ADJ|(CC|MO|TO|RET)-[0-9a-f]{8})$");

  public enum Kind {
    /** Taken off sale. */
    WITHDRAWAL,
    /** Taken off sale, and the customers who may have bought it are told. */
    RECALL;

    /**
     * Whether this kind of action obliges the business to notify customers.
     *
     * @return {@code true} for a RECALL; a WITHDRAWAL only takes stock off sale
     */
    public boolean tellsCustomers() {
      return this == RECALL;
    }
  }

  public enum Hazard {
    MICROBIOLOGICAL,
    ALLERGEN,
    FOREIGN_BODY,
    CHEMICAL,
    LABELLING,
    QUALITY,
    OTHER
  }

  public enum Source {
    SUPPLIER,
    FSA,
    FSS,
    INTERNAL,
    OTHER
  }

  public enum Status {
    OPEN,
    CLOSED,
    CANCELLED
  }

  /** What a recall offers a buyer, GPSR art.37: the buyer chooses among what is offered. */
  public enum Remedy {
    REPAIR,
    REPLACEMENT,
    REFUND;

    /** The stored form: names in declaration order, comma-separated; null for none. */
    public static String csv(Set<Remedy> remedies) {
      if (remedies == null || remedies.isEmpty()) return null;
      return EnumSet.copyOf(remedies).stream().map(Enum::name).collect(Collectors.joining(","));
    }

    /**
     * Parses the stored form.
     *
     * @return the remedies, empty for null or blank
     */
    public static Set<Remedy> parse(String csv) {
      if (csv == null || csv.isBlank()) return Set.of();
      Set<Remedy> out = EnumSet.noneOf(Remedy.class);
      for (String name : csv.split(",")) {
        out.add(valueOf(name.trim()));
      }
      return Set.copyOf(out);
    }
  }

  /**
   * What GPSR art.36(2)(c) forbids a recall notice to say: words that play the risk down. Held
   * here, not in a message catalogue, because the check is the rule.
   */
  public static final List<String> MINIMISING_PHRASES =
      List.of(
          "voluntary",
          "precautionary",
          "discretionary",
          "in rare situations",
          "in specific situations",
          "no reported accidents",
          "no accidents have been reported",
          "no incidents have been reported");

  /**
   * The first phrase in a notice that plays the risk down, if any.
   *
   * @param notice the notice text, or null
   * @return the offending phrase as the rule lists it
   */
  public static Optional<String> minimisingPhrase(String notice) {
    if (notice == null) return Optional.empty();
    String lower = notice.toLowerCase(Locale.ROOT);
    return MINIMISING_PHRASES.stream().filter(lower::contains).findFirst();
  }

  /**
   * Whether an offer of remedies satisfies GPSR art.37: at least two, or one with the reason the
   * others are impossible or disproportionate.
   */
  public static boolean remediesSufficient(Set<Remedy> remedies, String singleRemedyReason) {
    return remedies.size() >= 2
        || (remedies.size() == 1 && singleRemedyReason != null && !singleRemedyReason.isBlank());
  }

  /** How sure a recall is that a batch is affected. */
  public enum Match {
    IN_SCOPE,
    LOT_UNKNOWN,
    DATE_UNKNOWN;

    /** Only a batch that might not be affected can be checked and released. */
    public boolean isReleasable() {
      return this != IN_SCOPE;
    }

    /**
     * The more certain of two matches, so combining evidence never weakens a hold.
     *
     * <p>Certainty follows declaration order, {@code IN_SCOPE} being the most certain. A {@code
     * null} is treated as no evidence and loses to anything.
     *
     * @param a one match, or {@code null}
     * @param b the other match, or {@code null}
     * @return whichever is more certain
     */
    public static Match moreCertain(Match a, Match b) {
      if (a == null) return b;
      if (b == null) return a;
      return a.ordinal() <= b.ordinal() ? a : b;
    }
  }

  public enum QuarantinedOn {
    /** Held when the recall was opened. */
    OPEN,
    /** Held as it arrived — a delivery, transfer or return of stock already recalled. */
    ARRIVAL
  }

  public enum Disposition {
    HELD_FOR_COLLECTION,
    RETURNED_TO_SUPPLIER,
    DESTROYED;

    /** The stock has left the business, so it leaves the books. */
    public boolean isFinal() {
      return this != HELD_FOR_COLLECTION;
    }
  }

  public record Header(
      UUID id,
      UUID tenantId,
      String reference,
      Kind kind,
      Hazard hazard,
      String reason,
      String customerNotice,
      Source source,
      String sourceReference,
      Status status,
      UUID openedBy,
      Instant openedAt,
      UUID endedBy,
      Instant endedAt,
      String endNotes,
      /** What a buyer may choose from; empty for a withdrawal. */
      Set<Remedy> remedies,
      /** Why only one remedy is offered, where the law asks for two. */
      String singleRemedyReason,
      /** A free number or an online service where a buyer gets more, art.36(2)(f). */
      String contactPhone,
      String contactUrl,
      /** Sales on or after this day were looked for; null for every sale of the packs in scope. */
      LocalDate soldFrom) {

    /**
     * Whether this recall reaches out to the people who bought the product.
     *
     * @return {@code true} for a RECALL that offers at least one remedy
     */
    public boolean tellsBuyers() {
      return kind.tellsCustomers() && !remedies.isEmpty();
    }
  }

  /**
   * One sale a recall found in its scope: an order that drew from a batch the recall covers, and
   * how sure the recall is of it.
   */
  public record AffectedSale(
      UUID orderId,
      UUID storeId,
      UUID variantId,
      UUID batchId,
      String batchNo,
      LocalDate expiryDate,
      BigDecimal qty,
      Instant soldAt,
      Match match) {}

  /** One order a recall reached, with the lines of it that drew on packs in scope. */
  public record AffectedOrder(
      UUID orderId, UUID storeId, Instant soldAt, List<AffectedSale> lines) {
    public AffectedOrder {
      lines = List.copyOf(lines);
    }
  }

  /** How far a recall reached: the orders that drew on its packs, and how much they took. */
  public record Reach(int ordersAffected, BigDecimal qtySold) {
    public static final Reach NONE = new Reach(0, BigDecimal.ZERO);
  }

  /**
   * One line of a recall's scope. A line naming no lot and no dates covers every pack of the
   * variant.
   */
  public record Scope(
      UUID id, UUID variantId, String batchNo, LocalDate expiryFrom, LocalDate expiryTo) {

    /**
     * Whether this line sweeps in the whole variant rather than particular packs.
     *
     * @return {@code true} when the line names neither a lot nor a date range
     */
    public boolean coversEveryPack() {
      return batchNo == null && expiryFrom == null && expiryTo == null;
    }

    /**
     * Whether a batch of this line's variant is affected.
     *
     * <p>A known lot or date that falls outside the scope rules a batch out. One that is not known
     * cannot, so the batch is held as possibly affected: a pack nobody can rule out is withdrawn.
     *
     * @return null when the batch is certainly not affected
     */
    public Match classify(String lot, LocalDate expiry) {
      boolean lotUnknown = false;
      if (batchNo != null) {
        if (!isSupplierLot(lot)) {
          lotUnknown = true;
        } else if (!batchNo.trim().equalsIgnoreCase(lot.trim())) {
          return null;
        }
      }
      boolean dateUnknown = false;
      if (expiryFrom != null || expiryTo != null) {
        if (expiry == null) {
          dateUnknown = true;
        } else if ((expiryFrom != null && expiry.isBefore(expiryFrom))
            || (expiryTo != null && expiry.isAfter(expiryTo))) {
          return null;
        }
      }
      if (lotUnknown) return Match.LOT_UNKNOWN;
      if (dateUnknown) return Match.DATE_UNKNOWN;
      return Match.IN_SCOPE;
    }
  }

  /**
   * Whether a batch number is a supplier's lot, as opposed to one this service wrote itself for
   * stock it could not tie to one ({@code ADJ}, or {@code CC-}/{@code MO-}/{@code TO-}/{@code RET-}
   * and a short reference). Only a supplier's lot can be recalled by name, or carried across when
   * the stock moves.
   */
  public static boolean isSupplierLot(String batchNo) {
    return batchNo != null && !batchNo.isBlank() && !SYSTEM_BATCH_NO.matcher(batchNo).matches();
  }

  /** The most certain match of any line of a recall's scope, or null when none matches. */
  public static Match classify(List<Scope> scope, UUID variantId, String lot, LocalDate expiry) {
    Match best = null;
    for (Scope line : scope) {
      if (line.variantId().equals(variantId)) {
        best = Match.moreCertain(best, line.classify(lot, expiry));
      }
    }
    return best;
  }

  public record Release(String reason, UUID releasedBy, Instant releasedAt) {}

  public record HeldBatch(
      UUID batchId,
      UUID storeId,
      UUID variantId,
      String batchNo,
      LocalDate expiryDate,
      Match match,
      BigDecimal qtyAtQuarantine,
      BigDecimal remainingQty,
      QuarantinedOn quarantinedOn,
      Instant quarantinedAt,
      Release release) {

    /**
     * Whether this batch is still quarantined.
     *
     * @return {@code true} until someone checks it and releases it back to sale
     */
    public boolean isHeld() {
      return release == null;
    }
  }

  public record StoreAction(
      UUID id,
      UUID storeId,
      BigDecimal qtyFound,
      BigDecimal systemQty,
      Disposition disposition,
      boolean noticeDisplayed,
      String notes,
      UUID recordedBy,
      Instant recordedAt) {}

  public record Detail(
      Header header,
      List<Scope> scope,
      List<HeldBatch> batches,
      List<StoreAction> actions,
      Reach reach) {

    /** Stores still holding stock this recall took off sale and has not seen leave the books. */
    public Set<UUID> outstandingStores() {
      return batches.stream()
          .filter(HeldBatch::isHeld)
          .filter(b -> b.remainingQty().signum() > 0)
          .map(HeldBatch::storeId)
          .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Every store still holding a quarantined batch, whether or not any stock remains.
     *
     * <p>Wider than {@link #outstandingStores()}: a store that has cleared its shelves is still
     * affected, and still has to confirm what it did with the stock.
     *
     * @return the affected store ids, in a stable order
     */
    public Set<UUID> affectedStores() {
      return batches.stream()
          .filter(HeldBatch::isHeld)
          .map(HeldBatch::storeId)
          .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Whether any store has recorded a disposition that settles the stock for good.
     *
     * @return {@code true} once at least one action is final, e.g. destroyed or returned
     */
    public boolean hasFinalAction() {
      return actions.stream().anyMatch(a -> a.disposition().isFinal());
    }
  }

  /** A recall in a list, with the counts a manager scans for. */
  public record Summary(
      Header header,
      int scopeLines,
      int storesAffected,
      int storesOutstanding,
      BigDecimal qtyHeld,
      Reach reach) {}

  /** One scope line of an open recall, as the till checks a scanned item against it. */
  public record ActiveItem(
      UUID recallId,
      String reference,
      Kind kind,
      Hazard hazard,
      String customerNotice,
      Scope scope) {

    public ActiveItem {
      Objects.requireNonNull(scope, "scope");
    }
  }
}
