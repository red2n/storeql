import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/ids.dart';
import 'package:storeql_app/core/storage/app_storage.dart';
import 'package:storeql_app/features/admin/customer_providers.dart';
import 'package:storeql_app/features/pos/held_card_payment.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_terminal.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Settling a card payment the till left at the machine (07.16): what the
// attempts against the held order come to, read without starting anything.
// ---------------------------------------------------------------------------

const _sale = HeldSale(
  storeId: 'store-1',
  lines: [],
  settlement: null,
  customer: null,
  walkInPhone: '',
  discount: 0,
  discountReason: '',
  tenders: [],
);

HeldCardPayment _held({String? orderId = 'order-1'}) => HeldCardPayment(
    base: 'base', signature: 'sig', orderId: orderId, sale: _sale);

TerminalOutcome _a(String id, String state,
        {String kind = 'SALE',
        String amount = '12.00',
        String? refundOf,
        String? decided,
        String? terminalId,
        String? paymentId}) =>
    TerminalOutcome(id: id, state: state, kind: kind, amount: amount,
        currency: 'GBP', refundOf: refundOf, decided: decided,
        terminalId: terminalId, paymentId: paymentId);

class _MemStorage implements AppStorage {
  final Map<String, String> data = {};
  bool unreadable = false;
  bool unwritable = false;

  @override
  Future<String?> read({required String key}) async {
    if (unreadable) throw StateError('locked');
    return data[key];
  }

  @override
  Future<void> write({required String key, required String? value}) async {
    if (unwritable) throw StateError('full');
    if (value == null) {
      data.remove(key);
    } else {
      data[key] = value;
    }
  }

  @override
  Future<void> delete({required String key}) async => data.remove(key);

  @override
  Future<void> deleteAll({Set<String> keep = const {}}) async =>
      data.removeWhere((k, _) => !keep.contains(k));
}

/// A held sale with something in every field, to be written and read back.
HeldCardPayment _full() => HeldCardPayment(
      base: '0190a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b',
      saleId: 'sale-1',
      tenantId: 'tenant-1',
      signature: 'sig',
      orderSignature: 'order-sig',
      tenderPrints: const ['p0', 'p1', null],
      fixed: 2,
      termTries: const {1: 2},
      approved: const {0},
      paid: const {0},
      attemptIds: const {0: 'att-1', 1: 'att-3'},
      orderId: 'order-1',
      sale: HeldSale(
        storeId: 'store-1',
        lines: [
          PosLine(
            variantId: 'v-cheese',
            sku: 'CHEESE',
            name: 'Cheddar',
            qty: 0.375,
            unitPrice: 12.4,
            currency: 'GBP',
            soldBy: 'WEIGHT',
            unit: 'kg',
            weighingInstrumentId: 'scale-1',
            markdownId: 'md-1',
            originalPrice: 15.0,
            batchNo: 'L42',
            expiry: DateTime.utc(2026, 11, 3),
            depositMaterial: 'GLASS',
            depositVolumeMl: 750,
            depositEach: 0.2,
          ),
          PosLine.giftCardSale(amount: 20, currency: 'GBP', code: 'GC-1'),
        ],
        settlement: const PosExchangeSettlement(
            orderId: 'order-0', due: 4.5, currency: 'GBP', lines: []),
        customer: const Customer(
            id: 'cust-1',
            email: 'a@example.com',
            phone: '+447700900000',
            firstName: 'Ada',
            lastName: 'Lovelace',
            status: 'ACTIVE'),
        walkInPhone: '07700900000',
        discount: 1.5,
        discountReason: 'Dented tin',
        tenders: const [
          PosTender(method: 'CARD', amount: 6, terminalId: 'term-1',
              terminalReceiptLine: 'VISA ****4242'),
          PosTender(method: 'CASH', amount: 5, cashGiven: 10),
          PosTender(method: 'GIFT_CARD', amount: 1, giftCardCode: 'GC-9'),
        ],
      ),
    );

/// Answers the attempts read with [attempts] (or fails as a lost network) and
/// each cancel with [cancelled].
class _Server implements HttpClientAdapter {
  _Server({
    this.attempts = '[]',
    this.cancelled = 'CANCELLED',
    this.payments = '[]',
  });

  String attempts;
  final String cancelled;
  final String payments;
  bool offline = false;
  int? refundStatus;

  /// What a refund the machine is asked for answers, and how long it takes.
  String refundState = 'APPROVED';
  Duration refundTakes = Duration.zero;

  /// What each read of one attempt finds, the last repeating.
  List<String> reads = const ['REQUESTED'];
  int _read = 0;
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (offline) {
      throw DioException(
          requestOptions: o, type: DioExceptionType.connectionError);
    }
    if (o.method == 'GET' && o.path.contains('/terminal/by-order/')) {
      return jsonResponse('{"data":$attempts}');
    }
    if (o.method == 'POST' && o.path.endsWith('/cancel')) {
      final id = o.path.split('/').reversed.skip(1).first;
      return jsonResponse(
          '{"data":{"id":"$id","state":"$cancelled","kind":"SALE"}}');
    }
    if (o.method == 'POST' && o.path.endsWith('/refunds')) {
      if (refundStatus != null) {
        return jsonResponse(
            '{"error":{"code":"TERMINAL_RETIRED","message":"retired"}}',
            refundStatus!);
      }
      final id = o.path.split('/').reversed.skip(1).first;
      final amount = (o.data as Map)['amount'];
      if (refundTakes > Duration.zero) await Future<void>.delayed(refundTakes);
      return jsonResponse('{"data":{"id":"ref-${requests.length}",'
          '"state":"$refundState","kind":"REFUND","refundOf":"$id",'
          '"amount":"$amount","currency":"GBP"}}', 201);
    }
    if (o.method == 'GET' &&
        RegExp(r'/payments/terminal/[^/]+$').hasMatch(o.path)) {
      final id = o.path.split('/').last;
      final state = reads[_read < reads.length ? _read++ : reads.length - 1];
      return jsonResponse(
          '{"data":{"id":"$id","state":"$state","kind":"SALE"}}');
    }
    if (o.method == 'GET' && o.path.contains('/payments/by-order/')) {
      return jsonResponse('{"data":$payments}');
    }
    return jsonResponse('{"data":{}}', 404);
  }
}

Dio _dio(_Server s) =>
    Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = s;

void main() {
  group('what a held payment comes to', () {
    test('a payment still at the machine wins over everything', () {
      expect(
          HeldCardCheck.of([_a('1', 'APPROVED'), _a('2', 'REQUESTED')]).state,
          HeldCardState.atMachine);
    });

    test('then a card taken, then one that may have been', () {
      expect(HeldCardCheck.of([_a('1', 'TIMED_OUT'), _a('2', 'APPROVED')]).state,
          HeldCardState.taken);
      expect(HeldCardCheck.of([_a('1', 'DECLINED'), _a('2', 'TIMED_OUT')]).state,
          HeldCardState.mayBeTaken);
    });

    test('only payments that took nothing free the till', () {
      expect(
          HeldCardCheck.of([
            _a('1', 'DECLINED'),
            _a('2', 'CANCELLED'),
            _a('3', 'FAILED'),
          ]).state,
          HeldCardState.settled);
      expect(HeldCardCheck.of(const []).state, HeldCardState.settled);
    });

    test('a refund against the order is not the sale\'s card payment', () {
      final check = HeldCardCheck.of(
          [_a('1', 'DECLINED'), _a('2', 'REQUESTED', kind: 'REFUND')]);
      expect(check.state, HeldCardState.settled);
      expect(check.attempts.map((a) => a.id), ['1']);
    });
  });

  group('reading it', () {
    test('an order never placed sent no card anywhere: settled, nothing asked',
        () async {
      final server = _Server();
      final check = await checkHeldCardPayment(_dio(server), _held(orderId: null));
      expect(check.state, HeldCardState.settled);
      expect(server.requests, isEmpty);
    });

    test('the attempts against the held order are read, never started',
        () async {
      final server = _Server(
          attempts: '[{"id":"att-1","state":"REQUESTED","kind":"SALE",'
              '"amount":"12.00","currency":"GBP"}]');
      final check = await checkHeldCardPayment(_dio(server), _held());
      expect(check.state, HeldCardState.atMachine);
      expect(check.atMachine.single.amount, '12.00');
      final read = server.requests.single;
      expect(read.method, 'GET');
      expect(read.path, endsWith('/payments/terminal/by-order/order-1'));
      expect(read.headers.containsKey('Idempotency-Key'), isFalse);
    });

    test('a read that fails is unknown, never "nothing there"', () async {
      final server = _Server()..offline = true;
      final check = await checkHeldCardPayment(_dio(server), _held());
      expect(check.state, HeldCardState.unknown);
    });
  });

  group('cancelling it on the machine', () {
    test('cancels only what is still at the machine, and reads the result',
        () async {
      final server = _Server(cancelled: 'CANCELLED');
      final after = await cancelHeldAtMachine(_dio(server),
          HeldCardCheck.of([_a('att-0', 'DECLINED'), _a('att-1', 'REQUESTED')]));
      expect(server.requests.map((r) => r.path),
          [endsWith('/payments/terminal/att-1/cancel')]);
      expect(after.state, HeldCardState.settled);
    });

    test('a cancel that loses the race to an approval is a card taken',
        () async {
      final server = _Server(cancelled: 'APPROVED');
      final after = await cancelHeldAtMachine(
          _dio(server), HeldCardCheck.of([_a('att-1', 'REQUESTED')]));
      expect(after.state, HeldCardState.taken);
    });

    test('a cancel settles nothing: the attempt is read until the machine '
        'answers it', () async {
      final server = _Server(cancelled: 'REQUESTED')
        ..reads = ['REQUESTED', 'CANCELLED'];
      final after = await cancelHeldAtMachine(
          _dio(server), HeldCardCheck.of([_a('att-1', 'REQUESTED')]),
          wait: const TerminalWait(
              every: Duration.zero, limit: Duration(seconds: 5)));
      expect(after.state, HeldCardState.settled);
      expect(
          server.requests
              .where((r) => r.method == 'GET')
              .map((r) => r.path),
          [endsWith('/payments/terminal/att-1'), endsWith('/payments/terminal/att-1')]);
    });

    test('one the machine has not answered when the wait is up is still at '
        'the machine, never "cancelled"', () async {
      final server = _Server(cancelled: 'REQUESTED')..reads = ['REQUESTED'];
      final after = await cancelHeldAtMachine(
          _dio(server), HeldCardCheck.of([_a('att-1', 'REQUESTED')]),
          wait: const TerminalWait(
              every: Duration(milliseconds: 5),
              limit: Duration(milliseconds: 30)));
      expect(after.state, HeldCardState.atMachine);
    });

    test('a cancel that cannot be sent leaves the payment unknown', () async {
      final server = _Server()..offline = true;
      final after = await cancelHeldAtMachine(
          _dio(server), HeldCardCheck.of([_a('att-1', 'REQUESTED')]));
      expect(after.state, HeldCardState.unknown);
    });
  });

  group('a timed-out payment a manager recorded', () {
    test('is what they said the machine shows: taken, or nothing taken', () {
      expect(
          HeldCardCheck.of([_a('1', 'TIMED_OUT', decided: 'APPROVED')]).state,
          HeldCardState.taken);
      expect(
          HeldCardCheck.of([_a('1', 'TIMED_OUT', decided: 'NOT_TAKEN')]).state,
          HeldCardState.settled);
      expect(HeldCardCheck.of([_a('1', 'TIMED_OUT')]).state,
          HeldCardState.mayBeTaken,
          reason: 'with nobody\'s word on it, it may have been taken');
    });

    test('a refund recorded as gone back counts as back on the card', () {
      final check = HeldCardCheck.of([
        _a('att-1', 'APPROVED'),
        _a('ref-1', 'TIMED_OUT',
            kind: 'REFUND', refundOf: 'att-1', decided: 'APPROVED'),
      ]);
      expect(check.state, HeldCardState.settled);
    });
  });

  group('which place of the held sale an approval is', () {
    const twoCards = HeldSale(
      storeId: 'store-1',
      lines: [],
      settlement: null,
      customer: null,
      walkInPhone: '',
      discount: 0,
      discountReason: '',
      tenders: [
        PosTender(method: 'CASH', amount: 2),
        PosTender(method: 'CARD', amount: 6, terminalId: 'term-1'),
        PosTender(method: 'CARD', amount: 4, terminalId: 'term-1'),
      ],
    );

    test('the place the till heard it at; else the one place it sent a card '
        'from and never heard, for exactly that amount on that machine', () {
      const held = HeldCardPayment(
        base: 'base',
        signature: 'sig',
        orderId: 'order-1',
        sale: twoCards,
        fixed: 3,
        attemptIds: {1: 'att-1'},
      );
      final found = placesOfTaken(
          held,
          HeldCardCheck.of([
            _a('att-1', 'APPROVED', amount: '6.00'),
            _a('att-2', 'APPROVED', amount: '4.00', terminalId: 'term-1'),
          ]));
      expect(found, {1: 'att-1', 2: 'att-2'});
      expect(held.withTaken(found).approved, {1, 2});
      expect(held.withTaken(found).attemptIds, {1: 'att-1', 2: 'att-2'});
    });

    test('never a place it was not sent from, another amount, another '
        'machine, or one already recorded as taken', () {
      const held = HeldCardPayment(
        base: 'base',
        signature: 'sig',
        orderId: 'order-1',
        sale: twoCards,
        fixed: 2,
        approved: {1},
        attemptIds: {1: 'att-1'},
      );
      expect(
          placesOfTaken(
              held,
              HeldCardCheck.of([
                _a('att-1', 'APPROVED', amount: '6.00'),
                _a('att-2', 'APPROVED', amount: '4.00'),
              ])),
          isEmpty);
      const unheard = HeldCardPayment(
          base: 'base',
          signature: 'sig',
          orderId: 'order-1',
          sale: twoCards,
          fixed: 3);
      expect(
          placesOfTaken(
              unheard,
              HeldCardCheck.of([
                _a('att-9', 'APPROVED', amount: '5.00'),
                _a('att-8', 'APPROVED', amount: '4.00', terminalId: 'term-2'),
              ])),
          isEmpty);
    });

    test('a press reads the newest attempt no other place has, for exactly '
        'its amount on its machine', () {
      final attempts = [
        _a('att-1', 'DECLINED', amount: '6.00', terminalId: 'term-1'),
        _a('att-2', 'APPROVED', amount: '4.00', terminalId: 'term-1'),
        _a('att-3', 'REQUESTED', amount: '6.00', terminalId: 'term-1'),
        _a('ref-1', 'REQUESTED', kind: 'REFUND', amount: '6.00'),
      ];
      expect(
          attemptOfPlace(attempts,
                  place: 1,
                  terminalId: 'term-1',
                  amount: 6,
                  currency: 'GBP',
                  attemptIds: const {2: 'att-2'})
              ?.id,
          'att-3');
      expect(
          attemptOfPlace(attempts,
              place: 1,
              terminalId: 'term-2',
              amount: 6,
              currency: 'GBP',
              attemptIds: const {}),
          isNull);
    });
  });

  group('what the earlier press recorded stays with its order', () {
    // £8 on a gift card, £4 on the card machine, £2 in cash: the gift card is
    // redeemed by order-svc (no payment posted), the cash posted.
    const giftCardThenCard = HeldSale(
      storeId: 'store-1',
      lines: [],
      settlement: null,
      customer: null,
      walkInPhone: '',
      discount: 0,
      discountReason: '',
      tenders: [
        PosTender(method: 'GIFT_CARD', amount: 8, giftCardCode: 'GC-1'),
        PosTender(method: 'CARD', amount: 4, terminalId: 'term-1'),
        PosTender(method: 'CASH', amount: 2),
      ],
    );
    HeldCardPayment hold(
            {int fixed = 2,
            Set<int> paid = const {0},
            Set<int> approved = const {},
            Map<int, int> tries = const {},
            Map<int, String> attempts = const {1: 'att-1'},
            String? orderId = 'order-1'}) =>
        HeldCardPayment(
          base: 'base',
          signature: 'sig',
          orderId: orderId,
          sale: giftCardThenCard,
          fixed: fixed,
          paid: paid,
          approved: approved,
          termTries: tries,
          attemptIds: attempts,
        );

    test('every tender sent but a card on a machine may be recorded on the '
        'order; nothing is, without one', () {
      expect(hold(paid: const {}).mayBeRecorded, {0},
          reason: 'a gift card sent may have been redeemed, its answer lost');
      expect(hold(fixed: 3, paid: const {}).mayBeRecorded, {0, 2});
      expect(hold(fixed: 2, paid: const {0, 1}).mayBeRecorded, {0, 1},
          reason: 'a card recorded is a tender on the order');
      expect(hold(fixed: 0, paid: const {}).mayBeRecorded, isEmpty);
      expect(hold(orderId: null).mayBeRecorded, isEmpty,
          reason: 'an order never placed has nothing recorded on it');
    });

    test('once its cards took nothing, the places after what the order keeps '
        'reopen, each card under a key of its own', () {
      final check = HeldCardCheck.of([_a('att-1', 'CANCELLED', amount: '4.00')]);
      final reopened = hold().reopenedAfter(check);
      expect(reopened.fixed, 1, reason: 'the gift card redeemed stays');
      expect(reopened.paid, {0});
      expect(reopened.termTries, {1: 1},
          reason: 'the cancelled attempt\'s key would only hand it back');
      expect(reopened.attemptIds, isEmpty);
      expect(reopened.base, 'base');
      expect(reopened.orderId, 'order-1');
      // Nothing to reopen: kept as it is.
      expect(hold(fixed: 1, attempts: const {}).reopenedAfter(check).fixed, 1);
    });

    test('a card taken stays; one put back in full, unrecorded, reopens', () {
      const twoCards = HeldSale(
        storeId: 'store-1',
        lines: [],
        settlement: null,
        customer: null,
        walkInPhone: '',
        discount: 0,
        discountReason: '',
        tenders: [
          PosTender(method: 'CASH', amount: 2),
          PosTender(method: 'CARD', amount: 6, terminalId: 'term-1'),
          PosTender(method: 'CARD', amount: 4, terminalId: 'term-1'),
        ],
      );
      const held = HeldCardPayment(
        base: 'base',
        saleId: 'sale-a',
        signature: 'sig',
        orderSignature: 'order',
        tenderPrints: ['cash-2', 'card-6', 'card-4'],
        orderId: 'order-1',
        sale: twoCards,
        fixed: 3,
        paid: {0},
        approved: {1},
        attemptIds: {1: 'att-1', 2: 'att-2'},
      );
      final stillTaken = held.reopenedAfter(HeldCardCheck.of([
        _a('att-1', 'APPROVED', amount: '6.00'),
        _a('att-2', 'DECLINED', amount: '4.00'),
      ]));
      expect(stillTaken.fixed, 2);
      expect(stillTaken.approved, {1});
      expect(stillTaken.termTries, {2: 1});
      final putBack = held.reopenedAfter(HeldCardCheck.of([
        _a('att-1', 'APPROVED', amount: '6.00'),
        _a('att-2', 'DECLINED', amount: '4.00'),
        _a('ref-1', 'APPROVED',
            kind: 'REFUND', refundOf: 'att-1', amount: '6.00'),
      ]));
      expect(putBack.fixed, 1);
      expect(putBack.approved, isEmpty);
      expect(putBack.termTries, {1: 1, 2: 1});
      expect(putBack.attemptIds, isEmpty);
      // Both reopened places go under keys never sent, so the same sale and
      // order carry on with the cash, whatever comes after it.
      expect(putBack.freed, {1, 2});
      expect(putBack.carriesOn('sale-a', 'order', ['cash-2', 'cash-10']),
          isTrue);
      expect(
          HeldCardPayment.fromJson(
                  jsonDecode(jsonEncode(putBack.toJson())) as Map<String, dynamic>)
              .freed,
          {1, 2});
    });

    test('an approval read recorded on its sale marks its place recorded', () {
      final held = hold(fixed: 2, paid: const {}, approved: const {1});
      final check = HeldCardCheck.of(
          [_a('att-1', 'APPROVED', amount: '4.00', paymentId: 'pay-b')]);
      expect(placesRecorded(held, check), {1});
      expect(held.withRecorded({1}).paid, {1});
      expect(
          placesRecorded(held,
              HeldCardCheck.of([_a('att-1', 'APPROVED', amount: '4.00')])),
          isEmpty);
      expect(placesRecorded(hold(paid: const {1}, approved: const {1}), check),
          isEmpty,
          reason: 'already recorded in the hold');
    });

    test('a card taken recorded in the hold lies before what it fixed', () {
      final held = hold(fixed: 1, attempts: const {});
      final taken = held.withTaken({1: 'att-1'});
      expect(taken.approved, {1});
      expect(taken.attemptIds, {1: 'att-1'});
      expect(taken.fixed, 2);
    });
  });

  group('which place of this till\'s hold an approval on its order is', () {
    const sale = HeldSale(
      storeId: 'store-1',
      lines: [],
      settlement: null,
      customer: null,
      walkInPhone: '',
      discount: 0,
      discountReason: '',
      tenders: [
        PosTender(method: 'CASH', amount: 6),
        PosTender(method: 'CARD', amount: 6, terminalId: 'term-1'),
      ],
    );
    const held = HeldCardPayment(
      base: 'base',
      signature: 'sig',
      orderId: 'order-1',
      sale: sale,
      fixed: 1,
      paid: {0},
      termTries: {1: 1},
    );

    test('the place it was heard at; else a card on its machine for exactly '
        'its amount, not taken nor recorded — past what the hold fixed too', () {
      expect(
          placeOfApproval(
              held,
              _a('att-1', 'APPROVED',
                  amount: '6.00', terminalId: 'term-1')),
          1,
          reason: 'a late approval after "nothing taken" reopened its place');
      expect(
          placeOfApproval(
              const HeldCardPayment(
                  base: 'base',
                  signature: 'sig',
                  orderId: 'order-1',
                  sale: sale,
                  fixed: 2,
                  attemptIds: {1: 'att-7'}),
              _a('att-7', 'APPROVED', amount: '6.00')),
          1);
    });

    test('never another amount, another machine, a place taken or recorded',
        () {
      expect(placeOfApproval(held, _a('att-1', 'APPROVED', amount: '5.00')),
          isNull);
      expect(
          placeOfApproval(held,
              _a('att-1', 'APPROVED', amount: '6.00', terminalId: 'term-2')),
          isNull);
      expect(
          placeOfApproval(
              const HeldCardPayment(
                  base: 'base',
                  signature: 'sig',
                  orderId: 'order-1',
                  sale: sale,
                  fixed: 2,
                  paid: {0, 1}),
              _a('att-1', 'APPROVED', amount: '6.00')),
          isNull);
      expect(
          placeOfApproval(
              const HeldCardPayment(
                  base: 'base',
                  signature: 'sig',
                  orderId: 'order-1',
                  sale: sale,
                  fixed: 2,
                  approved: {1},
                  attemptIds: {1: 'att-2'}),
              _a('att-1', 'APPROVED', amount: '6.00')),
          isNull);
    });
  });

  group('a card put back on the machine', () {
    test('a card whose whole amount came back took nothing', () {
      final check = HeldCardCheck.of([
        _a('att-1', 'APPROVED'),
        _a('ref-1', 'APPROVED', kind: 'REFUND', refundOf: 'att-1'),
      ]);
      expect(check.state, HeldCardState.settled);
      expect(check.taken, isEmpty);
    });

    test('part of it back is still a card taken, for the rest', () {
      final check = HeldCardCheck.of([
        _a('att-1', 'APPROVED'),
        _a('ref-1', 'APPROVED',
            kind: 'REFUND', refundOf: 'att-1', amount: '5.00'),
      ]);
      expect(check.state, HeldCardState.taken);
      expect(check.outstanding(check.taken.single), 700, reason: 'pence');
    });

    test('a refund declined, still on the machine or timed out puts nothing '
        'back', () {
      for (final state in ['DECLINED', 'REQUESTED', 'TIMED_OUT']) {
        final check = HeldCardCheck.of([
          _a('att-1', 'APPROVED'),
          _a('ref-1', state, kind: 'REFUND', refundOf: 'att-1'),
        ]);
        expect(check.state, HeldCardState.taken, reason: state);
        expect(check.reversalUnsettled(check.taken.single),
            state != 'DECLINED', reason: state);
      }
    });

    test('a refund of another card puts nothing back on this one', () {
      final check = HeldCardCheck.of([
        _a('att-1', 'APPROVED'),
        _a('ref-1', 'APPROVED', kind: 'REFUND', refundOf: 'att-9'),
      ]);
      expect(check.state, HeldCardState.taken);
    });

    test('the refund goes to the attempt that took the card, for what it has '
        'not had back, as money, under a key of the held sale; then the '
        'attempts are read again', () async {
      final server = _Server(
          attempts: '[{"id":"att-1","state":"APPROVED","kind":"SALE",'
              '"amount":"12.00","currency":"GBP"},'
              '{"id":"ref-0","state":"APPROVED","kind":"REFUND",'
              '"refundOf":"att-1","amount":"12.00","currency":"GBP"}]');
      final before = HeldCardCheck.of([
        _a('att-1', 'APPROVED'),
        _a('ref-x', 'APPROVED',
            kind: 'REFUND', refundOf: 'att-1', amount: '2.50'),
      ]);
      final after = await reverseHeldOnMachine(_dio(server), _held(), before,
          reason: 'Wrong size');

      final refund = server.requests
          .where((r) => r.method == 'POST' && r.path.endsWith('/refunds'))
          .single;
      expect(refund.path, '/payment-svc/payments/terminal/att-1/refunds');
      // The manager's reason goes with it: payment-svc keeps it on the refund.
      expect(refund.data, {'amount': '9.50', 'reason': 'Wrong size'});
      expect(refund.headers['Idempotency-Key'],
          derivedId('base', 'reverse:att-1'));
      expect(server.requests.last.path, endsWith('/terminal/by-order/order-1'));
      expect(after.state, HeldCardState.settled);
    });

    test('a refund the machine declined is tried again under a key of its own',
        () async {
      final server = _Server();
      await reverseHeldOnMachine(
          _dio(server),
          _held(),
          HeldCardCheck.of([
            _a('att-1', 'APPROVED'),
            _a('ref-1', 'DECLINED', kind: 'REFUND', refundOf: 'att-1'),
          ]),
          reason: 'r');
      final refund = server.requests
          .where((r) => r.path.endsWith('/refunds'))
          .single;
      expect(refund.headers['Idempotency-Key'],
          derivedId('base', 'reverse:att-1:1'));
    });

    test('a refund still on the machine or timed out is never sent again',
        () async {
      final server = _Server();
      await reverseHeldOnMachine(
          _dio(server),
          _held(),
          HeldCardCheck.of([
            _a('att-1', 'APPROVED'),
            _a('ref-1', 'TIMED_OUT', kind: 'REFUND', refundOf: 'att-1'),
          ]),
          reason: 'r');
      expect(server.requests.where((r) => r.path.endsWith('/refunds')), isEmpty);
    });

    test('a refusal is thrown as it came; a refund that cannot be sent is '
        'unknown', () async {
      final refused = _Server()..refundStatus = 409;
      await expectLater(
          reverseHeldOnMachine(_dio(refused), _held(),
              HeldCardCheck.of([_a('att-1', 'APPROVED')]),
              reason: 'r'),
          throwsA(isA<DioException>()));
      final offline = _Server()..offline = true;
      final after = await reverseHeldOnMachine(_dio(offline), _held(),
          HeldCardCheck.of([_a('att-1', 'APPROVED')]),
          reason: 'r');
      expect(after.state, HeldCardState.unknown);
    });

    test('one wait for the lot: a refund still at the machine when the time '
        'is up sends no further refund', () async {
      final server = _Server()
        ..refundState = 'REQUESTED'
        ..reads = ['REQUESTED'];
      const wait = TerminalWait(
          every: Duration(milliseconds: 5), limit: Duration(milliseconds: 60));
      await reverseHeldOnMachine(
          _dio(server),
          _held(),
          HeldCardCheck.of([
            _a('att-1', 'APPROVED', amount: '6.00'),
            _a('att-2', 'APPROVED', amount: '6.00'),
          ]),
          reason: 'r',
          wait: wait);
      final refunds =
          server.requests.where((r) => r.path.endsWith('/refunds')).toList();
      expect(refunds, hasLength(1),
          reason: 'the second is not sent on a fresh wait of its own');
      expect(refunds.single.receiveTimeout,
          lessThanOrEqualTo(const Duration(milliseconds: 60)),
          reason: 'the ask never outlasts the time left');
    });

    test('the wait for a refund still at the machine counts the time its ask '
        'took: it never starts again', () async {
      final server = _Server()
        ..refundState = 'REQUESTED'
        ..refundTakes = const Duration(milliseconds: 200)
        ..reads = ['REQUESTED'];
      const wait = TerminalWait(
          every: Duration(milliseconds: 10), limit: Duration(milliseconds: 300));
      final took = Stopwatch()..start();
      await reverseHeldOnMachine(_dio(server), _held(),
          HeldCardCheck.of([_a('att-1', 'APPROVED')]),
          reason: 'r', wait: wait);
      took.stop();
      // 300 ms in all, the ask's 200 among them; a wait started afresh after
      // the ask would take 500.
      expect(took.elapsed, lessThan(const Duration(milliseconds: 430)));
    });

    test('whether a tender is recorded on the order is read, never guessed',
        () async {
      expect(await tendersRecordedOn(_dio(_Server()), 'order-1'), isFalse);
      expect(
          await tendersRecordedOn(
              _dio(_Server(payments: '[{"id":"pay-1"}]')), 'order-1'),
          isTrue);
      expect(await tendersRecordedOn(_dio(_Server()..offline = true), 'order-1'),
          isNull);
    });
  });

  group('which press carries a held sale on', () {
    const held = HeldCardPayment(
      base: 'base',
      saleId: 'sale-a',
      tenantId: 't',
      signature: 'all',
      orderSignature: 'order',
      tenderPrints: ['card-6', 'card-6b'],
      fixed: 1,
      orderId: 'order-1',
      sale: _sale,
    );

    test('the same order with the tenders the server acted on unchanged', () {
      expect(held.carriesOn('sale-a', 'order', ['card-6', 'card-6b']), isTrue);
      expect(held.carriesOn('sale-a', 'order', ['card-6', 'cash-6']), isTrue,
          reason: 'a tender after the fixed ones never reached the server');
    });

    test('never another order, nor a changed tender the server acted on', () {
      expect(held.carriesOn('sale-a', 'order-2', ['card-6', 'card-6b']),
          isFalse);
      expect(held.carriesOn('sale-a', 'order', ['cash-6', 'card-6b']), isFalse);
      expect(held.carriesOn('sale-a', 'order', const []), isFalse);
      expect(held.carriesOn('sale-a', null, ['card-6']), isFalse);
    });

    test('never another sale, however like it: the next customer buying the '
        'same thing is not carried on under these keys', () {
      expect(held.carriesOn('sale-b', 'order', ['card-6', 'card-6b']), isFalse);
      expect(held.carriesOn(null, 'order', ['card-6', 'card-6b']), isFalse);
      const unnamed = HeldCardPayment(
        base: 'base',
        signature: 'all',
        orderSignature: 'order',
        tenderPrints: ['card-6'],
        fixed: 1,
        orderId: 'order-1',
        sale: _sale,
      );
      expect(unnamed.carriesOn(null, 'order', ['card-6']), isFalse,
          reason: 'a hold that cannot say which sale it is carries none on');
    });

    test('a hold is only the business\'s that made it', () {
      expect(held.belongsTo('t'), isTrue);
      expect(held.belongsTo('sandbox'), isFalse);
      expect(held.belongsTo(null), isFalse);
      expect(_held().belongsTo('t'), isFalse,
          reason: 'a hold that names no business is no business\'s');
    });

    test('never a hold whose taken, recorded or declined tenders lie past what '
        'it fixed', () {
      HeldCardPayment withFixed(int fixed,
              {Set<int> approved = const {},
              Set<int> paid = const {},
              Map<int, int> tries = const {}}) =>
          HeldCardPayment(
            base: 'base',
            saleId: 'sale-a',
            signature: 'all',
            orderSignature: 'order',
            tenderPrints: const ['card-6', 'card-6b'],
            fixed: fixed,
            approved: approved,
            paid: paid,
            termTries: tries,
            orderId: 'order-1',
            sale: _sale,
          );
      bool on(HeldCardPayment h, List<String?> prints) =>
          h.carriesOn('sale-a', 'order', prints);
      // A card taken is a place the server acted on: it lies before [fixed].
      expect(on(withFixed(0, approved: {1}), ['x', 'y']), isFalse);
      expect(on(withFixed(1, approved: {1}), ['card-6', 'y']), isFalse);
      expect(on(withFixed(2, approved: {1}), ['card-6', 'card-6b']), isTrue);
      // So does a tender recorded.
      expect(on(withFixed(1, paid: {1}), ['card-6', 'y']), isFalse);
      expect(on(withFixed(2, paid: {1}), ['card-6', 'card-6b']), isTrue);
      // A declined card frees its own place, and no later one.
      expect(on(withFixed(1, tries: {1: 1}), ['card-6', 'y']), isTrue,
          reason: 'the declined card swapped for cash carries the sale on');
      expect(on(withFixed(0, tries: {1: 1}), ['x', 'y']), isFalse);
    });

    test('a card tried again after a decline goes under a key of its own', () {
      expect(terminalKey('base', 0, 0), derivedId('base', 'term:0'));
      expect(terminalKey('base', 0, 1), derivedId('base', 'term:0:1'));
      expect(terminalKey('base', 0, 1), isNot(terminalKey('base', 0, 0)));
    });
  });

  group('one press at a time', () {
    test('a press starts only once the one before it has stopped, and one '
        'kept waiting too long starts nothing', () async {
      final till = HeldCardPaymentNotifier(storage: _MemStorage());
      final endA = (await till.claimPress())!;
      var bStarted = false;
      final b = till.claimPress().then((end) {
        bStarted = true;
        return end;
      });
      await Future<void>.delayed(const Duration(milliseconds: 10));
      expect(bStarted, isFalse, reason: 'A has not stopped');

      endA();
      final endB = await b;
      expect(endB, isNotNull);
      endA(); // A ending again does not end B's claim
      expect(
          await till.claimPress(patience: const Duration(milliseconds: 20)),
          isNull,
          reason: 'B has not stopped: C starts nothing');

      endB!();
      expect(await till.claimPress(), isNotNull);
    });
  });

  group('signing out', () {
    // Sign-out wipes the device for the next person, but a card payment at the
    // machine is the till's, not the cashier's: a restart after a shift change
    // must still find it, or the next press sends a second amount.
    for (final (how, signOut) in <(String, Future<void> Function(AuthNotifier))>[
      ('a sign-out', (a) => a.logout()),
      ('a session lost on the way back from the sandbox',
          (a) => a.leaveSandbox()),
    ]) {
      test('$how keeps the held card payment on the device', () async {
        final container = ProviderContainer();
        addTearDown(container.dispose);
        await container.read(authNotifierProvider.future);
        const device = AppStorage();
        final till = HeldCardPaymentNotifier(storage: device);
        await till.ready;
        await till.hold(_full());
        await device.write(
            key: heldCardPaymentCorruptStorageKey, value: '{set aside');
        await device.write(
            key: heldCardPaymentLetGoStorageKey, value: '["order-gone"]');

        await signOut(container.read(authNotifierProvider.notifier));

        final restarted = HeldCardPaymentNotifier(storage: device);
        await restarted.ready;
        expect(restarted.wasLetGo('order-gone'), isTrue,
            reason: 'what the till let go is the till\'s, not the cashier\'s');
        expect(restarted.current?.base, _full().base);
        expect(await device.read(key: heldCardPaymentCorruptStorageKey),
            '{set aside');
      });
    }
  });

  group('kept on the device', () {
    test('everything needed to put the sale back is written and read back',
        () {
      final held = _full();
      final back = HeldCardPayment.fromJson(
          jsonDecode(jsonEncode(held.toJson())) as Map<String, dynamic>);
      expect(jsonEncode(back.toJson()), jsonEncode(held.toJson()));
      expect(back.sale.lines.first.expiry, DateTime.utc(2026, 11, 3));
      expect(back.sale.customer!.fullName, 'Ada Lovelace');
      expect(back.sale.tenders.first.terminalId, 'term-1');
      expect(back.termTries, {1: 2});
      expect(back.approved, {0});
      expect(back.paid, {0});
      expect(back.attemptIds, {0: 'att-1', 1: 'att-3'});
      expect(back.saleId, 'sale-1');
      expect(back.tenantId, 'tenant-1');
    });

    test('a hold is written before it is answered, and a till that starts '
        'again reads it back', () async {
      final device = _MemStorage();
      final first = HeldCardPaymentNotifier(storage: device);
      await first.ready;
      await first.hold(_full());
      expect(device.data[heldCardPaymentStorageKey], isNotNull);

      final restarted = HeldCardPaymentNotifier(storage: device);
      expect(restarted.current, isNull, reason: 'not read yet');
      await restarted.ready;
      expect(restarted.current?.base, _full().base);
      expect(restarted.current?.orderId, 'order-1');

      await restarted.release();
      expect(device.data.containsKey(heldCardPaymentStorageKey), isFalse);
      final again = HeldCardPaymentNotifier(storage: device);
      await again.ready;
      expect(again.current, isNull);
    });

    test('a hold set while the device is still being read is not overwritten '
        'by the older one', () async {
      final device = _MemStorage();
      device.data[heldCardPaymentStorageKey] =
          jsonEncode(_held(orderId: 'old').toJson());
      final notifier = HeldCardPaymentNotifier(storage: device);
      await notifier.hold(_held(orderId: 'new'));
      await notifier.ready;
      expect(notifier.current?.orderId, 'new');
    });

    test('a hold that cannot be read is set aside and the till still opens',
        () async {
      final device = _MemStorage();
      device.data[heldCardPaymentStorageKey] = '{not json';
      final notifier = HeldCardPaymentNotifier(storage: device);
      await notifier.ready;
      expect(notifier.current, isNull);
      expect(device.data[heldCardPaymentCorruptStorageKey], '{not json');

      final locked = _MemStorage()..unreadable = true;
      final opens = HeldCardPaymentNotifier(storage: locked);
      await opens.ready;
      expect(opens.current, isNull);
    });

    test('while one is set aside the till says so, after a restart too, until '
        'a manager clears it', () async {
      final device = _MemStorage();
      device.data[heldCardPaymentStorageKey] = '{not json';
      final first = HeldCardPaymentNotifier(storage: device);
      await first.ready;
      expect(first.corrupt.value, isTrue);

      final restarted = HeldCardPaymentNotifier(storage: device);
      await restarted.ready;
      expect(restarted.corrupt.value, isTrue,
          reason: 'set aside and not cleared');

      await restarted.clearCorrupt();
      expect(restarted.corrupt.value, isFalse);
      expect(device.data.containsKey(heldCardPaymentCorruptStorageKey), isFalse);
      final after = HeldCardPaymentNotifier(storage: device);
      await after.ready;
      expect(after.corrupt.value, isFalse);

      // A device that cannot be read at all may hold one.
      final locked = HeldCardPaymentNotifier(
          storage: _MemStorage()..unreadable = true);
      await locked.ready;
      expect(locked.corrupt.value, isTrue);
    });

    test('a hold let go unfinished leaves its order remembered on the device, '
        'and a till that starts again still knows it', () async {
      final device = _MemStorage();
      final till = HeldCardPaymentNotifier(storage: device);
      await till.ready;
      expect(till.wasLetGo('order-1'), isFalse);
      await till.hold(_held());
      await till.releaseUnfinished('order-1');

      expect(till.current, isNull, reason: 'the hold is gone');
      expect(device.data.containsKey(heldCardPaymentStorageKey), isFalse);
      expect(till.wasLetGo('order-1'), isTrue);
      expect(till.wasLetGo('order-2'), isFalse);
      expect(jsonDecode(device.data[heldCardPaymentLetGoStorageKey]!),
          ['order-1']);

      final restarted = HeldCardPaymentNotifier(storage: device);
      await restarted.ready;
      expect(restarted.wasLetGo('order-1'), isTrue);
      // One let go after the restart is added to those, not written over them.
      await restarted.hold(_held(orderId: 'order-7'));
      await restarted.releaseUnfinished('order-7');
      expect(jsonDecode(device.data[heldCardPaymentLetGoStorageKey]!),
          ['order-1', 'order-7']);
    });

    test('a sale that completes, or a hold with no order placed, leaves '
        'nothing remembered', () async {
      final device = _MemStorage();
      final till = HeldCardPaymentNotifier(storage: device);
      await till.ready;
      await till.hold(_held());
      await till.release();
      expect(till.wasLetGo('order-1'), isFalse);

      await till.hold(_held(orderId: null));
      await till.releaseUnfinished(null);
      expect(till.current, isNull);
      expect(device.data.containsKey(heldCardPaymentLetGoStorageKey), isFalse);
    });

    test('only so many are remembered, the oldest forgotten first; the device '
        'refusing the list still lets the hold go, remembered while the app '
        'runs; and a list that cannot be read never puts the card machine out '
        'of use', () async {
      final device = _MemStorage();
      final till = HeldCardPaymentNotifier(storage: device);
      await till.ready;
      for (var i = 0; i <= HeldCardPaymentNotifier.letGoRemembered; i++) {
        await till.releaseUnfinished('order-$i');
      }
      expect(till.wasLetGo('order-0'), isFalse);
      expect(till.wasLetGo('order-1'), isTrue);
      expect(
          till.wasLetGo('order-${HeldCardPaymentNotifier.letGoRemembered}'),
          isTrue);

      device.unwritable = true;
      await till.hold(_held(orderId: 'order-x'));
      await till.releaseUnfinished('order-x');
      expect(till.current, isNull);
      expect(till.wasLetGo('order-x'), isTrue);

      final garbled = _MemStorage()
        ..data[heldCardPaymentLetGoStorageKey] = '{not a list';
      final opens = HeldCardPaymentNotifier(storage: garbled);
      await opens.ready;
      expect(opens.corrupt.value, isFalse);
      expect(opens.wasLetGo('order-1'), isFalse);
    });

    test('a hold the device would not take says so', () async {
      final device = _MemStorage();
      final notifier = HeldCardPaymentNotifier(storage: device);
      await notifier.ready;
      expect(await notifier.hold(_full()), isTrue);
      device.unwritable = true;
      expect(await notifier.hold(_held()), isFalse);
      expect(notifier.current?.base, 'base',
          reason: 'still held on this till while it runs');
    });
  });
}
