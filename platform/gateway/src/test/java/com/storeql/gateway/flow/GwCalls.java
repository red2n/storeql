package com.storeql.gateway.flow;

import com.storeql.test.WebTargets;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.Map;

/** Calls to the running gateway, and reading what comes back. */
final class GwCalls {

  private GwCalls() {}

  static Invocation.Builder request(
      WebTarget target, String pathAndQuery, String token, Map<String, String> headers) {
    Invocation.Builder b = WebTargets.at(target, pathAndQuery).request();
    if (token != null) b = b.header("Authorization", "Bearer " + token);
    for (Map.Entry<String, String> h : headers.entrySet()) b = b.header(h.getKey(), h.getValue());
    return b;
  }

  static Response get(WebTarget target, String pathAndQuery, String token) {
    return request(target, pathAndQuery, token, Map.of()).get();
  }

  static Response get(
      WebTarget target, String pathAndQuery, String token, Map<String, String> headers) {
    return request(target, pathAndQuery, token, headers).get();
  }

  static Response post(WebTarget target, String pathAndQuery, String token, String body) {
    return request(target, pathAndQuery, token, Map.of())
        .post(Entity.entity(body, "application/json"));
  }

  /** The body as a JSON object (empty when there is none); the response is closed. */
  static JsonObject json(Response response) {
    try (response) {
      String text = response.readEntity(String.class);
      if (text == null || text.isBlank()) return Json.createObjectBuilder().build();
      return Json.createReader(new StringReader(text)).readObject();
    }
  }

  /** The stable code of an error body, whether it is problem details or the plain envelope. */
  static String codeOf(JsonObject body) {
    if (body.containsKey("code") && !body.isNull("code")) return body.getString("code");
    if (body.containsKey("error") && !body.isNull("error")) {
      return body.getJsonObject("error").getString("code", null);
    }
    return null;
  }

  static String requestId(Response response) {
    return response.getHeaderString("X-Request-Id");
  }
}
