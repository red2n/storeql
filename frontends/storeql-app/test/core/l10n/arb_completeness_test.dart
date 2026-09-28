import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart' show setEquals;
import 'package:flutter_test/flutter_test.dart';

// ---------------------------------------------------------------------------
// lib/l10n/app_en.arb (l10n.yaml's template-arb-file) is the one place every
// string the sign-in card, the forgot-password page and the reset page can
// show is declared. Every other lib/l10n/app_*.arb is a translation of it —
// and flutter_gen-l10n is quiet about a language that falls short: a key a
// locale doesn't have simply falls back to the template's English at
// runtime, mid-sentence, in an otherwise-translated card. That is exactly
// the bug this guards: it fails the build the moment a language is missing
// a key the template has, or answers with different placeholders than the
// template declares for that key ({min}, {max}, {business}, …) — rather than
// letting a shopper or a member of staff discover it as English words
// sitting inside a translated screen.
// ---------------------------------------------------------------------------

const _l10nDir = 'lib/l10n';
const _templateFile = 'app_en.arb';

/// Every `lib/l10n/app_*.arb` file, by its own file name (`app_en.arb`,
/// `app_pl.arb`, …) — the generated `lib/l10n/gen/` package is a directory,
/// never a `.arb` file, so a plain top-level listing already excludes it.
Map<String, Map<String, dynamic>> _loadArbFiles() {
  final files = <String, Map<String, dynamic>>{};
  for (final entity in Directory(_l10nDir).listSync()) {
    if (entity is! File) continue;
    final name = entity.path.replaceAll('\\', '/').split('/').last;
    if (!name.startsWith('app_') || !name.endsWith('.arb')) continue;
    final json = jsonDecode(entity.readAsStringSync()) as Map<String, dynamic>;
    files[name] = json;
  }
  return files;
}

/// The message keys of an ARB map: every entry except `@@locale` and a
/// message's own `@key` metadata block:
/// https://github.com/flutter/flutter/wiki/Application-Localizations —
/// metadata describes a message, it is never itself shown on a screen.
Set<String> _messageKeys(Map<String, dynamic> arb) =>
    arb.keys.where((k) => !k.startsWith('@')).toSet();

/// The `{placeholder}` names an ICU-free ARB message contains, e.g.
/// `fieldPasswordTooShort`'s `{min}` or `continueWithBusiness`'s
/// `{business}` — every one of these must survive translation exactly, or
/// flutter_gen-l10n's generated call site (`l.fieldPasswordTooShort(min)`)
/// has nothing to substitute into that language's string.
final _placeholder = RegExp(r'\{(\w+)\}');
Set<String> _placeholdersOf(String message) =>
    _placeholder.allMatches(message).map((m) => m.group(1)!).toSet();

void main() {
  final arbFiles = _loadArbFiles();
  final template = arbFiles[_templateFile];

  if (template == null) {
    test('the template itself is present and not empty', () {
      fail("$_l10nDir/$_templateFile must exist — it is l10n.yaml's template-arb-file");
    });
    return;
  }

  test('the template itself is present and not empty', () {
    expect(_messageKeys(template), isNotEmpty);
  });

  final templateKeys = _messageKeys(template);
  final locales = arbFiles.keys.where((name) => name != _templateFile).toList()..sort();

  // A future ARB with no companion translation would otherwise pass this
  // file silently — there must be at least the languages this suite already
  // knows about (English's template aside) for the checks below to mean
  // anything.
  test('more than one language is shipped', () {
    expect(locales, isNotEmpty);
  });

  for (final locale in locales) {
    final arb = arbFiles[locale]!;
    final keys = _messageKeys(arb);

    test('$locale has every key $_templateFile has', () {
      final missing = templateKeys.difference(keys).toList()..sort();
      expect(
        missing,
        isEmpty,
        reason: '$locale is missing ${missing.length} key(s) the template declares: $missing\n'
            'A missing key falls back to English at runtime — mixing languages in one card.',
      );
    });

    test('$locale has no key $_templateFile does not declare', () {
      final extra = keys.difference(templateKeys).toList()..sort();
      expect(
        extra,
        isEmpty,
        reason: '$locale declares ${extra.length} key(s) $_templateFile does not: $extra\n'
            'A key the template never declares is never read by AppLocalizations — likely a typo of a real key.',
      );
    });

    test('$locale\'s placeholders match the template\'s, key by key', () {
      final mismatches = <String>[];
      for (final key in templateKeys.intersection(keys)) {
        final templateValue = template[key];
        final localeValue = arb[key];
        if (templateValue is! String || localeValue is! String) continue;
        final wanted = _placeholdersOf(templateValue);
        if (wanted.isEmpty) continue;
        final got = _placeholdersOf(localeValue);
        if (!setEquals(wanted, got)) {
          mismatches.add('$key: template wants $wanted, $locale has $got');
        }
      }
      expect(
        mismatches,
        isEmpty,
        reason: '$locale has a placeholder mismatch flutter_gen-l10n cannot substitute into:\n'
            '${mismatches.join('\n')}',
      );
    });
  }
}
