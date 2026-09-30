import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_receipt_data.dart';
import 'package:storeql_app/features/pos/pos_receipt_escpos.dart';

// ---------------------------------------------------------------------------
// The receipt carries a scannable code of its number (the legal receipt number,
// else the short order reference) so the till's Returns screen finds the sale
// by scanning it. HTML: a QR drawn as SVG. ESC/POS: the printer's own QR.
// ---------------------------------------------------------------------------

const _orderId = '01a090ae-611e-701e-a773-cff68a489efe';

PosReceiptData _receipt({String orderId = _orderId, String? number}) => PosReceiptData(
      orderId: orderId,
      storeName: 'High Street',
      dateTime: DateTime(2026, 9, 13, 11, 30),
      items: const [
        PosLine(
            variantId: 'v-1',
            sku: 'MUG',
            name: 'Mug',
            qty: 1,
            unitPrice: 4,
            currency: 'GBP'),
      ],
      subtotal: 4,
      discount: 0,
      total: 4,
      currency: 'GBP',
      tenders: const [PosTender(method: 'CASH', amount: 4, cashGiven: 4)],
      change: 0,
      fiscalNumber: number,
    );

bool _contains(Uint8List bytes, List<int> needle) {
  for (var i = 0; i + needle.length <= bytes.length; i++) {
    var ok = true;
    for (var j = 0; j < needle.length; j++) {
      if (bytes[i + j] != needle[j]) {
        ok = false;
        break;
      }
    }
    if (ok) return true;
  }
  return false;
}

void main() {
  // The receipt prints its date in the device's own words.
  setUpAll(initializeDateFormatting);

  group('the code carries', () {
    test('the legal receipt number when there is one', () {
      expect(_receipt(number: '2026-000042').receiptCode, '2026-000042');
    });

    test('the short order reference when there is none', () {
      expect(_receipt().receiptCode, '8A489EFE');
    });

    test('nothing for a sale held offline: its reference means nothing to the server yet',
        () {
      expect(_receipt(orderId: '3C4D5E').receiptCode, isNull);
    });
  });

  group('the HTML receipt', () {
    test('shows the receipt number as a QR code', () {
      final html = _receipt(number: '2026-000042').toHtml();
      expect(html, contains('data-receipt-code="2026-000042"'));
      expect(html, contains('<svg'));
      expect(html, contains('<rect'));
    });

    test('shows the short reference as a QR code when no number is issued yet', () {
      final data = _receipt();
      expect(data.toHtml(), contains('data-receipt-code="${data.shortId}"'));
    });

    test('an offline receipt prints no code', () {
      final html = _receipt(orderId: '3C4D5E').toHtml();
      expect(html, isNot(contains('data-receipt-code')));
      expect(html, isNot(contains('<svg')));
    });
  });

  group('the ESC/POS receipt', () {
    const gs = 0x1D;

    test('stores and prints a QR of the receipt number', () {
      final bytes = const EscPosReceipt().encode(_receipt(number: '2026-000042'));
      final payload = ascii.encode('2026-000042');
      // GS ( k, model 2, then the stored data: 11 bytes + 3.
      expect(_contains(bytes, [gs, 0x28, 0x6B, 4, 0, 49, 65, 50, 0]), isTrue);
      expect(_contains(bytes, [gs, 0x28, 0x6B, 14, 0, 49, 80, 48, ...payload]), isTrue,
          reason: 'the receipt number is the stored data');
      expect(_contains(bytes, [gs, 0x28, 0x6B, 3, 0, 49, 81, 48]), isTrue,
          reason: 'and it is printed');
    });

    test('an offline receipt prints no code', () {
      final bytes = const EscPosReceipt().encode(_receipt(orderId: '3C4D5E'));
      expect(_contains(bytes, [gs, 0x28, 0x6B]), isFalse);
    });
  });
}
