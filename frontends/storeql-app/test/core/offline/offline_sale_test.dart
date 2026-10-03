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

DioException _refused(int status, String code) => DioException(
      requestOptions: RequestOptions(path: '/orders'),
      type: DioExceptionType.badResponse,
      response: Response(
        requestOptions: RequestOptions(path: '/orders'),
        statusCode: status,
        data: {'code': code, 'detail': 'refused', 'status': status},
      ),
    );

/// Who was signed in at the till when a sale was made: a UUIDv7, as iam-svc mints them.
const _cashier = '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c4d60';

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
      expect(isPermanentRejection(_refused(409, 'IDEMPOTENCY_CONFLICT')),
          isFalse);
    });

    test('but a replay the server still refuses over a recall or a scale is '
        'parked, not retried: within its grace order-svc records the sale and '
        'flags it for a manager, so these come back only for a sale older than '
        'the grace, judged as made now — the same answer on every attempt, and '
        'retrying would hold up every sale queued behind it', () {
      expect(isPermanentRejection(_refused(409, 'ORDER_LINE_RECALLED')),
          isTrue);
      expect(isPermanentRejection(_refused(409, 'ORDER_SCALE_NOT_CERTIFIED')),
          isTrue);
    });

    test('and so is a card tender payment-svc will not link to the payment its '
        'card machine took: the sale given up, the approval recorded already, '
        'put back, or not that sale\'s — no retry changes the answer, and '
        'taken for "try again" it would hold up every sale queued behind it',
        () {
      for (final code in [
        'PAYMENT_ORDER_GIVEN_UP',
        'TERMINAL_ATTEMPT_ALREADY_RECORDED',
        'TERMINAL_ATTEMPT_REFUNDED',
        'TERMINAL_AMOUNT_MISMATCH',
        'TERMINAL_WRONG_STORE',
        'TERMINAL_NOT_APPROVED',
        'TERMINAL_ATTEMPT_OTHER_ORDER',
        'TERMINAL_NOT_A_SALE',
      ]) {
        expect(isPermanentRejection(_refused(409, code)), isTrue, reason: code);
      }
      // A press of the same key still going is asked again.
      expect(isPermanentRejection(_refused(409, 'TERMINAL_REQUEST_IN_FLIGHT')),
          isFalse);
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

    test('the capture time is written in UTC and reads back as the same moment, '
        'with who rang the sale up', () {
      // Whatever zone the till is set to, the stored text is the instant, so
      // a change of zone or clock setting while the sale waits changes nothing.
      final local = DateTime.utc(2026, 9, 8, 11, 30).toLocal();
      final sale = OfflineSale(
        id: 'pos-1700000123456',
        capturedAt: local,
        rungUpBy: _cashier,
        storeId: 'store-1',
        currency: 'GBP',
        orderRequest: const {'storeId': 'store-1'},
        tenders: const [],
        total: 5.0,
        itemCount: 1,
      );
      final json = sale.toJson();
      expect(json['capturedAt'], '2026-09-08T11:30:00.000Z');

      final back = OfflineSale.fromJson(json);
      expect(back.capturedAt, DateTime.utc(2026, 9, 8, 11, 30));
      expect(back.capturedAt!.isAtSameMomentAs(local), isTrue);
      expect(back.rungUpBy, _cashier);
    });

    test('a capture time an older build stored in local time still reads', () {
      final legacy = OfflineSale.fromJson({
        'id': 'pos-1700000123456',
        'capturedAt': '2026-09-08T12:30:00.000',
        'storeId': 'store-1',
        'currency': 'GBP',
        'orderRequest': const {'storeId': 'store-1'},
        'tenders': const [],
        'total': 5.0,
        'itemCount': 1,
      });
      expect(legacy.capturedAt, DateTime(2026, 9, 8, 12, 30));
      expect(legacy.rungUpBy, isNull, reason: 'that build recorded nobody');
    });

    test('a capture time that cannot be read stays unknown, never now', () {
      // Now would put an invented moment on the audit trail as the till's
      // word; unknown is sent as none, and the server judges the sale as made
      // now instead.
      Map<String, dynamic> stored(Object? capturedAt) => {
            'id': 'pos-1700000123456',
            'capturedAt': ?capturedAt,
            'storeId': 'store-1',
            'currency': 'GBP',
            'orderRequest': const {'storeId': 'store-1'},
            'tenders': const [],
            'total': 5.0,
            'itemCount': 1,
          };
      final spoiled = OfflineSale.fromJson(stored('last Tuesday'));
      expect(spoiled.capturedAt, isNull);
      expect(OfflineSale.fromJson(stored(null)).capturedAt, isNull);
      expect(spoiled.toJson()['capturedAt'], isNull,
          reason: 'and it stays unknown across a restart');
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

    test('a gift-card tender is complete once redeemed: the redeem is the tender', () {
      const redeemedOnly = OfflineTender(
          body: {'method': 'GIFT_CARD'},
          amount: 5.0,
          giftCardCode: 'GC-1',
          redeemDone: true);
      expect(redeemedOnly.isComplete, isTrue,
          reason: 'payment-svc records the tender from the redemption');
      const other = OfflineTender(body: {'method': 'CASH'}, amount: 5.0);
      expect(other.isComplete, isFalse);
      expect(other.copyWith(tenderDone: true).isComplete, isTrue);
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
