import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:storeql_app/core/auth/password_policy.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';
import 'package:storeql_app/features/storefront/storefront_shell.dart';

// ---------------------------------------------------------------------------
// The storefront's own sign-in dialog, like the main login card: signing in
// asks only that a password is typed (a short wrong one is the server's
// "incorrect", never the sign-up rule); creating an account holds a new
// password to iam-svc's fifteen characters before anything is sent.
// ---------------------------------------------------------------------------

class _Recording extends StorefrontAuthNotifier {
  final List<String> calls = [];

  @override
  Future<void> login(String email, String password) async => calls.add('login $email $password');

  @override
  Future<void> register(String email, String password, String? phone) async =>
      calls.add('register $email $password');
}

Future<_Recording> _open(WidgetTester tester, {PasswordPolicy policy = PasswordPolicy.fallback}) async {
  final auth = _Recording();
  await tester.pumpWidget(ProviderScope(
    overrides: [
      storefrontAuthProvider.overrideWith((ref) => auth),
      // Never a real network call in a widget test — it would leave a
      // pending Dio timer behind. Every screen this file pumps reads the
      // same fallback the app itself would, unless a test names another.
      passwordPolicyProvider.overrideWith((ref) async => policy),
    ],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog(context: context, builder: (_) => const StorefrontAuthDialog()),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return auth;
}

Future<void> _type(WidgetTester tester, String email, String password) async {
  await tester.enterText(find.widgetWithText(TextFormField, 'Email'), email);
  await tester.enterText(find.widgetWithText(TextFormField, 'Password'), password);
}

void main() {
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));

  testWidgets('signing in asks only for a password: a short one goes to the server', (tester) async {
    final auth = await _open(tester);
    await _type(tester, 'ana@example.com', '');
    await tester.tap(find.widgetWithText(FilledButton, 'Sign in'));
    await tester.pumpAndSettle();
    expect(auth.calls, isEmpty, reason: 'nothing typed, nothing sent');
    expect(find.text('Enter your password'), findsOneWidget);

    await _type(tester, 'ana@example.com', 'short');
    await tester.tap(find.widgetWithText(FilledButton, 'Sign in'));
    await tester.pumpAndSettle();
    expect(auth.calls, ['login ana@example.com short']);
    expect(find.textContaining('15 characters'), findsNothing);
  });

  testWidgets('creating an account holds the password to fifteen characters before sending', (tester) async {
    final auth = await _open(tester);
    await tester.tap(find.textContaining('Create an account'));
    await tester.pumpAndSettle();
    await _type(tester, 'ana@example.com', 'fourteen chars');
    await tester.enterText(find.widgetWithText(TextFormField, 'Phone number'), '07700900123');
    await tester.tap(find.widgetWithText(FilledButton, 'Create account'));
    await tester.pumpAndSettle();
    expect(auth.calls, isEmpty);
    expect(find.textContaining('At least 15 characters'), findsOneWidget);
  });

  testWidgets('offers a way back for a forgotten password, in sign-in mode only', (tester) async {
    await _open(tester);
    expect(find.byKey(const Key('forgot-password')), findsOneWidget);

    await tester.tap(find.textContaining('Create an account'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('forgot-password')), findsNothing);
  });

  testWidgets(
      "the forgotten-password link names where it came from, so the page can "
      'lead back to the shop rather than to staff sign-in',
      (tester) async {
    String? capturedFrom;
    final router = GoRouter(
      initialLocation: '/store/products',
      routes: [
        GoRoute(
          path: '/store/products',
          builder: (_, _) => Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () => showDialog(
                    context: context, builder: (_) => const StorefrontAuthDialog()),
                child: const Text('open'),
              ),
            ),
          ),
        ),
        GoRoute(
          path: '/forgot-password',
          builder: (_, state) {
            capturedFrom = state.uri.queryParameters['from'];
            return const Text('FORGOT PASSWORD PAGE');
          },
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        storefrontAuthProvider.overrideWith((ref) => _Recording()),
        passwordPolicyProvider.overrideWith((ref) async => PasswordPolicy.fallback),
      ],
      child: MaterialApp.router(routerConfig: router),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('forgot-password')));
    await tester.pumpAndSettle();

    expect(capturedFrom, 'storefront');
    expect(find.text('FORGOT PASSWORD PAGE'), findsOneWidget);
  });
}
