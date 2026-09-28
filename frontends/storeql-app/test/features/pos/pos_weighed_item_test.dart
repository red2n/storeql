import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_receipt_data.dart';
import 'package:storeql_app/features/pos/pos_weighed_item.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// Selling by weight at the till. The line quantity was a whole number, so the
// till could not ring up 0.375 kg of anything.
// ---------------------------------------------------------------------------

class _Reply implements HttpClientAdapter {
  int status;
  String body;
  _Reply(this.status, this.body);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async =>
      ResponseBody.fromString(body, status, headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType]
      });
}

Dio _dio(int status, String body) =>
    Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Reply(status, body);

const _cheese = PosLine(
  variantId: 'v-cheese',
  sku: 'CHEDDAR',
  name: 'Mature cheddar',
  qty: 0.375,
  unitPrice: 12,
  currency: 'GBP',
  soldBy: 'WEIGHT',
  unit: 'kg',
);

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);
  group('fetchSaleUnit', () {
    test('an item sold by the each', () async {
      expect(await fetchSaleUnit(_dio(200, '{"data":{"soldBy":"EACH"}}'), 'v'), isA<SoldEach>());
    });

    test('an item sold by weight, in the unit the product names', () async {
      final u = await fetchSaleUnit(
              _dio(200, '{"data":{"soldBy":"WEIGHT","netContentUom":"KG","tareWeight":0.02}}'), 'v')
          as SoldByMeasure;
      expect(u.soldBy, 'WEIGHT');
      expect(u.unit, 'kg');
      expect(u.tare, 0.02);
    });

    test('a catch-weight item with no unit named reads in kilograms', () async {
      final u = await fetchSaleUnit(
          _dio(200, '{"data":{"soldBy":"WEIGHT","catchWeight":true}}'), 'v') as SoldByMeasure;
      expect(u.unit, 'kg');
      expect(u.catchWeight, isTrue);
    });

    test('an unreadable answer is not guessed as "each"', () async {
      expect(await fetchSaleUnit(_dio(200, '{"data":{}}'), 'v'), isA<SaleUnitUnknown>());
      expect(await fetchSaleUnit(_dio(200, '{"data":[]}'), 'v'), isA<SaleUnitUnknown>());
    });

    test('a failed call is not guessed either', () async {
      expect(await fetchSaleUnit(_dio(503, '{"error":{"code":"X","details":[]}}'), 'v'),
          isA<SaleUnitUnknown>());
    });
  });

  group('parseMeasuredQuantity', () {
    test('a reading to the gram', () {
      expect(parseMeasuredQuantity('0.375'), 0.375);
      expect(parseMeasuredQuantity('1'), 1.0);
      // A decimal comma, as a scale or keyboard in much of Europe shows it.
      expect(parseMeasuredQuantity('0,375'), 0.375);
    });

    test('nothing that is not a usable reading', () {
      for (final bad in ['', ' ', 'abc', '0', '0.000', '-1', '0.3755', '1000', '1.2.3']) {
        expect(parseMeasuredQuantity(bad), isNull, reason: '"$bad"');
      }
    });
  });

  group('a measured line', () {
    test('is one item, reads in its unit, and prices by the unit', () {
      expect(_cheese.itemCount, 1);
      expect(_cheese.qtyLabel, '0.375 kg');
      expect(_cheese.lineTotal, 4.5);
    });

    test('an each line still counts and reads as units', () {
      const tins = PosLine(
          variantId: 'v', sku: 'S', name: 'Tins', qty: 3, unitPrice: 1, currency: 'GBP');
      expect(tins.itemCount, 3);
      expect(tins.qtyLabel, '3');
    });

    test('prints on the receipt with its reading and the price per unit', () {
      final html = PosReceiptData(
        orderId: 'o-1',
        storeName: 'High Street',
        dateTime: DateTime(2026, 9, 10, 14, 5),
        items: const [_cheese],
        subtotal: 4.5,
        discount: 0,
        total: 4.5,
        currency: 'GBP',
        tenders: const [],
        change: 0,
      ).toHtml();
      expect(html, contains('0.375 kg × £12.00/kg'));
      // Money and the date as a shopper reads them, not codes and ISO digits.
      expect(html, isNot(contains('GBP')));
      expect(html, contains('10 Sept 2026 14:05'));
      expect(html, isNot(contains('2026-09-10')));
    });

    test('a parked sale comes back with its weight, not truncated to nothing', () {
      final parked = ParkedSale.fromJson({
        'id': 'p-1',
        'items': [
          {'variantId': 'v-cheese', 'qty': 0.375, 'unitPrice': 12},
        ],
      });
      expect(parked.lines.single.qty, 0.375);
    });
  });

  group('MeasuredQuantityDialog', () {
    Future<double?> enter(WidgetTester tester, String reading,
        {SoldByMeasure unit =
            const SoldByMeasure(soldBy: 'WEIGHT', unit: 'kg', catchWeight: false)}) async {
      double? result;
      await tester.pumpWidget(MaterialApp(
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async {
              result = await showDialog<double>(
                context: context,
                builder: (_) => MeasuredQuantityDialog(
                    itemName: 'Mature cheddar', unit: unit, unitPrice: 12, currency: 'GBP'),
              );
            },
            child: const Text('open'),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), reading);
      await tester.pumpAndSettle();
      if (tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Add')).onPressed != null) {
        await tester.tap(find.text('Add'));
        await tester.pumpAndSettle();
      }
      return result;
    }

    testWidgets('asks for the approved scale reading and shows the line price', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: MeasuredQuantityDialog(
            itemName: 'Mature cheddar',
            unit: SoldByMeasure(soldBy: 'WEIGHT', unit: 'kg', tare: 0.02, catchWeight: false),
            unitPrice: 12,
            currency: 'GBP',
          ),
        ),
      ));
      expect(find.text('£12.00 / kg'), findsOneWidget);
      expect(find.textContaining('approved scale shows. Do not estimate it.'), findsOneWidget);
      // Tare is the scale's to deduct, and the dialog says so rather than subtracting it.
      expect(find.textContaining('deducted by the scale'), findsOneWidget);

      await tester.enterText(find.byType(TextField), '0.375');
      await tester.pumpAndSettle();
      expect(find.text('Line price: £4.50'), findsOneWidget);
    });

    testWidgets('a usable reading is returned', (tester) async {
      expect(await enter(tester, '0.375'), 0.375);
    });

    testWidgets('an unusable reading cannot be added', (tester) async {
      expect(await enter(tester, '0.3755'), isNull);
    });
  });
}
