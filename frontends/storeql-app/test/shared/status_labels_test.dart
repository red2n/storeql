import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';

void main() {
  group('humanizeCode', () {
    test('turns a code into words, keeping acronyms', () {
      expect(humanizeCode('PARTIALLY_FULFILLED'), 'Partially fulfilled');
      expect(humanizeCode('HMRC_MTD'), 'HMRC MTD');
      expect(humanizeCode('store-credit'), 'Store credit');
      expect(humanizeCode('ACTIVE'), 'Active');
    });

    test('blank in, blank out', () {
      expect(humanizeCode(''), '');
      expect(humanizeCode(null), '');
      expect(humanizeCode('  '), '');
    });
  });

  group('batch material statuses', () {
    test('read as words, the same in the list, the filter and the dialog', () {
      expect(materialStatusLabel('AVAILABLE'), 'Available');
      expect(materialStatusLabel('QUARANTINE'), 'In quarantine');
      expect(materialStatusLabel('HOLD'), 'On hold');
      expect(materialStatusLabel('rejected'), 'Rejected');
      expect(materialStatusLabel('SOMETHING_NEW'), 'Something new');
      expect(batchMaterialStatuses, ['AVAILABLE', 'QUARANTINE', 'HOLD', 'REJECTED']);
    });

    test('green to sell, amber while held back, red once rejected', () {
      expect(materialStatusTone('AVAILABLE'), StatusTone.success);
      expect(materialStatusTone('QUARANTINE'), StatusTone.warning);
      expect(materialStatusTone('HOLD'), StatusTone.warning);
      expect(materialStatusTone('REJECTED'), StatusTone.error);
      expect(materialStatusTone('SOMETHING_NEW'), StatusTone.neutral);
    });
  });

  group('order statuses', () {
    test('read as words everywhere', () {
      expect(orderStatusLabel('PARTIALLY_FULFILLED'), 'Part fulfilled');
      expect(orderStatusLabel('AWAITING_PRICE'), 'Awaiting price');
      expect(orderStatusLabel('pending'), 'Pending');
      expect(orderStatusLabel('PLACED'), 'Pending');
      // A status added later still reads as words, not a constant.
      expect(orderStatusLabel('ON_HOLD'), 'On hold');
    });

    test('take the tone of what they mean', () {
      expect(orderStatusTone('PENDING'), StatusTone.info);
      expect(orderStatusTone('AWAITING_PRICE'), StatusTone.info);
      expect(orderStatusTone('PARTIALLY_FULFILLED'), StatusTone.warning);
      expect(orderStatusTone('FULFILLED'), StatusTone.success);
      expect(orderStatusTone('CANCELLED'), StatusTone.neutral);
      expect(orderStatusTone(null), StatusTone.neutral);
    });

    test('the till is the till', () {
      expect(channelLabel('POS'), 'Till');
      expect(channelLabel('ONLINE'), 'Online');
    });
  });

  testWidgets('a badge fits an unbounded row and ellipsizes in a narrow box',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Column(
          children: [
            Row(children: [StatusBadge.order('PARTIALLY_FULFILLED')]),
            const SizedBox(
              width: 60,
              child: StatusBadge(
                'Pending approval',
                tone: StatusTone.info,
                icon: Icons.schedule,
              ),
            ),
          ],
        ),
      ),
    ));

    expect(tester.takeException(), isNull);
    expect(find.textContaining('Part fulfilled'), findsOneWidget);
    expect(find.textContaining('Pending approval'), findsOneWidget);
  });
}
