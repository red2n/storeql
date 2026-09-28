package com.storeql.gateway.filters;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeql.gateway.GatewayConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

  @Mock GatewayConfig config;
  @Mock ContainerRequestContext requestContext;
  @Mock UriInfo uriInfo;
  @Mock TenantStatusGate tenantStatusGate;

  private static final String KID = "unit-test-key";
  private static final java.security.KeyPair KEYS = rsaKeys();
  private static final com.auth0.jwt.algorithms.Algorithm SIGNER =
      com.auth0.jwt.algorithms.Algorithm.RSA256(
          null, (java.security.interfaces.RSAPrivateKey) KEYS.getPrivate());

  private JwtAuthFilter filter;
  private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();

  private static java.security.KeyPair rsaKeys() {
    try {
      var gen = java.security.KeyPairGenerator.getInstance("RSA");
      gen.initialize(2048);
      return gen.generateKeyPair();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static java.security.interfaces.RSAPublicKey publicKey() {
    return (java.security.interfaces.RSAPublicKey) KEYS.getPublic();
  }

  @BeforeEach
  void setUp() {
    lenient().when(config.jwtIssuer()).thenReturn("storeql");
    lenient().when(tenantStatusGate.isActive(any())).thenReturn(true);
    filter = new JwtAuthFilter();
    filter.config = config;
    filter.tenantStatusGate = tenantStatusGate;
    filter.signingKeys = SigningKeySet.of(java.util.Map.of(KID, publicKey()));
    lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
    lenient().when(requestContext.getHeaders()).thenReturn(headers);
  }

  // ── Payment provider webhooks ──────────────────────────────────────────────
  // These bypass token validation entirely, so the shape they match has to be exact. The provider
  // calls them from its own infrastructure with no JWT and no storefront header; authentication is
  // the provider's signature over the raw body, checked inside payment-svc.

  @Test
  void providerWebhookBypassesTokenValidation() throws IOException {
    when(requestContext.getMethod()).thenReturn("POST");
    when(uriInfo.getPath()).thenReturn("api/payment-svc/payments/webhooks/stripe");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  @Test
  void versionedProviderWebhookAlsoBypasses() throws IOException {
    when(requestContext.getMethod()).thenReturn("POST");
    when(uriInfo.getPath()).thenReturn("api/v1/payment-svc/payments/webhooks/stripe");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  /** A GET on the webhook path is not a delivery, so it gets no exemption. */
  @Test
  void webhookExemptionIsPostOnly() throws IOException {
    when(requestContext.getMethod()).thenReturn("GET");
    when(uriInfo.getPath()).thenReturn("api/payment-svc/payments/webhooks/stripe");

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  // ── The API's own description (22.8) ──────────────────────────────────────
  // What versions exist and when the alias retires is the one thing an integrator reads before
  // holding any credential, so it needs none.

  @Test
  void theVersionsDocumentIsPublic() throws IOException {
    when(requestContext.getMethod()).thenReturn("GET");
    when(uriInfo.getPath()).thenReturn("api/versions");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  @Test
  void theVersionsDocumentIsNotAServiceCalledVersions() throws IOException {
    when(requestContext.getMethod()).thenReturn("GET");
    when(uriInfo.getPath()).thenReturn("api/versions/anything");

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  // ── E-invoice deliveries (07.13, the transport seam) ──────────────────────
  // A network's access point delivers a supplier's e-invoice with no JWT; purchase-svc checks the
  // delivery key. The shape is exact: the network is one segment, and the upload route beside it
  // — a person's, with a token — is not the delivery route.

  @Test
  void eInvoiceDeliveryBypassesTokenValidation() throws IOException {
    when(requestContext.getMethod()).thenReturn("POST");
    when(uriInfo.getPath()).thenReturn("api/purchase-svc/e-invoices/inbound/peppol");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  @Test
  void versionedEInvoiceDeliveryAlsoBypasses() throws IOException {
    when(requestContext.getMethod()).thenReturn("POST");
    when(uriInfo.getPath()).thenReturn("/api/v1/purchase-svc/e-invoices/inbound/fr_pdp/");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  @Test
  void eInvoiceDeliveryExemptionIsPostOnlyAndExact() throws IOException {
    when(requestContext.getMethod()).thenReturn("GET");
    when(uriInfo.getPath()).thenReturn("api/purchase-svc/e-invoices/inbound/peppol");
    filter.filter(requestContext);
    verify(requestContext).abortWith(any());

    // The upload route a person uses, and anything deeper than one network, keep needing a token.
    for (String path :
        new String[] {
          "api/purchase-svc/e-invoices",
          "api/purchase-svc/e-invoices/inbound",
          "api/purchase-svc/e-invoices/inbound/peppol/again",
          "api/purchase-svc/e-invoices/inbound-replay/peppol"
        }) {
      org.mockito.Mockito.reset(requestContext);
      lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
      lenient().when(requestContext.getHeaders()).thenReturn(headers);
      when(requestContext.getMethod()).thenReturn("POST");
      when(uriInfo.getPath()).thenReturn(path);
      filter.filter(requestContext);
      verify(requestContext, org.mockito.Mockito.description(path)).abortWith(any());
    }
  }

  /**
   * The exemption is for exactly one segment after /webhooks/. Anything deeper, or a route that
   * merely starts with the same characters, still needs a token — the same trap the public-suffix
   * test above guards.
   */
  @Test
  void pathsBeyondTheWebhookShapeStillRequireAToken() throws IOException {
    when(requestContext.getMethod()).thenReturn("POST");
    when(uriInfo.getPath()).thenReturn("api/payment-svc/payments/webhooks/stripe/replay");

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  @Test
  void webhookLookalikePathStillRequiresAToken() throws IOException {
    when(requestContext.getMethod()).thenReturn("POST");
    when(uriInfo.getPath()).thenReturn("api/payment-svc/payments/webhooks-replay");

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  /** A bare /webhooks/ with no provider names nothing, so it is not a delivery. */
  @Test
  void webhookWithNoProviderRequiresAToken() throws IOException {
    when(requestContext.getMethod()).thenReturn("POST");
    when(uriInfo.getPath()).thenReturn("api/payment-svc/payments/webhooks/");

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  @Test
  void publicLoginPathBypassesTokenValidation() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/iam-svc/auth/login");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  @Test
  void versionedPublicLoginPathBypassesTokenValidation() throws IOException {
    // /api/v1/... must hit the same public whitelist as the unversioned alias.
    when(uriInfo.getPath()).thenReturn("api/v1/iam-svc/auth/login");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  @Test
  void versionedStorefrontCatalogResolvesTenant() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/v1/product-svc/catalog/products");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("tenant-abc");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("tenant-abc", headers.getFirst("X-Tenant-Id"));
  }

  @Test
  void pathMerelyEmbeddingPublicSuffixStillRequiresToken() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/product-svc/x/iam-svc/auth/login");
    when(requestContext.getHeaderString("Authorization")).thenReturn(null);

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  @Test
  void openApiSpecBypassesTokenValidation() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/order-svc/openapi");
    when(requestContext.getMethod()).thenReturn("GET");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  @Test
  void openApiSpecRequiresGet() throws IOException {
    // Same path, wrong verb — must not be treated as the public spec endpoint.
    when(uriInfo.getPath()).thenReturn("api/order-svc/openapi");
    when(requestContext.getMethod()).thenReturn("POST");
    when(requestContext.getHeaderString("Authorization")).thenReturn(null);

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  @Test
  void pathEmbeddingOpenApiSuffixStillRequiresToken() throws IOException {
    // Trailing-segment match only: a deeper path that happens to end in a different segment
    // after "openapi" must not slip through.
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders/openapi");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("Authorization")).thenReturn(null);

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  @Test
  void protectedPathWithoutTokenIsRejected() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders");
    when(requestContext.getHeaderString("Authorization")).thenReturn(null);

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  @Test
  void garbageTokenIsRejected() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders");
    when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer not-a-jwt");

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
  }

  @Test
  void signedInCustomerGetsTenantFromStorefrontHeaderOnMyOrders() throws IOException {
    // A customer token carries identity but no tenant claim.
    String token = customerToken();
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders/mine");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer " + token);
    when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("tenant-abc");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("tenant-abc", headers.getFirst("X-Tenant-Id"));
    org.junit.jupiter.api.Assertions.assertEquals(
        "01a090ae-611e-700b-bde4-50df0324c37c", headers.getFirst("X-User-Id"));
  }

  private String customerToken() {
    return com.auth0
        .jwt
        .JWT
        .create()
        .withKeyId(KID)
        .withIssuer("storeql")
        .withSubject("01a090ae-611e-700b-bde4-50df0324c37c")
        .withClaim("type", "CUSTOMER")
        .withArrayClaim("roles", new String[] {"CUSTOMER"})
        .sign(SIGNER);
  }

  /**
   * Runs the filter for a signed-in shopper naming a shop through the storefront header.
   *
   * @param headerRead whether the path is one the filter reads the storefront header for; a path
   *     outside the whitelist never asks for it, and a strict stub would object to the unused one
   * @return the tenant the filter derived, or null
   */
  private String tenantDerivedFor(String method, String path, boolean headerRead)
      throws IOException {
    headers.clear();
    when(uriInfo.getPath()).thenReturn(path);
    when(requestContext.getMethod()).thenReturn(method);
    when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer " + customerToken());
    if (headerRead) {
      when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("tenant-abc");
    } else {
      lenient()
          .when(requestContext.getHeaderString("X-Storefront-Tenant"))
          .thenReturn("tenant-abc");
    }
    filter.filter(requestContext);
    return headers.getFirst("X-Tenant-Id");
  }

  /** The privacy notice is read before anyone signs up (13.12): public, one exact shape. */
  @Test
  void thePrivacyNoticeIsPublicAndTheRestOfPrivacyIsNot() throws IOException {
    // A guest reads it from a storefront: the tenant comes from the storefront header.
    when(requestContext.getMethod()).thenReturn("GET");
    when(uriInfo.getPath()).thenReturn("api/customer-svc/customers/privacy/notice");
    when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("tenant-abc");
    filter.filter(requestContext);
    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("tenant-abc", headers.getFirst("X-Tenant-Id"));

    for (String[] c :
        new String[][] {
          {"GET", "api/customer-svc/customers/privacy/settings"},
          {"GET", "api/customer-svc/customers/privacy/notices"},
          {"POST", "api/customer-svc/customers/privacy/notice"},
          {"GET", "api/customer-svc/customers/privacy/notice/extra"},
          {"GET", "api/customer-svc/customers/me/privacy"},
        }) {
      org.mockito.Mockito.reset(requestContext);
      lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
      lenient().when(requestContext.getHeaders()).thenReturn(headers);
      when(requestContext.getMethod()).thenReturn(c[0]);
      when(uriInfo.getPath()).thenReturn(c[1]);
      filter.filter(requestContext);
      verify(requestContext, org.mockito.Mockito.description(c[0] + " " + c[1])).abortWith(any());
    }
  }

  @Test
  void customerTokenReachesItsOwnProfileAndAddressBookFromAStorefront() throws IOException {
    // 12.10. The first live run of the account flow got NO_TENANT on PUT /customers/me: the
    // downstream filter admitted the shape, the gateway's storefront list had never heard of it.
    String[][] cases = {
      {"PUT", "api/customer-svc/customers/me"},
      {"GET", "api/customer-svc/customers/me/addresses"},
      {"POST", "api/customer-svc/customers/me/addresses"},
      {"PUT", "api/customer-svc/customers/me/addresses/01a09509-72ec-72e9-9f08-94a93df26a36"},
      {"DELETE", "api/customer-svc/customers/me/addresses/01a09509-72ec-72e9-9f08-94a93df26a36"},
      {"GET", "api/notification-svc/notifications/devices"},
      {"POST", "api/notification-svc/notifications/devices"},
      {"DELETE", "api/notification-svc/notifications/devices/01a09509-72ec-72e9-9f08-94a93df26a36"},
      // 13.12: the shopper's own privacy — consents withdrawn in one step, requests for rights.
      {"GET", "api/customer-svc/customers/me/privacy"},
      {"PUT", "api/customer-svc/customers/me/privacy/consents"},
      {"DELETE", "api/customer-svc/customers/me/privacy/consents"},
      {"GET", "api/customer-svc/customers/me/privacy/requests"},
      {"POST", "api/customer-svc/customers/me/privacy/requests"},
    };
    for (String[] c : cases) {
      org.junit.jupiter.api.Assertions.assertEquals(
          "tenant-abc", tenantDerivedFor(c[0], c[1], true), c[0] + " " + c[1]);
    }
  }

  @Test
  void customerTokenGetsNoTenantForAddressPathsOutsideTheShape() throws IOException {
    // A literal child, a non-id, or a method the book does not take: no tenant is derived, so the
    // request reaches the service without one and is refused there.
    String[][] cases = {
      {"DELETE", "api/customer-svc/customers/me/addresses/not-an-id"},
      {"POST", "api/customer-svc/customers/me/addresses/01a09509-72ec-72e9-9f08-94a93df26a36"},
      {"GET", "api/customer-svc/customers/me/addresses/01a09509-72ec-72e9-9f08-94a93df26a36/share"},
      {"DELETE", "api/customer-svc/customers/me"},
      {"PUT", "api/customer-svc/customers/01a09509-72ec-72e9-9f08-94a93df26a36/addresses"},
      {"PUT", "api/notification-svc/notifications/devices/01a09509-72ec-72e9-9f08-94a93df26a36"},
      {"POST", "api/notification-svc/notifications/send"},
      // A split checkout is read by id only: not its list, not anything under it, not a write.
      {"GET", "api/order-svc/order-groups"},
      {"GET", "api/order-svc/order-groups/01a09509-72ec-72e9-9f08-94a93df26a36/parts"},
      {"POST", "api/order-svc/order-groups/01a09509-72ec-72e9-9f08-94a93df26a36"},
    };
    for (String[] c : cases) {
      headers.clear();
      when(uriInfo.getPath()).thenReturn(c[1]);
      when(requestContext.getMethod()).thenReturn(c[0]);
      when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer " + customerToken());
      // Never read for these paths, which is the point; lenient so strict stubs do not object.
      lenient()
          .when(requestContext.getHeaderString("X-Storefront-Tenant"))
          .thenReturn("tenant-abc");
      filter.filter(requestContext);
      org.junit.jupiter.api.Assertions.assertNull(
          headers.getFirst("X-Tenant-Id"), c[0] + " " + c[1]);
    }
  }

  @Test
  void customerTokenOpensItsOwnOrderById() throws IOException {
    // A shopper could list their orders through /orders/mine and open none of them: no tenant was
    // derived for the read by id. The id-shaped self-reads now get the storefront tenant too;
    // order-svc's object-level check decides whose order it is.
    for (String path :
        new String[] {
          "api/order-svc/orders/01a09509-72ec-72e9-9f08-94a93df26a36",
          "api/order-svc/orders/01a09509-72ec-72e9-9f08-94a93df26a36/history",
          "api/order-svc/orders/01a09509-72ec-72e9-9f08-94a93df26a36/fiscal-receipt",
          // A split checkout's parts (order orchestration), checked in order-svc like an order.
          "api/order-svc/order-groups/01a09509-72ec-72e9-9f08-94a93df26a36"
        }) {
      org.junit.jupiter.api.Assertions.assertEquals(
          "tenant-abc", tenantDerivedFor("GET", path, true), path);
    }
  }

  @Test
  void customerTokenReachesItsOwnRecallNoticesAndNothingElseUnderThem() throws IOException {
    // 05.10: the shopper's notices and their choice of remedy get the storefront tenant; a recall's
    // list, its progress and settling a notice do not — those are staff work through the normal
    // door — and neither does a literal where the id goes.
    String[][] allowed = {
      {"GET", "api/order-svc/orders/recall-notices/mine"},
      {"POST", "api/order-svc/orders/recall-notices/01a09509-72ec-72e9-9f08-94a93df26a36/remedy"},
    };
    for (String[] c : allowed) {
      org.junit.jupiter.api.Assertions.assertEquals(
          "tenant-abc", tenantDerivedFor(c[0], c[1], true), c[0] + " " + c[1]);
    }
    String[][] refused = {
      {"GET", "api/order-svc/orders/recall-notices"},
      {"GET", "api/order-svc/orders/recall-notices/progress"},
      {"POST", "api/order-svc/orders/recall-notices/01a09509-72ec-72e9-9f08-94a93df26a36/resolve"},
      {"POST", "api/order-svc/orders/recall-notices/mine/remedy"},
      {"GET", "api/order-svc/orders/recall-notices/mine/all"},
      {"POST", "api/order-svc/orders/recall-notices/mine"},
    };
    for (String[] c : refused) {
      org.junit.jupiter.api.Assertions.assertNull(
          tenantDerivedFor(c[0], c[1], false), c[0] + " " + c[1]);
    }
  }

  @Test
  void customerTokenGetsNoTenantForALiteralOrderChild() throws IOException {
    // /orders/export names a subject and is staff-only; a literal must never pass as an order id
    // here any more than it does in the downstream read allowlist.
    for (String path : new String[] {"api/order-svc/orders/export", "api/order-svc/orders/abc"}) {
      headers.clear();
      when(uriInfo.getPath()).thenReturn(path);
      when(requestContext.getMethod()).thenReturn("GET");
      when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer " + customerToken());
      // lenient: the point is that the filter never asks for the storefront header on these paths.
      org.mockito.Mockito.lenient()
          .when(requestContext.getHeaderString("X-Storefront-Tenant"))
          .thenReturn("tenant-abc");
      filter.filter(requestContext);
      org.junit.jupiter.api.Assertions.assertNull(headers.getFirst("X-Tenant-Id"), path);
    }
  }

  @Test
  void customerTokenCannotNameTenantForAdminOrderList() throws IOException {
    // The storefront-tenant fallback must be scoped to whitelisted customer paths — a customer
    // token hitting the admin order list must NOT get a tenant stamped from the storefront header,
    // or it could read another business's full order book.
    String token =
        com.auth0
            .jwt
            .JWT
            .create()
            .withKeyId(KID)
            .withIssuer("storeql")
            .withSubject("01a090ae-611e-700b-bde4-50df0324c37c")
            .withArrayClaim("roles", new String[] {"CUSTOMER"})
            .sign(SIGNER);
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer " + token);
    lenient().when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("tenant-abc");

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Tenant-Id"));
  }

  @Test
  void guestCanReadActivePromotionsWithStorefrontTenant() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/pricing-svc/promotions");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("tenant-abc");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("tenant-abc", headers.getFirst("X-Tenant-Id"));
  }

  @Test
  void signedInCustomerCanStillBrowseCatalogAfterCheckout() throws IOException {
    // Regression: a signed-in customer's Dio client attaches its Bearer token to every request,
    // including plain catalog browsing. That irrelevant token must not force JWT verification and
    // reject the request for lacking a tenant claim — these paths stay public regardless of caller.
    String token = customerToken();
    when(uriInfo.getPath()).thenReturn("api/product-svc/catalog/products");
    when(requestContext.getMethod()).thenReturn("GET");
    // Present but must never be consulted: this path stays public regardless of caller identity.
    lenient().when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer " + token);
    when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("tenant-abc");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("tenant-abc", headers.getFirst("X-Tenant-Id"));
  }

  @Test
  void staffBearerTokenResolvesTenantOnStorefrontPublicPathWithoutHeader() throws IOException {
    // Regression: an authenticated staff caller (e.g. the admin console checking inventory
    // availability, or POS clock-in listing stores via the same cashier-safe endpoint) has no
    // storefront context and sends no X-Storefront-Tenant — it must fall through to normal Bearer
    // verification instead of being silently left tenant-less (previously surfaced downstream as
    // a blanket 401 NO_TENANT on every call).
    String token = staffToken(new String[] {"OWNER"}, null);
    when(uriInfo.getPath()).thenReturn("api/inventory-svc/inventory/availability");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer " + token);
    when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn(null);

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("tenant-xyz", headers.getFirst("X-Tenant-Id"));
  }

  @Test
  void suspendedTenantStorefrontRequestIsBlocked() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/product-svc/catalog/products");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("dead-tenant");
    when(tenantStatusGate.isActive("dead-tenant")).thenReturn(false);

    filter.filter(requestContext);

    verify(requestContext).abortWith(any());
    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Tenant-Id"));
  }

  @Test
  void clientSuppliedIdentityHeadersAreStrippedEvenOnPublicPaths() throws IOException {
    headers.putSingle("X-Tenant-Id", "spoofed");
    headers.putSingle("X-User-Id", "spoofed");
    headers.putSingle("X-Roles", "PLATFORM_ADMIN");
    headers.putSingle("X-Permissions", "sales.void,staff.manage");
    when(uriInfo.getPath()).thenReturn("api/iam-svc/auth/login");

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Tenant-Id"));
    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-User-Id"));
    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Roles"));
    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Permissions"));
  }

  // ── the permission claim (20.10) ─────────────────────────────────────────────

  private String staffToken(String[] roles, String[] perms) {
    var b =
        com.auth0
            .jwt
            .JWT
            .create()
            .withKeyId(KID)
            .withIssuer("storeql")
            .withSubject("01a090ae-611e-700f-b645-a14095230b77")
            .withClaim("type", "STAFF")
            .withClaim("tenant", "tenant-xyz")
            .withArrayClaim("roles", roles);
    if (perms != null) b = b.withArrayClaim("perms", perms);
    return b.sign(SIGNER);
  }

  @Test
  void permissionClaimIsStampedAsAHeader() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("Authorization"))
        .thenReturn(
            "Bearer "
                + staffToken(
                    new String[] {"MANAGER"}, new String[] {"sales.void", "finance.journal"}));

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals(
        "sales.void,finance.journal", headers.getFirst("X-Permissions"));
  }

  @Test
  void aClaimNamingNothingIsStampedAsADashNotDropped() throws IOException {
    // A role narrowed to nothing must reach the service as "nothing", not as "no claim": the
    // latter would make the service fall back to the tier's defaults and undo the narrowing.
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("Authorization"))
        .thenReturn("Bearer " + staffToken(new String[] {"CASHIER"}, new String[] {}));

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertEquals("-", headers.getFirst("X-Permissions"));
  }

  @Test
  void aTokenWithoutTheClaimStampsNoHeaderAndAForgedOneIsReplaced() throws IOException {
    headers.putSingle("X-Permissions", "sales.void");
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("Authorization"))
        .thenReturn("Bearer " + staffToken(new String[] {"CASHIER"}, null));

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Permissions"));
  }

  // ── Second factors (20.12) ─────────────────────────────────────────────────

  private static String enrolmentToken(String scope) {
    return com.auth0
        .jwt
        .JWT
        .create()
        .withKeyId(KID)
        .withIssuer("storeql")
        .withSubject("01a090ae-611e-700f-b645-a14095230b77")
        .withClaim("type", "STAFF")
        .withArrayClaim("roles", new String[] {})
        .withClaim("scope", scope)
        .sign(SIGNER);
  }

  @Test
  void anEnrolmentTokenReachesTheSecondFactorRoutesAndSaysWhatItIs() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/iam-svc/auth/mfa/totp/confirm");
    when(requestContext.getMethod()).thenReturn("POST");
    when(requestContext.getHeaderString("Authorization"))
        .thenReturn("Bearer " + enrolmentToken("mfa-enrol"));

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("mfa-enrol", headers.getFirst("X-Auth-Scope"));
  }

  @Test
  void anEnrolmentTokenReachesNothingElse() throws IOException {
    for (String path :
        new String[] {
          "api/order-svc/orders",
          "api/iam-svc/auth/me",
          "api/iam-svc/auth/mfa-policy",
          "api/iam-svc/auth/admin/mfa-policy",
          "api/customer-svc/customers/me"
        }) {
      org.mockito.Mockito.reset(requestContext);
      lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
      lenient().when(requestContext.getHeaders()).thenReturn(headers);
      when(uriInfo.getPath()).thenReturn(path);
      lenient().when(requestContext.getMethod()).thenReturn("GET");
      when(requestContext.getHeaderString("Authorization"))
          .thenReturn("Bearer " + enrolmentToken("mfa-enrol"));

      filter.filter(requestContext);

      org.junit.jupiter.api.Assertions.assertEquals(403, abortedStatus(), path);
    }
  }

  @Test
  void aScopeTheGatewayDoesNotKnowReachesNothingNotEvenTheSecondFactorRoutes() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/iam-svc/auth/mfa/totp");
    lenient().when(requestContext.getMethod()).thenReturn("POST");
    when(requestContext.getHeaderString("Authorization"))
        .thenReturn("Bearer " + enrolmentToken("everything"));

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertEquals(403, abortedStatus());
  }

  @Test
  void aClientCannotClaimAScopeForItself() throws IOException {
    headers.putSingle("X-Auth-Scope", "mfa-enrol");
    protectedRead(staffToken(new String[] {"OWNER"}, null));

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Auth-Scope"));
  }

  @Test
  void aSignInAnswersItsSecondFactorWithoutAToken() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/iam-svc/auth/mfa/login");
    lenient().when(requestContext.getMethod()).thenReturn("POST");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  // ── single sign-on (20.x) ─────────────────────────────────────────────────

  @Test
  void aSignInThroughAProviderStartsReturnsAndRedeemsWithoutAToken() throws IOException {
    for (String path :
        new String[] {
          "api/iam-svc/auth/sso/start",
          "api/iam-svc/auth/sso/callback",
          "api/iam-svc/auth/sso/token"
        }) {
      org.mockito.Mockito.reset(requestContext);
      lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
      lenient().when(requestContext.getHeaders()).thenReturn(headers);
      when(uriInfo.getPath()).thenReturn(path);

      filter.filter(requestContext);

      verify(requestContext, never()).abortWith(any());
    }
  }

  // ── password reset ────────────────────────────────────────────────────────

  @Test
  void aForgottenPasswordIsAskedForAndResetWithoutAToken() throws IOException {
    String[][] routes = {
      {"api/iam-svc/auth/password/forgot", "POST"},
      {"api/iam-svc/auth/password/reset", "POST"},
      {"api/iam-svc/auth/password-policy", "GET"},
      {"api/v1/iam-svc/auth/password/forgot", "POST"},
      {"api/v1/iam-svc/auth/password-policy", "GET"}
    };
    for (String[] route : routes) {
      org.mockito.Mockito.reset(requestContext);
      lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
      lenient().when(requestContext.getHeaders()).thenReturn(headers);
      when(uriInfo.getPath()).thenReturn(route[0]);
      lenient().when(requestContext.getMethod()).thenReturn(route[1]);

      filter.filter(requestContext);

      verify(requestContext, never()).abortWith(any());
    }
  }

  @Test
  void nothingBesideThePasswordResetPathsIsPublic() throws IOException {
    for (String path :
        new String[] {
          "api/iam-svc/auth/password",
          "api/iam-svc/auth/password/forgot/x",
          "api/iam-svc/auth/password/resets",
          "api/iam-svc/auth/password-policy/x",
          "api/tenant-svc/auth/password/forgot"
        }) {
      org.mockito.Mockito.reset(requestContext);
      lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
      lenient().when(requestContext.getHeaders()).thenReturn(headers);
      when(uriInfo.getPath()).thenReturn(path);
      lenient().when(requestContext.getMethod()).thenReturn("POST");

      filter.filter(requestContext);

      org.junit.jupiter.api.Assertions.assertEquals(401, abortedStatus(), path);
    }
  }

  // ── delivery and collection slots ─────────────────────────────────────────

  @Test
  void aGuestReadsAStoresFulfilmentSlotsForTheStorefrontsTenant() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/order-svc/storefront/fulfilment-slots");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("tenant-abc");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("tenant-abc", headers.getFirst("X-Tenant-Id"));
  }

  @Test
  void theSlotReadIsTheOnlyPublicOrderRouteAndOnlyForReading() throws IOException {
    String[][] routes = {
      {"api/order-svc/storefront/fulfilment-slots", "POST"},
      {"api/order-svc/storefront/fulfilment-slots/x", "GET"},
      {"api/order-svc/storefront", "GET"},
      {"api/order-svc/admin/fulfilment-windows", "GET"}
    };
    for (String[] route : routes) {
      org.mockito.Mockito.reset(requestContext);
      lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
      lenient().when(requestContext.getHeaders()).thenReturn(headers);
      when(uriInfo.getPath()).thenReturn(route[0]);
      lenient().when(requestContext.getMethod()).thenReturn(route[1]);
      lenient().when(requestContext.getHeaderString("X-Storefront-Tenant")).thenReturn("t");

      filter.filter(requestContext);

      org.junit.jupiter.api.Assertions.assertEquals(401, abortedStatus(), route[0]);
    }
  }

  @Test
  void theBusinesssProviderSettingsAndAnythingBesideTheSignInPathsNeedAToken() throws IOException {
    for (String path :
        new String[] {
          "api/iam-svc/auth/admin/sso",
          "api/iam-svc/auth/admin/sso/readiness",
          "api/iam-svc/auth/sso",
          "api/iam-svc/auth/sso/start/x",
          "api/iam-svc/auth/sso/callbacks"
        }) {
      org.mockito.Mockito.reset(requestContext);
      lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
      lenient().when(requestContext.getHeaders()).thenReturn(headers);
      when(uriInfo.getPath()).thenReturn(path);
      lenient().when(requestContext.getMethod()).thenReturn("GET");

      filter.filter(requestContext);

      org.junit.jupiter.api.Assertions.assertEquals(401, abortedStatus(), path);
    }
  }

  @Test
  void howTheSessionWasAuthenticatedIsStampedFromTheTokenAndNeverFromTheClient()
      throws IOException {
    headers.putSingle("X-Auth-Methods", "sso,mfa");
    String token =
        com.auth0
            .jwt
            .JWT
            .create()
            .withKeyId(KID)
            .withIssuer("storeql")
            .withSubject("01a090ae-611e-700f-b645-a14095230b77")
            .withClaim("tenant", "tenant-xyz")
            .withArrayClaim("roles", new String[] {"CASHIER"})
            .withArrayClaim("amr", new String[] {"pwd", "otp"})
            .sign(SIGNER);
    protectedRead(token);

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("pwd,otp", headers.getFirst("X-Auth-Methods"));
  }

  @Test
  void aTokenThatSaysNothingOfHowStampsNothingAndAForgedHeaderGoes() throws IOException {
    headers.putSingle("X-Auth-Methods", "sso,mfa");
    protectedRead(staffToken(new String[] {"OWNER"}, null));

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Auth-Methods"));
  }

  // ── Token signing (20.15; RFC 8725) ────────────────────────────────────────

  /** An owner's token for tenant-xyz under this key id (null for none), signed by this signer. */
  private static String ownerToken(String kid, com.auth0.jwt.algorithms.Algorithm signer) {
    var b =
        com.auth0
            .jwt
            .JWT
            .create()
            .withIssuer("storeql")
            .withSubject("01a090ae-611e-700f-b645-a14095230b77")
            .withClaim("tenant", "tenant-xyz")
            .withArrayClaim("roles", new String[] {"OWNER"});
    return (kid == null ? b : b.withKeyId(kid)).sign(signer);
  }

  private static com.auth0.jwt.algorithms.Algorithm strangersKey() {
    return com.auth0.jwt.algorithms.Algorithm.RSA256(
        null, (java.security.interfaces.RSAPrivateKey) rsaKeys().getPrivate());
  }

  private void protectedRead(String token) {
    when(uriInfo.getPath()).thenReturn("api/order-svc/orders");
    when(requestContext.getMethod()).thenReturn("GET");
    when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer " + token);
  }

  private int abortedStatus() {
    var response = org.mockito.ArgumentCaptor.forClass(jakarta.ws.rs.core.Response.class);
    verify(requestContext).abortWith(response.capture());
    return response.getValue().getStatus();
  }

  @Test
  void aTokenSignedByAPublishedKeyPasses() throws IOException {
    protectedRead(staffToken(new String[] {"OWNER"}, null));

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("tenant-xyz", headers.getFirst("X-Tenant-Id"));
  }

  // ── 21.12: the pay link, public because a suspended business cannot sign in ──

  @Test
  void thePayLinkNeedsNoTokenAndNothingElseUnderItIsPublic() throws IOException {
    when(requestContext.getMethod()).thenReturn("POST");
    when(uriInfo.getPath()).thenReturn("api/tenant-svc/billing/pay/AbC123-token_value");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }

  @Test
  void anythingElseUnderThePayPathStillNeedsAToken() throws IOException {
    // Matched by shape: the prefix plus exactly one segment. A prefix match would make everything
    // that appears under /billing/pay/ later public by accident, and this is the one path the
    // gateway lets through with no identity.
    for (String path :
        new String[] {
          "api/tenant-svc/billing/pay",
          "api/tenant-svc/billing/pay/",
          "api/tenant-svc/billing/pay/tok/extra",
          "api/tenant-svc/billing/payx/tok",
          "api/tenant-svc/billing/invoices/tok/payments"
        }) {
      org.mockito.Mockito.reset(requestContext);
      when(requestContext.getHeaders()).thenReturn(new MultivaluedHashMap<>());
      when(requestContext.getUriInfo()).thenReturn(uriInfo);
      when(requestContext.getMethod()).thenReturn("POST");
      when(uriInfo.getPath()).thenReturn(path);

      filter.filter(requestContext);

      verify(requestContext, org.mockito.Mockito.atLeastOnce()).abortWith(any());
    }
  }

  // ── SJ-D66: two clocks, and which way leeway may bend ──────────────────────
  // iam mints a token and the gateway judges it milliseconds later, on a different pod's clock.
  // With no leeway a token whose iat rounds to the next second is "from the future" and refused
  // until the second turns over — which cost a whole k6 run in 401s that looked random. Leeway is
  // allowed where it only ever helps a legitimate caller, and refused where it would extend
  // access past the moment it was meant to end.

  /** A token whose issued-at and expiry are placed relative to now, in seconds. */
  private static String timedToken(long iatOffsetSeconds, long expOffsetSeconds) {
    java.time.Instant now = java.time.Instant.now();
    return com.auth0
        .jwt
        .JWT
        .create()
        .withKeyId(KID)
        .withIssuer("storeql")
        .withSubject("01a090ae-611e-700f-b645-a14095230b77")
        .withClaim("tenant", "tenant-xyz")
        .withArrayClaim("roles", new String[] {"OWNER"})
        .withIssuedAt(now.plusSeconds(iatOffsetSeconds))
        .withExpiresAt(now.plusSeconds(expOffsetSeconds))
        .sign(SIGNER);
  }

  @Test
  void aTokenMintedAMomentAheadOfThisClockIsAccepted() throws IOException {
    protectedRead(timedToken(2, 900));

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
    org.junit.jupiter.api.Assertions.assertEquals("tenant-xyz", headers.getFirst("X-Tenant-Id"));
  }

  @Test
  void aTokenFromFarEnoughAheadIsStillRefused() throws IOException {
    // Leeway is for skew, not for a token minted by something whose clock is simply wrong.
    protectedRead(timedToken(3600, 7200));

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertEquals(401, abortedStatus());
    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Roles"));
  }

  @Test
  void anExpiredTokenIsRefusedNoMatterWhatTheClocksSay() throws IOException {
    // The direction leeway must never bend. A token whose life is over is over; tolerating skew on
    // the expiry would hand every token an extra minute of access it was not granted.
    protectedRead(timedToken(-900, -5));

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertEquals(401, abortedStatus());
    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Roles"));
  }

  @Test
  void anHmacTokenKeyedWithThePublicKeyIsRefused() throws IOException {
    // The algorithm-confusion attack: the public key is public, so anyone can HMAC with it. A
    // verifier that let the token choose its algorithm would accept this.
    String forged =
        ownerToken(KID, com.auth0.jwt.algorithms.Algorithm.HMAC256(publicKey().getEncoded()));
    protectedRead(forged);

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertEquals(401, abortedStatus());
    org.junit.jupiter.api.Assertions.assertFalse(headers.containsKey("X-Roles"));
  }

  @Test
  void anUnsignedTokenIsRefused() throws IOException {
    var url = java.util.Base64.getUrlEncoder().withoutPadding();
    String header =
        url.encodeToString(
            "{\"alg\":\"none\",\"typ\":\"JWT\",\"kid\":\"unit-test-key\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String body =
        url.encodeToString(
            "{\"iss\":\"storeql\",\"sub\":\"x\",\"tenant\":\"tenant-xyz\",\"roles\":[\"OWNER\"]}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    protectedRead(header + "." + body + ".");

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertEquals(401, abortedStatus());
  }

  @Test
  void aTokenNamingAKeyNobodyPublishedIsRefused() throws IOException {
    String token = ownerToken("a-key-of-my-own", strangersKey());
    protectedRead(token);

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertEquals(401, abortedStatus());
  }

  @Test
  void aStrangersKeyUnderAPublishedKeyIdIsRefused() throws IOException {
    String token = ownerToken(KID, strangersKey());
    protectedRead(token);

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertEquals(401, abortedStatus());
  }

  @Test
  void aTokenWithoutAKeyIdIsRefused() throws IOException {
    String token = ownerToken(null, SIGNER);
    protectedRead(token);

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertEquals(401, abortedStatus());
  }

  @Test
  void aTamperedPayloadIsRefused() throws IOException {
    String[] parts = staffToken(new String[] {"CASHIER"}, null).split("\\.");
    var url = java.util.Base64.getUrlEncoder().withoutPadding();
    String raised =
        new String(
                java.util.Base64.getUrlDecoder().decode(parts[1]),
                java.nio.charset.StandardCharsets.UTF_8)
            .replace("CASHIER", "OWNER");
    protectedRead(
        parts[0]
            + "."
            + url.encodeToString(raised.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + "."
            + parts[2]);

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertEquals(401, abortedStatus());
  }

  @Test
  void beforeAnyKeySetIsReadTheAnswerIsUnavailableNotUnauthorised() throws IOException {
    // iam-svc is not up yet: a 401 would sign every browser out for the platform's own start-up.
    filter.signingKeys = new SigningKeySet();
    filter.signingKeys.iamUrl = java.util.Optional.of("http://localhost:1");
    filter.signingKeys.webClient = io.helidon.webclient.api.WebClient.builder().build();
    protectedRead(staffToken(new String[] {"OWNER"}, null));

    filter.filter(requestContext);

    org.junit.jupiter.api.Assertions.assertEquals(503, abortedStatus());
  }

  @Test
  void theKeySetItselfIsReadWithoutAToken() throws IOException {
    when(uriInfo.getPath()).thenReturn("api/iam-svc/auth/.well-known/jwks.json");
    lenient().when(requestContext.getMethod()).thenReturn("GET");

    filter.filter(requestContext);

    verify(requestContext, never()).abortWith(any());
  }
}
