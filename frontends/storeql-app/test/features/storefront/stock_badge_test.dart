import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/storefront/stock_badge.dart';

// ---------------------------------------------------------------------------
// StockBadge: the storefront's stock signal. "Only N left" only when the
// business set a threshold and this variant is at or under it; otherwise the
// plain in-stock/out-of-stock words — and never a number at all when the
// business set no threshold (onlyLeft is then null).
// ---------------------------------------------------------------------------

Future<void> _pump(WidgetTester tester, Widget child) =>
    tester.pumpWidget(MaterialApp(home: Scaffold(body: child)));

void main() {
  testWidgets('shows "Only 3 left" when onlyLeft is set', (tester) async {
    await _pump(tester, const StockBadge(inStock: true, onlyLeft: 3));

    expect(find.text('Only 3 left'), findsOneWidget);
    expect(find.text('In stock'), findsNothing);
  });

  testWidgets('shows nothing but "In stock" when onlyLeft is null', (
    tester,
  ) async {
    await _pump(tester, const StockBadge(inStock: true, onlyLeft: null));

    expect(find.text('In stock'), findsOneWidget);
    expect(find.textContaining('Only'), findsNothing);
  });

  testWidgets('out of stock never shows a count, even if onlyLeft were set', (
    tester,
  ) async {
    await _pump(tester, const StockBadge(inStock: false, onlyLeft: 2));

    expect(find.text('Out of stock'), findsOneWidget);
    expect(find.textContaining('Only'), findsNothing);
  });
}
