import 'format.dart';

/// Reading an amount a person types: the marks the app's language writes a
/// number with ([AmountMarks]), the digits and places a request allows
/// ([AmountShape]), and the plain decimal sent ([plainDecimal]).
///
/// Nothing typed is ever dropped. A field built on this keeps every key where
/// it was typed and, while any of it cannot be read, says why under it and
/// sends nothing. A key dropped unsaid let the keys after it be taken, so the
/// figure sent was not the one typed: 12.50 went as 1250 in Romanian, 12.505 as
/// 12.50, -3 as 3, and 12٫50 as 1250.

/// How the app's language writes an amount, so a field reads one the way the
/// app writes it back (`12,50 zł` in Polish). It reads the language's own
/// decimal mark, the other of `.` and `,` as well unless the language groups
/// thousands with it, and Arabic's decimal separator (U+066B), which is never
/// anything else. A grouping mark or a space is refused in words. It is never
/// read as a decimal and never dropped: in English `1,250` is a thousand and
/// more, in Polish one and a quarter.
class AmountMarks {
  /// The language's own decimal mark: the one the field's hint shows.
  final String decimal;

  /// Every mark read as the decimal point.
  final Set<String> decimals;

  /// What groups thousands, here or anywhere: refused in words.
  final Set<String> grouping;

  /// A point or comma read as the decimal that is not the language's own:
  /// the point in Polish and South African English, which group with a
  /// space. Before exactly three digits it may be a thousands group to the
  /// person typing (1.250), so it is refused there in words, never read.
  final Set<String> ambiguous;

  /// A sign typed before or in an amount: refused in words, since the action
  /// says which way money goes. A [AmountShape.signed] figure (a temperature)
  /// reads one before its digits.
  static const signs = {'-', '+', '\u2212'};

  /// Every mark the field knows: what it reads as the decimal point and what
  /// it refuses as a thousands separator.
  Set<String> get all => {...decimals, ...grouping};

  const AmountMarks._(this.decimal, this.decimals, this.grouping, this.ambiguous);

  /// The marks of [locale], the app's own language when none is named.
  factory AmountMarks.ofApp({String? locale}) {
    final m = AppFormat.numberMarks(locale: locale);
    final decimals = {
      m.decimal,
      ...{'.', ','}.where((c) => c != m.group),
      '\u066B',
    };
    return AmountMarks._(
        m.decimal,
        decimals,
        {
          m.group, '.', ',', "'", '\u2019', '\u066C',
          // Spaces: plain, no-break, narrow no-break, thin and figure.
          ' ', '\u00A0', '\u202F', '\u2009', '\u2007',
        }.difference(decimals),
        {'.', ','}.intersection(decimals).difference({m.decimal}));
  }

  /// The decimal mark in words.
  String get name => _named(decimal);

  /// [mark] in words.
  static String _named(String mark) =>
      switch (mark) { ',' => 'comma', '.' => 'point', _ => 'mark' };

  /// The field's hint: nought written to [places] in this language.
  String hint(int places) => places > 0 ? '0$decimal${'0' * places}' : '0';

  /// [plain], a decimal written with a point (`12.5`), written the way this
  /// language writes it (`12,5`), so a field can be filled with a figure it
  /// already holds and read it back unchanged.
  String write(String plain) => plain.replaceAll('.', decimal);

  /// [value] written to [places] the way this language writes a number, so a
  /// field filled with a figure it holds reads it back unchanged: a Kuwaiti
  /// dinar's 1.234 at its three places, never 1.23; a yen's 1500 with none.
  /// One finer than [places] keeps the places it has (up to six) and is never
  /// rounded away: the field refuses it in words rather than saving another
  /// figure.
  String writeAt(num value, int places) {
    var fixed = value.toStringAsFixed(places);
    if (num.parse(fixed) != value) {
      fixed = value.toStringAsFixed(6).replaceFirst(RegExp(r'\.?0+$'), '');
    }
    return write(fixed);
  }
}

/// How many whole digits and decimals an amount may have: the receiving
/// request's own limits, so a field never refuses what the server takes.
class AmountShape {
  final int whole;
  final int decimals;

  /// Whether the figure may be below nought: a temperature, never money. One
  /// sign may lead it — a hyphen, the true minus (U+2212) a phone's symbols
  /// offer, or a plus — and a sign anywhere else is refused in words. Never
  /// dropped: a freezer's −18 recorded as 18 is a pass that was a failure.
  final bool signed;

  const AmountShape(this.whole, this.decimals, {this.signed = false});

  /// Money in [currency]: [whole] digits and the currency's own minor units
  /// ([AppFormat.minorUnits]) — three for the Kuwaiti dinar, none for the
  /// yen, two without a currency — the places every service keeps it at.
  factory AmountShape.money(String? currency, {int whole = 14}) =>
      AmountShape(whole, AppFormat.minorUnits(currency));

  /// A quantity of stock: three places, as every service keeps one
  /// (NUMERIC(18,3)), and fifteen whole digits.
  static const quantity = AmountShape(15, 3);

  /// Why [text] may not be sent, in words, or null when it may: nothing yet,
  /// or digits within [whole] and, where the amount has them, one of [marks]'
  /// decimal marks and up to [decimals] after it. Text that is not blank but
  /// holds no digit — a mark or a sign alone — is refused, never taken as
  /// blank: a field that sends nothing for blank lets the server apply its
  /// default (a debit note's whole gross credited), which nobody typed.
  String? refusal(String text, AmountMarks marks) {
    final chars = _unsigned(text.split(''));
    if (chars.any(AmountMarks.signs.contains)) {
      return signed ? 'Only one sign, before the digits.' : 'Type the amount without a sign.';
    }
    final misplaced = _markRefusal(chars, marks);
    if (misplaced != null) return misplaced;
    if (chars.any((c) => !isAsciiDigit(c) && !marks.all.contains(c))) {
      return decimals > 0 ? 'Only digits and a decimal ${marks.name}.' : 'Only digits.';
    }
    final at = chars.indexWhere(marks.decimals.contains);
    final ambiguous = _ambiguity(chars, at, marks);
    if (ambiguous != null) return ambiguous;
    if (at >= 0 && chars.length - at - 1 > decimals) {
      return decimals == 1
          ? 'At most 1 decimal place.'
          : 'At most $decimals decimal places.';
    }
    if ((at < 0 ? chars.length : at) > whole) {
      return decimals > 0
          ? 'At most $whole digits before the decimals.'
          : 'At most $whole digits.';
    }
    if (text.isNotEmpty && !chars.any(isAsciiDigit)) {
      return chars.length < text.length
          ? 'Type the digits after the sign.'
          : 'Type the amount in digits.';
    }
    return null;
  }

  /// Why the decimal mark at [at] in [chars] cannot be read, or null: a mark
  /// not the language's own ([AmountMarks.ambiguous]) after one to three
  /// digits that could lead a group (not nought) and before exactly three is
  /// a thousands group to many — 1.250 in Polish is twelve hundred and fifty
  /// to some and one and a quarter to others — so it is refused, never read.
  String? _ambiguity(List<String> chars, int at, AmountMarks marks) {
    if (at < 0 || decimals <= 0 || !marks.ambiguous.contains(chars[at])) return null;
    final before = chars.sublist(0, at).join();
    final after = chars.sublist(at + 1).join();
    if (!RegExp(r'^[1-9][0-9]{0,2}$').hasMatch(before) ||
        !RegExp(r'^[0-9]{3}$').hasMatch(after)) {
      return null;
    }
    return 'A ${AmountMarks._named(chars[at])} may group thousands here. '
        'Type the figure without grouping, with any decimals after a ${marks.name}.';
  }

  /// Why a mark in [chars] cannot be read, or null when every one can: a
  /// thousands separator (or a space), a decimal mark where the amount has no
  /// decimals, a second decimal mark.
  String? _markRefusal(List<String> chars, AmountMarks marks) {
    if (chars.any(marks.grouping.contains)) {
      return decimals > 0
          ? 'Type the amount without thousands separators. '
              'Decimals go after a ${marks.name}.'
          : 'Type the amount without thousands separators.';
    }
    final points = chars.where(marks.decimals.contains).length;
    if (points > 0 && decimals <= 0) return 'Whole amounts only.';
    if (points > 1) return 'Only one decimal ${marks.name}.';
    return null;
  }

  /// [text] as the plain decimal it is ([plainDecimal]), led by a minus where
  /// a [signed] amount is below nought, or null while it is refused
  /// ([refusal]) or holds no figure.
  String? read(String text, AmountMarks marks) {
    if (refusal(text, marks) != null) return null;
    final chars = text.split('');
    final unsigned = _unsigned(chars);
    final plain = plainDecimal(unsigned.join(), marks);
    final negative = unsigned.length < chars.length && chars.first != '+';
    return plain != null && negative && plain != '0' ? '-$plain' : plain;
  }

  /// [chars] without the one sign a [signed] amount may lead with.
  List<String> _unsigned(List<String> chars) =>
      signed && chars.isNotEmpty && AmountMarks.signs.contains(chars.first)
          ? chars.sublist(1)
          : chars;

  /// Whether [c] is one of the digits 0 to 9.
  static bool isAsciiDigit(String c) =>
      c.length == 1 && c.codeUnitAt(0) >= 0x30 && c.codeUnitAt(0) <= 0x39;
}

/// [text], typed with [marks], as the decimal it is, written once with a
/// point: no leading zeros, no trailing fractional ones, no bare point.
/// `007.50` is `7.5`, `.5` is `0.5`, `12.` is `12`, `0.00` is `0`, and in
/// Polish `12,50` is `12.5`. So `5`, `5.00` and `05` are one figure. Null when
/// it is not a plain decimal: anything but digits and one decimal mark, or no
/// digit at all.
String? plainDecimal(String text, AmountMarks marks) {
  // Only digits and the language's decimal marks: a thousands separator
  // (Romanian's point, English's comma) is never read as a point, nor dropped.
  final chars = text.trim().split('');
  if (chars.any((c) => !AmountShape.isAsciiDigit(c) && !marks.decimals.contains(c))) {
    return null;
  }
  final typed = chars.map((c) => marks.decimals.contains(c) ? '.' : c).join();
  final m = RegExp(r'^(\d*)(?:\.(\d*))?$').firstMatch(typed);
  if (m == null) return null;
  if (m.group(1)!.isEmpty && (m.group(2) ?? '').isEmpty) return null;
  final whole = m.group(1)!.replaceFirst(RegExp(r'^0+'), '');
  final fraction = (m.group(2) ?? '').replaceFirst(RegExp(r'0+$'), '');
  final w = whole.isEmpty ? '0' : whole;
  return fraction.isEmpty ? w : '$w.$fraction';
}
