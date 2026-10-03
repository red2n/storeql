import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/reference/iso_reference.dart';
import 'package:storeql_app/shared/widgets/reference_fields.dart';

// ---------------------------------------------------------------------------
// The reference fields replace per-screen literal lists that each started on a
// country or currency nobody chose (SJ-D53). They start empty unless given a
// value and refuse to submit empty. A currency or zone the list does not carry
// is kept; a country the list does not carry is one no service takes, so a real
// one is asked for — unless the field is switched off. A country only ever
// suggests a currency.
// ---------------------------------------------------------------------------

Future<GlobalKey<FormState>> _pump(WidgetTester tester, Widget field) async {
  final key = GlobalKey<FormState>();
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: Form(key: key, child: field),
      ),
    ),
  );
  return key;
}

void main() {
  testWidgets('an empty currency, country or zone is refused, not defaulted', (
    tester,
  ) async {
    for (final field in <Widget>[
      CurrencyField(value: null, onChanged: (_) {}),
      CountryField(value: null, onChanged: (_) {}),
      TimezoneField(value: null, onChanged: (_) {}),
    ]) {
      final key = await _pump(tester, field);
      expect(key.currentState!.validate(), isFalse);
      await tester.pump();
    }
    expect(find.text('Choose a time zone'), findsOneWidget);
  });

  testWidgets(
    'a value the list does not carry is kept, so an edit never changes it',
    (tester) async {
      final key = await _pump(
        tester,
        CurrencyField(value: 'XTS', onChanged: (_) {}),
      );
      expect(key.currentState!.validate(), isTrue);
      expect(find.text('XTS'), findsOneWidget);
      await _pump(
        tester,
        TimezoneField(value: 'Pacific/Chatham', onChanged: (_) {}),
      );
      expect(find.text('Pacific/Chatham'), findsOneWidget);
    },
  );

  testWidgets('a yen tenant opens in yen', (tester) async {
    await _pump(tester, CurrencyField(value: 'JPY', onChanged: (_) {}));
    expect(find.text('JPY — Japanese Yen'), findsOneWidget);
    await _pump(tester, CountryField(value: 'JP', onChanged: (_) {}));
    expect(find.text('Japan (JP)'), findsOneWidget);
  });

  test('a country suggests the currency it trades in, and nothing else', () {
    expect(currencyOfCountry('JP'), 'JPY');
    expect(currencyOfCountry('KW'), 'KWD');
    expect(currencyOfCountry('DE'), 'EUR');
    expect(currencyOfCountry(null), isNull);
    expect(currencyOfCountry('ZZ'), isNull);
    expect(currencyOfCountry('GE'), 'GEL');
    expect(currencyOfCountry('MN'), 'MNT');
    // A territory with no currency of its own suggests none.
    expect(currencyOfCountry('AQ'), isNull);
    for (final e in isoCountries.entries) {
      final currency = e.value.$2;
      if (currency == null) continue;
      expect(isoCurrencies.containsKey(currency), isTrue, reason: e.key);
    }
  });

  testWidgets('every country is offered, in the order people read names', (
    tester,
  ) async {
    await _pump(tester, CountryField(value: null, onChanged: (_) {}));
    final field = tester.widget<DropdownButtonFormField<String>>(
      find.byType(DropdownButtonFormField<String>),
    );
    final dropdown = tester.widget<DropdownButton<String>>(
      find.descendant(
        of: find.byWidget(field),
        matching: find.byType(DropdownButton<String>),
      ),
    );
    final codes = [for (final i in dropdown.items!) i.value!];
    expect(codes, hasLength(isoCountries.length));
    expect(codes, containsAll(['GE', 'IS', 'MN']));
    // By name, not by code: Georgia (GE) before Germany (DE), and a name that
    // starts with a letter carrying an accent sits with its plain letter.
    expect(codes.first, 'AF');
    expect(codes.indexOf('AX'), 1, reason: 'Åland Islands reads as Aland');
    expect(codes.indexOf('GE'), lessThan(codes.indexOf('DE')));
    expect(codes.last, 'ZW');
  });

  testWidgets(
    'a name with an accent anywhere sorts with its plain letters, lower case too',
    (tester) async {
      await _pump(tester, CountryField(value: null, onChanged: (_) {}));
      final dropdown = tester.widget<DropdownButton<String>>(
        find.byType(DropdownButton<String>),
      );
      final codes = [for (final i in dropdown.items!) i.value!];
      // Each accented name sits exactly between the two names its plain
      // spelling falls between: São Tomé and Príncipe reads as "Sao Tome",
      // never after Syria; Réunion, never after Rwanda; Türkiye, never after
      // Tuvalu; Côte d'Ivoire, never after Czechia; Curaçao, never after Cyprus.
      for (final (before, accented, after) in [
        ('SM', 'ST', 'SA'), // San Marino, São Tomé and Príncipe, Saudi Arabia
        ('QA', 'RE', 'RO'), // Qatar, Réunion, Romania
        ('TN', 'TR', 'TM'), // Tunisia, Türkiye, Turkmenistan
        ('CR', 'CI', 'HR'), // Costa Rica, Côte d'Ivoire, Croatia
        ('CU', 'CW', 'CY'), // Cuba, Curaçao, Cyprus
        ('AF', 'AX', 'AL'), // Afghanistan, Åland Islands, Albania
      ]) {
        expect(
          codes.indexOf(accented),
          codes.indexOf(before) + 1,
          reason: '${isoCountries[accented]!.$1} after ${isoCountries[before]!.$1}',
        );
        expect(
          codes.indexOf(after),
          codes.indexOf(accented) + 1,
          reason: '${isoCountries[accented]!.$1} before ${isoCountries[after]!.$1}',
        );
      }
      // Saint Barthélemy with the other Saints, ahead of Saint Helena.
      expect(codes.indexOf('BL'), lessThan(codes.indexOf('SH')));
    },
  );

  testWidgets(
    'a saved code no service takes is never kept or offered: the field asks for a country',
    (tester) async {
      for (final optional in [false, true]) {
        for (final held in ['UK', 'JX', 'United Kingdom']) {
          final key = await _pump(
            tester,
            CountryField(
              value: held,
              optional: optional,
              noneLabel: 'Not set',
              onChanged: (_) {},
            ),
          );
          final reason = '$held (optional: $optional)';
          expect(find.text(held), findsNothing, reason: reason);
          // Shown as needing a country before anything is pressed.
          expect(find.text('Choose a country'), findsOneWidget, reason: reason);
          expect(find.text('Not set'), findsNothing, reason: reason);
          expect(key.currentState!.validate(), isFalse, reason: reason);
          final dropdown = tester.widget<DropdownButton<String>>(
            find.byType(DropdownButton<String>),
          );
          expect(dropdown.value, isNull, reason: reason);
          expect(
            [for (final i in dropdown.items!) i.value],
            isNot(contains(held)),
            reason: reason,
          );
        }
      }
    },
  );

  testWidgets('a switched-off country field never holds the form back', (tester) async {
    // e.g. a customer no longer VAT-registered whose saved country is one no
    // service takes: nothing is sent for it, so nothing is asked for.
    final key = await _pump(
      tester,
      CountryField(value: 'UK', optional: true, enabled: false, onChanged: (_) {}),
    );
    expect(key.currentState!.validate(), isTrue);
    await tester.pump();
    expect(find.text('Choose a country'), findsNothing);
  });

  testWidgets('a saved code in lower case opens on its country', (tester) async {
    final key = await _pump(tester, CountryField(value: 'ge', onChanged: (_) {}));
    expect(find.text('Georgia (GE)'), findsOneWidget);
    expect(key.currentState!.validate(), isTrue);
  });

  testWidgets(
    'an optional country offers "none" and is never refused for being blank',
    (tester) async {
      String? chosen = 'unset';
      final key = await _pump(
        tester,
        CountryField(
          value: null,
          optional: true,
          noneLabel: "The business's own",
          onChanged: (v) => chosen = v,
        ),
      );
      expect(key.currentState!.validate(), isTrue);
      expect(find.text("The business's own"), findsOneWidget);
      await tester.tap(find.byType(DropdownButtonFormField<String>));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.text('Georgia (GE)'),
        400,
        scrollable: find.byType(Scrollable).last,
      );
      await tester.tap(find.text('Georgia (GE)').last);
      await tester.pumpAndSettle();
      expect(chosen, 'GE');
    },
  );
}
