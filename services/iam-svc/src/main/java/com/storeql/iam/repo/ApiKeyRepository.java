package com.storeql.iam.repo;

import com.storeql.iam.domain.ApiKey;
import com.storeql.service.BaseJdbcRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The {@code api_keys} table (22.7): a business's keys, found by hash and listed by business. */
@ApplicationScoped
public class ApiKeyRepository extends BaseJdbcRepository {

  private static final String COLUMNS =
      "k.id, k.tenant_id, k.owner_tenant_id, k.sandbox, k.name, k.prefix, k.key_hash, k.role,"
          + " k.store_ids, k.created_by, k.created_at, k.expires_at, k.last_used_at, k.revoked_at,"
          + " k.revoked_by";

  private static final String INSERT =
      "INSERT INTO api_keys (id, tenant_id, owner_tenant_id, sandbox, name, prefix, key_hash, role,"
          + " store_ids, created_by, created_at, expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";

  /** A key by its hash, with whether its business is switched on; nothing else knows tenants. */
  private static final String BY_HASH =
      "SELECT "
          + COLUMNS
          + ", COALESCE(ts.status, 'ACTIVE') AS tenant_status FROM api_keys k"
          + " LEFT JOIN tenant_status ts ON ts.tenant_id = k.tenant_id WHERE k.key_hash = ?";

  private static final String FIND =
      "SELECT " + COLUMNS + " FROM api_keys k WHERE k.owner_tenant_id = ? AND k.id = ?";

  private static final String LIST =
      "SELECT " + COLUMNS + " FROM api_keys k WHERE k.owner_tenant_id = ? ORDER BY k.id LIMIT ?";

  private static final String LIST_AFTER =
      "SELECT "
          + COLUMNS
          + " FROM api_keys k WHERE k.owner_tenant_id = ? AND k.id > ? ORDER BY k.id LIMIT ?";

  private static final String REVOKE =
      "UPDATE api_keys SET revoked_at = ?, revoked_by = ? WHERE owner_tenant_id = ? AND id = ?"
          + " AND revoked_at IS NULL";

  /** A use is kept to the minute: one write a minute per key, not one a request. */
  private static final String TOUCH =
      "UPDATE api_keys SET last_used_at = ? WHERE id = ?"
          + " AND (last_used_at IS NULL OR last_used_at < ?)";

  /** A key and whether the business it belongs to is switched on. */
  public record Found(ApiKey key, boolean tenantActive) {}

  public void insert(ApiKey k) {
    exec(
        INSERT,
        ps -> {
          ps.setObject(1, k.id());
          ps.setObject(2, k.tenantId());
          ps.setObject(3, k.ownerTenantId());
          ps.setBoolean(4, k.sandbox());
          ps.setString(5, k.name());
          ps.setString(6, k.prefix());
          ps.setString(7, k.keyHash());
          ps.setString(8, k.role());
          setStores(ps, 9, k.storeIds());
          ps.setObject(10, k.createdBy());
          ps.setObject(11, k.createdAt().atOffset(ZoneOffset.UTC));
          ps.setObject(12, k.expiresAt() == null ? null : k.expiresAt().atOffset(ZoneOffset.UTC));
        },
        "insert api key");
  }

  public Optional<Found> byHash(String hash) {
    return query(
            BY_HASH,
            ps -> ps.setString(1, hash),
            rs -> new Found(read(rs), "ACTIVE".equals(rs.getString("tenant_status"))),
            "api key by hash")
        .stream()
        .findFirst();
  }

  /** By the business that owns the key: the live one, whose sandbox keys are its own too (22.8). */
  public Optional<ApiKey> find(UUID ownerTenantId, UUID id) {
    return query(
            FIND,
            ps -> {
              ps.setObject(1, ownerTenantId);
              ps.setObject(2, id);
            },
            ApiKeyRepository::read,
            "api key")
        .stream()
        .findFirst();
  }

  /** A business's keys in the order they were made, {@code limit} at most, after an id. */
  public List<ApiKey> list(UUID tenantId, UUID after, int limit) {
    if (after == null) {
      return query(
          LIST,
          ps -> {
            ps.setObject(1, tenantId);
            ps.setInt(2, limit);
          },
          ApiKeyRepository::read,
          "api keys");
    }
    return query(
        LIST_AFTER,
        ps -> {
          ps.setObject(1, tenantId);
          ps.setObject(2, after);
          ps.setInt(3, limit);
        },
        ApiKeyRepository::read,
        "api keys after");
  }

  /**
   * @return false when the key is not this business's or is already revoked
   */
  public boolean revoke(UUID tenantId, UUID id, UUID by, Instant at) {
    return inTx(
        c -> {
          try (PreparedStatement ps = c.prepareStatement(REVOKE)) {
            ps.setObject(1, at.atOffset(ZoneOffset.UTC));
            ps.setObject(2, by);
            ps.setObject(3, tenantId);
            ps.setObject(4, id);
            return ps.executeUpdate() == 1;
          }
        },
        "revoke api key");
  }

  public void touch(UUID id, Instant at) {
    exec(
        TOUCH,
        ps -> {
          ps.setObject(1, at.atOffset(ZoneOffset.UTC));
          ps.setObject(2, id);
          ps.setObject(3, at.minusSeconds(60).atOffset(ZoneOffset.UTC));
        },
        "touch api key");
  }

  private static void setStores(PreparedStatement ps, int index, List<UUID> stores)
      throws SQLException {
    if (stores.isEmpty()) {
      ps.setNull(index, java.sql.Types.ARRAY);
    } else {
      ps.setArray(index, ps.getConnection().createArrayOf("uuid", stores.toArray()));
    }
  }

  private static ApiKey read(ResultSet rs) throws SQLException {
    return new ApiKey(
        rs.getObject("id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("owner_tenant_id", UUID.class),
        rs.getBoolean("sandbox"),
        rs.getString("name"),
        rs.getString("prefix"),
        rs.getString("key_hash"),
        rs.getString("role"),
        stores(rs.getArray("store_ids")),
        rs.getObject("created_by", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        instant(rs, "expires_at"),
        instant(rs, "last_used_at"),
        instant(rs, "revoked_at"),
        rs.getObject("revoked_by", UUID.class));
  }

  private static List<UUID> stores(Array array) throws SQLException {
    if (array == null) return List.of();
    List<UUID> ids = new ArrayList<>();
    for (Object o : (Object[]) array.getArray()) ids.add((UUID) o);
    return ids;
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }
}
