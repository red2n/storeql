package com.storeql.purchase.client.accounting;

import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientRequest;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** The one HTTP exchange every package shares: send, read whole, tell an outage from an answer. */
final class PackageHttp {

  static final String USER_AGENT = "StoreQL accounting connector";
  private static final int SNIPPET = 2000;

  record Reply(int status, String body) {
    boolean ok() {
      return status >= 200 && status < 300;
    }

    /** The body as an object, or an outage: a package that answers no JSON is not answering. */
    JsonObject json() {
      try (JsonReader reader = Json.createReader(new StringReader(body == null ? "" : body))) {
        return reader.readObject();
      } catch (RuntimeException e) {
        throw new AccountingPackage.Unreachable(
            "an unreadable answer (HTTP " + status + "): " + PackageHttp.snippet(body), true, e);
      }
    }

    String snippet() {
      return PackageHttp.snippet(body);
    }
  }

  private final WebClient http;

  PackageHttp(Duration timeout) {
    this.http =
        WebClient.builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(timeout)
            .followRedirects(false)
            .build();
  }

  Reply send(
      String method, String url, Map<String, String> headers, String contentType, String body) {
    HttpClientRequest request =
        switch (method) {
          case "GET" -> http.get(url);
          case "PUT" -> http.put(url);
          default -> http.post(url);
        };
    request.header(HeaderNames.USER_AGENT, USER_AGENT);
    for (var h : headers.entrySet()) {
      request.header(HeaderNames.create(h.getKey()), h.getValue());
    }
    boolean sent = false;
    try {
      HttpClientResponse response;
      if (body == null) {
        response = request.request();
      } else {
        request.header(HeaderNames.CONTENT_TYPE, contentType);
        response = request.submit(body.getBytes(StandardCharsets.UTF_8));
      }
      sent = true;
      try (response) {
        return new Reply(response.status().code(), response.as(String.class));
      }
    } catch (RuntimeException e) {
      throw new AccountingPackage.Unreachable(
          e.getClass().getSimpleName() + ": " + e.getMessage(), sent || !looksUnsent(e), e);
    }
  }

  /**
   * Whether the failure happened before anything left: nothing to resolve, nobody to connect to.
   */
  private static boolean looksUnsent(Throwable e) {
    Throwable t = e;
    for (int depth = 0; t != null && depth < 20; depth++) {
      if (t instanceof ConnectException
          || t instanceof UnknownHostException
          || t instanceof UnresolvedAddressException) {
        return true;
      }
      t = t.getCause();
    }
    return false;
  }

  static String snippet(String body) {
    if (body == null) return "";
    return body.length() <= SNIPPET ? body : body.substring(0, SNIPPET);
  }

  /** A JSON member as text, or null when absent, null or not a string. */
  static String text(JsonObject o, String key) {
    if (o == null || !o.containsKey(key) || o.isNull(key)) return null;
    return switch (o.get(key).getValueType()) {
      case STRING -> o.getString(key);
      case NUMBER -> o.getJsonNumber(key).toString();
      case TRUE -> "true";
      case FALSE -> "false";
      default -> null;
    };
  }
}
