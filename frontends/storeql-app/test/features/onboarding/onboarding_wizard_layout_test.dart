import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/onboarding/onboarding_wizard.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The set-up wizard a new login is routed to (the design system's SignIn /
// Onboarding card). A login that ends up here by mistake can leave it: the
// wizard signs out. Its way on is "Continue" with an arrow that turns round in
// Urdu and Arabic, not a "→" baked into the words. And its fields line up: no
// field carries a leading icon the country and currency dropdowns do not.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    if (o.method == 'GET' && o.path.endsWith('/plans')) {
      return jsonResponse(jsonEncode({
        'data': [
          {
            'id': '019987a0-0f1e-7c3b-8a4d-3e2f1a0b9c81',
            'code': 'STARTER',
            'name': 'Starter',
            'billingInterval': 'MONTH',
            'trialDays': 14,
            'isDefault': true,
            'prices': [
              {'currency': 'GBP', 'amount': 49},
            ],
            'includes': [],
          },
        ],
      }));
    }
    return jsonResponse('{"data":{}}', 201);
  }
}

/// A signed-in login with no business yet, counting its sign-outs.
class _NewLogin extends AuthNotifier {
  int signedOut = 0;

  @override
  Future<AuthState> build() async => const AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'u',
        roles: [],
      );

  @override
  Future<void> logout() async {
    signedOut++;
    state = const AsyncValue.data(AuthUnauthenticated());
  }
}

Future<_NewLogin> _pump(WidgetTester tester, {TextDirection direction = TextDirection.ltr}) async {
  tester.view.physicalSize = const Size(1200, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final auth = _NewLogin();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Server();
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => auth),
    ],
    child: MaterialApp(
      theme: AppTheme.light,
      builder: (context, child) => Directionality(textDirection: direction, child: child!),
      home: const OnboardingWizard(),
    ),
  ));
  await tester.pumpAndSettle();
  return auth;
}

void main() {
  testWidgets('a login routed to the wizard can sign out of it', (tester) async {
    final auth = await _pump(tester);

    final signOut = find.text('Sign out');
    expect(signOut, findsOneWidget);
    await tester.tap(signOut);
    await tester.pumpAndSettle();
    expect(auth.signedOut, 1);
  });

  testWidgets('Continue carries a real arrow icon, not an arrow in its words', (tester) async {
    await _pump(tester);

    expect(find.textContaining('→'), findsNothing);
    final button = find.ancestor(of: find.text('Continue'), matching: find.byWidgetPredicate((w) => w is FilledButton));
    expect(button, findsOneWidget);
    expect(find.descendant(of: button, matching: find.byIcon(Icons.arrow_forward)), findsOneWidget);
    expect(Icons.arrow_forward.matchTextDirection, isTrue, reason: 'the arrow turns round in a right-to-left language');
    expect(
      tester.getCenter(find.byIcon(Icons.arrow_forward)).dx,
      greaterThan(tester.getCenter(find.text('Continue')).dx),
      reason: 'the arrow follows the word',
    );
  });

  testWidgets('in a right-to-left language the arrow follows the word on its left', (tester) async {
    await _pump(tester, direction: TextDirection.rtl);

    expect(
      tester.getCenter(find.byIcon(Icons.arrow_forward)).dx,
      lessThan(tester.getCenter(find.text('Continue')).dx),
    );
  });

  testWidgets('the business\'s four fields start their labels in one line', (tester) async {
    await _pump(tester);

    final starts = [
      for (final label in ['Business name *', 'Legal / registered name (optional)', 'Country *', 'Currency *'])
        tester.getTopLeft(find.text(label)).dx,
    ];
    expect(starts.toSet(), hasLength(1), reason: 'labels start at $starts');
  });
}
