import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/inventory_network_tab.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The Inventory screen's "Depot & shops" tab: the network listed with what a
// shop buys direct; Serve a shop putting the shop, the warehouse and the lead
// time; Propose transfers posting the warehouse with an idempotency key; the
// drafts listed with the reason on each line, released or discarded; a cashier
// reading without the buttons.
// ---------------------------------------------------------------------------

const _dc = '01a0d900-0000-7000-8000-0000000000d1';
const _leeds = '01a0d900-0000-7000-8000-0000000000e1';
const _york = '01a0d900-0000-7000-8000-0000000000e2';
const _apples = '01a0d900-0000-7000-8000-0000000000a1';
const _pears = '01a0d900-0000-7000-8000-0000000000a2';
const _transfer = '01a0d900-0000-7000-8000-0000000000f1';
const _line = '01a0d900-0000-7000-8000-0000000000f2';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/admin/stores')) {
      return jsonResponse('{"data":['
          '{"id":"$_dc","name":"Leeds DC","code":"DC","type":"WAREHOUSE","status":"ACTIVE"},'
          '{"id":"$_leeds","name":"Leeds","code":"LDS","type":"STORE","status":"ACTIVE"},'
          '{"id":"$_york","name":"York","code":"YRK","type":"STORE","status":"ACTIVE"}'
          '],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/network/serving') && o.method == 'GET') {
      return jsonResponse('{"data":[{"id":"x","storeId":"$_leeds","warehouseId":"$_dc","leadTimeDays":2,"direct":["$_pears"]}]}');
    }
    if (path.endsWith('/network/serving') && o.method == 'PUT') {
      return jsonResponse('{"data":{"id":"x","storeId":"$_york","warehouseId":"$_dc","leadTimeDays":1,"direct":[]}}');
    }
    if (path.endsWith('/network/proposals')) {
      return jsonResponse(
          '{"data":{"id":"r","warehouseId":"$_dc","coverDays":7,"shops":2,"transfers":1,"lines":1,"shortLines":1,"transferIds":["$_transfer"],"transferOrders":[]}}',
          201);
    }
    if (path.endsWith('/transfers/$_transfer/release') || path.endsWith('/transfers/$_transfer/cancel')) {
      return jsonResponse('{"data":{"id":"$_transfer","fromStoreId":"$_dc","toStoreId":"$_leeds","status":"PENDING","lines":[]}}');
    }
    if (path.endsWith('/admin/inventory/transfers')) {
      return jsonResponse('{"data":[{"id":"$_transfer","fromStoreId":"$_dc","toStoreId":"$_leeds","transferType":"INTRANSIT",'
          '"status":"DRAFT","source":"PROPOSAL","lines":[{"id":"$_line","variantId":"$_apples","requestedQty":19.5,'
          '"reason":"on hand 4 + inbound 0 = 4 ≤ reorder point 10; back to it plus 1.5/day over 9 days (no forecast)"}]}]}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(WidgetTester tester, {String role = 'MANAGER'}) async {
  tester.view.physicalSize = const Size(1200, 1800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
    ],
    child: const MaterialApp(home: Scaffold(body: InventoryNetworkTab())),
  ));
  await tester.pumpAndSettle();
  return server;
}

Map<String, dynamic> _body(RequestOptions r) =>
    (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>;

void main() {
  testWidgets('the network and the warehouse\'s drafts are listed, each line with its reason', (tester) async {
    await _pump(tester);
    expect(find.byKey(const Key('serving-$_leeds')), findsOneWidget);
    expect(find.text('Leeds ← Leeds DC'), findsOneWidget);
    expect(find.textContaining('2 days from the warehouse · bought direct'), findsOneWidget);
    expect(find.byKey(const Key('draft-$_transfer')), findsOneWidget);
    expect(find.textContaining('≤ reorder point 10'), findsOneWidget);
    expect(find.text('To Leeds'), findsOneWidget);
  });

  testWidgets('Serve a shop puts the shop, the warehouse and the lead time', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('network-serve')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('serve-shop')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('York').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('serve-warehouse')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Leeds DC').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('serve-lead')), '1');
    await tester.tap(find.byKey(const Key('serve-save')));
    await tester.pumpAndSettle();
    final put = server.requests.firstWhere((r) => r.method == 'PUT' && r.path.endsWith('/network/serving'));
    expect(_body(put), {'storeId': _york, 'warehouseId': _dc, 'leadTimeDays': 1});
  });

  testWidgets('Propose transfers posts the warehouse with a key; a draft is released', (tester) async {
    final server = await _pump(tester, role: 'STOREKEEPER');
    expect(find.byKey(const Key('network-serve')), findsNothing, reason: 'the network is management\'s');
    await tester.tap(find.byKey(const Key('network-propose')));
    await tester.pumpAndSettle();
    final post = server.requests.firstWhere((r) => r.path.endsWith('/network/proposals'));
    expect(_body(post), {'warehouseId': _dc});
    expect(post.headers['Idempotency-Key'], isNotNull);
    expect(find.textContaining('1 transfers proposed, 1 lines cut'), findsOneWidget);
    await tester.tap(find.byKey(const Key('draft-release-$_transfer')));
    await tester.pumpAndSettle();
    expect(server.requests.where((r) => r.path.endsWith('/transfers/$_transfer/release')).length, 1);
  });

  testWidgets('a cashier sees the network and the drafts without the buttons', (tester) async {
    await _pump(tester, role: 'CASHIER');
    expect(find.byKey(const Key('serving-$_leeds')), findsOneWidget);
    expect(find.byKey(const Key('network-serve')), findsNothing);
    expect(find.byKey(const Key('network-propose')), findsNothing);
    expect(find.byKey(const Key('draft-release-$_transfer')), findsNothing);
    expect(find.byKey(const Key('serving-remove-$_leeds')), findsNothing);
  });
}
