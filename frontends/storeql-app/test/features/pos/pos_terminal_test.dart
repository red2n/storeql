import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/pos/pos_terminal.dart';

// ---------------------------------------------------------------------------
// Taking a card on an EMV terminal (07.16).
//
// The four outcomes are the point. They look similar in a response body and mean
// entirely different things about money:
//
//   APPROVED   money taken — record the tender.
//   DECLINED   nothing taken — ask for another tender.
//   CANCELLED  nothing taken — same.
//   TIMED_OUT  money MAY have been taken — record nothing, retry nothing, and
//              make sure a human looks at the terminal.
//
// A till that collapses the last one into "declined" loses a taking; one that
// collapses it into "approved" claims money it cannot prove it has. Both are the
// kind of error a customer notices on their statement, so each is asserted here.
// ---------------------------------------------------------------------------

class _Till implements HttpClientAdapter {
  final List<RequestOptions> calls = [];
  String body = '{"data":{"id":"a-1","state":"APPROVED","scheme":"VISA",'
      '"panLast4":"4242","authCode":"012345","receiptLine":"VISA DEBIT ****4242 (CHIP, PIN)"}}';
  int status = 201;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    calls.add(o);
    return ResponseBody.fromString(body, status,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Dio _dio(HttpClientAdapter server) =>
    Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;

Future<TerminalOutcome> _take(_Till till, {String key = 'sale-1-term0'}) =>
    takeCardOnTerminal(
      _dio(till),
      terminalId: 't-1',
      orderId: 'o-1',
      amount: 12.5,
      currency: 'GBP',
      idempotencyKey: key,
    );

/// The till's clock in a test: it moves only when the test's server says an
/// answer took time.
class _Clock {
  DateTime t = DateTime.utc(2026, 10, 2, 9);
  DateTime now() => t;
}

/// A server that answers each request with the next scripted reply and repeats
/// the last. A reply that is a [DioExceptionType] is thrown as the network would.
/// Each answer moves [clock] on by [perCall], as the time it took.
class _Script implements HttpClientAdapter {
  final List<Object> replies;
  final List<RequestOptions> calls = [];
  final _Clock? clock;
  final Duration perCall;
  int _next = 0;

  _Script(this.replies,
      {this.clock, this.perCall = const Duration(seconds: 1)});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    calls.add(o);
    final clock = this.clock;
    if (clock != null) clock.t = clock.t.add(perCall);
    final reply = replies[_next < replies.length ? _next++ : replies.length - 1];
    if (reply is DioExceptionType) {
      throw DioException(requestOptions: o, type: reply);
    }
    return ResponseBody.fromString(reply as String, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

/// The attempt `a-1` in [state], as payment-svc answers a read of it.
String _attempt(String state, {String? detail, String? line}) => jsonEncode({
      'data': {
        'id': 'a-1',
        'state': state,
        'outcomeDetail': ?detail,
        'receiptLine': ?line,
      },
    });

/// The shortest wait there is: no pause and four seconds, each answer from a
/// [_Script] on a [_Clock] taking one, so a test is exact.
const _quick = TerminalWait(every: Duration.zero, limit: Duration(seconds: 4));

const _pending = TerminalOutcome(id: 'a-1', state: 'REQUESTED');

void main() {
  group('what is sent to the terminal', () {
    test('an amount as money, a currency, and the key that stops a double charge',
        () async {
      final till = _Till();
      await _take(till);

      final sent = till.calls.single;
      expect(sent.path, '/payment-svc/payments/terminal');
      expect(sent.method, 'POST');
      final data = sent.data as Map<String, dynamic>;
      expect(data['terminalId'], 't-1');
      expect(data['orderId'], 'o-1');
      // Two decimal places as a string: the service refuses a third rather than
      // rounding somebody else's money, and a JSON number is a double by the time
      // a browser has parsed it.
      expect(data['amount'], '12.50');
      expect(data['currency'], 'GBP');
      // The key is the whole guard. Without it a second press is a second EMV
      // transaction on a real card.
      expect(sent.headers['Idempotency-Key'], 'sale-1-term0');
    });

    test('the amount is what the sale owes, in the currency\'s own minor units '
        '(ISO 4217), never rounded onto the card', () {
      expect(terminalAmount(12.5, 'GBP'), '12.50');
      expect(terminalAmount(129.99, 'RSD'), '129.99');
      expect(terminalAmount(1200, 'JPY'), '1200');
      // Three-decimal dinars: a figure that fits two is sent with two (the
      // terminal call takes at most two); one that does not is sent as it is,
      // to be refused, rather than "1.13" taken for a sale of 1.125.
      expect(terminalAmount(1.12, 'IQD'), '1.12');
      expect(terminalAmount(3.1, 'KWD'), '3.10');
      expect(terminalAmount(3, 'KWD'), '3.00');
      expect(terminalAmount(1.125, 'IQD'), '1.125');
      expect(terminalAmount(1.125, 'IQD'), isNot('1.13'));
    });

    test('a dinar amount reaches the terminal call unrounded', () async {
      final till = _Till();
      await takeCardOnTerminal(
        _dio(till),
        terminalId: 't-1',
        orderId: 'o-1',
        amount: 1.125,
        currency: 'IQD',
        idempotencyKey: 'sale-1-term0',
      );
      expect((till.calls.single.data as Map<String, dynamic>)['amount'],
          '1.125');
    });

    test('no field carries a card number, because the terminal reads the card',
        () async {
      final till = _Till();
      await _take(till);
      final data = (till.calls.single.data as Map<String, dynamic>).keys.toSet();
      expect(data, {'terminalId', 'orderId', 'amount', 'currency'});
    });
  });

  group('what the terminal said', () {
    test('an approval carries the receipt line the card needs', () async {
      final outcome = await _take(_Till());
      expect(outcome.approved, isTrue);
      expect(outcome.uncertain, isFalse);
      expect(outcome.receiptLine, 'VISA DEBIT ****4242 (CHIP, PIN)');
      expect(outcome.panLast4, '4242');
      expect(outcome.authCode, '012345');
      expect(outcome.message, 'Approved');
    });

    test('a decline shows the terminal\'s own words, not just "declined"',
        () async {
      // A decline with no reason sends a cashier to ring the bank.
      final till = _Till()
        ..body = '{"data":{"id":"a-2","state":"DECLINED",'
            '"outcomeDetail":"DECLINED — insufficient funds"}}';
      final outcome = await _take(till);
      expect(outcome.approved, isFalse);
      expect(outcome.uncertain, isFalse);
      expect(outcome.message, 'DECLINED — insufficient funds');
      expect(outcome.receiptLine, isNull, reason: 'nothing was taken');
    });

    test('a cancellation is not an approval', () async {
      final till = _Till()
        ..body = '{"data":{"id":"a-3","state":"CANCELLED",'
            '"outcomeDetail":"Cancelled at the terminal"}}';
      final outcome = await _take(till);
      expect(outcome.approved, isFalse);
      expect(outcome.uncertain, isFalse);
    });

    test('a timeout is neither approved nor merely declined', () async {
      // The one that matters most. The card may have been charged, so the till
      // must not record a taking and must not quietly try again.
      final till = _Till()
        ..body = '{"data":{"id":"a-4","state":"TIMED_OUT",'
            '"outcomeDetail":"No answer from the terminal"}}';
      final outcome = await _take(till);
      expect(outcome.approved, isFalse);
      expect(outcome.uncertain, isTrue);
      expect(outcome.message, contains('may have been charged'));
      expect(outcome.message, contains('check the terminal'));
    });

    test('an unrecognised state is treated as a failure, never as an approval',
        () async {
      // A new vendor, a typo, a truncated body: the safe reading of "I do not
      // know what this means" is that no money was taken.
      final till = _Till()..body = '{"data":{"id":"a-5","state":"SOMETHING_NEW"}}';
      final outcome = await _take(till);
      expect(outcome.approved, isFalse);
      expect(outcome.message, isNotEmpty);
    });

    test('a body with no state at all is a failure', () async {
      final till = _Till()..body = '{"data":{"id":"a-6"}}';
      final outcome = await _take(till);
      expect(outcome.state, 'FAILED');
      expect(outcome.approved, isFalse);
    });
  });

  group('a card still at the machine', () {
    test('a REQUESTED answer is pending: not approved, not uncertain, and not a failure',
        () async {
      // What payment-svc answers (201) a repeated press of the same payment while
      // the first is still waiting on the cardholder. It has not failed: the
      // machine has the amount and has not said.
      final outcome = await _take(_Till()..body = _attempt('REQUESTED'));
      expect(outcome.pending, isTrue);
      expect(outcome.approved, isFalse);
      expect(outcome.uncertain, isFalse);
      expect(outcome.receiptLine, isNull, reason: 'nothing has been taken yet');
      expect(outcome.message, 'Waiting for the card machine…');
      expect(outcome.message, isNot(contains('could not be reached')),
          reason: 'the till used to read this as an unreachable machine');
    });

    test('no other state reads as pending', () {
      for (final state in [
        'APPROVED',
        'DECLINED',
        'CANCELLED',
        'TIMED_OUT',
        'FAILED',
        'SOMETHING_NEW',
      ]) {
        expect(TerminalOutcome(id: 'a', state: state).pending, isFalse,
            reason: state);
      }
    });

    test('the wait stops with the attempt still pending, never as a refusal', () {
      // Thrown when the machine has still not said: nothing was refused and
      // nothing went wrong, so it is not the exception for a refused card.
      const stillPending = TerminalStillPending(_pending);
      expect(stillPending, isNot(isA<TerminalNotApproved>()));
      expect(stillPending.outcome.pending, isTrue);
      expect(stillPending.toString(), 'Waiting for the card machine…');
    });
  });

  group('waiting for the card machine', () {
    test('reads the attempt by its id until it settles, and starts nothing',
        () async {
      final machine = _Script([
        _attempt('REQUESTED'),
        _attempt('REQUESTED'),
        _attempt('APPROVED', line: 'VISA DEBIT ****4242 (CHIP, PIN)'),
      ]);
      final outcome = await awaitTerminalOutcome(
          _dio(machine), _pending,
          wait: _quick);

      expect(outcome.approved, isTrue);
      expect(outcome.receiptLine, 'VISA DEBIT ****4242 (CHIP, PIN)');
      expect(machine.calls, hasLength(3), reason: 'it stops asking once settled');
      for (final call in machine.calls) {
        expect(call.method, 'GET');
        expect(call.path, '/payment-svc/payments/terminal/a-1');
        // A read starts nothing, so it carries no key: it cannot start a second
        // payment on the card.
        expect(call.headers.containsKey('Idempotency-Key'), isFalse);
      }
    });

    test('a decline read during the wait is a decline in the terminal\'s own words',
        () async {
      final machine = _Script([
        _attempt('REQUESTED'),
        _attempt('DECLINED', detail: 'DECLINED — insufficient funds'),
      ]);
      final outcome =
          await awaitTerminalOutcome(_dio(machine), _pending, wait: _quick);

      expect(outcome.pending, isFalse);
      expect(outcome.approved, isFalse);
      expect(outcome.message, 'DECLINED — insufficient funds');
    });

    test('a timeout read during the wait is still the uncertain one', () async {
      final outcome = await awaitTerminalOutcome(
          _dio(_Script([_attempt('TIMED_OUT')])), _pending,
          wait: _quick);
      expect(outcome.uncertain, isTrue);
      expect(outcome.pending, isFalse);
    });

    test('the wait runs out when its time does, with the attempt still pending',
        () async {
      final clock = _Clock();
      final machine = _Script([_attempt('REQUESTED')], clock: clock);
      final outcome = await awaitTerminalOutcome(_dio(machine), _pending,
          wait: const TerminalWait(
              every: Duration.zero, limit: Duration(seconds: 3)),
          now: clock.now);

      expect(outcome.pending, isTrue,
          reason: 'it stops waiting; it does not decide the payment failed');
      expect(machine.calls, hasLength(3), reason: 'a second each, three seconds');
    });

    test('a read that fails is no news, not a failure', () async {
      // The attempt is still at the machine, so a blink in the network during
      // the wait must neither fail the card nor send the sale to the queue.
      final machine = _Script([
        DioExceptionType.connectionError,
        DioExceptionType.receiveTimeout,
        _attempt('REQUESTED'),
        _attempt('APPROVED'),
      ]);
      final outcome =
          await awaitTerminalOutcome(_dio(machine), _pending, wait: _quick);

      expect(outcome.approved, isTrue);
      expect(machine.calls, hasLength(4));
    });

    test('it stops asking when nobody is waiting any more', () async {
      final machine = _Script([_attempt('REQUESTED')]);
      final outcome = await awaitTerminalOutcome(_dio(machine), _pending,
          wait: _quick, abandoned: () => true);

      expect(outcome.pending, isTrue);
      expect(machine.calls, isEmpty);
    });

    test('an answer with no id leaves nothing to ask after', () async {
      final machine = _Script([_attempt('APPROVED')]);
      const noId = TerminalOutcome(id: '', state: 'REQUESTED');
      final outcome =
          await awaitTerminalOutcome(_dio(machine), noId, wait: _quick);

      expect(outcome.pending, isTrue);
      expect(machine.calls, isEmpty);
    });

    test('the default wait is generous but never open-ended: ninety seconds by '
        'the clock, asked every two', () {
      const wait = TerminalWait();
      expect(wait.every, const Duration(seconds: 2));
      expect(wait.limit, const Duration(seconds: 90));
    });
  });

  // payment-svc settles an attempt before it answers the press that started it,
  // so a cardholder slow over a PIN outlasts the till's own receive timeout.
  // That timeout is not the till being offline: the amount is at the machine.
  // The till asks again under the same key — payment-svc hands back the attempt
  // it already has, REQUESTED or settled, and never asks the machine twice —
  // and waits for it. A press it never hears back from is "no answer yet",
  // never a refusal and never a sale queued as paid by card.
  group('a press the till heard nothing back from', () {
    Future<TerminalOutcome> press(HttpClientAdapter server,
            {TerminalWait wait = _quick,
            bool Function()? abandoned,
            _Clock? clock}) =>
        takeCardAndWait(
          _dio(server),
          terminalId: 't-1',
          orderId: 'o-1',
          amount: 12.5,
          currency: 'GBP',
          idempotencyKey: 'sale-1-term0',
          wait: wait,
          now: (clock ?? (server is _Script ? server.clock : null))?.now ??
              DateTime.now,
          abandoned: abandoned,
        );

    List<RequestOptions> posts(_Script s) =>
        s.calls.where((c) => c.method == 'POST').toList();

    test('a press that times out is asked again under the same key, then the '
        'attempt is read until it settles', () async {
      final machine = _Script([
        DioExceptionType.receiveTimeout,
        _attempt('REQUESTED'),
        _attempt('REQUESTED'),
        _attempt('APPROVED', line: 'VISA DEBIT ****4242 (CHIP, PIN)'),
      ]);
      final outcome = await press(machine);

      expect(outcome.approved, isTrue);
      expect(outcome.receiptLine, 'VISA DEBIT ****4242 (CHIP, PIN)');
      expect([for (final c in machine.calls) c.method],
          ['POST', 'POST', 'GET', 'GET']);
      for (final p in posts(machine)) {
        expect(p.path, '/payment-svc/payments/terminal');
        expect(p.headers['Idempotency-Key'], 'sale-1-term0',
            reason: 'one payment: a new key would be a second one on the card');
      }
      expect(machine.calls[2].path, '/payment-svc/payments/terminal/a-1');
    });

    test('an attempt already settled when asked again is the answer', () async {
      final machine = _Script([
        DioExceptionType.receiveTimeout,
        _attempt('DECLINED', detail: 'DECLINED — insufficient funds'),
      ]);
      final outcome = await press(machine);

      expect(outcome.pending, isFalse);
      expect(outcome.message, 'DECLINED — insufficient funds');
      expect(posts(machine), hasLength(2));
    });

    test('a connection lost on the press is no news either, and the same key '
        'is presented again', () async {
      final machine = _Script([
        DioExceptionType.connectionError,
        DioExceptionType.sendTimeout,
        _attempt('APPROVED'),
      ]);
      final outcome = await press(machine);

      expect(outcome.approved, isTrue);
      expect(posts(machine), hasLength(3));
      expect({for (final p in posts(machine)) p.headers['Idempotency-Key']},
          {'sale-1-term0'});
    });

    test('a press never answered runs out as "no answer yet": pending, '
        'unheard, never a refusal', () async {
      final machine =
          _Script([DioExceptionType.receiveTimeout], clock: _Clock());
      final outcome = await press(machine, wait: _quick);

      expect(outcome.pending, isTrue);
      expect(outcome.heard, isFalse);
      expect(outcome.approved, isFalse);
      expect(outcome.uncertain, isFalse);
      expect(posts(machine), hasLength(4),
          reason: 'four seconds, a second an ask: the press and three more');
    });

    test('asking again and reading share one wait, so it is never open-ended',
        () async {
      final machine = _Script([
        DioExceptionType.receiveTimeout,
        _attempt('REQUESTED'),
      ], clock: _Clock());
      final outcome = await press(machine, wait: _quick);

      expect(outcome.pending, isTrue);
      expect(outcome.heard, isTrue, reason: 'the attempt is known, just unsettled');
      // Four seconds: the press, one more press and two reads.
      expect([for (final c in machine.calls) c.method],
          ['POST', 'POST', 'GET', 'GET']);
    });

    test('the wait is ninety seconds by the clock, and each ask\'s own timeout '
        'counts against it', () async {
      // Every press ends on the till's own ten-second receive timeout, and the
      // pause between asks is two: twelve seconds an ask. Counted in asks (45
      // of them) the cashier waited nine minutes; by the clock it is ninety
      // seconds — asks at 0, 12, … 84, and none after.
      final clock = _Clock();
      final machine = _Script([DioExceptionType.receiveTimeout],
          clock: clock, perCall: const Duration(seconds: 12));
      final start = clock.now();
      final outcome = await press(machine,
          wait: const TerminalWait(
              every: Duration.zero, limit: Duration(seconds: 90)),
          clock: clock);

      expect(outcome.heard, isFalse);
      expect(posts(machine), hasLength(8));
      expect(clock.now().difference(start), const Duration(seconds: 96),
          reason: 'the last ask began inside the ninety seconds');
      // And no ask outlasts what is left: the last began at 84 seconds, with
      // six to go, and was sent with a receive timeout of six.
      expect(posts(machine).last.receiveTimeout, const Duration(seconds: 6));
      expect({for (final p in posts(machine)) p.headers['Idempotency-Key']},
          {'sale-1-term0'});
    });

    test('a refusal is an answer, never asked again', () async {
      final till = _Till()
        ..status = 409
        ..body = '{"error":{"code":"TERMINAL_RETIRED","message":"retired"}}';
      await expectLater(press(till), throwsA(isA<DioException>()));
      expect(till.calls, hasLength(1));
    });

    test('it stops asking when nobody is waiting any more', () async {
      final machine = _Script([DioExceptionType.receiveTimeout]);
      final outcome = await press(machine, abandoned: () => true);

      expect(outcome.heard, isFalse);
      expect(posts(machine), hasLength(1));
    });

    test('a press answered at once is the answer, with nothing more asked',
        () async {
      final machine = _Script([_attempt('APPROVED')]);
      final outcome = await press(machine);
      expect(outcome.approved, isTrue);
      expect(machine.calls, hasLength(1));
    });
  });

  group('the exception the till throws on a refused card', () {
    test('carries the outcome, so a timeout can be handled differently', () async {
      const declined =
          TerminalOutcome(id: 'a', state: 'DECLINED', detail: 'No funds');
      const timedOut = TerminalOutcome(id: 'b', state: 'TIMED_OUT');

      expect(const TerminalNotApproved(declined).outcome.uncertain, isFalse);
      expect(const TerminalNotApproved(timedOut).outcome.uncertain, isTrue);
      // The message reaches the cashier through toString, so it must be the
      // terminal's, not the class's name.
      expect(const TerminalNotApproved(declined).toString(), 'No funds');
    });
  });

  group('the devices a till offers', () {
    test('the simulator is marked, so nobody thinks a card was really taken', () {
      const sim = CardTerminalDevice(
          id: 't-1', label: 'Till 1', vendor: 'SIMULATED', storeId: 's-1');
      const real = CardTerminalDevice(
          id: 't-2', label: 'Till 2', vendor: 'VERIFONE', storeId: 's-1');
      expect(sim.simulated, isTrue);
      expect(real.simulated, isFalse);
    });
  });
  group('what a manager recorded for a payment the machine did not answer', () {
    test('approved is a card taken; not taken frees it; neither is uncertain',
        () async {
      final till = _Till()
        ..body = '{"data":{"id":"a-1","state":"TIMED_OUT","standing":'
            '"APPROVED_UNRECORDED","orderId":"o-1","terminalId":"t-1",'
            '"decision":{"outcome":"APPROVED","reason":"seen",'
            '"decidedBy":"u","decidedAt":"2026-10-02T09:00:00Z"}}}';
      final approved = await _take(till);
      expect(approved.approved, isTrue);
      expect(approved.uncertain, isFalse);
      expect(approved.standing, 'APPROVED_UNRECORDED');
      expect(approved.orderId, 'o-1');
      expect(approved.terminalId, 't-1');

      till.body = '{"data":{"id":"a-1","state":"TIMED_OUT",'
          '"decision":{"outcome":"NOT_TAKEN","reason":"seen"}}}';
      final notTaken = await _take(till);
      expect(notTaken.approved, isFalse);
      expect(notTaken.uncertain, isFalse);
      expect(notTaken.notTaken, isTrue);
      expect(notTaken.message, contains('Nothing was taken'));

      till.body = '{"data":{"id":"a-1","state":"TIMED_OUT","decision":null}}';
      expect((await _take(till)).uncertain, isTrue);
    });

    test(
        'the machine\'s own answer arriving after "not taken" stands over it: '
        'approved is money on the card, never "nothing was taken"', () {
      // payment-svc keeps an approval that follows a person's NOT_TAKEN: the
      // attempt then reads APPROVED with the person's word still on it.
      final late = TerminalOutcome.fromJson({
        'id': 'a-1',
        'state': 'APPROVED',
        'standing': 'APPROVED_UNRECORDED',
        'decision': {'outcome': 'NOT_TAKEN', 'reason': 'Screen blank'},
      });
      expect(late.approved, isTrue);
      expect(late.notTaken, isFalse);
      expect(late.uncertain, isFalse);
      expect(late.approvedAfterNotTaken, isTrue);
      expect(late.message, 'Approved');

      // Neither a plain approval, nor one a manager read, is that.
      expect(
          const TerminalOutcome(id: 'a-2', state: 'APPROVED')
              .approvedAfterNotTaken,
          isFalse);
      expect(
          const TerminalOutcome(
                  id: 'a-3', state: 'TIMED_OUT', decided: 'APPROVED')
              .approvedAfterNotTaken,
          isFalse);
      expect(
          const TerminalOutcome(
                  id: 'a-4', state: 'TIMED_OUT', decided: 'NOT_TAKEN')
              .approvedAfterNotTaken,
          isFalse);
    });

    test('is sent as what the machine shows and why, under a key', () async {
      final till = _Till()
        ..status = 200
        ..body = '{"data":{"id":"a-1","state":"TIMED_OUT",'
            '"decision":{"outcome":"NOT_TAKEN","reason":"r"}}}';
      final decided = await settleTerminalAttempt(_dio(till), 'a-1',
          shows: MachineShows.notTaken,
          reason: 'Screen shows it cancelled',
          idempotencyKey: 'k-1');
      final sent = till.calls.single;
      expect(sent.method, 'POST');
      expect(sent.path, '/payment-svc/payments/terminal/a-1/settle');
      expect(sent.data,
          {'outcome': 'NOT_TAKEN', 'reason': 'Screen shows it cancelled'});
      expect(sent.headers['Idempotency-Key'], 'k-1');
      expect(decided.notTaken, isTrue);
    });
  });

  group('putting money back and recording an approval', () {
    test('a refund carries the manager\'s reason, which payment-svc keeps',
        () async {
      final till = _Till()
        ..body = '{"data":{"id":"r-1","state":"APPROVED","kind":"REFUND"}}';
      await refundTerminalAttempt(_dio(till), 'a-1',
          amount: 12, currency: 'GBP', reason: 'Wrong size',
          idempotencyKey: 'k-2');
      expect(till.calls.single.data, {'amount': '12.00', 'reason': 'Wrong size'});
    });

    test('an approval is recorded on its own order as exactly that payment',
        () async {
      final till = _Till()..body = '{"data":{"id":"pay-1"}}';
      await recordTerminalApproval(
          _dio(till),
          const TerminalOutcome(
              id: 'a-9',
              state: 'APPROVED',
              orderId: 'o-9',
              amount: '7.50',
              currency: 'GBP'),
          idempotencyKey: 'k-3');
      final sent = till.calls.single;
      expect(sent.path, '/payment-svc/payments');
      expect(sent.data, {
        'orderId': 'o-9',
        'amount': '7.50',
        'method': 'CARD',
        'currency': 'GBP',
        'terminalPaymentId': 'a-9',
      });
    });
  });

  group('what holds a card machine, as payment-svc refuses it', () {
    DioException refusal(Map<String, dynamic> body) {
      final o = RequestOptions(path: '/payment-svc/payments/terminal');
      return DioException(
          requestOptions: o,
          response: Response(requestOptions: o, data: body, statusCode: 409));
    }

    test('one per detail, a sale and a refund', () {
      final cards = unsettledCardsOf(refusal({
        'error': {
          'code': 'TERMINAL_UNSETTLED_APPROVAL',
          'message': 'held',
          'details': [
            'attemptId=a-1;orderId=o-1;amount=12.00;currency=GBP;onCard=12.00;'
                'state=APPROVED;standing=APPROVED_UNRECORDED;kind=SALE',
            'attemptId=r-1;orderId=o-2;amount=3.00;currency=GBP;onCard=0.00;'
                'state=TIMED_OUT;standing=UNDECIDED;kind=REFUND;refundOf=a-0',
            'nothing to read here',
          ],
        },
      }));
      expect(cards, hasLength(2));
      expect(cards[0].attemptId, 'a-1');
      expect(cards[0].orderId, 'o-1');
      expect(cards[0].onCard, '12.00');
      expect(cards[0].standing, 'APPROVED_UNRECORDED');
      expect(cards[0].isRefund, isFalse);
      expect(cards[1].isRefund, isTrue);
      expect(cards[1].refundOf, 'a-0');
      expect(cards[1].asOutcome.uncertain, isTrue);
      // A timeout a manager recorded as approved holds the machine as an
      // approval: read from the details alone it is one.
      final decided = UnsettledCard.parse(
          'attemptId=a-2;orderId=o-3;amount=5.00;currency=GBP;onCard=5.00;'
          'state=TIMED_OUT;standing=APPROVED_UNRECORDED;kind=SALE')!;
      expect(decided.asOutcome.approved, isTrue);
      expect(decided.asOutcome.uncertain, isFalse);
    });

    test('any other refusal names none', () {
      expect(
          unsettledCardsOf(refusal({
            'error': {'code': 'TERMINAL_RETIRED', 'message': 'retired'},
          })),
          isEmpty);
    });

    test('a settle refused while the machine may still answer says from when',
        () {
      expect(
          decidableFrom(refusal({
            'error': {
              'code': 'TERMINAL_REQUEST_IN_FLIGHT',
              'message': 'in flight',
              'details': ['decidableFrom=2026-10-02T10:15:00Z'],
            },
          })),
          '2026-10-02T10:15:00Z');
    });
  });

  group('a press that shares its time with what comes after it', () {
    test('ends when the time it was given does, and says each ask that went '
        'unanswered', () async {
      final clock = _Clock();
      final machine = _Script([
        DioExceptionType.receiveTimeout,
        DioExceptionType.receiveTimeout,
        _attempt('APPROVED'),
      ], clock: clock);
      var unheard = 0;
      final outcome = await takeCardAndWait(
        _dio(machine),
        terminalId: 't-1',
        orderId: 'o-1',
        amount: 12,
        currency: 'GBP',
        idempotencyKey: 'k',
        wait: _quick,
        now: clock.now,
        until: clock.now().add(const Duration(seconds: 1)),
        onUnheard: () => unheard++,
      );
      expect(outcome.heard, isFalse, reason: 'its second was up after one ask');
      expect(machine.calls, hasLength(1));
      expect(unheard, 1);
    });
  });
}
