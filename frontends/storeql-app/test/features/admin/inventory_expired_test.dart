import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/admin/inventory_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A stock level carries `expired` (inventory-svc, 30 Sep 2026): on hand but past
// its last day of sale, never available. The Levels tab says so in words, in
// the warning tone, only when there is some, without crowding a phone's row.
// ---------------------------------------------------------------------------

const _store = '01a0c200-0000-7000-8000-000000000001';
const _mug = '01a0c200-0000-7000-8000-0000000000v1';
const _plate = '01a0c200-0000-7000-8000-0000000000v2';

class _Server implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    final path = o.path;
    if (path.endsWith('/admin/stores')) {
      return jsonResponse('{"data":[{"id":"$_store","name":"Leeds","code":"LDS","type":"STORE",'
          '"status":"ACTIVE"}],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/admin/products/variants/resolve')) {
      return jsonResponse('{"data":['
          '{"variantId":"$_mug","productName":"Blue mug","sku":"MUG-BLU"},'
          '{"variantId":"$_plate","productName":"Side plate","sku":"PLT-SD"}]}');
    }
    if (path.endsWith('/admin/inventory/levels/summary')) {
      return jsonResponse('{"data":{"skuCount":2,"lowStockCount":0}}');
    }
    if (path.endsWith('/admin/inventory/levels')) {
      return jsonResponse('{"data":['
          '{"variantId":"$_mug","storeId":"$_store","onHand":22,"reserved":0,"available":18,"expired":4},'
          '{"variantId":"$_plate","storeId":"$_store","onHand":30,"reserved":0,"available":30}'
          '],"meta":{"nextCursor":null}}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<void> _pump(WidgetTester tester, Size size) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Server();
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('OWNER')),
    ],
    child: const MaterialApp(home: Scaffold(body: InventoryScreen())),
  ));
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('the table says how much is expired, only where some is, in the warning tone', (tester) async {
    await _pump(tester, const Size(1280, 900));
    expect(find.text('Blue mug'), findsOneWidget);
    expect(find.text('4 expired'), findsOneWidget);
    expect(find.byKey(const Key('level-expired')), findsOneWidget, reason: 'the plate has none');
    final text = tester.widget<Text>(find.byKey(const Key('level-expired')));
    final context = tester.element(find.byKey(const Key('level-expired')));
    expect(text.style?.color, context.status.warning);
    // Nothing else moved: the figures are still the server's.
    expect(find.text('22'), findsOneWidget);
    expect(find.text('18'), findsOneWidget);
  });

  testWidgets('a phone row gives it a line of its own and does not overflow', (tester) async {
    await _pump(tester, const Size(390, 800));
    expect(find.text('4 expired (past last day of sale)'), findsOneWidget);
    expect(find.textContaining('expired'), findsOneWidget);
    expect(find.textContaining('Avail: 18'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
