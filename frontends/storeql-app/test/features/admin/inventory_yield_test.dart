import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/inventory_yield_tab.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The Inventory screen's "Yield & prep" tab: the template with its cuts and
// expected loss, this month's breakdowns with the loss against expected and the
// month's total; Record a breakdown filling the cuts in at what the template
// expects and posting what actually came out; New template refusing before it
// posts without a primal; and a storekeeper who may record but not define.
// ---------------------------------------------------------------------------

const _store = '01a0b100-0000-7000-8000-000000000001';
const _side = '01a0b100-0000-7000-8000-0000000000a1';
const _sirloin = '01a0b100-0000-7000-8000-0000000000a2';
const _mince = '01a0b100-0000-7000-8000-0000000000a3';
const _template = '01a0b100-0000-7000-8000-0000000000t1';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/admin/stores')) {
      return jsonResponse(
          '{"data":[{"id":"$_store","name":"Butchery","code":"B1","type":"STORE","status":"ACTIVE"}],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/yield/templates') && o.method == 'GET') {
      return jsonResponse(
          '{"data":[{"id":"$_template","name":"Side of beef","inputVariantId":"$_side","unit":"kg","active":true,"expectedLossPct":20,"outputs":[{"variantId":"$_sirloin","expectedPct":35,"costShare":60,"shelfLifeDays":5},{"variantId":"$_mince","expectedPct":45,"costShare":40}]}]}');
    }
    if (path.endsWith('/yield/runs') && o.method == 'GET') {
      return jsonResponse(
          '{"data":{"runs":[{"id":"01a0b100-0000-7000-8000-0000000000r1","storeId":"$_store","templateName":"Side of beef","inputQty":100,"inputCost":500.00,"outputQty":78,"lossQty":22,"lossPct":22,"expectedLossQty":20,"lossVariance":2,"lossAtCost":110.00,"reference":"Monday side","recordedAt":"2026-09-24T09:00:00Z","outputs":[]}],"totals":{"runs":1,"inputQty":100,"outputQty":78,"lossQty":22,"expectedLossQty":20,"lossAtCost":110.00}}}');
    }
    if (path.endsWith('/yield/runs') && o.method == 'POST') {
      return jsonResponse(
          '{"data":{"id":"01a0b100-0000-7000-8000-0000000000r2","lossQty":22,"expectedLossQty":20,"lossAtCost":110.00}}',
          201);
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(WidgetTester tester, {String role = 'MANAGER'}) async {
  tester.view.physicalSize = const Size(1100, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
    ],
    child: const MaterialApp(home: Scaffold(body: InventoryYieldTab())),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  // Dates are written with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);
  testWidgets('the template, its cuts and this month\'s loss against expected are shown',
      (tester) async {
    await _pump(tester);
    expect(find.text('Side of beef'), findsOneWidget);
    expect(find.textContaining('expected loss 20 %'), findsOneWidget);
    expect(find.textContaining('100 in, 78 out'), findsOneWidget);
    expect(find.textContaining('lost 22 (22 %) against 20 expected, 2 over'), findsOneWidget);
    expect(find.byKey(const Key('yield-total-loss')), findsOneWidget);
    expect(find.textContaining('22 of 100 in, against 20 expected'), findsOneWidget);
  });

  testWidgets('Record a breakdown fills the cuts in at what the template expects and posts',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('yield-record')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('yield-store')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Butchery').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('yield-template')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Side of beef').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('yield-input')), '100');
    await tester.pumpAndSettle();
    // Filled in at 35 % and 45 % of what went in.
    expect(
        tester.widget<TextField>(find.byKey(const Key('yield-out-$_sirloin'))).controller!.text,
        '35');
    expect(
        tester.widget<TextField>(find.byKey(const Key('yield-out-$_mince'))).controller!.text,
        '45');
    await tester.enterText(find.byKey(const Key('yield-out-$_sirloin')), '34');
    await tester.enterText(find.byKey(const Key('yield-out-$_mince')), '44');
    await tester.enterText(find.byKey(const Key('yield-reference')), 'Monday side');
    await tester.tap(find.byKey(const Key('yield-run-save')));
    await tester.pumpAndSettle();
    final post = server.requests.lastWhere((r) => r.method == 'POST');
    expect(post.path, endsWith('/admin/inventory/yield/runs'));
    final body = post.data is String ? jsonDecode(post.data as String) : post.data;
    expect(body['storeId'], _store);
    expect(body['templateId'], _template);
    expect(body['inputQty'], 100);
    expect(body['outputs'], [
      {'variantId': _sirloin, 'qty': 34},
      {'variantId': _mince, 'qty': 44},
    ]);
    expect(body['reference'], 'Monday side');
    expect(find.textContaining('22 lost against 20 expected'), findsOneWidget);
  });

  testWidgets('New template refuses before posting until the primal is picked', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('yield-new-template')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('yield-name')), 'Whole salmon');
    await tester.enterText(find.byKey(const Key('yield-pct-0')), '55');
    await tester.pumpAndSettle();
    expect(find.text('Expected loss: 45 %'), findsOneWidget);
    await tester.tap(find.byKey(const Key('yield-save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('yield-refusal')), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
  });

  testWidgets('a storekeeper may record a breakdown but not define a template', (tester) async {
    await _pump(tester, role: 'STOREKEEPER');
    expect(find.byKey(const Key('yield-new-template')), findsNothing);
    expect(find.byKey(const Key('yield-end-$_template')), findsNothing);
    expect(find.byKey(const Key('yield-record')), findsOneWidget);
  });
}
