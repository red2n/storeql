import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/inventory_waves_tab.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The Inventory screen's "Picking & putaway" tab: the orders waiting at the
// store and the waves; Build wave posting the store with an idempotency key;
// the wave's lines in walk order under their zone names, the picks saved and
// the wave completed; a batch on the putaway list named by its number and
// placed in a zone; a cashier reading without the buttons.
// ---------------------------------------------------------------------------

const _store = '01a0b400-0000-7000-8000-000000000001';
const _zoneA = '01a0b400-0000-7000-8000-0000000000a1';
const _zoneB = '01a0b400-0000-7000-8000-0000000000a2';
const _wave = '01a0b400-0000-7000-8000-0000000000w1';
const _line1 = '01a0b400-0000-7000-8000-0000000000l1';
const _line2 = '01a0b400-0000-7000-8000-0000000000l2';
const _task = '01a0b400-0000-7000-8000-0000000000t1';
const _task2 = '01a0b400-0000-7000-8000-0000000000t2';
const _order1 = '01a0b400-0000-7000-8000-0000000000o1';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  /// When set, saving the picks is refused, as the server does for more than was directed.
  bool refusePicks = false;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/admin/stores')) {
      return jsonResponse(
          '{"data":[{"id":"$_store","name":"Leeds","code":"LDS","type":"STORE","status":"ACTIVE"}],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/stores/$_store/zones')) {
      return jsonResponse(
          '{"data":[{"id":"$_zoneA","storeId":"$_store","name":"Aisle A","code":"A","type":"AISLE","status":"ACTIVE"},{"id":"$_zoneB","storeId":"$_store","name":"Cold room","code":"CR","type":"COLD_ROOM","status":"ACTIVE"}],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/waves/awaiting')) {
      return jsonResponse(
          '{"data":[{"orderId":"$_order1","storeId":"$_store","fulfilmentType":"DELIVERY","confirmedAt":"2026-09-25T08:00:00Z","lines":[{"variantId":"01a0b400-0000-7000-8000-0000000000v1","qtyOutstanding":3}]}]}');
    }
    if (path.endsWith('/waves/$_wave/picks') && refusePicks) {
      return jsonResponse(
          '{"code":"INVENTORY_WAVE_PICK_EXCEEDS_LINE","title":"Bad Request","status":400,"detail":"more than directed","error":{"code":"INVENTORY_WAVE_PICK_EXCEEDS_LINE","message":"more than directed"}}',
          400);
    }
    if (path.endsWith('/waves/$_wave/picks') || path.endsWith('/waves/$_wave/complete')) {
      return jsonResponse('{"data":{"id":"$_wave","storeId":"$_store","status":"COMPLETED","createdAt":"2026-09-25T09:00:00Z","orderCount":1,"lines":[]}}');
    }
    if (path.endsWith('/waves/$_wave')) {
      return jsonResponse('{"data":{"id":"$_wave","storeId":"$_store","status":"OPEN","createdAt":"2026-09-25T09:00:00Z","orderCount":1,"lines":['
          '{"id":"$_line1","walkOrder":1,"zoneId":"$_zoneA","batchId":"b1","batchNo":"A-NEW","variantId":"01a0b400-0000-7000-8000-0000000000v1","directedQty":2,"orders":[{"orderId":"$_order1","qty":2}]},'
          '{"id":"$_line2","walkOrder":2,"zoneId":"$_zoneB","batchId":"b2","batchNo":"A-OLD","variantId":"01a0b400-0000-7000-8000-0000000000v1","directedQty":1,"orders":[{"orderId":"$_order1","qty":1}]}]}}');
    }
    if (path.endsWith('/waves') && o.method == 'POST') {
      return jsonResponse('{"data":{"id":"$_wave","storeId":"$_store","status":"OPEN","createdAt":"2026-09-25T09:00:00Z","orderCount":1,"lines":[{"id":"$_line1","walkOrder":1,"zoneId":"$_zoneA","batchId":"b1","variantId":"v","directedQty":2,"orders":[]}]}}', 201);
    }
    if (path.endsWith('/waves') && o.method == 'GET') {
      return jsonResponse('{"data":[{"id":"$_wave","storeId":"$_store","status":"OPEN","createdAt":"2026-09-25T09:00:00Z","orderCount":1,"lines":[]}]}');
    }
    if (path.endsWith('/putaway/tasks')) {
      return jsonResponse(
          '{"data":[{"id":"$_task","storeId":"$_store","batchId":"01a0b400-0000-7000-8000-0000000000b9","batchNo":"LOT-ODD-7","variantId":"01a0b400-0000-7000-8000-0000000000v2","qty":3,"status":"OPEN"},'
          '{"id":"$_task2","storeId":"$_store","batchId":"01a0b400-0000-7000-8000-0000000000b8","variantId":"01a0b400-0000-7000-8000-0000000000v2","qty":1,"status":"OPEN"}]}');
    }
    if (path.endsWith('/putaway/tasks/$_task/place')) {
      return jsonResponse('{"data":{"id":"$_task","status":"PLACED","placedZoneId":"$_zoneB"}}');
    }
    if (path.endsWith('/putaway/rules')) {
      return jsonResponse('{"data":[{"id":"01a0b400-0000-7000-8000-0000000000r1","storeId":"$_store","zoneId":"$_zoneB"}]}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(WidgetTester tester, {String role = 'STOREKEEPER'}) async {
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
    child: const MaterialApp(home: Scaffold(body: InventoryWavesTab())),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  setUpAll(initializeDateFormatting);
  testWidgets('the orders waiting at the store and its waves are listed', (tester) async {
    await _pump(tester);
    expect(find.byKey(const Key('awaiting-$_order1')), findsOneWidget);
    expect(find.textContaining('· delivery'), findsOneWidget);
    expect(find.byKey(const Key('wave-$_wave')), findsOneWidget);
    expect(find.textContaining('1 orders'), findsOneWidget);
    // The wave's status in words, its day as a date.
    expect(find.text('To pick'), findsOneWidget);
    expect(find.textContaining('OPEN'), findsNothing);
    expect(find.textContaining('2026-09-25'), findsNothing);
    expect(find.byKey(const Key('putaway-task-$_task')), findsOneWidget);
    // A task names its batch by number; the id's tail stands in only when the batch has none.
    final named = tester.widget<Text>(find.byKey(const Key('putaway-task-title-$_task'))).data!;
    expect(named, endsWith(' · batch LOT-ODD-7'));
    expect(named, isNot(contains('000000b9')));
    expect(tester.widget<Text>(find.byKey(const Key('putaway-task-title-$_task2'))).data,
        endsWith(' · batch …000000b8'));
    expect(find.text('Anything else'), findsOneWidget);
    expect(find.text('→ Cold room'), findsOneWidget);
  });

  testWidgets('Build wave posts the store with an idempotency key and opens the wave', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('wave-build')));
    await tester.pumpAndSettle();
    final post = server.requests.firstWhere((r) => r.method == 'POST' && r.path.endsWith('/waves'));
    final body = post.data is String ? jsonDecode(post.data as String) : post.data;
    expect(body, {'storeId': _store});
    expect(post.headers['Idempotency-Key'], isNotNull);
    expect(find.textContaining('Wave built'), findsOneWidget);
    expect(find.byKey(const Key('wave-line-$_line1')), findsOneWidget);
  });

  testWidgets('the wave walks its lines by zone, saves the picks and completes', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('wave-$_wave')));
    await tester.pumpAndSettle();
    expect(tester.widget<Text>(find.byKey(const Key('wave-line-title-$_line1'))).data, startsWith('Aisle A · A-NEW'));
    expect(tester.widget<Text>(find.byKey(const Key('wave-line-title-$_line2'))).data, startsWith('Cold room · A-OLD'));
    await tester.enterText(find.byKey(const Key('wave-pick-$_line2')), '0');
    await tester.tap(find.byKey(const Key('wave-complete')));
    await tester.pumpAndSettle();
    final picks = server.requests.firstWhere((r) => r.path.endsWith('/waves/$_wave/picks'));
    final body = picks.data is String ? jsonDecode(picks.data as String) : picks.data;
    expect(body['lines'], [
      {'lineId': _line1, 'pickedQty': 2},
      {'lineId': _line2, 'pickedQty': 0},
    ]);
    expect(server.requests.where((r) => r.path.endsWith('/waves/$_wave/complete')).length, 1);
    // The completion request follows the saved picks, in that order.
    final order = server.requests.map((r) => r.path).where((p) => p.contains('/waves/$_wave/')).toList();
    expect(order.indexWhere((p) => p.endsWith('/picks')) < order.indexWhere((p) => p.endsWith('/complete')), isTrue);
  });

  testWidgets('Complete stops when the picks were refused: the wave is not completed', (tester) async {
    final server = await _pump(tester);
    server.refusePicks = true;
    await tester.tap(find.byKey(const Key('wave-$_wave')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('wave-pick-$_line1')), '9');
    await tester.tap(find.byKey(const Key('wave-complete')));
    await tester.pumpAndSettle();
    expect(server.requests.where((r) => r.path.endsWith('/waves/$_wave/picks')).length, 1);
    expect(server.requests.where((r) => r.path.endsWith('/waves/$_wave/complete')), isEmpty);
    expect(find.textContaining('more than directed'), findsOneWidget);
  });

  testWidgets('a batch on the putaway list is placed in a zone; a cashier only reads', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('putaway-zone-$_task')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Cold room').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('putaway-place-$_task')));
    await tester.pumpAndSettle();
    final place = server.requests.firstWhere((r) => r.path.endsWith('/putaway/tasks/$_task/place'));
    final body = place.data is String ? jsonDecode(place.data as String) : place.data;
    expect(body, {'zoneId': _zoneB});
    expect(find.text('Placed.'), findsOneWidget);
  });

  testWidgets('a cashier sees the lists without the buttons', (tester) async {
    await _pump(tester, role: 'CASHIER');
    expect(find.byKey(const Key('wave-build')), findsNothing);
    expect(find.byKey(const Key('putaway-place-$_task')), findsNothing);
    expect(find.byKey(const Key('putaway-rule-new')), findsNothing);
    expect(find.byKey(const Key('awaiting-$_order1')), findsOneWidget);
  });
}
