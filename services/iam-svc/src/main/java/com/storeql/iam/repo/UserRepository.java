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

  /** Find a user by email within a tenant scope (tenantId null = global/customer scope). */
  public Optional<User> findByEmail(UUID tenantId, String email) {
    // Separate branches so a null tenant maps to "IS NULL" cleanly (JDBC can't infer the type of a
    // null UUID bind parameter inside "tenant_id = ?").
    String sql =
        tenantId == null
            ? "SELECT "
                + SELECT_COLS
                + " FROM users WHERE lower(email) = lower(?) AND tenant_id IS NULL"
            : "SELECT "
                + SELECT_COLS
                + " FROM users WHERE lower(email) = lower(?) AND tenant_id = ?";
    try (var c = dataSource.getConnection();
        var ps = c.prepareStatement(sql)) {
      ps.setString(1, email);
      if (tenantId != null) ps.setObject(2, tenantId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(map(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw dbError("find user by email", e);
    }
  }

  /**
   * All users with this email across every tenant scope. Email is unique only per scope
   * (uq_users_tenant_email), so after a user is bound to a tenant their row leaves the NULL scope —
   * login must search all scopes and disambiguate by password.
   */
  public List<User> findAllByEmail(String email) {
    return query(
        "SELECT " + SELECT_COLS + " FROM users WHERE lower(email) = lower(?)",
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
   * store (an owner, a business-wide manager, the platform admin) names every match, as before.
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
                ? ""
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
   *     self-signup), or {@code null} to skip role assignment — used for admin-driven staff
   *     provisioning, where the account is created tenant-less and the real store-scoped role is
   *     bound later when tenant-svc publishes {@code StaffAssigned} (see {@link
   *     com.storeql.iam.service.AuthService#provisionStaff}). There is no generic "STAFF" row in
   *     {@code roles} — passing that name throws "role not found".
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
   */
  public boolean bindOwnerOnce(
      UUID eventId, String consumerName, UUID userId, UUID tenantId, String ownerRole) {
    return inTx(
        c -> {
          if (!markProcessedIfNewTx(c, eventId, consumerName)) {
            return false;
          }
          stampTenant(c, userId, tenantId);
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
   * Stamp a tenant onto a staff user and grant a store-scoped role — idempotently, with the
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
          stampTenant(c, userId, tenantId);
          UUID roleId = roleIdByName(c, roleName);
          String perms = permissions == null ? null : String.join(",", permissions);
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

  /** Makes a login this tenant's staff, unless it already belongs to a tenant. */
  private static void stampTenant(java.sql.Connection c, UUID userId, UUID tenantId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE users SET tenant_id = ?, type = 'STAFF' WHERE id = ? AND tenant_id IS NULL")) {
      ps.setObject(1, tenantId);
      ps.setObject(2, userId);
      ps.executeUpdate();
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
                  "DELETE FROM user_roles WHERE user_id = ? AND role_id = ? AND store_id = ?")) {
            ps.setObject(1, userId);
            ps.setObject(2, roleId);
            ps.setObject(3, storeId);
            removed = ps.executeUpdate();
          }
          // The last staff role gone: the login is no longer this tenant's. Left as it was, the
          // token would still carry the tenant with no role — harmless for admin work, which the
          // filter refuses without a role, but a shopper's token that names a tenant is a token
          // the gateway trusts over the storefront header, so their next order at another shop
          // would be recorded against the business that let them go.
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
          if (staffRolesLeft == 0) {
            try (PreparedStatement ps =
                c.prepareStatement(
                    "UPDATE users SET tenant_id = NULL, type = CASE WHEN EXISTS"
                        + " (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id"
                        + "   WHERE ur.user_id = users.id AND r.name = 'CUSTOMER')"
                        + " THEN 'CUSTOMER' ELSE type END WHERE id = ? AND tenant_id = ?")) {
              ps.setObject(1, userId);
              ps.setObject(2, tenantId);
              ps.executeUpdate();
            }
          }
          auditTx(
              c,
              tenantId,
              userId,
              "STAFF_UNBOUND",
              roleName
                  + " @ store "
                  + storeId
                  + " x"
                  + removed
                  + (staffRolesLeft == 0 ? ", last role: unbound from the tenant" : ""));
          return true;
        },
        "unbind staff");
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
