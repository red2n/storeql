import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/password_policy.dart';
import 'package:storeql_app/core/auth/sso.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/auth/login_screen.dart';
import 'package:storeql_app/features/platform/platform_login_screen.dart';
import 'package:storeql_app/l10n/gen/app_localizations.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The two sign-in forms and the one sign-up form. iam-svc's PasswordPolicy asks
// fifteen characters of a new password (400 PASSWORD_TOO_SHORT and its three
// siblings); a sign-in asks only that the password is the right one. So the
// sign-up mirrors the rule and says the policy's words when the server refuses,
// and a sign-in checks only that a password was typed: a short wrong password
// is "Invalid email or password.", never a policy message. The login card is
// translated, so its buttons and tooltips are too.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final Map<String, ResponseBody Function(RequestOptions)> routes;
  final List<RequestOptions> requests = [];

  _Server(this.routes);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final route = routes['${o.method} ${o.path}'];
    return route == null ? jsonResponse('{"error":{"code":"NOT_FOUND","message":"no route"}}', 404) : route(o);
  }

  bool asked(String path) => requests.any((r) => r.path == path);

  int calls(String path) => requests.where((r) => r.path == path).length;
}

class _Browser implements SsoBrowser {
  @override
  bool get supported => true;

  @override
  String get origin => 'http://localhost:8088';

  @override
  Future<void> go(String url) async {}

  @override
  void keepVerifier(String verifier) {}

  @override
  String? takeVerifier() => null;

  @override
  SsoReturn? takeReturn() => null;
}

void _secureStorage() {
  final data = <String, String>{};
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
    const MethodChannel('plugins.it_nomads.com/flutter_secure_storage'),
    (call) async {
      final args = (call.arguments as Map?) ?? const {};
      final key = args['key'] as String? ?? '';
      switch (call.method) {
        case 'read':
          return data[key];
        case 'write':
          data[key] = args['value'] as String;
          return null;
        case 'delete':
          data.remove(key);
          return null;
        default:
          return null;
      }
    },
  );
}

const _iam = '/iam-svc/auth';

ResponseBody _refused(String code, String message, [int status = 400]) =>
    jsonResponse('{"error":{"code":"$code","message":"$message"}}', status);

Future<void> _pump(
  WidgetTester tester,
  _Server server,
  Widget screen, {
  Locale? locale,
  // Every published policy this file's screens read resolves instantly to
  // the app's own built-in default, never a real network call (which would
  // hang the test on a pending Dio timer) — a test after the published
  // policy itself overrides this.
  PasswordPolicy policy = PasswordPolicy.fallback,
}) async {
  tester.view.physicalSize = const Size(1200, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  final container = ProviderContainer(overrides: [
    apiClientProvider.overrideWithValue(FakeApiClient(dio)),
    passwordPolicyProvider.overrideWith((ref) async => policy),
  ]);
  addTearDown(container.dispose);
  await tester.runAsync(() => container.read(authNotifierProvider.future));
  await tester.pumpWidget(UncontrolledProviderScope(
    container: container,
    child: MaterialApp(
      locale: locale,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: screen,
    ),
  ));
  await tester.pumpAndSettle();
}

Future<void> _fill(WidgetTester tester, {required String email, required String password}) async {
  final fields = find.byType(TextFormField);
  await tester.enterText(fields.first, email);
  await tester.enterText(fields.last, password);
}

const _rule15 =
    'Use at least 15 characters. A phrase of a few words is easiest to remember and hardest to guess; spaces are fine.';

void main() {
  setUp(() {
    _secureStorage();
    ssoBrowser = _Browser();
    ssoReturnAtLaunch = null;
  });

  // ── sign in ─────────────────────────────────────────────────────────────────

  testWidgets('signing in asks only that a password is entered: a short one goes to the server', (tester) async {
    final server = _Server({
      'POST $_iam/login': (_) => _refused('INVALID_CREDENTIALS', 'Invalid email or password', 401),
    });
    await _pump(tester, server, const LoginScreen());

    await _fill(tester, email: 'ana@example.com', password: '');
    await tester.tap(find.text('Sign in'));
    await tester.pumpAndSettle();
    expect(find.text('Enter your password'), findsOneWidget);
    expect(server.asked('$_iam/login'), isFalse);

    await _fill(tester, email: 'ana@example.com', password: 'short');
    await tester.tap(find.text('Sign in'));
    await tester.pumpAndSettle();
    expect(server.asked('$_iam/login'), isTrue, reason: 'a sign-in is not held to the sign-up rule');
    expect(find.text('Invalid email or password.'), findsOneWidget);
    expect(find.textContaining('Minimum'), findsNothing);
    expect(find.textContaining('at least'), findsNothing);
  });

  // ── sign up ─────────────────────────────────────────────────────────────────

  testWidgets('a new password is held to iam-svc\'s fifteen characters before anything is sent', (tester) async {
    final server = _Server({});
    await _pump(tester, server, const LoginScreen());
    await tester.tap(find.text('New here? Create an account'));
    await tester.pumpAndSettle();

    await _fill(tester, email: 'ana@example.com', password: 'fourteen chars');
    await tester.tap(find.text('Create account'));
    await tester.pumpAndSettle();
    expect(find.text(_rule15), findsOneWidget);
    expect(server.asked('$_iam/register'), isFalse);
    expect(find.text('Minimum 8 characters'), findsNothing);
  });

  testWidgets('a password the policy refuses is said in the policy\'s words, never "Something went wrong"', (
    tester,
  ) async {
    var answer = _refused('PASSWORD_BREACHED', 'this password appears in known data breaches and would be guessed; choose another');
    final server = _Server({'POST $_iam/register': (_) => answer});
    await _pump(tester, server, const LoginScreen());
    await tester.tap(find.text('New here? Create an account'));
    await tester.pumpAndSettle();

    await _fill(tester, email: 'ana@example.com', password: 'correct horse battery');
    await tester.tap(find.text('Create account'));
    await tester.pumpAndSettle();
    expect(server.asked('$_iam/register'), isTrue);
    expect(find.text('This password appears in known data breaches and would be guessed. Choose another.'), findsOneWidget);
    expect(find.text('Something went wrong. Please try again.'), findsNothing);

    answer = _refused('PASSWORD_IS_IDENTITY', 'the password must not be, or contain, your login');
    await tester.tap(find.text('Create account'));
    await tester.pumpAndSettle();
    expect(find.text('The password must not be, or contain, your email address.'), findsOneWidget);

    // The app already knows the published policy's minimum (fetched, never
    // learned from a refusal): said in that rule's own words, whatever number
    // the server's free text happens to use.
    answer = _refused('PASSWORD_TOO_SHORT', 'use at least 20 characters — a phrase of a few words is easiest to remember');
    await tester.tap(find.text('Create account'));
    await tester.pumpAndSettle();
    expect(find.text(_rule15), findsNWidgets(2),
        reason: 'the refusal and the helper agree — both are the published policy\'s');

    answer = _refused('PASSWORD_TOO_LONG', 'use at most 128 characters');
    await tester.tap(find.text('Create account'));
    await tester.pumpAndSettle();
    expect(find.text('Use at most 128 characters.'), findsOneWidget);
  });

  testWidgets('the sign-up form takes the published policy at its word from the start — a policy of '
      '12 shows 12', (tester) async {
    final server = _Server({
      'POST $_iam/register': (_) => _refused('PASSWORD_TOO_SHORT', 'use at least 12 characters'),
    });
    const policy = PasswordPolicy(
        minLength: 12, maxLength: 64, breachScreened: true, mustNotContainLogin: true);
    await _pump(tester, server, const LoginScreen(), policy: policy);
    await tester.tap(find.text('New here? Create an account'));
    await tester.pumpAndSettle();
    const rule12 =
        'Use at least 12 characters. A phrase of a few words is easiest to remember and hardest to guess; spaces are fine.';
    expect(find.text(rule12), findsOneWidget,
        reason: 'the published rule, before anything is typed or sent — never this app\'s own default');
    expect(find.text(_rule15), findsNothing);

    // Eleven characters is refused here, not sent — the field's own error
    // replaces its helper text (Flutter shows one or the other), so it is
    // said once, not twice, but never reaches the server at all.
    await _fill(tester, email: 'ana@example.com', password: 'eleven char');
    await tester.tap(find.text('Create account'));
    await tester.pumpAndSettle();
    expect(server.asked('$_iam/register'), isFalse);
    expect(find.text(rule12), findsOneWidget);

    // Twelve is enough, and is sent.
    await _fill(tester, email: 'ana@example.com', password: 'twelve chars');
    await tester.tap(find.text('Create account'));
    await tester.pumpAndSettle();
    expect(server.asked('$_iam/register'), isTrue);
  });

  testWidgets('the sign-in card offers a way back for a forgotten password; the platform console does not',
      (tester) async {
    await _pump(tester, _Server({}), const LoginScreen());
    expect(find.byKey(const Key('forgot-password')), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await _pump(tester, _Server({}), const PlatformLoginScreen());
    expect(find.byKey(const Key('forgot-password')), findsNothing);
  });

  // ── translated ──────────────────────────────────────────────────────────────

  testWidgets('a Polish card is all Polish: the business button, the tooltips and Continue with …', (tester) async {
    final server = _Server({
      'POST $_iam/login': (_) => jsonResponse(
          '{"error":{"code":"SSO_REQUIRED","message":"signs in through its provider","details":["slug=acme-foods"]}}',
          403),
    });
    await _pump(tester, server, const LoginScreen(), locale: const Locale('pl'));

    expect(find.text('Zaloguj się przez swoją firmę'), findsOneWidget);
    expect(find.text('Sign in with your business'), findsNothing);
    expect(find.byTooltip('Pokaż hasło'), findsOneWidget);
    await tester.tap(find.byTooltip('Pokaż hasło'));
    await tester.pump();
    expect(find.byTooltip('Ukryj hasło'), findsOneWidget);

    await _fill(tester, email: 'kasjer@example.com', password: 'jakieś hasło');
    await tester.tap(find.text('Zaloguj się').first);
    await tester.pumpAndSettle();
    expect(find.text('Kontynuuj przez acme-foods'), findsOneWidget);
    expect(find.textContaining('Continue with'), findsNothing);
    // Why the password was not enough, in Polish too.
    expect(find.textContaining('Twoja firma loguje Cię przez własną stronę logowania'), findsOneWidget);
    expect(find.textContaining('signs you in through'), findsNothing);
  });

  testWidgets('a single sign-on refusal on a Polish card is in Polish', (tester) async {
    final server = _Server({
      'POST $_iam/login': (_) => jsonResponse(
          '{"error":{"code":"TENANT_INACTIVE","message":"tenant is suspended"}}', 403),
    });
    await _pump(tester, server, const LoginScreen(), locale: const Locale('pl'));
    await _fill(tester, email: 'kasjer@example.com', password: 'jakieś hasło');
    await tester.tap(find.text('Zaloguj się').first);
    await tester.pumpAndSettle();
    expect(find.text('Konto tej firmy jest zawieszone. Skontaktuj się z pomocą techniczną.'), findsOneWidget);
    expect(find.textContaining('suspended'), findsNothing);
  });

  testWidgets('the business sign-in dialog is translated too', (tester) async {
    await _pump(tester, _Server({}), const LoginScreen(), locale: const Locale('pl'));
    await tester.tap(find.byKey(const Key('sso-start')));
    await tester.pumpAndSettle();
    expect(find.text('Nazwa logowania'), findsOneWidget);
    expect(find.text('Anuluj'), findsOneWidget);
    expect(find.text('Kontynuuj'), findsOneWidget);
    expect(find.text('Cancel'), findsNothing);
  });

  // ── the platform console ────────────────────────────────────────────────────

  testWidgets('the platform console asks only that a password is entered; a short wrong one is refused as wrong', (
    tester,
  ) async {
    final server = _Server({
      'POST $_iam/platform-login': (_) => _refused('INVALID_CREDENTIALS', 'Invalid email or password', 401),
    });
    await _pump(tester, server, const PlatformLoginScreen());

    await _fill(tester, email: 'root@storeql.com', password: '');
    await tester.tap(find.text('Sign in'));
    await tester.pumpAndSettle();
    expect(find.text('Enter your password'), findsOneWidget);
    expect(server.asked('$_iam/platform-login'), isFalse);

    await _fill(tester, email: 'root@storeql.com', password: 'short');
    await tester.tap(find.text('Sign in'));
    await tester.pumpAndSettle();
    expect(server.asked('$_iam/platform-login'), isTrue);
    expect(find.text('Invalid email or password.'), findsOneWidget);
    expect(find.text('Minimum 8 characters'), findsNothing);
  });
}
