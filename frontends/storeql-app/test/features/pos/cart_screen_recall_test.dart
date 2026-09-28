import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/pos/cart_screen.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_recall_check.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The recall check at the register.
//
// Opening a recall takes the stock off sale in inventory-svc, but the till
// sells a variant, not a batch, and takes no reservation — so a pack still on
// the shelf could be rung up. The till keeps the list of open recalls and
// checks each scan against it: every pack recalled means no sale at all; some
// lots or dates recalled means the cashier is told what to look for.
// ---------------------------------------------------------------------------

class _NoopPosSessionNotifier extends PosSessionNotifier {
  _NoopPosSessionNotifier(super.ref);

  @override
  Future<void> restore() async {}
}

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Till implements HttpClientAdapter {
  (int, String) recalls = (200, '{"data":[]}');

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    var status = 200;
    var body = '{"data":[]}';
    if (o.path.endsWith('/admin/inventory/recalls/active')) {
      (status, body) = recalls;
    } else if (o.path.contains('/catalog/scan')) {
      // /catalog/scan (07.15): one route for every kind of code, with the code in a query
      // parameter because a GS1 Digital Link is a URI, and the item wrapped in `item` beside
      // what the code itself carried in `code`.
      final code = '${o.queryParameters['code']}';
      body = '{"data":{"item":{"variantId":"v-$code","sku":"$code","productName":"Item $code"}}}';
    } else if (o.path.contains('/prices/resolve')) {
      body = '{"data":{"unitPrice":3.0,"currency":"GBP"}}';
    } else if (o.path.endsWith('/compliance')) {
      body = '{"data":{"soldBy":"EACH","catchWeight":false}}';
    } else if (o.path.endsWith('/age-check')) {
      body = '{"data":{"restricted":false}}';
    }
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

const _everyPack = '''
{"data":[{"recallId":"r-1","reference":"FSA-PRIN-42","kind":"RECALL","hazard":"ALLERGEN",
 "customerNotice":"Do not eat. Return for a refund.","variantId":"v-PEANUT"}]}''';

const _oneLot = '''
{"data":[{"recallId":"r-2","reference":"SUP-7","kind":"WITHDRAWAL","hazard":"FOREIGN_BODY",
 "variantId":"v-JAM","batchNo":"L-2291","expiryFrom":"2026-10-01","expiryTo":"2026-10-31"}]}''';

Future<_Till> _pump(WidgetTester tester, {(int, String) recalls = (200, '{"data":[]}')}) async {
  tester.view.physicalSize = const Size(700, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  final till = _Till()..recalls = recalls;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = till;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
      posStoreProvider.overrideWith((ref) => 'store-1'),
      posStoresProvider.overrideWith((ref) async => const [
            StoreInfo(
                id: 'store-1',
                name: 'High Street',
                code: 'HS',
                type: 'STORE',
                status: 'ACTIVE',
                country: 'GB'),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: PosCartScreen())),
  ));
  await tester.pumpAndSettle();
  return till;
}

Future<void> _scan(WidgetTester tester, String code) async {
  await tester.enterText(find.widgetWithText(TextField, 'Scan barcode or type SKU…'), code);
  await tester.testTextInput.receiveAction(TextInputAction.done);
  await tester.pumpAndSettle();
}

List<PosLine> _basket(WidgetTester tester) =>
    ProviderScope.containerOf(tester.element(find.byType(PosCartScreen).first))
        .read(posCartProvider);

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  // Dates are written through AppFormat in the app's locale (en_GB here); the
  // app loads intl's date data through flutter_localizations, a test loads it here.
  setUpAll(initializeDateFormatting);
  testWidgets('an item under an every-pack recall cannot be sold at all', (tester) async {
    await _pump(tester, recalls: (200, _everyPack));
    await _scan(tester, 'PEANUT');

    expect(find.text('Do not sell this item'), findsOneWidget);
    expect(find.text('Do not eat. Return for a refund.'), findsOneWidget);
    // No override: the only way out of the dialog is to keep the item out.
    expect(find.text('Remove from sale'), findsOneWidget);
    await tester.tap(find.text('Remove from sale'));
    await tester.pumpAndSettle();
    expect(_basket(tester), isEmpty);
  });

  testWidgets('a lot recall asks the cashier to check the pack, and either answer is honoured',
      (tester) async {
    await _pump(tester, recalls: (200, _oneLot));
    await _scan(tester, 'JAM');

    expect(find.text('Check the pack before selling'), findsOneWidget);
    expect(find.textContaining('lot L-2291, best before 1 Oct 2026 to 31 Oct 2026'), findsOneWidget);
    await tester.tap(find.text('Affected — remove'));
    await tester.pumpAndSettle();
    expect(_basket(tester), isEmpty);

    await _scan(tester, 'JAM');
    await tester.tap(find.text('Not affected — sell'));
    await tester.pumpAndSettle();
    expect(_basket(tester), hasLength(1));
  });

  testWidgets('an item under no recall goes straight in', (tester) async {
    await _pump(tester, recalls: (200, _everyPack));
    await _scan(tester, 'BREAD');
    expect(find.text('Do not sell this item'), findsNothing);
    expect(_basket(tester), hasLength(1));
  });

  testWidgets('a till that cannot load recalls says so and keeps selling', (tester) async {
    await _pump(tester, recalls: (503, '{"error":{"code":"UNAVAILABLE","message":"down"}}'));
    expect(find.text("Recalls couldn't be loaded. Check items against the recall notices."),
        findsOneWidget);
    await _scan(tester, 'BREAD');
    expect(_basket(tester), hasLength(1));
  });

  group('checkRecall and the kept list', () {
    const every = ActiveRecallItem(
        recallId: 'r', reference: 'R', kind: 'RECALL', hazard: 'ALLERGEN', variantId: 'v-1');
    const lot = ActiveRecallItem(
        recallId: 'r', reference: 'R', kind: 'RECALL', hazard: 'ALLERGEN', variantId: 'v-2', batchNo: 'L1');

    test('every pack blocks; a lot asks for a pack check; another item is clear', () {
      expect(checkRecall('v-1', const [every, lot]), isA<RecallBlocked>());
      expect(checkRecall('v-2', const [every, lot]), isA<RecallCheckPack>());
      expect(checkRecall('v-3', const [every, lot]), isA<RecallClear>());
    });

    test('an every-pack line wins over a lot line for the same item', () {
      const alsoLot = ActiveRecallItem(
          recallId: 'r2', reference: 'R2', kind: 'WITHDRAWAL', hazard: 'QUALITY', variantId: 'v-1', batchNo: 'L9');
      expect(checkRecall('v-1', const [alsoLot, every]), isA<RecallBlocked>());
    });

    test('the list is stale only after a failed refresh and a long silence', () {
      final now = DateTime(2026, 9, 11, 12);
      expect(RecallList(fetchedAt: now.subtract(const Duration(hours: 2))).isStale(now), isFalse);
      expect(
          RecallList(fetchedAt: now.subtract(const Duration(minutes: 10)), failed: true).isStale(now),
          isFalse);
      expect(
          RecallList(fetchedAt: now.subtract(const Duration(hours: 1)), failed: true).isStale(now),
          isTrue);
      expect(const RecallList(failed: true).isStale(now), isTrue);
    });
  });
}
