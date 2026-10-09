import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/pos/cart_screen.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Held sales at the till (order-svc, 30 Sep 2026): resuming a held sale calls
// POST /pos/parked-sales/{id}/resume, so the record says it was resumed;
// discarding is its own action (DELETE), which the record keeps apart; the list
// says who held each sale, by name; and a sale another till already picked up is
// refused in words and never loaded.
// ---------------------------------------------------------------------------

class _NoopSession extends PosSessionNotifier {
  _NoopSession(super.ref);
  @override
  Future<void> restore() async {}
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int resumeStatus = 200;

  List<RequestOptions> get writes => requests.where((r) => r.method != 'GET').toList();

  @override
  void close({bool force = false}) {}

  ResponseBody _ok(String b, [int status = 200]) =>
      jsonResponse(b, status);

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/pos/parked-sales') && o.method == 'GET') {
      return _ok(jsonEncode({
        'data': [
          {
            'id': 'p1',
            'customerName': 'Mrs Patel',
            'subtotal': 12.5,
            'parkedAt': '2026-09-30T09:00:00Z',
            'parkedBy': 'u-ann',
            'items': [
              {'variantId': 'v-1', 'qty': 2, 'unitPrice': 5.0},
              {'variantId': 'v-2', 'qty': 1, 'unitPrice': 2.5},
            ],
          },
        ],
      }));
    }
    if (o.path.endsWith('/resume') && o.method == 'POST') {
      return resumeStatus == 200
          ? _ok(jsonEncode({
              'data': {
                'id': 'p1',
                'subtotal': 12.5,
                'items': [
                  {'variantId': 'v-1', 'qty': 2, 'unitPrice': 5.0},
                  {'variantId': 'v-2', 'qty': 1, 'unitPrice': 2.5},
                ],
              },
            }))
          : _ok(jsonEncode({'error': {'code': 'PARKED_SALE_NOT_OPEN', 'message': 'PARKED_SALE_NOT_OPEN'}}),
              resumeStatus);
    }
    if (o.method == 'DELETE') return _ok('', 204);
    if (o.path.contains('/auth/admin/staff-users')) {
      return _ok('{"data":[{"userId":"u-ann","email":"ann@shop.test"}]}');
    }
    return _ok('{"data":[]}');
  }
}

Future<_Server> _pump(WidgetTester tester) async {
  tester.view.physicalSize = const Size(700, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final srv = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = srv;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      posStoresProvider.overrideWith((ref) async => const []),
      posSessionProvider.overrideWith((ref) => _NoopSession(ref)),
    ],
    child: const MaterialApp(home: Scaffold(body: PosCartScreen())),
  ));
  await tester.pumpAndSettle();
  return srv;
}

List<String> _cart(WidgetTester tester) => ProviderScope.containerOf(tester.element(find.byType(PosCartScreen).first))
    .read(posCartProvider)
    .map((l) => l.variantId)
    .toList();

void main() {
  testWidgets('the list says who held each sale, by name', (tester) async {
    await _pump(tester);
    await tester.tap(find.text('Resume'));
    await tester.pumpAndSettle();
    expect(find.textContaining('held by ann@shop.test'), findsOneWidget);
    expect(find.textContaining('u-ann'), findsNothing);
  });

  testWidgets('choosing a held sale POSTs resume, loads its basket, and deletes nothing', (tester) async {
    final srv = await _pump(tester);
    await tester.tap(find.text('Resume'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Mrs Patel'));
    await tester.pumpAndSettle();
    // A quote of the basket is a question the till asks, not a write.
    expect(
      srv.writes
          .where((r) => !r.path.endsWith('/prices/quote'))
          .map((r) => '${r.method} ${r.path}'),
      ['POST /order-svc/pos/parked-sales/p1/resume'],
    );
    expect(_cart(tester), ['v-1', 'v-2']);
  });

  testWidgets('a sale another till already picked up is refused in words and not loaded', (tester) async {
    final srv = await _pump(tester);
    srv.resumeStatus = 409;
    await tester.tap(find.text('Resume'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Mrs Patel'));
    await tester.pumpAndSettle();
    expect(find.text('That held sale was already picked up at another till.'), findsOneWidget);
    expect(find.textContaining('PARKED_SALE'), findsNothing);
    expect(_cart(tester), isEmpty);
  });

  testWidgets('discarding is its own action: it asks, then DELETEs, and never resumes', (tester) async {
    final srv = await _pump(tester);
    await tester.tap(find.text('Resume'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('held-discard-p1')));
    await tester.pumpAndSettle();
    expect(find.text('Discard held sale?'), findsOneWidget);
    // Keeping it sends nothing.
    await tester.tap(find.text('Keep it'));
    await tester.pumpAndSettle();
    expect(srv.writes, isEmpty);
    await tester.tap(find.byKey(const Key('held-discard-p1')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('held-discard-confirm')));
    await tester.pumpAndSettle();
    expect(srv.writes.map((r) => '${r.method} ${r.path}'), ['DELETE /order-svc/pos/parked-sales/p1']);
    expect(_cart(tester), isEmpty);
  });
}
