import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/returns_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A recall return at the till (order-svc, 30 Sep 2026): when the sale has open
// recall notices the Returns screen offers "This is a recall return" with the
// notice in words (product and lot); choosing it sends the notice's id, asks no
// condition for the recalled line and says the refund is not held by the return
// policy. A sale with no notice shows nothing extra and returns as before.
// ---------------------------------------------------------------------------

const _notice = {
  'id': 'n-1',
  'recallId': 'r-1',
  'reference': 'R-2026-017',
  'hazard': 'ALLERGEN',
  'orderId': 'o-1',
  'storeId': 'store-1',
  'status': 'ISSUED',
  'lines': [
    {'variantId': 'v-1', 'productName': 'Strawberry jam', 'batchNo': 'L7', 'qty': 2},
  ],
};

class _Server implements HttpClientAdapter {
  _Server({this.notices = const []});
  final List<Map<String, dynamic>> notices;
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final p = o.path;
    if (p.endsWith('/orders/by-receipt')) {
      return jsonResponse(jsonEncode({
        'data': {
          'order': {'id': 'o-1', 'currency': 'GBP'},
          'receiptNumber': '2026-000042',
          'lines': [
            {'variantId': 'v-1', 'soldQty': 2, 'returnedQty': 0, 'returnableQty': 2, 'unitPrice': 6.0},
          ],
        },
      }));
    }
    if (p.endsWith('/orders/recall-notices')) return jsonResponse(jsonEncode({'data': notices}));
    if (p.endsWith('/variants/resolve')) {
      return jsonResponse('{"data":[{"variantId":"v-1","productName":"Strawberry jam","sku":"JAM-1"}]}');
    }
    if (o.method == 'POST' && p.endsWith('/returns')) {
      return jsonResponse('{"data":{"refundAmount":12.0}}', 201);
    }
    return jsonResponse('{"data":{}}');
  }
}

Future<_Server> _pump(WidgetTester tester, {List<Map<String, dynamic>> notices = const []}) async {
  tester.view.physicalSize = const Size(800, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(notices: notices);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  final router = GoRouter(routes: [
    GoRoute(path: '/', builder: (_, _) => const Scaffold(body: PosReturnsScreen())),
  ]);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth("CASHIER")),
      posStoreProvider.overrideWith((ref) => 'store-1'),
    ],
    child: MaterialApp.router(routerConfig: router),
  ));
  await tester.pumpAndSettle();
  return server;
}

Future<void> _tap(WidgetTester tester, Finder f) async {
  await tester.ensureVisible(f);
  await tester.pumpAndSettle();
  await tester.tap(f);
  await tester.pumpAndSettle();
}

Future<void> _findSaleAndTakeOneBack(WidgetTester tester) async {
  await tester.enterText(find.byKey(const Key('returns-receipt-field')), '2026-000042');
  await _tap(tester, find.byKey(const Key('returns-find')));
  await _tap(tester, find.byKey(const Key('returns-inc-v-1')));
}

void main() {
  testWidgets('a sale with no open notice shows nothing about recalls', (tester) async {
    await _pump(tester);
    await _findSaleAndTakeOneBack(tester);
    expect(find.byKey(const Key('recall-return-choice')), findsNothing);
    expect(find.textContaining('recall'), findsNothing);
  });

  testWidgets('a sale with an open notice offers it in words, asking the server for that order\'s open notices',
      (tester) async {
    final server = await _pump(tester, notices: [_notice]);
    await _findSaleAndTakeOneBack(tester);
    final ask = server.requests.singleWhere((r) => r.path.endsWith('/orders/recall-notices'));
    expect(ask.queryParameters['orderId'], 'o-1');
    expect(ask.queryParameters['status'], 'ISSUED');
    expect(find.text('This is a recall return'), findsOneWidget);
    expect(find.textContaining('Recall R-2026-017 · Strawberry jam, lot L7'), findsOneWidget);
    // Offered, not chosen: the ordinary return still asks its condition.
    expect(find.byKey(const Key('returns-condition-v-1-SEALED')), findsOneWidget);
    expect(find.byKey(const Key('recall-return-hint')), findsNothing);
  });

  testWidgets('choosing it sends the notice, no condition for the recalled line, and says the policy does not hold it',
      (tester) async {
    final server = await _pump(tester, notices: [_notice]);
    await _findSaleAndTakeOneBack(tester);
    await _tap(tester, find.byKey(const Key('recall-return-switch')));
    expect(find.textContaining('The refund is not held by the return policy'), findsOneWidget);
    expect(find.byKey(const Key('returns-condition-v-1-SEALED')), findsNothing);
    expect(find.byKey(const Key('returns-recalled-v-1')), findsOneWidget);

    await _tap(tester, find.byKey(const Key('returns-submit')));
    final post = server.requests.singleWhere((r) => r.method == 'POST' && r.path.endsWith('/orders/o-1/returns'));
    final body = post.data as Map<String, dynamic>;
    expect(body['recallNoticeId'], 'n-1');
    expect(body['items'], [
      {'variantId': 'v-1', 'qty': 1}
    ]);
    expect(find.byKey(const Key('returns-done')), findsOneWidget);
  });

  testWidgets('not choosing it is an ordinary return: a condition is asked and no notice is sent', (tester) async {
    final server = await _pump(tester, notices: [_notice]);
    await _findSaleAndTakeOneBack(tester);
    await _tap(tester, find.byKey(const Key('returns-submit')));
    expect(find.text('Say what condition each returned item is in.'), findsOneWidget);
    await _tap(tester, find.byKey(const Key('returns-condition-v-1-OPENED')));
    await _tap(tester, find.byKey(const Key('returns-submit')));
    final body = server.requests.singleWhere((r) => r.method == 'POST').data as Map<String, dynamic>;
    expect(body.containsKey('recallNoticeId'), isFalse);
    expect(body['items'], [
      {'variantId': 'v-1', 'qty': 1, 'condition': 'OPENED'}
    ]);
  });
}
