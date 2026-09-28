import 'package:flutter/widgets.dart';
import 'package:intl/intl.dart';

/// Locales the app ships with.
///
/// StoreQL is multi-tenant and multi-location — a business, and its shoppers,
/// may be anywhere; nothing here assumes any one country. English resolves to
/// the device's own region when intl has it (numbers, dates and currency read
/// the way that region expects), and to plain, region-less English otherwise
/// — never one country's English as everyone else's default. Beyond English,
/// the app ships the largest non-English-speaking communities in the UK —
/// ranked by "main language other than English" in the 2021 England & Wales
/// census: Polish, Romanian, Punjabi, Urdu, Bengali, Gujarati, Arabic — kept
/// for whoever speaks them, wherever they are, not only in the UK.
///
/// Urdu and Arabic are right-to-left; Flutter mirrors the whole layout
/// automatically when one of those locales resolves. All eight ship with
/// `flutter_localizations`, so built-in Material/Cupertino widgets (date
/// pickers, dialogs, etc.) and number/date formatting are localized today;
/// app-specific strings are migrated to ARB separately (see AUDIT U6
/// follow-up).
class AppLocales {
  AppLocales._();

  /// English regions this app resolves to their own locale rather than
  /// collapsing to plain English, because a device reporting one of them
  /// means dates, numbers and currency should read the way that region
  /// expects. Not exhaustive of every English-speaking place — a region not
  /// named here still gets a working app, in plain English; none of these is
  /// treated as everyone else's default.
  static const List<Locale> englishRegions = [
    Locale('en', 'IN'),
    Locale('en', 'US'),
    Locale('en', 'AU'),
    Locale('en', 'ZA'),
    Locale('en', 'IE'),
    Locale('en', 'CA'),
    Locale('en', 'NZ'),
    Locale('en', 'SG'),
    Locale('en', 'GB'),
  ];

  /// Plain English: no region assumed. Default + resolution fallback (must
  /// stay first in [supported]) — a device in a language this app has not
  /// shipped gets this, not any one country's English.
  static const Locale fallback = Locale('en');

  static const List<Locale> supported = [
    fallback, // English — default and fallback
    ...englishRegions,
    Locale('pl'), // Polish — largest non-English main language in the UK
    Locale('ro'), // Romanian
    Locale('pa'), // Punjabi
    Locale('ur'), // Urdu (RTL)
    Locale('bn'), // Bengali
    Locale('gu'), // Gujarati
    Locale('ar'), // Arabic (RTL)
  ];

  /// The supported locale for a device's [locale]:
  /// - English in a region intl has ([englishRegions]): that region, so dates,
  ///   numbers and currency read the way it expects (`en_IN`, `en_US`, …);
  /// - English anywhere else, or no region at all: plain English ([fallback]);
  /// - one of the app's other shipped languages: kept, whatever its region;
  /// - anything else, or nothing: [fallback] — never one country's English
  ///   presumed for a language this app does not speak.
  static Locale resolve(Locale? locale, Iterable<Locale> supported) {
    if (locale == null) return fallback;
    if (locale.languageCode == 'en') {
      for (final region in englishRegions) {
        if (region.countryCode == locale.countryCode) return region;
      }
      return fallback;
    }
    for (final s in supported) {
      if (s.languageCode == locale.languageCode) return s;
    }
    return fallback;
  }

  /// The name intl knows [locale] by: `en_GB`, `pl`.
  static String intlName(Locale locale) =>
      Intl.canonicalizedLocale(locale.toLanguageTag());
}
