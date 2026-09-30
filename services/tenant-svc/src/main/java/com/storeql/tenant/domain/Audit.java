package com.storeql.tenant.domain;

import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The business's own change log: who changed a store's status or till-phone setting, who was given
 * or lost a role, who defined or changed a custom role. Pure — the repository writes an entry on
 * the transaction of the change it describes, and the log is append-only.
 */
public final class Audit {

  private Audit() {}

  /** What the log adds to a role when the assignment is business-wide (no store). */
  public static final String BUSINESS_WIDE = " (business-wide)";

  public static final String STORE_CREATED = "STORE_CREATED";
  public static final String STORE_STATUS_CHANGED = "STORE_STATUS_CHANGED";
  public static final String STORE_TILL_PHONE_CHANGED = "STORE_TILL_PHONE_CHANGED";
  public static final String STAFF_ASSIGNED = "STAFF_ASSIGNED";
  public static final String STAFF_UNASSIGNED = "STAFF_UNASSIGNED";
  public static final String ROLE_DEFINED = "ROLE_DEFINED";
  public static final String ROLE_CHANGED = "ROLE_CHANGED";
  public static final String ROLE_DELETED = "ROLE_DELETED";

  /** Every type an entry can carry, and so every value the {@code type} filter accepts. */
  public static final Set<String> TYPES =
      Set.of(
          STORE_CREATED,
          STORE_STATUS_CHANGED,
          STORE_TILL_PHONE_CHANGED,
          STAFF_ASSIGNED,
          STAFF_UNASSIGNED,
          ROLE_DEFINED,
          ROLE_CHANGED,
          ROLE_DELETED);

  /**
   * One entry. {@code storeId} is null for a change that concerns the whole business; {@code
   * actorId} is null when nobody made it (a system step).
   */
  public record Entry(
      UUID id,
      UUID tenantId,
      String type,
      UUID actorId,
      UUID storeId,
      UUID subjectId,
      String subjectCode,
      String fromValue,
      String toValue,
      Instant occurredAt) {

    /** A new entry stamped now, with its own id. */
    public static Entry of(
        UUID tenantId,
        String type,
        UUID actorId,
        UUID storeId,
        UUID subjectId,
        String subjectCode,
        String fromValue,
        String toValue) {
      return new Entry(
          Ids.newId(),
          tenantId,
          type,
          actorId,
          storeId,
          subjectId,
          subjectCode,
          fromValue,
          toValue,
          Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
  }

  /** What a read narrows to; every field but the tenant may be null. */
  public record Filter(String type, UUID actorId, UUID storeId, Instant from, Instant to) {}

  /**
   * The type filter, upper-cased; null when none is asked for.
   *
   * @throws ApiException 400 {@code AUDIT_TYPE_INVALID} for a type no entry carries
   */
  public static String type(String requested) {
    if (requested == null || requested.isBlank()) return null;
    String type = requested.trim().toUpperCase(Locale.ROOT);
    if (!TYPES.contains(type)) {
      throw ApiException.badRequest(
          "AUDIT_TYPE_INVALID",
          "type must be one of " + List.copyOf(new java.util.TreeSet<>(TYPES)));
    }
    return type;
  }

  /**
   * Checks the window: {@code from} may not come after {@code to}.
   *
   * @throws ApiException 400 {@code AUDIT_RANGE_INVALID}
   */
  public static void requireOrderedWindow(Instant from, Instant to) {
    if (from != null && to != null && from.isAfter(to)) {
      throw ApiException.badRequest("AUDIT_RANGE_INVALID", "from must not be after to");
    }
  }

  /** A permission set as the log writes it: sorted, comma-separated, empty for none. */
  public static String permissions(Set<String> permissions) {
    return String.join(",", new java.util.TreeSet<>(permissions));
  }
}
