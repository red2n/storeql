import 'dart:math' as math;

import 'package:intl/intl.dart';

import 'l10n/app_locales.dart';

/// Locale-aware formatting for money, counts and dates. Every number and date
/// the app shows goes through here (test/core/format_guard_test.dart): a
/// NumberFormat or DateFormat made anywhere else without a locale asks
/// `Intl.getCurrentLocale()`, which pins the whole app's default locale to the
/// system's (en_US) as a side effect, and every later date changes form.
///
/// There is no default currency (SJ-D53). A constant pound-sterling code here
/// once turned any amount whose currency was unknown into pounds, on a platform
/// whose tenants trade in yen, rupees and dinars. An amount with no currency is
/// formatted as a plain number with two decimals, which is visibly incomplete
/// rather than quietly wrong. The locale is the one the app is running in
/// (main.dart sets it from the resolved app locale) unless a caller passes
/// another.
class AppFormat {
  AppFormat._();

  /// The locale the app runs in, or the app's own UI fallback when none is set
  /// (a plain unit test, a background isolate). Read without setting it.
  static String get locale =>
      Intl.defaultLocale ?? AppLocales.intlName(AppLocales.fallback);

  /// Money in the given ISO-4217 [currencyCode], formatted for [locale], with
  /// the right symbol, grouping and minor units, e.g. `£1,234.50` or `¥3,702`.
  /// Without a currency, the amount alone: `1,234.50`.
  ///
  /// With [maxDecimals], an amount finer than the currency's minor unit keeps
  /// the places it has, up to that many: a unit price set to four places
  /// (`0.035` a text) reads `£0.035`, never rounded to a price nobody set. An
  /// amount at the minor unit is exactly the plain money (`£11.70`).
  static String money(
    num amount, {
    String? currencyCode,
    String? locale,
    int? maxDecimals,
  }) {
    final code = currencyCode?.trim() ?? '';
    final format = code.isEmpty
        ? NumberFormat.decimalPatternDigits(
            locale: locale ?? AppFormat.locale,
            decimalDigits: 2,
          )
        : NumberFormat.simpleCurrency(
            locale: locale ?? AppFormat.locale,
            name: code,
          );
    final minor = format.decimalDigits ?? 2;
    if (maxDecimals != null &&
        maxDecimals > minor &&
        !_fitsPlaces(amount, minor)) {
      format
        ..minimumFractionDigits = minor
        ..maximumFractionDigits = maxDecimals;
    }
    return format.format(amount);
  }

  /// Whether [amount] has nothing past [places] decimals. Compared with a
  /// tolerance, because 0.07 × 100 is not quite 7 in floating point.
  static bool _fitsPlaces(num amount, int places) {
    final scaled = amount * math.pow(10, places);
    return (scaled - scaled.round()).abs() < 1e-9;
  }

  /// The symbol a currency is written with in [locale] (`£`, `¥`, `₹`), or the
  /// code itself when the locale has none; empty for no currency.
  static String currencySymbol(String? currencyCode, {String? locale}) {
    final code = currencyCode?.trim() ?? '';
    if (code.isEmpty) return '';
    return NumberFormat.simpleCurrency(
      locale: locale ?? AppFormat.locale,
      name: code,
    ).currencySymbol;
  }

  /// A count or a quantity as people say it: grouped for the locale
  /// (`138,469`), a ledger's trailing zeros dropped (`52`, not `52.000`), and
  /// a part unit keeping its fraction (`2.5`).
  static String count(num n, {String? locale}) =>
      NumberFormat.decimalPattern(locale ?? AppFormat.locale).format(n);

  /// A locale-formatted date, e.g. `23 Jun 2026`. Accepts an ISO-8601 string;
  /// returns the input unchanged if it can't be parsed, or '' when null/blank.
  static String date(String? iso, {String? locale}) {
    final dt = _parse(iso);
    if (dt == null) return iso ?? '';
    return dateOf(dt, locale: locale);
  }

  /// A date already in hand, written as [date] writes one: `1 Oct 2026`. The
  /// [dt] is taken as it is (no conversion to local time).
  static String dateOf(DateTime dt, {String? locale}) =>
      DateFormat.yMMMd(locale ?? AppFormat.locale).format(dt);

  /// A short weekday and date with no year, in the reader's own order: `Sun 27
  /// Sept` in en_GB, `Sun, Sep 27` in en_US, `niedz., 27 wrz` in Polish — a
  /// window or slot read at a glance. [dt] is taken as it is (no conversion to
  /// local time): a store's own calendar day stays its own.
  static String weekdayDate(DateTime dt, {String? locale}) =>
      DateFormat.MMMEd(locale ?? AppFormat.locale).format(dt);

  /// A locale-formatted date + time, e.g. `23 Jun 2026 14:05` (en_GB).
  static String dateTime(String? iso, {String? locale}) {
    final dt = _parse(iso);
    if (dt == null) return iso ?? '';
    return DateFormat.yMMMd(locale ?? AppFormat.locale).add_Hm().format(dt);
  }

  /// A time of day on the 24-hour clock, e.g. `14:05`; the input unchanged if
  /// it can't be parsed, or '' when null/blank.
  static String time(String? iso, {String? locale}) {
    final dt = _parse(iso);
    if (dt == null) return iso ?? '';
    return DateFormat.Hm(locale ?? AppFormat.locale).format(dt);
  }

  static DateTime? _parse(String? iso) {
    if (iso == null || iso.isEmpty) return null;
    return DateTime.tryParse(iso)?.toLocal();
  }
}
