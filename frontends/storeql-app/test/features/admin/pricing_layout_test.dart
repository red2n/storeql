import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/fx_rates_card.dart';
import 'package:storeql_app/features/admin/pricing_screen.dart';

import '../../support/fake_api.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The Pricing page as people read it: a price list's channel in words and the
// day it starts as a date, and one inset for the title, the add bar and the
// cards, so their edges line up on a phone (16) and from tablet width (24).
// ---------------------------------------------------------------------------

const _lists = '''
{"data":[
 {"id":"pl-1","name":"Everywhere","channel":"ALL","currency":"GBP","active":true,"effectiveFrom":"2026-01-01T00:00:00Z"},
 {"id":"pl-2","name":"Web only","channel":"ONLINE","currency":"GBP","active":true,"effectiveFrom":"2026-03-15T00:00:00Z"},
 {"id":"pl-3","name":"Tills only","channel":"POS","currency":"GBP","active":false,"effectiveFrom":"2026-06-01T00:00:00Z"}
]}''';

const _promos = '''
{"data":[
 {"id":"p-1","name":"Web tenner","type":"BASKET_FLAT","value":10,"channel":"ONLINE",
  "active":true,"startsAt":"2026-01-01T00:00:00Z","priority":100,"exclusive":false}
]}''';

class _Server implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    final path = o.path;
    if (path.endsWith('/price-lists')) return jsonResponse(_lists);
    if (path.endsWith('/promotions')) return jsonResponse(_promos);
    if (path.endsWith('/fx-rates')) return jsonResponse('{"data":{"home":"GBP","rates":[]}}');
    if (path.endsWith('/admin/tenant')) return jsonResponse('{"data":{"currency":"GBP"}}');
    return jsonResponse('{"data":[]}');
  }
}

Future<void> _pump(WidgetTester tester, Size size, {double textScale = 1}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Server();
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
    ],
    child: MaterialApp(
      home: MediaQuery(
        data: MediaQueryData(size: size, textScaler: TextScaler.linear(textScale)),
        child: const Scaffold(body: PricingScreen()),
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

double _cardLeft(WidgetTester tester, String name) =>
    tester.getTopLeft(find.ancestor(of: find.text(name), matching: find.byType(Card))).dx;

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);

  testWidgets('a price list names its channel and gives its start as a date', (tester) async {
    await _pump(tester, const Size(1200, 1000));
    expect(find.text('All channels · GBP · from 1 Jan 2026'), findsOneWidget);
    expect(find.text('Online · GBP · from 15 Mar 2026'), findsOneWidget);
    expect(find.text('In store · GBP · from 1 Jun 2026'), findsOneWidget);
    expect(find.textContaining('T00:00:00Z'), findsNothing);
    expect(find.textContaining('ALL ·'), findsNothing);
    expect(find.textContaining('POS ·'), findsNothing);
  });

  testWidgets('a promotion for one channel names it in words', (tester) async {
    await _pump(tester, const Size(1200, 1000));
    await tester.tap(find.text('Promotions'));
    await tester.pumpAndSettle();
    expect(find.textContaining('· Online'), findsOneWidget);
    expect(find.textContaining('ONLINE'), findsNothing);
    // Its amount is money in the business's currency, not a bare number.
    expect(find.textContaining('£10.00 off the basket'), findsOneWidget);
  });

  for (final (label, size, gutter) in [
    ('on a phone', const Size(390, 844), 16.0),
    ('from tablet width', const Size(1200, 1000), 24.0),
  ]) {
    testWidgets('$label the title, the banner and the cards share one inset', (tester) async {
      await _pump(tester, size);
      expect(tester.getTopLeft(find.text('Pricing')).dx, gutter);
      expect(tester.getTopLeft(find.text('Price Lists')).dx, gutter);
      // No standard rate in this fake, so the banner shows; it lines up too.
      expect(tester.getTopLeft(find.byKey(const Key('standard-vat-missing'))).dx, gutter);
      expect(_cardLeft(tester, 'Everywhere'), gutter);
      // The exchange-rate card above the lists lines up with them too.
      expect(tester.getTopLeft(find.byKey(const Key('fx-rates-card'))).dx, gutter);
      expect(tester.getTopRight(find.byKey(const Key('fx-rates-card'))).dx, size.width - gutter);
      // The add bar's button ends where the cards end.
      final addRight = tester.getTopRight(find.text('New price list')).dx;
      final cardRight = tester.getTopRight(
          find.ancestor(of: find.text('Everywhere'), matching: find.byType(Card))).dx;
      expect(cardRight, size.width - gutter);
      expect(addRight, lessThanOrEqualTo(size.width - gutter));
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('on a phone at 200% text the title and banner scroll away and the lists get the room',
      (tester) async {
    await _pump(tester, const Size(390, 844), textScale: 2);
    expect(tester.takeException(), isNull);
    final everywhere = find.text('Everywhere');
    await tester.scrollUntilVisible(everywhere, 100,
        scrollable: find
            .descendant(of: find.byType(NestedScrollView), matching: find.byType(Scrollable))
            .first);
    await tester.pumpAndSettle();
    expect(everywhere.hitTestable(), findsOneWidget);
    // The tabs stay reachable while the list scrolls.
    expect(find.text('Price Lists').hitTestable(), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  for (final scale in [1.0, 2.0]) {
    testWidgets('while the exchange rates load, a phone at ${scale}x text does not overflow',
        (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          fxRatesProvider.overrideWith((ref) => Completer<FxRateSheet>().future),
        ],
        child: MaterialApp(
          home: MediaQuery(
            data: MediaQueryData(size: const Size(390, 844), textScaler: TextScaler.linear(scale)),
            child: const Scaffold(body: FxRatesCard(management: true)),
          ),
        ),
      ));
      await tester.pump();
      expect(find.text('Loading exchange rates…'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('with rates and Set a rate, a phone at ${scale}x text does not overflow',
        (tester) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          fxRatesProvider.overrideWithValue(AsyncData(FxRateSheet.fromJson(const {
            'home': 'GBP',
            'rates': [
              {'currency': 'EUR', 'rate': 0.85, 'effectiveFrom': '2026-09-01'},
            ],
          }))),
        ],
        child: MaterialApp(
          home: MediaQuery(
            data: MediaQueryData(size: const Size(390, 844), textScaler: TextScaler.linear(scale)),
            child: const Scaffold(body: SingleChildScrollView(child: FxRatesCard(management: true))),
          ),
        ),
      ));
      await tester.pump();
      expect(find.text('Set a rate'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }
}
