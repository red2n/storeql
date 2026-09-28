import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/admin/inventory_warehouse_tabs.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';

const _batch = BatchInfo(
  id: '01890a5d-ac96-774b-bcce-b302099a8057',
  storeId: '01890a5d-ac96-774b-bcce-b302099a8058',
  variantId: '01890a5d-ac96-774b-bcce-b302099a8059',
  batchNo: 'B-7',
  receivedQty: 10,
  remainingQty: 10,
  createdAt: '2026-09-01T10:00:00Z',
  status: 'ACTIVE',
  materialStatus: 'QUARANTINE',
);

void main() {
  testWidgets('the status dialog offers the same words as the Batches badges', (tester) async {
    await tester.pumpWidget(ProviderScope(
      child: MaterialApp(
        home: Scaffold(
          body: Consumer(
            builder: (context, ref, _) => TextButton(
              onPressed: () => showMaterialStatusDialog(context, ref, batch: _batch, onChanged: () {}),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    // The batch's own status, as its badge says it.
    expect(find.text('In quarantine'), findsOneWidget);
    await tester.tap(find.text('In quarantine'));
    await tester.pumpAndSettle();
    for (final words in ['Available', 'In quarantine', 'On hold', 'Rejected']) {
      expect(find.text(words), findsWidgets, reason: words);
    }
    expect(find.text('Quarantine'), findsNothing);
    expect(find.text('Hold'), findsNothing);
  });
}
