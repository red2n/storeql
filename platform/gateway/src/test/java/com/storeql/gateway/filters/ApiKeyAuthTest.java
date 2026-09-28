package com.storeql.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.gateway.GatewayConfig;
import com.storeql.ids.Ids;
import com.storeql.web.ApiResponse;
import com.storeql.web.HttpHeaders;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A business's API key at the door (22.7): a bearer that is a key, not a token, is asked about at
 * iam-svc and then acts as the business in the role the key was given — never as a person who can
 * sign in, manage logins or start a business; a key that is unknown, revoked or expired is refused
 * as invalid, one of a suspended business as suspended, and when iam-svc cannot be asked the caller
 * is told to try again rather than being signed out.
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyAuthTest {

  @Mock GatewayConfig config;
  @Mock TenantStatusGate tenantStatusGate;
  @Mock ApiKeyIntrospector apiKeys;

  private static final String KEY = "sqk_" + "A".repeat(40);
  private static final String KEY_ID = Ids.newId().toString();
  private static final String TENANT = Ids.newId().toString();
  private static final String STORE = Ids.newId().toString();

  private JwtAuthFilter filter;

  @BeforeEach
  void setUp() {
    lenient().when(config.jwtIssuer()).thenReturn("storeql");
    lenient().when(tenantStatusGate.isActive(any())).thenReturn(true);
    filter = new JwtAuthFilter();
    filter.config = config;
    filter.tenantStatusGate = tenantStatusGate;
    filter.signingKeys = SigningKeySet.of(java.util.Map.of());
    filter.apiKeys = apiKeys;
  }

  /** A request carrying the key as its bearer, with its own header map to read back. */
  private record Request(ContainerRequestContext ctx, MultivaluedMap<String, String> headers) {}

  private static Request request(String method, String path) {
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);
    UriInfo uri = mock(UriInfo.class);
    MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
    lenient().when(ctx.getUriInfo()).thenReturn(uri);
    lenient().when(uri.getPath()).thenReturn(path);
    lenient().when(ctx.getMethod()).thenReturn(method);
    lenient().when(ctx.getHeaders()).thenReturn(headers);
    lenient().when(ctx.getHeaderString("Authorization")).thenReturn("Bearer " + KEY);
    return new Request(ctx, headers);
  }

  private static Response refusal(ContainerRequestContext ctx) {
    ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(captor.capture());
    return captor.getValue();
  }

  private static String codeOf(Response r) {
    return ((ApiResponse<?>) r.getEntity()).error().code();
  }

  @Test
  void aKeyActsForItsBusinessInTheRoleItWasGiven() throws IOException {
    when(apiKeys.introspect(KEY))
        .thenReturn(
            new ApiKeyIntrospector.Active(
                KEY_ID, TENANT, "STOREKEEPER", List.of(STORE), "Warehouse ERP"));
    Request r = request("POST", "api/inventory-svc/admin/inventory/receive");

    filter.filter(r.ctx());

    verify(r.ctx(), never()).abortWith(any());
    assertEquals(KEY_ID, r.headers().getFirst(HttpHeaders.USER_ID), "the key is the actor");
    assertEquals(TENANT, r.headers().getFirst(HttpHeaders.TENANT_ID));
    assertEquals("STOREKEEPER", r.headers().getFirst(HttpHeaders.ROLES));
    assertEquals(STORE, r.headers().getFirst(HttpHeaders.STORE_IDS));
    assertEquals("api-key", r.headers().getFirst(HttpHeaders.AUTH_METHODS));
    assertNull(r.headers().getFirst(HttpHeaders.USER_EMAIL), "a key has no address");
    assertNull(r.headers().getFirst(HttpHeaders.PERMISSIONS), "a tier, judged by its defaults");
    assertNull(r.headers().getFirst(HttpHeaders.AUTH_SCOPE));
  }

  @Test
  void aKeyForEveryStoreNamesNone() throws IOException {
    when(apiKeys.introspect(KEY))
        .thenReturn(new ApiKeyIntrospector.Active(KEY_ID, TENANT, "MANAGER", List.of(), "ERP"));
    Request r = request("GET", "api/order-svc/admin/orders");

    filter.filter(r.ctx());

    verify(r.ctx(), never()).abortWith(any());
    assertEquals("MANAGER", r.headers().getFirst(HttpHeaders.ROLES));
    assertNull(r.headers().getFirst(HttpHeaders.STORE_IDS));
  }

  @Test
  void aKeyCannotSignInManageLoginsOrStartABusiness() throws IOException {
    for (String[] route :
        List.of(
            new String[] {"GET", "api/iam-svc/auth/me"},
            new String[] {"POST", "api/iam-svc/auth/admin/api-keys"},
            new String[] {"GET", "api/iam-svc/auth/admin/api-keys"},
            new String[] {"PUT", "api/iam-svc/auth/admin/sso"},
            new String[] {"POST", "api/iam-svc/auth/mfa/totp/enrol"},
            new String[] {"POST", "api/iam-svc/pos/sessions"},
            new String[] {"POST", "api/tenant-svc/onboarding/tenants"},
            new String[] {"POST", "api/tenant-svc/onboarding/stores"},
            new String[] {"GET", "api/tenant-svc/platform/tenants"},
            new String[] {"GET", "api/v1/iam-svc/auth/me"})) {
      Request r = request(route[0], route[1]);
      filter.filter(r.ctx());
      Response refused = refusal(r.ctx());
      assertEquals(403, refused.getStatus(), route[1]);
      assertEquals("API_KEY_ROUTE_FORBIDDEN", codeOf(refused), route[1]);
      assertNull(r.headers().getFirst(HttpHeaders.TENANT_ID), route[1]);
    }
    verify(apiKeys, never()).introspect(any());
  }

  @Test
  void anUnknownRevokedOrExpiredKeyIsInvalid() throws IOException {
    for (String reason : List.of("unknown", "revoked", "expired")) {
      when(apiKeys.introspect(KEY)).thenReturn(new ApiKeyIntrospector.Refused(reason));
      Request r = request("GET", "api/product-svc/admin/products");
      filter.filter(r.ctx());
      Response refused = refusal(r.ctx());
      assertEquals(401, refused.getStatus(), reason);
      assertNull(r.headers().getFirst(HttpHeaders.TENANT_ID), reason);
    }
  }

  @Test
  void aKeyOfASuspendedBusinessIsRefusedAsTheBusinessIs() throws IOException {
    when(apiKeys.introspect(KEY)).thenReturn(new ApiKeyIntrospector.Refused("tenant suspended"));
    Request r = request("GET", "api/product-svc/admin/products");

    filter.filter(r.ctx());

    Response refused = refusal(r.ctx());
    assertEquals(403, refused.getStatus());
    assertEquals("TENANT_INACTIVE", codeOf(refused));
  }

  @Test
  void whenIamCannotBeAskedTheCallerIsToldToTryAgainNotSignedOut() throws IOException {
    when(apiKeys.introspect(KEY)).thenReturn(new ApiKeyIntrospector.Unavailable());
    Request r = request("GET", "api/product-svc/admin/products");

    filter.filter(r.ctx());

    assertEquals(503, refusal(r.ctx()).getStatus());
  }
}
