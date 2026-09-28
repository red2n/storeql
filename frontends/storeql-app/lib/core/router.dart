import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'auth/auth_notifier.dart';
import 'auth/auth_state.dart';
import '../features/auth/login_screen.dart';
import '../features/auth/second_factor_screen.dart';
import '../features/auth/second_factor_setup_screen.dart';
import '../features/auth/security_screen.dart';
import '../features/auth/pay_link_screen.dart';
import '../features/auth/forgot_password_screen.dart';
import '../features/auth/reset_password_screen.dart';
import '../features/platform/platform_login_screen.dart';
import '../features/onboarding/onboarding_wizard.dart';
import '../features/admin/admin_shell.dart';
import '../features/admin/admin_screens.dart' deferred as admin_lib;
import '../features/platform/platform_shell.dart';
import '../features/platform/platform_screens.dart' deferred as platform_lib;
import '../features/pos/pos_shell.dart';
import '../features/pos/pos_screens.dart' deferred as pos_lib;
import '../features/storefront/storefront_shell.dart';
import '../shared/widgets/deferred_widget.dart';
import '../features/storefront/storefront_screens.dart' deferred as storefront_lib;

final routerProvider = Provider<GoRouter>((ref) {
  final router = GoRouter(
    initialLocation: '/login',
    // _AuthListenable tells go_router to re-run redirect whenever auth changes
    refreshListenable: _AuthListenable(ref),
    redirect: (context, state) {
      final authAsync = ref.read(authNotifierProvider);
      if (authAsync.isLoading) return null;

      final auth = authAsync.value ?? const AuthUnauthenticated();
      final loc = state.matchedLocation;

      // Public storefront — accessible to EVERYONE (guests and any signed-in
      // user), so a storefront deep-link is never hijacked by the login /
      // onboarding / platform-admin redirects below. The storefront has its own
      // (separate) customer session and tenant-from-URL context.
      if (loc.startsWith('/store')) return null;

      // The pay link in a dunning notice (21.12): the business it reaches has
      // been suspended and cannot sign in, so the page that pays needs no session.
      if (loc.startsWith('/pay/')) return null;

      // Forgot/reset password (intent/password-reset.md): no session is ever
      // needed to ask for a link or to spend one — the link itself is the
      // proof — and a signed-in person opening either (a staff member on a
      // shared device, say) is not bounced away mid-reset. Mirrors how the
      // pay link above is let through, ahead of every other rule.
      if (loc.startsWith('/forgot-password') || loc.startsWith('/reset-password/')) {
        return null;
      }

      // A sign-in between its password and its session (20.12): the second step,
      // or the set-up of one, and nowhere else until that is done or given up.
      if (auth is AuthSecondFactorOwed) return loc == '/mfa' ? null : '/mfa';
      if (auth is AuthEnrolmentOwed) return loc == '/mfa/setup' ? null : '/mfa/setup';

      if (auth is AuthUnauthenticated) {
        if (loc == '/login' || loc == '/platform/login') return null;
        // /platform/* (other than the login page) has no unauthenticated access —
        // bounce to the platform login, not the store/POS one.
        return loc.startsWith('/platform') ? '/platform/login' : '/login';
      }

      if (auth is AuthAuthenticated) {
        // send logged-in users away from login/root
        if (loc == '/login' || loc == '/platform/login' || loc == '/' || loc.startsWith('/mfa')) {
          return auth.homeRoute;
        }
        // Every login's own sign-in security, whichever shell it works in.
        if (loc == '/account/security') return null;
        // force incomplete-onboarding users to the wizard
        if (auth.needsOnboarding && loc != '/onboarding') return '/onboarding';
        // once onboarded, keep them out of the wizard
        if (!auth.needsOnboarding && loc == '/onboarding') return auth.homeRoute;
        // PLATFORM_ADMIN: only allowed in /platform/*
        if (auth.isPlatformAdmin && !loc.startsWith('/platform')) {
          return auth.homeRoute;
        }
        // Tenant admins/cashiers: blocked from the platform area
        if (!auth.isPlatformAdmin && loc.startsWith('/platform')) {
          return auth.homeRoute;
        }
        // Admin shell: OWNER/MANAGER (full) or STOREKEEPER (inventory-focused).
        // Storekeeper deep-links into management-only screens are bounced home
        // by AdminShell's restricted destinations list + API 403s as belt-and-
        // suspenders; the gate here only checks shell entry.
        if (loc.startsWith('/admin') && !auth.canAccessAdmin) {
          return auth.homeRoute;
        }
        // Storekeeper-only: keep them on inventory (and store list for zone
        // context). Other /admin/* routes redirect home so the nav can't be
        // bypassed via URL.
        if (loc.startsWith('/admin') &&
            auth.isStorekeeper &&
            !auth.isManager &&
            !storekeeperAdminAllowed(loc)) {
          return auth.homeRoute;
        }
        // POS: cashiers and managers. Storekeepers stay on admin inventory.
        if (loc.startsWith('/pos') && !auth.isCashier && !auth.isManager) {
          return auth.homeRoute;
        }
      }
      return null;
    },
    routes: [
      GoRoute(path: '/login', builder: (_, _) => const LoginScreen()),
      GoRoute(path: '/platform/login', builder: (_, _) => const PlatformLoginScreen()),
      GoRoute(path: '/mfa', builder: (_, _) => const SecondFactorScreen()),
      GoRoute(path: '/mfa/setup', builder: (_, _) => const SecondFactorSetupScreen()),
      GoRoute(path: '/account/security', builder: (_, _) => const SecurityScreen()),
      GoRoute(path: '/onboarding', builder: (_, _) => const OnboardingWizard()),
      GoRoute(
        path: '/pay/:token',
        builder: (_, state) => PayLinkScreen(token: state.pathParameters['token'] ?? ''),
      ),
      GoRoute(
        path: '/forgot-password',
        // Named by whoever sent a shopper here (the storefront's own sign-in
        // dialog, `?from=storefront`) so the page can lead back to the shop
        // rather than to staff sign-in once they're done — never guessed from
        // the referrer, which a deep link or a refresh would not carry.
        builder: (_, state) => ForgotPasswordScreen(
            from: state.uri.queryParameters['from']),
      ),
      GoRoute(
        path: '/reset-password/:token',
        builder: (_, state) => ResetPasswordScreen(token: state.pathParameters['token'] ?? ''),
      ),

      // ── Platform admin shell (PLATFORM_ADMIN only) ─────────────────────────
      ShellRoute(
        builder: (context, state, child) =>
            PlatformShell(currentLocation: state.matchedLocation, child: child),
        routes: [
          GoRoute(path: '/platform', redirect: (_, _) => '/platform/overview'),
          GoRoute(
            path: '/platform/overview',
            builder: (_, _) => DeferredWidget(
              libraryLoader: platform_lib.loadLibrary,
              builder: (_) => platform_lib.PlatformDashboardScreen(),
            ),
          ),
          GoRoute(
            path: '/platform/tenants',
            builder: (_, _) => DeferredWidget(
              libraryLoader: platform_lib.loadLibrary,
              builder: (_) => platform_lib.TenantsScreen(),
            ),
          ),
          GoRoute(
            path: '/platform/plans',
            builder: (_, _) => DeferredWidget(
              libraryLoader: platform_lib.loadLibrary,
              builder: (_) => platform_lib.PlansScreen(),
            ),
          ),
          GoRoute(
            path: '/platform/billing',
            builder: (_, _) => DeferredWidget(
              libraryLoader: platform_lib.loadLibrary,
              builder: (_) => platform_lib.PlatformBillingScreen(),
            ),
          ),
          GoRoute(
            path: '/platform/security',
            builder: (_, _) => DeferredWidget(
              libraryLoader: platform_lib.loadLibrary,
              builder: (_) => platform_lib.SecurityIncidentsScreen(),
            ),
            // One incident has its own address inside the shell, above the
            // register: a reload or a shared link opens it, and back (the
            // browser's, or the page's) returns to the register as it was left.
            routes: [
              GoRoute(
                path: ':id',
                builder: (_, state) => DeferredWidget(
                  libraryLoader: platform_lib.loadLibrary,
                  builder: (_) => platform_lib.SecurityIncidentDetailScreen(
                    id: state.pathParameters['id']!,
                  ),
                ),
              ),
            ],
          ),
        ],
      ),

      // ── Admin shell ────────────────────────────────────────────────────────
      ShellRoute(
        builder: (context, state, child) =>
            AdminShell(currentLocation: state.matchedLocation, child: child),
        routes: [
          GoRoute(path: '/admin', redirect: (_, _) => '/admin/dashboard'),
          GoRoute(
            path: '/admin/dashboard',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.DashboardScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/catalog',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.CatalogScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/inventory',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.InventoryScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/food-safety',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.FoodSafetyScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/recalls',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.RecallsScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/age-checks',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.AgeChecksScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/obligations',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.ObligationsScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/messages',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.MessagesScreen(),
            ),
            // One message's editor, inside the shell (under its one app bar)
            // and above the list, at an address a reload or a link keeps.
            routes: [
              GoRoute(
                path: ':type',
                builder: (_, state) => DeferredWidget(
                  libraryLoader: admin_lib.loadLibrary,
                  builder: (_) => admin_lib.MessageEditorPage(
                    type: state.pathParameters['type']!,
                  ),
                ),
              ),
            ],
          ),
          GoRoute(
            path: '/admin/statutory-returns',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.StatutoryReturnsScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/disputes',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.DisputesScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/plan',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.PlanScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/integrations',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.IntegrationsScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/billing',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.BillingScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/shelf-space',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.ShelfSpaceScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/terminals',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.TerminalsScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/settlements',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.SettlementsScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/deposits',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.DepositsReportScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/retention',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.RetentionScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/tenant-data',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.TenantDataScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/privacy',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.PrivacyScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/security-notices',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.SecurityNoticesScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/audit',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.AuditTrailScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/stores',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.StoresScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/orders',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.AdminOrdersScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/fulfilment',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.FulfilmentScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/procurement',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.ProcurementScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/pricing',
            // `?tab=vat-return` opens that tab (PricingScreen.tabNames).
            builder: (_, state) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.PricingScreen(
                initialTab: state.uri.queryParameters['tab'],
              ),
            ),
          ),
          GoRoute(
            path: '/admin/reports',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.ReportsScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/customers',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.CustomersScreen(),
            ),
          ),
          GoRoute(
            path: '/admin/sales',
            // `?tab=receipts` opens that tab (SalesScreen.tabNames).
            builder: (_, state) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.SalesScreen(
                initialTab: state.uri.queryParameters['tab'],
              ),
            ),
          ),
          GoRoute(
            path: '/admin/staff',
            builder: (_, _) => DeferredWidget(
              libraryLoader: admin_lib.loadLibrary,
              builder: (_) => admin_lib.StaffScreen(),
            ),
          ),
        ],
      ),

      // ── POS shell ──────────────────────────────────────────────────────────
      // The customer-facing display: a second window of the till, on the
      // customer's side of the counter, without the till's own chrome.
      GoRoute(
        path: '/pos/display',
        builder: (_, _) => DeferredWidget(
          libraryLoader: pos_lib.loadLibrary,
          builder: (_) => pos_lib.CustomerDisplayScreen(),
        ),
      ),
      ShellRoute(
        builder: (context, state, child) =>
            PosShell(currentLocation: state.matchedLocation, child: child),
        routes: [
          GoRoute(path: '/pos', redirect: (_, _) => '/pos/cart'),
          GoRoute(
            path: '/pos/cart',
            builder: (_, _) => DeferredWidget(
              libraryLoader: pos_lib.loadLibrary,
              builder: (_) => pos_lib.PosCartScreen(),
            ),
          ),
          GoRoute(
            path: '/pos/tender',
            builder: (_, _) => DeferredWidget(
              libraryLoader: pos_lib.loadLibrary,
              builder: (_) => pos_lib.TenderScreen(),
            ),
          ),
          GoRoute(
            path: '/pos/cash',
            builder: (_, _) => DeferredWidget(
              libraryLoader: pos_lib.loadLibrary,
              builder: (_) => pos_lib.CashScreen(),
            ),
          ),
          GoRoute(
            path: '/pos/pending',
            builder: (_, _) => DeferredWidget(
              libraryLoader: pos_lib.loadLibrary,
              builder: (_) => pos_lib.OfflineQueueScreen(),
            ),
          ),
        ],
      ),

      // ── Storefront shell ───────────────────────────────────────────────────
      ShellRoute(
        builder: (context, state, child) =>
            StorefrontShell(currentLocation: state.matchedLocation, child: child),
        routes: [
          GoRoute(path: '/store', redirect: (_, _) => '/store/products'),
          GoRoute(
            path: '/store/products',
            builder: (_, _) => DeferredWidget(
              libraryLoader: storefront_lib.loadLibrary,
              builder: (_) => storefront_lib.ProductListScreen(),
            ),
          ),
          GoRoute(
            path: '/store/products/:id',
            builder: (_, state) => DeferredWidget(
              libraryLoader: storefront_lib.loadLibrary,
              builder: (_) => storefront_lib.ProductDetailScreen(
                productId: state.pathParameters['id']!,
              ),
            ),
          ),
          GoRoute(
            path: '/store/cart',
            builder: (_, _) => DeferredWidget(
              libraryLoader: storefront_lib.loadLibrary,
              builder: (_) => storefront_lib.StorefrontCartScreen(),
            ),
          ),
          GoRoute(
            path: '/store/orders',
            builder: (_, _) => DeferredWidget(
              libraryLoader: storefront_lib.loadLibrary,
              builder: (_) => storefront_lib.StorefrontOrdersScreen(),
            ),
          ),
          GoRoute(
            path: '/store/privacy',
            builder: (_, _) => DeferredWidget(
              libraryLoader: storefront_lib.loadLibrary,
              builder: (_) => storefront_lib.StorefrontPrivacyScreen(),
            ),
          ),
          GoRoute(
            path: '/store/account',
            builder: (_, _) => DeferredWidget(
              libraryLoader: storefront_lib.loadLibrary,
              builder: (_) => storefront_lib.StorefrontAccountScreen(),
            ),
          ),
          GoRoute(
            path: '/store/accessibility',
            builder: (_, _) => DeferredWidget(
              libraryLoader: storefront_lib.loadLibrary,
              builder: (_) => storefront_lib.StorefrontAccessibilityScreen(),
            ),
          ),
        ],
      ),
    ],
    errorBuilder: (context, state) => Scaffold(
      body: Center(child: Text('Page not found: ${state.uri}')),
    ),
  );

  ref.onDispose(router.dispose);
  return router;
});

/// Paths a storekeeper-only user may open inside the admin shell: the pages
/// whose reads the services open to any member of staff. Kept in step with
/// the menu (`storekeeperAdminRoutes`) by `test/core/storekeeper_routes_test`.
bool storekeeperAdminAllowed(String loc) {
  return loc.startsWith('/admin/inventory') ||
      loc.startsWith('/admin/fulfilment') ||
      loc.startsWith('/admin/food-safety') ||
      loc.startsWith('/admin/recalls') ||
      loc.startsWith('/admin/obligations') ||
      loc.startsWith('/admin/stores') ||
      loc.startsWith('/admin/shelf-space');
}

class _AuthListenable extends ChangeNotifier {
  _AuthListenable(Ref ref) {
    ref.listen<AsyncValue<AuthState>>(
      authNotifierProvider,
      (_, _) => notifyListeners(),
    );
  }
}
