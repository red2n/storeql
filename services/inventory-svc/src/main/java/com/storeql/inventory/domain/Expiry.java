package com.storeql.inventory.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * When stock is past its date. A batch's {@code expiry_date} is the last day it may be sold: from
 * the day after, in the store's own time zone, it still counts on hand but is never available, held
 * or drawn (except by a write-off, a return to vendor and a recall). A batch with no date is
 * unaffected. This is the one place the rule lives: pure logic for a single date, and the SQL
 * fragment every query that decides what may be sold takes its condition from.
 *
 * <p>A store's day is its zone's; a store whose zone is not known is read in UTC (a guess named
 * here and nowhere else) — the caller passes only the zones it could read.
 */
public final class Expiry {

  private final LocalDate utcDay;
  private final Map<LocalDate, Set<UUID>> otherDays;

  private Expiry(LocalDate utcDay, Map<LocalDate, Set<UUID>> otherDays) {
    this.utcDay = utcDay;
    this.otherDays = otherDays;
  }

  /**
   * The day it is at each store at this instant.
   *
   * @param now the instant
   * @param zones the zone of each store whose zone is known; any other store is read in UTC
   */
  public static Expiry at(Instant now, Map<UUID, ZoneId> zones) {
    LocalDate utc = now.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    Map<LocalDate, Set<UUID>> others = new TreeMap<>();
    for (Map.Entry<UUID, ZoneId> e : zones.entrySet()) {
      LocalDate day = now.atZone(e.getValue()).toLocalDate();
      if (!day.equals(utc)) {
        others.computeIfAbsent(day, d -> new TreeSet<>()).add(e.getKey());
      }
    }
    return new Expiry(utc, others);
  }

  /** As {@link #at}, every store read in UTC. */
  public static Expiry utc(Instant now) {
    return at(now, Map.of());
  }

  /** Whether a batch with this date may still be sold on {@code today}: its last day, yes. */
  public static boolean sellable(LocalDate expiryDate, LocalDate today) {
    return expiryDate == null || !expiryDate.isBefore(today);
  }

  /** The day it is at the store. */
  public LocalDate today(UUID storeId) {
    for (Map.Entry<LocalDate, Set<UUID>> e : otherDays.entrySet()) {
      if (e.getValue().contains(storeId)) return e.getKey();
    }
    return utcDay;
  }

  /** Whether a batch with this date, at this store, may be sold now. */
  public boolean sellableAt(UUID storeId, LocalDate expiryDate) {
    return sellable(expiryDate, today(storeId));
  }

  /**
   * The SQL condition, for a batch table aliased {@code alias} (or none), that is true for stock
   * still sellable: no date, or a date not before the store's day. The dates and store ids are
   * typed values written as literals, never text from a request.
   */
  public String sellableSql(String alias) {
    String p = alias == null || alias.isEmpty() ? "" : alias + ".";
    return "(" + p + "expiry_date IS NULL OR " + p + "expiry_date >= " + dayExpr(p) + ")";
  }

  /** The SQL condition true for stock past its date; the negation of {@link #sellableSql}. */
  public String expiredSql(String alias) {
    String p = alias == null || alias.isEmpty() ? "" : alias + ".";
    return "(" + p + "expiry_date IS NOT NULL AND " + p + "expiry_date < " + dayExpr(p) + ")";
  }

  private String dayExpr(String p) {
    if (otherDays.isEmpty()) return "DATE '" + utcDay + "'";
    StringBuilder sb = new StringBuilder("(CASE");
    for (Map.Entry<LocalDate, Set<UUID>> e : otherDays.entrySet()) {
      String ids = e.getValue().stream().map(u -> "'" + u + "'").collect(Collectors.joining(","));
      sb.append(" WHEN ").append(p).append("store_id IN (").append(ids).append(") THEN DATE '");
      sb.append(e.getKey()).append("'");
    }
    return sb.append(" ELSE DATE '").append(utcDay).append("' END)").toString();
  }
}
