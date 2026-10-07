package com.storeql.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.helidon.webclient.api.WebClient;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** An upstream body is streamed to the caller, not buffered whole in gateway heap (group-4). */
class ProxyResourceStreamingTest {

  private HttpServer server;
  private final byte[] big = new byte[600_000];

  @BeforeEach
  void startUpstream() throws IOException {
    Arrays.fill(big, (byte) 7);
    big[0] = 1;
    big[big.length - 1] = 9;
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/image",
        ex -> {
          ex.getResponseHeaders().add("Content-Type", "image/png");
          ex.sendResponseHeaders(200, big.length);
          ex.getResponseBody().write(big);
          ex.close();
        });
    server.createContext(
        "/bare",
        ex -> {
          ex.sendResponseHeaders(405, -1);
          ex.close();
        });
    server.start();
  }

  @AfterEach
  void stopUpstream() {
    server.stop(0);
  }

  private ProxyResource resource() {
    ProxyResource r = new ProxyResource();
    r.breaker = new UpstreamCircuitBreaker();
    return r;
  }

  private String url(String path) {
    return "http://localhost:" + server.getAddress().getPort() + path;
  }

  @Test
  void aBinaryBodyIsHandedOnAsAStreamWithItsTypeAndLengthIntact() throws IOException {
    WebClient client = WebClient.builder().build();
    Response response = resource().relay(() -> client.get(url("/image")).request(), "svc", "rid");

    assertEquals(200, response.getStatus());
    assertEquals("image/png", response.getMediaType().toString());
    assertEquals("600000", response.getHeaderString("Content-Length"));
    StreamingOutput stream = assertInstanceOf(StreamingOutput.class, response.getEntity());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    stream.write(out);
    assertArrayEquals(big, out.toByteArray());
  }

  @Test
  void aBareStatusHasNoEntityAndNothingIsLeftOpen() {
    WebClient client = WebClient.builder().build();
    Response response = resource().relay(() -> client.get(url("/bare")).request(), "svc", "rid");

    assertEquals(405, response.getStatus());
    assertFalse(response.hasEntity());
    assertTrue(response.getEntity() == null);
  }
}
