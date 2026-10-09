/// The VAT on a receipt (intent/vat-inclusive-pricing.md): the rows of its VAT
/// table, who is selling, and the table of a sale made with no server.
///
/// Pure, with no platform or network import: a receipt made offline and one
/// the server drew come out of the same maths ([TaxInclusive]).
library;

import '../../core/tax_inclusive.dart';
import 'pos_providers.dart';

/// A row of a VAT table: what was paid at a code, the net of it and the VAT in it.
class PosVatRow {
  final String? code;
  final double? rate;
  final double gross;
  final double net;
  final double vat;
  const PosVatRow({
    required this.code,
    required this.rate,
    required this.gross,
    required this.net,
    required this.vat,
  });
}

/// What a receipt says about VAT when the sale was at shelf prices: who is
/// selling (legal name and VAT number, when the business has set them) and the
/// table whose gross column adds up to what the goods cost.
class PosReceiptVat {
  final String? sellerName;
  final String? vatNumber;
  final List<PosVatRow> rows;

  const PosReceiptVat({this.sellerName, this.vatNumber, this.rows = const []});

  /// Reads the receipt document the server draws (`GET
  /// /orders/{id}/receipt-document`): the same figures as the books.
  factory PosReceiptVat.fromDocument(Map<String, dynamic> d) {
    double n(Object? v) => (v as num?)?.toDouble() ?? 0;
    final seller = (d['seller'] as Map?)?.cast<String, dynamic>() ?? const {};
    String? text(Object? v) {
      final s = (v as String?)?.trim();
      return s == null || s.isEmpty ? null : s;
    }

    return PosReceiptVat(
      sellerName: text(seller['legalName']) ?? text(seller['tradingName']),
      vatNumber: text(seller['vatNumber']),
      rows: [
        for (final r in (d['vat'] as List? ?? const []))
          PosVatRow(
            code: (r as Map)['vatCode'] as String?,
            rate: (r['rate'] as num?)?.toDouble(),
            gross: n(r['gross']),
            net: n(r['net']),
            vat: n(r['vat']),
          ),
      ],
    );
  }
}

/// The VAT table of a sale made without the server: the lines' VAT worked from
/// what each finally cost — the shelf price times the quantity, less its share
/// of a staff [discount] — with the maths the server would use, grouped by
/// code. [minorUnits] is the currency's.
List<PosVatRow> offlineVatTable(
  List<PosLine> lines,
  double discount,
  int minorUnits,
) {
  final scale = _pow10(minorUnits);
  final goods = productLines(lines);
  final grosses = [for (final l in goods) (l.lineTotal * scale).round()];
  final total = grosses.fold<int>(0, (a, b) => a + b);
  final off = (discount * scale).round().clamp(0, total);
  final shares = TaxInclusive.shareByGross(off, grosses);
  final byCode = <String, List<int>>{};
  final rates = <String, double?>{};
  for (var i = 0; i < goods.length; i++) {
    final code = goods[i].vatCode ?? '';
    final paid = grosses[i] - shares[i];
    final vat = TaxInclusive.vatInside(
      paid,
      TaxInclusive.rateText(goods[i].vatRate),
    );
    final sums = byCode.putIfAbsent(code, () => [0, 0]);
    sums[0] += paid;
    sums[1] += vat;
    rates.putIfAbsent(code, () => goods[i].vatRate);
  }
  return [
    for (final e in byCode.entries)
      PosVatRow(
        code: e.key.isEmpty ? null : e.key,
        rate: rates[e.key],
        gross: e.value[0] / scale,
        net: (e.value[0] - e.value[1]) / scale,
        vat: e.value[1] / scale,
      ),
  ];
}

int _pow10(int n) {
  var v = 1;
  for (var i = 0; i < n; i++) {
    v *= 10;
  }
  return v;
}
