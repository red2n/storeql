package com.storeql.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.core.Response;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdminAuthorizationFilterTest {

  private final AdminAuthorizationFilter filter = new AdminAuthorizationFilter();
  private final TenantContext ctx = new TenantContext();

  AdminAuthorizationFilterTest() {
    filter.ctx = ctx;
  }

  @Test
  void exactCartPathIsOpenForGuests() throws Exception {
    // No staff role at all — a guest/customer managing their own cart.
    assertNotAborted(invoke("POST", "/cart/items"));
  }

  @Test
  void cartSubPathIsOpenForGuests() throws Exception {
    assertNotAborted(invoke("DELETE", "/cart/items/123"));
  }

  @Test
  void lookalikeCartPathRequiresStaffRole() throws Exception {
    // Regression guard: a prefix match on "/cart" would have let an unrelated future
    // "/cart-something" route silently inherit the open-mutation carve-out meant only for the
    // shopping cart. It must fall back to the default-deny staff-role requirement instead.
    assertAborted(invoke("POST", "/cart-something"), 403);
  }

  @Test
  void lookalikeCartPathPassesWithStaffRole() throws Exception {
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertNotAborted(invoke("POST", "/cart-something"));
  }

  // ── 12.10: the shopper's own profile and address book ────────────────────────

  @Test
  void ownProfileAndAddressBookAreOpenToASignedInShopper() throws Exception {
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertNotAborted(invoke("PUT", "/customers/me"));
    // The shopper's own loyalty: the leaf, read only.
    assertNotAborted(invoke("GET", "/customers/me/loyalty"));
    assertAborted(invoke("POST", "/customers/me/loyalty"), 403);
    assertAborted(invoke("GET", "/customers/me/loyalty/ledger"), 403);
    assertNotAborted(invoke("GET", "/customers/me/addresses"));
    assertNotAborted(invoke("POST", "/customers/me/addresses"));
    assertNotAborted(invoke("PUT", "/customers/me/addresses/01a090ae-611e-7011-ae7d-1bd68c966ff6"));
    assertNotAborted(
        invoke("DELETE", "/customers/me/addresses/01a090ae-611e-7011-ae7d-1bd68c966ff6"));
  }

  @Test
  void addressBookIsMatchedByShapeNotPrefix() throws Exception {
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    // A literal or a deeper child under the book is not the book.
    assertAborted(invoke("GET", "/customers/me/addresses/all"), 403);
    assertAborted(
        invoke("POST", "/customers/me/addresses/01a090ae-611e-7011-ae7d-1bd68c966ff6/share"), 403);
    assertAborted(invoke("GET", "/customers/me/addressesX"), 403);
    // Somebody else's book, addressed by id, is still staff-only.
    assertAborted(invoke("GET", "/customers/01a090ae-611e-7011-ae7d-1bd68c966ff6/addresses"), 403);
    assertAborted(invoke("POST", "/customers/01a090ae-611e-7011-ae7d-1bd68c966ff6/addresses"), 403);
  }

  // ── 13.7: the caller's own push devices ──────────────────────────────────────

  @Test
  void ownPushDevicesAreOpenToAnySignedInCaller() throws Exception {
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertNotAborted(invoke("GET", "/notifications/devices"));
    assertNotAborted(invoke("POST", "/notifications/devices"));
    assertNotAborted(
        invoke("DELETE", "/notifications/devices/01a090ae-611e-7011-ae7d-1bd68c966ff6"));
    // A literal child is not a device, and the send route stays staff-only.
    assertAborted(invoke("DELETE", "/notifications/devices/all"), 403);
    assertAborted(invoke("POST", "/notifications/send"), 403);
  }

  // ── SJ-D11: reads default-deny, like mutations always have ──────────────────

  /**
   * The gap that prompted the read tier, sampled across all eight services that had one. The filter
   * now gates 38 non-{@code /admin/} GETs; they were not equally exposed beforehand, and the
   * difference is worth keeping straight:
   *
   * <ul>
   *   <li><b>24 were open to any authenticated caller in the tenant</b>, a signed-in storefront
   *       CUSTOMER included — every path in this test except the two noted below. The service layer
   *       took {@code TenantContext} only to read {@code tenantId} off it, so tenant isolation held
   *       and nothing else did.
   *   <li><b>8 already had object-level authorization</b> ({@code /customers/{id}} and its
   *       sub-resources, {@code /payments/{id}}, {@code /payments/by-order/…}). A signed-in
   *       customer reading someone else's record already got a 404 — but a caller with no principal
   *       at all fell through their "no userId, no roles means a trusted service-to-service lookup"
   *       branch and was served. That branch is now unreachable from outside the mesh, which is why
   *       {@code CustomerClient} and {@code OrderClient} stamp a role on their internal reads.
   *   <li><b>6 already required a role of their own</b> — the two pricing reads SJ-D10 closed,
   *       {@code /pos/parked-sales}, and {@code /platform/tenants}. For those this tier is only
   *       belt-and-braces.
   * </ul>
   */
  @Test
  void businessReadsAreDeniedWithoutAStaffRole() throws Exception {
    for (String path :
        new String[] {
          // Open: the customer list and the email/phone lookup behind it.
          "/customers",
          "/customers/lookup",
          // Object-level guarded, but served to a caller with no principal at all.
          "/customers/abc/loyalty",
          "/payments/abc",
          // Open: the tenant-wide order book, and the till's gift card and layaway balances.
          "/orders",
          "/gift-cards/GC-1234",
          "/layaways/abc",
          // Open: who is signed in at which till.
          "/auth/pos/sessions",
          // Open: stock held for other people's in-flight checkouts.
          "/inventory/reservations",
          // Open: the tenant's own commercial position — what it charges and what it pays.
          "/price-lists",
          "/vat-rates",
          "/suppliers",
          "/purchase-orders",
          "/goods-receipts",
          "/nominal-ledger",
          // Role-guarded already; this tier is belt-and-braces.
          "/vat-return"
        }) {
      assertAborted(invoke("GET", path), 403);
    }
  }

  @Test
  void theSameReadsPassForStaff() throws Exception {
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertNotAborted(invoke("GET", "/customers"));
    assertNotAborted(invoke("GET", "/orders"));
    assertNotAborted(invoke("GET", "/suppliers"));
  }

  /** The laws a business trades under are read by the staff who obey them, never by a shopper. */
  @Test
  void legalObligationsAreReadByStaffNotShoppers() throws Exception {
    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      ctx.set(null, null, Set.of(role), null, null);
      assertNotAborted(invoke("GET", "/admin/tenant/obligations"));
      // Reading is all staff may do there; nothing below management writes under /admin/tenant.
      assertAborted(invoke("POST", "/admin/tenant/obligations"), 403);
    }
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/admin/tenant/obligations"), 403);
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    // Only the exact path: nothing beneath or beside it is widened.
    assertAborted(invoke("GET", "/admin/tenant/obligations/extra"), 403);
  }

  /** A CUSTOMER is not staff — that is the whole point, since storefront tokens carry it. */
  @Test
  void aCustomerRoleIsNotStaff() throws Exception {
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/customers"), 403);
    assertAborted(invoke("GET", "/orders"), 403);
  }

  /** Everything the storefront actually calls must still work with no role whatsoever. */
  @Test
  void theStorefrontReadSurfaceStaysOpen() throws Exception {
    for (String path :
        new String[] {
          "/catalog/categories",
          "/catalog/products",
          "/catalog/products/abc",
          "/catalog/products/abc/variants",
          "/catalog/products/abc/image",
          "/storefront/config",
          "/storefront/stores",
          "/storefront/active",
          "/inventory/availability",
          "/orders/mine",
          // An id-shaped segment: every order id is a UUID, and the self-read shape now insists
          // on one so that a literal child such as /orders/export cannot pass as an order.
          "/orders/01a09509-72ec-72e9-9f08-94a93df26a36",
          "/orders/01a09509-72ec-72e9-9f08-94a93df26a36/history",
          "/orders/01a09509-72ec-72e9-9f08-94a93df26a36/returns",
          "/orders/01a09509-72ec-72e9-9f08-94a93df26a36/fiscal-receipt",
          // A split checkout (order orchestration), with the order's own object-level check.
          "/order-groups/01a09509-72ec-72e9-9f08-94a93df26a36",
          "/promotions",
          "/auth/me",
          "/cart",
          "/cart/items",
          "/onboarding/status",
          "/fulfilment/resolve"
        }) {
      assertNotAborted(invoke("GET", path));
    }
  }

  /**
   * The same lookalike trap the cart carve-out has: a bare prefix match would hand an unrelated
   * future route the storefront's open-read exemption.
   */
  @Test
  void lookalikePublicPathsDoNotInheritTheExemption() throws Exception {
    assertAborted(invoke("GET", "/catalog-exports"), 403);
    assertAborted(invoke("GET", "/storefront-admin"), 403);
    // Anything new under /orders/ that is not one of the five object-level-authorized shapes
    // stays denied, so a future sub-resource cannot inherit the exemption by accident.
    assertAborted(invoke("GET", "/orders/abc/audit-trail"), 403);
    assertAborted(invoke("GET", "/orders/abc/history/all"), 403);
    // Only the id-shaped split checkout; its list and anything under one stay staff work.
    assertAborted(invoke("GET", "/order-groups"), 403);
    assertAborted(invoke("GET", "/order-groups/export"), 403);
    assertAborted(invoke("GET", "/order-groups/01a09509-72ec-72e9-9f08-94a93df26a36/parts"), 403);
  }

  /**
   * A readiness probe that starts returning 403 takes every replica out of rotation. Helidon
   * usually serves these outside JAX-RS, but the filter must not be the thing that finds out.
   */
  @Test
  void probesAndMetricsAreNeverDenied() throws Exception {
    assertNotAborted(invoke("GET", "/health"));
    assertNotAborted(invoke("GET", "/health/ready"));
    assertNotAborted(invoke("GET", "/metrics"));
    assertNotAborted(invoke("GET", "/openapi"));
  }

  /**
   * RFC 9116's security.txt is read by someone with no account. Exactly that document: a sibling
   * under /.well-known, a path beneath it, or a write to it stays where the defaults put it.
   */
  @Test
  void theVulnerabilityDisclosureFileIsPublicAndNothingBesideIt() throws Exception {
    assertNotAborted(invoke("GET", "/.well-known/security.txt"));
    assertNotAborted(invoke("HEAD", "/.well-known/security.txt"));
    assertAborted(invoke("GET", "/.well-known/security.txt/extra"), 403);
    assertAborted(invoke("GET", "/.well-known/openid-configuration"), 403);
    assertAborted(invoke("GET", "/.well-known/security.txt.bak"), 403);
    assertAborted(invoke("POST", "/.well-known/security.txt"), 403);
  }

  /**
   * At the gateway the request path is the proxy route, and since 22.8 the canonical form carries a
   * version segment: {@code /api/v1/{service}/…} must strip to the same service-local path as the
   * alias, or every public read on the versioned form — the price list, a service's OpenAPI
   * description — is refused to the person with no account it exists for. The versions document
   * itself is public; a private read stays private on either form.
   */
  @Test
  void theVersionedFormIsAsOpenAsTheAlias() throws Exception {
    assertNotAborted(invoke("GET", "/api/tenant-svc/plans"));
    assertNotAborted(invoke("GET", "/api/v1/tenant-svc/plans"));
    assertNotAborted(invoke("GET", "/api/v1/iam-svc/openapi"));
    assertNotAborted(invoke("GET", "/api/v12/customer-svc/openapi"));
    assertNotAborted(invoke("GET", "/api/versions"));
    assertAborted(invoke("GET", "/api/v1/order-svc/admin/orders"), 403);
    assertAborted(invoke("GET", "/api/v1/tenant-svc/admin/tenant"), 403);
    assertAborted(invoke("GET", "/api/versions/extra"), 403);
  }

  /** CORS preflight carries no credentials by design; denying it breaks every browser client. */
  @Test
  void corsPreflightIsNotDenied() throws Exception {
    assertNotAborted(invoke("OPTIONS", "/customers"));
  }

  // ── Payment intents (PSP integration) ──────────────────────────────────────

  /**
   * A shopper opens an intent at the same point in checkout that POST /payments/online already
   * serves, and polls it after being sent away for SCA. Both are reachable with no staff role;
   * payment-svc verifies the order against order-svc rather than trusting the caller.
   */
  @Test
  void aShopperCanOpenAndPollTheirOwnPaymentIntent() throws Exception {
    assertNotAborted(invoke("POST", "/payments/intents"));
    assertNotAborted(invoke("GET", "/payments/intents/abc"));
  }

  /**
   * The provider has to reach this with no JWT and no tenant. It is authenticated by the signature
   * over the raw body instead — see PaymentProvider.verifyWebhook.
   */
  @Test
  void providerWebhooksAreReachableWithoutAnyRole() throws Exception {
    assertNotAborted(invoke("POST", "/payments/webhooks/stripe"));
    assertNotAborted(invoke("POST", "/payments/webhooks/razorpay"));
  }

  /**
   * The shopper's own privacy (13.12) is theirs without a staff role, as /customers/me is; the
   * notice is public; the business's settings, notices, queue and a customer's record are staff.
   */
  @Test
  void theShoppersOwnPrivacyIsOpenAndTheBusinessesSideIsStaff() throws Exception {
    assertNotAborted(invoke("GET", "/customers/privacy/notice"));
    assertNotAborted(invoke("GET", "/customers/me/privacy"));
    assertNotAborted(invoke("PUT", "/customers/me/privacy/consents"));
    assertNotAborted(invoke("DELETE", "/customers/me/privacy/consents"));
    assertNotAborted(invoke("GET", "/customers/me/privacy/requests"));
    assertNotAborted(invoke("POST", "/customers/me/privacy/requests"));
    assertAborted(invoke("GET", "/customers/privacy/settings"), 403);
    assertAborted(invoke("PUT", "/customers/privacy/settings"), 403);
    assertAborted(invoke("POST", "/customers/privacy/notices"), 403);
    assertAborted(invoke("GET", "/customers/privacy/requests"), 403);
    assertAborted(invoke("POST", "/customers/privacy/breach-intimations"), 403);
    assertAborted(invoke("GET", "/customers/01a09509-72ec-72e9-9f08-94a93df26a36/privacy"), 403);
    assertAborted(
        invoke("POST", "/customers/01a09509-72ec-72e9-9f08-94a93df26a36/privacy/guardian"), 403);
    assertAborted(invoke("GET", "/customers/privacy/notice/extra"), 403);
  }

  /**
   * A network delivering an e-invoice reaches purchase-svc with no JWT and no tenant, like a
   * webhook; the delivery key is checked there. A person's upload beside it needs a staff role.
   */
  @Test
  void eInvoiceDeliveriesAreReachableWithoutAnyRoleButUploadsAreNot() throws Exception {
    assertNotAborted(invoke("POST", "/e-invoices/inbound/peppol"));
    assertNotAborted(invoke("POST", "/e-invoices/inbound/simulated"));
    assertAborted(invoke("POST", "/e-invoices"), 403);
    assertAborted(invoke("POST", "/e-invoices/inbound-replay/peppol"), 403);
    assertAborted(invoke("GET", "/e-invoices/inbound/peppol"), 403);
  }

  /** Taking the money is the business's act, not the shopper's. */
  @Test
  void capturingAnIntentRequiresStaff() throws Exception {
    assertAborted(invoke("POST", "/payments/intents/abc/capture"), 403);
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("POST", "/payments/intents/abc/capture"), 403);
  }

  /** The same lookalike trap as /catalog and /orders: a prefix match would hand these away. */
  @Test
  void paymentLookalikePathsDoNotInheritTheExemption() throws Exception {
    // Not the exempted shape: only GET /payments/intents/{id} is open.
    assertAborted(invoke("GET", "/payments/intents"), 403);
    assertAborted(invoke("GET", "/payments/intents/abc/audit"), 403);
    // A route merely starting with the same characters is a different route.
    assertAborted(invoke("POST", "/payments/intents-bulk"), 403);
    assertAborted(invoke("POST", "/payments/webhooks-replay"), 403);
  }

  // ── Staff-operable admin surfaces (STOREKEEPER inventory + CASHIER till) ──

  @Test
  void storekeeperCanReceiveStockUnderAdminInventory() throws Exception {
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertNotAborted(invoke("POST", "/admin/inventory/receive"));
  }

  @Test
  void storekeeperCanReadInventoryLevels() throws Exception {
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertNotAborted(invoke("GET", "/admin/inventory/levels"));
  }

  @Test
  void customerCannotReadAdminInventory() throws Exception {
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/admin/inventory/levels"), 403);
  }

  @Test
  void unauthenticatedCannotReadAdminInventory() throws Exception {
    assertAborted(invoke("GET", "/admin/inventory/levels"), 403);
  }

  // ── SJ-D19: the reports subtree is carved out of the staff tier ───────────

  /**
   * The whole point of the carve-out. Every report under {@code /admin/inventory/reports} answered
   * a CASHIER with 200 on the running stack, because it sits inside the warehouse subtree that
   * STOREKEEPER and CASHIER legitimately share. Stock valuation, cost of goods sold and a shrinkage
   * report naming which colleague wrote off what are not warehouse work.
   */
  @Test
  void staffCannotReadInventoryReports() throws Exception {
    for (String role : new String[] {"CASHIER", "STOREKEEPER"}) {
      ctx.set(null, null, Set.of(role), null, null);
      for (String report :
          new String[] {"valuation", "shrinkage", "low-stock", "stock-turn", "dead-stock"}) {
        assertAborted(invoke("GET", "/admin/inventory/reports/" + report), 403);
      }
    }
  }

  @Test
  void managementCanReadInventoryReports() throws Exception {
    ctx.set(null, null, Set.of("MANAGER"), null, null);
    assertNotAborted(invoke("GET", "/admin/inventory/reports/valuation"));
    ctx.set(null, null, Set.of("OWNER"), null, null);
    assertNotAborted(invoke("GET", "/admin/inventory/reports/stock-turn"));
  }

  /**
   * The lookalike trap, pinned the way SJ-D11 pinned {@code /catalog-exports}: a path that merely
   * starts with the same characters is a different path, and must keep the staff access the
   * warehouse subtree grants it.
   */
  @Test
  void aPathMerelyStartingWithReportsKeepsItsStaffAccess() throws Exception {
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertNotAborted(invoke("GET", "/admin/inventory/reports-config"));
    assertNotAborted(invoke("POST", "/admin/inventory/reportable-items"));
  }

  /**
   * The one report opened to the shop floor: a storekeeper reads the shelf gaps (the resource then
   * holds them to their own stores). Only the read, only that report, and nobody without a staff
   * role.
   */
  @Test
  void theShelfGapsAreTheOneReportStaffMayRead() throws Exception {
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertNotAborted(invoke("GET", "/admin/inventory/reports/shelf-gaps"));
    assertAborted(invoke("POST", "/admin/inventory/reports/shelf-gaps"), 403);
    assertAborted(invoke("GET", "/admin/inventory/reports/shelf-gaps/export"), 403);
    assertAborted(invoke("GET", "/admin/inventory/reports/shelf-gapsx"), 403);
    assertAborted(invoke("GET", "/admin/inventory/reports/low-stock"), 403);
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/admin/inventory/reports/shelf-gaps"), 403);
    ctx.set(null, null, Set.of(), null, null);
    assertAborted(invoke("GET", "/admin/inventory/reports/shelf-gaps"), 403);
  }

  // ── password reset ──────────────────────────────────────────────────────────

  @Test
  void aForgottenPasswordIsAskedForAndResetWithNoRoleAtAll() throws Exception {
    assertNotAborted(invoke("POST", "/auth/password/forgot"));
    assertNotAborted(invoke("POST", "/auth/password/reset"));
    assertNotAborted(invoke("GET", "/auth/password-policy"));
  }

  @Test
  void nothingBesideThePasswordResetPathsIsOpened() throws Exception {
    assertAborted(invoke("POST", "/auth/password"), 403);
    assertAborted(invoke("POST", "/auth/password/forgot/again"), 403);
    assertAborted(invoke("POST", "/auth/password/resets"), 403);
    assertAborted(invoke("GET", "/auth/password/reset"), 403);
    assertAborted(invoke("POST", "/auth/password-policy"), 403);
    assertAborted(invoke("GET", "/auth/password-policy/x"), 403);
  }

  @Test
  void cashierCanOpenTillSession() throws Exception {
    // Resource layer allows CASHIER on open; the filter must not management-block first.
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertNotAborted(invoke("POST", "/admin/cash/till-sessions"));
  }

  @Test
  void cashierCanReadTillSession() throws Exception {
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertNotAborted(
        invoke("GET", "/admin/cash/till-sessions/01a090ae-611e-7001-a690-2682e4afcb55"));
  }

  @Test
  void storekeeperCanReadStoresForInventoryUi() throws Exception {
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertNotAborted(invoke("GET", "/admin/stores"));
    assertNotAborted(invoke("GET", "/admin/stores/abc/zones"));
    assertNotAborted(invoke("GET", "/admin/tenant"));
    assertNotAborted(invoke("GET", "/admin/products/variants/resolve"));
  }

  @Test
  void storekeeperCannotMutateStores() throws Exception {
    // Creating/updating stores stays management-only.
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertAborted(invoke("POST", "/admin/stores"), 403);
    assertAborted(invoke("PUT", "/admin/stores/abc"), 403);
  }

  @Test
  void storekeeperCannotAccessManagementAdminPaths() throws Exception {
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertAborted(invoke("GET", "/admin/staff"), 403);
    assertAborted(invoke("GET", "/admin/reports/sales/summary"), 403);
    assertAborted(invoke("POST", "/admin/products"), 403);
  }

  @Test
  void managerStillHasFullAdminAccess() throws Exception {
    ctx.set(null, null, Set.of("MANAGER"), null, null);
    assertNotAborted(invoke("GET", "/admin/staff"));
    assertNotAborted(invoke("POST", "/admin/inventory/receive"));
    assertNotAborted(invoke("POST", "/admin/cash/till-sessions"));
  }

  // ── 20.12: second factors ─────────────────────────────────────────────────────

  @Test
  void aSignInAnswersItsSecondFactorWithNoRoleAtAll() throws Exception {
    assertNotAborted(invoke("POST", "/auth/mfa/login"));
    assertNotAborted(invoke("POST", "/auth/mfa/login/passkey-options"));
  }

  @Test
  void anyLoginSetsUpAndRemovesItsOwnFactors() throws Exception {
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertNotAborted(invoke("GET", "/auth/mfa"));
    assertNotAborted(invoke("POST", "/auth/mfa/totp"));
    assertNotAborted(invoke("POST", "/auth/mfa/totp/confirm"));
    assertNotAborted(invoke("POST", "/auth/mfa/totp/remove"));
    assertNotAborted(invoke("POST", "/auth/mfa/recovery-codes"));
    assertNotAborted(invoke("POST", "/auth/mfa/passkeys/options"));
    assertNotAborted(invoke("POST", "/auth/mfa/passkeys"));
  }

  @Test
  void theBusinesssRuleAndTheLostPhoneResetStayWithManagement() throws Exception {
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/auth/admin/mfa-policy"), 403);
    assertAborted(invoke("PUT", "/auth/admin/mfa-policy"), 403);
    assertAborted(
        invoke("DELETE", "/auth/admin/staff-users/01a090ae-611e-7011-ae7d-1bd68c966ff6/mfa"), 403);
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertAborted(invoke("PUT", "/auth/admin/mfa-policy"), 403);
  }

  @Test
  void aLookalikeOfTheSecondFactorPathsIsNotOpened() throws Exception {
    assertAborted(invoke("POST", "/auth/mfa-something"), 403);
    assertAborted(invoke("POST", "/auth/mfax/login"), 403);
  }

  // ── 20.x: single sign-on ─────────────────────────────────────────────────────

  @Test
  void aSignInThroughAProviderStartsReturnsAndRedeemsWithNoRoleAtAll() throws Exception {
    assertNotAborted(invoke("POST", "/auth/sso/start"));
    assertNotAborted(invoke("GET", "/auth/sso/callback"));
    assertNotAborted(invoke("POST", "/auth/sso/token"));
  }

  @Test
  void theBusinesssProviderSettingsStayWithManagement() throws Exception {
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/auth/admin/sso"), 403);
    assertAborted(invoke("PUT", "/auth/admin/sso"), 403);
    assertAborted(invoke("DELETE", "/auth/admin/sso"), 403);
    assertAborted(invoke("GET", "/auth/admin/sso/readiness"), 403);
    assertAborted(invoke("GET", "/auth/admin/sso/identities"), 403);
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertAborted(invoke("PUT", "/auth/admin/sso"), 403);
  }

  @Test
  void nothingElseUnderTheSignInPathsIsOpened() throws Exception {
    assertAborted(invoke("POST", "/auth/sso/start/again"), 403);
    assertAborted(invoke("POST", "/auth/sso/callback"), 403);
    assertAborted(invoke("GET", "/auth/sso/token"), 403);
    assertAborted(invoke("GET", "/auth/sso/callbackx"), 403);
    assertAborted(invoke("POST", "/auth/sso"), 403);
  }

  // ── 20.15: the token signing keys' public halves ─────────────────────────────

  @Test
  void thePublishedKeySetIsReadByAnybody() throws Exception {
    // The broker and the gateway read it with no identity at all.
    assertNotAborted(invoke("GET", "/auth/.well-known/jwks.json"));
  }

  @Test
  void thePublishedKeySetIsNeverWrittenByAnybodyWithoutAStaffRole() throws Exception {
    assertAborted(invoke("POST", "/auth/.well-known/jwks.json"), 403);
    assertAborted(invoke("DELETE", "/auth/.well-known/jwks.json"), 403);
  }

  @Test
  void theSigningKeyRegisterIsNotOpenedByTheKeySet() throws Exception {
    // The open read is the one exact path, not a prefix the admin routes could fall under.
    assertAborted(invoke("GET", "/auth/admin/signing-keys"), 403);
    assertAborted(invoke("POST", "/auth/admin/signing-keys/rotate"), 403);
  }

  private Response.StatusType invoke(String method, String path) throws Exception {
    AbortCapture capture = new AbortCapture();
    var req = requestContext(method, path, capture);
    filter.filter(req);
    return capture.status;
  }

  private static void assertAborted(Response.StatusType status, int expectedCode) {
    assertTrue(status != null, "expected the filter to abort the request");
    assertEquals(expectedCode, status.getStatusCode());
  }

  private static void assertNotAborted(Response.StatusType status) {
    assertFalse(status != null, "expected the filter to let the request through");
  }

  private static final class AbortCapture {
    Response.StatusType status;
  }

  /**
   * Hand-rolled {@code ContainerRequestContext}/{@code UriInfo} stub: {@link
   * AdminAuthorizationFilter#filter} only calls {@code getUriInfo().getPath()}, {@code
   * getMethod()}, and {@code abortWith(Response)}, so every other method is unimplemented — pulling
   * in a mocking framework for three methods isn't worth the dependency.
   */
  private static jakarta.ws.rs.container.ContainerRequestContext requestContext(
      String method, String path, AbortCapture capture) {
    jakarta.ws.rs.core.UriInfo uriInfo =
        (jakarta.ws.rs.core.UriInfo)
            Proxy.newProxyInstance(
                AdminAuthorizationFilterTest.class.getClassLoader(),
                new Class<?>[] {jakarta.ws.rs.core.UriInfo.class},
                (proxy, m, args) -> {
                  // Real JAX-RS UriInfo.getPath() returns the path with NO leading slash — the
                  // filter itself prepends one (see its "normalize" comment), so the stub must
                  // match that contract or every path gets double-slashed and fails to match.
                  if ("getPath".equals(m.getName()) && m.getParameterCount() == 0) {
                    return path.startsWith("/") ? path.substring(1) : path;
                  }
                  if ("getRequestUri".equals(m.getName())) return URI.create("http://x" + path);
                  throw new UnsupportedOperationException(m.getName());
                });
    return (jakarta.ws.rs.container.ContainerRequestContext)
        Proxy.newProxyInstance(
            AdminAuthorizationFilterTest.class.getClassLoader(),
            new Class<?>[] {jakarta.ws.rs.container.ContainerRequestContext.class},
            (proxy, m, args) -> {
              switch (m.getName()) {
                case "getUriInfo":
                  return uriInfo;
                case "getMethod":
                  return method;
                case "abortWith":
                  capture.status = ((Response) args[0]).getStatusInfo();
                  return null;
                default:
                  throw new UnsupportedOperationException(m.getName());
              }
            });
  }

  @Test
  void ordersExportIsNotASelfRead() throws Exception {
    // A literal child of /orders must not be mistaken for an order id: /orders/export names a
    // subject and is staff-only. A negative test found a customer token reading it before it
    // shipped, because the self-read shape matched "export" as if it were an id.
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/orders/export"), 403);
    // /orders/mine and an id-addressed order stay open to the shopper, as before.
    assertNotAborted(invoke("GET", "/orders/mine"));
    assertNotAborted(invoke("GET", "/orders/01a09509-72ec-72e9-9f08-94a93df26a36"));
    assertNotAborted(invoke("GET", "/orders/01a09509-72ec-72e9-9f08-94a93df26a36/history"));
    // A staff role reads the export.
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertNotAborted(invoke("GET", "/orders/export"));
  }

  // ── 21.16: the retention schedule ────────────────────────────────────────────

  @Test
  void retentionSheetIsStaffReadableAndTheRestIsManagement() throws Exception {
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertNotAborted(invoke("GET", "/admin/tenant/retention"));
    assertAborted(invoke("GET", "/admin/tenant/retention/holds"), 403);
    assertAborted(invoke("GET", "/admin/tenant/retention/runs"), 403);
    assertAborted(invoke("PUT", "/admin/tenant/retention/CUSTOMER_RECORDS"), 403);
    assertAborted(invoke("POST", "/admin/tenant/retention/holds"), 403);
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/admin/tenant/retention"), 403);
    ctx.set(null, null, Set.of("MANAGER"), null, null);
    assertNotAborted(invoke("PUT", "/admin/tenant/retention/CUSTOMER_RECORDS"));
    assertNotAborted(invoke("GET", "/admin/tenant/retention/runs"));
  }

  // ── /platform/ is the operator's own, and default-deny ───────────────────────

  @Test
  void everythingUnderPlatformNeedsManagementBeforeItsBodyIsEvenRead() throws Exception {
    // Found by a k6 abuse check that wanted 403 from an owner and got 400: the route's own
    // requireAnyRole runs *after* Bean Validation, so an owner with no business there learned
    // whether
    // its body was well-formed first. And a new /platform/ route that forgot the call would have
    // been
    // open to any staff role — the SJ-D65 shape again.
    ctx.set(null, null, Set.of("OWNER"), null, null);
    for (String path :
        new String[] {
          "/platform/plans",
          "/platform/tenants",
          "/platform/billing/profile",
          "/platform/billing/dunning/policy",
          "/platform/security-incidents"
        }) {
      assertAborted(invoke("GET", path), 403);
      assertAborted(invoke("PUT", path), 403);
      assertAborted(invoke("POST", path), 403);
    }

    ctx.set(null, null, Set.of("MANAGER"), null, null);
    assertAborted(invoke("GET", "/platform/tenants"), 403);
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertAborted(invoke("GET", "/platform/tenants"), 403);
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/platform/tenants"), 403);

    ctx.set(null, null, Set.of("PLATFORM_ADMIN"), null, null);
    assertNotAborted(invoke("GET", "/platform/tenants"));
    assertNotAborted(invoke("PUT", "/platform/billing/profile"));

    // By prefix with its slash, so a path that merely begins with the word is not caught by it and
    // falls through to the ordinary tiers instead. (A staff role still gets it there — the point
    // here
    // is only that the platform tier did not claim it.)
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertNotAborted(invoke("GET", "/platformx/anything"));
  }

  // ── 21.12: the pay link in a dunning notice ──────────────────────────────────

  @Test
  void thePayLinkIsReachableWithNoIdentityAtAll() throws Exception {
    // The one route in the service with no identity. By the time the later dunning notices go out
    // the
    // business has been suspended, so it cannot sign in; a pay link that demands a session is a
    // dead
    // end. The token is the whole capability and it names one invoice.
    ctx.set(null, null, Set.of(), null, null);
    assertNotAborted(invoke("POST", "/billing/pay/AbC123-token_value"));

    // Matched by shape, so nothing else under the prefix is admitted.
    assertAborted(invoke("POST", "/billing/pay"), 403);
    assertAborted(invoke("POST", "/billing/pay/"), 403);
    assertAborted(invoke("POST", "/billing/pay/tok/extra"), 403);
    assertAborted(invoke("POST", "/billing/payx/tok"), 403);
    // And the token opens nothing else: it is a payment, not a door into billing.
    assertAborted(invoke("POST", "/billing/invoices/tok/void"), 403);
    assertAborted(invoke("GET", "/billing/pay/tok"), 403);

    // A cashier gains nothing from it either — there is no role that makes it more than one
    // payment.
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertNotAborted(invoke("POST", "/billing/pay/AbC123-token_value"));
  }

  // ── 21.10: whether one more metered thing may be done, read service-to-service ──

  @Test
  void exchangeRatesAreStaffReadableAndSettingThemIsManagement() throws Exception {
    // pricing-svc shows a price in another currency and purchase-svc translates a spend ceiling
    // (03.x): both read the business's rates as STOREKEEPER, the identity one service uses for
    // another's staff-operable reads. The leaf only; setting a rate stays management's.
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertNotAborted(invoke("GET", "/admin/tenant/fx-rates"));
    assertAborted(invoke("GET", "/admin/tenant/fx-rates/USD/history"), 403);
    assertAborted(invoke("PUT", "/admin/tenant/fx-rates/USD"), 403);
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/admin/tenant/fx-rates"), 403);
    // The currencies a shop shows prices in are for anyone browsing, like a price itself.
    assertNotAborted(invoke("GET", "/prices/currencies"));
    ctx.set(null, null, Set.of("MANAGER"), null, null);
    assertNotAborted(invoke("PUT", "/admin/tenant/fx-rates/USD"));
    assertNotAborted(invoke("GET", "/admin/tenant/fx-rates/USD/history"));
  }

  @Test
  void promotionWindowsAreStaffReadableAndTheRestOfPromotionsIsManagement() throws Exception {
    // inventory-svc reads the windows for its forecast (06.x) as STOREKEEPER, the identity one
    // service uses for another's staff-operable reads. The leaf only: creating, scoping and
    // switching a promotion stay management's, and so does its switch history.
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertNotAborted(invoke("GET", "/admin/promotions/windows"));
    assertAborted(invoke("GET", "/admin/promotions"), 403);
    assertAborted(invoke("GET", "/admin/promotions/windows/extra"), 403);
    assertAborted(invoke("POST", "/admin/promotions/windows"), 403);
    assertAborted(invoke("POST", "/admin/promotions"), 403);
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/admin/promotions/windows"), 403);
    ctx.set(null, null, Set.of("MANAGER"), null, null);
    assertNotAborted(invoke("GET", "/admin/promotions/windows"));
  }

  @Test
  void aUsageAllowanceIsStaffReadableAndWhatWasUsedIsManagement() throws Exception {
    // notification-svc asks this before a marketing text, as STOREKEEPER. Left off the staff
    // tier, the call would be refused, Quotas would fail open, and a hard quota would never refuse
    // anything — SJ-D65 exactly, so it is pinned here the day it is added.
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertNotAborted(invoke("GET", "/admin/tenant/usage/allowance"));
    // The leaf only. What the business used, and what it costs, are management's.
    assertAborted(invoke("GET", "/admin/tenant/usage"), 403);
    assertAborted(invoke("GET", "/admin/tenant/usage/allowance/SMS"), 403);
    assertAborted(invoke("GET", "/admin/tenant/usage/allowances"), 403);
    // A read, never a write.
    assertAborted(invoke("PUT", "/admin/tenant/usage/allowance"), 403);
    assertAborted(invoke("POST", "/admin/tenant/usage/allowance"), 403);
    // A shopper is not staff.
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/admin/tenant/usage/allowance"), 403);
    // Management reads the whole of it.
    ctx.set(null, null, Set.of("MANAGER"), null, null);
    assertNotAborted(invoke("GET", "/admin/tenant/usage"));
  }

  // ── 21.8: what the plan allows, read service-to-service ──────────────────────

  @Test
  void planAllowancesAreStaffReadableAndTheRestOfThePlanIsManagement() throws Exception {
    // STOREKEEPER is the identity ServiceReader stamps on a service-to-service read, and this is
    // the route product-svc enforces its product ceiling from. It was refused here before SJ-D65,
    // so the ceiling was never enforced at all.
    ctx.set(null, null, Set.of("STOREKEEPER"), null, null);
    assertNotAborted(invoke("GET", "/admin/tenant/plan/limits"));
    // The leaf only. The plan itself carries what the business is charged, which is management's.
    assertAborted(invoke("GET", "/admin/tenant/plan"), 403);
    assertAborted(invoke("GET", "/admin/tenant/plan/available"), 403);
    // Nothing under the leaf, and nothing that merely begins with its name.
    assertAborted(invoke("GET", "/admin/tenant/plan/limits/stores.max"), 403);
    assertAborted(invoke("GET", "/admin/tenant/plan/limitsx"), 403);
    assertAborted(invoke("GET", "/admin/tenant/plans/limits"), 403);
    // A read, never a write: an allowance is the platform's to set.
    assertAborted(invoke("PUT", "/admin/tenant/plan/limits"), 403);
    assertAborted(invoke("POST", "/admin/tenant/plan/limits"), 403);
    assertAborted(invoke("DELETE", "/admin/tenant/plan/limits"), 403);

    // A shopper is not staff, whatever the route.
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    assertAborted(invoke("GET", "/admin/tenant/plan/limits"), 403);
    // Nor is a caller carrying no role at all.
    ctx.set(null, null, Set.of(), null, null);
    assertAborted(invoke("GET", "/admin/tenant/plan/limits"), 403);

    ctx.set(null, null, Set.of("MANAGER"), null, null);
    assertNotAborted(invoke("GET", "/admin/tenant/plan"));
    assertNotAborted(invoke("GET", "/admin/tenant/plan/limits"));
  }

  // ── 05.10: a recall's notices to buyers ──────────────────────────────────────

  @Test
  void recallNoticesOpenOnlyTheShoppersOwnShapes() throws Exception {
    ctx.set(null, null, Set.of("CUSTOMER"), null, null);
    // Their own notices, and their choice of remedy on one — object-level checks in order-svc.
    assertNotAborted(invoke("GET", "/orders/recall-notices/mine"));
    assertNotAborted(
        invoke("POST", "/orders/recall-notices/01a09509-72ec-72e9-9f08-94a93df26a36/remedy"));
    // A recall's whole list, its progress, and settling a notice are staff work.
    assertAborted(invoke("GET", "/orders/recall-notices"), 403);
    assertAborted(invoke("GET", "/orders/recall-notices/progress"), 403);
    assertAborted(
        invoke("POST", "/orders/recall-notices/01a09509-72ec-72e9-9f08-94a93df26a36/resolve"), 403);
    // Matched by shape: a literal where the id goes, or a deeper child, is not the shape.
    assertAborted(invoke("POST", "/orders/recall-notices/mine/remedy"), 403);
    assertAborted(invoke("GET", "/orders/recall-notices/mine/all"), 403);
    assertAborted(
        invoke("POST", "/orders/recall-notices/01a09509-72ec-72e9-9f08-94a93df26a36/remedy/x"),
        403);
    assertAborted(invoke("GET", "/orders/recall-noticesX/mine"), 403);
    ctx.set(null, null, Set.of("CASHIER"), null, null);
    assertNotAborted(invoke("GET", "/orders/recall-notices"));
    assertNotAborted(invoke("GET", "/orders/recall-notices/progress"));
    assertNotAborted(
        invoke("POST", "/orders/recall-notices/01a09509-72ec-72e9-9f08-94a93df26a36/resolve"));
  }
}
