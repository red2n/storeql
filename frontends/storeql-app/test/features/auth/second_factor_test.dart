import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/auth/passkeys.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/mfa_policy_dialog.dart';
import 'package:storeql_app/features/auth/second_factor_screen.dart';
import 'package:storeql_app/features/auth/second_factor_setup_screen.dart';
import 'package:storeql_app/features/auth/security_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The second step of signing in (20.12). A right password is no longer the whole
// of it for a login that holds a second factor: the app goes to the step, keeps
// nothing until the step is answered, says so when an answer is wrong, and goes
// back to the password when the wait has ended. A login that must have a factor
// and has none sets one up with a token good for nothing else, and is not let
// past its recovery codes until it says they are kept.
// ---------------------------------------------------------------------------

/// A stand-in iam-svc: answers by path, remembers what it was sent.
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

  RequestOptions last(String path) => requests.lastWhere((r) => r.path == path);
}

/// flutter_secure_storage, in memory.
Map<String, String> _secureStorage() {
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
        case 'deleteAll':
          data.clear();
          return null;
        default:
          return null;
      }
    },
  );
  return data;
}

String _jwt(Map<String, dynamic> claims) {
  String part(Object o) => base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  return '${part({'alg': 'RS256'})}.${part(claims)}.sig';
}

final _session = {
  'accessToken': _jwt({'sub': 'u-1', 'tenant': 't-1', 'roles': ['CASHIER'], 'amr': ['pwd', 'otp']}),
  'refreshToken': 'refresh-1',
  'tokenType': 'Bearer',
  'expiresInSeconds': 900,
};

const _iam = '/iam-svc/auth';

class _FakePasskeys implements Passkeys {
  @override
  final bool supported;
  int asked = 0;

  _FakePasskeys({this.supported = true});

  @override
  Future<Map<String, String>> create(Map<String, dynamic> options) async =>
      {'clientDataJson': 'Y2xpZW50', 'attestationObject': 'YXR0'};

  @override
  Future<Map<String, String>> get(Map<String, dynamic> options) async {
    asked++;
    return {'credentialId': 'a2V5', 'clientDataJson': 'Y2xpZW50', 'authenticatorData': 'YXV0aA', 'signature': 'c2ln'};
  }
}

Future<ProviderContainer> _pump(WidgetTester tester, _Server server, Widget screen) async {
  tester.view.physicalSize = const Size(1200, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  final container = ProviderContainer(overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))]);
  addTearDown(container.dispose);
  // Real futures (the storage channel, Dio) need the real event loop, not the test's fake one.
  await tester.runAsync(() => container.read(authNotifierProvider.future));
  await tester.pumpWidget(UncontrolledProviderScope(container: container, child: MaterialApp(home: screen)));
  return container;
}

Future<void> _signIn(WidgetTester tester, ProviderContainer container) => tester.runAsync(
    () => container.read(authNotifierProvider.notifier).login('ana@example.com', 'a phrase long enough'));

void main() {
  late Map<String, String> stored;
  final realPasskeys = passkeys;

  setUpAll(initializeDateFormatting);
  setUp(() {
    TestWidgetsFlutterBinding.ensureInitialized();
    stored = _secureStorage();
  });
  tearDown(() => passkeys = realPasskeys);

  ResponseBody owed(RequestOptions _) => jsonResponse(
      '{"data":{"mfaRequired":true,"mfaToken":"wait-1","mfaMethods":["TOTP","RECOVERY_CODE"]}}');

  testWidgets('a right password leads to the second step, and nothing is kept until it is answered', (tester) async {
    final server = _Server({
      'POST $_iam/login': owed,
      'POST $_iam/mfa/login': (o) => (o.data as Map)['code'] == '123456'
          ? jsonResponse(jsonEncode({'data': _session}))
          : jsonResponse('{"error":{"code":"MFA_CODE_INVALID","message":"That did not match"}}', 401),
    });
    final container = await _pump(tester, server, const SecondFactorScreen());
    await _signIn(tester, container);
    await tester.pumpAndSettle();

    expect(container.read(authNotifierProvider).value, isA<AuthSecondFactorOwed>());
    expect(stored, isEmpty, reason: 'no token exists yet, so none is stored');
    expect(find.text('One more step'), findsOneWidget);
    expect(find.byKey(const Key('mfa-passkey')), findsNothing, reason: 'this login has no passkey');

    await tester.enterText(find.byKey(const Key('mfa-code')), '000 000');
    await tester.tap(find.byKey(const Key('mfa-submit')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('mfa-error')), findsOneWidget);
    expect(find.textContaining('did not match'), findsOneWidget);
    expect(container.read(authNotifierProvider).value, isA<AuthSecondFactorOwed>(), reason: 'the wait stays open');
    expect((server.last('$_iam/mfa/login').data as Map)['method'], 'TOTP');
    expect((server.last('$_iam/mfa/login').data as Map)['mfaToken'], 'wait-1');

    await tester.enterText(find.byKey(const Key('mfa-code')), '123456');
    await tester.tap(find.byKey(const Key('mfa-submit')));
    await tester.pumpAndSettle();
    final auth = container.read(authNotifierProvider).value;
    expect(auth, isA<AuthAuthenticated>());
    expect((auth as AuthAuthenticated).roles, ['CASHIER']);
    expect(stored.values, contains('refresh-1'));
  });

  testWidgets('a lost phone: the recovery code is one tap away and goes as a recovery code', (tester) async {
    final server = _Server({
      'POST $_iam/login': owed,
      'POST $_iam/mfa/login': (_) => jsonResponse(jsonEncode({'data': _session})),
    });
    final container = await _pump(tester, server, const SecondFactorScreen());
    await _signIn(tester, container);
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('mfa-switch')));
    await tester.pumpAndSettle();
    expect(find.text('Recovery code'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('mfa-code')), 'abcd-efgh-jkmn');
    await tester.tap(find.byKey(const Key('mfa-submit')));
    await tester.pumpAndSettle();
    expect((server.last('$_iam/mfa/login').data as Map)['method'], 'RECOVERY_CODE');
    expect((server.last('$_iam/mfa/login').data as Map)['code'], 'abcd-efgh-jkmn');
  });

  testWidgets('when the wait has ended it is the password again; giving up is too', (tester) async {
    final server = _Server({
      'POST $_iam/login': owed,
      'POST $_iam/mfa/login': (_) =>
          jsonResponse('{"error":{"code":"MFA_CHALLENGE_EXPIRED","message":"Sign in again"}}', 401),
    });
    final container = await _pump(tester, server, const SecondFactorScreen());
    await _signIn(tester, container);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('mfa-code')), '123456');
    await tester.tap(find.byKey(const Key('mfa-submit')));
    await tester.pumpAndSettle();
    expect(container.read(authNotifierProvider).value, isA<AuthUnauthenticated>());

    await _signIn(tester, container);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mfa-cancel')));
    await tester.pumpAndSettle();
    expect(container.read(authNotifierProvider).value, isA<AuthUnauthenticated>());
    expect(stored, isEmpty);
  });

  testWidgets('a passkey is offered where the device can use one, and answers the server\'s challenge', (tester) async {
    final fake = _FakePasskeys();
    passkeys = fake;
    final server = _Server({
      'POST $_iam/login': (_) =>
          jsonResponse('{"data":{"mfaRequired":true,"mfaToken":"wait-2","mfaMethods":["PASSKEY","RECOVERY_CODE"]}}'),
      'POST $_iam/mfa/login/passkey-options': (_) => jsonResponse(
          '{"data":{"challenge":"Y2hhbGxlbmdl","rpId":"localhost","allowCredentials":["a2V5"],"userVerification":"required","timeoutMillis":120000}}'),
      'POST $_iam/mfa/login': (_) => jsonResponse(jsonEncode({'data': _session})),
    });
    final container = await _pump(tester, server, const SecondFactorScreen());
    await _signIn(tester, container);
    await tester.pumpAndSettle();

    expect(find.text('Recovery code'), findsOneWidget, reason: 'no app to ask a code of: the recovery code is the field');
    await tester.tap(find.byKey(const Key('mfa-passkey')));
    await tester.pumpAndSettle();
    expect(fake.asked, 1);
    expect((server.last('$_iam/mfa/login/passkey-options').data as Map)['mfaToken'], 'wait-2');
    final sent = server.last('$_iam/mfa/login').data as Map;
    expect(sent['method'], 'PASSKEY');
    expect((sent['assertion'] as Map)['credentialId'], 'a2V5');
    expect(container.read(authNotifierProvider).value, isA<AuthAuthenticated>());
  });

  testWidgets('where the device cannot use a passkey the button is not there', (tester) async {
    passkeys = _FakePasskeys(supported: false);
    final server = _Server({
      'POST $_iam/login': (_) =>
          jsonResponse('{"data":{"mfaRequired":true,"mfaToken":"wait-3","mfaMethods":["PASSKEY","RECOVERY_CODE"]}}'),
    });
    final container = await _pump(tester, server, const SecondFactorScreen());
    await _signIn(tester, container);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('mfa-passkey')), findsNothing);
    expect(find.text('Recovery code'), findsOneWidget);
  });

  testWidgets('a login that must have a second step sets one up with a token good for nothing else', (tester) async {
    passkeys = _FakePasskeys(supported: false);
    final enrolToken = _jwt({'sub': 'u-1', 'roles': <String>[], 'scope': 'mfa-enrol'});
    final codes = [for (var i = 0; i < 10; i++) 'AAAA-BBBB-CCC$i'];
    final server = _Server({
      'POST $_iam/login': (_) => jsonResponse(jsonEncode({
            'data': {'accessToken': enrolToken, 'tokenType': 'Bearer', 'expiresInSeconds': 600, 'mfaEnrolmentRequired': true}
          })),
      'POST $_iam/mfa/totp': (_) => jsonResponse(
          '{"data":{"secret":"GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ","otpauthUri":"otpauth://totp/StoreQL:ana?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=StoreQL"}}'),
      'POST $_iam/mfa/totp/confirm': (o) => (o.data as Map)['code'] == '654321'
          ? jsonResponse(jsonEncode({'data': {'recoveryCodes': codes, 'tokens': _session}}))
          : jsonResponse('{"error":{"code":"MFA_CODE_INVALID","message":"no"}}', 400),
    });
    final container = await _pump(tester, server, const SecondFactorSetupScreen());
    await _signIn(tester, container);
    await tester.pumpAndSettle();

    expect(container.read(authNotifierProvider).value, isA<AuthEnrolmentOwed>());
    expect(stored, isEmpty, reason: 'the enrolment token is held in memory, never stored');
    expect(find.text('Set up a second step'), findsOneWidget);
    expect(find.textContaining('Your business asks'), findsOneWidget);
    expect(find.text('GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ'), findsOneWidget);
    expect(server.last('$_iam/mfa/totp').headers['Authorization'], 'Bearer $enrolToken');
    expect(find.byKey(const Key('setup-switch')), findsNothing, reason: 'no passkeys on this device');

    await tester.enterText(find.byKey(const Key('mfa-code')), '111111');
    await tester.tap(find.byKey(const Key('totp-confirm')));
    await tester.pumpAndSettle();
    expect(find.textContaining('did not match'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('mfa-code')), '654321');
    await tester.tap(find.byKey(const Key('totp-confirm')));
    await tester.pumpAndSettle();
    expect(find.text('Keep these recovery codes'), findsOneWidget);
    expect(find.text('AAAA-BBBB-CCC7'), findsOneWidget);
    expect(container.read(authNotifierProvider).value, isA<AuthEnrolmentOwed>(), reason: 'not in until the codes are kept');
    expect(tester.widget<FilledButton>(find.byKey(const Key('recovery-done'))).onPressed, isNull);

    await tester.tap(find.byKey(const Key('recovery-kept')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('recovery-done')));
    await tester.pumpAndSettle();
    expect(container.read(authNotifierProvider).value, isA<AuthAuthenticated>());
    expect(stored.values, contains('refresh-1'));
  });

  testWidgets('sign-in security: what is set up, what is required, and removal asks for the password', (tester) async {
    passkeys = _FakePasskeys(supported: false);
    var removed = false;
    final server = _Server({
      'GET $_iam/mfa': (_) => jsonResponse(jsonEncode({
            'data': {
              'totp': !removed,
              'passkeys': [
                {'id': 'p-1', 'name': 'Office laptop', 'createdAt': '2026-09-01T10:00:00Z', 'lastUsedAt': '2026-09-16T08:00:00Z'}
              ],
              'recoveryCodesLeft': 7,
              'required': true,
            }
          })),
      'POST $_iam/mfa/totp/remove': (o) {
        if ((o.data as Map)['password'] != 'a phrase long enough') {
          return jsonResponse('{"error":{"code":"INVALID_CREDENTIALS","message":"no"}}', 401);
        }
        removed = true;
        return jsonResponse('{"data":"removed"}');
      },
    });
    await _pump(tester, server, const SecurityScreen());
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('mfa-required')), findsOneWidget);
    expect(find.text('Office laptop'), findsOneWidget);
    // A date as a person reads it (the design system's SignIn card), not the ISO string.
    expect(find.text('Last used ${AppFormat.date('2026-09-16T08:00:00Z')}'), findsOneWidget);
    expect(find.textContaining('2026-09-16'), findsNothing);
    expect(find.textContaining('7 left'), findsOneWidget);
    expect(find.byKey(const Key('passkey-add')), findsNothing, reason: 'this device cannot make one');

    await tester.tap(find.byKey(const Key('totp-remove')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('security-prompt')), 'not it');
    await tester.tap(find.byKey(const Key('security-prompt-ok')));
    await tester.pumpAndSettle();
    expect(find.text('That password did not match.'), findsOneWidget);
    expect(find.byKey(const Key('totp-remove')), findsOneWidget);

    await tester.tap(find.byKey(const Key('totp-remove')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('security-prompt')), 'a phrase long enough');
    await tester.tap(find.byKey(const Key('security-prompt-ok')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('totp-setup')), findsOneWidget, reason: 'removed: it can be set up again');
  });

  testWidgets('the owner says which tiers must have a second step', (tester) async {
    final server = _Server({
      'GET $_iam/admin/mfa-policy': (_) => jsonResponse('{"data":{"requiredTiers":["MANAGER"]}}'),
      'PUT $_iam/admin/mfa-policy': (o) => jsonResponse(jsonEncode({'data': {'requiredTiers': (o.data as Map)['requiredTiers']}})),
    });
    await _pump(tester, server, const Scaffold(body: MfaPolicyDialog()));
    await tester.pumpAndSettle();

    expect(tester.widget<CheckboxListTile>(find.byKey(const Key('mfa-tier-MANAGER'))).value, isTrue);
    expect(tester.widget<CheckboxListTile>(find.byKey(const Key('mfa-tier-CASHIER'))).value, isFalse);
    await tester.tap(find.byKey(const Key('mfa-tier-CASHIER')));
    await tester.tap(find.byKey(const Key('mfa-tier-OWNER')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mfa-policy-save')));
    await tester.pumpAndSettle();
    final put = server.requests.lastWhere((r) => r.method == 'PUT');
    expect(put.path, '$_iam/admin/mfa-policy');
    expect((put.data as Map)['requiredTiers'], ['CASHIER', 'MANAGER', 'OWNER']);
  });
}
