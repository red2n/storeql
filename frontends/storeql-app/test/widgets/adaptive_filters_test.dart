import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/shared/widgets/adaptive_filters.dart';

Future<void> _pump(WidgetTester tester, {required double width, double textScale = 1}) async {
  tester.view.physicalSize = Size(width, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(
    home: MediaQuery(
      data: MediaQueryData(size: Size(width, 900), textScaler: TextScaler.linear(textScale)),
      child: Scaffold(
        body: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: AdaptiveFilters(
            activeCount: 2,
            onClear: () {},
            children: const [TextField(decoration: InputDecoration(labelText: 'Search'))],
          ),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('on a 390px phone the Filters button and Clear filters fit without overflowing',
      (tester) async {
    await _pump(tester, width: 390);
    expect(tester.takeException(), isNull);
    expect(find.text('Filters · 2'), findsOneWidget);
    expect(find.text('Clear filters'), findsOneWidget);
  });

  testWidgets('at 200% text on a phone, Clear filters moves under the button instead of overflowing',
      (tester) async {
    await _pump(tester, width: 390, textScale: 2);
    expect(tester.takeException(), isNull);
    final button = tester.getRect(find.text('Filters · 2'));
    final clear = tester.getRect(find.text('Clear filters'));
    expect(clear.top, greaterThanOrEqualTo(button.bottom), reason: 'wrapped to its own line');
  });

  testWidgets('the filters open in place, stacked, when the button is tapped', (tester) async {
    await _pump(tester, width: 390);
    expect(find.byType(TextField), findsNothing);
    await tester.tap(find.text('Filters · 2'));
    await tester.pumpAndSettle();
    expect(find.byType(TextField), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
