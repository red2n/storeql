import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/l10n/app_locales.dart';

// ---------------------------------------------------------------------------
// StoreQL is multi-tenant and multi-location: a business, and its shoppers,
// may be anywhere. AppLocales.resolve must never presume one country's
// English (or its date/number conventions) for everyone — it resolves the
// device's own region when this app has data for it, plain English
// otherwise, and one of the app's other shipped languages whatever region it
// is reported in.
// ---------------------------------------------------------------------------

void main() {
  group('English resolves to the device\'s own region when intl has it', () {
    test('en_IN — an Indian device keeps its own region, not the UK\'s', () {
      expect(
        AppLocales.resolve(const Locale('en', 'IN'), AppLocales.supported),
        const Locale('en', 'IN'),
      );
    });

    test('en_US — an American device keeps its own region', () {
      expect(
        AppLocales.resolve(const Locale('en', 'US'), AppLocales.supported),
        const Locale('en', 'US'),
      );
    });

    test('every named region resolves to itself', () {
      for (final region in AppLocales.englishRegions) {
        expect(AppLocales.resolve(region, AppLocales.supported), region);
      }
    });
  });

  group('anything else, or nothing, is plain English — no country assumed', () {
    test('de_DE — a language this app has not shipped, plain English', () {
      expect(
        AppLocales.resolve(const Locale('de', 'DE'), AppLocales.supported),
        const Locale('en'),
      );
    });

    test(
      'English in a region this app has no data for is still plain English, not a guess',
      () {
        expect(
          AppLocales.resolve(const Locale('en', 'DE'), AppLocales.supported),
          const Locale('en'),
        );
        expect(
          AppLocales.resolve(const Locale('en', 'FR'), AppLocales.supported),
          const Locale('en'),
        );
      },
    );

    test('no locale at all', () {
      expect(
        AppLocales.resolve(null, AppLocales.supported),
        const Locale('en'),
      );
    });

    test('the fallback itself names no country', () {
      expect(AppLocales.fallback, const Locale('en'));
      expect(AppLocales.fallback.countryCode, isNull);
    });
  });

  group('a device already in one of the app\'s other shipped languages keeps it', () {
    test('pl_PL — Polish, whatever its region', () {
      expect(
        AppLocales.resolve(const Locale('pl', 'PL'), AppLocales.supported),
        const Locale('pl'),
      );
    });

    test(
      'every shipped language resolves to itself, region-less, whatever region is reported',
      () {
        const shipped = ['pl', 'ro', 'pa', 'ur', 'bn', 'gu', 'ar'];
        for (final code in shipped) {
          expect(
            AppLocales.resolve(Locale(code, 'ZZ'), AppLocales.supported),
            Locale(code),
            reason:
                '$code keeps its language whatever region a device happens to report',
          );
        }
      },
    );

    test(
      'Urdu and Arabic — the app\'s right-to-left languages — are unaffected by the English-region change',
      () {
        expect(
          AppLocales.resolve(const Locale('ur', 'PK'), AppLocales.supported),
          const Locale('ur'),
        );
        expect(
          AppLocales.resolve(const Locale('ar', 'EG'), AppLocales.supported),
          const Locale('ar'),
        );
      },
    );
  });

  test(
    'every English region and the plain fallback are all declared supported',
    () {
      expect(AppLocales.supported, contains(AppLocales.fallback));
      for (final region in AppLocales.englishRegions) {
        expect(AppLocales.supported, contains(region));
      }
    },
  );

  group('AppLocales.intlName', () {
    test('names a region with its underscore form', () {
      expect(AppLocales.intlName(const Locale('en', 'IN')), 'en_IN');
      expect(AppLocales.intlName(const Locale('en', 'GB')), 'en_GB');
    });

    test('names a region-less locale by its language alone', () {
      expect(AppLocales.intlName(const Locale('en')), 'en');
      expect(AppLocales.intlName(const Locale('pl')), 'pl');
    });
  });
}
