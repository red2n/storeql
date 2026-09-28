import '../../core/format.dart';

/// The store's own bare calendar date ("2026-09-27"), read as its year/month/day
/// only — never run through a timezone conversion, so a store on the other
/// side of the world keeps its own day. Null when [ymd] is not that shape.
///
/// Kept local to this file (rather than shared from a feature) so a window's
/// wording has no dependency on the storefront or the back office — both
/// import this instead of each other.
DateTime? _slotYmd(String ymd) {
  final parts = ymd.split('-');
  if (parts.length != 3) return null;
  final y = int.tryParse(parts[0]);
  final m = int.tryParse(parts[1]);
  final d = int.tryParse(parts[2]);
  if (y == null || m == null || d == null) return null;
  return DateTime(y, m, d);
}

/// The window an order holds, worded so it says which kind it is: *Delivery ·
/// Sun 27 Sept, 17:00–19:00* or *Collection · Sun 27 Sept, 17:00–19:00* — the
/// fulfilment word, the weekday and date through [AppFormat] in the app's
/// language, and the store's own clock exactly as order-svc sent it, never
/// converted on the device.
///
/// One helper so the storefront order history, the back office Orders screen
/// and Fulfilment all word a window the same way, rather than each leaving
/// the window unlabelled (bare date and time, with nothing to say a delivery
/// window from a collection one).
String slotWindowLabel({
  required String fulfilmentType, // DELIVERY | PICKUP
  required String date, // the store's own "YYYY-MM-DD"
  required String startTime,
  required String endTime,
}) {
  final word = fulfilmentType.toUpperCase() == 'DELIVERY' ? 'Delivery' : 'Collection';
  return '$word · ${slotWhen(date: date, startTime: startTime, endTime: endTime)}';
}

/// When a window is, without saying which kind: *Sun 27 Sept, 17:00–19:00* —
/// for a line that already says delivery or collection beside it (a Fulfilment
/// row, the review before paying, the placed-order note).
String slotWhen({
  required String date, // the store's own "YYYY-MM-DD"
  required String startTime,
  required String endTime,
}) {
  final d = _slotYmd(date);
  final when = d == null ? date : AppFormat.weekdayDate(d);
  // A window whose day never arrived still says its times, with no stray comma.
  return when.isEmpty ? '$startTime–$endTime' : '$when, $startTime–$endTime';
}
