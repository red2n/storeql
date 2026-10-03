import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/sourcing_tab.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Awarding a line to a supplier other than the lowest comparable bid asks for
// a reason for that line (purchase-svc: PURCHASE_RFQ_AWARD_REASON_REQUIRED),
// the kept reason is shown when an awarded request is read, and a bid recorded
// after the due date carries a quiet "Received after the due date" note.
// ---------------------------------------------------------------------------

const _rfq = '01a0b300-0000-7000-8000-0000000000r1';
const _va = '01a0b300-0000-7000-8000-0000000000v1';
const _vb = '01a0b300-0000-7000-8000-0000000000v2';
const _s1 = '01a0b300-0000-7000-8000-0000000000s1';
const _s2 = '01a0b300-0000-7000-8000-0000000000s2';

String _detail({String status = 'ISSUED', String awards = '[]'}) =>
    '{"id":"$_rfq","reference":"RFQ-000001","title":"Autumn beef","storeId":"x","status":"$status","closesOn":"2026-10-01",'
    '"lines":[{"id":"l1","variantId":"$_va","qty":10},{"id":"l2","variantId":"$_vb","qty":5}],'
    '"bids":['
    '{"supplierId":"$_s1","supplierName":"Highland Meats","status":"QUOTED","currency":"GBP","grade":"C","receivedLate":true,"prices":[{"variantId":"$_va","unitPrice":10.00},{"variantId":"$_vb","unitPrice":4.00}]},'
    '{"supplierId":"$_s2","supplierName":"Boucherie Nord","status":"QUOTED","currency":"GBP","grade":"A","prices":[{"variantId":"$_va","unitPrice":9.00},{"variantId":"$_vb","unitPrice":5.00}]}],'
    '"comparison":{"homeCurrency":"GBP","lines":['
    '{"variantId":"$_va","qty":10,"prices":[{"supplierId":"$_s1","unitPrice":10.00,"currency":"GBP","homeUnitPrice":10.00,"lineTotal":100.00,"homeLineTotal":100.00,"lowest":false},{"supplierId":"$_s2","unitPrice":9.00,"currency":"GBP","homeUnitPrice":9.00,"lineTotal":90.00,"homeLineTotal":90.00,"lowest":true}]},'
    '{"variantId":"$_vb","qty":5,"prices":[{"supplierId":"$_s1","unitPrice":4.00,"currency":"GBP","homeUnitPrice":4.00,"lineTotal":20.00,"homeLineTotal":20.00,"lowest":true},{"supplierId":"$_s2","unitPrice":5.00,"currency":"GBP","homeUnitPrice":5.00,"lineTotal":25.00,"homeLineTotal":25.00,"lowest":false}]}],'
    '"bids":[{"supplierId":"$_s1","supplierName":"Highland Meats","status":"QUOTED","complete":true,"total":120.00,"currency":"GBP","homeTotal":120.00,"rank":2,"receivedLate":true},'
    '{"supplierId":"$_s2","supplierName":"Boucherie Nord","status":"QUOTED","complete":true,"total":115.00,"currency":"GBP","homeTotal":115.00,"rank":1}]},'
    '"awards":$awards}';

class _Server implements HttpClientAdapter {
  _Server(this.detail);
  final String detail;
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/rfqs') && o.method == 'GET') {
      return jsonResponse(
          '{"data":[{"id":"$_rfq","reference":"RFQ-000001","title":"Autumn beef","storeId":"x","status":"ISSUED","lines":2,"suppliers":2,"quotes":2}]}');
    }
    if (path.endsWith('/rfqs/$_rfq') && o.method == 'GET') return jsonResponse('{"data":$detail}');
    if (path.endsWith('/award')) return jsonResponse('{"data":{"id":"$_rfq","purchaseOrderIds":["p1"]}}');
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _open(WidgetTester tester, String detail) async {
  tester.view.physicalSize = const Size(1200, 1800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(detail);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('MANAGER')),
    ],
    child: const MaterialApp(home: Scaffold(body: SourcingTab())),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('rfq-$_rfq')));
  await tester.pumpAndSettle();
  return server;
}

FilledButton _award(WidgetTester t) => t.widget<FilledButton>(find.byKey(const Key('award-save')));

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('a bid received after the due date is marked in the request and in the comparison', (tester) async {
    await _open(tester, _detail());
    expect(find.byKey(const Key('rfq-late-$_s1')), findsOneWidget);
    expect(find.byKey(const Key('rfq-late-cmp-$_s1')), findsOneWidget);
    expect(find.text('Received after the due date'), findsNWidgets(2));
    expect(find.byKey(const Key('rfq-late-$_s2')), findsNothing);
    expect(find.byKey(const Key('rfq-late-cmp-$_s2')), findsNothing);
    // The ranking is as the server said.
    expect(tester.widget<Text>(find.byKey(const Key('rfq-total-$_s2'))).data, contains('#1'));
  });

  testWidgets('awarding the lowest bids asks for no reason and sends none', (tester) async {
    final server = await _open(tester, _detail());
    await tester.tap(find.byKey(const Key('rfq-award')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('award-reason-$_va')), findsNothing);
    expect(find.byKey(const Key('award-reason-$_vb')), findsNothing);
    await tester.tap(find.byKey(const Key('award-save')));
    await tester.pumpAndSettle();
    final post = server.requests.lastWhere((r) => r.method == 'POST');
    final body = post.data is String ? jsonDecode(post.data as String) : post.data;
    expect(body['awards'], [
      {'variantId': _va, 'supplierId': _s2},
      {'variantId': _vb, 'supplierId': _s1},
    ]);
  });

  testWidgets('choosing another supplier asks for that line\'s reason, holds Award until given, and sends it',
      (tester) async {
    final server = await _open(tester, _detail());
    await tester.tap(find.byKey(const Key('rfq-award')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('award-$_va')));
    await tester.pumpAndSettle();
    await tester.tap(find.textContaining('Highland Meats').last);
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('award-reason-$_va')), findsOneWidget);
    expect(find.byKey(const Key('award-reason-$_vb')), findsNothing);
    expect(_award(tester).onPressed, isNull, reason: 'no reason yet');

    await tester.enterText(find.byKey(const Key('award-reason-$_va')), '   ');
    await tester.pump();
    expect(_award(tester).onPressed, isNull, reason: 'blank is not a reason');

    await tester.enterText(find.byKey(const Key('award-reason-$_va')), 'Delivers to the second store');
    await tester.pump();
    expect(_award(tester).onPressed, isNotNull);
    await tester.tap(find.byKey(const Key('award-save')));
    await tester.pumpAndSettle();

    final post = server.requests.lastWhere((r) => r.method == 'POST');
    final body = post.data is String ? jsonDecode(post.data as String) : post.data;
    expect(body['awards'], [
      {'variantId': _va, 'supplierId': _s1, 'reason': 'Delivers to the second store'},
      {'variantId': _vb, 'supplierId': _s1},
    ]);
  });

  testWidgets('leaving the line unawarded needs no reason', (tester) async {
    await _open(tester, _detail());
    await tester.tap(find.byKey(const Key('rfq-award')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('award-$_va')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Not awarded').last);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('award-reason-$_va')), findsNothing);
    expect(_award(tester).onPressed, isNotNull);
  });

  testWidgets('an awarded request shows the kept reason', (tester) async {
    await _open(
        tester,
        _detail(
            status: 'AWARDED',
            awards:
                '[{"variantId":"$_va","supplierId":"$_s1","poId":"p1","unitPrice":10.00,"currency":"GBP","reason":"Delivers to the second store"},'
                '{"variantId":"$_vb","supplierId":"$_s1","poId":"p1","unitPrice":4.00,"currency":"GBP"}]'));
    expect(find.textContaining('Not the lowest bid: Delivers to the second store'), findsOneWidget);
    expect(find.byKey(const Key('rfq-award-reason-$_vb')), findsOneWidget);
    expect(find.textContaining('Not the lowest bid'), findsOneWidget);
  });
}
