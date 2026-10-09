import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_receipt_data.dart';
import 'package:storeql_app/features/pos/pos_receipt_escpos.dart';
import 'package:storeql_app/features/pos/pos_vat.dart';

// ---------------------------------------------------------------------------
// The receipt of a sale at shelf prices (intent/vat-inclusive-pricing.md): who
// is selling and their VAT number, the prices as the shelf says them, and a VAT
// table whose gross column adds up to the goods. A sale priced net prints as it
// always did.
// ---------------------------------------------------------------------------

const _bread = PosLine(
  variantId: 'v-bread', sku: 'BRE', name: 'Bread', qty: 1, unitPrice: 1.29,
  currency: 'GBP', vatCode: 'T1', vatRate: 0.2, taxInclusive: true,
);
const _jam = PosLine(
  variantId: 'v-jam', sku: 'JAM', name: 'Jam', qty: 1, unitPrice: 1.99,
  currency: 'GBP', vatCode: 'T5', vatRate: 0.05, taxInclusive: true,
);

const _vat = PosReceiptVat(
  sellerName: 'Acme Foods Ltd',
  vatNumber: 'GB123456789',
  rows: [
    PosVatRow(code: 'T1', rate: 0.2, gross: 1.29, net: 1.07, vat: 0.22),
    PosVatRow(code: 'T5', rate: 0.05, gross: 1.99, net: 1.9, vat: 0.09),
  ],
);

PosReceiptData _receipt({PosReceiptVat? vat}) => PosReceiptData(
  orderId: '01a090ae-611e-701e-a773-cff68a489efe',
  storeName: 'High Street',
  storeAddress: '12 High St, London, EC1A 1BB',
  dateTime: DateTime(2026, 10, 9, 11, 30),
  items: const [_bread, _jam],
  subtotal: 3.28,
  discount: 0,
  total: 3.28,
  currency: 'GBP',
  tenders: const [PosTender(method: 'CARD', amount: 3.28)],
  change: 0,
  fiscalNumber: '2026-000042',
  vat: vat,
);

List<String> _printed(Uint8List bytes) {
  // Printable bytes only; commands are not text.
  final out = <String>[];
  final cur = StringBuffer();
  for (final b in bytes) {
    if (b == 0x0A) {
      out.add(cur.toString());
      cur.clear();
    } else if (b >= 0x20 && b < 0x7F) {
      cur.writeCharCode(b);
    }
  }
  if (cur.isNotEmpty) out.add(cur.toString());
  return out;
}

void main() {
  setUpAll(() => initializeDateFormatting('en'));

  group('the page', () {
    test('names the seller and prints the VAT table', () {
      final html = _receipt(vat: _vat).toHtml();

      expect(html, contains('Acme Foods Ltd'));
      expect(html, contains('VAT No. GB123456789'));
      expect(html, contains('data-vat-table="1"'));
      expect(html, contains('Prices include VAT'));
      expect(html, contains('<td>T1</td><td>20%</td>'));
      expect(html, contains('<td>T5</td><td>5%</td>'));
      // The shelf prices, as the shelf says them.
      expect(html, contains('£1.29'));
      expect(html, contains('£1.99'));
    });

    test('a sale priced net prints no VAT table and no seller block', () {
      final html = _receipt().toHtml();

      expect(html, isNot(contains('data-vat-table')));
      expect(html, isNot(contains('Prices include VAT')));
      expect(html, isNot(contains('VAT No.')));
    });

    test('a seller who has not set a VAT number prints none', () {
      final html = _receipt(
        vat: const PosReceiptVat(sellerName: 'Acme Foods Ltd', rows: [
          PosVatRow(code: 'T1', rate: 0.2, gross: 1.29, net: 1.07, vat: 0.22),
        ]),
      ).toHtml();

      expect(html, contains('Acme Foods Ltd'));
      expect(html, isNot(contains('VAT No.')));
    });

    test('the receipt keeps its VAT through the copies the till makes of it', () {
      final r = _receipt(vat: _vat)
          .withSoldCards(const [])
          .withFiscalNumber('2026-000043');

      expect(r.vat, isNotNull);
      expect(r.toHtml(), contains('VAT No. GB123456789'));
    });
  });

  group('the thermal print', () {
    test('prints the seller, the note and the table on 80 mm paper', () {
      final lines = _printed(const EscPosReceipt().encode(_receipt(vat: _vat)));

      expect(lines, contains('Acme Foods Ltd'));
      expect(lines, contains('VAT No. GB123456789'));
      expect(lines, contains('Prices include VAT'));
      expect(lines.any((l) => l.startsWith('T1 20%') && l.contains('1.29') && l.contains('1.07') && l.contains('0.22')), isTrue);
      expect(lines.any((l) => l.startsWith('T5 5%') && l.contains('1.99') && l.contains('1.90') && l.contains('0.09')), isTrue);
    });

    test('a sale priced net prints none of it', () {
      final lines = _printed(const EscPosReceipt().encode(_receipt()));

      expect(lines.any((l) => l.contains('Prices include VAT')), isFalse);
      expect(lines.any((l) => l.contains('VAT No.')), isFalse);
    });

    test('money is at the currency\'s own minor units: whole yen print without decimals', () {
      final yen = PosReceiptData(
        orderId: '01a090ae-611e-701e-a773-cff68a489efe',
        storeName: 'Tokyo',
        dateTime: DateTime(2026, 10, 9),
        items: const [
          PosLine(variantId: 'v', sku: 's', name: 'Ramen', qty: 1, unitPrice: 129, currency: 'JPY'),
        ],
        subtotal: 129,
        discount: 0,
        total: 129,
        currency: 'JPY',
        tenders: const [PosTender(method: 'CASH', amount: 129, cashGiven: 129)],
        change: 0,
      );

      final lines = _printed(const EscPosReceipt().encode(yen));

      expect(lines.any((l) => l.contains('JPY 129') && !l.contains('129.00')), isTrue);
    });
  });

  group('the server\'s receipt document', () {
    test('is read into the same seller and rows', () {
      final v = PosReceiptVat.fromDocument({
        'seller': {'legalName': 'Acme Foods Ltd', 'tradingName': 'Acme', 'vatNumber': 'GB123456789'},
        'vat': [
          {'vatCode': 'T1', 'rate': 0.2, 'gross': 1.29, 'net': 1.07, 'vat': 0.22},
        ],
      });

      expect(v.sellerName, 'Acme Foods Ltd');
      expect(v.vatNumber, 'GB123456789');
      expect(v.rows.single.vat, 0.22);
    });

    test('a business that has set nothing yet is read as nothing', () {
      final v = PosReceiptVat.fromDocument({'seller': {}, 'vat': []});

      expect(v.sellerName, isNull);
      expect(v.vatNumber, isNull);
      expect(v.rows, isEmpty);
    });
  });
}
