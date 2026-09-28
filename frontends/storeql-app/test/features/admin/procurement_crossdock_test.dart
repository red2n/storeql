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
// Cross-docking on the Procurement screen: a warehouse's draft order line
// shows where it crosses the dock to and is allocated to the shops the
// warehouse serves — typed, or filled from their needs; an order to a shop
// offers no allocation.
// ---------------------------------------------------------------------------

const _supplier = '01a0dc00-0000-7000-8000-0000000000a1';
const _po = '01a0dc00-0000-7000-8000-0000000000f1';
const _line = '01a0dc00-0000-7000-8000-0000000000f2';
const _dc = '01a0dc00-0000-7000-8000-0000000000d1';
const _leeds = '01a0dc00-0000-7000-8000-0000000000e1';
const _york = '01a0dc00-0000-7000-8000-0000000000e2';
const _beans = '01a0dc00-0000-7000-8000-0000000000b1';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  final String storeOfOrder;
  _Server(this.storeOfOrder);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/suppliers')) {
      return jsonResponse(
          '{"data":[{"id":"$_supplier","name":"Bean Co","vatRegistered":true,"currency":"GBP","paymentTermsDays":30}]}');
    }
    if (path.endsWith('/purchase-orders') && o.method == 'GET') {
      return jsonResponse('{"data":['
          '{"id":"$_po","supplierId":"$_supplier","storeId":"$storeOfOrder","status":"DRAFT","currency":"GBP","totalNet":80.0,"totalVat":0.0,"totalGross":80.0,"createdAt":"2026-09-25T09:00:00Z","source":"MANUAL"}'
          ']}');
    }
    if (path.endsWith('/purchase-orders/$_po/lines')) {
      return jsonResponse('{"data":[{"id":"$_line","variantId":"$_beans","qty":40,"unitPrice":2.0,"vatCode":"T1"}]}');
    }
    if (path.endsWith('/purchase-orders/$_po/allocations')) {
      return jsonResponse('{"data":[{"id":"x","poLineId":"$_line","variantId":"$_beans","storeId":"$_leeds","qty":25}]}');
    }
    if (path.endsWith('/lines/$_line/allocations') || path.endsWith('/lines/$_line/allocations/fill')) {
      return jsonResponse('{"data":[]}');
    }
    if (path.endsWith('/network/serving')) {
      return jsonResponse('{"data":['
          '{"id":"a","storeId":"$_leeds","warehouseId":"$_dc","leadTimeDays":1,"direct":[]},'
          '{"id":"b","storeId":"$_york","warehouseId":"$_dc","leadTimeDays":1,"direct":[]}]}');
    }
    if (path.endsWith('/stores')) {
      return jsonResponse('{"data":['
          '{"id":"$_dc","name":"Leeds DC","code":"DC","type":"WAREHOUSE","status":"ACTIVE","country":"GB"},'
          '{"id":"$_leeds","name":"Leeds","code":"LDS","type":"STORE","status":"ACTIVE","country":"GB"},'
          '{"id":"$_york","name":"York","code":"YRK","type":"STORE","status":"ACTIVE","country":"GB"}]}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _openOrder(WidgetTester tester, String storeOfOrder) async {
  tester.view.physicalSize = const Size(1200, 1100);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(storeOfOrder);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('MANAGER')),
    ],
    child: const MaterialApp(home: ProcurementScreen()),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Draft')); // the status badge's word
  await tester.pumpAndSettle();
  return server;
}

Map<String, dynamic> _body(RequestOptions r) =>
    (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>;

void main() {
  testWidgets('a warehouse order\'s line says where it crosses the dock to and is allocated by shop', (tester) async {
    final server = await _openOrder(tester, _dc);
    expect(find.textContaining('cross-docked to Leeds 25'), findsOneWidget);
    await tester.tap(find.byKey(const Key('po-line-allocate-$_line')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('allocate-$_leeds')), findsOneWidget);
    expect(find.byKey(const Key('allocate-$_york')), findsOneWidget);
    await tester.enterText(find.byKey(const Key('allocate-$_york')), '15');
    await tester.tap(find.byKey(const Key('allocate-save')));
    await tester.pumpAndSettle();
    final put = server.requests.firstWhere((r) => r.method == 'PUT' && r.path.endsWith('/lines/$_line/allocations'));
    expect(_body(put)['allocations'], [
      {'storeId': _leeds, 'qty': 25.0},
      {'storeId': _york, 'qty': 15.0},
    ]);
  });

  testWidgets('Fill from the shops\' needs posts the fill', (tester) async {
    final server = await _openOrder(tester, _dc);
    await tester.tap(find.byKey(const Key('po-line-allocate-$_line')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('allocate-fill')));
    await tester.pumpAndSettle();
    expect(server.requests.where((r) => r.method == 'POST' && r.path.endsWith('/lines/$_line/allocations/fill')).length, 1);
  });

  testWidgets('an order to a shop offers no allocation', (tester) async {
    await _openOrder(tester, _leeds);
    expect(find.byKey(const Key('po-line-allocate-$_line')), findsNothing);
  });
}
