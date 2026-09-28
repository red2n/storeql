import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/admin/categories_screen.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';

// A category's status reads as a word on the shared badge — Active, Inactive —
// never the code the catalogue stores, on a phone's list and a desktop's table.

void main() {
  for (final size in const [Size(390, 844), Size(1400, 900)]) {
    testWidgets('at ${size.width.toInt()} wide the status is a word, not a code', (tester) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(
        overrides: [
          categoriesProvider.overrideWith((ref) async => const [
                CategoryInfo(id: 'c-1', name: 'Pantry', status: 'ACTIVE', createdAt: ''),
                CategoryInfo(id: 'c-2', name: 'Seasonal', status: 'INACTIVE', createdAt: ''),
              ]),
        ],
        child: const MaterialApp(home: Scaffold(body: CategoriesScreen())),
      ));
      await tester.pumpAndSettle();
      expect(find.widgetWithText(StatusBadge, 'Active'), findsOneWidget);
      expect(find.widgetWithText(StatusBadge, 'Inactive'), findsOneWidget);
      expect(find.text('ACTIVE'), findsNothing);
      expect(find.text('INACTIVE'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  }
}
