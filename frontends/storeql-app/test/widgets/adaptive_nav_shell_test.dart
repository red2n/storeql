import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/shared/widgets/adaptive_nav_shell.dart';

Widget _harness() => MaterialApp(
      home: AdaptiveNavShell(
        title: 'Admin',
        selectedIndex: 0,
        onDestinationSelected: (_) {},
        destinations: const [
          AdaptiveNavDestination(
              label: 'Home', icon: Icons.home_outlined, selectedIcon: Icons.home),
          AdaptiveNavDestination(
              label: 'Settings',
              icon: Icons.settings_outlined,
              selectedIcon: Icons.settings),
        ],
        child: const SizedBox.shrink(),
      ),
    );

/// A back-office-sized shell: a dashboard, then 24 pages in two sections.
Widget _sectioned({int selected = 0, ValueChanged<int>? onSelected}) =>
    MaterialApp(
      home: AdaptiveNavShell(
        title: 'Corner Stores Ltd',
        selectedIndex: selected,
        onDestinationSelected: onSelected ?? (_) {},
        destinations: [
          const AdaptiveNavDestination(
              label: 'Dashboard',
              icon: Icons.dashboard_outlined,
              selectedIcon: Icons.dashboard),
          for (var i = 0; i < 24; i++)
            AdaptiveNavDestination(
              label: 'Page $i',
              icon: Icons.circle_outlined,
              selectedIcon: Icons.circle,
              section: i < 12 ? 'Sell' : 'Money',
            ),
        ],
        child: const SizedBox.shrink(),
      ),
    );

void _window(WidgetTester tester, Size size) {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

void main() {
  testWidgets('shows a NavigationRail on wide layouts', (tester) async {
    _window(tester, const Size(1200, 800));

    await tester.pumpWidget(_harness());
    expect(find.byType(NavigationRail), findsOneWidget);
  });

  testWidgets('collapses to a drawer (no rail) on narrow layouts',
      (tester) async {
    _window(tester, const Size(500, 800));

    await tester.pumpWidget(_harness());
    expect(find.byType(NavigationRail), findsNothing);
    expect(find.byType(Scaffold), findsOneWidget);
  });

  group('sectioned shell', () {
    testWidgets(
        'keeps a labelled list with headings beside the content on desktop, '
        'scrolled to the selected page', (tester) async {
      _window(tester, const Size(1400, 800));

      // Index 20 is "Page 19", far below the fold of an unscrolled list.
      await tester.pumpWidget(_sectioned(selected: 20));
      await tester.pumpAndSettle();

      expect(find.byType(NavigationRail), findsNothing);
      expect(find.byType(NavigationDrawer), findsOneWidget);
      final selected = find.text('Page 19');
      expect(selected, findsOneWidget);
      expect(tester.getRect(selected).bottom, lessThan(800));
    });

    testWidgets('below desktop width opens the same list as a drawer',
        (tester) async {
      _window(tester, const Size(1024, 768));

      await tester.pumpWidget(_sectioned());
      expect(find.byType(NavigationRail), findsNothing);
      expect(find.byType(NavigationDrawer), findsNothing);

      await tester.tap(find.byTooltip('Toggle menu'));
      await tester.pumpAndSettle();
      expect(find.byType(NavigationDrawer), findsOneWidget);
      expect(find.text('Sell'), findsOneWidget);
    });

    testWidgets('the find box filters the list and Enter opens the first match',
        (tester) async {
      _window(tester, const Size(1400, 800));
      int? picked;

      await tester.pumpWidget(_sectioned(onSelected: (i) => picked = i));
      await tester.pumpAndSettle();
      expect(find.text('Sell'), findsOneWidget);

      await tester.enterText(find.byType(TextField), 'page 2');
      await tester.pumpAndSettle();
      expect(find.text('Page 2'), findsOneWidget);
      expect(find.text('Page 3'), findsNothing);
      // A filtered list is flat: no headings.
      expect(find.text('Sell'), findsNothing);

      await tester.testTextInput.receiveAction(TextInputAction.go);
      await tester.pumpAndSettle();
      expect(picked, 3); // Dashboard, Page 0, Page 1, Page 2
    });

    testWidgets('Ctrl+K puts the cursor in the find box', (tester) async {
      _window(tester, const Size(1400, 800));

      await tester.pumpWidget(_sectioned());
      await tester.pumpAndSettle();

      await tester.sendKeyDownEvent(LogicalKeyboardKey.controlLeft);
      await tester.sendKeyEvent(LogicalKeyboardKey.keyK);
      await tester.sendKeyUpEvent(LogicalKeyboardKey.controlLeft);
      await tester.pumpAndSettle();

      final field = tester.widget<TextField>(find.byType(TextField));
      expect(field.focusNode!.hasFocus, isTrue);
    });
  });
}
