package com.storeql.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.gateway.GatewayConfig;
import jakarta.json.bind.JsonbBuilder;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Request bodies stay at 1 MB except on a route that takes a document: refused on the declared
 * length before anything is read, counted only as far as the cap when no length is declared, and a
 * path that walks out of the upload route does not take its cap with it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BodySizeFilterTest {

  private static final int MB = 1024 * 1024;
  private static final String UPLOAD = "http://gw/api/purchase-svc/e-invoices";

  @Mock ContainerRequestContext ctx;
  @Mock UriInfo uriInfo;
  @Mock GatewayConfig config;

  private BodySizeFilter filter;

  @BeforeEach
  void setUp() {
    when(config.maxBodyBytes()).thenReturn(MB);
    when(config.uploadRoutes()).thenReturn("/api/purchase-svc/e-invoices=21000000");
    filter = new BodySizeFilter();
    filter.config = config;
    when(ctx.getUriInfo()).thenReturn(uriInfo);
    when(uriInfo.getRequestUri()).thenReturn(URI.create("http://gw/api/order-svc/orders"));
    when(ctx.getMethod()).thenReturn("POST");
    when(ctx.hasEntity()).thenReturn(true);
  }

  private Response refused() {
    ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("A declared length over the cap is refused with 413 before a byte is read")
  void refusesOnTheDeclaredLength() throws IOException {
    when(ctx.getHeaderString(HttpHeaders.CONTENT_LENGTH)).thenReturn(Integer.toString(MB + 1));
    filter.filter(ctx);
    Response r = refused();
    assertEquals(413, r.getStatus());
    assertTrue(JsonbBuilder.create().toJson(r.getEntity()).contains(BodySizeFilter.CODE));
    verify(ctx, never()).getEntityStream();
  }

  @Test
  @DisplayName("The e-invoice route takes a 5 MB PDF, and not one past its own cap")
  void theUploadRouteHasItsOwnCap() throws IOException {
    when(uriInfo.getRequestUri()).thenReturn(URI.create(UPLOAD));
    when(ctx.getHeaderString(HttpHeaders.CONTENT_LENGTH)).thenReturn(Integer.toString(5 * MB));
    filter.filter(ctx);
    verify(ctx, never()).abortWith(any());
    when(ctx.getHeaderString(HttpHeaders.CONTENT_LENGTH)).thenReturn("21000001");
    filter.filter(ctx);
    assertEquals(413, refused().getStatus());
  }

  @Test
  @DisplayName("Only the upload route itself: its sub-routes and a walk out of it keep the default")
  void aPathOutOfTheRouteDoesNotKeepItsCap() {
    assertEquals(21_000_000L, filter.limitFor(URI.create(UPLOAD + "/")));
    assertEquals(MB, filter.limitFor(URI.create(UPLOAD + "/../../order-svc/orders")));
    assertEquals(
        MB, filter.limitFor(URI.create(UPLOAD + "/0192f000-0000-7000-8000-000000000001/match")));
    assertEquals(21_000_000L, filter.limitFor(URI.create("http://gw/API/Purchase-SVC/e-invoices")));
  }

  @Test
  @DisplayName("With no length declared, a body is counted up to the cap and refused past it")
  void countsAChunkedBody() throws IOException {
    when(ctx.getEntityStream()).thenReturn(new ByteArrayInputStream(new byte[MB + 10]));
    filter.filter(ctx);
    assertEquals(413, refused().getStatus());
  }

  @Test
  @DisplayName("A body under the cap goes on intact, whether or not it declared its length")
  void aSmallBodyIsIntact() throws IOException {
    byte[] json = "{\"note\":\"fine\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(ctx.getEntityStream()).thenReturn(new ByteArrayInputStream(json));
    ArgumentCaptor<InputStream> replaced = ArgumentCaptor.forClass(InputStream.class);
    filter.filter(ctx);
    verify(ctx, never()).abortWith(any());
    verify(ctx).setEntityStream(replaced.capture());
    assertArrayEquals(json, replaced.getValue().readAllBytes());
  }

  @Test
  @DisplayName("A Content-Length that is not a number is a bad request; a GET is not measured")
  void badLengthAndReads() throws IOException {
    when(ctx.getHeaderString(HttpHeaders.CONTENT_LENGTH)).thenReturn("lots");
    filter.filter(ctx);
    assertEquals(400, refused().getStatus());
    when(ctx.getMethod()).thenReturn("GET");
    BodySizeFilter reads = new BodySizeFilter();
    reads.config = config;
    ContainerRequestContext get = org.mockito.Mockito.mock(ContainerRequestContext.class);
    when(get.getMethod()).thenReturn("GET");
    reads.filter(get);
    verify(get, never()).abortWith(any());
  }

  @Test
  @DisplayName("The routes in force are the whole list: no packaged copy shadows it (SJ-D64)")
  void theRoutesInForceAreTheWholeList() throws Exception {
    String key = "storeql.gateway.upload-routes";
    java.util.Properties packaged = new java.util.Properties();
    try (InputStream in =
        GatewayConfig.class.getResourceAsStream("/META-INF/microprofile-config.properties")) {
      packaged.load(in);
    }
    String declared =
        GatewayConfig.class
            .getDeclaredField("uploadRoutes")
            .getAnnotation(org.eclipse.microprofile.config.inject.ConfigProperty.class)
            .defaultValue();
    // What the gateway runs with when no environment overrides it: the packaged file wins over the
    // declared default, which is how a one-route copy there once held every network's delivery of
    // a supplier's PDF to the 1 MB cap.
    Map<String, Long> inForce = BodySizeFilter.parseRoutes(packaged.getProperty(key, declared));
    assertEquals(
        Map.of(
            "/api/purchase-svc/e-invoices", 21_000_000L,
            "/api/purchase-svc/e-invoices/inbound/peppol", 21_000_000L,
            "/api/purchase-svc/e-invoices/inbound/fr_pdp", 21_000_000L,
            "/api/purchase-svc/e-invoices/inbound/simulated", 21_000_000L,
            "/api/payment-svc/admin/settlements", 6_000_000L,
            "/api/product-svc/admin/catalogue-imports", 12_582_912L),
        inForce);
    long server = Long.parseLong(packaged.getProperty("server.max-payload-size"));
    assertTrue(
        inForce.values().stream().allMatch(cap -> cap <= server),
        "the server's own cap must admit the largest route's");
  }

  @Test
  @DisplayName("Route entries that do not parse are skipped rather than trusted")
  void routeListsAreParsedStrictly() {
    assertEquals(
        Map.of("/api/a/docs", 5L),
        BodySizeFilter.parseRoutes("/api/a/docs=5, relative=9, /api/b=lots, =3, /api/c=-1,,"));
  }
}
