import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/shared/widgets/page_header.dart';

Widget _harness(String title, {double? stackBelow}) => MaterialApp(
      home: Scaffold(
        body: PageHeader(
          title: title,
          stackBelow: stackBelow ?? PageHeader.defaultStackBelow,
          subtitle: 'Everyone who has shopped with you',
          actions: [
            FilledButton.icon(
              onPressed: () {},
              icon: const Icon(Icons.person_add_alt),
              label: const Text('Add customer'),
            ),
            IconButton(
              onPressed: () {},
              icon: const Icon(Icons.refresh),
              tooltip: 'Refresh customers',
            ),
          ],
        ),
      ),
    );

void _window(WidgetTester tester, Size size) {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

void main() {
  testWidgets('title and actions share one row from tablet width',
      (tester) async {
    _window(tester, const Size(1024, 768));
    await tester.pumpWidget(_harness('Customers'));

    final title = tester.getRect(find.text('Customers'));
    final button = tester.getRect(find.text('Add customer'));
    expect(button.left, greaterThan(title.right));
    expect(button.top, lessThan(title.bottom));
  });

  testWidgets('on a phone the actions wrap under a long title, no overflow',
      (tester) async {
    _window(tester, const Size(360, 800));
    await tester.pumpWidget(
        _harness('Customers and their loyalty accounts across every store'));

    expect(tester.takeException(), isNull);
    final title = tester.getRect(find.textContaining('Customers and'));
    final button = tester.getRect(find.text('Add customer'));
    expect(button.top, greaterThan(title.bottom));
  });

  testWidgets('a page may ask for its actions under the title below a wider header',
      (tester) async {
    // A 600px window: the header is 552 inside its 24 gutters.
    _window(tester, const Size(600, 800));
    await tester.pumpWidget(_harness('Chargebacks'));
    var title = tester.getRect(find.text('Chargebacks'));
    var button = tester.getRect(find.text('Add customer'));
    expect(button.left, greaterThan(title.right), reason: 'one row by default at 552');

    await tester.pumpWidget(_harness('Chargebacks', stackBelow: 600));
    title = tester.getRect(find.text('Chargebacks'));
    button = tester.getRect(find.text('Add customer'));
    expect(button.top, greaterThan(title.bottom), reason: 'stacked below a 600px header');
    expect(tester.takeException(), isNull);
  });
}
