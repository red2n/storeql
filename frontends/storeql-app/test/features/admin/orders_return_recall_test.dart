import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/orders_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A recall return in the admin Return / Refund dialog (order-svc, 30 Sep 2026):
// an order with open recall notices offers "This is a recall return" with the
// notice in words; choosing it sends its id, asks no condition for the recalled
// line and says the return policy does not hold the refund. Several notices
// are chosen between. An order with none shows nothing extra.
// ---------------------------------------------------------------------------

Map<String, dynamic> _notice(String id, String ref, String lot) => {
      'id': id,
      'recallId': 'r-$id',
      'reference': ref,
      'hazard': 'ALLERGEN',
      'orderId': 'o-1',
      'storeId': 's',
      'status': 'ISSUED',
      'lines': [
        {'variantId': 'v-1', 'productName': 'Oat milk 1L', 'batchNo': lot, 'qty': 2},
      ],
    };

class _Server implements HttpClientAdapter {
  _Server(this.notices);
  final List<Map<String, dynamic>> notices;
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'POST') return jsonResponse('{"data":{"refundAmount":10.0}}', 201);
    if (o.path.endsWith('/orders/recall-notices')) return jsonResponse(jsonEncode({'data': notices}));
    if (o.path.endsWith('/orders/o-1')) {
      return jsonResponse('{"data":{"id":"o-1","status":"FULFILLED","currency":"GBP","total":20.0,'
          '"items":[{"variantId":"v-1","qty":2,"unitPrice":10.0,"lineTotal":20.0}]}}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _open(WidgetTester tester, List<Map<String, dynamic>> notices) async {
  tester.view.physicalSize = const Size(800, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(notices);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<void>(
              context: context,
              builder: (_) => ReturnDialog(orderId: 'o-1', onDone: () {}),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return server;
}

Future<void> _tap(WidgetTester tester, Finder f) async {
  await tester.ensureVisible(f);
  await tester.pumpAndSettle();
  await tester.tap(f);
  await tester.pumpAndSettle();
}

Map<String, dynamic> _posted(_Server s) {
  final r = s.requests.singleWhere((r) => r.method == 'POST');
  return (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>;
}

void main() {
  testWidgets('an order with no open notice shows nothing about recalls', (tester) async {
    await _open(tester, const []);
    expect(find.byKey(const Key('recall-return-choice')), findsNothing);
    expect(find.textContaining('recall'), findsNothing);
  });

  testWidgets('one notice is offered in words, and choosing it sends its id with no condition', (tester) async {
    final server = await _open(tester, [_notice('n-1', 'R-2026-017', 'L7')]);
    final ask = server.requests.singleWhere((r) => r.path.endsWith('/orders/recall-notices'));
    expect(ask.queryParameters['orderId'], 'o-1');
    expect(ask.queryParameters['status'], 'ISSUED');
    expect(find.text('This is a recall return'), findsOneWidget);
    expect(find.textContaining('Recall R-2026-017 · Oat milk 1L, lot L7'), findsOneWidget);

    await _tap(tester, find.byTooltip('Increase quantity'));
    expect(find.byKey(const Key('return-condition-v-1-SEALED')), findsOneWidget);
    await _tap(tester, find.byKey(const Key('recall-return-switch')));
    expect(find.byKey(const Key('return-condition-v-1-SEALED')), findsNothing);
    expect(find.byKey(const Key('return-recalled-v-1')), findsOneWidget);
    expect(find.textContaining('not held by the return policy'), findsOneWidget);

    await _tap(tester, find.widgetWithText(FilledButton, 'Process return'));
    final body = _posted(server);
    expect(body['recallNoticeId'], 'n-1');
    expect(body['items'], [
      {'variantId': 'v-1', 'qty': 1}
    ]);
  });

  testWidgets('several notices are chosen between, and the chosen one is sent', (tester) async {
    final server = await _open(tester, [_notice('n-1', 'R-2026-017', 'L7'), _notice('n-2', 'R-2026-031', 'L9')]);
    expect(find.textContaining('affected by 2 recalls'), findsOneWidget);
    await _tap(tester, find.byTooltip('Increase quantity'));
    await _tap(tester, find.byKey(const Key('recall-return-switch')));
    await _tap(tester, find.byKey(const Key('recall-return-notice-n-2')));
    await _tap(tester, find.widgetWithText(FilledButton, 'Process return'));
    expect(_posted(server)['recallNoticeId'], 'n-2');
  });

  testWidgets('left off, it is an ordinary return: a condition is asked and no notice is sent', (tester) async {
    final server = await _open(tester, [_notice('n-1', 'R-2026-017', 'L7')]);
    await _tap(tester, find.byTooltip('Increase quantity'));
    await _tap(tester, find.widgetWithText(FilledButton, 'Process return'));
    expect(find.text('Say what condition each returned item is in.'), findsOneWidget);
    await _tap(tester, find.byKey(const Key('return-condition-v-1-DAMAGED')));
    await _tap(tester, find.widgetWithText(FilledButton, 'Process return'));
    final body = _posted(server);
    expect(body.containsKey('recallNoticeId'), isFalse);
    expect(body['items'], [
      {'variantId': 'v-1', 'qty': 1, 'condition': 'DAMAGED'}
    ]);
  });
}
