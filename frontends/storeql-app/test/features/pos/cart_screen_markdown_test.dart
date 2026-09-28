import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/pos/cart_screen.dart';
import 'package:storeql_app/features/pos/markdown_label.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';

// ---------------------------------------------------------------------------
// Ringing up a reduced-price sticker (05.4), the way a cashier does: scan the
// code the counter printed, see the pack at the sticker's price with the list
// price struck through, and see the till refuse a sticker that should not sell.
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

final _live = '210000100224${ean13CheckDigit('210000100224')}';
final _expired = '210000200150${ean13CheckDigit('210000200150')}';
final _unknown = '210000900999${ean13CheckDigit('210000900999')}';
final _unannounced = '210000300180${ean13CheckDigit('210000300180')}';

class _Till implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    var body = '{"data":[]}';
    var status = 200;
    final parts = o.path.split('/');
    if (o.path.contains('/prices/markdown-labels/')) {
      final code = parts.last;
      if (code == _live) {
        body =
            '{"data":{"markdownId":"md-1","variantId":"v-YOG","storeId":"store-1","labelCode":"$_live","markdownPrice":2.24,"originalPrice":2.99,"currency":"GBP","expiryDate":"2026-09-14","remainingQty":6,"wasPrice":2.99,"reductionAnnounceable":true}}';
      } else if (code == _unannounced) {
        // 03.12: the list price is known, but no prior price is proven, so there is no was price.
        body =
            '{"data":{"markdownId":"md-2","variantId":"v-YOG","storeId":"store-1","labelCode":"$_unannounced","markdownPrice":1.80,"originalPrice":2.99,"currency":"GBP","expiryDate":"2026-09-14","remainingQty":3,"reductionAnnounceable":false,"priorPriceStatus":"SHORT_HISTORY","priorPriceRequired":true}}';
      } else if (code == _expired) {
        status = 409;
        body =
            '{"error":{"code":"PRICING_MARKDOWN_EXPIRED","message":"sticker $_expired is for a batch that expired on 2026-09-11"}}';
      } else {
        status = 404;
        body =
            '{"error":{"code":"PRICING_MARKDOWN_LABEL_UNKNOWN","message":"no live reduced-price sticker carries $code"}}';
      }
    } else if (o.path.endsWith('/admin/products/variants/resolve')) {
      body =
          '{"data":[{"variantId":"v-YOG","productName":"Greek yoghurt 500g","sku":"YOG-500"}]}';
    } else if (o.path.endsWith('/weighing-instruments')) {
      body = '{"data":[]}';
    } else if (o.path.contains('/catalog/scan')) {
      // /catalog/scan (07.15): one route for every kind of code, with the code in a query
      // parameter because a GS1 Digital Link is a URI, and the item wrapped in `item` beside
      // what the code itself carried in `code`.
      final code = '${o.queryParameters['code']}';
      body =
          '{"data":{"item":{"variantId":"v-YOG","sku":"$code","productName":"Greek yoghurt 500g"}}}';
    } else if (o.path.contains('/prices/resolve')) {
      body = '{"data":{"unitPrice":2.99,"currency":"GBP"}}';
    } else if (o.path.endsWith('/age-check')) {
      body = '{"data":{"restricted":false}}';
    } else if (o.path.endsWith('/compliance')) {
      body = '{"data":{"soldBy":"EACH"}}';
    }
    return ResponseBody.fromString(
      body,
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }
}

Future<_Till> _pump(WidgetTester tester) async {
  tester.view.physicalSize = const Size(700, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final till = _Till();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = till;
  await tester.pumpWidget(
    ProviderScope(
      overrides: <Override>[
        apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
        posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
        posStoreProvider.overrideWith((ref) => 'store-1'),
        posStoresProvider.overrideWith(
          (ref) async => const [
            StoreInfo(
              id: 'store-1',
              name: 'High Street',
              code: 'HS',
              type: 'STORE',
              status: 'ACTIVE',
              country: 'GB',
            ),
          ],
        ),
      ],
      child: const MaterialApp(home: Scaffold(body: PosCartScreen())),
    ),
  );
  await tester.pumpAndSettle();
  return till;
}

Future<void> _scan(WidgetTester tester, String code) async {
  await tester.enterText(
    find.widgetWithText(TextField, 'Scan barcode or type SKU…'),
    code,
  );
  await tester.testTextInput.receiveAction(TextInputAction.done);
  await tester.pumpAndSettle();
}

List<PosLine> _basket(WidgetTester tester) => ProviderScope.containerOf(
  tester.element(find.byType(PosCartScreen).first),
).read(posCartProvider);

void main() {
  testWidgets(
    'a sticker rings the pack up at the sticker price, list price struck through',
    (tester) async {
      final till = await _pump(tester);
      await _scan(tester, _live);

      final line = _basket(tester).single;
      expect(line.markdownId, 'md-1');
      expect(line.unitPrice, 2.24);
      expect(line.originalPrice, 2.99);
      expect(line.name, 'Greek yoghurt 500g');
      expect(find.byKey(const Key('reduced-md-1')), findsOneWidget);
      // Money reads as the back office writes it: the symbol, not the code.
      expect(find.text('was £2.99'), findsOneWidget);
      expect(find.text('REDUCED'), findsOneWidget);
      expect(find.text('£2.24'), findsWidgets);
      // The sticker was asked about; the catalogue was not.
      expect(
        till.requests.any(
          (r) => r.path.contains('/prices/markdown-labels/$_live'),
        ),
        isTrue,
      );
      expect(
        till.requests.any(
          (r) => r.path.contains('/catalog/scan'),
        ),
        isFalse,
      );
      // Not weighed, not asked: the sticker prices the pack.
      expect(till.requests.any((r) => r.path.endsWith('/compliance')), isFalse);
    },
  );

  testWidgets(
    'a sticker the law will not let be called reduced rings up at its price, with no was price',
    (tester) async {
      await _pump(tester);
      await _scan(tester, _unannounced);

      final line = _basket(tester).single;
      expect(line.markdownId, 'md-2');
      expect(line.unitPrice, 1.80);
      expect(line.originalPrice, isNull,
          reason: 'the list price is not a prior price pricing-svc proved');
      expect(find.text('was £2.99'), findsNothing);
      expect(find.text('REDUCED'), findsNothing);
      expect(find.text('MARKDOWN'), findsOneWidget);
    },
  );

  testWidgets('a sticker pricing-svc refuses is kept out, with its reason', (
    tester,
  ) async {
    await _pump(tester);
    await _scan(tester, _expired);
    expect(_basket(tester), isEmpty);
    expect(find.textContaining('expired on 2026-09-11'), findsOneWidget);
  });

  testWidgets(
    'a code shaped like a sticker that no live sticker carries is looked up as a barcode',
    (tester) async {
      final till = await _pump(tester);
      await _scan(tester, _unknown);
      final line = _basket(tester).single;
      expect(line.markdownId, isNull);
      expect(line.unitPrice, 2.99);
      expect(
        till.requests.any(
          (r) => r.queryParameters['code'] == _unknown,
        ),
        isTrue,
      );
    },
  );

  testWidgets(
    'the stickered pack and the same product at the list price stay two lines',
    (tester) async {
      await _pump(tester);
      await _scan(tester, _live);
      await _scan(tester, 'YOG-500');
      await _scan(tester, _live);

      final lines = _basket(tester);
      expect(lines.length, 2);
      final reduced = lines.firstWhere((l) => l.reduced);
      final list = lines.firstWhere((l) => !l.reduced);
      expect(reduced.qty, 2);
      expect(list.qty, 1);

      // ± on the stickered line moves only the stickered line.
      await tester.tap(
        find.descendant(
          of: find.ancestor(
            of: find.byKey(const Key('reduced-md-1')),
            matching: find.byType(ListTile),
          ),
          matching: find.byTooltip('Increase quantity'),
        ),
      );
      await tester.pumpAndSettle();
      expect(_basket(tester).firstWhere((l) => l.reduced).qty, 3);
      expect(_basket(tester).firstWhere((l) => !l.reduced).qty, 1);
    },
  );

  test('the parked and tendered line carries its markdown', () {
    final line = const PosLine(
      variantId: 'v',
      sku: 's',
      name: 'n',
      qty: 1,
      unitPrice: 2.24,
      currency: 'GBP',
      markdownId: 'md-1',
      originalPrice: 2.99,
    );
    expect(line.copyWith(qty: 2).markdownId, 'md-1');
    expect(line.copyWith(qty: 2).originalPrice, 2.99);
    expect(
      jsonEncode({'markdownId': line.markdownId}),
      '{"markdownId":"md-1"}',
    );
  });
}
