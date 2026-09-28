import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/features/storefront/delivery_slot_picker.dart';
import 'package:storeql_app/features/storefront/storefront_providers.dart';

// ---------------------------------------------------------------------------
// The design system's DeliverySlotPicker: a row of day choices (today,
// tomorrow, then dates via AppFormat), the selected day in a `primary` fill,
// and only that day's windows below it — never all seven at once, which used
// to push everything under the picker off the bottom of the web cart's
// narrow checkout column. A chosen window is the informational
// `tertiaryContainer` with a 2px `tertiary` border, not the theme's default
// selected filter chip. A full window is shown, disabled, and still
// readable; a day with none says so; offered=false renders nothing at all —
// a store with no windows checks out as before.
// ---------------------------------------------------------------------------

const _store = 's1';

/// Seven days from [firstDate], each day([i]) getting [slotsFor](i) — empty by
/// default.
List<Map<String, dynamic>> _days(
  String firstDate, {
  List<Map<String, dynamic>> Function(int day)? slotsFor,
}) {
  final start = DateTime.parse(firstDate);
  return [
    for (var i = 0; i < 7; i++)
      {
        'date': start.add(Duration(days: i)).toIso8601String().substring(0, 10),
        'slots': slotsFor == null ? const [] : slotsFor(i),
      },
  ];
}

Map<String, dynamic> _slot(
  String windowId,
  String startTime,
  String endTime, {
  bool full = false,
  int left = 1,
}) => {
  'windowId': windowId,
  'startsAt': '2026-09-26T$startTime:00Z',
  'endsAt': '2026-09-26T$endTime:00Z',
  'startTime': startTime,
  'endTime': endTime,
  'left': left,
  'full': full,
};

class _Server extends Interceptor {
  final Map<String, dynamic> body;
  _Server(this.body);

  @override
  void onRequest(RequestOptions o, RequestInterceptorHandler h) {
    if (o.path.endsWith('/storefront/fulfilment-slots')) {
      h.resolve(
        Response(requestOptions: o, statusCode: 200, data: {'data': body}),
      );
      return;
    }
    h.reject(
      DioException(requestOptions: o, type: DioExceptionType.connectionError),
    );
  }
}

Future<void> _pump(
  WidgetTester tester,
  Map<String, dynamic> body, {
  SlotOption? selected,
  ValueChanged<SlotOption?>? onSelected,
  String fulfilmentType = 'DELIVERY',
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        storefrontDioProvider.overrideWith((ref) {
          final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
          dio.interceptors.add(_Server(body));
          return dio;
        }),
      ],
      child: MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: DeliverySlotPicker(
              storeId: _store,
              fulfilmentType: fulfilmentType,
              selected: selected,
              onSelected: onSelected ?? (_) {},
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

/// The theme the picker itself reads, so assertions compare against the same
/// roles rather than a colour copied by hand.
ColorScheme _colors(WidgetTester tester) =>
    Theme.of(tester.element(find.byType(DeliverySlotPicker))).colorScheme;

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('shows the days as a row of choices, Today and Tomorrow first', (
    tester,
  ) async {
    await _pump(tester, {
      'storeId': _store,
      'fulfilmentType': 'DELIVERY',
      'timeZone': 'Europe/London',
      'offered': true,
      'days': _days(
        '2026-09-26',
        slotsFor: (i) => i == 0 ? [_slot('w1', '17:00', '19:00')] : const [],
      ),
    });

    expect(find.text('Today'), findsOneWidget);
    expect(find.text('Tomorrow'), findsOneWidget);
    // Five more named days, each written through AppFormat (never the raw ISO date)
    // as the review and the order write a window's day: weekday and date, no year.
    expect(find.textContaining('2026-09'), findsNothing);
    expect(find.text(AppFormat.weekdayDate(DateTime(2026, 9, 28))), findsOneWidget);
    expect(find.textContaining('2026'), findsNothing);
    // Today is selected by default, so its window shows without tapping anything.
    expect(find.text('17:00–19:00'), findsOneWidget);
  });

  testWidgets(
    "only the selected day's windows show — a day with none says so",
    (tester) async {
      await _pump(tester, {
        'storeId': _store,
        'fulfilmentType': 'DELIVERY',
        'timeZone': 'Europe/London',
        'offered': true,
        'days': _days(
          '2026-09-26',
          slotsFor: (i) {
            if (i == 0) return [_slot('w1', '17:00', '19:00')];
            if (i == 1) return [_slot('w2', '09:00', '10:00')];
            return const [];
          },
        ),
      });

      // Today (day 0) is selected first: only its window shows.
      expect(find.text('17:00–19:00'), findsOneWidget);
      expect(find.text('09:00–10:00'), findsNothing);
      expect(find.text('No windows this day'), findsNothing);

      // Tomorrow (day 1) has a different window — switching the day tab
      // swaps which windows show, it does not add to them.
      await tester.tap(find.text('Tomorrow'));
      await tester.pumpAndSettle();
      expect(find.text('09:00–10:00'), findsOneWidget);
      expect(find.text('17:00–19:00'), findsNothing);

      // A day with no windows at all says so, instead of showing nothing.
      final thirdDay = tester.widgetList<ChoiceChip>(find.byType(ChoiceChip)).length;
      expect(thirdDay, greaterThanOrEqualTo(7), reason: 'all seven days are offered as choices');
      await tester.tap(find.byKey(const Key('slot-day-2')));
      await tester.pumpAndSettle();
      expect(find.text('No windows this day'), findsOneWidget);
      expect(find.text('09:00–10:00'), findsNothing);
    },
  );

  testWidgets('the selected day is filled in primary, not the filter chip default', (
    tester,
  ) async {
    await _pump(tester, {
      'storeId': _store,
      'fulfilmentType': 'DELIVERY',
      'timeZone': 'Europe/London',
      'offered': true,
      'days': _days('2026-09-26'),
    });
    final cs = _colors(tester);

    final today = tester.widget<ChoiceChip>(find.byKey(const Key('slot-day-0')));
    expect(today.selected, isTrue);
    expect(today.selectedColor, cs.primary);
    expect(today.labelStyle?.color, cs.onPrimary);

    final tomorrow = tester.widget<ChoiceChip>(find.byKey(const Key('slot-day-1')));
    expect(tomorrow.selected, isFalse);
    expect(tomorrow.labelStyle?.color, isNot(cs.onPrimary));
  });

  testWidgets(
    'a chosen window is tertiaryContainer with a 2px tertiary border, '
    'not the default selected filter chip',
    (tester) async {
      await _pump(
        tester,
        {
          'storeId': _store,
          'fulfilmentType': 'DELIVERY',
          'timeZone': 'Europe/London',
          'offered': true,
          'days': _days(
            '2026-09-26',
            slotsFor: (i) => i == 0 ? [_slot('w1', '17:00', '19:00')] : const [],
          ),
        },
        selected: SlotOption.fromJson(_slot('w1', '17:00', '19:00')),
      );
      final cs = _colors(tester);

      final chip = tester.widget<ChoiceChip>(
        find.ancestor(
          of: find.text('17:00–19:00'),
          matching: find.byType(ChoiceChip),
        ).first,
      );
      expect(chip.selected, isTrue);
      expect(chip.showCheckmark, isFalse,
          reason: 'the border carries the state, not a check on a '
              'secondary-container fill');
      expect(chip.selectedColor, cs.tertiaryContainer);
      expect(chip.labelStyle?.color, cs.onTertiaryContainer);
      final shape = chip.shape;
      expect(shape, isA<RoundedRectangleBorder>());
      final side = (shape as RoundedRectangleBorder).side;
      expect(side.color, cs.tertiary);
      expect(side.width, 2);
    },
  );

  testWidgets('a full window is shown, disabled, and marked Full — and stays readable', (
    tester,
  ) async {
    var selected = false;
    await _pump(tester, {
      'storeId': _store,
      'fulfilmentType': 'DELIVERY',
      'timeZone': 'Europe/London',
      'offered': true,
      'days': _days(
        '2026-09-26',
        slotsFor: (i) => i == 0
            ? [_slot('w1', '17:00', '19:00', full: true, left: 0)]
            : const [],
      ),
    }, onSelected: (_) => selected = true);
    final cs = _colors(tester);

    final chipText = find.text('17:00–19:00 · Full');
    expect(chipText, findsOneWidget);
    // A plain Chip, not a disabled ChoiceChip: Flutter fades a disabled
    // ChoiceChip's label to 38% opacity, which would make "Full" hard to
    // read rather than merely unpickable.
    expect(find.ancestor(of: chipText, matching: find.byType(ChoiceChip)), findsNothing);
    final chip = tester.widget<Chip>(
      find.ancestor(of: chipText, matching: find.byType(Chip)).first,
    );
    expect(chip.labelStyle?.color, cs.onSurface,
        reason: 'full opacity — disabled but still legible');

    await tester.tap(chipText, warnIfMissed: false);
    await tester.pumpAndSettle();
    expect(selected, isFalse);
  });

  Map<String, dynamic> oneDeliveryDay() => {
    'storeId': _store,
    'fulfilmentType': 'DELIVERY',
    'timeZone': 'Europe/London',
    'offered': true,
    'days': _days(
      '2026-09-26',
      slotsFor: (i) => i == 0 ? [_slot('w1', '17:00', '19:00')] : const [],
    ),
  };

  testWidgets('picking an unselected window reports it', (tester) async {
    SlotOption? picked;
    await _pump(tester, oneDeliveryDay(), onSelected: (s) => picked = s);

    await tester.tap(find.text('17:00–19:00'));
    await tester.pumpAndSettle();
    expect(picked?.windowId, 'w1');
  });

  testWidgets('un-picking a window keeps the shopper on the day they were looking at', (
    tester,
  ) async {
    // Hosted as the cart hosts it: the pick lives in the parent and comes back in.
    SlotOption? selected;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          storefrontDioProvider.overrideWith((ref) {
            final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
            dio.interceptors.add(
              _Server({
                'storeId': _store,
                'fulfilmentType': 'DELIVERY',
                'timeZone': 'Europe/Warsaw',
                'offered': true,
                'days': _days(
                  '2026-09-26',
                  slotsFor: (i) => i == 0
                      ? [_slot('w1', '17:00', '19:00')]
                      : i == 2
                      ? [_slot('w3', '12:00', '13:00')]
                      : const [],
                ),
              }),
            );
            return dio;
          }),
        ],
        child: MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: StatefulBuilder(
                builder: (context, setState) => DeliverySlotPicker(
                  storeId: _store,
                  fulfilmentType: 'DELIVERY',
                  selected: selected,
                  onSelected: (s) => setState(() => selected = s),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('slot-day-2')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('12:00–13:00'));
    await tester.pumpAndSettle();
    expect(selected?.windowId, 'w3');
    expect(selected?.date, '2026-09-28', reason: 'a picked window knows its own day');

    await tester.tap(find.text('12:00–13:00'));
    await tester.pumpAndSettle();
    expect(selected, isNull);
    // Still on the third day: its window shows, today's does not.
    expect(find.text('12:00–13:00'), findsOneWidget);
    expect(find.text('17:00–19:00'), findsNothing);
  });

  testWidgets('picking the already-chosen window clears it', (tester) async {
    SlotOption? picked;
    await _pump(
      tester,
      oneDeliveryDay(),
      selected: SlotOption.fromJson(_slot('w1', '17:00', '19:00')),
      onSelected: (s) => picked = s,
    );

    await tester.tap(find.text('17:00–19:00'));
    await tester.pumpAndSettle();
    expect(picked, isNull);
  });

  testWidgets('offered=false renders nothing — checkout exactly as before', (
    tester,
  ) async {
    await _pump(tester, {
      'storeId': _store,
      'fulfilmentType': 'DELIVERY',
      'timeZone': 'Europe/London',
      'offered': false,
      'days': _days('2026-09-26'),
    });

    expect(find.text('Today'), findsNothing);
    expect(find.byType(ChoiceChip), findsNothing);
    expect(find.textContaining('Pick a'), findsNothing);
    // The widget itself takes no room.
    expect(tester.getSize(find.byType(DeliverySlotPicker)), Size.zero);
  });

  testWidgets(
    'with nothing chosen yet, it says why Place order cannot go ahead',
    (tester) async {
      await _pump(
        tester,
        {
          'storeId': _store,
          'fulfilmentType': 'PICKUP',
          'timeZone': 'Europe/London',
          'offered': true,
          'days': _days(
            '2026-09-26',
            slotsFor: (i) =>
                i == 0 ? [_slot('w1', '09:00', '10:00')] : const [],
          ),
        },
        selected: null,
        fulfilmentType: 'PICKUP',
      );

      expect(
        find.text('Pick a collection window before placing your order'),
        findsOneWidget,
      );
    },
  );
}
