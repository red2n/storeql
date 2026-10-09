import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/tax_inclusive.dart';

/// The offline VAT maths against the vectors the server's Java was proved on:
/// a receipt the till makes with no server is the receipt the server would make.
void main() {
  Map<String, dynamic> load(String name) => jsonDecode(
    File('../../shared/common-service/src/test/resources/$name').readAsStringSync(),
  ) as Map<String, dynamic>;

  int minor(String text, int scale) {
    final negative = text.startsWith('-');
    final t = negative ? text.substring(1) : text;
    final dot = t.indexOf('.');
    final whole = dot < 0 ? t : t.substring(0, dot);
    final frac = dot < 0 ? '' : t.substring(dot + 1);
    final padded = frac.padRight(scale, '0').substring(0, scale);
    final v = int.parse(whole + padded);
    return negative ? -v : v;
  }

  test('every golden VAT vector is reproduced to the minor unit', () {
    final vectors = (load('tax-inclusive-vectors.json')['vectors'] as List)
        .cast<Map<String, dynamic>>();
    expect(vectors.length, greaterThan(3000));
    for (final v in vectors) {
      final scale = v['scale'] as int;
      final gross = minor(v['gross'] as String, scale);
      final rate = v['rate'] as String;
      final where = '${v['currency']} ${v['gross']} at $rate';
      expect(TaxInclusive.vatInside(gross, rate), minor(v['vat'] as String, scale), reason: where);
      expect(TaxInclusive.netOf(gross, rate), minor(v['net'] as String, scale), reason: where);
    }
  });

  test('every shared-discount vector is reproduced', () {
    final cases = (load('tax-inclusive-share-vectors.json')['cases'] as List)
        .cast<Map<String, dynamic>>();
    expect(cases.length, greaterThan(300));
    for (final c in cases) {
      final got = TaxInclusive.shareByGross(
        c['amount'] as int,
        (c['grosses'] as List).cast<int>(),
      );
      expect(got, (c['shares'] as List).cast<int>(), reason: c.toString());
    }
  });

  test('1.29 at 20% is an exact tie and rounds up; a refund is its mirror', () {
    expect(TaxInclusive.vatInside(129, '0.20'), 22);
    expect(TaxInclusive.netOf(129, '0.20'), 107);
    expect(TaxInclusive.vatInside(-129, '0.20'), -22);
    expect(TaxInclusive.vatInside(199, '0.05'), 9);
    expect(TaxInclusive.vatInside(5000, '0'), 0);
  });

  test('a rate read from JSON is read as written', () {
    expect(TaxInclusive.rateText(0.2), '0.2');
    expect(TaxInclusive.rateText(0.05), '0.05');
    expect(TaxInclusive.rateText(null), '0');
    expect(TaxInclusive.vatInside(129, TaxInclusive.rateText(0.2)), 22);
  });

  test('a discount larger than the basket is refused', () {
    expect(() => TaxInclusive.shareByGross(301, [100, 200]), throwsArgumentError);
    expect(() => TaxInclusive.shareByGross(-1, [100, 200]), throwsArgumentError);
  });
}
