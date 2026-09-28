package com.storeql.iam.service;

import com.storeql.iam.auth.Tokens;
import com.storeql.iam.domain.ApiKey;
import com.storeql.iam.dto.ApiKeyDtos;
import com.storeql.iam.repo.ApiKeyRepository;
import com.storeql.iam.repo.SandboxRepository;
import com.storeql.iam.repo.UserRepository;
import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A business's API keys (22.7): minted by its owner for the systems it runs, shown once, listed
 * without the key, revoked when done with; and, for the gateway, what a key may do right now.
 *
 * <p>A key is a staff tier's worth of authority and never an owner's: the things only an owner may
 * do — close the business, export it, change what it pays, mint more keys — are the things a leaked
 * key must not be able to do. The key is 30 random bytes behind a fixed prefix, kept only as its
 * SHA-256; a key that has the wrong length or prefix is refused without a lookup, so a flood of
 * guesses costs the database nothing.
 */
@ApplicationScoped
public class ApiKeyService {

  private static final int RANDOM_BYTES = 30;
  private static final int NAME_MAX = 80;

  @Inject ApiKeyRepository keys;
  @Inject UserRepository users;
  @Inject SandboxRepository sandboxes;

  /** A key as made, with the one copy of the key itself. */
  public record Minted(ApiKey key, String secret) {}

  /**
   * @param tenantId the caller's tenant: the live business, or its sandbox when called from inside
   *     one — in which case the key is a sandbox key whatever the request says (22.8)
   */
  public Minted mint(UUID tenantId, UUID by, ApiKeyDtos.CreateRequest req) {
    // Whose key it is, and where it acts. A sandbox's keys belong to the live business, which lists
    // and revokes them; a key asked for with sandbox: true acts in the business's active sandbox.
    UUID owner = sandboxes.liveOf(tenantId).orElse(tenantId);
    boolean inside = !owner.equals(tenantId);
    boolean sandbox = inside || Boolean.TRUE.equals(req.sandbox());
    UUID actsAs =
        inside
            ? tenantId
            : sandbox
                ? sandboxes
                    .activeSandboxOf(tenantId)
                    .orElseThrow(
                        () ->
                            ApiException.notFound(
                                "SANDBOX_NOT_FOUND",
                                "This business has no sandbox to mint a key for"))
                : tenantId;
    String name = req.name() == null ? "" : req.name().trim();
    if (name.isEmpty() || name.length() > NAME_MAX) {
      throw ApiException.badRequest(
          "API_KEY_NAME_INVALID", "A key needs a name of up to " + NAME_MAX + " characters");
    }
    String role = req.role() == null ? "" : req.role().trim();
    if (!ApiKey.TIERS.contains(role)) {
      throw ApiException.badRequest(
          "API_KEY_ROLE_INVALID",
          "A key acts as MANAGER, STOREKEEPER or CASHIER — never as an owner or the platform");
    }
    Instant now = Instant.now();
    Instant expiresAt = Parsing.optionalInstant(req.expiresAt(), "expiresAt");
    if (expiresAt != null && !expiresAt.isAfter(now)) {
      throw ApiException.badRequest("API_KEY_EXPIRY_PAST", "A key expires on a day still to come");
    }
    List<UUID> stores = new ArrayList<>();
    if (req.storeIds() != null) {
      Set<UUID> distinct = new LinkedHashSet<>();
      for (String s : req.storeIds()) distinct.add(Parsing.uuid(s, "storeIds"));
      stores.addAll(distinct);
    }
    String secret =
        (sandbox ? ApiKey.TEST_PREFIX : ApiKey.PREFIX) + Tokens.newOpaqueToken(RANDOM_BYTES);
    ApiKey key =
        new ApiKey(
            Ids.newId(),
            actsAs,
            owner,
            sandbox,
            name,
            secret.substring(0, ApiKey.SHOWN),
            Tokens.hash(secret),
            role,
            stores,
            by,
            now,
            expiresAt,
            null,
            null,
            null);
    keys.insert(key);
    users.audit(
        owner,
        by,
        "API_KEY_CREATED",
        key.id() + " " + key.prefix() + " " + role + (sandbox ? " sandbox " + actsAs : ""));
    return new Minted(key, secret);
  }

  /** A page of the business's keys in the order they were made, and where the next page starts. */
  public record Page(List<ApiKey> items, String nextCursor) {}

  /**
   * Every key the business owns, live and sandbox alike — from the live business or its sandbox.
   */
  public Page list(UUID tenantId, UUID after, int limit) {
    UUID owner = sandboxes.liveOf(tenantId).orElse(tenantId);
    int size = Math.max(1, Math.min(limit, 100));
    List<ApiKey> found = keys.list(owner, after, size + 1);
    if (found.size() <= size) return new Page(found, null);
    List<ApiKey> page = found.subList(0, size);
    return new Page(page, page.get(size - 1).id().toString());
  }

  public ApiKey revoke(UUID tenantId, UUID by, UUID id) {
    UUID owner = sandboxes.liveOf(tenantId).orElse(tenantId);
    ApiKey key =
        keys.find(owner, id)
            .orElseThrow(() -> ApiException.notFound("API_KEY_NOT_FOUND", "No such key"));
    if (key.revoked()) {
      throw ApiException.conflict("API_KEY_REVOKED", "This key was already revoked");
    }
    Instant now = Instant.now();
    if (!keys.revoke(owner, id, by, now)) {
      throw ApiException.conflict("API_KEY_REVOKED", "This key was already revoked");
    }
    users.audit(owner, by, "API_KEY_REVOKED", key.id() + " " + key.prefix());
    return keys.find(owner, id).orElseThrow();
  }

  /** What a key may do right now, for the gateway; a reason when it may do nothing. */
  public ApiKeyDtos.IntrospectionResponse introspect(String secret) {
    if (!ApiKey.looksLikeKey(secret)) {
      return ApiKeyDtos.IntrospectionResponse.refused("unknown");
    }
    var found = keys.byHash(Tokens.hash(secret)).orElse(null);
    if (found == null) return ApiKeyDtos.IntrospectionResponse.refused("unknown");
    ApiKey key = found.key();
    Instant now = Instant.now();
    if (key.revoked()) return ApiKeyDtos.IntrospectionResponse.refused("revoked");
    if (key.expired(now)) return ApiKeyDtos.IntrospectionResponse.refused("expired");
    if (!found.tenantActive()) return ApiKeyDtos.IntrospectionResponse.refused("tenant suspended");
    keys.touch(key.id(), now);
    return new ApiKeyDtos.IntrospectionResponse(
        true,
        null,
        key.id().toString(),
        key.tenantId().toString(),
        List.of(key.role()),
        key.storeIds().stream().map(UUID::toString).toList(),
        key.name(),
        key.sandbox());
  }
}
