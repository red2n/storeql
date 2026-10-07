package com.storeql.gateway.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import io.helidon.microprofile.testing.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A gateway that starts while Redis is unreachable (a rollout racing the cache) is the harder case
 * of "Redis down": there is no connection to lose, only one that cannot be made. Requests still
 * pass, and the screen says its figures are unavailable.
 */
@HelidonTest
@AddConfig(key = "storeql.redis.port", value = "1")
class RedisNeverUpIT {

  static {
    GatewayHarness.start();
  }

  @Inject WebTarget target;
  @Inject FlowRecorder recorder;

  @Test
  @DisplayName("Requests pass, records are given up and counted, and the screen says unavailable")
  void requestsPassWithNoRedisAtAll() {
    String tenant = Ids.newId().toString();
    String token = GatewayHarness.token(tenant, "OWNER");
    for (int i = 0; i < 3; i++) {
      try (Response r = GwCalls.get(target, "/api/v1/order-svc/ok/" + i, token)) {
        assertEquals(200, r.getStatus());
        assertTrue(Ids.isV7(Ids.parse(GwCalls.requestId(r))));
      }
    }
    recorder.flush();
    assertTrue(recorder.droppedSinceStart() >= 3, "the records were counted as given up");

    try (Response r = GwCalls.get(target, "/api/v1/system-health/summary", token)) {
      assertEquals(200, r.getStatus());
      JsonObject s = GwCalls.json(r).getJsonObject("data");
      assertFalse(s.getBoolean("available"));
      assertTrue(s.getJsonArray("perMinute").isEmpty());
    }
    try (Response r = GwCalls.get(target, "/api/v1/system-health/failures", token)) {
      assertEquals(200, r.getStatus());
      assertFalse(GwCalls.json(r).getJsonObject("data").getBoolean("available"));
    }
  }
}
