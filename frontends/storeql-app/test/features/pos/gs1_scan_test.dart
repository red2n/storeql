import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/pos/pos_recall_check.dart';

// ---------------------------------------------------------------------------
// What a 2D code changes at the till (07.15).
//
// A linear barcode names a variant. A GS1 DataMatrix or Digital Link QR names
// the pack: its lot and its expiry. That difference decides a recall.
//
// Before, a recall scoped to one lot could only be handled by asking the cashier
// to read the jar — for every pack of that line, including the thousands that are
// not the recalled lot. With the pack identifying itself the till stops the
// recalled lot and sells the rest, and the cases below are the ones where getting
// it wrong is expensive in one direction or dangerous in the other: condemning
// good stock, or letting recalled food through.
// ---------------------------------------------------------------------------

ActiveRecallItem _recall({
  String variantId = 'v-1',
  String? batchNo,
  DateTime? from,
  DateTime? to,
}) =>
    ActiveRecallItem(
      recallId: 'r-1',
      reference: 'FSA-2026-1',
      kind: 'RECALL',
      hazard: 'ALLERGEN',
      variantId: variantId,
      batchNo: batchNo,
      expiryFrom: from,
      expiryTo: to,
    );

void main() {
  group('a recall scoped to a lot, and a pack that says which lot it is', () {
    final lotRecall = _recall(batchNo: 'LOT-7');

    test('the recalled lot is stopped', () {
      expect(
        checkRecall('v-1', [lotRecall], batchNo: 'LOT-7'),
        isA<RecallBlocked>(),
      );
    });

    test('the recalled lot printed another way is still the recalled lot', () {
      // Trimmed and case aside, as inventory-svc and order-svc compare it, so
      // the till never sells a pack the server would refuse at the tender.
      expect(
        checkRecall('v-1', [lotRecall], batchNo: ' lot-7 '),
        isA<RecallBlocked>(),
      );
    });

    test('another lot of the same line goes on selling', () {
      // The expensive half. Without the pack's lot every jar of this line needed
      // a cashier to read it, which is why this row is worth having.
      expect(
        checkRecall('v-1', [lotRecall], batchNo: 'LOT-8'),
        isA<RecallClear>(),
      );
    });

    test('a pack that carried no lot still goes to the cashier', () {
      // The dangerous half. A linear barcode says nothing about the pack, and
      // guessing "probably not the recalled one" would sell recalled food.
      expect(checkRecall('v-1', [lotRecall]), isA<RecallCheckPack>());
    });
  });

  group('a recall scoped to expiry dates', () {
    final window = _recall(
      from: DateTime.utc(2026, 12, 1),
      to: DateTime.utc(2026, 12, 31),
    );

    test('a pack expiring inside the window is stopped', () {
      expect(
        checkRecall('v-1', [window], expiry: DateTime.utc(2026, 12, 15)),
        isA<RecallBlocked>(),
      );
    });

    test('the edges of the window are inside it', () {
      // A recall that names dates means those dates. Excluding either end would
      // leave the first and last day's stock on sale.
      expect(
        checkRecall('v-1', [window], expiry: DateTime.utc(2026, 12, 1)),
        isA<RecallBlocked>(),
      );
      expect(
        checkRecall('v-1', [window], expiry: DateTime.utc(2026, 12, 31)),
        isA<RecallBlocked>(),
      );
    });

    test('a pack expiring outside it is not this recall', () {
      expect(
        checkRecall('v-1', [window], expiry: DateTime.utc(2027, 1, 1)),
        isA<RecallClear>(),
      );
      expect(
        checkRecall('v-1', [window], expiry: DateTime.utc(2026, 11, 30)),
        isA<RecallClear>(),
      );
    });

    test('a pack with a lot but no expiry cannot answer a date-scoped recall', () {
      // It identified itself, but not in the terms this recall is written in. That
      // is not a clearance — it goes to the cashier.
      expect(
        checkRecall('v-1', [window], batchNo: 'LOT-7'),
        isA<RecallCheckPack>(),
      );
    });
  });

  group('a recall naming both a lot and dates', () {
    final both = _recall(
      batchNo: 'LOT-7',
      from: DateTime.utc(2026, 12, 1),
      to: DateTime.utc(2026, 12, 31),
    );

    test('both must agree before the pack is stopped', () {
      expect(
        checkRecall('v-1', [both],
            batchNo: 'LOT-7', expiry: DateTime.utc(2026, 12, 15)),
        isA<RecallBlocked>(),
      );
      expect(
        checkRecall('v-1', [both],
            batchNo: 'LOT-7', expiry: DateTime.utc(2027, 6, 1)),
        isA<RecallClear>(),
        reason: 'the right lot, the wrong date — not the recalled stock',
      );
      expect(
        checkRecall('v-1', [both],
            batchNo: 'LOT-9', expiry: DateTime.utc(2026, 12, 15)),
        isA<RecallClear>(),
        reason: 'the right date, the wrong lot',
      );
    });
  });

  group('what the pack cannot argue with', () {
    test('a recall covering every pack blocks whatever the code says', () {
      // No scope at all means every pack. A lot number on the packet is not an
      // argument against that, and a 2D code must not become a way round it.
      final every = _recall();
      expect(
        checkRecall('v-1', [every], batchNo: 'LOT-8'),
        isA<RecallBlocked>(),
      );
      expect(
        checkRecall('v-1', [every, _recall(batchNo: 'LOT-7')],
            batchNo: 'LOT-8'),
        isA<RecallBlocked>(),
      );
    });

    test('another line is untouched', () {
      expect(
        checkRecall('v-2', [_recall(batchNo: 'LOT-7')], batchNo: 'LOT-7'),
        isA<RecallClear>(),
      );
    });

    test('two lot-scoped recalls: the pack matching either one is stopped', () {
      final two = [_recall(batchNo: 'LOT-7'), _recall(batchNo: 'LOT-9')];
      expect(checkRecall('v-1', two, batchNo: 'LOT-9'), isA<RecallBlocked>());
      expect(checkRecall('v-1', two, batchNo: 'LOT-8'), isA<RecallClear>());
    });
  });
}
