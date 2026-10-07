package com.storeql.gateway.filters;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.gateway.GatewayConfig;
import com.storeql.gateway.flow.FlowAttributes;
import com.storeql.ids.Ids;
import com.storeql.test.SigningKeysFixture;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whom a request is for, as the health screen's recording is told. {@code JwtAuthFilter} sets the
 * business and the caller as request properties only once it has verified them — a token, an API
 * key, or a storefront the status gate passed — and never from a header the client wrote, because
 * the screen shows a business the failures attributed to it.
 */
class JwtAuthFilterAttributionTest {

  private static final SigningKeysFixture KEYS = SigningKeysFixture.generate("attribution-key");
  private static final String TENANT = Ids.newId().toString();
  private static final String USER = Ids.newId().toString();

  private final GatewayConfig config = mock(GatewayConfig.class);
  private final TenantStatusGate gate = mock(TenantStatusGate.class);
  private final ApiKeyIntrospector apiKeys = mock(ApiKeyIntrospector.class);
  private final ContainerRequestContext ctx = mock(ContainerRequestContext.class);
  private final UriInfo uri = mock(UriInfo.class);
  private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
  private JwtAuthFilter filter;

  @BeforeEach
  void setUp() {
    when(config.jwtIssuer()).thenReturn("storeql");
    lenient().when(gate.isActive(any())).thenReturn(true);
    filter = new JwtAuthFilter();
    filter.config = config;
    filter.tenantStatusGate = gate;
    filter.apiKeys = apiKeys;
    filter.signingKeys = SigningKeySet.of(Map.of(KEYS.kid(), KEYS.publicKey()));
    lenient().when(ctx.getUriInfo()).thenReturn(uri);
    lenient().when(ctx.getHeaders()).thenReturn(headers);
    lenient().when(ctx.getMethod()).thenReturn("GET");
    lenient().when(uri.getPath()).thenReturn("api/v1/order-svc/orders");
  }

  private String token(Map<String, Object> claims) {
    return KEYS.sign("storeql", USER, claims, 600);
  }

  private void bearer(String token) {
    lenient().when(ctx.getHeaderString("Authorization")).thenReturn("Bearer " + token);
  }

  @Test
  @DisplayName("A verified token attributes the request to its business and its subject")
  void aVerifiedTokenAttributes() throws IOException {
    bearer(token(Map.of("tenant", TENANT, "roles", List.of("OWNER"))));
    filter.filter(ctx);
    verify(ctx).setProperty(FlowAttributes.TENANT_ID, TENANT);
    verify(ctx).setProperty(FlowAttributes.USER_ID, USER);
  }

  @Test
  @DisplayName("A shopper's token names a caller but no business, so no business is attributed")
  void aShoppersTokenHasNoBusiness() throws IOException {
    bearer(token(Map.of("roles", List.of("CUSTOMER"))));
    filter.filter(ctx);
    verify(ctx).setProperty(FlowAttributes.USER_ID, USER);
    verify(ctx, never()).setProperty(eq(FlowAttributes.TENANT_ID), any());
  }

  @Test
  @DisplayName("No token, and a token that does not verify, attribute nothing to anyone")
  void failedAuthenticationAttributesNothing() throws IOException {
    filter.filter(ctx); // no Authorization header
    bearer("forged.token.value");
    filter.filter(ctx);
    bearer(
        SigningKeysFixture.generate("attribution-key")
            .sign("storeql", USER, Map.of("tenant", TENANT, "roles", List.of("OWNER")), 600));
    filter.filter(ctx); // a stranger's signature under our key id
    verify(ctx, never()).setProperty(eq(FlowAttributes.TENANT_ID), any());
    verify(ctx, never()).setProperty(eq(FlowAttributes.USER_ID), any());
  }

  @Test
  @DisplayName("A header the client wrote is never an attribution, even one that stays in place")
  void aClientHeaderIsNeverAnAttribution() throws IOException {
    // Onboarding keeps the client's X-Tenant-Id for a signed-in person with no business yet.
    when(uri.getPath()).thenReturn("api/tenant-svc/onboarding/stores");
    when(ctx.getMethod()).thenReturn("POST");
    headers.putSingle("X-Tenant-Id", TENANT);
    when(ctx.getHeaderString("X-Tenant-Id")).thenReturn(TENANT);
    bearer(token(Map.of("roles", List.of("CUSTOMER"))));
    filter.filter(ctx);
    org.junit.jupiter.api.Assertions.assertEquals(TENANT, headers.getFirst("X-Tenant-Id"));
    verify(ctx, never()).setProperty(eq(FlowAttributes.TENANT_ID), any());
  }

  @Test
  @DisplayName(
      "A guest on a storefront is attributed to the shop, once the status gate has passed it")
  void aGuestIsAttributedToTheShop() throws IOException {
    when(uri.getPath()).thenReturn("api/v1/product-svc/catalog/products");
    when(ctx.getHeaderString("X-Storefront-Tenant")).thenReturn(TENANT);
    filter.filter(ctx);
    verify(ctx).setProperty(FlowAttributes.TENANT_ID, TENANT);
    verify(ctx, never()).setProperty(eq(FlowAttributes.USER_ID), any());
  }

  @Test
  @DisplayName("A guest naming a suspended shop is refused and attributed to nobody")
  void aSuspendedShopIsNotAttributed() throws IOException {
    when(uri.getPath()).thenReturn("api/v1/product-svc/catalog/products");
    when(ctx.getHeaderString("X-Storefront-Tenant")).thenReturn(TENANT);
    when(gate.isActive(TENANT)).thenReturn(false);
    filter.filter(ctx);
    verify(ctx, never()).setProperty(eq(FlowAttributes.TENANT_ID), any());
  }

  @Test
  @DisplayName("An API key is attributed to its business, with the key as the caller")
  void anApiKeyAttributes() throws IOException {
    String key = "sqk_" + "A".repeat(40);
    String keyId = Ids.newId().toString();
    when(apiKeys.introspect(key))
        .thenReturn(new ApiKeyIntrospector.Active(keyId, TENANT, "MANAGER", List.of(), "ERP"));
    bearer(key);
    filter.filter(ctx);
    verify(ctx).setProperty(FlowAttributes.TENANT_ID, TENANT);
    verify(ctx).setProperty(FlowAttributes.USER_ID, keyId);
  }
}
