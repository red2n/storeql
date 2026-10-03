import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/auth/mfa_api.dart';
import 'package:storeql_app/features/auth/my_sessions.dart';
import 'package:storeql_app/features/auth/security_screen.dart';
import 'package:storeql_app/features/storefront/account_screen.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Where a login is signed in (iam-svc, 30 Sep 2026): each session with its
// device, network, start and last use, this device marked, "Sign out" on every
// other one. The token's `sid` is sent as X-Session-Id so the server can say
// which one is this device.
// ---------------------------------------------------------------------------

/// A token whose payload carries [sid]; the signature is never checked here.
String _token(String? sid) {
  String b64(Map<String, dynamic> m) =>
      base64Url.encode(utf8.encode(jsonEncode(m))).replaceAll('=', '');
  return '${b64({'alg': 'none'})}.${b64({'sub': 'u', 'sid': ?sid})}.sig';
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int endStatus = 204;
  List<Map<String, dynamic>> sessions = [
    {
      'id': 'sess-here',
      'deviceLabel': 'Chrome on Windows',
      'network': '203.0.113.0/24',
      'startedAt': '2026-09-30T08:00:00Z',
      'lastUsedAt': '2026-09-30T09:00:00Z',
      'authMethod': 'pwd+otp',
      'current': true,
    },
    {
      'id': 'sess-phone',
      'deviceLabel': 'Safari on iPhone',
      'network': '198.51.100.0/24',
      'startedAt': '2026-09-28T18:00:00Z',
      'lastUsedAt': '2026-09-29T07:30:00Z',
      'authMethod': 'pwd',
      'current': false,
    },
  ];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'GET' && o.path.endsWith('/auth/sessions')) {
      return jsonResponse(jsonEncode({'data': sessions}));
    }
    if (o.method == 'DELETE') {
      if (endStatus == 204) {
        sessions = sessions.where((e) => !o.path.endsWith('/${e['id']}')).toList();
        return ResponseBody.fromString('', 204);
      }
      return jsonResponse('{"error":{"code":"SESSION_NOT_FOUND","message":""}}', endStatus);
    }
    return jsonResponse('{"data":{}}');
  }
}

Future<_Server> _pump(WidgetTester tester, {String? sid = 'sess-here', _Server? server}) async {
  tester.view.physicalSize = const Size(900, 1800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final srv = server ?? _Server();
  await tester.pumpWidget(MaterialApp(
    home: Scaffold(
      body: SingleChildScrollView(
        child: MySessionsCard(
          dio: Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = srv,
          accessToken: _token(sid),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  return srv;
}

void main() {
  setUpAll(initializeDateFormatting);

  test('the sid claim is read from the token, and absent when there is none', () {
    expect(sessionIdOfToken(_token('abc')), 'abc');
    expect(sessionIdOfToken(_token(null)), isNull);
    expect(sessionIdOfToken('not-a-token'), isNull);
    expect(sessionIdOfToken(null), isNull);
  });

  testWidgets('lists each session with device, network and times, and marks this device', (tester) async {
    final server = await _pump(tester);
    expect(find.text('Chrome on Windows'), findsOneWidget);
    expect(find.text('Safari on iPhone'), findsOneWidget);
    expect(find.text('This device'), findsOneWidget);
    expect(find.textContaining('Network 198.51.100.0/24'), findsOneWidget);
    expect(find.textContaining('Password and a code'), findsOneWidget);
    // Sign out only on the sessions that are not this one.
    expect(find.byKey(const Key('my-session-end-sess-phone')), findsOneWidget);
    expect(find.byKey(const Key('my-session-end-sess-here')), findsNothing);
    // The token's sid tells the server which session is this device.
    final ask = server.requests.single;
    expect(ask.headers['X-Session-Id'], 'sess-here');
  });

  testWidgets('a token with no sid sends no header', (tester) async {
    final server = await _pump(tester, sid: null);
    expect(server.requests.single.headers.containsKey('X-Session-Id'), isFalse);
  });

  testWidgets('Sign out ends that one session and the list refreshes without it', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('my-session-end-sess-phone')));
    await tester.pumpAndSettle();
    final del = server.requests.singleWhere((r) => r.method == 'DELETE');
    expect(del.path, '/iam-svc/auth/sessions/sess-phone');
    expect(find.text('Safari on iPhone'), findsNothing);
    expect(find.text('Chrome on Windows'), findsOneWidget);
    expect(find.textContaining('Signed out of Safari on iPhone'), findsOneWidget);
  });

  testWidgets('a session already gone says so, and the list refreshes', (tester) async {
    final server = await _pump(tester, server: _Server()..endStatus = 404);
    await tester.tap(find.byKey(const Key('my-session-end-sess-phone')));
    await tester.pumpAndSettle();
    expect(find.text('That session had already ended.'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'GET'), hasLength(2));
  });

  testWidgets('a failed read says so in words and can be retried', (tester) async {
    final srv = _Server();
    srv.sessions = [];
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: MySessionsCard(
          dio: Dio(BaseOptions(baseUrl: 'http://test'))
            ..httpClientAdapter = _Failing(),
          accessToken: _token('s'),
        ),
      ),
    ));
    await tester.pumpAndSettle();
    expect(find.textContaining("Can't reach the server"), findsOneWidget);
    expect(find.byKey(const Key('my-sessions-retry')), findsOneWidget);
  });

  testWidgets('the security screen carries the list beside Sign out everywhere', (tester) async {
    tester.view.physicalSize = const Size(900, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final srv = _Server();
    final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = srv;
    await tester.pumpWidget(ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        authNotifierProvider.overrideWith(() => RoleAuth('MANAGER')),
        mfaStatusProvider.overrideWith((ref) async => const MfaStatus(
            totp: false, passkeys: [], recoveryCodesLeft: 0, required: false)),
      ],
      child: const MaterialApp(home: SecurityScreen()),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Where you are signed in'), findsOneWidget);
    expect(find.text('Safari on iPhone'), findsOneWidget);
    expect(find.byKey(const Key('sign-out-everywhere')), findsOneWidget);
  });
  testWidgets("the shopper's account lists where they are signed in, with the shopper's own credential",
      (tester) async {
    tester.view.physicalSize = const Size(900, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final srv = _Server();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        storefrontAuthProvider.overrideWith((ref) => _ShopperAuth()),
        storefrontDioProvider.overrideWith(
            (ref) => Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = srv),
      ],
      child: const MaterialApp(home: Scaffold(body: StorefrontAccountScreen())),
    ));
    await tester.pumpAndSettle();
    expect(find.text('Where you are signed in'), findsOneWidget);
    expect(find.text('Safari on iPhone'), findsOneWidget);
    final ask = srv.requests.singleWhere((r) => r.path.endsWith('/auth/sessions'));
    expect(ask.headers['X-Session-Id'], 'sess-here');
  });
}

class _ShopperAuth extends StorefrontAuthNotifier {
  _ShopperAuth() {
    state = StorefrontAuthState(
        accessToken: _token('sess-here'), refreshToken: 'ref', email: 'ana@example.com');
  }
}

class _Failing implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async =>
      throw DioException(requestOptions: o, type: DioExceptionType.connectionError);
}
