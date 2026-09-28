package com.storeql.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.storeql.gateway.ApiVersions;
import com.storeql.gateway.GatewayConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Every answer on the unversioned alias says so in the standard headers (22.8): Deprecation (RFC
 * 9745, a date), Sunset (RFC 8594) and Link to the successor; the versioned form says nothing.
 */
@ExtendWith(MockitoExtension.class)
class ApiVersionDeprecationFilterTest {

  @Mock GatewayConfig config;
  @Mock ContainerRequestContext req;
  @Mock ContainerResponseContext res;
  @Mock UriInfo uriInfo;

  private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
  private ApiVersionDeprecationFilter filter;

  @BeforeEach
  void setUp() {
    lenient()
        .when(config.apiVersions())
        .thenReturn(ApiVersions.Policy.of("v1", "v1", "2026-09-23", "2027-09-30"));
    lenient().when(req.getUriInfo()).thenReturn(uriInfo);
    lenient().when(res.getHeaders()).thenReturn(headers);
    filter = new ApiVersionDeprecationFilter();
    filter.config = config;
  }

  @Test
  @DisplayName(
      "The alias: Deprecation @<epoch of the day>, Sunset as an HTTP date, Link to /api/v1")
  void theAliasIsMarked() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders");
    filter.filter(req, res);
    assertEquals("@1790121600", headers.getFirst("Deprecation"));
    assertEquals("Thu, 30 Sep 2027 00:00:00 GMT", headers.getFirst("Sunset"));
    assertEquals("</api/v1>; rel=\"successor-version\"", headers.getFirst("Link"));
  }

  @Test
  @DisplayName(
      "The versioned form, the versions document and anything outside the proxy carry none of it")
  void nothingElseIsMarked() throws IOException {
    for (String path :
        new String[] {
          "api/v1/order-svc/orders",
          "/api/v1/order-svc/",
          "api/versions",
          "health/ready",
          ".well-known/security.txt"
        }) {
      headers.clear();
      when(uriInfo.getPath()).thenReturn(path);
      filter.filter(req, res);
      assertFalse(headers.containsKey("Deprecation"), path);
      assertFalse(headers.containsKey("Sunset"), path);
      assertFalse(headers.containsKey("Link"), path);
    }
  }
}
