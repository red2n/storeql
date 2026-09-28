import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/fulfilment_screen.dart';
import 'package:storeql_app/shared/widgets/page_header.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The Fulfilment screen (ship-from-store and dark-store picking): a store's
// queue by stage — what waits to be picked, what is packed for the courier,
// what is ready to collect, what left today — with Dispatch posting the
// carrier, reference and parcels, and Collected posting who took it. A
// warehouse is never offered; a dark store is named as one.
//
// The page frame: titled *Fulfilment* with refresh as the header's action,
// capped at the content width, its cards 8 apart. Outstanding lines counts
// lines; the Substitute dialog finds what was packed by name or SKU, never by
// variant id; *Handed over today* starts at the store's own midnight.
// ---------------------------------------------------------------------------

const _leeds = '01a0d930-0000-7000-8000-0000000000e1';
const _dark = '01a0d930-0000-7000-8000-0000000000e2';
const _dc = '01a0d930-0000-7000-8000-0000000000d1';
const _packed = '01a0d930-0000-7000-8000-0000000000f1';
const _packed2 = '01a0d930-0000-7000-8000-0000000000f6';
const _ready = '01a0d930-0000-7000-8000-0000000000f2';
const _gone = '01a0d930-0000-7000-8000-0000000000f3';
const _owing = '01a0d930-0000-7000-8000-0000000000f4';
const _noSubs = '01a0d930-0000-7000-8000-0000000000f5';
const _apples = '01a0d930-0000-7000-8000-0000000000a1';
const _pears = '01a0d930-0000-7000-8000-0000000000a2';
const _plums = '01a0d930-0000-7000-8000-0000000000a3';
const _bread = '01a0d930-0000-7000-8000-0000000000a4';
const _rye = '01a0d930-0000-7000-8000-0000000000a5';
const _ryeProduct = '01a0d930-0000-7000-8000-0000000000b5';

/// What product-svc's resolve knows, by variant: product name and SKU.
const _names = {
  _apples: ('Apples', 'APL'),
  _bread: ('Bread', 'BRD'),
  _plums: ('Plums', 'PLU'),
};

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  String _order(String id, String type, {String? handover, String? slot}) {
    final tail = handover == null ? '' : ',"handover":$handover';
    final slotTail = slot == null ? '' : ',"slot":$slot';
    return '{"id":"$id","storeId":"$_leeds","channel":"ONLINE","fulfilmentType":"$type","status":"FULFILLED",'
        '"total":12.5,"currency":"GBP","createdAt":"2026-09-25T09:00:00Z"$tail$slotTail}';
  }

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/admin/stores')) {
      return jsonResponse('{"data":['
          '{"id":"$_dc","name":"Leeds DC","code":"DC","type":"WAREHOUSE","status":"ACTIVE"},'
          '{"id":"$_leeds","name":"Leeds","code":"LDS","type":"STORE","status":"ACTIVE","timezone":"Europe/London"},'
          '{"id":"$_dark","name":"Online hub","code":"HUB","type":"DARK_STORE","status":"ACTIVE"}'
          '],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/orders/owing')) {
      return jsonResponse('{"data":['
          '{"orderId":"$_owing","status":"CONFIRMED","fulfilmentType":"DELIVERY","allowSubstitutions":true,'
          '"createdAt":"2026-09-25T09:00:00Z","lines":[{"variantId":"$_apples","qty":3,"fulfilledQty":1,"shortQty":0,"outstandingQty":2},'
          '{"variantId":"$_bread","qty":2,"fulfilledQty":0,"shortQty":0,"outstandingQty":2}]},'
          '{"orderId":"$_noSubs","status":"CONFIRMED","fulfilmentType":"PICKUP","allowSubstitutions":false,'
          '"createdAt":"2026-09-25T09:05:00Z","lines":[{"variantId":"$_apples","qty":1,"fulfilledQty":0,"shortQty":0,"outstandingQty":1}]}'
          ']}');
    }
    if (path.endsWith('/lines/$_apples/substitutes')) {
      return jsonResponse('{"data":['
          '{"variantId":"$_pears","productName":"Pears","sku":"PEA","available":5},'
          '{"variantId":"$_plums","productName":"","sku":"PLU","available":0}'
          ']}');
    }
    if (path.endsWith('/admin/products/variants/resolve')) {
      final ids = (o.queryParameters['ids'] as String? ?? '').split(',');
      return jsonResponse(jsonEncode({
        'data': [
          for (final id in ids)
            if (_names[id] != null)
              {'variantId': id, 'productName': _names[id]!.$1, 'sku': _names[id]!.$2},
        ],
      }));
    }
    if (path.endsWith('/catalog/products')) {
      final q = (o.queryParameters['q'] as String?)?.toLowerCase() ?? '';
      final sku = o.queryParameters['sku'] as String?;
      final hit = sku == 'RYE' || (q.isNotEmpty && 'rye loaf'.contains(q));
      return jsonResponse(jsonEncode({
        'data': [
          if (hit)
            {'id': _ryeProduct, 'name': 'Rye loaf', 'status': 'ACTIVE', 'createdAt': '2026-09-01T09:00:00Z'},
        ],
      }));
    }
    if (path.endsWith('/admin/products/$_ryeProduct/variants')) {
      return jsonResponse(jsonEncode({
        'data': [
          {'id': _rye, 'productId': _ryeProduct, 'sku': 'RYE', 'attributes': '{}', 'status': 'ACTIVE'},
        ],
      }));
    }
    if (path.endsWith('/short') || path.endsWith('/substitute')) {
      return jsonResponse('{"data":${_order(_owing, 'DELIVERY')}}');
    }
    if (path.endsWith('/waves/awaiting')) {
      return jsonResponse('{"data":[{"orderId":"a"},{"orderId":"b"},{"orderId":"c"}]}');
    }
    if (path.endsWith('/orders') && o.method == 'GET') {
      final q = o.queryParameters;
      if (q['handover'] == 'DONE') {
        return jsonResponse('{"data":[${_order(_gone, 'DELIVERY', handover: '{"kind":"DISPATCHED","carrier":"DPD","reference":"1Z999","at":"2026-09-25T10:00:00Z"}')}]}');
      }
      if (q['fulfilmentType'] == 'DELIVERY') {
        return jsonResponse('{"data":[${_order(_packed, 'DELIVERY', slot: '{"date":"2026-09-27","startTime":"17:00","endTime":"19:00","timeZone":"Europe/London"}')},${_order(_packed2, 'DELIVERY')}]}');
      }
      if (q['fulfilmentType'] == 'PICKUP') return jsonResponse('{"data":[${_order(_ready, 'PICKUP')}]}');
    }
    if (path.endsWith('/dispatch') || path.endsWith('/collect')) {
      return jsonResponse('{"data":${_order(_packed, 'DELIVERY', handover: '{"kind":"DISPATCHED","carrier":"DPD"}')}}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _open(WidgetTester tester,
    {Size size = const Size(1200, 1400), DateTime? now}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('STOREKEEPER')),
      if (now != null) fulfilmentClockProvider.overrideWithValue(() => now),
    ],
    child: const MaterialApp(home: Scaffold(body: FulfilmentScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

Map<String, dynamic> _body(RequestOptions r) =>
    (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>;

/// The last *Handed over today* read for [store].
RequestOptions _handedToday(_Server server, String store) => server.requests.lastWhere((r) =>
    r.method == 'GET' &&
    r.path.endsWith('/orders') &&
    r.queryParameters['handover'] == 'DONE' &&
    r.queryParameters['store'] == store);

/// The `handedFrom` of the last *Handed over today* read for [store]: when the
/// order was handed over, never `from` (when it was placed).
String? _handedFrom(_Server server, String store) {
  final q = _handedToday(server, store).queryParameters;
  expect(q.containsKey('from'), isFalse, reason: 'from= is when the order was placed');
  return q['handedFrom'] as String?;
}

void main() {
  setUpAll(initializeDateFormatting);
  // The windows below are read the British way (Sun 27 Sept): pinned, since the
  // app itself assumes no country for English.
  setUp(() => Intl.defaultLocale = 'en_GB');

  testWidgets('the queue is shown by stage for the store, and a warehouse is never offered',
      (tester) async {
    await _open(tester);
    expect(find.text('3 orders waiting — picked on Inventory › Picking & putaway.'), findsOneWidget);
    expect(find.byKey(const Key('queued-$_packed')), findsOneWidget);
    expect(find.byKey(const Key('queued-$_ready')), findsOneWidget);
    expect(find.text('Dispatched · DPD 1Z999'), findsOneWidget);
    await tester.tap(find.byKey(const Key('fulfilment-store')));
    await tester.pumpAndSettle();
    expect(find.text('Online hub · dark store'), findsOneWidget);
    expect(find.text('Leeds DC'), findsNothing);
  });

  testWidgets('a queued order says its total as money', (tester) async {
    await _open(tester);
    expect(find.textContaining('£12.50'), findsWidgets);
    expect(find.textContaining('GBP 12.50'), findsNothing);
  });

  testWidgets('on a phone the actions sit under the line, so the name keeps the width',
      (tester) async {
    await _open(tester, size: const Size(390, 2400));
    expect(tester.takeException(), isNull);
    final line = find.text('2 of 3 outstanding');
    final substitute = find.byKey(const Key('substitute-$_owing-$_apples'));
    await tester.ensureVisible(substitute);
    expect(tester.getTopLeft(substitute).dy, greaterThanOrEqualTo(tester.getBottomLeft(line).dy));
    final dispatch = find.byKey(const Key('dispatch-$_packed'));
    await tester.ensureVisible(dispatch);
    final order = find.descendant(
        of: find.byKey(const Key('queued-$_packed')), matching: find.textContaining('£12.50'));
    expect(tester.getTopLeft(dispatch).dy, greaterThanOrEqualTo(tester.getBottomLeft(order).dy));
    // The page's gutter is the phone's 16.
    expect(tester.takeException(), isNull);
  });

  testWidgets('Dispatch posts the carrier, reference and parcels', (tester) async {
    final server = await _open(tester);
    await tester.tap(find.byKey(const Key('dispatch-$_packed')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('dispatch-save')));
    await tester.pumpAndSettle();
    expect(find.text('Say who is carrying it.'), findsOneWidget, reason: 'a carrier is required');
    await tester.enterText(find.byKey(const Key('dispatch-carrier')), 'DPD');
    await tester.enterText(find.byKey(const Key('dispatch-reference')), '1Z999');
    await tester.enterText(find.byKey(const Key('dispatch-parcels')), '2');
    await tester.tap(find.byKey(const Key('dispatch-save')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere((r) => r.method == 'POST' && r.path.endsWith('/orders/$_packed/dispatch'));
    expect(_body(post), {'carrier': 'DPD', 'reference': '1Z999', 'parcels': 2});
    expect(find.text('Dispatched with DPD.'), findsOneWidget);
  });

  testWidgets('Collected posts who took it, and nothing when nobody was noted', (tester) async {
    final server = await _open(tester);
    await tester.tap(find.byKey(const Key('collect-$_ready')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('collect-who')), 'Sam Shopper');
    await tester.tap(find.byKey(const Key('collect-save')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere((r) => r.method == 'POST' && r.path.endsWith('/orders/$_ready/collect'));
    expect(_body(post), {'collectedBy': 'Sam Shopper'});
  });

  // ── substitutions for out-of-stock online lines ────────────────────────────

  testWidgets('outstanding lines are listed by name, and Substitute posts the chosen stand-in',
      (tester) async {
    final server = await _open(tester);
    expect(find.byKey(const Key('owing-count')), findsOneWidget);
    expect(find.text('2 of 3 outstanding'), findsOneWidget);
    expect(find.text('Apples'), findsNWidgets(2), reason: 'names, not ids');
    expect(find.byKey(const Key('substitute-$_owing-$_apples')), findsOneWidget);
    expect(find.byKey(const Key('substitute-$_noSubs-$_apples')), findsNothing,
        reason: 'the shopper said no: only Short is offered');
    expect(find.byKey(const Key('short-$_noSubs-$_apples')), findsOneWidget);
    await tester.tap(find.byKey(const Key('substitute-$_owing-$_apples')));
    await tester.pumpAndSettle();
    expect(find.text('Pears'), findsOneWidget);
    expect(find.text('PEA · 5 available'), findsOneWidget);
    await tester.tap(find.byKey(const Key('substitute-save')));
    await tester.pumpAndSettle();
    expect(find.text('Say what you packed.'), findsOneWidget);
    await tester.tap(find.byKey(const Key('suggestion-$_pears')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('substitute-save')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere(
        (r) => r.method == 'POST' && r.path.endsWith('/orders/$_owing/lines/$_apples/substitute'));
    expect(_body(post), {'substituteVariantId': _pears, 'qty': 2});
    expect(post.headers['Idempotency-Key'], isNotNull);
    expect(find.text('Substituted. The shopper is told and pays no more.'), findsOneWidget);
  });

  testWidgets('Short posts the quantity and reason, at most what the line still owes',
      (tester) async {
    final server = await _open(tester);
    await tester.tap(find.byKey(const Key('short-$_owing-$_apples')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('short-qty')), '5');
    await tester.tap(find.byKey(const Key('short-save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('short-error')), findsOneWidget);
    await tester.enterText(find.byKey(const Key('short-qty')), '1');
    await tester.enterText(find.byKey(const Key('short-reason')), 'last one bruised');
    await tester.tap(find.byKey(const Key('short-save')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere(
        (r) => r.method == 'POST' && r.path.endsWith('/orders/$_owing/lines/$_apples/short'));
    expect(_body(post), {'qty': 1, 'reason': 'last one bruised'});
    expect(find.text('Closed short. The shopper is told and refunded.'), findsOneWidget);
  });

  // ── the page frame ─────────────────────────────────────────────────────────

  testWidgets('the page is titled Fulfilment, with refresh as its action and the Store field under it',
      (tester) async {
    final server = await _open(tester);
    final header = find.widgetWithText(PageHeader, 'Fulfilment');
    expect(header, findsOneWidget);
    final refresh = find.descendant(of: header, matching: find.byTooltip('Refresh'));
    expect(refresh, findsOneWidget, reason: "refresh is the header's action");
    expect(tester.getTopLeft(find.byKey(const Key('fulfilment-store'))).dy,
        greaterThanOrEqualTo(tester.getBottomLeft(header).dy));
    final before = server.requests.where((r) => r.path.endsWith('/orders/owing')).length;
    await tester.tap(refresh);
    await tester.pumpAndSettle();
    expect(server.requests.where((r) => r.path.endsWith('/orders/owing')).length, before + 1);
  });

  testWidgets('cards stand 8 apart, in Outstanding lines and in each queue', (tester) async {
    await _open(tester);
    double gap(String a, String b) =>
        tester.getTopLeft(find.byKey(Key(b))).dy - tester.getBottomLeft(find.byKey(Key(a))).dy;
    expect(gap('owing-$_owing', 'owing-$_noSubs'), 8);
    expect(gap('queued-$_packed', 'queued-$_packed2'), 8);
  });

  testWidgets('Outstanding lines counts the lines owed, not the orders', (tester) async {
    await _open(tester);
    // Two orders owing three lines between them.
    expect(tester.widget<Text>(find.byKey(const Key('owing-count'))).data, '3');
  });

  testWidgets('on a wide desktop the page is capped at the content width', (tester) async {
    await _open(tester, size: const Size(2000, 1400));
    expect(tester.takeException(), isNull);
    // 1200 wide, centred: from 400 to 1600.
    expect(tester.getTopLeft(find.byKey(const Key('owing-$_owing'))).dx, greaterThanOrEqualTo(400));
    expect(tester.getTopRight(find.byKey(const Key('dispatch-$_packed'))).dx, lessThanOrEqualTo(1600));
  });

  testWidgets('names, never fragments of ids, for owed lines and stand-ins', (tester) async {
    await _open(tester);
    expect(find.text('Bread'), findsOneWidget);
    expect(find.textContaining('…'), findsNothing);
    await tester.tap(find.byKey(const Key('substitute-$_owing-$_apples')));
    await tester.pumpAndSettle();
    // Order-svc named no product for this stand-in; the catalogue does.
    expect(find.text('Plums'), findsOneWidget);
    expect(find.textContaining('…'), findsNothing);
  });

  testWidgets('Substitute finds what was packed by name or SKU, never by variant id',
      (tester) async {
    final server = await _open(tester);
    await tester.tap(find.byKey(const Key('substitute-$_owing-$_bread')));
    await tester.pumpAndSettle();
    expect(find.textContaining('No stand-ins are declared for this product.'), findsOneWidget);
    expect(find.textContaining(RegExp('variant id', caseSensitive: false)), findsNothing);
    await tester.tap(find.byKey(const Key('substitute-save')));
    await tester.pumpAndSettle();
    expect(find.text('Say what you packed.'), findsOneWidget);

    await tester.tap(find.byKey(const Key('substitute-variant')));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Name or SKU'), 'rye');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(ListTile, 'Rye loaf'));
    await tester.pumpAndSettle();
    expect(find.text('Rye loaf · RYE'), findsOneWidget);

    await tester.tap(find.byKey(const Key('substitute-save')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere(
        (r) => r.method == 'POST' && r.path.endsWith('/orders/$_owing/lines/$_bread/substitute'));
    expect(_body(post), {'substituteVariantId': _rye, 'qty': 2});
  });

  // ── handed over today, on the store's clock ────────────────────────────────

  testWidgets("Handed over today starts at the store's own midnight, not UTC's", (tester) async {
    // 00:30 on 26 September in Leeds (BST): UTC is still on the 25th.
    final server = await _open(tester, now: DateTime.utc(2026, 9, 25, 23, 30));
    expect(_handedFrom(server, _leeds), '2026-09-25T23:00:00.000Z');
  });

  testWidgets("Handed over today keeps the store's day through the small hours", (tester) async {
    // 01:30 BST on the 26th: UTC's day began an hour after the shop's.
    final server = await _open(tester, now: DateTime.utc(2026, 9, 26, 0, 30));
    expect(_handedFrom(server, _leeds), '2026-09-25T23:00:00.000Z');
  });

  testWidgets("a store with no time zone counts from the device's midnight", (tester) async {
    final now = DateTime.utc(2026, 9, 26, 12);
    final server = await _open(tester, now: now);
    await tester.tap(find.byKey(const Key('fulfilment-store')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Online hub · dark store').last);
    await tester.pumpAndSettle();
    final local = now.toLocal();
    expect(_handedFrom(server, _dark),
        DateTime(local.year, local.month, local.day).toUtc().toIso8601String());
  });

  // ── delivery and collection slots ──────────────────────────────────────────

  testWidgets('a packed order shows its window, in the store\'s own local clock',
      (tester) async {
    await _open(tester);
    final says = find.descendant(
        of: find.byKey(const Key('queued-$_packed')),
        matching: find.textContaining('Sun 27 Sept, 17:00–19:00'));
    expect(says, findsOneWidget);
    // The row names the kind once, at its start — never again beside the window.
    final text = tester.widget<Text>(says).data!;
    expect(text.startsWith('Delivery · '), isTrue, reason: text);
    expect('Delivery'.allMatches(text).length, 1, reason: text);
    // The other packed order carries no window at all.
    expect(
        find.descendant(
            of: find.byKey(const Key('queued-$_packed2')),
            matching: find.textContaining('17:00–19:00')),
        findsNothing);
  });

  testWidgets('packed and ready are asked in window order (sort=slot)', (tester) async {
    final server = await _open(tester);
    final packedRead = server.requests.lastWhere((r) =>
        r.method == 'GET' && r.path.endsWith('/orders') && r.queryParameters['fulfilmentType'] == 'DELIVERY');
    final readyRead = server.requests.lastWhere((r) =>
        r.method == 'GET' && r.path.endsWith('/orders') && r.queryParameters['fulfilmentType'] == 'PICKUP');
    expect(packedRead.queryParameters['sort'], 'slot');
    expect(readyRead.queryParameters['sort'], 'slot');
    // Handed over today is sorted by when it happened, not by any window.
    expect(_handedToday(server, _leeds).queryParameters['sort'], isNull);
  });
}
