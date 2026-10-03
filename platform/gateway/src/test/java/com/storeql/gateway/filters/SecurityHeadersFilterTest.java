package com.storeql.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Every answer that carries a token pair is marked never to be stored (RFC 6749 §5.1): a sign-in, a
 * refresh, and a sign-up — the shopper's and the business's alike, since both sign the person in at
 * once.
 */
@ExtendWith(MockitoExtension.class)
class SecurityHeadersFilterTest {

  @Mock ContainerRequestContext request;
  @Mock ContainerResponseContext response;
  @Mock UriInfo uriInfo;

  private final SecurityHeadersFilter filter = new SecurityHeadersFilter();
  private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

  @BeforeEach
  void setUp() {
    lenient().when(request.getUriInfo()).thenReturn(uriInfo);
    lenient().when(request.getMethod()).thenReturn("POST");
    lenient().when(response.getHeaders()).thenReturn(headers);
  }

  @Test
  void aSignUpThatAnswersWithTokensIsNeverCached() throws IOException {
    for (String path :
        new String[] {
          "api/iam-svc/auth/register",
          "api/iam-svc/auth/register/business",
          "api/v1/iam-svc/auth/register/business",
          "api/iam-svc/auth/login",
          "api/iam-svc/auth/refresh"
        }) {
      headers.clear();
      when(uriInfo.getPath()).thenReturn(path);

      filter.filter(request, response);

      assertEquals("no-store", headers.getFirst("Cache-Control"), path);
      assertEquals("no-cache", headers.getFirst("Pragma"), path);
    }
  }

  @Test
  void anOrdinaryAnswerKeepsItsOwnCaching() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/product-svc/catalog/products");

    filter.filter(request, response);

    assertFalse(headers.containsKey("Cache-Control"));
    assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"));
  }
}
