import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/inventory_warehouse_tabs.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';

import '../../support/fake_api.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// Inventory's Transfers and Movements tabs read in words: a transfer's status
// and a movement's kind as words, stores and products by name (the end of an
// id only while a name is unknown, never a whole id), times as dates — and on
// a phone the transfers toolbar wraps instead of running off the screen.
// ---------------------------------------------------------------------------

const _leeds = '01a0c100-0000-7000-8000-00000000aa01';
const _gone = '01a0c100-0000-7000-8000-00000000ff99';
const _mug = '01a090ae-611e-7011-ae7d-1bd68c966ff6';

const _york = '01a0c100-0000-7000-8000-00000000aa02';

class _Resolve implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    return o.path.endsWith('/variants/resolve')
        ? jsonResponse('{"data":[{"variantId":"$_mug","productName":"Blue mug","sku":"MUG-BLU"}]}')
        : jsonResponse('{"data":[]}');
  }
}

/// The movements the Movements tab opens on: one sale, unless a test names its own.
const _oneSale = [
  StockMovement(
    id: 'm-1',
    storeId: _leeds,
    variantId: _mug,
    type: 'SALE',
    qty: -2,
    createdAt: '2026-09-25T09:30:00Z',
  ),
];

Future<_Resolve> _pump(
  WidgetTester tester,
  Widget tab, {
  Size size = const Size(1200, 900),
  List<StockMovement> movements = _oneSale,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Resolve();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      storesProvider.overrideWith((ref) async => const [
            StoreInfo(id: _leeds, name: 'Leeds', code: 'LDS', type: 'STORE', status: 'ACTIVE'),
            StoreInfo(id: _york, name: 'York', code: 'YRK', type: 'STORE', status: 'ACTIVE'),
          ]),
      transferOrdersProvider('').overrideWith((ref) async => const [
            TransferOrder(
              id: 't-1',
              fromStoreId: _leeds,
              toStoreId: _gone,
              status: 'PENDING',
              lines: [TransferOrderLine(variantId: _mug, requestedQty: 4)],
            ),
          ]),
      stockMovementsProvider('').overrideWith((ref) async => movements),
    ],
    child: MaterialApp(home: Scaffold(body: tab)),
  ));
  await tester.pumpAndSettle();
  await tester.pump(const Duration(milliseconds: 50));
  await tester.pumpAndSettle();
  return server;
}

/// New transfer, Leeds to York, of the mug.
Future<void> _openTransfer(WidgetTester tester) async {
  await tester.tap(find.text('New transfer'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('From store'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Leeds').last);
  await tester.pumpAndSettle();
  await tester.tap(find.text('To store'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('York').last);
  await tester.pumpAndSettle();
  await tester.enterText(find.widgetWithText(TextField, 'Variant ID (UUID)'), _mug);
}

String? _qtySays(WidgetTester tester) =>
    tester.widget<TextField>(find.byKey(const Key('transfer-qty'))).decoration?.errorText;

Iterable<RequestOptions> _posts(_Resolve server) => server.requests.where((r) => r.method == 'POST');

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);

  testWidgets('a transfer says its status in words, and a store it cannot name by the end of its id',
      (tester) async {
    await _pump(tester, const InventoryTransfersTab());
    expect(find.text('Leeds → …0000ff99'), findsOneWidget);
    expect(find.textContaining(_gone), findsNothing, reason: 'never a whole id');
    expect(find.textContaining('Pending · 1 line'), findsOneWidget);
    expect(find.textContaining('PENDING'), findsNothing);
  });

  testWidgets('on a phone the transfers toolbar wraps rather than overflowing', (tester) async {
    await _pump(tester, const InventoryTransfersTab(), size: const Size(390, 844));
    expect(tester.takeException(), isNull);
    final button = find.text('New transfer');
    expect(tester.getRect(button).right, lessThanOrEqualTo(390 - 16));
    // The page's gutter on a phone is 16.
    expect(tester.getTopLeft(find.byType(DropdownButtonFormField<String?>)).dx, 16);
  });

  testWidgets('a movement names its kind, its product and its time', (tester) async {
    await _pump(tester, const InventoryMovementsTab());
    expect(find.text('Sale  -2'), findsOneWidget);
    expect(find.textContaining('Leeds · Blue mug · 25 Sept 2026'), findsOneWidget);
    expect(find.textContaining('SALE'), findsNothing);
    expect(find.textContaining('2026-09-25'), findsNothing);
  });
  // The words for each type inventory-svc writes to stock_movements.type
  // (Domain.MoveType: RECEIVE, SALE, ADJUST, TRANSFER, RTV, RESERVE, RELEASE,
  // BOND_RELEASE, YIELD, LOT_SPLIT and LOT_MERGE). A type the ledger never holds is not
  // listed here, and one it does hold never falls through to its code.
  group('every movement type the ledger holds reads in words', () {
    const kinds = {
      'RECEIVE': 'Receipt',
      'SALE': 'Sale',
      'ADJUST': 'Adjustment',
      'TRANSFER': 'Transfer',
      'RTV': 'Return to supplier',
      'RESERVE': 'Hold placed',
      'RELEASE': 'Hold released',
      'BOND_RELEASE': 'Released from bond',
      'YIELD': 'Breakdown',
      'LOT_SPLIT': 'Lot split',
      'LOT_MERGE': 'Lot merge',
    };

    test('each type has its own label, and no two read alike', () {
      for (final e in kinds.entries) {
        expect(movementTypeLabel(e.key), e.value, reason: e.key);
      }
      expect(kinds.keys.map(movementTypeLabel).toSet(), hasLength(kinds.length));
    });

    test('a code that is not a type of the ledger is still words, never raw', () {
      expect(movementTypeLabel('TRANSFER_OUT'), 'Transfer out');
      expect(movementTypeLabel('cycle_count'), 'Cycle count');
    });

    testWidgets('the Movements tab shows each with its quantity, in words', (tester) async {
      final rows = [
        for (final (i, e) in kinds.keys.indexed)
          StockMovement(
            id: 'm-$i',
            storeId: _leeds,
            variantId: _mug,
            type: e,
            qty: i.isEven ? 3 : -3,
            createdAt: '2026-09-25T09:30:00Z',
          ),
      ];
      await _pump(
        tester,
        const InventoryMovementsTab(),
        size: const Size(1200, 1800),
        movements: rows,
      );
      for (final (i, e) in kinds.entries.indexed) {
        final sign = i.isEven ? '+' : '-';
        expect(find.text('${e.value}  ${sign}3'), findsOneWidget, reason: e.key);
        expect(find.textContaining(e.key), findsNothing, reason: 'never the raw code ${e.key}');
      }
    });
  });

  // What a transfer moves is a quantity to three places: read the way the
  // app's language writes a number and sent as the decimal typed, or refused
  // under its field with no transfer raised. Parsed with a point, Romanian's
  // 1.250 moved a kilo and a quarter, and its 2,5 was called not positive.
  group('a transfer quantity is read as typed, or refused', () {
    for (final (locale, typed, sent) in [
      ('ro', '2,5', '2.5'),
      ('en_GB', '2.5', '2.5'),
      ('en', '1.125', '1.125'),
      ('pl', '1,125', '1.125'),
      ('ar', '2\u066B5', '2.5'),
    ]) {
      testWidgets('in $locale, $typed is requested as $sent', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester, const InventoryTransfersTab());
        await _openTransfer(tester);
        for (var i = 1; i <= typed.length; i++) {
          await tester.enterText(find.byKey(const Key('transfer-qty')), typed.substring(0, i));
          await tester.pump();
        }
        expect(_qtySays(tester), isNull);
        await tester.tap(find.text('Create'));
        await tester.pumpAndSettle();
        final post = _posts(server).single;
        final body = post.data is String ? jsonDecode(post.data as String) : post.data;
        expect(body['lines'], [
          {'variantId': _mug, 'requestedQty': sent},
        ]);
      });
    }

    for (final (locale, typed, says) in [
      ('ro', '1.250', 'Type the amount without thousands separators. Decimals go after a comma.'),
      ('en_GB', '2,5', 'Type the amount without thousands separators. Decimals go after a point.'),
      ('en', '2,5', 'Type the amount without thousands separators. Decimals go after a point.'),
      ('pl', '1.250',
          'A point may group thousands here. Type the figure without grouping, with any decimals after a comma.'),
      ('ar', '-2', 'Type the amount without a sign.'),
      ('en_GB', '.', 'Type the amount in digits.'),
      ('en', '1e3', 'Only digits and a decimal point.'),
    ]) {
      testWidgets('in $locale, "$typed" is refused under the field and no transfer is raised',
          (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester, const InventoryTransfersTab());
        await _openTransfer(tester);
        await tester.enterText(find.byKey(const Key('transfer-qty')), typed);
        await tester.pump();
        expect(_qtySays(tester), says);
        await tester.tap(find.text('Create'));
        await tester.pumpAndSettle();
        expect(_posts(server), isEmpty);
        expect(find.text('A figure cannot be read. Correct the one marked.'), findsOneWidget);
      });
    }
  });
}
