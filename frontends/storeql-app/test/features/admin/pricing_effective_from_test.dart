import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/admin/pricing_screen.dart';

// ---------------------------------------------------------------------------
// The VAT-rate dialog sent effectiveFrom as a bare '2026-01-01', which the API
// refuses with INVALID_DATE (the column is TIMESTAMPTZ), so no VAT rate could
// ever be saved from it. Both it and the price-list dialog now send the chosen
// day as a UTC instant, the start of that day, whole seconds.
// ---------------------------------------------------------------------------

void main() {
  test('the chosen day is sent as the start of that day in UTC', () {
    expect(effectiveFromInstant(DateTime(2026, 4, 1)), '2026-04-01T00:00:00Z');
  });

  test('the time of day a picker carries is dropped, never the day itself', () {
    expect(effectiveFromInstant(DateTime(2026, 12, 31, 23, 59, 58)), '2026-12-31T00:00:00Z');
  });

  test('it parses back to the same instant the API stores', () {
    final sent = effectiveFromInstant(DateTime(2027, 1, 15));
    expect(DateTime.parse(sent).isUtc, isTrue);
    expect(DateTime.parse(sent), DateTime.utc(2027, 1, 15));
  });
}
