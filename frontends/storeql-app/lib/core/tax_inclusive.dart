/// The VAT inside a shelf price, in integer minor units: the Dart half of
/// `com.storeql.money.TaxInclusive`, held to the same golden vectors
/// (`shared/common-service/src/test/resources/tax-inclusive-*.json`) so a
/// receipt made offline equals the one the server makes.
///
/// A till works in doubles only at the screen's edge; money is carried here as
/// whole minor units (pence, cents, yen, fils), so no sum drifts and a share of
/// a discount adds up to the discount exactly.
library;

class TaxInclusive {
  TaxInclusive._();

  /// A decimal rate written as text (`0.20`, `0.05`, `0`) as a fraction
  /// `p / q`. Rates arrive as JSON numbers; [rateText] turns one into the text
  /// this reads, so no binary fraction is ever multiplied.
  static ({int p, int q}) _fraction(String rate) {
    final t = rate.trim();
    final dot = t.indexOf('.');
    if (dot < 0) return (p: int.parse(t), q: 1);
    final digits = t.length - dot - 1;
    var q = 1;
    for (var i = 0; i < digits; i++) {
      q *= 10;
    }
    return (p: int.parse(t.replaceFirst('.', '')), q: q);
  }

  /// A rate as the text [vatInside] reads: `0.2` → `0.2`, `0.2000` → `0.2000`,
  /// null → `0`. (Dart prints a double as the shortest text that reads back, so
  /// `0.05` stays `0.05`; an exponent form is expanded.)
  static String rateText(num? rate) {
    if (rate == null) return '0';
    final s = rate.toString();
    if (!s.contains('e') && !s.contains('E')) return s;
    return rate.toStringAsFixed(12).replaceFirst(RegExp(r'0+$'), '');
  }

  /// The VAT inside [grossMinor] at [rate] (`0.20` for 20%): `gross × r / (1 +
  /// r)` in one division, rounded half up, ties away from zero, so a refund
  /// is the mirror image of the sale. A zero rate or zero amount has none.
  static int vatInside(int grossMinor, String rate) {
    final f = _fraction(rate);
    if (f.p == 0 || grossMinor == 0) return 0;
    final negative = grossMinor < 0;
    final num = grossMinor.abs() * f.p;
    final den = f.q + f.p;
    final vat = (2 * num + den) ~/ (2 * den);
    return negative ? -vat : vat;
  }

  /// What is left of [grossMinor] once its VAT is taken out.
  static int netOf(int grossMinor, String rate) =>
      grossMinor - vatInside(grossMinor, rate);

  /// Shares [amountMinor] (a basket or staff discount) across lines in
  /// proportion to their gross value, to the minor unit, so the shares add up
  /// to the amount exactly: each line takes the floor of its exact share and
  /// the units left over go one each to the largest remainders (a larger gross,
  /// then an earlier line, breaks a tie). No line gives more than it costs.
  static List<int> shareByGross(int amountMinor, List<int> grosses) {
    final total = grosses.fold<int>(0, (a, b) => a + b);
    if (amountMinor < 0 || amountMinor > total) {
      throw ArgumentError('a share of $amountMinor cannot be taken from $total');
    }
    final n = grosses.length;
    if (total == 0) return List<int>.filled(n, 0);
    final floors = <int>[];
    final rest = <int>[];
    var given = 0;
    for (final g in grosses) {
      final exact = amountMinor * g;
      floors.add(exact ~/ total);
      rest.add(exact % total);
      given += floors.last;
    }
    var left = amountMinor - given;
    final order = List<int>.generate(n, (i) => i)
      ..sort((a, b) {
        final byRest = rest[b].compareTo(rest[a]);
        if (byRest != 0) return byRest;
        final byGross = grosses[b].compareTo(grosses[a]);
        return byGross != 0 ? byGross : a.compareTo(b);
      });
    final out = [...floors];
    for (var k = 0; k < left; k++) {
      out[order[k]] += 1;
    }
    return out;
  }
}
