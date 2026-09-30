import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/security_events_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Security events for the owner (iam-svc, 30 Sep 2026): a filterable, paginated
// list of the business's login events — when, who by name, what happened in
// words, the detail when there is one. An event type the app has no words for
// reads as a generic line, never as its code. A manager held to stores is not
// sent to the server at all and is told who reads it.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int status = 200;
  bool empty = false;

  List<RequestOptions> get reads =>
      requests.where((r) => r.path.endsWith('/auth/admin/security-events')).toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/auth/admin/security-events')) {
      if (status != 200) {
        return jsonResponse(jsonEncode({'error': {'code': 'STORE_ACCESS_DENIED', 'message': 'STORE_ACCESS_DENIED'}}), status);
      }
      if (empty) return jsonResponse('{"data":{"items":[],"nextCursor":null}}');
      if (o.queryParameters['after'] == 'c1') {
        return jsonResponse(jsonEncode({
          'data': {
            'items': [
              {'id': 'e-3', 'type': 'PASSWORD_CHANGED', 'userId': 'u-2', 'email': 'ben@shop.test', 'at': '2026-09-20T08:00:00Z'},
            ],
            'nextCursor': null,
          },
        }));
      }
      if (o.queryParameters['type'] == 'MFA_LOCKED') {
        return jsonResponse(jsonEncode({
          'data': {
            'items': [
              {'id': 'e-9', 'type': 'MFA_LOCKED', 'userId': 'u-1', 'email': 'ana@shop.test', 'at': '2026-09-29T10:00:00Z'},
            ],
            'nextCursor': null,
          },
        }));
      }
      return jsonResponse(jsonEncode({
        'data': {
          'items': [
            {'id': 'e-1', 'type': 'MFA_LOGIN_FAILED', 'userId': 'u-1', 'email': 'ana@shop.test', 'detail': 'TOTP', 'at': '2026-09-29T09:00:00Z'},
            {'id': 'e-2', 'type': 'LOGIN_FAILED', 'at': '2026-09-29T08:00:00Z'},
            {'id': 'e-4', 'type': 'API_KEY_CREATED', 'userId': 'u-1', 'email': 'ana@shop.test', 'detail': 'Warehouse scanner (sqk_live_ab12)', 'at': '2026-09-28T08:00:00Z'},
            {'id': 'e-5', 'type': 'PASSKEY_TELEPORTED', 'userId': 'u-9', 'at': '2026-09-27T08:00:00Z'},
          ],
          'nextCursor': 'c1',
        },
      }));
    }
    if (o.path.endsWith('/admin/staff')) {
      return jsonResponse('{"data":[{"id":"a1","userId":"u-1","storeId":"s1","role":"MANAGER","assignedAt":""}],"meta":{}}');
    }
    if (o.path.contains('/auth/admin/staff-users')) {
      return jsonResponse('{"data":[{"userId":"u-1","email":"ana@shop.test"}]}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(WidgetTester tester,
    {Size size = const Size(1200, 1400), String role = 'OWNER', List<String> storeIds = const [], _Server? server}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final srv = server ?? _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = srv;
  await tester.pumpWidget(ProviderScope(
    key: UniqueKey(),
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role, storeIds: storeIds)),
    ],
    child: const MaterialApp(home: Scaffold(body: SecurityEventsScreen())),
  ));
  await tester.pumpAndSettle();
  return srv;
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('lists events newest first: when, who by name, what happened in words, the detail', (tester) async {
    final srv = await _pump(tester);
    expect(find.text('Security events'), findsWidgets);
    expect(find.text('Second step failed'), findsOneWidget);
    expect(find.textContaining('ana@shop.test · Totp'), findsOneWidget);
    expect(find.text('Sign-in failed'), findsOneWidget);
    // A sign-in that names no login says so; it is never blank or an id.
    expect(find.textContaining('no known login'), findsOneWidget);
    expect(find.text('API key created'), findsOneWidget);
    expect(find.textContaining('Warehouse scanner (sqk_live_ab12)'), findsOneWidget);
    for (final code in ['MFA_LOGIN_FAILED', 'LOGIN_FAILED', 'API_KEY_CREATED', 'TOTP']) {
      expect(find.textContaining(code), findsNothing, reason: code);
    }
    // The first read asks for a period and a page.
    final q = srv.reads.first.queryParameters;
    expect(q['limit'], 50);
    expect(q.containsKey('from') && q.containsKey('to'), isTrue);
    expect(q.containsKey('type') || q.containsKey('userId'), isFalse);
  });

  testWidgets('an event type the app has no words for reads as a generic line, never as its code', (tester) async {
    await _pump(tester);
    expect(find.text('Security event: passkey teleported'), findsOneWidget);
    expect(find.textContaining('PASSKEY_TELEPORTED'), findsNothing);
    expect(securityEventLabel('SOMETHING_NEW_HAPPENED'), 'Security event: something new happened');
  });

  testWidgets('the type filter asks the server for that kind only', (tester) async {
    final srv = await _pump(tester);
    await tester.tap(find.byKey(const Key('security-type')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Second step locked after too many wrong answers').last);
    await tester.pumpAndSettle();
    expect(srv.reads.last.queryParameters['type'], 'MFA_LOCKED');
    expect(find.text('Second step locked after too many wrong answers'), findsWidgets);
    expect(find.text('Sign-in failed'), findsNothing);
  });

  testWidgets('the Who filter names people and asks the server for that login', (tester) async {
    final srv = await _pump(tester);
    await tester.tap(find.byKey(const Key('security-user')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('ana@shop.test').last);
    await tester.pumpAndSettle();
    expect(srv.reads.last.queryParameters['userId'], 'u-1');
  });

  testWidgets('Load older appends the next page with the cursor', (tester) async {
    final srv = await _pump(tester);
    await tester.ensureVisible(find.byKey(const Key('security-load-older')));
    await tester.tap(find.byKey(const Key('security-load-older')));
    await tester.pumpAndSettle();
    expect(srv.reads.last.queryParameters['after'], 'c1');
    expect(find.text('Password changed'), findsOneWidget);
    expect(find.text('Second step failed'), findsOneWidget);
    expect(find.byKey(const Key('security-load-older')), findsNothing);
  });

  testWidgets('an empty period says so', (tester) async {
    final srv = _Server()..empty = true;
    await _pump(tester, server: srv);
    expect(find.text('Nothing recorded in this period'), findsOneWidget);
  });

  testWidgets('a refusal reads in words', (tester) async {
    final srv = _Server()..status = 403;
    await _pump(tester, server: srv);
    expect(find.text('That is not one of your stores.'), findsOneWidget);
    expect(find.textContaining('STORE_ACCESS_DENIED'), findsNothing);
  });

  testWidgets('a manager held to stores is told who reads it and nothing is asked', (tester) async {
    final srv = await _pump(tester, role: 'MANAGER', storeIds: ['s1']);
    expect(find.text('Only an owner or a head-office manager reads the security events.'), findsOneWidget);
    expect(srv.reads, isEmpty);
  });

  testWidgets('a head-office manager reads it', (tester) async {
    final srv = await _pump(tester, role: 'MANAGER');
    expect(srv.reads, isNotEmpty);
    expect(find.text('Second step failed'), findsOneWidget);
  });

  testWidgets('on a phone the filters fold behind one button and the rows do not overflow', (tester) async {
    await _pump(tester, size: const Size(390, 844));
    expect(find.byKey(const Key('security-type')), findsNothing);
    expect(find.text('Filters'), findsOneWidget);
    expect(find.text('Second step failed'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('nothing on the screen writes: every request is a GET', (tester) async {
    final srv = await _pump(tester);
    expect(srv.requests.every((r) => r.method == 'GET'), isTrue);
  });
}
