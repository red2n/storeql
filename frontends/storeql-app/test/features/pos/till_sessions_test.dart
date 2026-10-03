import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/pos/cash_screen.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Till sessions (iam-svc, 30 Sep 2026): a cashier re-attaches to their OWN
// sessions (`/mine`; the everyone list is management's and answers a cashier
// 403). A manager sees who is signed in at their store and ends a session with
// a required reason.
// ---------------------------------------------------------------------------

const _storeId = '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c0001';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  /// Status the end-session call answers.
  int endStatus = 204;

  static ResponseBody _json(Object body, int status) => ResponseBody.fromString(
        jsonEncode(body),
        status,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        },
      );

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final p = o.path;
    if (p.endsWith('/auth/pos/sessions/mine')) {
      return _json({
        'data': [
          {
            'id': 'sess-mine',
            'userId': 'u',
            'storeId': _storeId,
            'startedAt': '2026-09-30T08:00:00Z',
            'idleTimeoutSeconds': 900,
            'status': 'ACTIVE',
          }
        ]
      }, 200);
    }
    if (o.method == 'GET' && p.endsWith('/auth/pos/sessions')) {
      return _json({
        'data': [
          {
            'id': 'sess-1',
            'userId': 'cashier-1',
            'storeId': _storeId,
            'startedAt': '2026-09-30T08:00:00Z',
            'lastActivityAt': '2026-09-30T09:30:00Z',
            'status': 'ACTIVE',
          },
          {
            'id': 'sess-old',
            'userId': 'cashier-2',
            'storeId': _storeId,
            'startedAt': '2026-09-29T08:00:00Z',
            'status': 'ENDED',
          },
        ]
      }, 200);
    }
    if (p.endsWith('/auth/admin/staff-users')) {
      return _json({
        'data': [
          {'userId': 'cashier-1', 'email': 'sam@shop.example'}
        ]
      }, 200);
    }
    if (o.method == 'DELETE' && p.contains('/auth/pos/sessions/')) {
      if (endStatus >= 400) {
        return _json({
          'error': {'code': 'POS_SESSION_REASON_REQUIRED', 'message': ''}
        }, endStatus);
      }
      return ResponseBody.fromString('', 204);
    }
    if (p.endsWith('/till-sessions/current')) {
      return _json({
        'error': {'code': 'TILL_SESSION_NOT_OPEN', 'message': ''}
      }, 404);
    }
    if (p.endsWith('/tenant-svc/admin/tenant')) {
      return _json({
        'data': {'id': 't-1', 'name': 'Corner Shop', 'currency': 'GBP'}
      }, 200);
    }
    return _json({'data': []}, 200);
  }
}

List<Override> _overrides(_Server s, String role) => [
      apiClientProvider.overrideWithValue(FakeApiClient(
          Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = s)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
      posStoreProvider.overrideWith((ref) => _storeId),
      posStoresProvider.overrideWith((ref) async => const [
            StoreInfo(id: _storeId, name: 'High Street', code: 'HS', type: 'STORE', status: 'ACTIVE'),
          ]),
    ];

Future<_Server> _pump(WidgetTester tester, String role, {_Server? server}) async {
  tester.view.physicalSize = const Size(900, 1800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final s = server ?? _Server();
  await tester.pumpWidget(ProviderScope(
    overrides: _overrides(s, role),
    child: const MaterialApp(home: Scaffold(body: CashScreen())),
  ));
  await tester.pumpAndSettle();
  return s;
}

void main() {
  setUpAll(initializeDateFormatting);

  test("a cashier re-attaches through their own sessions, never the store's list", () async {
    final server = _Server();
    final container = ProviderContainer(overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(
          Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server)),
      authNotifierProvider.overrideWith(() => RoleAuth('CASHIER')),
    ]);
    addTearDown(container.dispose);
    await container.read(authNotifierProvider.future);
    container.read(posSessionProvider);
    await Future<void>.delayed(const Duration(milliseconds: 50));

    final gets = server.requests.where((r) => r.method == 'GET').toList();
    expect(gets.single.path, '/iam-svc/auth/pos/sessions/mine');
    expect(container.read(posSessionProvider)?.id, 'sess-mine');
    expect(container.read(posStoreProvider), _storeId);
  });

  testWidgets('a manager sees who is signed in, by name, and only open sessions', (tester) async {
    final server = await _pump(tester, 'MANAGER');
    final ask = server.requests.firstWhere(
        (r) => r.method == 'GET' && r.path.endsWith('/auth/pos/sessions'));
    expect(ask.queryParameters['storeId'], _storeId);
    expect(find.byKey(const Key('open-sessions-card')), findsOneWidget);
    expect(find.text('sam@shop.example'), findsOneWidget);
    expect(find.byKey(const Key('open-session-sess-1')), findsOneWidget);
    expect(find.byKey(const Key('open-session-sess-old')), findsNothing);
  });

  testWidgets('a cashier is not shown the sessions and does not ask for them', (tester) async {
    final server = await _pump(tester, 'CASHIER');
    expect(find.byKey(const Key('open-sessions-card')), findsNothing);
    expect(
        server.requests.where(
            (r) => r.method == 'GET' && r.path.endsWith('/auth/pos/sessions')),
        isEmpty);
  });

  testWidgets('End session asks a reason, and sends it once given', (tester) async {
    final server = await _pump(tester, 'MANAGER');
    await tester.ensureVisible(find.text('End session'));
    await tester.tap(find.text('End session'));
    await tester.pumpAndSettle();
    final confirm = find.descendant(
        of: find.byType(AlertDialog), matching: find.widgetWithText(FilledButton, 'End session'));
    expect(tester.widget<FilledButton>(confirm).onPressed, isNull, reason: 'a reason is required');
    await tester.enterText(find.byKey(const Key('reason-field')), '   ');
    await tester.pumpAndSettle();
    expect(tester.widget<FilledButton>(confirm).onPressed, isNull);
    await tester.enterText(find.byKey(const Key('reason-field')), ' Left the till unlocked ');
    await tester.pumpAndSettle();
    await tester.tap(confirm);
    await tester.pumpAndSettle();

    final del = server.requests.singleWhere((r) => r.method == 'DELETE');
    expect(del.path, '/iam-svc/auth/pos/sessions/sess-1');
    expect(del.queryParameters['reason'], 'Left the till unlocked');
    expect(find.text('Session ended.'), findsOneWidget);
  });

  testWidgets('cancelling the reason ends nothing', (tester) async {
    final server = await _pump(tester, 'MANAGER');
    await tester.ensureVisible(find.text('End session'));
    await tester.tap(find.text('End session'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(server.requests.where((r) => r.method == 'DELETE'), isEmpty);
  });

  testWidgets('a refusal reads in words', (tester) async {
    final server = await _pump(tester, 'MANAGER', server: _Server()..endStatus = 400);
    await tester.ensureVisible(find.text('End session'));
    await tester.tap(find.text('End session'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('reason-field')), 'x');
    await tester.pumpAndSettle();
    await tester.tap(find.descendant(
        of: find.byType(AlertDialog), matching: find.widgetWithText(FilledButton, 'End session')));
    await tester.pumpAndSettle();
    expect(find.text('Say why the session is being ended.'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'DELETE'), hasLength(1));
  });
}
