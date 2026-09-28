package com.storeql.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What iam-svc says about a key, as the gateway reads it (22.7): the identity when the key is good,
 * the reason when it is not, and "cannot say" for an answer that is not an answer; each verdict
 * kept ten seconds under the key's hash and never the key, so a revocation lands within ten seconds
 * and a flood of forged keys costs iam-svc one lookup each; "cannot say" is never kept.
 */
class ApiKeyIntrospectorTest {

  private static final String KEY = "sqk_" + "B".repeat(40);
  private static final String KEY_ID = Ids.newId().toString();
  private static final String TENANT = Ids.newId().toString();

  /** An introspector whose iam-svc is a fixed answer and whose clock is a field. */
  private static final class Stubbed extends ApiKeyIntrospector {
    ApiKeyIntrospector.Verdict answer = new Unavailable();
    long clock = 1_000_000L;
    int lookups;

    @Override
    Verdict lookup(String key) {
      lookups++;
      return answer;
    }

    @Override
    long now() {
      return clock;
    }
  }

  @Test
  void theAnswerIsReadFromWhatIamSays() {
    var active =
        ApiKeyIntrospector.parse(
            "{\"data\":{\"active\":true,\"keyId\":\""
                + KEY_ID
                + "\",\"tenantId\":\""
                + TENANT
                + "\",\"roles\":[\"MANAGER\"],\"storeIds\":[],\"name\":\"Accounts\"}}");
    var identity = assertInstanceOf(ApiKeyIntrospector.Active.class, active);
    assertEquals(KEY_ID, identity.keyId());
    assertEquals(TENANT, identity.tenantId());
    assertEquals("MANAGER", identity.role());
    assertEquals(List.of(), identity.storeIds());
    assertEquals("Accounts", identity.name());

    var refused = ApiKeyIntrospector.parse("{\"data\":{\"active\":false,\"reason\":\"revoked\"}}");
    assertEquals("revoked", assertInstanceOf(ApiKeyIntrospector.Refused.class, refused).reason());

    assertInstanceOf(ApiKeyIntrospector.Unavailable.class, ApiKeyIntrospector.parse("not json"));
    assertInstanceOf(
        ApiKeyIntrospector.Unavailable.class, ApiKeyIntrospector.parse("{\"data\":null}"));
    assertInstanceOf(
        ApiKeyIntrospector.Unavailable.class,
        ApiKeyIntrospector.parse("{\"data\":{\"active\":true}}"),
        "an identity with no tenant is no identity");
  }

  @Test
  void aVerdictIsKeptTenSecondsAndCannotSayIsNot() {
    Stubbed iam = new Stubbed();
    iam.answer = new ApiKeyIntrospector.Active(KEY_ID, TENANT, "MANAGER", List.of(), "ERP");
    assertInstanceOf(ApiKeyIntrospector.Active.class, iam.introspect(KEY));
    assertInstanceOf(ApiKeyIntrospector.Active.class, iam.introspect(KEY));
    assertEquals(1, iam.lookups, "the second call within ten seconds is answered from memory");

    iam.clock += ApiKeyIntrospector.TTL_MILLIS + 1;
    iam.answer = new ApiKeyIntrospector.Refused("revoked");
    assertInstanceOf(ApiKeyIntrospector.Refused.class, iam.introspect(KEY));
    assertEquals(2, iam.lookups, "after ten seconds iam is asked again, and a revocation lands");
    assertInstanceOf(ApiKeyIntrospector.Refused.class, iam.introspect(KEY));
    assertEquals(2, iam.lookups, "a refusal is kept too: a flood of one forged key is one lookup");

    iam.clock += ApiKeyIntrospector.TTL_MILLIS + 1;
    iam.answer = new ApiKeyIntrospector.Unavailable();
    assertInstanceOf(ApiKeyIntrospector.Unavailable.class, iam.introspect(KEY));
    assertInstanceOf(ApiKeyIntrospector.Unavailable.class, iam.introspect(KEY));
    assertEquals(
        4, iam.lookups, "an outage is asked about every time, so it ends the moment it ends");
  }

  @Test
  void theMemoryIsKeyedByTheHashAndNeverGrowsPastItsBound() {
    String cacheKey = ApiKeyIntrospector.cacheKey(KEY);
    assertNotEquals(KEY, cacheKey);
    assertEquals(64, cacheKey.length(), "SHA-256, in hex");
    assertTrue(cacheKey.matches("[0-9a-f]{64}"));

    Stubbed iam = new Stubbed();
    iam.answer = new ApiKeyIntrospector.Refused("unknown");
    for (int i = 0; i < ApiKeyIntrospector.MAX_ENTRIES + 5; i++) {
      iam.introspect("sqk_" + String.format("%040d", i));
    }
    assertTrue(iam.size() <= ApiKeyIntrospector.MAX_ENTRIES);
  }
}
