import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_quote.dart';
import 'package:storeql_app/features/pos/pos_vat.dart';

PosLine _line(String id, double qty, double price, {String? code, double? rate}) =>
    PosLine(
      variantId: id,
      sku: id,
      name: id,
      qty: qty,
      unitPrice: price,
      currency: 'GBP',
      vatCode: code,
      vatRate: rate,
    );

void main() {
  group('a quote as the server answers it', () {
    test('reads the gross total, the VAT and the table by code', () {
      final q = PosQuote.fromJson({
        'total': 3.28,
        'vatAmount': 0.31,
        'basketDiscount': 0,
        'taxInclusive': true,
        'lines': [
          {'variantId': 'a', 'lineGross': 1.29, 'vatAmount': 0.22, 'vatCode': 'T1', 'vatRate': 0.2},
          {'variantId': 'b', 'lineGross': 1.99, 'vatAmount': 0.09, 'vatCode': 'T5', 'vatRate': 0.05},
        ],
        'vatByRate': [
          {'vatCode': 'T1', 'rate': 0.2, 'gross': 1.29, 'net': 1.07, 'vat': 0.22},
          {'vatCode': 'T5', 'rate': 0.05, 'gross': 1.99, 'net': 1.9, 'vat': 0.09},
        ],
      });

      expect(q.total, 3.28);
      expect(q.taxInclusive, isTrue);
      expect(q.lines.length, 2);
      expect(q.vatByRate.length, 2);
      expect(q.vatByRate.first.vat, 0.22);
    });

    test('an answer that is not a quote is refused, never read as a free basket', () {
      expect(() => PosQuote.fromJson({}), throwsFormatException);
      expect(() => PosQuote.fromJson({'total': 1.0}), throwsFormatException);
    });

    test('a net quote from an older server is read as net plus VAT', () {
      final q = PosQuote.fromJson({
        'total': 12.0,
        'vatAmount': 2.0,
        'lines': [
          {'variantId': 'a', 'netTotal': 10.0, 'vatAmount': 2.0},
        ],
      });

      expect(q.taxInclusive, isFalse);
      expect(q.lines.single.gross, 12.0);
    });
  });

  group('what the till tenders', () {
    final lines = [_line('a', 1, 1.29), _line('b', 1, 1.99)];

    test('the server quote wins over the till\'s own sum', () {
      // A promotion the server knows and the till does not.
      final q = PosQuote.fromJson({'total': 2.99, 'vatAmount': 0.2, 'taxInclusive': true, 'lines': []});

      final t = PosTotals.of(lines, q);

      expect(t.goods, 2.99);
      expect(t.quoted, isTrue);
      expect(t.due(0), 2.99);
    });

    test('with no quote the till shows the sum of the prices it was given', () {
      final t = PosTotals.of(lines, null);

      expect(t.goods, closeTo(3.28, 1e-9));
      expect(t.quoted, isFalse);
    });

    test('a discount comes off the goods and never takes them below nothing', () {
      final t = PosTotals.of(lines, null);

      expect(t.due(1.0), closeTo(2.28, 1e-9));
      expect(t.discountOf(99), closeTo(3.28, 1e-9));
      expect(t.due(99), 0);
      expect(t.discountOf(-5), 0);
    });

    test('deposits are due in full, gift cards are never discounted', () {
      final withCard = [
        _line('a', 1, 10.0),
        PosLine.giftCardSale(amount: 20, currency: 'GBP'),
        const PosLine(
          variantId: 'd', sku: 'd', name: 'd', qty: 2, unitPrice: 1.0,
          currency: 'GBP', depositEach: 0.1,
        ),
      ];

      final t = PosTotals.of(withCard, null);

      expect(t.goods, 12.0);
      expect(t.cards, 20.0);
      expect(t.deposits, closeTo(0.2, 1e-9));
      expect(t.due(5.0), closeTo(12.0 - 5.0 + 20.0 + 0.2, 1e-9));
    });
  });

  group('the VAT table of a sale made offline', () {
    test('is made from what each line cost, grouped by code, and adds up', () {
      final lines = [
        _line('a', 1, 1.29, code: 'T1', rate: 0.2),
        _line('b', 1, 1.99, code: 'T5', rate: 0.05),
        _line('c', 1, 2.49, code: 'T0', rate: 0),
      ];

      final table = offlineVatTable(lines, 0, 2);

      expect(table.map((r) => r.code), ['T1', 'T5', 'T0']);
      expect(table.map((r) => r.vat), [0.22, 0.09, 0.0]);
      expect(table.fold<double>(0, (s, r) => s + r.gross), closeTo(5.77, 1e-9));
      for (final r in table) {
        expect(r.net + r.vat, closeTo(r.gross, 1e-9));
      }
    });

    test('a staff discount is shared by gross and lowers the VAT', () {
      final lines = [
        _line('a', 1, 1.29, code: 'T1', rate: 0.2),
        _line('b', 1, 12.0, code: 'T1', rate: 0.2),
      ];

      final table = offlineVatTable(lines, 1.0, 2);

      // 13.29 less 1.00: 12.29 paid, with 2.05 of VAT (1.29 → 1.19 bears 0.20, 12.00 → 11.10 bears 1.85)
      expect(table.single.gross, closeTo(12.29, 1e-9));
      expect(table.single.vat, closeTo(2.05, 1e-9));
    });

    test('whole-yen prices keep whole yen', () {
      final lines = [_line('a', 1, 129, code: 'S', rate: 0.1)];

      final table = offlineVatTable(lines, 9, 0);

      expect(table.single.gross, 120);
      expect(table.single.vat, 11);
    });
  });
}
