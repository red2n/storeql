package com.storeql.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** A receiver's answer is read up to the snippet size and no further. */
class WebhookSnippetTest {

  @Test
  void aHugeAnswerIsCutAtTheSnippetSize() throws Exception {
    byte[] big = new byte[6 * 1024 * 1024];
    Arrays.fill(big, (byte) 'x');
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        ex -> {
          ex.sendResponseHeaders(200, big.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(big);
          } catch (java.io.IOException ignored) {
            // the client stops reading once it has its snippet
          }
        });
    server.start();
    try (HttpClientResponse res =
        WebClient.builder()
            .baseUri("http://127.0.0.1:" + server.getAddress().getPort())
            .build()
            .get("/")
            .request()) {
      String snippet = WebhookDeliverer.snippet(res);
      assertEquals(8192, snippet.length());
      assertTrue(snippet.chars().allMatch(c -> c == 'x'));
    } finally {
      server.stop(0);
    }
  }
}
