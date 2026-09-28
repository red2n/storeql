import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/shared/util/slot_label.dart';

// How a delivery or collection window is worded, the same on every screen: the
// kind when the line does not already say it, the weekday and date in the
// reader's own order, and the store's own clock as order-svc sent it.
void main() {
  setUpAll(initializeDateFormatting);
  setUp(() => Intl.defaultLocale = 'en_GB');

  test('a window names its kind, day and times', () {
    expect(
      slotWindowLabel(
        fulfilmentType: 'DELIVERY',
        date: '2026-09-27',
        startTime: '17:00',
        endTime: '19:00',
      ),
      'Delivery · Sun 27 Sept, 17:00–19:00',
    );
    expect(
      slotWindowLabel(
        fulfilmentType: 'PICKUP',
        date: '2026-09-27',
        startTime: '17:00',
        endTime: '19:00',
      ),
      'Collection · Sun 27 Sept, 17:00–19:00',
    );
  });

  test('beside a line that already says the kind, only when it is', () {
    expect(
      slotWhen(date: '2026-09-27', startTime: '17:00', endTime: '19:00'),
      'Sun 27 Sept, 17:00–19:00',
    );
  });

  test('the reader decides the order, not one country', () {
    Intl.defaultLocale = 'en_US';
    expect(
      slotWhen(date: '2026-09-27', startTime: '17:00', endTime: '19:00'),
      'Sun, Sep 27, 17:00–19:00',
    );
  });

  test(
    'a window whose day never arrived still says its times, with no stray comma',
    () {
      expect(
        slotWhen(date: '', startTime: '17:00', endTime: '19:00'),
        '17:00–19:00',
      );
      expect(
        slotWindowLabel(
          fulfilmentType: 'DELIVERY',
          date: '',
          startTime: '17:00',
          endTime: '19:00',
        ),
        'Delivery · 17:00–19:00',
      );
    },
  );
}
