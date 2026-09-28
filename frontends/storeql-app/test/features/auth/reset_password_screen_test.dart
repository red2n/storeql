import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/password_policy.dart';
import 'package:storeql_app/features/auth/reset_password_screen.dart';
import 'package:storeql_app/l10n/gen/app_localizations.dart';

// ---------------------------------------------------------------------------
// intent/password-reset.md: the reset half — the link from the email, good
// for thirty minutes and once, no sign-in needed. Shows the published
// policy's rule before anything is typed, and words every refusal, never a
// raw code.
// ---------------------------------------------------------------------------

class _Answering implements HttpClientAdapter {
  final ResponseBody Function(RequestOptions) answer;
  final List<RequestOptions> requests = [];
  _Answering(this.answer);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    return answer(o);
  }
}

ResponseBody _json(String body, [int status = 200]) => ResponseBody.fromString(
  body,
  status,
  headers: {
    Headers.contentTypeHeader: [Headers.jsonContentType],
  },
);

ResponseBody _refused(String code, [int status = 400]) =>
    _json('{"error":{"code":"$code","message":"refused"}}', status);

Future<_Answering> _pump(
  WidgetTester tester,
  ResponseBody Function(RequestOptions) answer, {
  PasswordPolicy policy = PasswordPolicy.fallback,
  String token = 'a-token',
}) async {
  final adapter = _Answering(answer);
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        publicAuthDioProvider.overrideWithValue(
          Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter,
        ),
        passwordPolicyProvider.overrideWith((ref) async => policy),
      ],
      child: MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: ResetPasswordScreen(token: token),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return adapter;
}

Future<void> _fill(
  WidgetTester tester,
  String password, [
  String? confirm,
]) async {
  await tester.enterText(find.byKey(const Key('reset-new-password')), password);
  await tester.enterText(
    find.byKey(const Key('reset-confirm-password')),
    confirm ?? password,
  );
}

void main() {
  testWidgets(
    "the card is the sign-in card's own width (420), not the wider page form",
    (tester) async {
      await _pump(tester, (_) => _json('{"data":{"reset":true}}'));
      expect(tester.getSize(find.byType(Card).first).width, 420);
    },
  );

  testWidgets(
    'shows the published policy\'s rule before anything is typed — a policy of 12 shows 12',
    (tester) async {
      const policy = PasswordPolicy(
        minLength: 12,
        maxLength: 64,
        breachScreened: true,
        mustNotContainLogin: true,
      );
      await _pump(
        tester,
        (_) => _json('{"data":{"reset":true}}'),
        policy: policy,
      );
      expect(
        find.text(
          'Use at least 12 characters. A phrase of a few words is easiest to remember and hardest to guess; spaces are fine.',
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets('a password shorter than the policy is refused before sending', (
    tester,
  ) async {
    final adapter = await _pump(
      tester,
      (_) => _json('{"data":{"reset":true}}'),
    );
    await _fill(tester, 'fourteen chars');
    await tester.tap(find.byKey(const Key('reset-submit')));
    await tester.pumpAndSettle();
    expect(adapter.requests, isEmpty);
    expect(find.textContaining('Use at least 15 characters'), findsWidgets);
  });

  testWidgets('a confirmation that does not match is refused before sending', (
    tester,
  ) async {
    final adapter = await _pump(
      tester,
      (_) => _json('{"data":{"reset":true}}'),
    );
    await _fill(
      tester,
      'a phrase of several words',
      'a different phrase entirely',
    );
    await tester.tap(find.byKey(const Key('reset-submit')));
    await tester.pumpAndSettle();
    expect(adapter.requests, isEmpty);
    expect(
      find.text('This does not match the new password above.'),
      findsOneWidget,
    );
  });

  testWidgets(
    'success says the password changed and offers the way to sign in',
    (tester) async {
      await _pump(tester, (_) => _json('{"data":{"reset":true}}'));
      await _fill(tester, 'a phrase of several words');
      await tester.tap(find.byKey(const Key('reset-submit')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('reset-done')), findsOneWidget);
      expect(find.text('Password changed — sign in with it.'), findsOneWidget);
      expect(find.byKey(const Key('reset-go-sign-in')), findsOneWidget);
      // A storefront shopper is told they can sign in from the shop too.
      expect(find.textContaining('sign in from the shop'), findsOneWidget);
    },
  );

  testWidgets(
    '400 PASSWORD_RESET_TOKEN_INVALID is worded, with a way to ask for a new link',
    (tester) async {
      await _pump(tester, (_) => _refused('PASSWORD_RESET_TOKEN_INVALID'));
      await _fill(tester, 'a phrase of several words');
      await tester.tap(find.byKey(const Key('reset-submit')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('reset-token-invalid')), findsOneWidget);
      expect(
        find.text(
          'This link has expired or was already used — ask for a new one.',
        ),
        findsOneWidget,
      );
      expect(find.textContaining('PASSWORD_RESET_TOKEN_INVALID'), findsNothing);
      expect(find.byKey(const Key('reset-request-new')), findsOneWidget);
    },
  );

  testWidgets(
    'a refused password does not spend the token — the form stays, for another try',
    (tester) async {
      await _pump(tester, (_) => _refused('PASSWORD_BREACHED'));
      await _fill(tester, 'a phrase of several words');
      await tester.tap(find.byKey(const Key('reset-submit')));
      await tester.pumpAndSettle();
      expect(
        find.text(
          'This password appears in known data breaches and would be guessed. Choose another.',
        ),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('reset-new-password')),
        findsOneWidget,
        reason: 'the form is still here — the same link works again',
      );
    },
  );

  testWidgets('PASSWORD_IS_IDENTITY is worded', (tester) async {
    await _pump(tester, (_) => _refused('PASSWORD_IS_IDENTITY'));
    await _fill(tester, 'a phrase of several words');
    await tester.tap(find.byKey(const Key('reset-submit')));
    await tester.pumpAndSettle();
    expect(
      find.text('The password must not be, or contain, your email address.'),
      findsOneWidget,
    );
  });

  testWidgets('PASSWORD_TOO_LONG is worded with the policy\'s own maximum', (
    tester,
  ) async {
    const policy = PasswordPolicy(
      minLength: 12,
      maxLength: 40,
      breachScreened: true,
      mustNotContainLogin: true,
    );
    await _pump(tester, (_) => _refused('PASSWORD_TOO_LONG'), policy: policy);
    await _fill(tester, 'a' * 30);
    await tester.tap(find.byKey(const Key('reset-submit')));
    await tester.pumpAndSettle();
    expect(find.text('Use at most 40 characters.'), findsOneWidget);
  });

  testWidgets(
    'sends the token from the link and the new password, nothing else',
    (tester) async {
      final adapter = await _pump(
        tester,
        (_) => _json('{"data":{"reset":true}}'),
        token: 'tok-123',
      );
      await _fill(tester, 'a phrase of several words');
      await tester.tap(find.byKey(const Key('reset-submit')));
      await tester.pumpAndSettle();
      final sent = adapter.requests.single;
      expect(sent.path, endsWith('/auth/password/reset'));
      expect(sent.data, {
        'token': 'tok-123',
        'newPassword': 'a phrase of several words',
      });
    },
  );
}
