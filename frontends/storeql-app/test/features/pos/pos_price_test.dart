import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/pos/pos_price.dart';

void main() {
  test('the till charges the price with VAT, not the price before it', () {
    final p = ResolvedPosPrice.fromJson({
      'unitPrice': 1.07,
      'totalWithVat': 1.29,
      'vatAmount': 0.22,
      'vatCode': 'T1',
      'vatRate': 0.2,
      'currency': 'GBP',
      'taxInclusive': true,
    });

    expect(p.gross, 1.29);
    expect(p.vatCode, 'T1');
    expect(p.vatRate, 0.2);
    expect(p.taxInclusive, isTrue);
    expect(p.currency, 'GBP');
  });

  test('a price list that adds VAT is charged with the VAT added', () {
    final p = ResolvedPosPrice.fromJson({
      'unitPrice': 10.0,
      'totalWithVat': 12.0,
      'vatCode': 'T1',
      'vatRate': 0.2,
      'currency': 'GBP',
    });

    expect(p.gross, 12.0);
    expect(p.taxInclusive, isFalse);
  });

  test('an older pricing service that sends no total is read from the unit price', () {
    final p = ResolvedPosPrice.fromJson({'unitPrice': 4.5, 'currency': 'GBP'});

    expect(p.gross, 4.5);
    expect(p.vatCode, isNull);
    expect(p.vatRate, isNull);
  });
}
