package com.storeql.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * The stub answers a path as tenant-svc would, and refuses a query that was escaped into the path —
 * what Helidon's WebClient sends for a "?" written into a path string, and what a prefix match on
 * the decoded path used to answer as if nothing were wrong.
 */
class TenantSvcStubTest {

  @Test
  void aQueryEscapedIntoThePathIsNotFound() throws Exception {
    String tenant = Ids.newId().toString();
    try (TenantSvcStub stub = TenantSvcStub.start().with(tenant, "GBP", "GB")) {
      String base = System.getProperty("storeql.clients.tenant-svc.url");
      HttpClient http = HttpClient.newHttpClient();

      HttpResponse<String> asServed =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/admin/tenant"))
                  .header("X-Tenant-Id", tenant)
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, asServed.statusCode());

      HttpResponse<String> escaped =
          http.send(
              HttpRequest.newBuilder(URI.create(base + "/admin/tenant%3Fx=1"))
                  .header("X-Tenant-Id", tenant)
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(404, escaped.statusCode());
      assertTrue(escaped.body().contains("a query escaped into the path"), escaped.body());
    }
  }
}
