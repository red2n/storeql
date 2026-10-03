import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show visibleForTesting;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/offline/offline_sale.dart' show isOfflineError;

// ---------------------------------------------------------------------------
// Taking a card on an EMV terminal (07.16).
//
// A CARD tender used to be recorded because the cashier pressed "Card". The
// platform never asked a terminal whether the card was approved, so a declined
// card and an approved one looked identical in the books.
//
// Now the amount goes to a pinpad and the tender is recorded only if the card
// was actually approved. The outcome decides what the till does next, and the
// four outcomes are genuinely different:
//
//   APPROVED   the tender is recorded and the receipt carries the card line.
//   DECLINED   nothing was taken. The sale stays open for another tender.
//   CANCELLED  somebody pressed cancel. Same as declined for the till.
//   TIMED_OUT  the card MAY have been charged. Never retried automatically:
//              it holds the machine (payment-svc refuses its next card, 409
//              TERMINAL_UNSETTLED_APPROVAL) until a manager reads the machine's
//              own screen and records what it shows — APPROVED or NOT_TAKEN,
//              with a reason (POST /payments/terminal/{id}/settle). The
//              cashier's word alone never settles it. And the machine's own
//              answer, when it comes after a person's NOT_TAKEN, stands over
//              it: an approval then is money on the card for a sale that was
//              told nothing was taken ([TerminalOutcome.approvedAfterNotTaken]).
//
// And one that is not an outcome yet:
//
//   REQUESTED  the amount is at the machine and it has not said. payment-svc
//              settles an attempt before it answers the press that started it,
//              so the till never hears this from its own press: a cardholder
//              slow over a PIN outlasts the till's receive timeout first. That
//              timeout is not the till being offline — the amount is at the
//              machine — so the till asks again under the same Idempotency-Key,
//              and payment-svc hands back the attempt it already has (201):
//              REQUESTED while the first press is still with the cardholder, or
//              the settled attempt. It never asks the machine twice. Nothing has
//              failed, so the till is never told it has. It waits for the
//              machine by reading the attempt until it settles, and never
//              starts a second payment.
//
// A press the till never hears back from at all is "no answer yet": not a
// decline, not a failure, and never a sale queued offline as paid by card.
//
// The card number never reaches this app. The terminal reads the card; the till
// sends an amount and receives a verdict with four digits for the receipt.
// ---------------------------------------------------------------------------

/// A terminal the cashier can send an amount to.
class CardTerminalDevice {
  final String id;
  final String label;
  final String vendor;
  final String storeId;

  const CardTerminalDevice({
    required this.id,
    required this.label,
    required this.vendor,
    required this.storeId,
  });

  /// True for the built-in simulator, so the till can say so rather than let a
  /// cashier believe a card was really taken.
  bool get simulated => vendor == 'SIMULATED';

  factory CardTerminalDevice.fromJson(Map<String, dynamic> j) =>
      CardTerminalDevice(
        id: j['id'] as String? ?? '',
        label: j['label'] as String? ?? '',
        vendor: j['vendor'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
      );
}

/// What the terminal said.
class TerminalOutcome {
  final String id;
  final String state;
  final String? detail;
  final String? receiptLine;
  final String? scheme;
  final String? panLast4;
  final String? authCode;

  /// What the attempt asked the card for, as the server wrote it (`"12.00"`),
  /// and in what currency; null when the answer did not say.
  final String? amount;
  final String? currency;

  /// SALE or REFUND; null when the answer did not say.
  final String? kind;

  /// For a REFUND: the SALE attempt it put money back on.
  final String? refundOf;

  /// The order the attempt was taken for, and the machine it was sent to; null
  /// when the answer did not say.
  final String? orderId;
  final String? terminalId;

  /// Where it stands with the money, as payment-svc judges it: SETTLED,
  /// AT_MACHINE, APPROVED_UNRECORDED or UNDECIDED; null when not said. Anything
  /// but SETTLED holds the machine.
  final String? standing;

  /// What a manager recorded the machine as showing, for an attempt it did not
  /// answer: APPROVED or NOT_TAKEN; null when nobody has said.
  final String? decided;

  /// The tender this approval was recorded as, once it is.
  final String? paymentId;

  const TerminalOutcome({
    required this.id,
    required this.state,
    this.detail,
    this.receiptLine,
    this.scheme,
    this.panLast4,
    this.authCode,
    this.amount,
    this.currency,
    this.kind,
    this.refundOf,
    this.orderId,
    this.terminalId,
    this.standing,
    this.decided,
    this.paymentId,
  });

  /// A press the till heard nothing back from: the amount may be at the machine
  /// or may never have reached the server, and no attempt is known to ask after.
  /// Pending, because only the same key, asked again, can say which.
  const TerminalOutcome.unheard()
      : id = '',
        state = 'REQUESTED',
        detail = null,
        receiptLine = null,
        scheme = null,
        panLast4 = null,
        authCode = null,
        amount = null,
        currency = null,
        kind = null,
        refundOf = null,
        orderId = null,
        terminalId = null,
        standing = null,
        decided = null,
        paymentId = null;

  /// The machine took the money: it said so, or it did not answer and a
  /// manager recorded it as showing the payment approved.
  bool get approved =>
      state == 'APPROVED' || (state == 'TIMED_OUT' && decided == 'APPROVED');

  /// The card may have been charged and nobody has said what the machine
  /// shows. The one outcome that must never be retried and must never be
  /// recorded as a taking: only a manager's record of what the machine shows
  /// ([settleTerminalAttempt]) settles it.
  bool get uncertain => state == 'TIMED_OUT' && decided == null;

  /// A manager recorded the machine as having taken nothing, and the machine
  /// has not said otherwise since.
  bool get notTaken => decided == 'NOT_TAKEN' && state != 'APPROVED';

  /// The machine's own answer — approved — arrived after a manager had
  /// recorded the payment as not taken. payment-svc keeps the answer: the
  /// money is on the card. The sale it was for was told nothing was taken, so
  /// its customer paid another way or left: outside that sale, still open on
  /// the till that made it, this approval is never recorded on its order —
  /// it goes back on the card.
  bool get approvedAfterNotTaken =>
      state == 'APPROVED' && decided == 'NOT_TAKEN';

  /// Still at the card machine: the amount has been sent and the machine has not
  /// said yet. Not an outcome, so neither a decline nor a failure — the till
  /// waits and asks again until it is one.
  bool get pending => state == 'REQUESTED';

  /// The server answered with an attempt. False only for [TerminalOutcome.unheard].
  bool get heard => id.isNotEmpty;

  /// What the cashier is told. A decline without a reason sends them to ring the
  /// bank, so the terminal's own words are shown when it gave any.
  String get message => switch (state) {
    // The machine's own word stands over a person's reading of it.
    'APPROVED' => 'Approved',
    _ when decided == 'APPROVED' =>
      'Approved: a manager read it on the card machine',
    _ when decided == 'NOT_TAKEN' =>
      'Nothing was taken: a manager read it on the card machine',
    'DECLINED' => detail ?? 'Card declined',
    'CANCELLED' => detail ?? 'Cancelled at the terminal',
    'TIMED_OUT' =>
      'No answer from the terminal. The card may have been charged — check the '
          'terminal before taking payment again.',
    'REQUESTED' => 'Waiting for the card machine…',
    _ => detail ?? 'The terminal could not be reached',
  };

  factory TerminalOutcome.fromJson(Map<String, dynamic> j) => TerminalOutcome(
        id: j['id'] as String? ?? '',
        state: j['state'] as String? ?? 'FAILED',
        detail: j['outcomeDetail'] as String?,
        receiptLine: j['receiptLine'] as String?,
        scheme: j['scheme'] as String?,
        panLast4: j['panLast4'] as String?,
        authCode: j['authCode'] as String?,
        amount: j['amount']?.toString(),
        currency: j['currency'] as String?,
        kind: j['kind'] as String?,
        refundOf: j['refundOf'] as String?,
        orderId: j['orderId'] as String?,
        terminalId: j['terminalId'] as String?,
        standing: j['standing'] as String?,
        decided: j['decision'] is Map
            ? (j['decision'] as Map)['outcome'] as String?
            : null,
        paymentId: j['paymentId'] as String?,
      );
}

/// The active terminals at a store, so the till offers only devices that exist.
///
/// Empty is a normal answer: a shop with no pinpad goes on recording a CARD
/// tender the way it always did. Offering a terminal it has not got would be
/// worse than offering none.
final posTerminalsProvider =
    FutureProvider.family.autoDispose<List<CardTerminalDevice>, String>(
        (ref, storeId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.payment}/admin/payments/terminals');
  final rows = (resp.data['data'] as List?) ?? const [];
  return [
    for (final e in rows)
      if (e is Map<String, dynamic> &&
          e['status'] == 'ACTIVE' &&
          e['storeId'] == storeId)
        CardTerminalDevice.fromJson(e),
  ];
});

/// Sends an amount to a terminal and returns what it said.
///
/// [idempotencyKey] is not optional in practice and the caller must derive it
/// from the sale rather than generate a fresh one per press: the whole point is
/// that a second press with the same key finds the first attempt instead of
/// starting a second EMV transaction on a real card.
///
/// The answer can be [TerminalOutcome.pending]: a repeat of a payment still at
/// the machine gets that attempt back as it stands. Wait for it with
/// [awaitTerminalOutcome]; do not press again under a new key.
///
/// [within] is how long the till may still wait: when it is less than the
/// till's own receive timeout, the press waits no longer than that, so the
/// wait for the machine ([takeCardAndWait]) ends when its time does.
Future<TerminalOutcome> takeCardOnTerminal(
  Dio dio, {
  required String terminalId,
  required String orderId,
  required double amount,
  required String currency,
  required String idempotencyKey,
  Duration? within,
}) async {
  final resp = await dio.post(
    '/${ApiConstants.payment}/payments/terminal',
    data: {
      'terminalId': terminalId,
      'orderId': orderId,
      // A string, as money: a JSON number is a double by the time a browser
      // has parsed it. At the currency's own minor units ([terminalAmount]).
      'amount': terminalAmount(amount, currency),
      'currency': currency,
    },
    options: _within(dio, within, {'Idempotency-Key': idempotencyKey}),
  );
  return TerminalOutcome.fromJson(resp.data['data'] as Map<String, dynamic>);
}

/// A request's options, its receive timeout shortened to [within] when that is
/// less than the till's own: an ask never outlasts the time the wait has left.
Options _within(Dio dio, Duration? within, [Map<String, dynamic>? headers]) {
  final own = dio.options.receiveTimeout;
  final shorter = within != null &&
      within > Duration.zero &&
      (own == null || own == Duration.zero || within < own);
  return Options(headers: headers, receiveTimeout: shorter ? within : null);
}

/// The amount a terminal call carries: what the sale owes, written at the
/// currency's ISO 4217 minor units ([AppFormat.minorUnits]) — `12.50`, `1200`,
/// `129.99` — and never rounded to a figure the sale does not owe.
///
/// payment-svc's terminal call takes at most two decimals and refuses a third
/// rather than rounding somebody else's money. So a three-decimal amount that
/// fits two (`3.10` dinars) is sent with two, and one that does not (`1.125`)
/// is sent as it is, to be refused — never `1.13` taken on the card for a sale
/// of 1.125.
@visibleForTesting
String terminalAmount(double amount, String currency) {
  final digits = AppFormat.minorUnits(currency);
  var text = amount.toStringAsFixed(digits);
  while (digits > 2 &&
      text.endsWith('0') &&
      text.length - text.indexOf('.') - 1 > 2) {
    text = text.substring(0, text.length - 1);
  }
  return text;
}

/// How long the till waits for a card machine's answer, and how often it asks.
///
/// A cardholder can take a minute over a PIN, so the wait is generous but never
/// open-ended: ninety seconds, asked every two. Bounded by the clock and not by
/// a count of asks, because an ask is not free — a press the till's own receive
/// timeout ends has spent ten seconds before the next ask can start, and a
/// count of 45 asks so spent was the best part of ten minutes with a customer
/// at the counter. Each ask's own time counts against [limit], and an ask never
/// outlasts what is left of it. When it runs out the till stops waiting — it
/// does not give up on the payment, which is still at the machine — and says
/// so.
class TerminalWait {
  /// The pause before each ask after the first.
  final Duration every;

  /// How long the whole wait may take, the asks' own time included.
  final Duration limit;

  const TerminalWait({
    this.every = const Duration(seconds: 2),
    this.limit = const Duration(seconds: 90),
  });
}

/// What the tender screen waits by. A provider so a test can shorten it.
final terminalWaitProvider =
    Provider<TerminalWait>((ref) => const TerminalWait());

/// The till's clock, as the wait for a card machine reads it.
typedef TerminalClock = DateTime Function();

DateTime _systemNow() => DateTime.now();

/// The clock the tender screen waits by. A provider so a test can hand it a
/// fake one and run the ninety seconds exactly.
final terminalClockProvider = Provider<TerminalClock>((ref) => _systemNow);

/// One attempt as the server has it now: what the machine has said so far.
/// [within] as for [takeCardOnTerminal].
Future<TerminalOutcome> readTerminalAttempt(Dio dio, String attemptId,
    {Duration? within}) async {
  final resp = await dio.get(
      '/${ApiConstants.payment}/payments/terminal/$attemptId',
      options: _within(dio, within));
  return TerminalOutcome.fromJson(resp.data['data'] as Map<String, dynamic>);
}

/// Every card attempt against [orderId] as the server has it now, oldest first,
/// declines and refunds included. A read: it starts nothing on any machine.
Future<List<TerminalOutcome>> readTerminalAttemptsOfOrder(
    Dio dio, String orderId, {Duration? within}) async {
  final resp = await dio.get(
      '/${ApiConstants.payment}/payments/terminal/by-order/$orderId',
      options: _within(dio, within));
  final rows = (resp.data['data'] as List?) ?? const [];
  return [
    for (final e in rows)
      if (e is Map<String, dynamic>) TerminalOutcome.fromJson(e),
  ];
}

/// Asks the card machine to stop asking for the card on [attemptId] and returns
/// the attempt as it stands — usually still REQUESTED. The cancel settles
/// nothing: only the machine's own answer does, CANCELLED when the cancel took,
/// APPROVED when the cardholder finished first. The attempt keeps holding the
/// machine until then, so the till reads it until it settles
/// ([awaitTerminalOutcome]) and never takes a cancel's answer for the end.
/// payment-svc refuses a cancel of a refund (409 TERMINAL_NOT_A_SALE).
Future<TerminalOutcome> cancelTerminalAttempt(Dio dio, String attemptId) async {
  final resp = await dio
      .post('/${ApiConstants.payment}/payments/terminal/$attemptId/cancel');
  return TerminalOutcome.fromJson(resp.data['data'] as Map<String, dynamic>);
}

/// Puts [amount] back on the card that paid [attemptId], on the machine that
/// took it: payment-svc's linked refund (`POST /payments/terminal/{id}/refunds`,
/// MANAGER or OWNER holding `sales.refund`), never a card taken again. Under
/// [idempotencyKey], so a press repeated finds the refund already made instead
/// of making a second. [reason] is required (at most 500 characters): payment-svc
/// keeps it on the refund, with who asked and when. [within] as for
/// [takeCardOnTerminal]. Returns the REFUND attempt as the machine left it.
Future<TerminalOutcome> refundTerminalAttempt(
  Dio dio,
  String attemptId, {
  required double amount,
  required String currency,
  required String reason,
  required String idempotencyKey,
  Duration? within,
}) async {
  final resp = await dio.post(
    '/${ApiConstants.payment}/payments/terminal/$attemptId/refunds',
    data: {'amount': terminalAmount(amount, currency), 'reason': reason},
    options: _within(dio, within, {'Idempotency-Key': idempotencyKey}),
  );
  return TerminalOutcome.fromJson(resp.data['data'] as Map<String, dynamic>);
}

/// What a person can see on a card machine that did not answer.
enum MachineShows {
  /// The machine shows the payment approved: the card was charged.
  approved('APPROVED'),

  /// The machine shows nothing was taken.
  notTaken('NOT_TAKEN');

  const MachineShows(this.wire);
  final String wire;
}

/// Records what a manager saw on the card machine for [attemptId], one it did
/// not answer — TIMED_OUT, or REQUESTED past the time it had to answer — with
/// [reason] (at most 500 characters): payment-svc's
/// `POST /payments/terminal/{id}/settle`, MANAGER or OWNER holding
/// `sales.refund`, under [idempotencyKey]. Returns the attempt with the
/// decision on it: [TerminalOutcome.approved] (an approval to record on its
/// sale, or to put back) or [TerminalOutcome.notTaken] (the machine is free).
///
/// Refused 409 TERMINAL_REQUEST_IN_FLIGHT, with `decidableFrom=` in its
/// details, while the machine may still answer ([decidableFrom]).
Future<TerminalOutcome> settleTerminalAttempt(
  Dio dio,
  String attemptId, {
  required MachineShows shows,
  required String reason,
  required String idempotencyKey,
}) async {
  final resp = await dio.post(
    '/${ApiConstants.payment}/payments/terminal/$attemptId/settle',
    data: {'outcome': shows.wire, 'reason': reason},
    options: Options(headers: {'Idempotency-Key': idempotencyKey}),
  );
  return TerminalOutcome.fromJson(resp.data['data'] as Map<String, dynamic>);
}

/// When a manager may first record what the machine shows for a payment still
/// at it, as the ISO instant a 409 TERMINAL_REQUEST_IN_FLIGHT's
/// `decidableFrom=` names; null when [error] does not say.
String? decidableFrom(Object error) =>
    apiErrorOf(error)?.detail('decidableFrom');

/// Records the machine's approval [attempt] as a CARD tender on its own order
/// (`POST /payments` naming it as `terminalPaymentId`): exactly what the
/// machine took, at the machine's store. That is what finishes it and frees the
/// machine for its next card. Under [idempotencyKey], so a repeat finds the
/// tender already recorded.
Future<void> recordTerminalApproval(
  Dio dio,
  TerminalOutcome attempt, {
  required String idempotencyKey,
}) async {
  await dio.post(
    '/${ApiConstants.payment}/payments',
    data: {
      'orderId': attempt.orderId,
      'amount': attempt.amount,
      'method': 'CARD',
      if (attempt.currency != null) 'currency': attempt.currency,
      'terminalPaymentId': attempt.id,
    },
    options: Options(headers: {'Idempotency-Key': idempotencyKey}),
  );
}

/// One card payment that holds a machine, as payment-svc's 409
/// TERMINAL_UNSETTLED_APPROVAL names it in its details:
/// `attemptId=…;orderId=…;amount=…;currency=…;onCard=…;state=…;standing=…;kind=…`
/// and, for a refund, `;refundOf=…`.
class UnsettledCard {
  final String attemptId;
  final String orderId;
  final String? amount;
  final String? currency;

  /// What of a sale is still on the card as far as anybody knows; nothing for
  /// a refund.
  final String? onCard;
  final String? state;

  /// AT_MACHINE, APPROVED_UNRECORDED or UNDECIDED.
  final String? standing;
  final String? kind;
  final String? refundOf;

  const UnsettledCard({
    required this.attemptId,
    required this.orderId,
    this.amount,
    this.currency,
    this.onCard,
    this.state,
    this.standing,
    this.kind,
    this.refundOf,
  });

  bool get isRefund => kind == 'REFUND';

  /// One detail as payment-svc writes it; null when it names no attempt.
  static UnsettledCard? parse(String detail) {
    final parts = <String, String>{};
    for (final p in detail.split(';')) {
      final eq = p.indexOf('=');
      if (eq > 0) parts[p.substring(0, eq).trim()] = p.substring(eq + 1).trim();
    }
    final id = parts['attemptId'];
    if (id == null || id.isEmpty) return null;
    return UnsettledCard(
      attemptId: id,
      orderId: parts['orderId'] ?? '',
      amount: parts['amount'],
      currency: parts['currency'],
      onCard: parts['onCard'],
      state: parts['state'],
      standing: parts['standing'],
      kind: parts['kind'],
      refundOf: parts['refundOf'],
    );
  }

  /// The attempt as the details have it, for a till that could not read more.
  /// A timeout that holds the machine as an approval was recorded as one.
  TerminalOutcome get asOutcome => TerminalOutcome(
        id: attemptId,
        state: state ?? 'FAILED',
        amount: amount,
        currency: currency,
        kind: kind,
        refundOf: refundOf,
        orderId: orderId,
        standing: standing,
        decided: state == 'TIMED_OUT' && standing == 'APPROVED_UNRECORDED'
            ? 'APPROVED'
            : null,
      );
}

/// The card payments a 409 TERMINAL_UNSETTLED_APPROVAL says hold the machine,
/// in its order; empty for any other error.
List<UnsettledCard> unsettledCardsOf(Object error) {
  final err = apiErrorOf(error);
  if (err == null || err.code != 'TERMINAL_UNSETTLED_APPROVAL') return const [];
  return [
    for (final d in err.details) ?UnsettledCard.parse(d),
  ];
}

/// Waits for a pending attempt to settle and returns what the machine said.
///
/// It only reads. A read starts nothing, so it carries no Idempotency-Key and
/// cannot start a second payment: the attempt the server already has is the only
/// one there is. A read that fails is no news rather than bad news — the attempt
/// is still at the machine, so the next ask tries again — and a till whose
/// network blinked during the wait is neither told the card failed nor has its
/// sale queued as if the card had never been tried.
///
/// Bounded by [wait]'s time on [now], ending at [until] when the wait began
/// earlier (a press asked again shares the time). Returns the attempt still
/// pending when the time runs out, or when [abandoned] says nobody is waiting
/// any more.
Future<TerminalOutcome> awaitTerminalOutcome(
  Dio dio,
  TerminalOutcome attempt, {
  TerminalWait wait = const TerminalWait(),
  TerminalClock now = _systemNow,
  DateTime? until,
  bool Function()? abandoned,
}) async {
  var latest = attempt;
  // An answer with no id leaves nothing to ask after.
  if (attempt.id.isEmpty) return latest;
  final deadline = until ?? now().add(wait.limit);
  Duration left() => deadline.difference(now());
  while (latest.pending && left() > Duration.zero) {
    await Future<void>.delayed(wait.every);
    if (abandoned?.call() ?? false) break;
    // The pause may have used the last of it: no ask past the time.
    if (left() <= Duration.zero) break;
    try {
      latest = await readTerminalAttempt(dio, attempt.id, within: left());
    } catch (_) {
      // No news: ask again.
    }
  }
  return latest;
}

/// Sends an amount to a terminal and waits for what the machine says.
///
/// The press is [takeCardOnTerminal]. When the till hears nothing back from it
/// — its own receive timeout while the cardholder is at the PIN, or a network
/// that blinked — it is asked again under the SAME [idempotencyKey], which finds
/// the attempt the server already has (or starts the one the first press never
/// delivered) and never a second payment on the card. An attempt still
/// REQUESTED is then read until it settles ([awaitTerminalOutcome]).
///
/// Asking again and reading share [wait]'s time, measured on [now] from the
/// press: the time every ask took counts, so the wait is never open-ended. A
/// refusal from the server is an answer and is thrown as it came.
///
/// Returns the settled outcome; one still [TerminalOutcome.pending] when the
/// wait ran out with the attempt known; or [TerminalOutcome.unheard] when no
/// answer came at all, or [abandoned] says nobody is waiting any more.
///
/// [until] is when the wait ends, when the caller fixed it before the press
/// (so what it does after a refusal shares the same time); else [wait]'s limit
/// from now. [onUnheard] is told each time an ask goes unanswered: the amount
/// may then be at the machine, whatever a later ask is told.
Future<TerminalOutcome> takeCardAndWait(
  Dio dio, {
  required String terminalId,
  required String orderId,
  required double amount,
  required String currency,
  required String idempotencyKey,
  TerminalWait wait = const TerminalWait(),
  TerminalClock now = _systemNow,
  DateTime? until,
  bool Function()? abandoned,
  void Function()? onUnheard,
}) async {
  final deadline = until ?? now().add(wait.limit);
  Duration left() => deadline.difference(now());
  TerminalOutcome outcome;
  while (true) {
    try {
      outcome = await takeCardOnTerminal(
        dio,
        terminalId: terminalId,
        orderId: orderId,
        amount: amount,
        currency: currency,
        idempotencyKey: idempotencyKey,
        within: left(),
      );
      break;
    } catch (e) {
      // The server said something: that is an answer, not silence.
      if (!isOfflineError(e)) rethrow;
      onUnheard?.call();
      // The ask's own timeout has been spent: it counts.
      if (left() <= Duration.zero) return const TerminalOutcome.unheard();
      await Future<void>.delayed(wait.every);
      if (abandoned?.call() ?? false) return const TerminalOutcome.unheard();
      if (left() <= Duration.zero) return const TerminalOutcome.unheard();
    }
  }
  if (!outcome.pending) return outcome;
  return awaitTerminalOutcome(
    dio,
    outcome,
    wait: wait,
    now: now,
    until: deadline,
    abandoned: abandoned,
  );
}

/// Thrown when the wait for the card machine ran out with the payment still at
/// it, so the caller stops the settle without recording anything.
///
/// Not [TerminalNotApproved]: nothing was refused and nothing went wrong. The
/// caller keeps the sale and its keys (held_card_payment.dart, beyond the
/// screen), so that pressing Complete Sale again asks after this same payment
/// instead of starting another — and a sale changed in the meantime settles
/// this payment before it starts anything of its own.
class TerminalStillPending implements Exception {
  final TerminalOutcome outcome;
  const TerminalStillPending(this.outcome);

  @override
  String toString() => outcome.message;
}

/// Thrown when a card was not approved, so the caller stops the settle and
/// leaves the sale open for another tender.
///
/// Carries the outcome rather than a string, because the till has to treat a
/// timeout differently from a decline: one means "ask for another card", the
/// other means "do not touch this until somebody has read the terminal".
class TerminalNotApproved implements Exception {
  final TerminalOutcome outcome;
  const TerminalNotApproved(this.outcome);

  @override
  String toString() => outcome.message;
}
