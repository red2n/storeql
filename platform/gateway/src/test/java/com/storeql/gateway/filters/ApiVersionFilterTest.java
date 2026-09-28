package com.storeql.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.gateway.ApiVersions;
import com.storeql.gateway.GatewayConfig;
import com.storeql.web.ApiResponse;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The door refuses a version nobody published, and the alias once it is retired (22.8). */
@ExtendWith(MockitoExtension.class)
class ApiVersionFilterTest {

  @Mock GatewayConfig config;
  @Mock ContainerRequestContext ctx;
  @Mock UriInfo uriInfo;

  private ApiVersionFilter filter;

  @BeforeEach
  void setUp() {
    lenient()
        .when(config.apiVersions())
        .thenReturn(ApiVersions.Policy.of("v1", "v1", "2026-09-23", "2027-09-30"));
    lenient().when(ctx.getUriInfo()).thenReturn(uriInfo);
    filter = new ApiVersionFilter();
    filter.config = config;
    filter.clock = Clock.fixed(Instant.parse("2026-09-23T12:00:00Z"), ZoneOffset.UTC);
  }

  @Test
  @DisplayName("A published version and the alias pass through; the versions document too")
  void whatPasses() throws IOException {
    for (String path :
        new String[] {
          "api/v1/order-svc/orders", "api/order-svc/orders", "api/versions", "health/ready"
        }) {
      when(uriInfo.getPath()).thenReturn(path);
      filter.filter(ctx);
    }
    verify(ctx, never()).abortWith(any());
  }

  @Test
  @DisplayName("An unknown version is 404 API_VERSION_UNKNOWN, with a Link to the latest")
  void anUnknownVersionIs404() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/v9/order-svc/orders");
    filter.filter(ctx);
    Response r = aborted();
    assertEquals(404, r.getStatus());
    assertEquals("API_VERSION_UNKNOWN", code(r));
    assertEquals("</api/v1>; rel=\"latest-version\"", r.getHeaderString("Link"));
  }

  @Test
  @DisplayName("From the sunset day the alias is 410 API_VERSION_RETIRED, with the successor")
  void theRetiredAliasIs410() throws IOException {
    filter.clock = Clock.fixed(Instant.parse("2027-09-30T00:00:00Z"), ZoneOffset.UTC);
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders");
    filter.filter(ctx);
    Response r = aborted();
    assertEquals(410, r.getStatus());
    assertEquals("API_VERSION_RETIRED", code(r));
    assertEquals("</api/v1>; rel=\"successor-version\"", r.getHeaderString("Link"));
    assertEquals("Thu, 30 Sep 2027 00:00:00 GMT", r.getHeaderString("Sunset"));
  }

  @Test
  @DisplayName("The versioned form outlives the alias")
  void theVersionedFormOutlivesTheAlias() throws IOException {
    filter.clock = Clock.fixed(Instant.parse("2028-01-01T00:00:00Z"), ZoneOffset.UTC);
    when(uriInfo.getPath()).thenReturn("api/v1/order-svc/orders");
    filter.filter(ctx);
    verify(ctx, never()).abortWith(any());
  }

  private Response aborted() {
    ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(captor.capture());
    return captor.getValue();
  }

  private static String code(Response r) {
    return ((ApiResponse<?>) r.getEntity()).error().code();
  }
}
