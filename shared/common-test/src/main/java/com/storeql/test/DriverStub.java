package com.storeql.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import javax.net.ssl.SSLContext;

/**
 * An external party's API (a card processor, a carrier, a payee check, an SMS gateway, a webhook
 * receiver) stood in for by a server in the test process, for proving a real driver's exact request
 * shape. Every request is kept whole; every answer is what the test programmed. Nothing leaves the
 * machine.
 *
 * <pre>{@code
 * try (DriverStub api = DriverStub.start(r -> DriverStub.Reply.json(200, "{\"id\":\"x\"}"))) {
 *   driver.charge(api.url(), ...);
 *   DriverStub.Recorded r = api.last();
 *   assertEquals("POST", r.method());
 *   assertEquals("1250", r.form().get("amount").get(0));   // minor units
 *   api.assertOneIdempotencyKeyPerCall("Idempotency-Key");
 * }
 * }</pre>
 *
 * Use {@link #startTls(Function)} for a driver that insists on {@code https}: the certificate is
 * made at test time ({@link StubTls}); hand {@link #clientSslContext()} to the driver's client.
 */
public final class DriverStub implements AutoCloseable {

  /** One request as it arrived: nothing normalised, the body byte-for-byte as sent. */
  public record Recorded(
      String method, String path, Map<String, List<String>> headers, byte[] bodyBytes) {

    public Recorded {
      headers = Map.copyOf(headers);
      bodyBytes = bodyBytes.clone();
    }

    @Override
    public byte[] bodyBytes() {
      return bodyBytes.clone();
    }

    public String body() {
      return new String(bodyBytes, StandardCharsets.UTF_8);
    }

    /** The path without its query. */
    public String route() {
      int q = path.indexOf('?');
      return q < 0 ? path : path.substring(0, q);
    }

    /** The first value of a header, by name in any case; null when absent. */
    public String header(String name) {
      List<String> all = headers(name);
      return all.isEmpty() ? null : all.get(0);
    }

    public List<String> headers(String name) {
      for (var e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
      }
      return List.of();
    }

    /** The query string decoded to its members. */
    public Map<String, List<String>> query() {
      int q = path.indexOf('?');
      return q < 0 ? Map.of() : decodeForm(path.substring(q + 1));
    }

    /** An {@code application/x-www-form-urlencoded} body decoded to its members. */
    public Map<String, List<String>> form() {
      return decodeForm(body());
    }

    /**
     * True when the header carries the HMAC-SHA256, in hex, of the raw body under {@code key};
     * {@code prefix} (for example {@code sha256=}) is stripped first, and may be empty.
     */
    public boolean hmacHexMatches(String headerName, String key, String prefix) {
      String v = header(headerName);
      if (v == null || !v.startsWith(prefix)) return false;
      return Hmac.verifyHex(key, body(), v.substring(prefix.length()));
    }

    /** As {@link #hmacHexMatches}, for a scheme that signs {@code timestamp + "." + body}. */
    public boolean hmacHexMatchesWithTimestamp(
        String headerName, String key, String prefix, String timestamp) {
      String v = header(headerName);
      if (v == null || !v.startsWith(prefix)) return false;
      return Hmac.verifyHex(key, timestamp + "." + body(), v.substring(prefix.length()));
    }
  }

  /** What the stub answers. */
  public record Reply(int status, String body, String contentType, Map<String, String> headers) {

    public Reply {
      headers = Map.copyOf(headers);
    }

    public static Reply json(int status, String body) {
      return new Reply(status, body, "application/json", Map.of());
    }

    public static Reply text(int status, String body) {
      return new Reply(status, body, "text/plain", Map.of());
    }

    public static Reply empty(int status) {
      return new Reply(status, "", "text/plain", Map.of());
    }

    public Reply withHeader(String name, String value) {
      Map<String, String> h = new LinkedHashMap<>(headers);
      h.put(name, value);
      return new Reply(status, body, contentType, h);
    }
  }

  private final List<Recorded> requests = new CopyOnWriteArrayList<>();
  private final HttpServer server;
  private final StubTls tls;
  private volatile Function<Recorded, Reply> answer;

  private DriverStub(HttpServer server, StubTls tls, Function<Recorded, Reply> answer) {
    this.server = server;
    this.tls = tls;
    this.answer = answer;
  }

  public static DriverStub start(Function<Recorded, Reply> answer) {
    try {
      HttpServer s =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      return begin(s, null, answer);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static DriverStub startTls(Function<Recorded, Reply> answer) {
    try {
      StubTls tls = StubTls.create();
      HttpsServer s =
          HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      s.setHttpsConfigurator(new HttpsConfigurator(tls.server));
      return begin(s, tls, answer);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static DriverStub begin(HttpServer s, StubTls tls, Function<Recorded, Reply> answer) {
    DriverStub stub = new DriverStub(s, tls, answer);
    s.createContext("/", stub::handle);
    s.start();
    return stub;
  }

  private void handle(HttpExchange x) throws IOException {
    byte[] in = x.getRequestBody().readAllBytes();
    var uri = x.getRequestURI();
    Recorded r =
        new Recorded(
            x.getRequestMethod(),
            uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()),
            Map.copyOf(x.getRequestHeaders()),
            in);
    requests.add(r);
    Reply a;
    try {
      a = answer.apply(r);
    } catch (RuntimeException e) {
      a = Reply.text(500, "stub could not answer: " + e);
    }
    byte[] out = a.body().getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().add("Content-Type", a.contentType());
    a.headers().forEach((k, v) -> x.getResponseHeaders().add(k, v));
    x.sendResponseHeaders(a.status(), out.length == 0 ? -1 : out.length);
    if (out.length > 0) x.getResponseBody().write(out);
    x.close();
  }

  /** From now on answer with this. */
  public void answerWith(Function<Recorded, Reply> next) {
    this.answer = next;
  }

  /** {@code http://127.0.0.1:port} or {@code https://localhost:port}: the base URL to configure. */
  public String url() {
    return tls == null
        ? "http://127.0.0.1:" + server.getAddress().getPort()
        : "https://localhost:" + server.getAddress().getPort();
  }

  /** The context a client must use to trust this stub's throwaway certificate. */
  public SSLContext clientSslContext() {
    if (tls == null) throw new IllegalStateException("this stub does not speak TLS");
    return tls.client;
  }

  public List<Recorded> requests() {
    return new ArrayList<>(requests);
  }

  public int count() {
    return requests.size();
  }

  public Recorded last() {
    if (requests.isEmpty()) throw new AssertionError("the stub received no request");
    return requests.get(requests.size() - 1);
  }

  /**
   * Every recorded request carries the header, and no two calls share a value: one idempotency key
   * per call. A retry of the same call is expected to repeat its key, so a test asserting retries
   * reads {@link #idempotencyKeys} instead.
   */
  public void assertOneIdempotencyKeyPerCall(String headerName) {
    Set<String> seen = new HashSet<>();
    for (Recorded r : requests) {
      String k = r.header(headerName);
      if (k == null || k.isBlank()) {
        throw new AssertionError(r.method() + " " + r.path() + " carries no " + headerName);
      }
      if (!seen.add(k)) {
        throw new AssertionError("the " + headerName + " " + k + " was used for two calls");
      }
    }
  }

  public List<String> idempotencyKeys(String headerName) {
    return requests.stream().map(r -> r.header(headerName)).toList();
  }

  /** The amount as a provider that wants minor units takes it: 12.50 in USD is 1250. */
  public static long minorUnits(BigDecimal amount, String currencyCode) {
    int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
    return amount
        .setScale(digits, RoundingMode.UNNECESSARY)
        .movePointRight(digits)
        .longValueExact();
  }

  /** Fails unless {@code sent} is the whole-number minor-unit form of {@code amount}. */
  public static void assertMinorUnits(String sent, BigDecimal amount, String currencyCode) {
    long want = minorUnits(amount, currencyCode);
    if (sent == null || !sent.matches("-?\\d+") || Long.parseLong(sent) != want) {
      throw new AssertionError(
          "expected " + want + " minor units of " + currencyCode + " but sent " + sent);
    }
  }

  private static Map<String, List<String>> decodeForm(String s) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    if (s == null || s.isEmpty()) return out;
    for (String pair : s.split("&")) {
      int eq = pair.indexOf('=');
      String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
    }
    return out;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
