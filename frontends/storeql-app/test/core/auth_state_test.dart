import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/constants.dart';

AuthAuthenticated _auth({
  List<String> roles = const [],
  String? tenantId = 't-1',
}) {
  return AuthAuthenticated(
    accessToken: 'a',
    refreshToken: 'r',
    userId: 'u-1',
    tenantId: tenantId,
    roles: roles,
    email: 'user@example.com',
  );
}

void main() {
  group('homeRoute', () {
    test('storekeeper-only lands on inventory, not storefront', () {
      final auth = _auth(roles: [UserRoles.storekeeper]);
      expect(auth.homeRoute, '/admin/inventory');
      expect(auth.canAccessAdmin, isTrue);
      expect(auth.isAdmin, isTrue);
      expect(auth.isManager, isFalse);
    });

    test('manager lands on dashboard', () {
      final auth = _auth(roles: [UserRoles.manager]);
      expect(auth.homeRoute, '/admin/dashboard');
      expect(auth.isManager, isTrue);
      expect(auth.canAccessAdmin, isTrue);
    });

    test('owner lands on dashboard', () {
      final auth = _auth(roles: [UserRoles.owner]);
      expect(auth.homeRoute, '/admin/dashboard');
    });

    test('storekeeper who is also manager uses manager home', () {
      final auth = _auth(roles: [UserRoles.manager, UserRoles.storekeeper]);
      expect(auth.homeRoute, '/admin/dashboard');
      expect(auth.isManager, isTrue);
    });

    test('cashier lands on POS', () {
      final auth = _auth(roles: [UserRoles.cashier]);
      expect(auth.homeRoute, '/pos/cart');
      expect(auth.canAccessAdmin, isFalse);
    });

    test('customer lands on storefront', () {
      final auth = _auth(roles: [UserRoles.customer]);
      expect(auth.homeRoute, '/store/products');
    });

    test('platform admin lands on platform overview', () {
      final auth = _auth(roles: [UserRoles.platformAdmin], tenantId: null);
      expect(auth.homeRoute, '/platform/overview');
      expect(auth.isPlatformAdmin, isTrue);
    });

    test('owner without tenant needs onboarding', () {
      final auth = _auth(roles: [UserRoles.owner], tenantId: null);
      expect(auth.needsOnboarding, isTrue);
      expect(auth.homeRoute, '/onboarding');
    });

    test('storekeeper without tenant does not self-onboard', () {
      // Storekeepers are assigned to an existing tenant by staff; a missing
      // tenant claim is a token/config problem, not an onboarding flow.
      final auth = _auth(roles: [UserRoles.storekeeper], tenantId: null);
      expect(auth.needsOnboarding, isFalse);
      expect(auth.homeRoute, '/admin/inventory');
    });
  });

  group('system.health', () {
    AuthAuthenticated login(List<String> roles, [List<String>? permissions]) => AuthAuthenticated(
          accessToken: 'a',
          refreshToken: 'r',
          userId: 'u-1',
          tenantId: 't-1',
          roles: roles,
          permissions: permissions,
        );

    test('a manager holds it by default, as the server\'s catalogue says; no other tier does', () {
      expect(login([UserRoles.manager]).hasPermission('system.health'), isTrue);
      expect(login([UserRoles.storekeeper]).hasPermission('system.health'), isFalse);
      expect(login([UserRoles.cashier]).hasPermission('system.health'), isFalse);
      expect(login([UserRoles.customer]).hasPermission('system.health'), isFalse);
    });

    test('an owner always does; a claim narrows a manager to what it names', () {
      expect(login([UserRoles.owner], const []).hasPermission('system.health'), isTrue);
      expect(login([UserRoles.manager], const ['staff.manage']).hasPermission('system.health'), isFalse);
      expect(login([UserRoles.manager], const ['system.health']).hasPermission('system.health'), isTrue);
    });
  });

  group('storeIds', () {
    test('defaults to empty (unrestricted) when not supplied', () {
      final auth = _auth(roles: [UserRoles.owner]);
      expect(auth.storeIds, isEmpty);
    });

    test('carries the assigned stores for store-bound staff', () {
      final auth = const AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'u-1',
        tenantId: 't-1',
        roles: [UserRoles.cashier],
        storeIds: ['store-1'],
      );
      expect(auth.storeIds, ['store-1']);
    });
  });
}
