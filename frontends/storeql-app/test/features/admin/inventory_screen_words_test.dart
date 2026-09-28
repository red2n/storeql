import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/inventory_screen.dart';
import 'package:storeql_app/shared/widgets/empty_state.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';

import '../../support/fake_api.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The Inventory screen reads in words and dates:
//   * the Batches tab shows a batch's material status as a badge in words
//     (*Available*, *In quarantine*), never the code, and its expiry as a
//     date (*4 Oct 2026*) in the table, the phone rows and the expiring
//     banner — never ISO;
//   * a batch is named by its product, not by a fragment of its variant id;
//   * an empty list is the shared EmptyState, naming what is empty, with a
//     way out of a search that matched nothing.
// ---------------------------------------------------------------------------

const _store = '01a0c200-0000-7000-8000-000000000001';
const _zone = '01a0c200-0000-7000-8000-0000000000a1';
const _mug = '01a0c200-0000-7000-8000-0000000000v1';
const _plate = '01a0c200-0000-7000-8000-0000000000v2';

class _Server implements HttpClientAdapter {
  /// The stock levels list; empty when unset.
  String levels = '[]';

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    final path = o.path;
    if (path.endsWith('/admin/stores')) {
      return jsonResponse('{"data":[{"id":"$_store","name":"Leeds","code":"LDS","type":"STORE",'
          '"status":"ACTIVE"}],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/stores/$_store/zones')) {
      return jsonResponse('{"data":[{"id":"$_zone","storeId":"$_store","name":"Aisle A","code":"A",'
          '"type":"AISLE","status":"ACTIVE"}],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/admin/products/variants/resolve')) {
      return jsonResponse('{"data":['
          '{"variantId":"$_mug","productName":"Blue mug","sku":"MUG-BLU"},'
          '{"variantId":"$_plate","productName":"Side plate","sku":"PLT-SD"}]}');
    }
    if (path.endsWith('/admin/inventory/batches/expiring')) {
      return jsonResponse('{"data":[{"id":"b2","storeId":"$_store","variantId":"$_plate",'
          '"batchNo":"B-002","remainingQty":4,"expiryDate":"2026-10-04","daysUntilExpiry":9}]}');
    }
    if (path.endsWith('/admin/inventory/batches')) {
      return jsonResponse('{"data":['
          '{"id":"b1","storeId":"$_store","variantId":"$_mug","batchNo":"B-001","receivedQty":10,'
          '"remainingQty":6,"createdAt":"2026-09-01T09:00:00Z","status":"ACTIVE",'
          '"materialStatus":"AVAILABLE","zoneId":"$_zone"},'
          '{"id":"b2","storeId":"$_store","variantId":"$_plate","batchNo":"B-002","receivedQty":5,'
          '"remainingQty":4,"expiryDate":"2026-10-04","createdAt":"2026-09-02T09:00:00Z",'
          '"status":"ACTIVE","materialStatus":"QUARANTINE","zoneId":"$_zone"}]}');
    }
    if (path.endsWith('/admin/inventory/levels/summary')) {
      return jsonResponse('{"data":{"skuCount":1,"lowStockCount":0}}');
    }
    if (path.endsWith('/admin/inventory/levels')) {
      return jsonResponse('{"data":$levels,"meta":{"nextCursor":null}}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(WidgetTester tester,
    {Size size = const Size(1280, 900),
    double textScale = 1,
    void Function(_Server)? setUp}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  setUp?.call(server);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('OWNER')),
    ],
    child: MaterialApp(
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
        child: child!,
      ),
      home: const Scaffold(body: InventoryScreen()),
    ),
  ));
  await tester.pumpAndSettle();
  return server;
}

/// Opens the Batches tab and picks the store.
Future<void> _batchesAtLeeds(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(Tab, 'Batches'));
  await tester.pumpAndSettle();
  await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Store'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Leeds (LDS)').last);
  await tester.pumpAndSettle();
}

void _expectNoCodesOrIsoDates() {
  for (final code in ['AVAILABLE', 'QUARANTINE']) {
    expect(find.text(code), findsNothing, reason: code);
  }
  expect(find.textContaining('2026-10-04'), findsNothing);
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);

  group('Batches', () {
    testWidgets('the table shows status in words and expiry as a date', (tester) async {
      await _pump(tester);
      await _batchesAtLeeds(tester);

      expect(find.widgetWithText(StatusBadge, 'Available'), findsOneWidget);
      expect(find.widgetWithText(StatusBadge, 'In quarantine'), findsOneWidget);
      expect(find.widgetWithText(DataTable, '4 Oct 2026'), findsOneWidget);
      _expectNoCodesOrIsoDates();
    });

    testWidgets('each batch is named by its product, not its variant id', (tester) async {
      await _pump(tester);
      await _batchesAtLeeds(tester);

      expect(find.widgetWithText(DataTable, 'Blue mug'), findsOneWidget);
      expect(find.widgetWithText(DataTable, 'Side plate'), findsOneWidget);
      expect(find.textContaining('0000000000v1'), findsNothing);
    });

    testWidgets('the expiring banner gives the day as a date', (tester) async {
      await _pump(tester);
      await _batchesAtLeeds(tester);

      expect(find.byType(MaterialBanner), findsOneWidget);
      expect(
          find.descendant(
              of: find.byType(MaterialBanner), matching: find.textContaining('4 Oct 2026')),
          findsOneWidget);
      _expectNoCodesOrIsoDates();
    });

    testWidgets('on a phone the rows show words and dates too', (tester) async {
      await _pump(tester, size: const Size(390, 844));
      await _batchesAtLeeds(tester);
      expect(tester.takeException(), isNull);

      expect(find.widgetWithText(StatusBadge, 'Available'), findsOneWidget);
      expect(find.widgetWithText(StatusBadge, 'In quarantine'), findsOneWidget);
      expect(
          find.descendant(of: find.byType(Card), matching: find.textContaining('4 Oct 2026')),
          findsOneWidget);
      expect(find.descendant(of: find.byType(Card), matching: find.textContaining('Side plate')),
          findsOneWidget);
      _expectNoCodesOrIsoDates();
    });

    testWidgets('on a phone at 200% text a batch keeps its name, badge and count in view',
        (tester) async {
      await _pump(tester, size: const Size(390, 844), textScale: 2);
      await _batchesAtLeeds(tester);
      expect(tester.takeException(), isNull);
      // The filters scroll away with the cards; nothing is pinned above them.
      final tab = find
          .descendant(of: find.byType(CustomScrollView), matching: find.byType(Scrollable))
          .first;
      for (final words in ['Available', 'In quarantine']) {
        final badge = find.widgetWithText(StatusBadge, words);
        await tester.scrollUntilVisible(badge, 100, scrollable: tab);
        expect(badge, findsOneWidget, reason: words);
        expect(tester.takeException(), isNull);
      }
      final title = find.descendant(
          of: find.byType(Card), matching: find.textContaining('Side plate'));
      await tester.scrollUntilVisible(title, -100, scrollable: tab);
      expect(tester.getSize(title).width, greaterThan(150),
          reason: 'the name has the width, not the badge and the count');
      expect(tester.takeException(), isNull);
    });

    testWidgets('before a store is chosen, the shared empty state asks for one', (tester) async {
      await _pump(tester);
      await tester.tap(find.widgetWithText(Tab, 'Batches'));
      await tester.pumpAndSettle();
      expect(find.byType(EmptyState), findsOneWidget);
    });
  });

  testWidgets('on a phone at 200% text the empty states and the filter row fit',
      (tester) async {
    await _pump(tester, size: const Size(390, 844), textScale: 2);
    expect(tester.takeException(), isNull);
    expect(find.byType(EmptyState), findsOneWidget);
    expect(find.byTooltip('Refresh inventory'), findsOneWidget);

    await tester.tap(find.widgetWithText(Tab, 'Batches'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.byType(EmptyState), findsOneWidget);
  });

  group('Levels', () {
    testWidgets('no stock at all is the shared empty state', (tester) async {
      await _pump(tester);
      expect(find.widgetWithText(EmptyState, 'No stock recorded yet'), findsOneWidget);
      expect(find.text('No items found'), findsNothing);
    });

    testWidgets('a search that matches nothing says what was searched, and clears',
        (tester) async {
      await _pump(tester,
          setUp: (s) => s.levels = '[{"variantId":"$_mug","storeId":"$_store",'
              '"onHand":40,"reserved":0,"available":40}]');
      expect(find.text('Blue mug'), findsOneWidget);

      await tester.enterText(find.byType(SearchBar), 'teapot');
      await tester.pumpAndSettle();
      expect(find.widgetWithText(EmptyState, 'No items match “teapot”'), findsOneWidget);
      expect(find.text('No matches on loaded items'), findsNothing);

      await tester.tap(find.widgetWithText(TextButton, 'Clear filters'));
      await tester.pumpAndSettle();
      expect(find.byType(EmptyState), findsNothing);
      expect(find.text('Blue mug'), findsOneWidget);
      // The box is emptied too, not left showing a search no longer applied.
      expect(find.text('teapot'), findsNothing);
    });
  });
}
