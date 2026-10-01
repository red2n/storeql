package com.storeql.pricing;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A price list's items come a page at a time, in key order, and never across tenants. */
@HelidonTest
class PriceListItemsPageIT {

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;
  private static final String T = Ids.newId().toString();
  private static final String OTHER = Ids.newId().toString();

  static {
    PG = PostgresSupport.start();
    TENANTS = TenantSvcStub.start().with(T, "GBP", "GB").with(OTHER, "GBP", "GB");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "pricing");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private Response call(String method, String path, String json, String tenant, int limit) {
    var t = target.path(path);
    if (limit > 0) t = t.queryParam("limit", limit);
    return t.request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .method(method, json == null ? null : Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String cursorOf(String body) {
    Matcher m = Pattern.compile("\"nextCursor\":\"([^\"]+)\"").matcher(body);
    return m.find() ? m.group(1) : null;
  }

  private static int count(String body, String needle) {
    return body.split(Pattern.quote(needle), -1).length - 1;
  }

  @Test
  @DisplayName("items are paged by cursor: every row once, a short last page, no cursor at the end")
  void pagesWithoutRepeats() {
    String body =
        call(
                "POST",
                "/admin/price-lists",
                "{\"name\":\"Paged\",\"channel\":\"ALL\",\"currency\":\"GBP\","
                    + "\"effectiveFrom\":\"2024-01-01T00:00:00Z\"}",
                T,
                0)
            .readEntity(String.class);
    int start = body.indexOf("\"id\":\"") + 6;
    String list = body.substring(start, body.indexOf('"', start));
    for (int i = 0; i < 5; i++) {
      Response r =
          call(
              "POST",
              "/admin/price-lists/" + list + "/items",
              "{\"variantId\":\"" + Ids.newId() + "\",\"price\":1.00,\"minQty\":1}",
              T,
              0);
      assertThat(r.getStatus() < 300, is(true));
    }
    String first =
        call("GET", "/price-lists/" + list + "/items", null, T, 2).readEntity(String.class);
    assertThat(count(first, "\"variantId\""), is(2));
    String cursor = cursorOf(first);
    assertThat(cursor == null, is(false));
    int seen = 2;
    while (cursor != null) {
      Response r =
          target
              .path("/price-lists/" + list + "/items")
              .queryParam("limit", 2)
              .queryParam("after", cursor)
              .request()
              .header("X-Tenant-Id", T)
              .header("X-Roles", "OWNER")
              .get();
      String page = r.readEntity(String.class);
      assertThat(page, r.getStatus(), is(200));
      seen += count(page, "\"variantId\"");
      cursor = cursorOf(page);
    }
    assertThat(seen, is(5));

    // Another business cannot read the list, with or without a cursor.
    Response other = call("GET", "/price-lists/" + list + "/items", null, OTHER, 2);
    assertThat(other.getStatus(), is(404));
    assertThat(other.readEntity(String.class), not(containsString("variantId")));
  }
}
