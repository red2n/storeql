import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/shared/widgets/adaptive_actions.dart';

Widget _harness(List<String> tapped) => MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('POS Terminal'),
          actions: [
            AdaptiveActions(actions: [
              AdaptiveAction(
                label: 'Returns',
                icon: Icons.recycling,
                showLabel: true,
                keepOnCompact: true,
                onPressed: () => tapped.add('returns'),
              ),
              AdaptiveAction(
                label: 'Clock out',
                icon: Icons.logout,
                showLabel: true,
                onPressed: () => tapped.add('clock out'),
              ),
              AdaptiveAction(
                key: const Key('printer'),
                label: 'Receipt printer',
                icon: Icons.print_outlined,
                onPressed: () => tapped.add('printer'),
              ),
            ]),
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
  testWidgets('shows every action from tablet width, labelled ones as text',
      (tester) async {
    _window(tester, const Size(1024, 768));
    final tapped = <String>[];
    await tester.pumpWidget(_harness(tapped));

    expect(find.text('Returns'), findsOneWidget);
    expect(find.text('Clock out'), findsOneWidget);
    expect(find.byKey(const Key('printer')), findsOneWidget);
    expect(find.byIcon(Icons.more_vert), findsNothing);

    await tester.tap(find.byKey(const Key('printer')));
    expect(tapped, ['printer']);
  });

  testWidgets('on a phone keeps only keepOnCompact actions; the rest go into ⋮',
      (tester) async {
    _window(tester, const Size(390, 800));
    final tapped = <String>[];
    await tester.pumpWidget(_harness(tapped));

    // Returns stays, as an icon button; the others are not on the bar.
    expect(find.byTooltip('Returns'), findsOneWidget);
    expect(find.text('Clock out'), findsNothing);
    expect(find.byKey(const Key('printer')), findsNothing);

    await tester.tap(find.byIcon(Icons.more_vert));
    await tester.pumpAndSettle();
    expect(find.text('Clock out'), findsOneWidget);
    expect(find.text('Receipt printer'), findsOneWidget);

    await tester.tap(find.byKey(const Key('printer')));
    await tester.pumpAndSettle();
    expect(tapped, ['printer']);
  });
}
