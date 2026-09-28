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

class _Resolve implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async =>
      o.path.endsWith('/variants/resolve')
          ? jsonResponse('{"data":[{"variantId":"$_mug","productName":"Blue mug","sku":"MUG-BLU"}]}')
          : jsonResponse('{"data":[]}');
}

Future<void> _pump(WidgetTester tester, Widget tab, {Size size = const Size(1200, 900)}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Resolve();
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      storesProvider.overrideWith((ref) async => const [
            StoreInfo(id: _leeds, name: 'Leeds', code: 'LDS', type: 'STORE', status: 'ACTIVE'),
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
      stockMovementsProvider('').overrideWith((ref) async => const [
            StockMovement(
              id: 'm-1',
              storeId: _leeds,
              variantId: _mug,
              type: 'SALE',
              qty: -2,
              createdAt: '2026-09-25T09:30:00Z',
            ),
          ]),
    ],
    child: MaterialApp(home: Scaffold(body: tab)),
  ));
  await tester.pumpAndSettle();
  await tester.pump(const Duration(milliseconds: 50));
  await tester.pumpAndSettle();
}

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
}
