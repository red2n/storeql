// ---------------------------------------------------------------------------
// The start of a store's day, from its IANA time zone (`Europe/London`).
//
// Dart carries no time-zone database, and the app adds no package for one, so
// this is a table: every zone the store form offers (`ianaTimezones` in
// core/reference/iso_reference.dart), its standard offset and its
// daylight-saving rule as they stand in 2026 (checked hour by hour against
// tzdata 2026c for 2025–2027). A zone outside the table — or a store with
// none — falls back to the device's own midnight, which is right for a device
// in the shop, and never to UTC's, which is not the shop's day anywhere that
// keeps summer time or sits off Greenwich.
// ---------------------------------------------------------------------------

/// How a zone changes its clocks; every rule here puts them forward one hour.
enum _Dst {
  /// Never.
  none,

  /// The European Union and its neighbours: the last Sunday of March to the
  /// last Sunday of October, at 01:00 UTC.
  eu,

  /// The United States and Canada: the second Sunday of March, 02:00 local,
  /// to the first Sunday of November, 02:00 local.
  us,

  /// New South Wales and Victoria: the first Sunday of October to the first
  /// Sunday of April, at 02:00 standard time.
  au,

  /// New Zealand: the last Sunday of September to the first Sunday of April,
  /// at 02:00 standard time.
  nz,

  /// Chile: the first Sunday on or after 2 September (04:00 UTC) to the first
  /// Sunday on or after 2 April (03:00 UTC).
  chile,

  /// Egypt: the last Friday of April, 00:00 local, to the end of the last
  /// Thursday of October.
  egypt,

  /// British Columbia: as [us] until 1 November 2026, then UTC−7 all year.
  bc,
}

/// Standard offset in minutes east of UTC, and the clock-change rule.
const Map<String, (int, _Dst)> _zones = {
  'UTC': (0, _Dst.none),
  'Etc/UTC': (0, _Dst.none),
  'GMT': (0, _Dst.none),
  'Africa/Cairo': (120, _Dst.egypt),
  'Africa/Johannesburg': (120, _Dst.none),
  'Africa/Lagos': (60, _Dst.none),
  'Africa/Nairobi': (180, _Dst.none),
  'America/Bogota': (-300, _Dst.none),
  'America/Chicago': (-360, _Dst.us),
  'America/Denver': (-420, _Dst.us),
  'America/Lima': (-300, _Dst.none),
  'America/Los_Angeles': (-480, _Dst.us),
  'America/Mexico_City': (-360, _Dst.none),
  'America/New_York': (-300, _Dst.us),
  'America/Santiago': (-240, _Dst.chile),
  'America/Sao_Paulo': (-180, _Dst.none),
  'America/Toronto': (-300, _Dst.us),
  'America/Vancouver': (-480, _Dst.bc),
  'Asia/Bangkok': (420, _Dst.none),
  'Asia/Colombo': (330, _Dst.none),
  'Asia/Dhaka': (360, _Dst.none),
  'Asia/Dubai': (240, _Dst.none),
  'Asia/Hong_Kong': (480, _Dst.none),
  'Asia/Jakarta': (420, _Dst.none),
  'Asia/Karachi': (300, _Dst.none),
  'Asia/Kathmandu': (345, _Dst.none),
  'Asia/Kolkata': (330, _Dst.none),
  'Asia/Kuala_Lumpur': (480, _Dst.none),
  'Asia/Kuwait': (180, _Dst.none),
  'Asia/Manila': (480, _Dst.none),
  'Asia/Qatar': (180, _Dst.none),
  'Asia/Riyadh': (180, _Dst.none),
  'Asia/Seoul': (540, _Dst.none),
  'Asia/Shanghai': (480, _Dst.none),
  'Asia/Singapore': (480, _Dst.none),
  'Asia/Taipei': (480, _Dst.none),
  'Asia/Tokyo': (540, _Dst.none),
  'Atlantic/Reykjavik': (0, _Dst.none),
  'Australia/Melbourne': (600, _Dst.au),
  'Australia/Perth': (480, _Dst.none),
  'Australia/Sydney': (600, _Dst.au),
  'Europe/Amsterdam': (60, _Dst.eu),
  'Europe/Athens': (120, _Dst.eu),
  'Europe/Berlin': (60, _Dst.eu),
  'Europe/Brussels': (60, _Dst.eu),
  'Europe/Bucharest': (120, _Dst.eu),
  'Europe/Dublin': (0, _Dst.eu),
  'Europe/Helsinki': (120, _Dst.eu),
  'Europe/Istanbul': (180, _Dst.none),
  'Europe/Lisbon': (0, _Dst.eu),
  'Europe/London': (0, _Dst.eu),
  'Europe/Madrid': (60, _Dst.eu),
  'Europe/Oslo': (60, _Dst.eu),
  'Europe/Paris': (60, _Dst.eu),
  'Europe/Prague': (60, _Dst.eu),
  'Europe/Rome': (60, _Dst.eu),
  'Europe/Stockholm': (60, _Dst.eu),
  'Europe/Vienna': (60, _Dst.eu),
  'Europe/Warsaw': (60, _Dst.eu),
  'Europe/Zurich': (60, _Dst.eu),
  'Pacific/Auckland': (720, _Dst.nz),
};

/// Whether [zone] is one whose clock this file can tell.
bool knowsZone(String? zone) => zone != null && _zones.containsKey(zone.trim());

/// The offset from UTC in force in [zone] at [instant], or null for a zone
/// this file does not know.
Duration? utcOffsetIn(String zone, DateTime instant) {
  final entry = _zones[zone.trim()];
  if (entry == null) return null;
  final (standardMinutes, rule) = entry;
  final standard = Duration(minutes: standardMinutes);
  final t = instant.toUtc();
  return _summer(rule, t, standard) ? standard + const Duration(hours: 1) : standard;
}

/// The instant, in UTC, the day of [now] began in [zone]: the store's own
/// midnight. With no zone, or one this file does not know, the device's
/// midnight instead — never UTC's.
DateTime startOfDayIn(String? zone, {DateTime? now}) {
  final at = (now ?? DateTime.now()).toUtc();
  final name = zone?.trim() ?? '';
  final offsetNow = name.isEmpty ? null : utcOffsetIn(name, at);
  if (offsetNow == null) {
    final local = at.toLocal();
    return DateTime(local.year, local.month, local.day).toUtc();
  }
  final wall = at.add(offsetNow);
  final day = DateTime.utc(wall.year, wall.month, wall.day);
  // The clocks may have changed since midnight (a spring or autumn Sunday):
  // take the offset that held then, not now's.
  final offsetThen = utcOffsetIn(name, day.subtract(offsetNow))!;
  return day.subtract(offsetThen);
}

bool _summer(_Dst rule, DateTime t, Duration standard) {
  final y = t.year;
  const h = Duration(hours: 1);
  switch (rule) {
    case _Dst.none:
      return false;
    case _Dst.eu:
      return _within(t, _lastWeekday(y, 3, DateTime.sunday).add(h),
          _lastWeekday(y, 10, DateTime.sunday).add(h));
    case _Dst.bc:
      // Summer time kept from 02:00 on 1 November 2026 (tzdata 2026c).
      if (!t.isBefore(DateTime.utc(2026, 11, 1, 9))) return true;
      return _summer(_Dst.us, t, standard);
    case _Dst.us:
      return _within(
          t,
          _nthWeekday(y, 3, DateTime.sunday, 2).add(const Duration(hours: 2)).subtract(standard),
          _nthWeekday(y, 11, DateTime.sunday, 1).add(const Duration(hours: 1)).subtract(standard));
    case _Dst.au:
      // Southern: summer runs across the new year.
      return !_within(
          t,
          _nthWeekday(y, 4, DateTime.sunday, 1).add(const Duration(hours: 2)).subtract(standard),
          _nthWeekday(y, 10, DateTime.sunday, 1).add(const Duration(hours: 2)).subtract(standard));
    case _Dst.nz:
      return !_within(
          t,
          _nthWeekday(y, 4, DateTime.sunday, 1).add(const Duration(hours: 2)).subtract(standard),
          _lastWeekday(y, 9, DateTime.sunday).add(const Duration(hours: 2)).subtract(standard));
    case _Dst.chile:
      return !_within(t, _onOrAfter(y, 4, 2, DateTime.sunday).add(const Duration(hours: 3)),
          _onOrAfter(y, 9, 2, DateTime.sunday).add(const Duration(hours: 4)));
    case _Dst.egypt:
      return _within(t, _lastWeekday(y, 4, DateTime.friday).subtract(standard),
          _lastWeekday(y, 10, DateTime.thursday).add(const Duration(days: 1)).subtract(standard + h));
  }
}

/// [from] ≤ [t] < [to].
bool _within(DateTime t, DateTime from, DateTime to) => !t.isBefore(from) && t.isBefore(to);

/// Midnight (UTC) of the [n]th [weekday] of [month].
DateTime _nthWeekday(int year, int month, int weekday, int n) =>
    _onOrAfter(year, month, 1 + 7 * (n - 1), weekday);

/// Midnight (UTC) of the first [weekday] on or after [day] of [month].
DateTime _onOrAfter(int year, int month, int day, int weekday) {
  final first = DateTime.utc(year, month, day);
  return first.add(Duration(days: (weekday - first.weekday + 7) % 7));
}

/// Midnight (UTC) of the last [weekday] of [month].
DateTime _lastWeekday(int year, int month, int weekday) {
  final last = DateTime.utc(year, month + 1, 0);
  return last.subtract(Duration(days: (last.weekday - weekday + 7) % 7));
}
