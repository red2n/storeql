import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/auth/mfa_api.dart';
import 'package:storeql_app/features/auth/security_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';
import 'package:storeql_app/features/storefront/storefront_shell.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Sign out everywhere (iam-svc, 30 Sep 2026): a login ends all its sessions from
// where its security lives: staff on the sign-in security screen, a shopper in
// the account menu. It asks first; a yes calls revoke-all and then signs this
// device out; a no, or a refusal, leaves the person signed in.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int status = 200;

  List<RequestOptions> get revokes => requests.where((r) => r.path.endsWith('/auth/sessions/revoke-all')).toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    return status == 200
        ? jsonResponse('{"data":{"revoked":3}}')
        : jsonResponse('{"error":{"code":"UNAVAILABLE","message":"Try again in a moment."}}', status);
  }
}

/// Signed in as staff; signing out only changes the state (no secure storage).
class _StaffAuth extends RoleAuth {
  _StaffAuth() : super('MANAGER');

  @override
  Future<void> logout() async {
    state = const AsyncValue.data(AuthUnauthenticated());
  }
}

Future<(_Server, ProviderContainer)> _pumpStaff(WidgetTester tester) async {
  tester.view.physicalSize = const Size(900, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  final container = ProviderContainer(overrides: [
    apiClientProvider.overrideWithValue(FakeApiClient(dio)),
    authNotifierProvider.overrideWith(_StaffAuth.new),
    mfaStatusProvider.overrideWith((ref) async =>
        const MfaStatus(totp: false, passkeys: [], recoveryCodesLeft: 0, required: false)),
  ]);
  addTearDown(container.dispose);
  await container.read(authNotifierProvider.future);
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: const MaterialApp(home: SecurityScreen()),
  ));
  await tester.pumpAndSettle();
  return (server, container);
}

/// A shopper signed in, with the server call replaced: the real notifier builds
/// its own Dio and writes secure storage, neither of which a widget test reaches.
class _ShopperAuth extends StorefrontAuthNotifier {
  int calls = 0;
  bool refuse = false;

  _ShopperAuth() {
    state = const StorefrontAuthState(accessToken: 'tok', refreshToken: 'ref', email: 'ana@example.com');
  }

  @override
  Future<void> signOutEverywhere() async {
    calls++;
    if (refuse) {
      final o = RequestOptions(path: '/iam-svc/auth/sessions/revoke-all');
      throw DioException(
        requestOptions: o,
        type: DioExceptionType.badResponse,
        response: Response(requestOptions: o, statusCode: 503, data: {
          'error': {'code': 'UNAVAILABLE', 'message': 'Try again in a moment.'}
        }),
      );
    }
    state = const StorefrontAuthState();
  }
}

Future<_ShopperAuth> _pumpShopper(WidgetTester tester) async {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final auth = _ShopperAuth();
  await tester.pumpWidget(ProviderScope(
    overrides: [
      storefrontAuthProvider.overrideWith((ref) => auth),
      storefrontConfigProvider.overrideWith((ref) async =>
          const StorefrontConfig(showPrices: true, storeName: 'Test Store')),
      storefrontSuspendedProvider.overrideWith((ref) async => false),
    ],
    child: const MaterialApp(
      home: StorefrontShell(currentLocation: '/store/products', child: SizedBox.expand()),
    ),
  ));
  await tester.pumpAndSettle();
  return auth;
}

Future<void> _openShopperConfirm(WidgetTester tester) async {
  await tester.tap(find.byTooltip('ana@example.com'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Sign out everywhere'));
  await tester.pumpAndSettle();
}

void main() {
  group('staff, on the sign-in security screen', () {
    testWidgets('asks first, and a no sends nothing', (tester) async {
      final (server, container) = await _pumpStaff(tester);
      await tester.ensureVisible(find.byKey(const Key('sign-out-everywhere')));
      await tester.tap(find.byKey(const Key('sign-out-everywhere')));
      await tester.pumpAndSettle();
      expect(find.text('Sign out everywhere?'), findsOneWidget);
      await tester.tap(find.byKey(const Key('sign-out-everywhere-cancel')));
      await tester.pumpAndSettle();
      expect(server.revokes, isEmpty);
      expect(container.read(authNotifierProvider).value, isA<AuthAuthenticated>());
    });

    testWidgets('a yes calls revoke-all and then signs this device out', (tester) async {
      final (server, container) = await _pumpStaff(tester);
      await tester.ensureVisible(find.byKey(const Key('sign-out-everywhere')));
      await tester.tap(find.byKey(const Key('sign-out-everywhere')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('sign-out-everywhere-confirm')));
      await tester.pumpAndSettle();
      expect(server.revokes, hasLength(1));
      expect(server.revokes.single.method, 'POST');
      expect(container.read(authNotifierProvider).value, isA<AuthUnauthenticated>());
    });

    testWidgets('a refusal reads in words and leaves the person signed in', (tester) async {
      final (server, container) = await _pumpStaff(tester);
      server.status = 503;
      await tester.ensureVisible(find.byKey(const Key('sign-out-everywhere')));
      await tester.tap(find.byKey(const Key('sign-out-everywhere')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('sign-out-everywhere-confirm')));
      await tester.pumpAndSettle();
      expect(find.text('Try again in a moment.'), findsOneWidget);
      expect(container.read(authNotifierProvider).value, isA<AuthAuthenticated>());
    });
  });

  group('a shopper, in the account menu', () {
    testWidgets('asks first; a no leaves them signed in', (tester) async {
      final auth = await _pumpShopper(tester);
      await _openShopperConfirm(tester);
      expect(find.text('Sign out everywhere?'), findsOneWidget);
      await tester.tap(find.byKey(const Key('sign-out-everywhere-cancel')));
      await tester.pumpAndSettle();
      expect(auth.calls, 0);
      expect(auth.state.isSignedIn, isTrue);
    });

    testWidgets('a yes ends every session and signs this device out', (tester) async {
      final auth = await _pumpShopper(tester);
      await _openShopperConfirm(tester);
      await tester.tap(find.byKey(const Key('sign-out-everywhere-confirm')));
      await tester.pumpAndSettle();
      expect(auth.calls, 1);
      expect(auth.state.isSignedIn, isFalse);
    });

    testWidgets('a refusal reads in words and leaves them signed in', (tester) async {
      final auth = await _pumpShopper(tester);
      auth.refuse = true;
      await _openShopperConfirm(tester);
      await tester.tap(find.byKey(const Key('sign-out-everywhere-confirm')));
      await tester.pumpAndSettle();
      expect(find.text('Try again in a moment.'), findsOneWidget);
      expect(auth.state.isSignedIn, isTrue);
    });
  });
}
