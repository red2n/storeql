package com.storeql.iam.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A user — STAFF (belongs to a tenant, or to none: a business sign-up not yet onboarded, the
 * platform administrator) or CUSTOMER (tenantId null/global). See V1__init.sql for the tenant note.
 *
 * <p>The type is also the kind of account: a shopper's account (CUSTOMER) and a business account
 * (STAFF) are separate identities, so one address may hold one of each outside any business (the
 * per-kind unique indexes in V1__init.sql).
 */
public record User(
    UUID id,
    UUID tenantId, // null for CUSTOMER
    String type, // STAFF | CUSTOMER
    String email,
    String phone,
    String passwordHash,
    String status, // ACTIVE | DELETED
    Instant createdAt,
    Instant updatedAt) {
  public static final String TYPE_STAFF = "STAFF";
  public static final String TYPE_CUSTOMER = "CUSTOMER";
  public static final String STATUS_ACTIVE = "ACTIVE";

  /**
   * The logins a password sign-in may open, in the order they are to be tried: those of the kind
   * the sign-in is made for, or — only when the address holds none of that kind — the others.
   *
   * <p>A password that happens to open the other kind's login is never enough while one of the
   * asked-for kind exists: a person signing in to shop must not land in their business, nor a
   * cashier at the till in their shopping account, just because they reuse one password. The
   * fallback keeps a person with only one kind of account signing in wherever they did before.
   *
   * @param logins every login holding the address, in the order they are to be tried
   * @param kind {@link #TYPE_CUSTOMER} for a storefront, {@link #TYPE_STAFF} for running a business
   */
  public static List<User> signInCandidates(List<User> logins, String kind) {
    List<User> asked = logins.stream().filter(u -> kind.equals(u.type())).toList();
    return asked.isEmpty() ? logins : asked;
  }

  /**
   * The logins a sign-in that says what it is may open: only those of the kind it named, never the
   * other kind. An address that holds only the other kind yields none, which the caller answers
   * exactly as an unknown address (same work, same {@code 401}).
   *
   * @param logins every login holding the address
   * @param kind {@link #TYPE_CUSTOMER} or {@link #TYPE_STAFF}
   * @return the logins of that kind, in the order given
   */
  public static List<User> ofKind(List<User> logins, String kind) {
    return logins.stream().filter(u -> kind.equals(u.type())).toList();
  }
}
