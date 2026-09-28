package com.storeql.iam.repo;

import com.storeql.ids.Ids;
import com.storeql.service.BaseOutboxRepository;
import com.storeql.service.OutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for forgotten-password (intent/password-reset.md): the throttle
 * (password_reset_requests, keyed by an address hash — never the address) and the tokens
 * (password_reset_tokens, one hash per login, single-use).
 *
 * <p>{@link #mint} writes every token a forgot-password request calls for, the one outbox row that
 * tells notification-svc, and the throttle row, in ONE transaction (golden rule #6): a token must
 * not exist unless the event that carries its link does, and the request must not be free to retry
 * unless the throttle row landed too. {@link #reset} spends a token and finishes the reset — the
 * login's password, its sessions, and its audit row — in another.
 */
@ApplicationScoped
public class PasswordResetRepository extends BaseOutboxRepository {

  /** A token to mint for one eligible, non-single-sign-on login. */
  public record MintTarget(UUID userId, UUID tenantId, String tokenHash, Instant expiresAt) {}

  /** The login a token belongs to, as {@link #find} and {@link #reset} report it. */
  public record TokenOwner(UUID userId, UUID tenantId) {}

  /**
   * How many requests for this address (identified only by its hash) were accepted in the window —
   * what {@code storeql.iam.password-reset.max-per-hour} throttles on.
   *
   * @param addressHash {@link com.storeql.iam.domain.PasswordReset#addressHash}
   * @param since the window's start (now minus an hour)
   */
  public int recentRequestCount(String addressHash, Instant since) {
    return query(
            "SELECT count(*) FROM password_reset_requests"
                + " WHERE address_hash = ? AND requested_at > ?",
            ps -> {
              ps.setString(1, addressHash);
              ps.setTimestamp(2, Timestamp.from(since));
            },
            rs -> rs.getInt(1),
            "count password reset requests")
        .stream()
        .findFirst()
        .orElse(0);
  }

  /**
   * Mints every token a forgot-password request calls for — replacing each login's older unspent
   * tokens first — and writes the throttle row and, when anything was eligible, the one outbox row
   * that tells notification-svc, all on one transaction.
   *
   * @param targets one entry per eligible login that gets a link; may be empty when every eligible
   *     login uses single sign-on, or nothing was eligible at all
   * @param addressHash the throttle key for this request
   * @param requestedAt when this request was accepted, recorded against the throttle
   * @param outbox the {@code PasswordResetRequested} row, or {@code null} when nothing was eligible
   *     (no event at all)
   */
  public void mint(
      List<MintTarget> targets, String addressHash, Instant requestedAt, OutboxRow outbox) {
    inTx(
        c -> {
          for (MintTarget target : targets) {
            replaceUnspent(c, target.userId());
            insertToken(c, target);
          }
          insertOutbox(c, outbox);
          insertRequest(c, addressHash, requestedAt);
          return null;
        },
        "mint password reset tokens");
  }

  private static void replaceUnspent(Connection c, UUID userId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE password_reset_tokens SET replaced_at = now()"
                + " WHERE user_id = ? AND used_at IS NULL AND replaced_at IS NULL")) {
      ps.setObject(1, userId);
      ps.executeUpdate();
    }
  }

  private static void insertToken(Connection c, MintTarget target) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO password_reset_tokens"
                + " (id, user_id, tenant_id, token_hash, expires_at, created_at)"
                + " VALUES (?, ?, ?, ?, ?, now())")) {
      ps.setObject(1, Ids.newId());
      ps.setObject(2, target.userId());
      ps.setObject(3, target.tenantId());
      ps.setString(4, target.tokenHash());
      ps.setTimestamp(5, Timestamp.from(target.expiresAt()));
      ps.executeUpdate();
    }
  }

  private static void insertRequest(Connection c, String addressHash, Instant requestedAt)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO password_reset_requests (id, address_hash, requested_at)"
                + " VALUES (?, ?, ?)")) {
      ps.setObject(1, Ids.newId());
      ps.setString(2, addressHash);
      ps.setTimestamp(3, Timestamp.from(requestedAt));
      ps.executeUpdate();
    }
  }

  /**
   * The login a token names, read only — so a new password the policy refuses leaves the token
   * exactly as it was, still usable.
   *
   * @param tokenHash {@code CapabilityTokens.hash} of the token from the link
   * @return the login, when the token is unspent, unreplaced and unexpired; empty otherwise
   */
  public Optional<TokenOwner> find(String tokenHash) {
    return query(
            "SELECT user_id, tenant_id FROM password_reset_tokens"
                + " WHERE token_hash = ? AND used_at IS NULL AND replaced_at IS NULL"
                + " AND expires_at > now()",
            ps -> ps.setString(1, tokenHash),
            PasswordResetRepository::mapOwner,
            "find password reset token")
        .stream()
        .findFirst();
  }

  /**
   * Spends a token — atomically, so two requests racing the same link spend it exactly once — then
   * finishes the reset on the same transaction: the login's other unspent tokens are replaced, its
   * password hash is set, every refresh token it holds is revoked, and {@code PASSWORD_RESET} is
   * audited.
   *
   * @param tokenHash the token to spend
   * @param newPasswordHash the already-hashed new password (Argon2); never plaintext
   * @return the login the token belonged to, or empty when it was no longer valid — spent, replaced
   *     or expired since {@link #find} read it
   */
  public Optional<UUID> reset(String tokenHash, String newPasswordHash) {
    return inTx(
        c -> {
          UUID userId;
          UUID tenantId;
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE password_reset_tokens SET used_at = now()"
                      + " WHERE token_hash = ? AND used_at IS NULL AND replaced_at IS NULL"
                      + " AND expires_at > now() RETURNING user_id, tenant_id")) {
            ps.setString(1, tokenHash);
            try (ResultSet rs = ps.executeQuery()) {
              if (!rs.next()) {
                return Optional.<UUID>empty();
              }
              userId = rs.getObject("user_id", UUID.class);
              tenantId = rs.getObject("tenant_id", UUID.class);
            }
          }
          replaceUnspent(c, userId);
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE users SET password_hash = ?, updated_at = now() WHERE id = ?")) {
            ps.setString(1, newPasswordHash);
            ps.setObject(2, userId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement("UPDATE refresh_tokens SET revoked = true WHERE user_id = ?")) {
            ps.setObject(1, userId);
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO audit_log (id, tenant_id, user_id, action, detail)"
                      + " VALUES (?, ?, ?, 'PASSWORD_RESET', NULL)")) {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenantId);
            ps.setObject(3, userId);
            ps.executeUpdate();
          }
          return Optional.of(userId);
        },
        "reset password");
  }

  /**
   * Clears out what the throttle and the tokens no longer need: requests older than a day, and
   * tokens spent, replaced or expired more than a day ago — run on every forgot-password write
   * (there being nothing else in this small, cheap pair of tables to warrant a separate sweeper).
   *
   * @param now the moment to age the cutoff from
   */
  public void sweep(Instant now) {
    Instant cutoff = now.minus(Duration.ofDays(1));
    exec(
        "DELETE FROM password_reset_requests WHERE requested_at < ?",
        ps -> ps.setTimestamp(1, Timestamp.from(cutoff)),
        "sweep password reset requests");
    exec(
        "DELETE FROM password_reset_tokens"
            + " WHERE (used_at IS NOT NULL AND used_at < ?)"
            + " OR (replaced_at IS NOT NULL AND replaced_at < ?)"
            + " OR expires_at < ?",
        ps -> {
          ps.setTimestamp(1, Timestamp.from(cutoff));
          ps.setTimestamp(2, Timestamp.from(cutoff));
          ps.setTimestamp(3, Timestamp.from(cutoff));
        },
        "sweep password reset tokens");
  }

  private static TokenOwner mapOwner(ResultSet rs) throws SQLException {
    return new TokenOwner(
        rs.getObject("user_id", UUID.class), rs.getObject("tenant_id", UUID.class));
  }
}
