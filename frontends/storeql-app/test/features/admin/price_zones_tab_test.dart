import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/price_zones_tab.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The Pricing screen's "Zones & repricing" tab (03.x): the zones with their
// stores by name, a rule in a sentence, an open proposal with its figures;
// New zone posting the body; Apply posting to the proposal and saying what it
// did; a refusal shown by name; and no buttons for staff who may not.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

const _north = '01a0b000-0000-7000-8000-00000000000a';
const _store1 = '01a0b000-0000-7000-8000-000000000001';
const _store2 = '01a0b000-0000-7000-8000-000000000002';
const _rule = '01a0b000-0000-7000-8000-00000000000b';
const _proposal = '01a0b000-0000-7000-8000-00000000000c';
const _list = '01a0b000-0000-7000-8000-00000000000d';
const _variant = '01a0b000-0000-7000-8000-00000000000e';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool refuse = false;
  bool zonesMade = false;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/variants/resolve')) {
      return _json('{"data":[{"variantId":"$_variant","productName":"Oat milk 1L","sku":"OAT-1"}]}', 200);
    }
    if (path.endsWith('/admin/stores')) {
      return _json(
          '{"data":[{"id":"$_store1","name":"Leeds","code":"LDS","type":"STORE","status":"ACTIVE"},'
          '{"id":"$_store2","name":"Brighton","code":"BTN","type":"STORE","status":"ACTIVE"}],"meta":{"nextCursor":null}}',
          200);
    }
    if (path.endsWith('/price-lists') && o.method == 'GET') {
      return _json('{"data":[{"id":"$_list","name":"North prices","channel":"ALL","currency":"GBP","active":true,"zoneId":"$_north"}],"meta":{"nextCursor":null}}', 200);
    }
    if (path.endsWith('/admin/price-zones') && o.method == 'GET') {
      return _json(
          '{"data":[{"id":"$_north","name":"North","description":"Up the M1","storeIds":["$_store1"]}'
          '${zonesMade ? ',{"id":"01a0b000-0000-7000-8000-00000000000f","name":"Coast","description":null,"storeIds":[]}' : ''}]}',
          200);
    }
    if (path.endsWith('/admin/price-zones') && o.method == 'POST') {
      zonesMade = true;
      return _json('{"data":{"id":"01a0b000-0000-7000-8000-00000000000f","name":"Coast","storeIds":[]}}', 201);
    }
    if (path.endsWith('/admin/competitor-prices') && o.method == 'GET') {
      return _json(
          '{"data":[{"id":"01a0b000-0000-7000-8000-000000000010","variantId":"$_variant","competitor":"Rival A","price":8.5,"currency":"GBP","zoneId":"$_north","observedOn":"2026-09-24","source":"MANUAL"}]}',
          200);
    }
    if (path.endsWith('/admin/repricing/rules') && o.method == 'GET') {
      return _json(
          '{"data":[{"id":"$_rule","name":"North undercut","priceListId":"$_list","zoneId":"$_north","strategy":"UNDERCUT_PERCENT","value":1,"floorPercent":80,"rounding":"ENDING_99","maxAgeDays":14,"active":true}]}',
          200);
    }
    if (path.endsWith('/admin/repricing/proposals') && o.method == 'GET') {
      return _json(
          '{"data":[{"id":"$_proposal","ruleId":"$_rule","priceListId":"$_list","zoneId":"$_north","variantId":"$_variant","currentPrice":9.0,"competitor":"Rival A","competitorPrice":8.5,"observedOn":"2026-09-24","proposedPrice":7.99,"currency":"GBP","status":"PROPOSED"}]}',
          200);
    }
    if (path.endsWith('/apply') && o.method == 'POST') {
      if (refuse) {
        return _json(
            '{"code":"REPRICING_PROPOSAL_DECIDED","status":409,"error":{"code":"REPRICING_PROPOSAL_DECIDED","message":"proposal was already applied"}}',
            409);
      }
      return _json('{"data":{"id":"$_proposal","status":"APPLIED"}}', 200);
    }
    return _json('{"data":null}', 200);
  }

  static ResponseBody _json(String body, int status) => ResponseBody.fromString(body, status,
      headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
}

Future<_Server> _pump(WidgetTester tester, {bool management = true, bool refuse = false}) async {
  tester.view.physicalSize = const Size(1100, 1400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server()..refuse = refuse;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: MaterialApp(home: Scaffold(body: PriceZonesTab(management: management))),
  ));
  await tester.pumpAndSettle();
  // The product names are asked for once the lists are in, with no spinner
  // to keep pumpAndSettle going.
  await tester.pump(const Duration(milliseconds: 50));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);
  testWidgets('zones show their stores by name, a rule reads as a sentence, a proposal shows its figures',
      (tester) async {
    await _pump(tester);
    expect(find.text('North'), findsOneWidget);
    expect(find.text('Leeds'), findsOneWidget);
    expect(find.text('Brighton'), findsNothing);
    expect(find.textContaining('undercut the lowest rival by 1%, to a .99, never below 80%'), findsOneWidget);
    expect(find.textContaining('North prices'), findsOneWidget);
    expect(find.text('£9.00 → £7.99'), findsOneWidget);
    expect(find.textContaining('Oat milk 1L · Rival A at £8.50, seen 24 Sept 2026'), findsOneWidget);
    expect(find.textContaining('Oat milk 1L · seen 24 Sept 2026'), findsOneWidget);
    expect(find.textContaining('variant '), findsNothing);
    expect(find.text('Rival A · £8.50'), findsOneWidget);
  });

  testWidgets('New zone posts the name and description and the list refreshes', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('zone-new')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('zone-name')), 'Coast');
    await tester.enterText(find.byKey(const Key('zone-description')), 'Seaside stores');
    await tester.tap(find.byKey(const Key('zone-save')));
    await tester.pumpAndSettle();
    final post = server.requests.lastWhere((r) => r.method == 'POST');
    expect(post.path, endsWith('/admin/price-zones'));
    final body = post.data is String ? jsonDecode(post.data as String) : post.data;
    expect(body, {'name': 'Coast', 'description': 'Seaside stores'});
    expect(find.text('Coast'), findsOneWidget);
    expect(find.textContaining('Zone Coast made'), findsOneWidget);
  });

  testWidgets('Apply posts to the proposal and says what it did; staff who may not see no buttons',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('proposal-apply-$_proposal')));
    await tester.pumpAndSettle();
    final post = server.requests.lastWhere((r) => r.method == 'POST');
    expect(post.path, endsWith('/admin/repricing/proposals/$_proposal/apply'));
    expect(find.textContaining('Applied: £7.99 against Rival A'), findsOneWidget);

    await _pump(tester, management: false);
    expect(find.byKey(const Key('zone-new')), findsNothing);
    expect(find.byKey(const Key('rule-new')), findsNothing);
    expect(find.byKey(const Key('proposal-apply-$_proposal')), findsNothing);
    expect(find.byKey(const Key('rule-run-$_rule')), findsNothing);
    expect(find.text('North'), findsOneWidget);
  });

  testWidgets('a refusal is shown by its reason', (tester) async {
    await _pump(tester, refuse: true);
    await tester.tap(find.byKey(const Key('proposal-apply-$_proposal')));
    await tester.pumpAndSettle();
    expect(find.textContaining('already applied'), findsOneWidget);
  });
}
