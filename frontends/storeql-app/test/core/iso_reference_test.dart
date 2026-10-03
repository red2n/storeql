import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/reference/iso_reference.dart';

void main() {
  group('a country in words', () {
    test('its name, not its code', () {
      expect(countryName('FR'), 'France');
      expect(countryName('gb'), 'United Kingdom');
      // A code the list does not know yet stays as it came.
      expect(countryName('XQ'), 'XQ');
    });

    test('as it reads after "in": the names that take "the" get it', () {
      expect(countryInSentence('GB'), 'the United Kingdom');
      expect(countryInSentence('US'), 'the United States');
      expect(countryInSentence('NL'), 'the Netherlands');
      expect(countryInSentence('FR'), 'France');
      expect(countryInSentence('XQ'), 'XQ');
      // The names the full list added that read with "the".
      expect(countryInSentence('BS'), 'the Bahamas');
      expect(countryInSentence('KY'), 'the Cayman Islands');
      expect(countryInSentence('CF'), 'the Central African Republic');
      expect(countryInSentence('GE'), 'Georgia');
    });
  });

  group('every country there is (location-neutral)', () {
    // The services take every ISO 3166-1 alpha-2 code (tenant-svc and every
    // TenantProfiles caller check against the JDK's full list), so a list that
    // carries only some of them drops a real store's country on edit and leaves
    // a business with no way to choose its own.
    test('all 249 assigned ISO 3166-1 alpha-2 codes, each named', () {
      expect(isoCountries, hasLength(249));
      for (final code in ['GE', 'IS', 'MN', 'UY', 'CR', 'ET', 'RW', 'ZW', 'AQ', 'AX', 'BQ', 'SS', 'TL']) {
        expect(isoCountries.containsKey(code), isTrue, reason: code);
      }
      for (final e in isoCountries.entries) {
        expect(e.key, matches(RegExp(r'^[A-Z]{2}$')));
        expect(e.value.$1.trim(), isNotEmpty, reason: e.key);
        expect(e.value.$1, isNot(contains('&')), reason: 'names in words: ${e.key}');
      }
      expect(countryName('GE'), 'Georgia');
      expect(countryName('mn'), 'Mongolia');
    });

    test('a country code is exactly one on the list: what is not, the services refuse', () {
      expect(isCountryCode('GE'), isTrue);
      expect(isCountryCode('ge'), isTrue);
      expect(isCountryCode(' mn '), isTrue);
      // The list is the services' own set (the JDK's), so a code of the right
      // shape that it does not carry is one tenant-svc answers COUNTRY_INVALID
      // to: never a country to keep.
      expect(isCountryCode('JX'), isFalse);
      // Reserved, withdrawn or user-assigned, never a country.
      for (final code in ['UK', 'EU', 'UN', 'ZZ', 'XK', 'AA', 'QM', 'YU']) {
        expect(isCountryCode(code), isFalse, reason: code);
      }
      for (final code in ['', 'G', 'GEO', 'G1', 'United Kingdom']) {
        expect(isCountryCode(code), isFalse, reason: code);
      }
      for (final code in isoCountries.keys) {
        expect(isCountryCode(code), isTrue, reason: code);
      }
    });
  });
}
