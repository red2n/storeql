package com.storeql.iam.repo;

import com.storeql.ids.Ids;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persistence for refresh tokens. Only a HASH of each token is stored, never the raw value. */
@ApplicationScoped
public class RefreshTokenRepository extends BaseJdbcRepository {

  /**
   * Records a newly issued refresh token.
   *
   * @param userId the user the token authenticates
   * @param tokenHash the hash of the token; the raw value is never persisted
   * @param expiresAt when the token stops being redeemable, UTC
   * @param amr how the session was authenticated, carried to the tokens it is renewed into
   * @param authenticatedAt when the session was signed into, carried unchanged across rotations;
   *     null for a session older than the record of it
   */
  public void store(
      UUID userId, String tokenHash, Instant expiresAt, String amr, Instant authenticatedAt) {
    exec(
        "INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, revoked, amr,"
            + " authenticated_at) VALUES (?,?,?,?,false,?,?)",
        ps -> {
          ps.setObject(1, Ids.newId());
          ps.setObject(2, userId);
          ps.setString(3, tokenHash);
          ps.setTimestamp(4, Timestamp.from(expiresAt));
          ps.setString(5, amr);
          ps.setTimestamp(6, authenticatedAt == null ? null : Timestamp.from(authenticatedAt));
        },
        "store refresh token");
  }

  /**
   * A session a refresh token stood for.
   *
   * @param amr how it was authenticated (20.12): {@code pwd}, {@code pwd,otp}, {@code sso,mfa}…;
   *     null for a session older than second factors, which was a password's
   * @param authenticatedAt when it was signed into; null for a session older than the record
   */
  public record Session(UUID userId, String amr, Instant authenticatedAt) {}

  /**
   * Atomically consume (revoke) a valid token and return its owner. Validate-then-revoke as two
   * statements would let two concurrent requests both pass validation and each mint a fresh token
   * pair from the same (supposedly single-use) refresh token.
   */
  public Optional<Session> consume(String tokenHash) {
    return query(
            "UPDATE refresh_tokens SET revoked = true"
                + " WHERE token_hash = ? AND revoked = false AND expires_at > now()"
                + " RETURNING user_id, amr, authenticated_at",
            ps -> ps.setString(1, tokenHash),
            rs -> {
              java.time.OffsetDateTime at =
                  rs.getObject("authenticated_at", java.time.OffsetDateTime.class);
              return new Session(
                  rs.getObject("user_id", UUID.class),
                  rs.getString("amr"),
                  at == null ? null : at.toInstant());
            },
            "consume refresh token")
        .stream()
        .findFirst();
  }

  /** Owner of a token that {@link #consume} would still accept, without consuming it. */
  public Optional<UUID> ownerOfActive(String tokenHash) {
    return query(
            "SELECT user_id FROM refresh_tokens"
                + " WHERE token_hash = ? AND revoked = false AND expires_at > now()",
            ps -> ps.setString(1, tokenHash),
            rs -> rs.getObject("user_id", UUID.class),
            "find active token owner")
        .stream()
        .findFirst();
  }

  /**
   * Owner of an already-revoked (but known) token. A client presenting a revoked token is the
   * classic stolen-token signal — the caller revokes the whole session family in response.
   */
  public Optional<UUID> ownerOfRevoked(String tokenHash) {
    return query(
            "SELECT user_id FROM refresh_tokens WHERE token_hash = ? AND revoked = true",
            ps -> ps.setString(1, tokenHash),
            rs -> rs.getObject("user_id", UUID.class),
            "find revoked token owner")
        .stream()
        .findFirst();
  }

  /**
   * Revoke one token (logout) and return its owner, so the caller can act on whose session this
   * was.
   */
  public Optional<UUID> revoke(String tokenHash) {
    return query(
            "UPDATE refresh_tokens SET revoked = true WHERE token_hash = ? RETURNING user_id",
            ps -> ps.setString(1, tokenHash),
            rs -> rs.getObject("user_id", UUID.class),
            "revoke refresh token")
        .stream()
        .findFirst();
  }

  /**
   * Revokes every refresh token held by one user, ending all their renewable sessions.
   *
   * <p>Access tokens already issued stay valid until they expire.
   *
   * @param userId the user whose tokens to revoke
   */
  public void revokeAllForUser(UUID userId) {
    exec(
        "UPDATE refresh_tokens SET revoked = true WHERE user_id = ?",
        ps -> ps.setObject(1, userId),
        "revoke user tokens");
  }

  /**
   * Revokes every still-valid refresh token of one user and says how many that was — the sessions
   * ended by "sign out everywhere". Tokens already revoked or spent are not counted.
   *
   * @param userId the user whose renewable sessions end
   * @return how many refresh tokens were live and are now revoked
   */
  public int revokeLiveForUser(UUID userId) {
    return inTx(
        c -> {
          try (var ps =
              c.prepareStatement(
                  "UPDATE refresh_tokens SET revoked = true"
                      + " WHERE user_id = ? AND revoked = false")) {
            ps.setObject(1, userId);
            return ps.executeUpdate();
          }
        },
        "revoke live user tokens");
  }

  /**
   * Revoke every refresh token belonging to a tenant's users — used when a tenant is deactivated so
   * existing sessions can't mint new access tokens (login + refresh are blocked separately too).
   */
  public void revokeAllForTenant(UUID tenantId) {
    exec(
        "UPDATE refresh_tokens SET revoked = true"
            + " WHERE user_id IN (SELECT id FROM users WHERE tenant_id = ?)",
        ps -> ps.setObject(1, tenantId),
        "revoke tenant tokens");
  }
}
