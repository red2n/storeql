import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/offline/offline_sale.dart';

// ---------------------------------------------------------------------------
// The queue's whole safety argument rests on two classifications: what may be
// held for later, and what must stop being retried. Getting either wrong is a
// money bug — queue a rejection and the cashier is told a sale went through that
// never will; retry a rejection forever and the queue never drains.
// ---------------------------------------------------------------------------

DioException _network(DioExceptionType type) => DioException(
      requestOptions: RequestOptions(path: '/orders'),
      type: type,
    );

DioException _answered(int status) => DioException(
      requestOptions: RequestOptions(path: '/orders'),
      type: DioExceptionType.badResponse,
      response: Response(
        requestOptions: RequestOptions(path: '/orders'),
        statusCode: status,
      ),
    );

OfflineSale _sale({
  String id = 'pos-1700000123456',
  String? orderId,
  List<OfflineTender>? tenders,
}) =>
    OfflineSale(
      id: id,
      capturedAt: DateTime.utc(2026, 9, 8, 11, 30),
      storeId: 'store-1',
      currency: 'GBP',
      orderRequest: const {'storeId': 'store-1', 'channel': 'POS'},
      tenders: tenders ??
          const [OfflineTender(body: {'method': 'CASH'}, amount: 5.0)],
      total: 5.0,
      itemCount: 1,
      orderId: orderId,
    );

void main() {
  group('isOfflineError — may be queued', () {
    test('every flavour of "did not reach the server" counts', () {
      for (final type in [
        DioExceptionType.connectionError,
        DioExceptionType.connectionTimeout,
        DioExceptionType.sendTimeout,
        DioExceptionType.receiveTimeout,
      ]) {
        expect(isOfflineError(_network(type)), isTrue, reason: '$type');
      }
    });

    test('a dead socket arrives as `unknown` with no response', () {
      expect(isOfflineError(_network(DioExceptionType.unknown)), isTrue);
    });

    test('a server that answered is never "offline", whatever it said', () {
      expect(isOfflineError(_answered(400)), isFalse);
      expect(isOfflineError(_answered(500)), isFalse);
    });

    test('a non-Dio throw is not queued', () {
      expect(isOfflineError(Exception('boom')), isFalse);
    });
  });

  group('isPermanentRejection — stop retrying', () {
    test('a 4xx the server will repeat is permanent', () {
      for (final status in [400, 403, 404, 422]) {
        expect(isPermanentRejection(_answered(status)), isTrue,
            reason: '$status');
      }
    });

    test('401 stays retryable — the token expires while the till is offline', () {
      expect(isPermanentRejection(_answered(401)), isFalse);
    });

    test('409 stays retryable — payment-svc asks the caller to retry into the '
        'replay path on IDEMPOTENCY_CONFLICT', () {
      expect(isPermanentRejection(_answered(409)), isFalse);
    });

    test('rate limiting and server faults stay retryable', () {
      expect(isPermanentRejection(_answered(429)), isFalse);
      expect(isPermanentRejection(_answered(503)), isFalse);
    });

    test('a network failure is not a rejection', () {
      expect(isPermanentRejection(_network(DioExceptionType.connectionError)),
          isFalse);
    });
  });

  group('OfflineSale', () {
    test('round-trips through JSON with its idempotency base intact', () {
      // The id IS the idempotency base. If persistence lost or changed it, every
      // replay would present fresh keys and double-charge.
      final sale = _sale(orderId: 'order-9').copyWith(
        attempts: 3,
        lastError: 'Waiting for the network.',
      );
      final back = OfflineSale.fromJson(sale.toJson());

      expect(back.id, sale.id);
      expect(back.orderId, 'order-9');
      expect(back.attempts, 3);
      expect(back.lastError, 'Waiting for the network.');
      expect(back.capturedAt, sale.capturedAt);
      expect(back.orderRequest, sale.orderRequest);
      expect(back.total, 5.0);
    });

    test('per-tender progress survives persistence', () {
      // Progress is what stops a replay redoing a step that already landed.
      final sale = _sale(tenders: const [
        OfflineTender(
            body: {'method': 'GIFT_CARD'},
            amount: 5.0,
            giftCardCode: 'GC-1',
            tenderDone: true),
      ]);
      final back = OfflineSale.fromJson(sale.toJson());

      expect(back.tenders.single.tenderDone, isTrue);
      expect(back.tenders.single.redeemDone, isFalse);
      expect(back.tenders.single.giftCardCode, 'GC-1');
    });

    test('a failed sale stays failed across a restart', () {
      final failed = _sale().copyWith(status: OfflineSaleStatus.failed);
      expect(OfflineSale.fromJson(failed.toJson()).status,
          OfflineSaleStatus.failed);
    });

    test('markTender advances one step without disturbing the others', () {
      final sale = _sale(tenders: const [
        OfflineTender(body: {'method': 'CASH'}, amount: 2.0),
        OfflineTender(
            body: {'method': 'GIFT_CARD'}, amount: 3.0, giftCardCode: 'GC-1'),
      ]);
      final after = sale.markTender(1, tenderDone: true);

      expect(after.tenders[0].tenderDone, isFalse);
      expect(after.tenders[1].tenderDone, isTrue);
      expect(after.tenders[1].redeemDone, isFalse);
      expect(after.tenders[1].giftCardCode, 'GC-1');
    });

    test('isComplete walks every step, in order', () {
      // A sale is only off the queue once the order, each tender, any gift-card
      // redemption AND the transaction journal have all landed. Each of these
      // being separately trackable is what lets a replay resume rather than redo.
      final tendered = _sale(orderId: 'order-9', tenders: const [
        OfflineTender(
            body: {'method': 'GIFT_CARD'},
            amount: 5.0,
            giftCardCode: 'GC-1',
            tenderDone: true),
      ]);
      expect(tendered.isComplete, isFalse, reason: 'the card is not redeemed');

      final redeemed = tendered.markTender(0, redeemDone: true);
      expect(redeemed.isComplete, isFalse, reason: 'the sale is not journalled');

      expect(redeemed.copyWith(posLogDone: true).isComplete, isTrue);
    });

    test('an older queued sale replays rather than being stuck unjournalled', () {
      // posLogDone defaults to false when absent, so a sale written by a build
      // that predates journalling replays the journal write instead of failing
      // to parse. Re-journalling is a no-op server-side.
      final legacy = OfflineSale.fromJson({
        'id': 'pos-1700000123456',
        'capturedAt': '2026-09-08T11:30:00.000Z',
        'storeId': 'store-1',
        'currency': 'GBP',
        'orderRequest': const {'storeId': 'store-1'},
        'tenders': const [],
        'total': 5.0,
        'itemCount': 1,
        'orderId': 'order-9',
      });
      expect(legacy.posLogDone, isFalse);
      expect(legacy.isComplete, isFalse);
    });

    test('the reference a cashier reads off the receipt is stable', () {
      final sale = _sale(id: 'pos-1700000123456');
      expect(sale.reference, '123456');
      expect(OfflineSale.fromJson(sale.toJson()).reference, '123456');
    });

    test('the reference is upper-cased, as order references are elsewhere', () {
      // A real id is a UUIDv7: its random tail is lower-case hex.
      expect(_sale(id: '01a0c830-0e7a-7b3c-9d2e-5f1a2b7c41ae').reference,
          '7C41AE');
      expect(_sale(id: 'ab12').reference, 'AB12');
    });
  });
}
