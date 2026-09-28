package com.storeql.purchase.client.accounting;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * An accounting package's API, stood in for by an HTTP server in the test process: every request is
 * kept whole, and the answer is whatever the test says.
 */
final class PackageStub implements AutoCloseable {

  record Request(String method, String path, Map<String, List<String>> headers, String body) {
    String header(String name) {
      for (var e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name)) return e.getValue().get(0);
      }
      return null;
    }
  }

  record Answer(int status, String body) {}

  final List<Request> requests = new CopyOnWriteArrayList<>();
  private final HttpServer server;
  private volatile Function<Request, Answer> answer;

  private PackageStub(HttpServer server) {
    this.server = server;
  }

  static PackageStub start(Function<Request, Answer> answer) {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      PackageStub stub = new PackageStub(server);
      stub.answer = answer;
      server.createContext(
          "/",
          exchange -> {
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Request r =
                new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath()
                        + (exchange.getRequestURI().getRawQuery() == null
                            ? ""
                            : "?" + exchange.getRequestURI().getRawQuery()),
                    Map.copyOf(exchange.getRequestHeaders()),
                    body);
            stub.requests.add(r);
            Answer a = stub.answer.apply(r);
            byte[] bytes = a.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(a.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.start();
      return stub;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  void answerWith(Function<Request, Answer> next) {
    this.answer = next;
  }

  String url() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  Request last() {
    return requests.get(requests.size() - 1);
  }

  List<Request> all() {
    return new ArrayList<>(requests);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
