import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/l10n/gen/app_localizations.dart';

Future<AppLocalizations> _localizationsFor(WidgetTester tester, Locale locale) async {
  late AppLocalizations l;
  await tester.pumpWidget(MaterialApp(
    locale: locale,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: Builder(builder: (context) {
      l = AppLocalizations.of(context);
      return const SizedBox.shrink();
    }),
  ));
  return l;
}

void main() {
  testWidgets('English locale resolves the base strings', (tester) async {
    final l = await _localizationsFor(tester, const Locale('en'));
    expect(l.actionSignIn, 'Sign in');
  });

  testWidgets('Polish locale resolves translated strings', (tester) async {
    final l = await _localizationsFor(tester, const Locale('pl'));
    expect(l.actionSignIn, 'Zaloguj się');
  });

  testWidgets('every shipped language carries the sign-in, Romanian too',
      (tester) async {
    // Romanian was a stub that fell back to English; every shipped ARB is now
    // complete (test/core/l10n/arb_completeness_test.dart keeps it so). A
    // language the app does not ship falls back to plain English in
    // AppLocales.resolve (test/core/l10n/app_locales_test.dart).
    final l = await _localizationsFor(tester, const Locale('ro'));
    expect(l.actionSignIn, 'Conectează-te');
  });
}
