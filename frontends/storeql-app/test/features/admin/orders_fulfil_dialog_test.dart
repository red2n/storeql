import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/orders_screen.dart';

// ---------------------------------------------------------------------------
// A customer order can be part-fulfilled (SJ-D35). The dialog shows what is
// still outstanding per line, sends only the quantities going now, and sends
// no body at all when everything goes — the plain fulfilment the API always
// had. More than is outstanding is stopped before it is sent.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int postStatus = 200;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'POST') {
      final body = postStatus == 200
          ? '{"data":{"id":"o-1","status":"PARTIALLY_FULFILLED","currency":"GBP","total":50,"items":[]}}'
          : '{"error":{"code":"ORDER_FULFIL_QTY_EXCEEDS_OUTSTANDING","message":"variant v-1: 4 asked, 3 still outstanding"}}';
      return ResponseBody.fromString(body, postStatus,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
    }
    // The order: five mugs, two already handed over; three plates, none yet.
    const body = '{"data":{"id":"o-1","status":"PARTIALLY_FULFILLED","currency":"GBP","total":50,'
        '"items":[{"variantId":"01a090ae-611e-7011-ae7d-1bd68c966ff6","qty":5,"unitPrice":10,"lineTotal":50,"fulfilledQty":2},'
        '{"variantId":"01a090ae-611e-7011-ae7d-1bd68c966aa1","qty":3,"unitPrice":10,"lineTotal":30,"fulfilledQty":0}]}}';
    return ResponseBody.fromString(body, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<_Server> _pump(WidgetTester tester) async {
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  var done = 0;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<void>(
              context: context,
              builder: (_) => FulfilDialog(orderId: 'o-1', onDone: () => done++),
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

Map<String, dynamic>? _postBody(_Server s) {
  final post = s.requests.singleWhere((r) => r.method == 'POST');
  if (post.data == null) return null;
  return (post.data is String ? jsonDecode(post.data as String) : post.data) as Map<String, dynamic>;
}

void main() {
  testWidgets('shows what is outstanding per line, and sends only what goes now', (tester) async {
    final server = await _pump(tester);
    expect(find.textContaining('3 of 5 outstanding'), findsOneWidget);
    expect(find.textContaining('3 of 3 outstanding'), findsOneWidget);

    // One mug now, all three plates.
    await tester.enterText(find.byKey(const Key('fulfil-qty-01a090ae-611e-7011-ae7d-1bd68c966ff6')), '1');
    await tester.tap(find.widgetWithText(FilledButton, 'Picked & packed'));
    await tester.pumpAndSettle();

    final body = _postBody(server)!;
    expect(body['lines'], [
      {'variantId': '01a090ae-611e-7011-ae7d-1bd68c966ff6', 'qty': 1},
      {'variantId': '01a090ae-611e-7011-ae7d-1bd68c966aa1', 'qty': 3},
    ]);
    expect(server.requests.singleWhere((r) => r.method == 'POST').path, endsWith('/orders/o-1/fulfil'));
    expect(find.text('Part of the order picked.'), findsOneWidget);
  });

  testWidgets('everything outstanding is the plain fulfilment: no body', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.widgetWithText(FilledButton, 'Picked & packed'));
    await tester.pumpAndSettle();
    expect(_postBody(server), isNull);
    expect(find.text('Order picked and packed.'), findsOneWidget);
  });

  testWidgets('more than is outstanding is stopped here; zero for a line leaves it out',
      (tester) async {
    final server = await _pump(tester);
    await tester.enterText(find.byKey(const Key('fulfil-qty-01a090ae-611e-7011-ae7d-1bd68c966ff6')), '4');
    await tester.tap(find.widgetWithText(FilledButton, 'Picked & packed'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Only 3 outstanding'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), isEmpty);

    await tester.enterText(find.byKey(const Key('fulfil-qty-01a090ae-611e-7011-ae7d-1bd68c966ff6')), '0');
    await tester.tap(find.widgetWithText(FilledButton, 'Picked & packed'));
    await tester.pumpAndSettle();
    expect(_postBody(server)!['lines'], [
      {'variantId': '01a090ae-611e-7011-ae7d-1bd68c966aa1', 'qty': 3},
    ]);
  });

  testWidgets("the server's refusal is shown in words and the dialog stays open", (tester) async {
    final server = await _pump(tester)..postStatus = 409;
    await tester.tap(find.widgetWithText(FilledButton, 'Picked & packed'));
    await tester.pumpAndSettle();
    expect(find.textContaining('3 still outstanding'), findsOneWidget);
    expect(find.text('Picked & packed'), findsWidgets);
    expect(server.requests.where((r) => r.method == 'POST'), hasLength(1));
  });
}
