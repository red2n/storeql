import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/password_policy.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/admin_shell.dart';

// ---------------------------------------------------------------------------
// The signed-in staff member's own change-password dialog: shows the
// published policy's rule before anything is typed, and refuses a password
// that does not meet it before ever calling the API (tested separately from
// the network call, per this repo's own convention). What the call itself
// answers is tested below with a fake ApiClient (no AuthInterceptor, no real
// network): iam-svc's 401 is the current password, its 400 a policy refusal
// of the new one, each worded in its own words rather than one sentence for
// both.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Adapter implements HttpClientAdapter {
  int status = 401;
  String body = '{"error":{"code":"UNAUTHORIZED","message":"bad credentials"}}';

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<List<int>>? stream,
      Future<void>? cancel) async {
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }
}

Future<void> _pump(
  WidgetTester tester, {
  PasswordPolicy policy = PasswordPolicy.fallback,
  ApiClient? apiClient,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        passwordPolicyProvider.overrideWith((ref) async => policy),
        if (apiClient != null) apiClientProvider.overrideWithValue(apiClient),
      ],
      child: const MaterialApp(home: Scaffold(body: ChangePasswordDialog())),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));

  testWidgets(
    'shows the published policy\'s rule before anything is typed — a policy of 12 shows 12',
    (tester) async {
      const policy = PasswordPolicy(
        minLength: 12,
        maxLength: 64,
        breachScreened: true,
        mustNotContainLogin: true,
      );
      await _pump(tester, policy: policy);
      expect(find.byKey(const Key('change-password-new')), findsOneWidget);
      expect(find.textContaining('At least 12 characters'), findsOneWidget);
      expect(find.textContaining('At least 15 characters'), findsNothing);
    },
  );

  testWidgets(
    'falls back to the built-in default (15) when the policy is not overridden',
    (tester) async {
      await _pump(tester);
      expect(find.textContaining('At least 15 characters'), findsOneWidget);
    },
  );

  testWidgets(
    'a new password shorter than the policy is refused before anything is sent',
    (tester) async {
      await _pump(tester);
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Current password'),
        'whatever it was',
      );
      await tester.enterText(
        find.byKey(const Key('change-password-new')),
        'fourteen chars',
      );
      await tester.tap(find.widgetWithText(FilledButton, 'Change'));
      await tester.pumpAndSettle();
      expect(find.textContaining('At least 15 characters'), findsWidgets);
    },
  );

  testWidgets('a new password within the policy passes its own validation', (
    tester,
  ) async {
    await _pump(tester);
    final field = tester.widget<TextFormField>(
      find.byKey(const Key('change-password-new')),
    );
    expect(field.validator!('a phrase of several words'), isNull);
    expect(field.validator!('fourteen chars'), isNotNull);
  });

  testWidgets(
    "401 is worded as the wrong current password; each 400 policy code in "
    "its own words — never one sentence for every refusal",
    (tester) async {
      final adapter = _Adapter();
      final dio = Dio(BaseOptions(baseUrl: 'http://localhost'))
        ..httpClientAdapter = adapter;
      await _pump(tester, apiClient: _FakeApiClient(dio));

      await tester.enterText(
        find.widgetWithText(TextFormField, 'Current password'),
        'whatever it was',
      );
      await tester.enterText(
        find.byKey(const Key('change-password-new')),
        'a phrase of several words',
      );

      Future<void> attempt(int status, String code) async {
        adapter.status = status;
        adapter.body = '{"error":{"code":"$code","message":"server said so"}}';
        await tester.tap(find.widgetWithText(FilledButton, 'Change'));
        await tester.pumpAndSettle();
      }

      // 401: the current password, not a policy refusal of the new one.
      await attempt(401, 'UNAUTHORIZED');
      expect(find.text('Current password is incorrect.'), findsOneWidget);

      // 400 PASSWORD_TOO_SHORT: the same rule the field's own helper text
      // already showed — so it now reads twice, not "incorrect password".
      await attempt(400, 'PASSWORD_TOO_SHORT');
      expect(find.text('Current password is incorrect.'), findsNothing);
      expect(
          find.text(passwordPolicyRefusal(
              'PASSWORD_TOO_SHORT', PasswordPolicy.fallback)!),
          findsWidgets);

      // 400 PASSWORD_TOO_LONG.
      await attempt(400, 'PASSWORD_TOO_LONG');
      expect(
          find.text(passwordPolicyRefusal(
              'PASSWORD_TOO_LONG', PasswordPolicy.fallback)!),
          findsOneWidget);

      // 400 PASSWORD_IS_IDENTITY.
      await attempt(400, 'PASSWORD_IS_IDENTITY');
      expect(
          find.text(passwordPolicyRefusal(
              'PASSWORD_IS_IDENTITY', PasswordPolicy.fallback)!),
          findsOneWidget);

      // 400 PASSWORD_BREACHED.
      await attempt(400, 'PASSWORD_BREACHED');
      expect(
          find.text(passwordPolicyRefusal(
              'PASSWORD_BREACHED', PasswordPolicy.fallback)!),
          findsOneWidget);
    },
  );
}
