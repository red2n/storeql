import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/procurement_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The automatic order proposal (06.x) on the Procurement screen: a proposed
// draft wears its badge, each of its lines shows the arithmetic that produced
// it, and Propose orders posts the store and the cover and says what it did.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    String body = '{"data":[]}';
    if (o.method == 'POST' && o.path.endsWith('/purchase-orders/proposals/run')) {
      body = '{"data":{"id":"run-1","storeId":"st-1","ranAt":"2026-09-23T09:00:00Z","coverDays":28,"considered":3,'
          '"orders":[{"poId":"po-1","supplierId":"s-1","supplierName":"Acme Wholesale","currency":"GBP","lines":1,"totalNet":112.50}],'
          '"skipped":[{"variantId":"v-4","reason":"no reorder point computed for this item yet"}]}}';
    } else if (o.path.endsWith('/purchase-orders/po-1/lines')) {
      body = '{"data":[{"id":"l-1","poId":"po-1","variantId":"v-1111","qty":45,"unitPrice":2.5,"vatCode":"T1",'
          '"proposalReason":"on hand 3 + on order 10 = 13 ≤ reorder point 28; order EOQ 45"}]}';
    } else if (o.path.endsWith('/purchase-orders')) {
      body = '{"data":['
          '{"id":"po-1","supplierId":"s-1","storeId":"st-1","status":"DRAFT","currency":"GBP","totalNet":112.5,"totalVat":22.5,"totalGross":135.0,"createdAt":"2026-09-23T09:00:00Z","source":"PROPOSAL"},'
          '{"id":"po-2","supplierId":"s-1","storeId":"st-1","status":"SUBMITTED","currency":"GBP","totalNet":25.0,"totalVat":5.0,"totalGross":30.0,"createdAt":"2026-09-22T09:00:00Z","source":"MANUAL"}'
          ']}';
    } else if (o.path.endsWith('/stores')) {
      body = '{"data":[{"id":"st-1","name":"High Street","code":"HS","type":"STORE","status":"ACTIVE","country":"GB"}]}';
    }
    return ResponseBody.fromString(body, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<_Server> _pump(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1200, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('MANAGER')),
    ],
    child: const MaterialApp(home: ProcurementScreen()),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  testWidgets('a proposed draft wears its badge; an order a person typed does not', (tester) async {
    await _pump(tester);
    expect(find.text('Proposed'), findsOneWidget);
    // The status badge says a word, not the code.
    expect(find.text('Draft'), findsOneWidget);
    expect(find.text('Submitted'), findsOneWidget);
  });

  testWidgets('each proposed line shows the arithmetic that produced it', (tester) async {
    await _pump(tester);
    await tester.tap(find.text('Proposed'));
    await tester.pumpAndSettle();
    expect(find.textContaining('on hand 3 + on order 10 = 13'), findsOneWidget);
    expect(find.textContaining('order EOQ 45'), findsOneWidget);
  });

  testWidgets('Propose orders posts the store and the cover, then says what was raised and skipped',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('propose-orders')));
    await tester.pumpAndSettle();
    expect(find.text('Propose orders'), findsWidgets);
    await tester.enterText(find.byKey(const Key('propose-cover')), '14');
    await tester.tap(find.byKey(const Key('propose-run')));
    await tester.pumpAndSettle();
    final run = server.requests.where((r) => r.path.endsWith('/proposals/run')).single;
    final body = run.data is String ? jsonDecode(run.data as String) : run.data;
    expect(body['storeId'], 'st-1');
    expect(body['coverDays'], 14);
    expect(find.textContaining('Proposed 1 draft: Acme Wholesale (1 line'), findsOneWidget);
    expect(find.textContaining('1 skipped'), findsOneWidget);
  });
}
