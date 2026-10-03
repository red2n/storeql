import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/pos/pos_fiscal_receipt.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_receipt_data.dart';
import 'package:storeql_app/features/pos/pos_receipt_escpos.dart';

// ---------------------------------------------------------------------------
// The receipt as ESC/POS bytes (09.12). Byte-exact where a printer would care:
// the initialise, the alignment and size commands, the cut. Column-exact for
// the two paper widths. And the one property a printer makes dangerous: a
// product name is typed by a shop, and ESC/GS in it must never become a
// command — no drawer kick, no cut, no feed from a name.
// ---------------------------------------------------------------------------

const esc = 0x1B, gs = 0x1D, lf = 0x0A;

PosLine line(String name, {double qty = 1, double price = 2.5, String soldBy = 'EACH', String? unit}) =>
    PosLine(
      variantId: 'v-${name.hashCode}',
      sku: 'SKU',
      name: name,
      qty: qty,
      unitPrice: price,
      currency: 'GBP',
      soldBy: soldBy,
      unit: unit,
    );

PosReceiptData receipt({
  List<PosLine>? items,
  String store = 'High Street',
  FiscalStamp? stamp,
  String? number = '2026-000042',
  double change = 0,
}) =>
    PosReceiptData(
      orderId: '01a090ae-611e-701e-a773-cff68a489efe',
      storeName: store,
      storeAddress: '12 High St, London, EC1A 1BB',
      dateTime: DateTime(2026, 9, 13, 11, 30),
      cashierEmail: 'till@example.com',
      items: items ?? [line('Mug', price: 4), line('Plate', qty: 2, price: 3)],
      subtotal: 10,
      discount: 0,
      total: 10,
      currency: 'GBP',
      tenders: const [PosTender(method: 'CASH', amount: 10, cashGiven: 10)],
      change: change,
      fiscalNumber: number,
      fiscalStamp: stamp,
    );

/// The printable text between commands, one entry per line.
List<String> textLines(Uint8List bytes) {
  final out = <String>[];
  final cur = StringBuffer();
  var i = 0;
  while (i < bytes.length) {
    final b = bytes[i];
    if (b == esc || b == gs) {
      // Skip the command and its fixed arguments; the QR store command carries data after its
      // header, which is text and is left in place by the length it declares.
      if (b == gs && i + 7 < bytes.length && bytes[i + 1] == 0x28 && bytes[i + 2] == 0x6B) {
        final len = bytes[i + 3] | (bytes[i + 4] << 8);
        if (bytes[i + 6] == 80) {
          i += 8;
          continue; // the QR payload follows as plain bytes, and is read as text
        }
        i += 5 + len;
        continue;
      }
      // Fixed-length commands: ESC @ (2), ESC p m t1 t2 (5), GS V m n (4), the rest (3).
      final cmd = bytes[i + 1];
      i += b == esc && cmd == 0x40
          ? 2
          : b == esc && cmd == 0x70
              ? 5
              : b == gs && cmd == 0x56
                  ? 4
                  : 3;
      continue;
    }
    if (b == lf) {
      out.add(cur.toString());
      cur.clear();
    } else {
      cur.writeCharCode(b);
    }
    i++;
  }
  if (cur.isNotEmpty) out.add(cur.toString());
  return out;
}

int count(Uint8List bytes, List<int> seq) {
  var n = 0;
  for (var i = 0; i + seq.length <= bytes.length; i++) {
    var hit = true;
    for (var j = 0; j < seq.length; j++) {
      if (bytes[i + j] != seq[j]) {
        hit = false;
        break;
      }
    }
    if (hit) n++;
  }
  return n;
}

void main() {
  group('the commands a printer acts on', () {
    test('starts with initialise and the code page, ends with a partial cut, once', () {
      final bytes = const EscPosReceipt().encode(receipt());
      expect(bytes.sublist(0, 5), [esc, 0x40, esc, 0x74, 16]);
      expect(bytes.sublist(bytes.length - 4), [gs, 0x56, 66, 0]);
      expect(count(bytes, [gs, 0x56]), 1);
      expect(count(bytes, [esc, 0x40]), 1);
    });

    test('the store name is centred and double-sized, the body is left-aligned again', () {
      final bytes = const EscPosReceipt().encode(receipt());
      expect(count(bytes, [esc, 0x61, 1, gs, 0x21, 0x11]), 1);
      expect(count(bytes, [gs, 0x21, 0x00]), greaterThanOrEqualTo(1));
      expect(count(bytes, [esc, 0x61, 0]), greaterThanOrEqualTo(1));
    });

    test('the total is bold and tall, then normal again', () {
      final bytes = const EscPosReceipt().encode(receipt());
      expect(count(bytes, [esc, 0x45, 1, gs, 0x21, 0x10]), 1);
      expect(count(bytes, [gs, 0x21, 0x00, esc, 0x45, 0]), 1);
    });

    test('the drawer is kicked only when asked, and never from a product name', () {
      final quiet = const EscPosReceipt().encode(receipt());
      expect(count(quiet, [esc, 0x70]), 0);
      final kick = const EscPosReceipt(openDrawer: true).encode(receipt());
      expect(count(kick, [esc, 0x70, 0x00, 25, 250]), 1);

      // A name carrying the drawer-kick, cut and feed commands prints as text minus the
      // control bytes: no ESC, no GS, no LF from it reaches the printer.
      final hostile = const EscPosReceipt().encode(receipt(items: [
        line('Mug\x1Bp\x00\x19\xFA\x1DV\x42\x00\n\n\n\x1B@'),
      ]));
      expect(count(hostile, [esc, 0x70]), 0, reason: 'no drawer kick');
      expect(count(hostile, [gs, 0x56]), 1, reason: 'only the real cut');
      expect(count(hostile, [esc, 0x40]), 1, reason: 'only the real initialise');
      expect(textLines(hostile).any((l) => l.startsWith('Mugp')), isTrue, reason: 'printed as text');
    });

    test('no cut when the till asks for none', () {
      final bytes = const EscPosReceipt(cut: false).encode(receipt());
      expect(count(bytes, [gs, 0x56]), 0);
    });
  });

  group('the layout', () {
    test('every text line fits the paper: 32 columns on 58 mm, 48 on 80 mm', () {
      for (final paper in PaperWidth.values) {
        final bytes = EscPosReceipt(paper: paper).encode(receipt(items: [
          line('A very long product name that certainly needs wrapping on narrow paper', price: 1234.5),
        ]));
        for (final l in textLines(bytes)) {
          expect(l.length, lessThanOrEqualTo(paper.columns), reason: '"$l" on ${paper.name}');
        }
      }
    });

    test('a row puts the value flush right', () {
      final lines = textLines(const EscPosReceipt(paper: PaperWidth.mm58).encode(receipt()));
      final total = lines.firstWhere((l) => l.startsWith('TOTAL'));
      expect(total.length, 32);
      expect(total, endsWith('GBP 10.00'));
      expect(lines.firstWhere((l) => l.startsWith('Receipt no.:')), endsWith('2026-000042'));
    });

    test('a measured line prints its reading and the price per unit', () {
      final lines = textLines(const EscPosReceipt().encode(receipt(items: [
        line('Cheddar', qty: 0.375, price: 12, soldBy: 'WEIGHT', unit: 'kg'),
      ])));
      expect(lines.any((l) => l.contains('0.375 kg x GBP 12.00/kg')), isTrue);
      expect(lines.any((l) => l.endsWith('GBP 4.50')), isTrue);
    });

    test('pound and euro print as Windows-1252, the rest of Unicode as a question mark', () {
      final bytes = const EscPosReceipt().encode(receipt(items: [line('Tee £ € 日本')]));
      expect(count(bytes, [0xA3]), 1);
      expect(count(bytes, [0x80]), 1);
      expect(textLines(bytes).any((l) => l.contains('Tee £  ??')), isTrue);
    });

    test('a receipt without a number says so rather than passing the order id off as one', () {
      final lines = textLines(const EscPosReceipt().encode(receipt(number: null).copyWithNote()));
      expect(lines.any((l) => l.startsWith('Receipt no.:')), isFalse);
      expect(lines.any((l) => l.startsWith('Order ref:')), isTrue);
      expect(lines.any((l) => l.contains('not issued yet')), isTrue);
    });

    test('change is printed only when there is some', () {
      expect(textLines(const EscPosReceipt().encode(receipt())).any((l) => l.startsWith('Change')), isFalse);
      expect(textLines(const EscPosReceipt().encode(receipt(change: 2.5))).any((l) => l.startsWith('Change') && l.endsWith('GBP 2.50')), isTrue);
    });
  });

  group('the fiscal stamp', () {
    test('a German sale prints the module fields and a QR code', () {
      final bytes = const EscPosReceipt().encode(receipt(
        stamp: const FiscalStamp(
          fullNumber: '2026-000042',
          regime: 'KASSENSICHV',
          tseSerial: 'SIM-001',
          tseTransactionNumber: 7,
          tseSignatureCounter: 9,
          tseSignature: 'abc',
          tseStartedAt: '2026-09-13T11:30:00Z',
          tseFinishedAt: '2026-09-13T11:30:02Z',
          tseQr: 'V0;SIM-001;Kassenbeleg-V1;Beleg^10.00',
        ),
      ));
      final lines = textLines(bytes);
      expect(lines.any((l) => l.startsWith('TSE-Seriennr.:') && l.endsWith('SIM-001')), isTrue);
      expect(lines.any((l) => l.startsWith('Transaktionsnr.:') && l.endsWith('7')), isTrue);
      // Two codes: the module's own, and the receipt number's (return-controls).
      expect(count(bytes, [gs, 0x28, 0x6B, 4, 0, 49, 65, 50, 0]), 2, reason: 'QR model 2');
      expect(count(bytes, [gs, 0x28, 0x6B, 3, 0, 49, 81, 48]), 2, reason: 'QR printed');
    });

    test('a module outage is printed as the law asks', () {
      final lines = textLines(const EscPosReceipt().encode(receipt(
        stamp: const FiscalStamp(fullNumber: '2026-000043', regime: 'KASSENSICHV', tseError: 'timeout'),
      )));
      expect(lines.any((l) => l.startsWith('TSE:') && l.endsWith('ausgefallen')), isTrue);
      expect(count(const EscPosReceipt().encode(receipt(
        stamp: const FiscalStamp(fullNumber: '2026-000043', regime: 'KASSENSICHV', tseError: 'timeout'),
      )), [gs, 0x28, 0x6B, 3, 0, 49, 81, 48]), 1,
          reason: 'no QR of the signature: only the receipt number\'s code');
    });

    test('a Portuguese document prints the excerpt and the certificate', () {
      final lines = textLines(const EscPosReceipt().encode(receipt(
        stamp: const FiscalStamp(
            fullNumber: 'FR 2026/42', regime: 'SAFT_PT', ptExcerpt: 'aB3x', ptCertificateNumber: '9999', ptAtcud: 'ABCD-42'),
      )));
      expect(lines.any((l) => l.startsWith('ATCUD:') && l.endsWith('ABCD-42')), isTrue);
      expect(lines.join(' '), contains('aB3x - Processado por programa certificado n.o 9999/AT'));
    });
  });

  group('abuse', () {
    test('a thousand lines encode, bounded and linear', () {
      final hundred = const EscPosReceipt().encode(receipt(items: List.generate(100, (i) => line('Item $i'))));
      final thousand = const EscPosReceipt().encode(receipt(items: List.generate(1000, (i) => line('Item $i'))));
      expect(thousand.length, lessThan(hundred.length * 11));
      expect(count(thousand, [gs, 0x56]), 1);
    });

    test('an empty receipt is still a well-formed document', () {
      final bytes = const EscPosReceipt().encode(receipt(items: const [], store: ''));
      expect(bytes.sublist(0, 2), [esc, 0x40]);
      expect(count(bytes, [gs, 0x56]), 1);
    });
  });
}

extension on PosReceiptData {
  PosReceiptData copyWithNote() => PosReceiptData(
        orderId: orderId,
        storeName: storeName,
        storeAddress: storeAddress,
        dateTime: dateTime,
        cashierEmail: cashierEmail,
        items: items,
        subtotal: subtotal,
        discount: discount,
        total: total,
        currency: currency,
        tenders: tenders,
        change: change,
        customerName: customerName,
        fiscalNumberNote: 'Receipt number not issued yet. Reprint once it is.',
      );
}
