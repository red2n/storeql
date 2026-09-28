import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/shared/widgets/bottom_action_bar.dart';

BoxDecoration _decoration(WidgetTester tester) => tester
    .widget<DecoratedBox>(find
        .descendant(of: find.byType(BottomActionBar), matching: find.byType(DecoratedBox))
        .first)
    .decoration as BoxDecoration;

Future<void> _pump(WidgetTester tester, BottomActionBar bar) => tester.pumpWidget(MaterialApp(
      theme: AppTheme.light,
      home: Scaffold(body: const SizedBox.expand(), bottomNavigationBar: bar),
    ));

void main() {
  testWidgets('a plain bar reads as fixed by a hairline on the page colour, no shadow', (tester) async {
    await _pump(tester, const BottomActionBar(child: Text('Charge')));
    final d = _decoration(tester);
    final cs = AppTheme.light.colorScheme;
    expect(d.color, cs.surface);
    expect((d.border as Border).top.color, cs.outlineVariant);
    expect(d.boxShadow, isNull);
  });

  testWidgets('a raised bar floats on shadow-2 instead of the hairline', (tester) async {
    await _pump(tester, const BottomActionBar(raised: true, child: Text('Checkout')));
    final d = _decoration(tester);
    expect(d.color, AppTheme.light.colorScheme.surface);
    expect(d.border, isNull);
    expect(d.boxShadow, AppShadow.level2);
  });

  test('shadow-2 is the design system token: 0 2px 6px 2px @ 8%, 0 1px 2px @ 12%', () {
    expect(AppShadow.level2, const [
      BoxShadow(offset: Offset(0, 2), blurRadius: 6, spreadRadius: 2, color: Color(0x14000000)),
      BoxShadow(offset: Offset(0, 1), blurRadius: 2, color: Color(0x1F000000)),
    ]);
  });
}
