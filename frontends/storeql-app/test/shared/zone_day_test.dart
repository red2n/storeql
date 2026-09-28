import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/reference/iso_reference.dart';
import 'package:storeql_app/shared/util/zone_day.dart';

// ---------------------------------------------------------------------------
// The start of a store's day, as a UTC instant, from its IANA time zone: the
// zones the store form offers, each with its daylight-saving rule. A zone the
// table does not know, or none, falls back to the device's own midnight —
// never UTC's.
// ---------------------------------------------------------------------------

String _start(String? zone, DateTime now) => startOfDayIn(zone, now: now).toIso8601String();

void main() {
  test('London: BST in summer, GMT in winter', () {
    expect(_start('Europe/London', DateTime.utc(2026, 9, 25, 23, 30)), '2026-09-25T23:00:00.000Z');
    expect(_start('Europe/London', DateTime.utc(2026, 9, 26, 12)), '2026-09-25T23:00:00.000Z');
    expect(_start('Europe/London', DateTime.utc(2026, 12, 1, 0, 30)), '2026-12-01T00:00:00.000Z');
  });

  test('the EU changes the clocks on the last Sunday of March and October, at 01:00 UTC', () {
    // Sunday 29 March 2026: Paris is on CET until 01:00 UTC, so its day began at 23:00 UTC.
    expect(_start('Europe/Paris', DateTime.utc(2026, 3, 29, 12)), '2026-03-28T23:00:00.000Z');
    expect(_start('Europe/Paris', DateTime.utc(2026, 3, 30, 12)), '2026-03-29T22:00:00.000Z');
    // Sunday 25 October 2026: CEST until 01:00 UTC.
    expect(_start('Europe/Paris', DateTime.utc(2026, 10, 25, 12)), '2026-10-24T22:00:00.000Z');
    expect(_start('Europe/Paris', DateTime.utc(2026, 10, 26, 12)), '2026-10-25T23:00:00.000Z');
  });

  test('North America: from the second Sunday of March to the first of November', () {
    expect(_start('America/New_York', DateTime.utc(2026, 7, 1, 12)), '2026-07-01T04:00:00.000Z');
    expect(_start('America/New_York', DateTime.utc(2026, 1, 15, 12)), '2026-01-15T05:00:00.000Z');
    // 8 March 2026, 02:00 EST: the day began on standard time.
    expect(_start('America/New_York', DateTime.utc(2026, 3, 8, 12)), '2026-03-08T05:00:00.000Z');
    expect(_start('America/New_York', DateTime.utc(2026, 3, 9, 12)), '2026-03-09T04:00:00.000Z');
    // 1 November 2026, 02:00 EDT: the day began on daylight time.
    expect(_start('America/New_York', DateTime.utc(2026, 11, 1, 12)), '2026-11-01T04:00:00.000Z');
    expect(_start('America/Los_Angeles', DateTime.utc(2026, 11, 2, 12)), '2026-11-02T08:00:00.000Z');
    // British Columbia keeps summer time from 1 November 2026.
    expect(_start('America/Vancouver', DateTime.utc(2026, 7, 1, 12)), '2026-07-01T07:00:00.000Z');
    expect(_start('America/Vancouver', DateTime.utc(2026, 11, 2, 12)), '2026-11-02T07:00:00.000Z');
    expect(_start('America/Vancouver', DateTime.utc(2027, 1, 15, 12)), '2027-01-15T07:00:00.000Z');
  });

  test('the southern hemisphere keeps summer across the new year', () {
    expect(_start('Australia/Sydney', DateTime.utc(2026, 1, 10, 3)), '2026-01-09T13:00:00.000Z');
    expect(_start('Australia/Sydney', DateTime.utc(2026, 7, 10, 3)), '2026-07-09T14:00:00.000Z');
    expect(_start('Pacific/Auckland', DateTime.utc(2026, 1, 10, 3)), '2026-01-09T11:00:00.000Z');
    expect(_start('Pacific/Auckland', DateTime.utc(2026, 7, 10, 3)), '2026-07-09T12:00:00.000Z');
    expect(_start('America/Santiago', DateTime.utc(2026, 1, 10, 12)), '2026-01-10T03:00:00.000Z');
    expect(_start('America/Santiago', DateTime.utc(2026, 7, 10, 12)), '2026-07-10T04:00:00.000Z');
  });

  test('Egypt: summer time from the last Friday of April to the last Thursday of October', () {
    expect(_start('Africa/Cairo', DateTime.utc(2026, 7, 1, 12)), '2026-06-30T21:00:00.000Z');
    expect(_start('Africa/Cairo', DateTime.utc(2026, 12, 1, 12)), '2026-11-30T22:00:00.000Z');
  });

  test('a zone ahead of UTC by a part hour, with no clock change', () {
    // 02:00 on 26 September in Kolkata is 20:30 UTC on the 25th.
    expect(_start('Asia/Kolkata', DateTime.utc(2026, 9, 25, 20, 30)), '2026-09-25T18:30:00.000Z');
    expect(_start('Asia/Kathmandu', DateTime.utc(2026, 9, 26, 6)), '2026-09-25T18:15:00.000Z');
  });

  test('every zone the store form offers is known', () {
    for (final zone in ianaTimezones) {
      expect(knowsZone(zone), isTrue, reason: zone);
    }
  });

  test("an unknown zone, or none, falls back to the device's midnight, never UTC's", () {
    final now = DateTime.utc(2026, 9, 26, 12);
    final local = now.toLocal();
    final device = DateTime(local.year, local.month, local.day).toUtc().toIso8601String();
    expect(_start(null, now), device);
    expect(_start('', now), device);
    expect(_start('Mars/Olympus_Mons', now), device);
  });
}
