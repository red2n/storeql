import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Every number and date the app shows is written through AppFormat
/// (lib/core/format.dart), in the app's locale.
///
/// A NumberFormat or DateFormat built anywhere else is how a screen came to
/// print `GBP 1.35` or `Sep 11, 2026`: made without a locale, intl asks
/// `Intl.getCurrentLocale()`, which pins the whole app's default locale to the
/// system's (en_US) as a side effect, and every later AppFormat date changes
/// form with it.
void main() {
  test('no screen builds its own NumberFormat or DateFormat', () {
    final formatter = RegExp(r'\b(NumberFormat|DateFormat)\b');
    final offenders = <String>[];
    for (final entity in Directory('lib').listSync(recursive: true)) {
      if (entity is! File || !entity.path.endsWith('.dart')) continue;
      final path = entity.path.replaceAll(r'\', '/');
      if (path == 'lib/core/format.dart' || path.startsWith('lib/l10n/gen/')) continue;
      final lines = entity.readAsLinesSync();
      for (var i = 0; i < lines.length; i++) {
        final line = lines[i].trimLeft();
        if (line.startsWith('//')) continue;
        if (formatter.hasMatch(line)) offenders.add('$path:${i + 1}: ${line.trim()}');
      }
    }
    expect(offenders, isEmpty,
        reason: 'format through AppFormat (money, count, date, dateOf, dateTime, time) instead');
  });
}
