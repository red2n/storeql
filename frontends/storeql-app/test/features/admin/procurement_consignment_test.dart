import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/consignment_tab.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The Procurement screen's Consignment tab: what each supplier is owed for its
// stock that sold and is not yet on a statement, Settle posting the period and
// saying what it made, a refusal shown by name, statements listed, and no
// Settle for staff who may not.
// ---------------------------------------------------------------------------

const _supplier = '01a0b000-0000-7000-8000-0000000000a1';
const _variant = '01a0b000-0000-7000-8000-0000000000b1';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool refuse = false;
  bool settledOnce = false;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/suppliers')) {
      return jsonResponse(
          '{"data":[{"id":"$_supplier","name":"Sale or Return Ltd","vatRegistered":true,"currency":"GBP","paymentTermsDays":30}]}');
    }
    if (o.path.endsWith('/admin/consignment/sales')) {
      if (settledOnce) return jsonResponse('{"data":[]}');
      return jsonResponse('{"data":['
          '{"id":"01a0b000-0000-7000-8000-0000000000c1","supplierId":"$_supplier","variantId":"$_variant","qty":3,"unitCost":3.0,"amount":9.0,"currency":"GBP","soldOn":"2026-09-20","settled":false},'
          '{"id":"01a0b000-0000-7000-8000-0000000000c2","supplierId":"$_supplier","variantId":"$_variant","qty":2,"unitCost":3.0,"amount":6.0,"currency":"GBP","soldOn":"2026-09-22","settled":false}'
          ']}');
    }
    if (o.path.endsWith('/admin/consignment/settlements') && o.method == 'POST') {
      if (refuse) {
        return jsonResponse(
            '{"code":"PURCHASE_CONSIGNMENT_NOTHING_TO_SETTLE","status":409,"error":{"code":"PURCHASE_CONSIGNMENT_NOTHING_TO_SETTLE","message":"no unsettled consignment sale of this supplier between 2026-09-01 and 2026-09-24"}}',
            409);
      }
      settledOnce = true;
      return jsonResponse(
          '{"data":{"id":"01a0b000-0000-7000-8000-0000000000d2","supplierId":"$_supplier","reference":"CS-7F0A5090","periodFrom":"2026-09-01","periodTo":"2026-09-24","currency":"GBP","total":15.0,"salesCount":2}}',
          201);
    }
    if (o.path.endsWith('/admin/consignment/settlements')) {
      return jsonResponse('{"data":['
          '{"id":"01a0b000-0000-7000-8000-0000000000d1","supplierId":"$_supplier","reference":"CS-AUGUST01","periodFrom":"2026-08-01","periodTo":"2026-08-31","currency":"GBP","total":42.5,"salesCount":7}'
          ']}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(WidgetTester tester, {String role = 'MANAGER', bool refuse = false}) async {
  tester.view.physicalSize = const Size(1100, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server()..refuse = refuse;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
    ],
    child: const MaterialApp(home: Scaffold(body: ConsignmentTab())),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  // Dates are written with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);
  testWidgets('each supplier is owed the sum of its unsettled sales; statements are listed',
      (tester) async {
    await _pump(tester);
    expect(find.text('Sale or Return Ltd'), findsOneWidget);
    expect(find.textContaining('2 sales'), findsOneWidget);
    expect(find.textContaining('15.00'), findsOneWidget);
    expect(find.textContaining('CS-AUGUST01'), findsOneWidget);
    expect(find.textContaining('7 sales'), findsOneWidget);
  });

  testWidgets('Settle posts the supplier and the period and says what statement it made',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('settle-$_supplier')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('settle-from')), '2026-09-01');
    await tester.enterText(find.byKey(const Key('settle-to')), '2026-09-24');
    await tester.tap(find.byKey(const Key('settle-save')));
    await tester.pumpAndSettle();
    final post = server.requests.lastWhere((r) => r.method == 'POST');
    expect(post.path, endsWith('/admin/consignment/settlements'));
    final body = post.data is String ? jsonDecode(post.data as String) : post.data;
    expect(body, {'supplierId': _supplier, 'from': '2026-09-01', 'to': '2026-09-24'});
    expect(find.textContaining('Statement CS-7F0A5090'), findsOneWidget);
    expect(find.textContaining('for 2 sales of Sale or Return Ltd'), findsOneWidget);
    expect(find.text('Nothing owed on consignment'), findsOneWidget);
  });

  testWidgets('a refusal is shown by its reason', (tester) async {
    await _pump(tester, refuse: true);
    await tester.tap(find.byKey(const Key('settle-$_supplier')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('settle-save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('settle-refusal')), findsOneWidget);
    expect(find.textContaining('no unsettled consignment sale'), findsOneWidget);
  });

  testWidgets('staff who may not settle read the tab without the button', (tester) async {
    await _pump(tester, role: 'STOREKEEPER');
    expect(find.text('Sale or Return Ltd'), findsOneWidget);
    expect(find.byKey(const Key('settle-$_supplier')), findsNothing);
  });
}
