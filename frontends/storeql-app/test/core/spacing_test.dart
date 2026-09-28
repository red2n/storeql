import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/spacing.dart';

Future<void> _pumpAt(WidgetTester tester, double width, Widget child) async {
  tester.view.physicalSize = Size(width, 900);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(MaterialApp(home: Scaffold(body: child)));
}

void main() {
  testWidgets('ContentBounds caps a reading page at 1200 in a desktop window', (tester) async {
    await _pumpAt(tester, 1600, ContentBounds(child: Container(key: const Key('page'))));
    expect(tester.getSize(find.byKey(const Key('page'))).width, 1200);
    // Centred, not pinned to the left edge.
    expect(tester.getTopLeft(find.byKey(const Key('page'))).dx, 200);
  });

  testWidgets('ContentBounds.form caps a single-column form at 640', (tester) async {
    await _pumpAt(tester, 1600, ContentBounds.form(child: Container(key: const Key('form'))));
    expect(tester.getSize(find.byKey(const Key('form'))).width, 640);
  });

  testWidgets('ContentBounds.reading keeps running text to about 80 characters a line', (tester) async {
    // 552 of 14px text, plus the page's 24 gutters on each side from a tablet up.
    await _pumpAt(tester, 1600, ContentBounds.reading(child: Container(key: const Key('text'))));
    expect(tester.getSize(find.byKey(const Key('text'))).width, AppBreakpoints.readingMeasure + 2 * AppSpacing.xl);
    expect(AppBreakpoints.readingMeasure, 552);
  });

  testWidgets('ContentBounds.reading is a no-op on a phone', (tester) async {
    await _pumpAt(tester, 390, ContentBounds.reading(child: Container(key: const Key('text'))));
    expect(tester.getSize(find.byKey(const Key('text'))).width, 390);
  });

  testWidgets('ContentBounds is a no-op on a phone', (tester) async {
    await _pumpAt(tester, 390, ContentBounds(child: Container(key: const Key('page'))));
    expect(tester.getSize(find.byKey(const Key('page'))).width, 390);
  });

  testWidgets('the window class and page padding follow the width', (tester) async {
    late WindowClass wc;
    late EdgeInsets pad;
    final probe = Builder(builder: (context) {
      wc = context.windowClass;
      pad = context.pagePadding;
      return const SizedBox();
    });
    await _pumpAt(tester, 390, probe);
    expect(wc, WindowClass.compact);
    expect(pad, const EdgeInsets.all(AppSpacing.lg));
    await _pumpAt(tester, 700, probe);
    expect(wc, WindowClass.medium);
    expect(pad, const EdgeInsets.all(AppSpacing.xl));
    await _pumpAt(tester, 1000, probe);
    expect(wc, WindowClass.expanded);
    await _pumpAt(tester, 1400, probe);
    expect(wc, WindowClass.large);
  });

  test('the breakpoints are the Material 3 window classes plus the rail', () {
    expect(AppBreakpoints.medium, 600);
    expect(AppBreakpoints.rail, 800);
    expect(AppBreakpoints.expanded, 840);
    expect(AppBreakpoints.large, 1200);
    expect(AppBreakpoints.formMaxWidth, lessThan(AppBreakpoints.contentMaxWidth));
  });
}
