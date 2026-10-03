package com.storeql.purchase.client.accounting;

import com.storeql.purchase.config.Jsons;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientRequest;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.StringReader;
import java.net.ConnectException;
import java.net.URI;
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
      try (JsonReader reader =
          Jsons.PROVIDER.createReader(new StringReader(body == null ? "" : body))) {
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
    HttpClientRequest request = form(method, url, headers, body == null ? null : contentType);
    boolean sent = false;
    try {
      HttpClientResponse response =
          body == null ? request.request() : request.submit(body.getBytes(StandardCharsets.UTF_8));
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
   * The request, formed and not yet sent. An address or a header the connection's settings cannot
   * make is refused here, before anything leaves: there is no answer to wait for and nothing the
   * clock can mend by trying again, so it is a refusal for a person to put right — never a raw
   * failure that would leave a claimed push with nobody told.
   */
  private HttpClientRequest form(
      String method, String url, Map<String, String> headers, String contentType) {
    URI target;
    try {
      // A URI, never the String form: that escapes the "?" (and any "%") into the path, and a
      // package then answers 404 for ".../journalentry%3Fminorversion=…".
      target = URI.create(url);
    } catch (IllegalArgumentException e) {
      throw AccountingPackage.Refused.unsent(
          "the package's address cannot be formed from the connection's settings ("
              + e.getMessage()
              + "); correct them and connect again",
          e);
    }
    for (var h : headers.entrySet()) {
      if (!fieldValue(h.getValue())) {
        // Named, never echoed: the value may be a token.
        throw AccountingPackage.Refused.unsent(
            "the "
                + h.getKey()
                + " header cannot be formed from the connection's settings or tokens (a line break"
                + " or a control character); correct them and connect again");
      }
    }
    try {
      HttpClientRequest request =
          switch (method) {
            case "GET" -> http.get().uri(target);
            case "PUT" -> http.put().uri(target);
            default -> http.post().uri(target);
          };
      request.header(HeaderNames.USER_AGENT, USER_AGENT);
      for (var h : headers.entrySet()) {
        request.header(HeaderNames.create(h.getKey()), h.getValue());
      }
      if (contentType != null) {
        request.header(HeaderNames.CONTENT_TYPE, contentType);
      }
      return request;
    } catch (IllegalArgumentException e) {
      // The cause is dropped on purpose: its message may hold a header's value, which may be a
      // token.
      throw AccountingPackage.Refused.unsent( // NOPMD - PreserveStackTrace: see above
          "the request to the package cannot be formed from the connection's settings; correct"
              + " them and connect again");
    }
  }

  /** RFC 9110 §5.5: a field value holds no line break and no control character but a tab. */
  private static boolean fieldValue(String value) {
    if (value == null) return false;
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if ((ch < 0x20 && ch != '\t') || ch == 0x7F) return false;
    }
    return true;
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
