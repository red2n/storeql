package com.storeql.iam.repo;

import com.storeql.iam.domain.StaffLogin;
import com.storeql.iam.domain.TokenIdentity;
import com.storeql.iam.domain.User;
import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence for users, roles, refresh tokens, audit, and the outbox.
 *
 * <p>JDBC (template baseline). Write paths that must be atomic with the outbox (e.g. register) use
 * {@link #createUserWithOutbox} so the user row and the {@code UserRegistered} outbox row commit
 * together. Implements {@link com.storeql.service.OutboxStore} so the shared OutboxPublisher can
 * drain its outbox.
 */
@ApplicationScoped
public class UserRepository extends BaseOutboxRepository {

  // --- lookups ---

  private static final String SELECT_COLS =
      "id, tenant_id, type, email, phone, password_hash, status, created_at, updated_at";

  /**
   * The business's login with this email: one at most (uq_users_business_email).
   *
   * <p>A business only. Outside any business an address is one login of each kind — a shopper's and
   * a business sign-up's may share it — so "the login of no business with this email" is not one
   * row; a caller there reads {@link #findAllByEmail} and chooses by kind.
   *
   * @param tenantId the business; never null
   */
  public Optional<User> findByEmail(UUID tenantId, String email) {
    java.util.Objects.requireNonNull(tenantId, "tenantId: outside a business, choose by kind");
    return query(
            "SELECT " + SELECT_COLS + " FROM users WHERE tenant_id = ? AND lower(email) = lower(?)",
            ps -> {
              ps.setObject(1, tenantId);
              ps.setString(2, email);
            },
            UserRepository::map,
            "find user by email")
        .stream()
        .findFirst();
  }

  /**
   * Every login with this email, in every business and outside any, newest first.
   *
   * <p>An address is unique within a business (uq_users_business_email) and, outside any, once per
   * kind of login (uq_users_unbound_email), so it may name several logins: sign-in chooses among
   * them by kind and password, a forgotten password answers each. Newest first so the choice
   * between two logins of one kind that share a password is always the same one — the account the
   * person made last — rather than whichever row the table returned.
   */
  public List<User> findAllByEmail(String email) {
    return query(
        "SELECT "
            + SELECT_COLS
            + " FROM users WHERE lower(email) = lower(?) ORDER BY created_at DESC, id DESC",
        ps -> ps.setString(1, email),
        UserRepository::map,
        "find users by email");
  }

  /**
   * Looks a user up by primary key.
   *
   * <p>Not tenant-scoped: the user id is globally unique and the row itself carries the tenant, so
   * callers acting on behalf of a tenant must check {@link User#tenantId()} before trusting it.
   *
   * @param id the user to fetch
   * @return the user, or empty when no such user exists
   */
  public Optional<User> findById(UUID id) {
    return query(
            "SELECT " + SELECT_COLS + " FROM users WHERE id = ?",
            ps -> ps.setObject(1, id),
            UserRepository::map,
            "find user by id")
        .stream()
        .findFirst();
  }

  /**
   * The business's staff among the ids, by email. The tenant is the first condition: a login that
   * is another business's, a customer's (no tenant), or that nobody holds is simply not a row here.
   *
   * <p>A caller held to one or more stores names only staff who hold a role at one of those stores
   * or a business-wide role ({@code user_roles.store_id IS NULL}) in the caller's business —
   * filtered in the database, never by fetching every row and checking in Java. A caller held to no
   * store (an owner, a business-wide manager, the platform admin) names every login of the business
   * that holds a staff role — never one provisioned and not yet assigned anywhere, nor one let go.
   *
   * @param tenantId the caller's business, from the token
   * @param ids the user ids to name; at most a hundred, already read as UUIDv7s
   * @param storeIds the caller's stores ({@code TenantContext.storeIds()}); empty means
   *     unrestricted
   * @return one entry per staff login found, ordered by email
   */
  public List<StaffLogin> staffLogins(UUID tenantId, List<UUID> ids, Set<UUID> storeIds) {
    String sql =
        "SELECT u.id, u.email FROM users u"
            + " WHERE u.tenant_id = ? AND u.id = ANY(?) AND u.type = 'STAFF'"
            + " AND u.email IS NOT NULL"
            + (storeIds.isEmpty()
                // A provisioned login is made in the business before it is assigned anywhere, and
                // stays in it when let go of its last store: one that holds no staff role is
                // nobody's staff, and is not named.
                ? " AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
                    + " WHERE ur.user_id = u.id AND r.name NOT IN ('CUSTOMER', 'PLATFORM_ADMIN'))"
                // A role held at no store is business-wide only when it is a staff role: a login
                // that signed up as a shopper first keeps its CUSTOMER role, store-less, and that
                // must not name it to a manager of every store.
                : " AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
                    + " WHERE ur.user_id = u.id AND (ur.store_id = ANY(?)"
                    + " OR (ur.store_id IS NULL AND r.name <> 'CUSTOMER')))")
            + " ORDER BY lower(u.email), u.id";
    return query(
        sql,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids.toArray()));
          if (!storeIds.isEmpty()) {
            ps.setArray(3, ps.getConnection().createArrayOf("uuid", storeIds.toArray()));
          }
        },
        rs -> new StaffLogin(rs.getObject("id", UUID.class), rs.getString("email")),
        "find staff logins");
  }

  /**
   * The role names granted to a user, for the JWT {@code roles} claim.
   *
   * @param userId the user whose roles to load
   * @return the distinct role names, empty when the user holds none
   */
  public Set<String> rolesOf(UUID userId) {
    String sql =
        "SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = ?";
    Set<String> roles = new java.util.HashSet<>();
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement(sql)) {
      ps.setObject(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) roles.add(rs.getString(1));
      }
      return roles;
    } catch (SQLException e) {
      throw dbError("load roles", e);
    }
  }

  private static final String SELECT_TOKEN_IDENTITY =
      "SELECT u.tenant_id, u.type, u.email, r.name, ur.store_id, ur.permissions"
          + " FROM users u LEFT JOIN user_roles ur ON ur.user_id = u.id"
          + " LEFT JOIN roles r ON r.id = ur.role_id WHERE u.id = ?";

  /**
   * What a token says about a login — tenant, type, roles, store scope, permissions — read in
   * <strong>one statement</strong>, so from one snapshot (SJ-D63). Sign-in reads the user's row,
   * then spends a few hundred milliseconds checking the password; read piecemeal after that, a
   * staff removal that committed in between put the new roles beside the old tenant in one token.
   *
   * <p>Store scope: only staff roles carry one. CUSTOMER is global and its row has no store — read
   * as "a role with no store", it made every shopper-turned-cashier unrestricted across the tenant
   * (SJ-D48) — and PLATFORM_ADMIN has no tenant, let alone a store; both are skipped. Among the
   * staff roles that remain, a null store is a tenant-wide role (OWNER, MANAGER) and means
   * unrestricted, signalled by an <strong>empty set</strong>, because a tenant-wide grant must not
   * be narrowed by also holding a store-scoped role elsewhere.
   *
   * <p>Permissions: null unless one of the roles is a custom one; then the union of every
   * assignment's set, a built-in role contributing its tier's defaults.
   *
   * @return empty when the user no longer exists
   */
  public Optional<TokenIdentity> tokenIdentity(UUID userId) {
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement(SELECT_TOKEN_IDENTITY)) {
      ps.setObject(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        boolean found = false;
        UUID tenantId = null;
        String type = null;
        String email = null;
        Set<String> roles = new java.util.HashSet<>();
        Set<UUID> storeIds = new java.util.HashSet<>();
        boolean tenantWide = false;
        Set<String> permissions = new java.util.LinkedHashSet<>();
        boolean custom = false;
        while (rs.next()) {
          found = true;
          tenantId = (UUID) rs.getObject(1);
          type = rs.getString(2);
          email = rs.getString(3);
          String role = rs.getString(4);
          if (role == null) continue; // a login that holds no role at all
          roles.add(role);
          if (!"CUSTOMER".equals(role) && !"PLATFORM_ADMIN".equals(role)) {
            UUID storeId = (UUID) rs.getObject(5);
            if (storeId == null) tenantWide = true;
            else storeIds.add(storeId);
          }
          String perms = rs.getString(6);
          if (perms == null) {
            permissions.addAll(com.storeql.web.Permissions.defaultsFor(role));
          } else {
            custom = true;
            for (String p : perms.split(",")) {
              if (!p.isBlank()) permissions.add(p.trim());
            }
          }
        }
        if (!found) return Optional.empty();
        return Optional.of(
            new TokenIdentity(
                tenantId,
                type,
                email,
                roles,
                tenantWide ? Set.of() : storeIds,
                custom ? permissions : null));
      }
    } catch (SQLException e) {
      throw dbError("load token identity", e);
    }
  }

  // --- atomic write: create user + assign role + write outbox event in one transaction ---

  /**
   * Insert a user, optionally assign a role, and write an outbox event — atomically.
   *
   * @param roleName a real row in {@code roles} to grant immediately (e.g. {@code "CUSTOMER"} on
   *     self-signup), or {@code null} to skip role assignment — used for a business sign-up and for
   *     admin-driven staff provisioning, where the login is made already in its business and the
   *     real store-scoped role is bound later when tenant-svc publishes {@code StaffAssigned} (see
   *     {@link com.storeql.iam.service.AuthService#provisionStaff}). There is no generic "STAFF"
   *     row in {@code roles} — passing that name throws "role not found".
   * @return the created user
   */
  public User createUserWithOutbox(User user, String roleName, OutboxRow outbox) {
    return inTx(
        c -> {
          insertUser(c, user);
          if (roleName != null) assignRole(c, user.id(), roleName);
          insertOutbox(c, outbox);
          return user;
        },
        "create user");
  }

  /**
   * {@inheritDoc}
   *
   * <p>Maps a unique-constraint violation to a {@code 409} rather than a generic database error, so
   * a duplicate email or phone reads as {@code USER_ALREADY_EXISTS} to the caller.
   */
  @Override
  protected RuntimeException handleTxSqlException(String what, SQLException e) {
    if (UNIQUE_VIOLATION.equals(e.getSQLState()))
      return new ApiException(
          409, "USER_ALREADY_EXISTS", "Email or phone already registered", List.of(), e);
    return dbError(what, e);
  }

  /**
   * Stamp a tenant onto a user and grant the OWNER role — idempotently. The processed_events mark,
   * the bind, and the audit row commit in ONE transaction (golden rules #6/#7): marking first in a
   * separate transaction would swallow the event forever if the bind then failed. Returns false if
   * the event was already processed.
   *
   * <p>OWNER is granted only to a login that is now this tenant's. A login that already belongs to
   * another business keeps that tenant (the stamp never moves one), and a business-wide OWNER role
   * beside it would make it the owner of the business it already worked for — a cashier who starts
   * a business of their own would become their employer's owner. The event is still marked, and the
   * refusal audited, so a redelivery does not try again.
   */
  public boolean bindOwnerOnce(
      UUID eventId, String consumerName, UUID userId, UUID tenantId, String ownerRole) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumerName)) {
            return false;
          }
          stampTenant(c, userId, tenantId);
          if (!belongsTo(c, userId, tenantId)) {
            auditTx(
                c,
                tenantId,
                userId,
                "OWNER_BIND_REFUSED",
                notThisTenantsBecause(
                    c, userId, "the business already has another login with its email or phone"));
            return true;
          }
          UUID roleId = roleIdByName(c, ownerRole);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO user_roles (id, user_id, role_id, store_id)"
                      + " SELECT ?, ?, ?, NULL WHERE NOT EXISTS"
                      + " (SELECT 1 FROM user_roles WHERE user_id = ? AND role_id = ? AND store_id IS NULL)")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, userId);
            ps.setObject(3, roleId);
            ps.setObject(4, userId);
            ps.setObject(5, roleId);
            ps.executeUpdate();
          }
          auditTx(c, tenantId, userId, "OWNER_BOUND", "via TenantCreated");
          return true;
        },
        "bind owner");
  }

  /**
   * Grant a store-scoped role to one of this tenant's logins — idempotently, with the
   * processed_events mark in the same transaction (see {@link #bindOwnerOnce}). Returns false if
   * the event was already processed.
   */
  public boolean bindStaffOnce(
      UUID eventId,
      String consumerName,
      UUID userId,
      UUID tenantId,
      String roleName,
      UUID storeId) {
    return bindStaffOnce(
        eventId, consumerName, userId, tenantId, roleName, storeId, null, null, null);
  }

  /**
   * Binds a staff role at a store, once per event, carrying the custom role it was assigned through
   * (20.10).
   *
   * <p>One row per (user, tier, store): assigning a second custom role on the same tier at the same
   * store replaces the code and permissions on that row rather than adding a second, because a
   * login holds one set of permissions per tier and store, not a history of them.
   *
   * <p>A role is granted only to a login that is already this tenant's: one made in it by staff
   * provisioning ({@code POST /auth/admin/staff-users}), or its owner. Nothing is stamped here, so
   * the event can take on nobody (29 Sep 2026). tenant-svc assigns whatever user id its caller
   * names, and a business can read a shopper's login id off its own orders: a stamp here let it
   * pull that shopper's account into its staff without their say, after which the storefront
   * refused their token everywhere; it could equally capture a founder's sign-up before they had
   * set their business up. A login of no business, another business's login and an id nobody holds
   * are all refused, marked and audited, never a failing event. Another business's login matters
   * most: a role row does not say which business granted it, so bound anyway it would count in that
   * other business — a cashier who starts a business of their own could assign their employed login
   * OWNER at their own store and sign in as their employer's owner.
   *
   * @param roleCode the tenant's code for the custom role, or {@code null} for a plain tier
   * @param permissions the custom role's permissions, or {@code null} for a plain tier
   * @return {@code true} when this event was processed now; {@code false} when it had been
   */
  public boolean bindStaffOnce(
      UUID eventId,
      String consumerName,
      UUID userId,
      UUID tenantId,
      String roleName,
      UUID storeId,
      String roleCode,
      Set<String> permissions,
      java.time.Instant permissionsAt) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumerName)) {
            return false;
          }
          if (!belongsTo(c, userId, tenantId)) {
            auditTx(
                c,
                tenantId,
                userId,
                "STAFF_BIND_REFUSED",
                roleName
                    + " @ store "
                    + storeId
                    + ": "
                    + notThisTenantsBecause(
                        c,
                        userId,
                        "login belongs to no business: staff are provisioned in the business,"
                            + " never taken on by id"));
            return true;
          }
          UUID roleId = roleIdByName(c, roleName);
          String perms = permissions == null ? null : String.join(",", permissions);
          if (storeId == null) {
            // Business-wide: NULLs never collide in (user_id, role_id, store_id), so "one row per
            // tier" is kept by hand — a second grant rewrites the code and permissions.
            OffsetDateTime at =
                permissionsAt == null ? null : permissionsAt.atOffset(ZoneOffset.UTC);
            int updated;
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE user_roles SET role_code = ?, permissions = ?, permissions_at = ?"
                        + " WHERE user_id = ? AND role_id = ? AND store_id IS NULL")) {
              ps.setString(1, roleCode);
              ps.setString(2, perms);
              ps.setObject(3, at);
              ps.setObject(4, userId);
              ps.setObject(5, roleId);
              updated = ps.executeUpdate();
            }
            if (updated == 0) {
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "INSERT INTO user_roles (id, user_id, role_id, store_id, role_code,"
                          + " permissions, permissions_at) VALUES (?, ?, ?, NULL, ?, ?, ?)")) {
                ps.setObject(1, Ids.newId());
                ps.setObject(2, userId);
                ps.setObject(3, roleId);
                ps.setString(4, roleCode);
                ps.setString(5, perms);
                ps.setObject(6, at);
                ps.executeUpdate();
              }
            }
            auditTx(
                c,
                tenantId,
                userId,
                "STAFF_BOUND",
                (roleCode == null ? roleName : roleCode + " (" + roleName + ")")
                    + " @ business-wide");
            return true;
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO user_roles (id, user_id, role_id, store_id, role_code, permissions,"
                      + " permissions_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
                      + " ON CONFLICT (user_id, role_id, store_id)"
                      + " DO UPDATE SET permissions_at = EXCLUDED.permissions_at,"
                      + " role_code = EXCLUDED.role_code,"
                      + " permissions = EXCLUDED.permissions")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, userId);
            ps.setObject(3, roleId);
            ps.setObject(4, storeId);
            ps.setString(5, roleCode);
            ps.setString(6, perms);
            ps.setObject(7, permissionsAt == null ? null : permissionsAt.atOffset(ZoneOffset.UTC));
            ps.executeUpdate();
          }
          auditTx(
              c,
              tenantId,
              userId,
              "STAFF_BOUND",
              (roleCode == null ? roleName : roleCode + " (" + roleName + ")")
                  + " @ store "
                  + storeId);
          return true;
        },
        "bind staff");
  }

  /**
   * Makes a login of no business the tenant's: only for its owner, whom {@code TenantCreated} names
   * as the caller who created the business with their own token, so the move is theirs. A login
   * already in a business — this one or another — is left as it is.
   *
   * <p>Nor is a login moved in whose email or phone this business already has on another login: an
   * address is one login within a business (uq_users_business_email), and a stamp that broke that
   * would fail the event on every redelivery. The binding then finds the login not this tenant's
   * and refuses it.
   */
  private static void stampTenant(java.sql.Connection c, UUID userId, UUID tenantId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE users SET tenant_id = ?, type = 'STAFF' WHERE id = ? AND tenant_id IS NULL"
                + " AND NOT EXISTS (SELECT 1 FROM users o WHERE o.tenant_id = ?"
                + " AND (lower(o.email) = lower(users.email) OR o.phone = users.phone))")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, userId);
      ps.setObject(3, tenantId);
      ps.executeUpdate();
    }
  }

  /** Whether the login's row names this tenant, read on the binding's own transaction. */
  private static boolean belongsTo(java.sql.Connection c, UUID userId, UUID tenantId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT 1 FROM users WHERE tenant_id = ? AND id = ?")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, userId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /**
   * Why a binding found the login not this tenant's, for the audit row.
   *
   * @param ofNoBusiness what a login of no business means to the caller: for an owner, the stamp
   *     refused it; for staff, nothing is ever stamped
   */
  private static String notThisTenantsBecause(
      java.sql.Connection c, UUID userId, String ofNoBusiness) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT tenant_id FROM users WHERE id = ?")) {
      ps.setObject(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) return "no such login";
        return rs.getObject(1) == null ? ofNoBusiness : "login belongs to another tenant";
      }
    }
  }

  /**
   * Takes a staff role at a store away, once per event: what {@code StaffRemoved} asks for.
   *
   * <p>Before this, removing an assignment in tenant-svc left the role on the login for good
   * (SJ-D51): a cashier taken off a store could sign in as its cashier the next morning.
   *
   * @return {@code true} when this event was processed now
   */
  public boolean unbindStaffOnce(
      UUID eventId,
      String consumerName,
      UUID userId,
      UUID tenantId,
      String roleName,
      UUID storeId) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumerName)) {
            return false;
          }
          UUID roleId = roleIdByName(c, roleName);
          int removed;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM user_roles WHERE user_id = ? AND role_id = ?"
                      + (storeId == null ? " AND store_id IS NULL" : " AND store_id = ?"))) {
            ps.setObject(1, userId);
            ps.setObject(2, roleId);
            if (storeId != null) ps.setObject(3, storeId);
            removed = ps.executeUpdate();
          }
          // The last staff role gone: who the login goes back to being depends on how it came to
          // be the tenant's (see leaveTheBusiness).
          int staffRolesLeft;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
                      + " WHERE ur.user_id = ? AND r.name NOT IN ('CUSTOMER', 'PLATFORM_ADMIN')")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
              rs.next();
              staffRolesLeft = rs.getInt(1);
            }
          }
          String outcome = "";
          if (staffRolesLeft == 0 && belongsTo(c, userId, tenantId)) {
            outcome = leaveTheBusiness(c, userId, tenantId);
          }
          auditTx(
              c,
              tenantId,
              userId,
              "STAFF_UNBOUND",
              roleName + " @ store " + storeId + " x" + removed + outcome);
          return true;
        },
        "unbind staff");
  }

  /**
   * What a login of this tenant becomes once its last staff role is taken away, on the unbinding's
   * transaction; the outcome, for the audit row.
   *
   * <p>A shopper's account taken on before 29 Sep 2026 — when a {@code StaffAssigned} could stamp
   * any login of no business — still holds its CUSTOMER role, and goes back to being that shopper's
   * account, of no business. Left in the tenant, its token would go on naming the tenant with no
   * role: harmless for admin work, which the filter refuses without a role, but a shopper's token
   * that names a tenant is a token the gateway trusts over the storefront header, so their next
   * order at another shop would be recorded against the business that let them go. It stays only
   * when its email or phone has meanwhile become another shopper's account's
   * (uq_users_unbound_email, uq_users_unbound_phone), which a move would break, failing the event
   * on every redelivery. A business account of no business holding either is a separate identity
   * and never in the way.
   *
   * <p>Any other login was made in this business by staff provisioning, or founded it, and stays in
   * it holding no staff role. The admin filter refuses it; the storefront refuses a staff token;
   * and the business gets the same login back by provisioning the address again, which is the only
   * way a login becomes its staff now that nothing is stamped. Moved out, it would read as a
   * business sign-up waiting for its setup wizard — with the password the business chose — and hold
   * the address a business sign-up of the person's own needs.
   */
  private static String leaveTheBusiness(java.sql.Connection c, UUID userId, UUID tenantId)
      throws SQLException {
    boolean shopper = false;
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
                + " WHERE ur.user_id = ? AND r.name = 'CUSTOMER'")) {
      ps.setObject(1, userId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          shopper = true;
        }
      }
    }
    if (!shopper) {
      return ", last role: kept in the tenant, holding no staff role";
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE users SET tenant_id = NULL, type = 'CUSTOMER'"
                + " WHERE tenant_id = ? AND id = ?"
                + " AND NOT EXISTS (SELECT 1 FROM users o WHERE o.tenant_id IS NULL"
                + " AND o.type = 'CUSTOMER'"
                + " AND (lower(o.email) = lower(users.email) OR o.phone = users.phone))")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, userId);
      return ps.executeUpdate() == 1
          ? ", last role: back to the shopper's account, of no business"
          : ", last role: kept in the tenant, its email or phone being another shopper's account's";
    }
  }

  /**
   * Rewrites the permissions of every assignment in a tenant made through a custom role, once per
   * event: what {@code RoleDefined} asks for when a role is redefined. The next login carries the
   * new set; a token already issued carries the old one until it expires.
   *
   * @return {@code true} when this event was processed now
   */
  public boolean applyRolePermissionsOnce(
      UUID eventId,
      String consumerName,
      UUID tenantId,
      String roleCode,
      Set<String> permissions,
      java.time.Instant updatedAt) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumerName)) {
            return false;
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  // Only forward: a redefinition arriving after a newer one is a no-op, and so
                  // is one older than the definition an assignment was made with.
                  "UPDATE user_roles ur SET permissions = ?, permissions_at = ? FROM users u"
                      + " WHERE u.id = ur.user_id AND u.tenant_id = ? AND ur.role_code = ?"
                      + " AND (ur.permissions_at IS NULL OR ? IS NULL OR ur.permissions_at < ?)")) {
            var at = updatedAt == null ? null : updatedAt.atOffset(ZoneOffset.UTC);
            ps.setString(1, String.join(",", permissions));
            ps.setObject(2, at);
            ps.setObject(3, tenantId);
            ps.setString(4, roleCode);
            ps.setObject(5, at);
            ps.setObject(6, at);
            ps.executeUpdate();
          }
          return true;
        },
        "apply role permissions");
  }

  // --- audit ---

  /**
   * Appends a row to the append-only audit log, outside any caller transaction.
   *
   * <p>Deliberately swallows its own failures with a warning: losing an audit row must not break
   * the flow being audited. Callers that need the audit row to be atomic with their write should
   * use the in-transaction paths instead.
   *
   * @param tenantId owning tenant, or {@code null} for a platform-scoped action
   * @param userId the user the action concerns
   * @param action the machine-readable action code, e.g. {@code OWNER_BOUND}
   * @param detail free-text context recorded alongside the action
   */
  public void audit(UUID tenantId, UUID userId, String action, String detail) {
    try (var c = dataSource.getConnection()) {
      auditTx(c, tenantId, userId, action, detail);
    } catch (SQLException e) {
      // audit failure must not break the main flow
      System.getLogger(UserRepository.class.getName())
          .log(System.Logger.Level.WARNING, "audit insert failed: " + e.getMessage());
    }
  }

  private static void auditTx(
      java.sql.Connection c, UUID tenantId, UUID userId, String action, String detail)
      throws SQLException {
    try (var ps =
        c.prepareStatement(
            "INSERT INTO audit_log (id, tenant_id, user_id, action, detail)"
                + " VALUES (?,?,?,?,?)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, tenantId);
      ps.setObject(3, userId);
      ps.setString(4, action);
      ps.setString(5, detail);
      ps.executeUpdate();
    }
  }

  /**
   * Deletes a customer's login in one transaction with everything that would let it be used or
   * found again: its sessions, its one-time codes, any waiting password-reset link, and the event
   * that tells other services.
   *
   * <p>The row stays, with status DELETED, so the user id other records carry still resolves to
   * something rather than nothing; what identifies the person does not. Email and phone become NULL
   * rather than a placeholder, so the same address can register a new account later — the unique
   * indexes ignore NULLs.
   */
  public void deleteCustomerAccount(User user, OutboxRow event) {
    inTx(
        c -> {
          int rows;
          try (var ps =
              c.prepareStatement(
                  "UPDATE users SET email = NULL, phone = NULL, password_hash = NULL,"
                      + " status = 'DELETED', updated_at = now()"
                      + " WHERE id = ? AND type = 'CUSTOMER' AND status <> 'DELETED'")) {
            ps.setObject(1, user.id());
            rows = ps.executeUpdate();
          }
          if (rows == 0) {
            return null;
          }
          try (var ps =
              c.prepareStatement("UPDATE refresh_tokens SET revoked = true WHERE user_id = ?")) {
            ps.setObject(1, user.id());
            ps.executeUpdate();
          }
          // This row is anonymised, not deleted (unlike ON DELETE CASCADE on a hard delete), so a
          // password-reset link minted for this login must be cleared here or it would sit,
          // useless but present, until the sweeper's day-old cutoff.
          try (var ps = c.prepareStatement("DELETE FROM password_reset_tokens WHERE user_id = ?")) {
            ps.setObject(1, user.id());
            ps.executeUpdate();
          }
          for (String target : new String[] {user.email(), user.phone()}) {
            if (target == null || target.isBlank()) {
              continue;
            }
            try (var ps = c.prepareStatement("DELETE FROM otp_codes WHERE target = ?")) {
              ps.setString(1, target);
              ps.executeUpdate();
            }
          }
          // SJ-D45: the audit trail recorded the email on every registration and every sign-in,
          // successful or not, so a deleted account's address stayed legible in audit_log after
          // the users row had been scrubbed — which is the address the erasure existed to remove.
          //
          // audit_log is append-only (golden rule #8) and stays append-only: no row is deleted and
          // no action or timestamp is rewritten. Only the one field that names the person is
          // cleared, exactly as a settled order keeps its lines and loses its delivery address. The
          // history of who did what, and when, is intact; what is gone is the identifier.
          try (var ps =
              c.prepareStatement(
                  "UPDATE audit_log SET detail = NULL WHERE user_id = ? AND detail IS NOT NULL")) {
            ps.setObject(1, user.id());
            ps.executeUpdate();
          }
          insertOutbox(c, event);
          return null;
        },
        "delete customer account");
  }

  // --- mapping / helpers ---

  /**
   * Replaces a user's password hash.
   *
   * <p>Does not revoke existing refresh tokens — a caller changing a password for security reasons
   * must revoke them separately.
   *
   * @param userId the user whose password to change
   * @param newHash the already-hashed new password; never a plaintext value
   */
  public void updatePassword(UUID userId, String newHash) {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "UPDATE users SET password_hash = ?, updated_at = now() WHERE id = ?")) {
      ps.setString(1, newHash);
      ps.setObject(2, userId);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw dbError("update password", e);
    }
  }

  /**
   * Sets a new password hash and writes the {@code PasswordChanged} outbox row in ONE transaction
   * (golden rule #6): the notice is announced if and only if the password changed.
   *
   * @param userId the login whose password changes
   * @param newHash the already-hashed new password; never plaintext
   * @param outbox the {@code PasswordChanged} row, or {@code null} when nothing is announced
   */
  public void updatePassword(UUID userId, String newHash, OutboxRow outbox) {
    inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE users SET password_hash = ?, updated_at = now() WHERE id = ?")) {
            ps.setString(1, newHash);
            ps.setObject(2, userId);
            ps.executeUpdate();
          }
          insertOutbox(c, outbox);
          return null;
        },
        "update password and announce");
  }

  private static User map(ResultSet rs) throws SQLException {
    OffsetDateTime updOdt = rs.getObject("updated_at", OffsetDateTime.class);
    return new User(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getString("type"),
        rs.getString("email"),
        rs.getString("phone"),
        rs.getString("password_hash"),
        rs.getString("status"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        updOdt == null ? null : updOdt.toInstant());
  }

  private void insertUser(Connection c, User u) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO users"
                + " (id, tenant_id, type, email, phone, password_hash, status, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?)")) {
      ps.setObject(1, u.id());
      ps.setObject(2, u.tenantId());
      ps.setString(3, u.type());
      ps.setString(4, u.email());
      ps.setString(5, u.phone());
      ps.setString(6, u.passwordHash());
      ps.setString(7, u.status());
      ps.setObject(8, u.createdAt().atOffset(ZoneOffset.UTC));
      ps.setObject(
          9,
          u.updatedAt() != null
              ? u.updatedAt().atOffset(ZoneOffset.UTC)
              : u.createdAt().atOffset(ZoneOffset.UTC));
      ps.executeUpdate();
    }
  }

  private void assignRole(Connection c, UUID userId, String roleName) throws SQLException {
    UUID roleId = roleIdByName(c, roleName);
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO user_roles (id, user_id, role_id, store_id) VALUES (?,?,?,NULL)")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, userId);
      ps.setObject(3, roleId);
      ps.executeUpdate();
    }
  }

  private UUID roleIdByName(Connection c, String roleName) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("SELECT id FROM roles WHERE name = ?")) {
      ps.setString(1, roleName);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) return rs.getObject("id", UUID.class);
        throw new SQLException("role not found: " + roleName);
      }
    }
  }

  // --- bootstrap ---

  /**
   * Whether any platform administrator account exists yet.
   *
   * <p>Gates the one-shot bootstrap endpoint: once this is true, bootstrap must refuse to mint
   * another admin.
   *
   * @return {@code true} once at least one user holds {@code PLATFORM_ADMIN}
   */
  public boolean platformAdminExists() {
    try (var c = dataSource.getConnection();
        var ps =
            c.prepareStatement(
                "SELECT 1 FROM users u"
                    + " JOIN user_roles ur ON ur.user_id = u.id"
                    + " JOIN roles r ON r.id = ur.role_id"
                    + " WHERE r.name = 'PLATFORM_ADMIN' LIMIT 1")) {
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    } catch (SQLException e) {
      throw dbError("check platform admin", e);
    }
  }

  /**
   * Creates the first platform administrator, granting {@code PLATFORM_ADMIN} in the same
   * transaction as the user row.
   *
   * <p>Callers must check {@link #platformAdminExists()} first — this does not enforce the
   * one-admin bootstrap rule itself.
   *
   * @param user the tenant-less admin account to create
   */
  public void createPlatformAdmin(User user) {
    inTx(
        c -> {
          insertUser(c, user);
          assignRole(c, user.id(), "PLATFORM_ADMIN");
          return null;
        },
        "create platform admin");
  }
}
