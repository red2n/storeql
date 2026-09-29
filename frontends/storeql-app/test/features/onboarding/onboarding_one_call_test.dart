import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/auth/password_policy.dart';
import 'package:storeql_app/core/auth/sso.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/router.dart';
import 'package:storeql_app/features/onboarding/onboarding_wizard.dart';
import 'package:storeql_app/l10n/gen/app_localizations.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The setup wizard creates the business and its first store in one call
// (29 Sep 2026). It used to create the business on "Continue" and refresh the
// token until iam-svc had made the login its owner — and the router takes a
// login whose token names a business out of the wizard, so a new business was
// on its dashboard before the first store was ever asked for. Where the grant
// was slow, the store step was sent with a token naming no business and
// refused. Now "Continue" sends nothing; the store step sends both together,
// and only then is the token refreshed.
//
// One shell per file (see support/app_router_harness.dart): only the first
// test reaches the admin shell's deferred library.
// ---------------------------------------------------------------------------

const _signUp = '/iam-svc/auth/register/business';
const _refresh = '/iam-svc/auth/refresh';
const _onboard = '/tenant-svc/onboarding';
const _tenantOnly = '/tenant-svc/onboarding/tenants';
const _storeOnly = '/tenant-svc/onboarding/stores';
const _plans = '/tenant-svc/plans';

const _userId = '019987a0-0f1e-7c3b-8a4d-3e2f1a0b9c81';
const _tenantId = '019987a0-0f1e-7c3b-8a4d-3e2f1a0b9c90';
const _storeId = '019987a0-0f1e-7c3b-8a4d-3e2f1a0b9c91';

class _Server implements HttpClientAdapter {
  final Map<String, ResponseBody Function(RequestOptions)> routes;
  final List<RequestOptions> requests = [];

  _Server(this.routes);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    final route = routes['${o.method} ${o.path}'];
    return route == null
        ? jsonResponse('{"error":{"code":"NOT_FOUND","message":"no route"}}', 404)
        : route(o);
  }

  List<RequestOptions> to(String path) =>
      requests.where((r) => r.path == path).toList();
}

/// An access token as iam-svc mints one. Only its payload is read here.
String _token(Map<String, Object?> claims) {
  String part(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  return '${part({'alg': 'RS256', 'kid': 'k'})}.${part(claims)}.signature';
}

String _tokens(Map<String, Object?> claims) => jsonEncode({
  'data': {
    'accessToken': _token(claims),
    'refreshToken': 'refresh-1',
    'tokenType': 'Bearer',
    'expiresInSeconds': 900,
  },
});

/// The business sign-up's login: staff, no business, no role.
final _founder = _tokens({
  'sub': _userId,
  'type': 'STAFF',
  'roles': <String>[],
  'email': 'founder@example.com',
  'amr': ['pwd'],
});

/// The same login once iam-svc has read TenantCreated: the business's OWNER.
final _owner = _tokens({
  'sub': _userId,
  'type': 'STAFF',
  'tenant': _tenantId,
  'roles': ['OWNER'],
  'email': 'founder@example.com',
  'amr': ['pwd'],
});

/// What tenant-svc answers POST /onboarding with: the business and its store.
final _onboarded = jsonEncode({
  'data': {
    'tenant': {'id': _tenantId, 'name': 'Kyoto Market'},
    'store': {'id': _storeId, 'name': 'Kyoto Market Gion', 'code': 'KY-1'},
  },
});

/// A refusal of the business itself, as tenant-svc answers one: problem details
/// with the legacy error member.
final _planGone = jsonEncode({
  'type': 'urn:storeql:problem:PLAN_NOT_SOLD',
  'title': 'Conflict',
  'status': 409,
  'detail': 'The Growth plan is no longer on sale',
  'code': 'PLAN_NOT_SOLD',
  'error': {'code': 'PLAN_NOT_SOLD', 'message': 'The Growth plan is no longer on sale'},
});

/// Business details: a name, and Japan, whose yen is suggested for it.
Future<void> _businessDetails(WidgetTester tester) async {
  await tester.enterText(
    find.widgetWithText(TextFormField, 'Business name *'),
    'Kyoto Market',
  );
  await tester.tap(
    find.widgetWithText(DropdownButtonFormField<String>, 'Country *'),
  );
  await tester.pumpAndSettle();
  await tester.tap(find.text('Japan (JP)').last);
  await tester.pumpAndSettle();
  await tester.tap(find.text('Continue'));
  await tester.pumpAndSettle();
}

/// The first store, filled in but not yet sent.
Future<void> _firstStore(WidgetTester tester) async {
  await tester.enterText(
    find.widgetWithText(TextFormField, 'Store name *'),
    'Kyoto Market Gion',
  );
  await tester.enterText(
    find.widgetWithText(TextFormField, 'Store code * (e.g. STR-001)'),
    'ky-1',
  );
  await tester.tap(
    find.widgetWithText(DropdownButtonFormField<String>, 'Timezone *'),
  );
  await tester.pumpAndSettle();
  await tester.tap(find.text('Asia/Tokyo').last);
  await tester.pumpAndSettle();
}

/// A login with no business yet whose grant is slow: its refreshes name no
/// business until [granted] is set.
class _SlowGrant extends AuthNotifier {
  bool granted = false;
  int refreshes = 0;

  @override
  Future<AuthState> build() async => const AuthAuthenticated(
    accessToken: 'a',
    refreshToken: 'r',
    userId: _userId,
    roles: [],
  );

  @override
  Future<void> refresh() async {
    refreshes++;
    if (!granted) return;
    state = const AsyncValue.data(
      AuthAuthenticated(
        accessToken: 'b',
        refreshToken: 'r',
        userId: _userId,
        tenantId: _tenantId,
        roles: ['OWNER'],
      ),
    );
  }
}

/// The wizard on its own, under a router with only where its "done" leads; the
/// app's own router is proved in the first test.
Future<GoRouter> _openWizard(
  WidgetTester tester,
  _Server server,
  _SlowGrant auth,
) async {
  tester.view.physicalSize = const Size(1400, 5000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  final router = GoRouter(
    initialLocation: '/onboarding',
    routes: [
      GoRoute(
        path: '/onboarding',
        builder: (_, _) => const OnboardingWizard(),
      ),
      GoRoute(
        path: '/admin/dashboard',
        builder: (_, _) => const Scaffold(body: Text('dashboard')),
      ),
    ],
  );
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        authNotifierProvider.overrideWith(() => auth),
      ],
      child: MaterialApp.router(routerConfig: router),
    ),
  );
  // In the app the sign-in is built long before the wizard opens (the router
  // watches it). Build it here too, or its first build lands after the first
  // refresh and undoes it, and every count of refreshes is off by one.
  ProviderScope.containerOf(tester.element(find.byType(OnboardingWizard)))
      .read(authNotifierProvider);
  await tester.pumpAndSettle();
  return router;
}

void main() {
  // The dashboard dates what it shows with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);

  setUp(() {
    FlutterSecureStorage.setMockInitialValues({});
    ssoReturnAtLaunch = null;
  });

  testWidgets(
    'Start a business → business details → first store → dashboard: the store step is shown and '
    'sent, with the business, in one call, and only then is the token refreshed',
    (tester) async {
      // iam-svc grants OWNER at once: every refresh names the business. The
      // two-call routes answer too, so a wizard that still used them would be
      // refreshed into the business after step 1 and leave before step 2.
      final server = _Server({
        'POST $_signUp': (_) => jsonResponse(_founder, 201),
        'GET $_plans': (_) => jsonResponse('{"data":[]}'),
        'POST $_onboard': (_) => jsonResponse(_onboarded, 201),
        'POST $_tenantOnly': (_) =>
            jsonResponse('{"data":{"id":"$_tenantId","name":"Kyoto Market"}}', 201),
        'POST $_storeOnly': (_) =>
            jsonResponse('{"data":{"id":"$_storeId","name":"Kyoto Market Gion"}}', 201),
        'POST $_refresh': (_) => jsonResponse(_owner),
      });
      tester.view.physicalSize = const Size(1400, 5000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      tester.platformDispatcher.defaultRouteNameTestValue = '/start-business';
      addTearDown(tester.platformDispatcher.clearDefaultRouteNameTestValue);
      final dio = Dio(BaseOptions(baseUrl: 'http://test'))
        ..httpClientAdapter = server;
      late GoRouter router;
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            apiClientProvider.overrideWithValue(FakeApiClient(dio)),
            passwordPolicyProvider.overrideWith(
              (ref) async => PasswordPolicy.fallback,
            ),
          ],
          child: Consumer(
            builder: (context, ref, _) {
              router = ref.watch(routerProvider);
              return MaterialApp.router(
                routerConfig: router,
                localizationsDelegates: AppLocalizations.localizationsDelegates,
                supportedLocales: AppLocalizations.supportedLocales,
              );
            },
          ),
        ),
      );
      await tester.pumpAndSettle();

      // Signed up as a business: into the wizard.
      await tester.enterText(
        find.byKey(const Key('business-signup-email')),
        'founder@example.com',
      );
      await tester.enterText(
        find.byKey(const Key('business-signup-password')),
        'a phrase long enough',
      );
      await tester.enterText(
        find.byKey(const Key('business-signup-confirm')),
        'a phrase long enough',
      );
      await tester.tap(find.byKey(const Key('business-signup-submit')));
      await tester.pumpAndSettle();
      expect(router.state.uri.path, '/onboarding');

      // Business details: Continue sends nothing and the wizard stays.
      await _businessDetails(tester);
      expect(router.state.uri.path, '/onboarding');
      expect(find.byType(OnboardingWizard), findsOneWidget);
      expect(find.text('Your first store'), findsOneWidget);
      expect(server.to(_tenantOnly), isEmpty);
      expect(server.to(_onboard), isEmpty);
      expect(server.to(_refresh), isEmpty, reason: 'no business yet to be signed in to');

      // The first store: one call with both, then the refresh, then the dashboard.
      await _firstStore(tester);
      await tester.tap(find.text('Create store & finish setup'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 500));
      await tester.pumpAndSettle();

      final sent = server.to(_onboard);
      expect(sent, hasLength(1));
      expect(sent.single.data, {
        'businessName': 'Kyoto Market',
        'country': 'JP',
        'currency': 'JPY',
        'storeName': 'Kyoto Market Gion',
        'storeCode': 'KY-1',
        'storeType': 'STORE',
        'storeCountry': 'JP',
        'storeTimezone': 'Asia/Tokyo',
      });
      expect(server.to(_tenantOnly), isEmpty);
      expect(server.to(_storeOnly), isEmpty);
      expect(
        server.requests.indexOf(sent.single),
        lessThan(server.requests.indexWhere((r) => r.path == _refresh)),
        reason: 'the token is refreshed only once the business and its store exist',
      );
      expect(router.state.uri.path, '/admin/dashboard');
      final container = ProviderScope.containerOf(
        tester.element(find.byType(MaterialApp)),
      );
      final auth = container.read(authNotifierProvider).value;
      expect(auth, isA<AuthAuthenticated>());
      expect((auth as AuthAuthenticated).tenantId, _tenantId);
      expect(auth.roles, ['OWNER']);
    },
  );

  testWidgets(
    'a slow grant: the business is sent once, the wizard says it is set up and waits, and '
    'pressing again only signs in',
    (tester) async {
      final server = _Server({
        'GET $_plans': (_) => jsonResponse('{"data":[]}'),
        'POST $_onboard': (_) => jsonResponse(_onboarded, 201),
      });
      final auth = _SlowGrant();
      final router = await _openWizard(tester, server, auth);

      await _businessDetails(tester);
      await _firstStore(tester);
      await tester.tap(find.text('Create store & finish setup'));
      // Ten refreshes, 600ms apart, none of them naming the business.
      for (var i = 0; i < 12; i++) {
        await tester.pump(const Duration(milliseconds: 600));
      }
      await tester.pumpAndSettle();

      expect(server.to(_onboard), hasLength(1));
      expect(auth.refreshes, 10);
      expect(
        find.text(
          'Your business and its first store are set up. Signing you in to it is '
          'taking longer than usual — press Finish setup again in a moment.',
        ),
        findsOneWidget,
      );
      expect(find.text('Your first store'), findsOneWidget);
      expect(find.text('Create store & finish setup'), findsNothing);

      // The grant lands; pressing again signs in and never sends the business twice.
      auth.granted = true;
      await tester.tap(find.text('Finish setup'));
      await tester.pump();
      await tester.pumpAndSettle();

      expect(server.to(_onboard), hasLength(1));
      expect(auth.refreshes, 11);
      expect(router.state.uri.path, '/admin/dashboard');
      expect(find.text('dashboard'), findsOneWidget);
    },
  );

  testWidgets(
    'a refusal of the business is said on the store step, and Back leads to the details: nothing '
    'was created, so the next press sends the business and the store again',
    (tester) async {
      var attempts = 0;
      final server = _Server({
        'GET $_plans': (_) => jsonResponse('{"data":[]}'),
        'POST $_onboard': (_) => ++attempts == 1
            ? jsonResponse(_planGone, 409)
            : jsonResponse(_onboarded, 201),
      });
      final auth = _SlowGrant()..granted = true;
      final router = await _openWizard(tester, server, auth);

      await _businessDetails(tester);
      await _firstStore(tester);
      await tester.tap(find.text('Create store & finish setup'));
      await tester.pump();
      await tester.pumpAndSettle();

      expect(find.text('The Growth plan is no longer on sale'), findsOneWidget);
      expect(find.textContaining('PLAN_NOT_SOLD'), findsNothing);
      expect(find.text('Your first store'), findsOneWidget);
      expect(auth.refreshes, 0, reason: 'no business, so no sign-in to wait for');

      // Back to the details, the refusal still said, what was typed still there.
      await tester.tap(find.byKey(const Key('onboarding-back')));
      await tester.pumpAndSettle();
      expect(find.text('Business details'), findsOneWidget);
      expect(find.text('The Growth plan is no longer on sale'), findsOneWidget);
      expect(find.text('Kyoto Market'), findsOneWidget);

      await tester.tap(find.text('Continue'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Create store & finish setup'));
      await tester.pump();
      await tester.pumpAndSettle();

      final sent = server.to(_onboard);
      expect(sent, hasLength(2));
      expect(sent.last.data, sent.first.data, reason: 'the business and its store, again');
      expect(auth.refreshes, 1);
      expect(router.state.uri.path, '/admin/dashboard');
    },
  );
}
