import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/amount_entry.dart';

/// Every language the app ships (AppLocales.supported, as intl names them).
const _shipped = [
  'en', 'en_IN', 'en_US', 'en_AU', 'en_ZA', 'en_IE', 'en_CA', 'en_NZ', 'en_SG',
  'en_GB', 'pl', 'ro', 'pa', 'ur', 'bn', 'gu', 'ar',
];

/// What [text] is, worked out apart from the reader: digits with at most one
/// of [decimals] among them, within [shape], as a plain decimal; null for
/// anything else.
String? _oracle(String text, Set<String> decimals, AmountShape shape) {
  // A signed amount may lead with one sign; a minus makes it negative, unless
  // it is nought.
  if (shape.signed && text.isNotEmpty && const {'-', '+', '\u2212'}.contains(text[0])) {
    final unsigned = _oracle(text.substring(1), decimals, AmountShape(shape.whole, shape.decimals));
    return unsigned == null || text[0] == '+' || unsigned == '0' ? unsigned : '-$unsigned';
  }
  var whole = '';
  String? fraction;
  for (final c in text.split('')) {
    if (decimals.contains(c)) {
      if (fraction != null) return null;
      fraction = '';
    } else if (RegExp(r'^[0-9]$').hasMatch(c)) {
      if (fraction == null) {
        whole += c;
      } else {
        fraction += c;
      }
    } else {
      return null;
    }
  }
  if (whole.isEmpty && (fraction ?? '').isEmpty) return null;
  if (whole.length > shape.whole) return null;
  if (fraction != null && (shape.decimals == 0 || fraction.length > shape.decimals)) return null;
  final w = whole.replaceFirst(RegExp(r'^0+'), '');
  final f = (fraction ?? '').replaceFirst(RegExp(r'0+$'), '');
  return f.isEmpty ? (w.isEmpty ? '0' : w) : '${w.isEmpty ? '0' : w}.$f';
}

void main() {
  moreTests();
  // Nothing is read but what was typed: no key is dropped, so whatever a field
  // sends is exactly the reading of what it holds, and anything holding a key
  // it cannot read — a grouping mark, a sign, a letter, a second decimal mark,
  // a place too many — is no figure at all.
  test('in every shipped language, an amount is read exactly as typed or not at all', () {
    const keys = ['1', '5', '0', '.', ',', ' ', '\u066B', '-', 'a'];
    var texts = <String>[''];
    final all = <String>[];
    for (var n = 0; n < 4; n++) {
      texts = [for (final t in texts) for (final k in keys) t + k];
      all.addAll(texts);
    }
    for (final locale in _shipped) {
      final marks = AmountMarks.ofApp(locale: locale);
      for (final shape in const [
        AmountShape(14, 2),
        AmountShape(14, 0),
        AmountShape(14, 3),
        AmountShape(16, 2),
        AmountShape(2, 2),
        AmountShape(4, 2, signed: true),
      ]) {
        for (final text in all) {
          expect(shape.read(text, marks), _oracle(text, marks.decimals, shape),
              reason: '$locale ${shape.whole}/${shape.decimals}${shape.signed ? ' signed' : ''} "$text"');
          if (text.isNotEmpty) {
            expect(shape.refusal(text, marks) != null, shape.read(text, marks) == null,
                reason: 'text not read says why, and text refused is never read: $locale "$text"');
          }
        }
      }
    }
  });

  test('a grouping mark is never a decimal, and the other of point and comma is', () {
    for (final locale in _shipped) {
      final marks = AmountMarks.ofApp(locale: locale);
      expect(marks.decimals.intersection(marks.grouping), isEmpty, reason: locale);
      expect(marks.decimals, contains(marks.decimal), reason: locale);
      expect(marks.decimals, contains('\u066B'), reason: locale);
      expect(marks.grouping, containsAll(['\u066C', '\u2009', ' ']), reason: locale);
    }
    expect(AmountMarks.ofApp(locale: 'ro').grouping, contains('.'));
    expect(AmountMarks.ofApp(locale: 'en_GB').grouping, contains(','));
    expect(AmountMarks.ofApp(locale: 'pl').decimals, containsAll(['.', ',']));
  });

  test('refusals in words', () {
    final en = AmountMarks.ofApp(locale: 'en_GB');
    final ro = AmountMarks.ofApp(locale: 'ro');
    const money = AmountShape(14, 2);
    expect(money.refusal('-3', en), 'Type the amount without a sign.');
    expect(money.refusal('+3', en), 'Type the amount without a sign.');
    expect(money.refusal('\u22123', en), 'Type the amount without a sign.');
    expect(money.refusal('12.505', en), 'At most 2 decimal places.');
    expect(money.refusal('12,50', en),
        'Type the amount without thousands separators. Decimals go after a point.');
    expect(money.refusal('12.50', ro),
        'Type the amount without thousands separators. Decimals go after a comma.');
    expect(money.refusal('12\uFF0E50', en), 'Only digits and a decimal point.');
    expect(const AmountShape(14, 0).refusal('12\u066B50', en), 'Whole amounts only.');
    expect(money.refusal('1.2.5', en), 'Only one decimal point.');
    expect(money.refusal('', en), isNull);
  });

  test('a figure is written back the way the field reads it', () {
    final ro = AmountMarks.ofApp(locale: 'ro');
    expect(ro.write('12.5'), '12,5');
    expect(const AmountShape(12, 6).read(ro.write('0.0125'), ro), '0.0125');
    expect(ro.hint(2), '0,00');
    expect(AmountMarks.ofApp(locale: 'en_GB').hint(0), '0');
  });

  // A temperature goes below nought: a signed shape reads one sign before the
  // digits (a hyphen, the true minus U+2212 a phone's symbols offer, or a
  // plus), and refuses one anywhere else in words — never dropping it, so a
  // freezer's −18 is never recorded as 18.
  test('a signed amount reads one leading sign, and refuses one anywhere else', () {
    const reading = AmountShape(4, 2, signed: true);
    final en = AmountMarks.ofApp(locale: 'en_GB');
    final ar = AmountMarks.ofApp(locale: 'ar');
    final ro = AmountMarks.ofApp(locale: 'ro');
    expect(reading.read('-18', en), '-18');
    expect(reading.read('\u221218', en), '-18');
    expect(reading.read('+5', en), '5');
    expect(reading.read('-0.00', en), '0');
    expect(reading.read('-18,5', ro), '-18.5');
    expect(reading.read('62\u066B5', ar), '62.5');
    expect(reading.read('-', en), isNull);
    // A sign alone is no figure: refused, never sent as blank (the default).
    expect(reading.refusal('-', en), 'Type the digits after the sign.');
    for (final t in ['6-2', '--5', '-+5', '62-', '5\u2212']) {
      expect(reading.read(t, en), isNull, reason: t);
      expect(reading.refusal(t, en), 'Only one sign, before the digits.', reason: t);
    }
    expect(reading.refusal('-12345', en), 'At most 4 digits before the decimals.');
    expect(reading.refusal('-1.505', en), 'At most 2 decimal places.');
    expect(reading.refusal('-1,5', en),
        'Type the amount without thousands separators. Decimals go after a point.');
    // Unsigned, a sign is still refused as before.
    expect(const AmountShape(4, 2).refusal('-18', en), 'Type the amount without a sign.');
    expect(const AmountShape(4, 2).read('-18', en), isNull);
  });
}


/// What [text] is in [locale], as the field reads it: a plain decimal, or the
/// refusal in words.
Object? _typed(String text, String locale, AmountShape shape) {
  final marks = AmountMarks.ofApp(locale: locale);
  return shape.refusal(text, marks) ?? shape.read(text, marks);
}

void moreTests() {
  // Five keys at most of digits and marks, in every shipped language: read
  // exactly as an independent reading says, where a point or comma that is
  // not the language's own (intl's decimal mark) before exactly three digits,
  // after a group's lead, is no figure at all.
  test('a mark that may group thousands is never read, in every shipped language', () {
    const keys = ['1', '0', '.', ',', '\u066B'];
    var texts = <String>[''];
    final all = <String>[];
    for (var n = 0; n < 5; n++) {
      texts = [for (final t in texts) for (final k in keys) t + k];
      all.addAll(texts);
    }
    for (final locale in _shipped) {
      final marks = AmountMarks.ofApp(locale: locale);
      final own = NumberFormat.decimalPattern(locale).symbols;
      final foreign = {'.', ','}.difference({own.DECIMAL_SEP, own.GROUP_SEP});
      for (final shape in const [AmountShape(15, 3), AmountShape(14, 2), AmountShape(14, 0)]) {
        for (final text in all) {
          var expected = _oracle(text, marks.decimals, shape);
          final m = RegExp(r'^([1-9][0-9]{0,2})([.,])([0-9]{3})$').firstMatch(text);
          if (m != null && foreign.contains(m.group(2)) && shape.decimals > 0) expected = null;
          expect(shape.read(text, marks), expected, reason: '$locale ${shape.decimals} "$text"');
          expect(shape.refusal(text, marks) != null, shape.read(text, marks) == null,
              reason: '$locale ${shape.decimals} "$text"');
        }
      }
    }
  });

  // Text that holds a mark or a sign and no digit is no figure, and is not
  // blank either: it is refused in words, so a field that sends nothing for
  // blank (the server's default: a debit note's whole gross, a fee of none)
  // never sends that for a lone point.
  test('a lone mark or sign is refused in every shipped language, never read as blank', () {
    for (final locale in _shipped) {
      final marks = AmountMarks.ofApp(locale: locale);
      for (final shape in const [
        AmountShape(14, 2),
        AmountShape(14, 0),
        AmountShape(15, 3),
        AmountShape(4, 2, signed: true),
      ]) {
        for (final t in ['.', ',', '٫', '-', '−', '+', '-.', '+,', '−٫']) {
          expect(shape.read(t, marks), isNull, reason: '$locale "$t"');
          expect(shape.refusal(t, marks), isNotNull,
              reason: 'a lone mark is refused, not blank: $locale ${shape.decimals} "$t"');
        }
        expect(shape.refusal('', marks), isNull, reason: 'blank is blank: $locale');
      }
    }
    final en = AmountMarks.ofApp(locale: 'en_GB');
    expect(const AmountShape(14, 2).refusal('.', en), 'Type the amount in digits.');
    expect(const AmountShape(14, 2).refusal('٫', AmountMarks.ofApp(locale: 'ar')),
        'Type the amount in digits.');
  });

  // Polish and South African English write the decimal with a comma and
  // group with a space, but take a point as the decimal too. Before exactly
  // three digits that point may be a thousands group (1.250 is twelve hundred
  // and fifty to many): refused, never read as one and a quarter. Where the
  // point is the language's own mark (English, Arabic) it is the decimal, as
  // every hint and prefill writes it; Romanian groups with it, refused as ever.
  group('a point that may group thousands', () {
    const qty = AmountShape(15, 3);
    const money = AmountShape(14, 2);
    const says = 'A point may group thousands here. '
        'Type the figure without grouping, with any decimals after a comma.';
    for (final locale in ['pl', 'en_ZA']) {
      test('in $locale, a non-native point before three digits is refused', () {
        for (final t in ['1.250', '12.500', '999.999', '1.000']) {
          expect(_typed(t, locale, qty), says, reason: t);
          expect(_typed(t, locale, money), says, reason: '$t, money');
          expect(_typed(t, locale, const AmountShape(4, 2, signed: true)), says, reason: '$t, signed');
        }
        // Its own comma, or a point that cannot be a group, reads as typed.
        expect(_typed('1,250', locale, qty), '1.25');
        expect(_typed('0.250', locale, qty), '0.25');
        expect(_typed('.250', locale, qty), '0.25');
        expect(_typed('1234.250', locale, qty), '1234.25');
        expect(_typed('1.25', locale, qty), '1.25');
        expect(_typed('1.2', locale, qty), '1.2');
        expect(_typed('01.250', locale, qty), '1.25');
        expect(_typed('-1.250', locale, const AmountShape(4, 3, signed: true)), says);
        expect(_typed('-1,250', locale, const AmountShape(4, 3, signed: true)), '-1.25');
      });
    }
    test('in English and Arabic the point is the decimal; in Romanian it groups', () {
      for (final locale in ['en', 'en_GB', 'ar']) {
        expect(_typed('1.250', locale, qty), '1.25', reason: locale);
        expect(_typed('1,250', locale, qty),
            'Type the amount without thousands separators. Decimals go after a point.',
            reason: locale);
      }
      expect(_typed('1٫250', 'ar', qty), '1.25');
      expect(_typed('1.250', 'ro', qty),
          'Type the amount without thousands separators. Decimals go after a comma.');
      expect(_typed('1,250', 'ro', qty), '1.25');
    });
  });

  // Every key typed, one at a time, as a field sees it: what each prefix reads
  // as, or why it is refused — in the fallback English and the shipped
  // languages whose marks differ.
  group('key by key', () {
    const qty = AmountShape(15, 3);
    final cases = <String, List<(String, Object?)>>{
      'ro': [('1', '1'), ('12', '12'), ('12,', '12'), ('12,5', '12.5'), ('12,50', '12.5')],
      'en_GB': [('1', '1'), ('12', '12'), ('12.', '12'), ('12.5', '12.5'), ('12.50', '12.5')],
      'en': [('1', '1'), ('1.', '1'), ('1.2', '1.2'), ('1.25', '1.25'), ('1.250', '1.25')],
      'pl': [('1', '1'), ('1.', '1'), ('1.2', '1.2'), ('1.25', '1.25'), ('1.250', startsWith('A point may group thousands'))],
      'ar': [('1', '1'), ('1٫', '1'), ('1٫2', '1.2'), ('1٫25', '1.25'), ('1٫250', '1.25')],
    };
    cases.forEach((locale, steps) {
      test('$locale reads each key as typed', () {
        for (final (text, expected) in steps) {
          expect(_typed(text, locale, qty), expected, reason: '$locale "$text"');
        }
      });
    });
    test('pl refuses the point once three digits follow it', () {
      expect(AmountShape.quantity.read('1.250', AmountMarks.ofApp(locale: 'pl')), isNull);
    });
  });

  test('money takes its currency\'s minor units; a quantity three places', () {
    expect(AmountShape.money('KWD').decimals, 3);
    expect(AmountShape.money('JPY').decimals, 0);
    expect(AmountShape.money('GBP').decimals, 2);
    expect(AmountShape.money(null).decimals, 2);
    expect(AmountShape.money('').decimals, 2);
    expect(AmountShape.quantity.decimals, 3);
    final ro = AmountMarks.ofApp(locale: 'ro');
    expect(AmountShape.money('JPY').refusal('1500,5', ro), 'Whole amounts only.');
    expect(AmountShape.money('KWD').read('1,234', ro), '1.234');
  });

  // A field filled with a figure it holds writes it at the places it is kept
  // in, the way the language writes a number, and never rounds one finer away.
  test('a figure is written at its places, never rounded away', () {
    final ro = AmountMarks.ofApp(locale: 'ro');
    final en = AmountMarks.ofApp(locale: 'en_GB');
    expect(ro.writeAt(1.234, 3), '1,234');
    expect(en.writeAt(1.234, 3), '1.234');
    expect(en.writeAt(1500, 0), '1500');
    expect(en.writeAt(9, 2), '9.00');
    expect(ro.writeAt(9, 2), '9,00');
    expect(en.writeAt(0.035, 2), '0.035', reason: 'finer than the places: kept, so it is refused, not rounded');
    expect(en.writeAt(12.5, 0), '12.5');
    expect(AmountShape.money('KWD').read(ro.writeAt(1.234, 3), ro), '1.234');
  });
}
