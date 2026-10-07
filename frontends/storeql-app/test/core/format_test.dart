import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/core/l10n/app_locales.dart';

void main() {
  // intl locale data is initialized by flutter_localizations at runtime; load it
  // explicitly here so DateFormat(locale) works in a plain unit test.
  setUpAll(initializeDateFormatting);

  // Most of this file is about how AppFormat writes a GIVEN locale, not about
  // which locale the app defaults to (that is AppLocales' own job — see
  // test/core/l10n/app_locales_test.dart and the 'the app locale' group
  // below). Pin one explicitly so a change to the app's own fallback never
  // changes what "23 Jun 2026" means here; tests that are themselves about
  // the fallback set `Intl.defaultLocale` back to null and say so.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);

  group('AppFormat.money', () {
    test('formats GBP with the pound symbol, grouping and 2 decimals', () {
      final s = AppFormat.money(1234.5, currencyCode: 'GBP');
      expect(s, contains('£'));
      expect(s, contains('1,234.50'));
    });

    test('an amount with no currency is not turned into pounds (SJ-D53)', () {
      final s = AppFormat.money(1000);
      expect(s, '1,000.00');
      expect(s, isNot(contains('£')));
      expect(AppFormat.money(5, currencyCode: ''), '5.00');
      expect(AppFormat.money(5, currencyCode: '   '), '5.00');
    });

    test('each currency keeps its own symbol and minor units', () {
      expect(AppFormat.money(2.5, currencyCode: 'USD'), contains('2.50'));
      final yen = AppFormat.money(3702, currencyCode: 'JPY');
      expect(yen, contains('3,702'));
      expect(yen, isNot(contains('.00')));
      expect(AppFormat.money(1.234, currencyCode: 'KWD'), contains('1.234'));
    });

    test('the marks a language writes a number with, so a typed amount is read the same way', () {
      // Every language the app ships that writes a decimal comma, and one
      // that groups with a point; the app's own language when none is named.
      expect(AppFormat.numberMarks(locale: 'pl'), (decimal: ',', group: ' '));
      expect(AppFormat.numberMarks(locale: 'ro'), (decimal: ',', group: '.'));
      expect(AppFormat.numberMarks(locale: 'en_ZA'), (decimal: ',', group: ' '));
      expect(AppFormat.numberMarks(locale: 'en_US'), (decimal: '.', group: ','));
      expect(AppFormat.numberMarks(), (decimal: '.', group: ','));
      Intl.defaultLocale = 'pl';
      expect(AppFormat.numberMarks(), (decimal: ',', group: ' '));
    });

    test('the minor units a currency is paid in', () {
      expect(AppFormat.minorUnits('GBP'), 2);
      expect(AppFormat.minorUnits('JPY'), 0);
      expect(AppFormat.minorUnits('KWD'), 3);
      expect(AppFormat.minorUnits(' jpy '), 0);
      // No currency: two places, as money() writes it.
      expect(AppFormat.minorUnits(null), 2);
      expect(AppFormat.minorUnits(''), 2);
    });

    test('minor units are ISO 4217\'s, as every service rounds and stores '
        'money, not the CLDR display digits intl carries', () {
      // intl's CLDR table says 0 for these, where ISO 4217 and the services
      // (`Currency.getDefaultFractionDigits`, `Fx.minorUnits`) say 2: a store
      // in Serbia selling at 129.99 must be able to take 129.99.
      for (final code in [
        'RSD', 'HUF', 'PKR', 'IDR', 'COP', 'ALL', 'AFN', 'LAK', 'MMK', //
        'SYP', 'LBP', 'IRR', 'MGA', 'SOS', 'YER', 'KPW', 'SLL',
      ]) {
        expect(AppFormat.minorUnits(code), 2, reason: code);
      }
      // intl says 0 for the Iraqi dinar; ISO 4217 says 3.
      expect(AppFormat.minorUnits('IQD'), 3);
      // The rest of ISO's exceptions to two, as the JDK has them.
      for (final code in ['BHD', 'JOD', 'KWD', 'LYD', 'OMR', 'TND']) {
        expect(AppFormat.minorUnits(code), 3, reason: code);
      }
      for (final code in [
        'BIF', 'CLP', 'DJF', 'GNF', 'ISK', 'JPY', 'KMF', 'KRW', 'PYG', //
        'RWF', 'UGX', 'UYI', 'VND', 'VUV', 'XAF', 'XOF', 'XPF',
      ]) {
        expect(AppFormat.minorUnits(code), 0, reason: code);
      }
      expect(AppFormat.minorUnits('CLF'), 4);
      // A metal or fund with no minor unit (the JDK's -1) and a code nobody
      // issued are two, as `Fx.minorUnits` reads them.
      expect(AppFormat.minorUnits('XAU'), 2);
      expect(AppFormat.minorUnits('QQQ'), 2);
    });

    test('money is written to the same minor units the sale is charged in', () {
      expect(AppFormat.money(129.99, currencyCode: 'RSD'), contains('129.99'));
      expect(AppFormat.money(1299.5, currencyCode: 'HUF'), contains('1,299.50'));
      expect(AppFormat.money(1.25, currencyCode: 'IQD'), contains('1.250'));
      // Unchanged where the two tables agree.
      expect(AppFormat.money(3702, currencyCode: 'JPY'), isNot(contains('.')));
      expect(AppFormat.money(1.5, currencyCode: 'GBP'), contains('1.50'));
    });

    test('the currency symbol follows the code, and is empty without one', () {
      expect(AppFormat.currencySymbol('GBP'), '£');
      expect(AppFormat.currencySymbol('JPY'), contains('¥'));
      expect(AppFormat.currencySymbol(null), '');
      expect(AppFormat.currencySymbol(''), '');
    });
  });

  group('AppFormat.date', () {
    test('formats an ISO date the UK way (d MMM y)', () {
      expect(AppFormat.date('2026-06-23'), '23 Jun 2026');
    });

    test('returns the input unchanged when unparseable', () {
      expect(AppFormat.date('not-a-date'), 'not-a-date');
    });

    test('empty/null become empty string', () {
      expect(AppFormat.date(''), '');
      expect(AppFormat.date(null), '');
    });
  });

  group('AppFormat.weekdayDate', () {
    // A window read at a glance, in the reader's own order — never one
    // country's pattern for everyone.
    final sunday = DateTime(2026, 9, 27);
    test('British English puts the day first', () {
      expect(AppFormat.weekdayDate(sunday, locale: 'en_GB'), 'Sun 27 Sept');
    });
    test('American English puts the month first', () {
      expect(AppFormat.weekdayDate(sunday, locale: 'en_US'), 'Sun, Sep 27');
    });
    test('Polish in its own words', () {
      expect(AppFormat.weekdayDate(sunday, locale: 'pl'), contains('27'));
      expect(AppFormat.weekdayDate(sunday, locale: 'pl'), isNot(contains('Sun')));
    });
  });

  group('AppFormat.money with finer places', () {
    test('a unit price finer than the minor unit keeps the places it has, up to the cap', () {
      expect(AppFormat.money(0.035, currencyCode: 'GBP', maxDecimals: 4), '£0.035');
      expect(AppFormat.money(0.0125, currencyCode: 'GBP', maxDecimals: 4), '£0.0125');
      // Never more than the cap: the fifth place is rounded away.
      expect(AppFormat.money(0.01234, currencyCode: 'GBP', maxDecimals: 4), '£0.0123');
    });

    test('a price at the minor unit is exactly the plain money', () {
      expect(AppFormat.money(11.7, currencyCode: 'GBP', maxDecimals: 4), '£11.70');
      expect(AppFormat.money(0.07, currencyCode: 'GBP', maxDecimals: 4), '£0.07');
      expect(AppFormat.money(3702, currencyCode: 'JPY', maxDecimals: 4),
          AppFormat.money(3702, currencyCode: 'JPY'));
    });

    test('without a currency the amount alone keeps its places too', () {
      expect(AppFormat.money(0.035, maxDecimals: 4), '0.035');
      expect(AppFormat.money(5, maxDecimals: 4), '5.00');
    });
  });

  group('AppFormat.count', () {
    test('groups thousands and drops a ledger\'s trailing zeros', () {
      expect(AppFormat.count(138469), '138,469');
      expect(AppFormat.count(52.000), '52');
      expect(AppFormat.count(0), '0');
    });

    test('a part unit keeps its fraction', () {
      expect(AppFormat.count(2.5), '2.5');
    });
  });

  group('AppFormat.percent', () {
    test('a share of a whole, to one place at most', () {
      expect(AppFormat.percent(0.004), '0.4%');
      expect(AppFormat.percent(0.0123), '1.2%');
      expect(AppFormat.percent(0.05), '5%');
      expect(AppFormat.percent(0), '0%');
      expect(AppFormat.percent(1), '100%');
    });

    test('a finer share keeps the places asked for', () {
      expect(AppFormat.percent(0.00123, maxDecimals: 2), '0.12%');
      expect(AppFormat.percent(0.00004), '0%');
    });

    test('follows the locale, not the country', () {
      expect(AppFormat.percent(0.5, locale: 'de'), '50\u00A0%');
    });
  });

  group('AppFormat.time and dateOf', () {
    test('a time of day on the 24-hour clock', () {
      expect(AppFormat.time(DateTime(2026, 9, 23, 14, 5).toIso8601String()), '14:05');
      expect(AppFormat.time(null), '');
    });

    test('a time of day to the second', () {
      expect(AppFormat.timeSeconds(DateTime(2026, 9, 23, 14, 5, 9).toIso8601String()), '14:05:09');
      expect(AppFormat.timeSeconds(null), '');
    });

    test('a date already in hand is written as the date', () {
      expect(AppFormat.dateOf(DateTime(2026, 10, 1)), '1 Oct 2026');
      expect(AppFormat.dateOf(DateTime(2026, 10, 31, 23, 59)), '31 Oct 2026');
    });
  });

  group('the locale AppFormat writes in', () {
    test('is plain English when the app has set none — no country assumed', () {
      Intl.defaultLocale = null;
      expect(AppFormat.locale, 'en');
    });

    test('is the app\'s once the app has set it', () {
      Intl.defaultLocale = 'pl';
      expect(AppFormat.locale, 'pl');
    });

    test('formatting never pins the process to the system locale', () {
      Intl.defaultLocale = null;
      AppFormat.money(1, currencyCode: 'GBP');
      AppFormat.money(0.035, currencyCode: 'GBP', maxDecimals: 4);
      AppFormat.count(1200);
      AppFormat.date('2026-09-01');
      AppFormat.dateTime('2026-09-01T10:00:00Z');
      AppFormat.time('2026-09-01T10:00:00Z');
      AppFormat.dateOf(DateTime(2026, 9, 1));
      expect(Intl.defaultLocale, isNull);
      // Plain English (no country assumed) with nothing set: month first, and
      // "Sep" rather than "Sept" — the generic form, not the UK's.
      expect(AppFormat.date('2026-09-01'), 'Sep 1, 2026');
    });
  });

  group('the app locale', () {
    test('a device language the app speaks resolves to it, whatever its region', () {
      expect(AppLocales.resolve(const Locale('pl', 'PL'), AppLocales.supported), const Locale('pl'));
    });

    test('English resolves to the device\'s own region when intl has it, not the UK\'s', () {
      expect(AppLocales.resolve(const Locale('en', 'US'), AppLocales.supported), const Locale('en', 'US'));
      expect(AppLocales.resolve(const Locale('en', 'IN'), AppLocales.supported), const Locale('en', 'IN'));
      expect(AppLocales.resolve(const Locale('en', 'GB'), AppLocales.supported), const Locale('en', 'GB'));
    });

    test('anything else, or nothing, is plain English — no country assumed', () {
      expect(AppLocales.resolve(const Locale('fr'), AppLocales.supported), AppLocales.fallback);
      expect(AppLocales.resolve(const Locale('de', 'DE'), AppLocales.supported), AppLocales.fallback);
      expect(AppLocales.resolve(const Locale('en', 'DE'), AppLocales.supported), AppLocales.fallback,
          reason: 'English in a region intl has no data for this app ships — plain English, not a guess');
      expect(AppLocales.resolve(null, AppLocales.supported), AppLocales.fallback);
      expect(AppLocales.fallback, const Locale('en'));
    });

    test('is named as intl names it', () {
      expect(AppLocales.intlName(const Locale('en', 'GB')), 'en_GB');
      expect(AppLocales.intlName(const Locale('pl')), 'pl');
    });
  });
}
