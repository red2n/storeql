package com.storeql.product;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.RedisSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A plan's cap on product images (21.11), refused by the service that holds the bytes: what fits is
 * taken, the image that would take the business past the cap is refused before it is stored and
 * told the plan's figure, a replacement is measured as the room it frees plus the room it takes,
 * deleting makes room, and a business whose plan names no cap is not held back. Real Postgres;
 * tenant-svc stubbed; Kafka and Consul disabled.
 */
@HelidonTest
class ImageStorageCapIT {
  private static final PostgresSupport PG;
  private static final RedisSupport REDIS;
  private static final TenantSvcStub TENANTS;

  private static final String CAPPED = Ids.newId().toString();
  private static final String FREE = Ids.newId().toString();
  private static final String USER = Ids.newId().toString();

  /** Under the per-image ceiling of 256 KB; five fit in a megabyte and a sixth does not. */
  private static final byte[] IMAGE = new byte[200 * 1024];

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "product");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    REDIS = RedisSupport.start();
    System.setProperty("storeql.redis.host", REDIS.host());
    System.setProperty("storeql.redis.port", String.valueOf(REDIS.port()));
    System.setProperty("storeql.redis.password", "");
    TENANTS =
        TenantSvcStub.start()
            .with(CAPPED, "GBP", "GB")
            .withLimit(CAPPED, "images.mb.max", 1)
            .with(FREE, "GBP", "GB");
    Arrays.fill(IMAGE, (byte) 7);
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    TENANTS.close();
    PG.stop();
    REDIS.stop();
  }

  @Test
  @DisplayName("Five images fit in a megabyte; the sixth is refused before a byte is stored")
  void theImageThatWouldPassTheCapIsRefusedAndToldTheFigure() {
    String[] products = new String[6];
    for (int i = 0; i < 6; i++) products[i] = product(CAPPED, "Capped " + i);
    for (int i = 0; i < 5; i++) {
      Response r = upload(CAPPED, products[i], IMAGE);
      assertThat(r.readEntity(String.class), r.getStatus(), is(200));
    }
    Response sixth = upload(CAPPED, products[5], IMAGE);
    String body = sixth.readEntity(String.class);
    assertThat(body, sixth.getStatus(), is(409));
    assertThat(body, containsString("PLAN_LIMIT_REACHED"));
    assertThat(body, containsString("allows 1 MB of product images"));
    assertThat(body, containsString("would make 1.2 MB"));
    assertThat("nothing of the sixth was kept", imageRows(products[5]), is(0));

    // Replacing one of the five is measured as what it frees plus what it takes: it fits.
    Response replaced = upload(CAPPED, products[0], IMAGE);
    assertThat(replaced.readEntity(String.class), replaced.getStatus(), is(200));

    // Deleting one makes room for the sixth.
    Response deleted = delete(CAPPED, "/admin/products/" + products[1] + "/image");
    assertThat(deleted.getStatus(), is(200));
    Response now = upload(CAPPED, products[5], IMAGE);
    assertThat(now.readEntity(String.class), now.getStatus(), is(200));
  }

  @Test
  @DisplayName("A business whose plan names no cap keeps as many images as it likes")
  void noCapHoldsNothingBack() {
    for (int i = 0; i < 7; i++) {
      Response r = upload(FREE, product(FREE, "Free " + i), IMAGE);
      assertThat(r.readEntity(String.class), r.getStatus(), is(200));
    }
  }

  @Test
  @DisplayName("The table itself holds every image under 256 KB, whoever writes it (V12)")
  void theTableRefusesAnImageOf256KbOrMoreWhateverTheService() throws Exception {
    String small = product(FREE, "Table small " + Ids.newId());
    String edge = product(FREE, "Table edge " + Ids.newId());
    String big = product(FREE, "Table big " + Ids.newId());

    // One byte under 256 KB is taken, straight into the table behind the application.
    assertThat(insertImage(small, FREE, 262143), nullValue());
    assertThat(imageRows(small), is(1));

    // Exactly 256 KB, and a megabyte, are refused by the CHECK created with the table.
    for (String product : new String[] {edge, big}) {
      int size = product.equals(edge) ? 262144 : 1024 * 1024;
      assertThat(
          "a " + size + "-byte image",
          insertImage(product, FREE, size),
          containsString("chk_product_images_size_under_256kb"));
      assertThat("nothing of it was kept", imageRows(product), is(0));
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private String product(String tenant, String name) {
    Response r =
        request(tenant, "/admin/products")
            .post(
                Entity.entity(
                    "{\"name\":\"" + name + " " + Ids.newId() + "\",\"sellablePos\":true}",
                    MediaType.APPLICATION_JSON));
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(201));
    try (var reader = Json.createReader(new StringReader(body))) {
      return reader.readObject().getJsonObject("data").getString("id");
    }
  }

  private Response upload(String tenant, String productId, byte[] bytes) {
    return request(tenant, "/admin/products/" + productId + "/image")
        .put(Entity.entity(bytes, "image/png"));
  }

  /** How many image rows a product has in the database: one, or none. */
  private static int imageRows(String productId) {
    try (var c = PG.dataSource().getConnection();
        var ps =
            c.prepareStatement(
                "SELECT count(*) FROM product.product_images WHERE product_id = ?::uuid")) {
      ps.setString(1, productId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("counting image rows", e);
    }
  }

  /**
   * Writes an image row straight into the table.
   *
   * @return the database's refusal, or {@code null} when the row went in
   */
  private static String insertImage(String productId, String tenant, int size) {
    try (var c = PG.dataSource().getConnection();
        var ps =
            c.prepareStatement(
                "INSERT INTO product.product_images (product_id, tenant_id, content_type, bytes)"
                    + " VALUES (?::uuid, ?::uuid, 'image/png', ?)")) {
      ps.setString(1, productId);
      ps.setString(2, tenant);
      ps.setBytes(3, new byte[size]);
      ps.executeUpdate();
      return null;
    } catch (java.sql.SQLException e) {
      return e.getMessage();
    }
  }

  private Response delete(String tenant, String path) {
    return request(tenant, path).delete();
  }

  private jakarta.ws.rs.client.Invocation.Builder request(String tenant, String path) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", "OWNER");
  }
}
