package com.storeql.gateway.filters;

import com.storeql.discovery.ServiceRegistry;
import com.storeql.web.HttpHeaders;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a business's API key may do (22.7), as iam-svc says, remembered here for ten seconds.
 *
 * <p>The gateway holds a key it did not mint and must decide whose request it is. It asks iam-svc —
 * the service that minted the key and knows whether it has since been revoked, has expired, or
 * belongs to a business that was switched off — under the platform's own identity, on a route no
 * business reaches. Each verdict is kept ten seconds under the key's hash, never the key: a
 * revocation lands within ten seconds, and a flood of one forged key costs iam-svc one lookup. An
 * answer that is not an answer — iam-svc unreachable, or saying something unreadable — is "cannot
 * say", never kept, and never mistaken for a refusal: authentication fails closed, but a caller
 * with a good key is told to try again, not that the key is bad.
 *
 * <p>Bounded like {@link TenantAllowances}: the memory never grows past {@link #MAX_ENTRIES}.
 */
@ApplicationScoped
public class ApiKeyIntrospector {

  private static final Logger LOG = System.getLogger(ApiKeyIntrospector.class.getName());

  /** Every key starts with this; a bearer that does is a key, not a token. */
  public static final String KEY_PREFIX = "sqk_";

  static final long TTL_MILLIS = 10_000;
  static final int MAX_ENTRIES = 10_000;
  private static final String PATH = "/platform/api-keys/introspect";

  @Inject ServiceRegistry registry;
  @Inject WebClient webClient;

  /** iam-svc's answer. */
  public sealed interface Verdict permits Active, Refused, Unavailable {}

  /** The key is good: whose it is, what it acts as, where. */
  public record Active(
      String keyId, String tenantId, String role, List<String> storeIds, String name)
      implements Verdict {
    public Active {
      storeIds = List.copyOf(storeIds);
    }
  }

  /** The key is not good, and why: unknown, revoked, expired, tenant suspended. */
  public record Refused(String reason) implements Verdict {}

  /** iam-svc could not be asked, or did not answer in a way this gateway can read. */
  public record Unavailable() implements Verdict {}

  private record Cached(Verdict verdict, long expiresAt) {}

  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public Verdict introspect(String key) {
    String k = cacheKey(key);
    long now = now();
    Cached c = cache.get(k);
    if (c != null && c.expiresAt() > now) {
      return c.verdict();
    }
    Verdict verdict = lookup(key);
    if (verdict instanceof Unavailable) {
      return verdict;
    }
    if (cache.size() >= MAX_ENTRIES && !cache.containsKey(k)) {
      cache.values().removeIf(v -> v.expiresAt() <= now);
      if (cache.size() >= MAX_ENTRIES) {
        var it = cache.keySet().iterator();
        if (it.hasNext()) {
          it.next();
          it.remove();
        }
      }
    }
    cache.put(k, new Cached(verdict, now + TTL_MILLIS));
    return verdict;
  }

  /** iam-svc's answer for a key, asked now. */
  Verdict lookup(String key) {
    var instance = registry.resolve("iam-svc");
    if (instance.isEmpty()) {
      LOG.log(Level.WARNING, "API key could not be checked: iam-svc is not registered");
      return new Unavailable();
    }
    String body = Json.createObjectBuilder().add("key", key).build().toString();
    try (var resp =
        webClient
            .post(instance.get().baseUri() + PATH)
            .header(HeaderNames.CONTENT_TYPE, "application/json")
            .header(HeaderNames.create(HttpHeaders.ROLES), "PLATFORM_ADMIN")
            .submit(body.getBytes(StandardCharsets.UTF_8))) {
      if (resp.status().code() != 200) {
        LOG.log(Level.WARNING, "API key check answered {0}", resp.status().code());
        return new Unavailable();
      }
      return parse(resp.as(String.class));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "API key check failed: " + e.getMessage());
      return new Unavailable();
    }
  }

  long now() {
    return System.currentTimeMillis();
  }

  int size() {
    return cache.size();
  }

  /** The key's SHA-256 in hex: what the memory is keyed by, so a heap dump holds no key. */
  static String cacheKey(String key) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** iam-svc's answer as a verdict; anything unreadable, or active with no business, cannot say. */
  static Verdict parse(String body) {
    try (var reader = Json.createReader(new StringReader(body))) {
      JsonObject root = reader.readObject();
      if (!root.containsKey("data") || root.isNull("data")) return new Unavailable();
      JsonObject data = root.getJsonObject("data");
      if (!data.getBoolean("active", false)) {
        return new Refused(data.getString("reason", "unknown"));
      }
      String keyId = data.getString("keyId", null);
      String tenantId = data.getString("tenantId", null);
      String role =
          data.containsKey("roles")
                  && !data.isNull("roles")
                  && !data.getJsonArray("roles").isEmpty()
              ? data.getJsonArray("roles").getString(0)
              : null;
      if (keyId == null || tenantId == null || role == null) return new Unavailable();
      List<String> stores =
          data.containsKey("storeIds") && !data.isNull("storeIds")
              ? data.getJsonArray("storeIds").getValuesAs(JsonValue::toString).stream()
                  .map(s -> s.replace("\"", ""))
                  .toList()
              : List.of();
      return new Active(keyId, tenantId, role, stores, data.getString("name", ""));
    } catch (RuntimeException e) {
      return new Unavailable();
    }
  }
}
