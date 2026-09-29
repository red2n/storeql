import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/auth/password_policy.dart';
import 'package:storeql_app/core/auth/sso.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/router.dart';
import 'package:storeql_app/features/auth/business_sign_up_screen.dart';
import 'package:storeql_app/features/auth/login_screen.dart';
import 'package:storeql_app/features/onboarding/onboarding_wizard.dart';
import 'package:storeql_app/features/platform/platform_login_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';
import 'package:storeql_app/features/storefront/storefront_shell.dart';
import 'package:storeql_app/l10n/gen/app_localizations.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// "Start a business" (29 Sep 2026). The sign-in card's "Create account" makes
// a shopper, and a shopper never reaches the setup wizard, so a person setting
// a business up could not get there from signing up. The business sign-up is
// its own page: it posts to iam-svc's POST /auth/register/business, whose
// token names no business and no role, and a login like that is routed
// straight to the wizard. The storefront's own sign-up never offers it.
// ---------------------------------------------------------------------------

const _business = '/iam-svc/auth/register/business';
const _shopper = '/iam-svc/auth/register';

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

  bool asked(String path) => requests.any((r) => r.path == path);
}

ResponseBody _refused(String code, String message, [int status = 400]) =>
    jsonResponse('{"error":{"code":"$code","message":"$message"}}', status);

/// An access token as iam-svc mints one for a business sign-up: a staff login
/// with no tenant claim and an empty role list. Only its payload is read here.
String _token(Map<String, Object?> claims) {
  String part(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  return '${part({'alg': 'RS256', 'kid': 'k'})}.${part(claims)}.signature';
}

final _signedUp = jsonEncode({
  'data': {
    'accessToken': _token({
      'sub': '019987a0-0f1e-7c3b-8a4d-3e2f1a0b9c81',
      'type': 'STAFF',
      'roles': <String>[],
      'email': 'founder@example.com',
      'amr': ['pwd'],
    }),
    'refreshToken': 'refresh-1',
    'tokenType': 'Bearer',
    'expiresInSeconds': 900,
  },
});

/// The app itself, opened at [link] with nobody signed in: the real router
/// and the real sign-in state, answered by [server].
Future<GoRouter> _openApp(
  WidgetTester tester,
  String link,
  _Server server, {
  PasswordPolicy policy = PasswordPolicy.fallback,
}) async {
  tester.view.physicalSize = const Size(1200, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  tester.platformDispatcher.defaultRouteNameTestValue = link;
  addTearDown(tester.platformDispatcher.clearDefaultRouteNameTestValue);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  late GoRouter router;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        passwordPolicyProvider.overrideWith((ref) async => policy),
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
  return router;
}

/// One screen on its own, with the same stand-in server.
Future<void> _openScreen(
  WidgetTester tester,
  Widget screen,
  _Server server, {
  PasswordPolicy policy = PasswordPolicy.fallback,
  Locale? locale,
}) async {
  tester.view.physicalSize = const Size(1200, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        passwordPolicyProvider.overrideWith((ref) async => policy),
      ],
      child: MaterialApp(
        locale: locale,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: screen,
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> _fill(
  WidgetTester tester, {
  required String email,
  required String password,
  String? confirm,
}) async {
  await tester.enterText(find.byKey(const Key('business-signup-email')), email);
  await tester.enterText(
    find.byKey(const Key('business-signup-password')),
    password,
  );
  await tester.enterText(
    find.byKey(const Key('business-signup-confirm')),
    confirm ?? password,
  );
}

Future<void> _submit(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('business-signup-submit')));
  await tester.pumpAndSettle();
}

const _rule15 =
    'Use at least 15 characters. A phrase of a few words is easiest to remember and hardest to guess; spaces are fine.';

void main() {
  setUp(() {
    FlutterSecureStorage.setMockInitialValues({});
    ssoReturnAtLaunch = null;
  });

  // ── the entry on the sign-in card ───────────────────────────────────────────

  testWidgets(
    'the sign-in card offers "Start a business"; the platform console does not',
    (tester) async {
      await _openScreen(tester, const LoginScreen(), _Server({}));
      expect(find.byKey(const Key('start-business')), findsOneWidget);
      expect(find.text('Start a business'), findsOneWidget);
      // Still there once the card is turned to the shopper's sign-up.
      await tester.tap(find.text('New here? Create an account'));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('start-business')), findsOneWidget);

      await tester.pumpWidget(const SizedBox());
      await _openScreen(tester, const PlatformLoginScreen(), _Server({}));
      expect(find.byKey(const Key('start-business')), findsNothing);
      expect(find.text('Start a business'), findsNothing);
    },
  );

  testWidgets('a Polish card offers it in Polish', (tester) async {
    await _openScreen(
      tester,
      const LoginScreen(),
      _Server({}),
      locale: const Locale('pl'),
    );
    expect(find.text('Załóż firmę'), findsOneWidget);
    expect(find.text('Start a business'), findsNothing);
  });

  testWidgets('the entry opens the business sign-up, with nobody signed in', (
    tester,
  ) async {
    final router = await _openApp(tester, '/login', _Server({}));
    expect(find.byType(LoginScreen), findsOneWidget);

    await tester.tap(find.byKey(const Key('start-business')));
    await tester.pumpAndSettle();
    expect(router.state.uri.path, '/start-business');
    expect(find.byType(BusinessSignUpScreen), findsOneWidget);
    expect(find.text('Start your business'), findsOneWidget);

    // And back to signing in.
    await tester.tap(find.byKey(const Key('business-signup-sign-in')));
    await tester.pumpAndSettle();
    expect(router.state.uri.path, '/login');
  });

  // ── the form ────────────────────────────────────────────────────────────────

  testWidgets(
    'the published rule is shown before typing; a short password or a different confirmation '
    'is refused before anything is sent',
    (tester) async {
      final server = _Server({});
      const policy = PasswordPolicy(
        minLength: 12,
        maxLength: 64,
        breachScreened: true,
        mustNotContainLogin: true,
      );
      await _openScreen(
        tester,
        const BusinessSignUpScreen(),
        server,
        policy: policy,
      );
      const rule12 =
          'Use at least 12 characters. A phrase of a few words is easiest to remember and hardest to guess; spaces are fine.';
      expect(find.text(rule12), findsOneWidget);
      expect(find.text(_rule15), findsNothing);

      await _fill(tester, email: 'founder@example.com', password: 'eleven char');
      await _submit(tester);
      expect(server.asked(_business), isFalse);
      expect(find.text(rule12), findsOneWidget);

      await _fill(
        tester,
        email: 'founder@example.com',
        password: 'twelve chars',
        confirm: 'twelve charz',
      );
      await _submit(tester);
      expect(server.asked(_business), isFalse);
      expect(find.text('This does not match the password above.'), findsOneWidget);
    },
  );

  testWidgets(
    "posts the email and password to the business sign-up — never the shopper's",
    (tester) async {
      final server = _Server({
        'POST $_business': (_) => jsonResponse(_signedUp, 201),
        'GET /tenant-svc/plans': (_) => jsonResponse('{"data":[]}'),
      });
      await _openScreen(tester, const BusinessSignUpScreen(), server);
      await _fill(
        tester,
        email: '  founder@example.com ',
        password: 'a phrase long enough',
      );
      await _submit(tester);

      final sent = server.requests.where((r) => r.path == _business).toList();
      expect(sent, hasLength(1));
      expect(sent.single.method, 'POST');
      expect(sent.single.data, {
        'email': 'founder@example.com',
        'password': 'a phrase long enough',
      });
      expect(server.asked(_shopper), isFalse);
    },
  );

  testWidgets('a refusal is said in words, never as its code', (tester) async {
    var answer = _refused('USER_ALREADY_EXISTS', 'Email or phone already registered', 409);
    final server = _Server({'POST $_business': (_) => answer});
    await _openScreen(tester, const BusinessSignUpScreen(), server);
    await _fill(tester, email: 'founder@example.com', password: 'a phrase long enough');

    await _submit(tester);
    expect(find.text('An account with this email already exists.'), findsOneWidget);
    expect(find.textContaining('USER_ALREADY_EXISTS'), findsNothing);

    answer = _refused(
      'PASSWORD_BREACHED',
      'this password appears in known data breaches and would be guessed; choose another',
    );
    await _submit(tester);
    expect(
      find.text('This password appears in known data breaches and would be guessed. Choose another.'),
      findsOneWidget,
    );

    answer = _refused('PASSWORD_IS_IDENTITY', 'the password must not be, or contain, your login');
    await _submit(tester);
    expect(find.text('The password must not be, or contain, your email address.'), findsOneWidget);

    // The published minimum, in the rule's own words, whatever number the
    // server's free text happens to use.
    answer = _refused('PASSWORD_TOO_SHORT', 'use at least 20 characters');
    await _submit(tester);
    expect(find.text(_rule15), findsNWidgets(2), reason: 'the refusal and the helper agree');
    expect(find.textContaining('PASSWORD_'), findsNothing);
    expect(find.text('Something went wrong. Please try again.'), findsNothing);
  });

  // ── signed in, into the wizard ──────────────────────────────────────────────

  testWidgets(
    'a successful sign-up is signed in with no business and lands in the setup wizard',
    (tester) async {
      final server = _Server({
        'POST $_business': (_) => jsonResponse(_signedUp, 201),
        'GET /tenant-svc/plans': (_) => jsonResponse('{"data":[]}'),
      });
      final router = await _openApp(tester, '/start-business', server);
      expect(find.byType(BusinessSignUpScreen), findsOneWidget);

      await _fill(tester, email: 'founder@example.com', password: 'a phrase long enough');
      await _submit(tester);

      expect(server.asked(_business), isTrue);
      expect(router.state.uri.path, '/onboarding');
      expect(find.byType(OnboardingWizard), findsOneWidget);
      final container = ProviderScope.containerOf(
        tester.element(find.byType(OnboardingWizard)),
      );
      final auth = container.read(authNotifierProvider).value;
      expect(auth, isA<AuthAuthenticated>());
      expect((auth as AuthAuthenticated).tenantId, isNull);
      expect(auth.roles, isEmpty);
      expect(auth.needsOnboarding, isTrue);
    },
  );

  // ── not in the shop ─────────────────────────────────────────────────────────

  testWidgets("the storefront's own sign-in and sign-up never offer it", (
    tester,
  ) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          storefrontAuthProvider.overrideWith((ref) => StorefrontAuthNotifier()),
          passwordPolicyProvider.overrideWith(
            (ref) async => PasswordPolicy.fallback,
          ),
        ],
        child: MaterialApp(
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () => showDialog(
                  context: context,
                  builder: (_) => const StorefrontAuthDialog(),
                ),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('start-business')), findsNothing);
    expect(find.textContaining('business'), findsNothing);

    await tester.tap(find.textContaining('Create an account'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('start-business')), findsNothing);
    expect(find.textContaining('business'), findsNothing);
  });
}
