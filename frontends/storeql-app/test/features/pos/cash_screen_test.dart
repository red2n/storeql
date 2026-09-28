import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/pos/cash_screen.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';

// ---------------------------------------------------------------------------
// The till's open session lives on the server, not in the app. After a reload,
// or on another device at the same counter, the Cash screen asks payment-svc
// for the till already open at the store and carries on with it — offering
// *Open till* only when the server says there is none.
// ---------------------------------------------------------------------------

const _storeId = '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c0001';
const _openId = '01a0c830-0e7a-7b3c-9d2e-5f1a2b3cabcd';
const _newId = '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c1234';

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

/// payment-svc and tenant-svc as the Cash screen sees them. [current] is the
/// answer to "which till is open here": a session id, `null` for none (404
/// TILL_SESSION_NOT_OPEN), or [_down] for a failure.
class _Server implements HttpClientAdapter {
  _Server({this.current});

  String? current;
  final List<RequestOptions> requests = [];

  static ResponseBody _json(Object body, int status) => ResponseBody.fromString(
        jsonEncode(body),
        status,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        },
      );

  Map<String, dynamic> _session(String id) => {
        'id': id,
        'storeId': _storeId,
        'openedBy': '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c9999',
        'floatAmount': 100,
        'status': 'OPEN',
        'openedAt': '2026-09-25T08:00:00Z',
      };

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/tenant-svc/admin/tenant')) {
      return _json({
        'data': {'id': 't-1', 'name': 'Corner Shop', 'currency': 'GBP'}
      }, 200);
    }
    if (path.endsWith('/till-sessions/current')) {
      if (current == _down) {
        return _json({
          'error': {
            'code': 'SERVICE_UNAVAILABLE',
            'message': 'Payments are unavailable right now.'
          }
        }, 503);
      }
      if (current == null) {
        return _json({
          'error': {
            'code': 'TILL_SESSION_NOT_OPEN',
            'message': 'No till is open at this store.'
          }
        }, 404);
      }
      return _json({'data': _session(current!)}, 200);
    }
    if (path.endsWith('/x-report')) {
      return _json({
        'data': {
          'tillSessionId': current ?? _newId,
          'floatAmount': 100,
          'cashDropsTotal': 0,
          'expectedCashInTill': 142.5,
          'grossSales': 42.5,
          'totalRefunds': 0,
          'netSales': 42.5,
        }
      }, 200);
    }
    if (o.method == 'POST' && path.endsWith('/admin/cash/till-sessions')) {
      current = _newId;
      return _json({'data': _session(_newId)}, 201);
    }
    return _json({'data': []}, 200);
  }
}

const _down = 'down';

Future<_Server> _pump(WidgetTester tester, _Server server) async {
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      posStoreProvider.overrideWith((ref) => _storeId),
      posStoresProvider.overrideWith((ref) async => const [
            StoreInfo(
              id: _storeId,
              name: 'High Street',
              code: 'HS',
              type: 'STORE',
              status: 'ACTIVE',
            ),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: CashScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

Finder get _openTillButton => find.widgetWithText(FilledButton, 'Open till');

void main() {
  testWidgets(
      'a till already open at the store is picked up when the screen opens',
      (tester) async {
    final server = await _pump(tester, _Server(current: _openId));

    final ask = server.requests
        .singleWhere((r) => r.path.endsWith('/till-sessions/current'));
    expect(ask.method, 'GET');
    expect(ask.path, '/payment-svc/admin/cash/till-sessions/current');
    expect(ask.queryParameters['storeId'], _storeId);

    expect(_openTillButton, findsNothing,
        reason: 'the server has a till open here: never offer a second one');
    expect(find.text('Till session'), findsOneWidget);
    expect(find.text('Expected cash in till'), findsOneWidget);
    expect(find.text('£142.50'), findsOneWidget);
    expect(
        server.requests.where(
            (r) => r.path.endsWith('/till-sessions/$_openId/x-report')),
        isNotEmpty);
  });

  testWidgets('no till open (404 TILL_SESSION_NOT_OPEN) offers Open till',
      (tester) async {
    await _pump(tester, _Server());

    expect(_openTillButton, findsOneWidget);
    expect(find.text('Store: High Street'), findsOneWidget);
    expect(find.text('No till is open at this store.'), findsNothing,
        reason: 'no open till is an answer, not an error');
  });

  testWidgets('opening a till carries on to the open session', (tester) async {
    final server = await _pump(tester, _Server());

    await tester.enterText(find.byType(TextField), '100');
    await tester.tap(_openTillButton);
    await tester.pumpAndSettle();

    final open = server.requests.singleWhere((r) => r.method == 'POST');
    expect((open.data as Map)['storeId'], _storeId);
    expect(find.text('Till session'), findsOneWidget);
    expect(_openTillButton, findsNothing);
  });

  testWidgets(
      'when the server cannot say, Open till stays, with the reason and a retry',
      (tester) async {
    final server = await _pump(tester, _Server(current: _down));

    expect(_openTillButton, findsOneWidget);
    expect(find.textContaining('Payments are unavailable right now.'),
        findsOneWidget);

    // The server is back and a till is open after all: a retry finds it.
    server.current = _openId;
    await tester.tap(find.text('Try again'));
    await tester.pumpAndSettle();
    expect(find.text('Till session'), findsOneWidget);
    expect(_openTillButton, findsNothing);
  });
}
