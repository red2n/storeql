import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/admin/food_safety_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Food safety, as it is laid out and worded under the app's own theme.
//
// A count reads as English (*1 open failure*); a check's state is a word in
// its status colour at label size; the cards stand apart rather than touching
// (the theme's Card has no margin); on a phone *Record* goes under the check's
// text so the name and details get the width; and the tabs start under the
// title, not 52px in.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final String points;
  _Server(this.points);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    if (o.path.contains('/admin/stores')) {
      return jsonResponse(
          '{"data":[{"id":"store-1","name":"High Street","code":"HS","type":"STORE","status":"ACTIVE"}]}');
    }
    if (o.path.endsWith('/food-safety/points')) return jsonResponse(points);
    return jsonResponse('{"data":[]}');
  }
}

String _point(String id, String name, String due, {int openFailures = 0}) => '''
{"id":"$id","storeId":"store-1","name":"$name",
 "checkType":{"id":"t-1","code":"CHILLED_STORAGE","name":"Chilled storage","kind":"TEMPERATURE","maxValue":8.00,"statutory":true,"platform":true},
 "maxValue":8.00,"frequencyHours":4,"active":true,"dueStatus":"$due","openFailures":$openFailures,
 "lastRecordedAt":"2026-09-11T08:00:00Z","lastValue":9.5,"lastResult":"FAIL"}''';

final _three =
    '{"data":[${_point('pt-1', 'Dairy chiller 1', 'OK')},${_point('pt-2', 'Walk-in chiller', 'DUE')},${_point('pt-3', 'Deli counter', 'OVERDUE')}]}';

Future<void> _pump(
  WidgetTester tester, {
  String role = 'STOREKEEPER',
  required String points,
  Size size = const Size(1400, 1000),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = _Server(points);
  await tester.pumpWidget(ProviderScope(
    // A fresh scope each time, so a second pump in one test reads anew.
    key: UniqueKey(),
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
    ],
    child: MaterialApp(
      theme: AppTheme.light,
      home: const Scaffold(body: FoodSafetyScreen()),
    ),
  ));
  await tester.pumpAndSettle();
}

/// The gaps between each card and the next, top to bottom.
List<double> _gaps(WidgetTester tester) {
  final rects = tester.widgetList(find.byType(Card)).map((w) {
    return tester.getRect(find.byWidget(w));
  }).toList()
    ..sort((a, b) => a.top.compareTo(b.top));
  return [
    for (var i = 1; i < rects.length; i++) rects[i].top - rects[i - 1].bottom,
  ];
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('one open failure is a failure, not failures', (tester) async {
    await _pump(tester,
        points: '{"data":[${_point('pt-1', 'Dairy chiller 1', 'OK', openFailures: 1)}]}');
    expect(find.text('1 open failure'), findsOneWidget);
    expect(find.text('1 open failures'), findsNothing);

    await _pump(tester,
        points: '{"data":[${_point('pt-1', 'Dairy chiller 1', 'OK', openFailures: 2)}]}');
    expect(find.text('2 open failures'), findsOneWidget);
  });

  testWidgets('each state is a word in its status colour, at label size',
      (tester) async {
    await _pump(tester, points: _three);
    final context = tester.element(find.text('Done'));
    final theme = Theme.of(context);
    final labelLarge = theme.textTheme.labelLarge!.fontSize;
    Text word(String w) => tester.widget<Text>(find.text(w));

    expect(word('Done').style?.color, StatusColors.light.success);
    expect(word('Due').style?.color, StatusColors.light.warning);
    expect(word('Overdue').style?.color, theme.colorScheme.error);
    for (final w in ['Done', 'Due', 'Overdue']) {
      expect(word(w).style?.fontSize, labelLarge, reason: '"$w" at labelLarge');
    }
  });

  testWidgets('the checks on Today stand 8 apart', (tester) async {
    await _pump(tester, points: _three);
    expect(find.byType(Card), findsNWidgets(3));
    expect(_gaps(tester), [8, 8]);
  });

  testWidgets('the checks on Setup stand 8 apart', (tester) async {
    await _pump(tester, role: 'MANAGER', points: _three);
    await tester.tap(find.text('Setup'));
    await tester.pumpAndSettle();
    expect(find.byType(Card), findsNWidgets(3));
    expect(_gaps(tester), [8, 8]);
  });

  testWidgets('on a phone a check point\'s Edit and Switch off fold into one menu', (tester) async {
    await _pump(tester, role: 'MANAGER', points: _three, size: const Size(390, 844));
    await tester.tap(find.text('Setup'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.widgetWithText(TextButton, 'Edit'), findsNothing);
    expect(find.byType(PopupMenuButton<String>), findsNWidgets(3));
    // The point's name keeps most of the card.
    expect(tester.getSize(find.text('Dairy chiller 1')).width, greaterThan(150));
  });

  testWidgets('on a phone Record goes under the check, which gets the width',
      (tester) async {
    await _pump(tester,
        points: '{"data":[${_point('pt-1', 'Dairy chiller 1', 'DUE', openFailures: 1)}]}',
        size: const Size(390, 844));
    final details = find.textContaining('every 4 hours');
    final record = find.widgetWithText(FilledButton, 'Record');
    expect(tester.getRect(record).top,
        greaterThanOrEqualTo(tester.getRect(details).bottom));
    // Nothing sits beside the text: it runs to the card's end padding.
    final card = tester.getRect(find.byType(Card));
    expect(card.right - tester.getRect(details).right, lessThan(40));
    expect(tester.takeException(), isNull);
  });

  testWidgets('from tablet width Record stays beside the check', (tester) async {
    await _pump(tester,
        points: '{"data":[${_point('pt-1', 'Dairy chiller 1', 'DUE')}]}');
    final details = find.textContaining('every 4 hours');
    final record = find.widgetWithText(FilledButton, 'Record');
    expect(tester.getRect(record).left,
        greaterThan(tester.getRect(details).right));
  });

  testWidgets('on a phone at 200% text the checks still fit', (tester) async {
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await _pump(tester, points: _three, size: const Size(390, 844));
    expect(tester.takeException(), isNull);
    expect(find.text('Record'), findsWidgets);
  });

  testWidgets('the tabs start under the title, not 52 in', (tester) async {
    for (final size in [const Size(1400, 1000), const Size(390, 844)]) {
      await _pump(tester, role: 'MANAGER', points: _three, size: size);
      final title = tester.getRect(find.text('Food safety')).left;
      final firstTab = tester
          .getRect(find.descendant(
              of: find.byType(TabBar), matching: find.text('Today')))
          .left;
      expect(firstTab, closeTo(title, 1), reason: 'at ${size.width} wide');
    }
  });
}
