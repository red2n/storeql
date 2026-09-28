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
    });
  });
}
