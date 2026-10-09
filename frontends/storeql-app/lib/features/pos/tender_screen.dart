import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/offline/offline_queue.dart';
import '../../core/offline/offline_sale.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/bottom_action_bar.dart';
import '../../shared/util/status_labels.dart';
import '../../shared/widgets/empty_state.dart';
import '../admin/customer_providers.dart';
import '../admin/providers/admin_providers.dart';
import 'pos_fiscal_receipt.dart';
import 'pos_providers.dart';
import 'pos_quote.dart';
import 'pos_vat.dart';
import 'pos_terminal.dart';
import 'pos_receipt.dart';
import 'pos_receipt_printer.dart';
import 'pos_session_providers.dart';
import '../../shared/util/short_ref.dart';
import 'customer_display.dart';
import 'customer_display_channel.dart';
import 'held_card_payment.dart';
import 'package:storeql_app/core/ids.dart';

/// Words for the two refusals a till sale's contact phone can hit
/// (phone-at-the-till). Shown at the field, never a snackbar, so the cashier
/// fixes it right there while the customer is still at the counter.
String _phoneServerErrorMessage(String code, String tillPhone) => switch (code) {
      'ORDER_CONTACT_PHONE_REQUIRED' => 'This store asks for a number on every sale',
      // Required leaves no room to say "or leave it blank" — blank is exactly
      // what got it refused.
      'ORDER_CONTACT_PHONE_INVALID' => tillPhone == 'REQUIRED'
          ? "That isn't a phone number where this business trades — check it"
          : "That isn't a phone number where this business trades — check it, "
              'or leave it blank',
      _ => '',
    };

/// A fingerprint of everything a sale sends, so a press that follows "still
/// waiting for the card machine" can tell whether it is the same sale.
///
/// Null when the request cannot be written as JSON, and then nothing is reused.
/// A guard only: the amount dialog takes nothing but a finite amount
/// ([tenderAmount]), so no sale should carry a figure that is not a number —
/// but if one ever did, this must not throw before the sale's own handling,
/// which would leave Complete Sale spinning.
@visibleForTesting
String? saleFingerprint(Object? request) {
  try {
    return jsonEncode(request);
  } catch (_) {
    return null;
  }
}

/// Whether [e] is payment-svc refusing a card machine that is gone from the
/// sale's store — retired, or moved to another — which it does before it
/// looks the key up, on every ask: asking again never gets another answer.
bool _terminalGone(Object e) =>
    const {'TERMINAL_RETIRED', 'TERMINAL_WRONG_STORE'}.contains(apiErrorCode(e));

/// Whether [e] is payment-svc refusing a card press before it asks any machine
/// or claims the key: the machine is gone or not the business's, the amount or
/// currency is not one it takes, another payment holds the machine (409
/// TERMINAL_UNSETTLED_APPROVAL), the order has been given up, or the caller may
/// not act at that store. A gateway's 5xx is not an answer about the machine,
/// and TERMINAL_REQUEST_IN_FLIGHT is a press of the same key still going.
bool _machineNotAsked(Object e) {
  if (e is! DioException) return false;
  final status = e.response?.statusCode ?? 0;
  if (status < 400 || status >= 500) return false;
  return const {
    'TERMINAL_RETIRED',
    'TERMINAL_NOT_FOUND',
    'TERMINAL_AMOUNT_INVALID',
    'TERMINAL_ID_INVALID',
    'TERMINAL_CARD_DATA_NOT_ACCEPTED',
    'TERMINAL_UNSETTLED_APPROVAL',
    'CURRENCY_INVALID',
    'PAYMENT_ORDER_GIVEN_UP',
    'STORE_ACCESS_DENIED',
    'VALIDATION_FAILED',
  }.contains(apiErrorCode(e));
}

/// The attempt [id] as the machine left it, waited for while it is still at
/// the machine until [until] — the press's own time, never a fresh ninety
/// seconds — and null when it cannot be read or the time is gone. Only reads:
/// a card is never asked for by it.
Future<TerminalOutcome?> _readKnownAttempt(
  Dio dio,
  String id, {
  required TerminalWait wait,
  required TerminalClock now,
  required DateTime until,
  bool Function()? abandoned,
}) async {
  Duration left() => until.difference(now());
  if (left() <= Duration.zero) return null;
  try {
    final read = await readTerminalAttempt(dio, id, within: left());
    if (!read.pending) return read;
    return await awaitTerminalOutcome(dio, read,
        wait: wait, now: now, until: until, abandoned: abandoned);
  } catch (_) {
    return null;
  }
}

/// Thrown inside a press when its screen is gone and it holds a card: nothing
/// more is started, and what was sent stays held for the next press.
class _PressLeft implements Exception {
  const _PressLeft();
}

/// Thrown inside a press when the hold could not be written to the device just
/// before a card would go to a machine: the machine is not asked (place
/// [place] of the sale).
class _HoldNotKept implements Exception {
  final int place;
  const _HoldNotKept(this.place);
}

/// What the cashier chose about a card taken for a sale that has changed.
enum _TakenChoice { notNow, putBack, reverse, close, settleRefund }

/// What was chosen for one card payment holding the machine (409
/// TERMINAL_UNSETTLED_APPROVAL).
enum _HeldMachineAction { finish, reverse, settle }

/// Whether a card approval nobody recorded, holding the machine, may be
/// finished on its sale from this till.
enum _Finish {
  /// On this till's own sale, still open here, at the place that took it.
  yes,

  /// On another sale that reads as still owing it. Whether that sale's
  /// customer paid another way is not the till's to know, so it is a
  /// person's call: a manager's, asked first — never a default button.
  personsCall,

  /// Its sale is paid already, given up, or would be paid twice.
  paidAlready,

  /// Its sale could not be read.
  unknown,

  /// On this till's own sale, but no card on it is that payment.
  notThisSale,

  /// Approved after a manager had recorded it as not taken: its sale was
  /// told nothing was taken. Never finished on it; it goes back on the card.
  afterNotTaken,

  /// On an order this till let go of unfinished: its goods never left on
  /// that card. Never finished on it; it goes back on the card.
  letGo,

  /// A sale kept on this till for that order (the offline queue) records it
  /// when it reaches the server. Neither finished nor put back from here.
  queued,
}

/// Asks a manager why: a card taken for a held sale being put back, or a held
/// sale that cannot be finished being cancelled. The reason is required —
/// neither is ever a slip of the finger — and the server keeps it: on the
/// refund, or in the order's history. At most 500 characters, as the server
/// takes. Its keys are [keyPrefix]`-reason`, `-reason-field` and `-confirm`.
class _ManagerReasonDialog extends StatefulWidget {
  final String keyPrefix;
  final String title;
  final String body;
  final String label;
  final String confirm;

  const _ManagerReasonDialog({
    required this.keyPrefix,
    required this.title,
    required this.body,
    required this.label,
    required this.confirm,
  });

  @override
  State<_ManagerReasonDialog> createState() => _ManagerReasonDialogState();
}

class _ManagerReasonDialogState extends State<_ManagerReasonDialog> {
  final _reason = TextEditingController();

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ready = _reason.text.trim().isNotEmpty;
    return AlertDialog(
      key: Key('${widget.keyPrefix}-reason'),
      title: Text(widget.title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            widget.body,
            style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: AppSpacing.md),
          TextField(
            key: Key('${widget.keyPrefix}-reason-field'),
            controller: _reason,
            autofocus: true,
            maxLines: 2,
            maxLength: 500,
            decoration: InputDecoration(labelText: widget.label),
            onChanged: (_) => setState(() {}),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: Key('${widget.keyPrefix}-confirm'),
          onPressed:
              ready ? () => Navigator.of(context).pop(_reason.text.trim()) : null,
          child: Text(widget.confirm),
        ),
      ],
    );
  }
}

/// A manager says what a card machine that did not answer shows — the payment
/// approved, or nothing taken — and why (payment-svc's settle, kept with who
/// and when). Both are required: what the machine shows decides whether money
/// is on the card. Its keys are `tender-settle`, `-approved`, `-not-taken`,
/// `-reason-field` and `-confirm`.
class _MachineShowsDialog extends StatefulWidget {
  /// What the payment was for, in words (`£12.00`), or empty.
  final String amount;

  /// Money being put back on a card rather than taken from it.
  final bool refund;

  const _MachineShowsDialog({required this.amount, required this.refund});

  @override
  State<_MachineShowsDialog> createState() => _MachineShowsDialogState();
}

class _MachineShowsDialogState extends State<_MachineShowsDialog> {
  final _reason = TextEditingController();
  MachineShows? _shows;

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final ready = _shows != null && _reason.text.trim().isNotEmpty;
    final what = widget.amount.isEmpty ? '' : ' of ${widget.amount}';
    final approved = widget.refund
        ? 'It shows the money went back on the card'
        : 'It shows the payment approved: the card was charged';
    final notTaken = widget.refund
        ? 'It shows nothing went back on the card'
        : 'It shows nothing was taken';
    Widget choice(MachineShows value, String label, Key key) => ListTile(
          key: key,
          contentPadding: EdgeInsets.zero,
          leading: Icon(
            _shows == value
                ? Icons.radio_button_checked
                : Icons.radio_button_unchecked,
            color: _shows == value ? cs.primary : cs.onSurfaceVariant,
          ),
          title: Text(label),
          onTap: () => setState(() => _shows = value),
        );
    return AlertDialog(
      key: const Key('tender-settle'),
      title: const Text('What does the card machine show?'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'The card machine did not answer about the '
              '${widget.refund ? 'refund' : 'card payment'}$what. Look at the '
              "machine's own screen or its last receipt, then say what it "
              'shows. This is kept with your name.',
              style: TextStyle(color: cs.onSurfaceVariant),
            ),
            const SizedBox(height: AppSpacing.md),
            choice(MachineShows.approved, approved,
                const Key('tender-settle-approved')),
            choice(MachineShows.notTaken, notTaken,
                const Key('tender-settle-not-taken')),
            const SizedBox(height: AppSpacing.sm),
            TextField(
              key: const Key('tender-settle-reason-field'),
              controller: _reason,
              maxLines: 2,
              maxLength: 500,
              decoration: const InputDecoration(
                  labelText: 'What did you see, and where?'),
              onChanged: (_) => setState(() {}),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('tender-settle-confirm'),
          onPressed: ready
              ? () => Navigator.of(context)
                  .pop((shows: _shows!, reason: _reason.text.trim()))
              : null,
          child: const Text('Record it'),
        ),
      ],
    );
  }
}

/// Multi-tender payment screen: a sale can be split across cash, card, gift card
/// and store credit. The cashier stages tenders until the balance is cleared,
/// then completes — placing one order and recording each tender against it.
class TenderScreen extends ConsumerStatefulWidget {
  const TenderScreen({super.key});

  @override
  ConsumerState<TenderScreen> createState() => _TenderScreenState();
}

class _TenderScreenState extends ConsumerState<TenderScreen> {
  final List<PosTender> _tenders = [];
  bool _processing = false;

  /// The walk-in phone field shown on this screen (phone-at-the-till): the
  /// same value as the Sale tab's, so typing in either shows in both.
  late final TextEditingController _phoneCtrl;

  /// A reason the sale cannot complete, or a server refusal, shown at the
  /// phone field rather than a snackbar — the cashier fixes it right there
  /// while the customer is still at the counter.
  String? _phoneError;

  /// A card payment is at the machine — the first press, or a repeat of it —
  /// and the till is waiting for the machine to say. Shown as what the till is
  /// waiting on rather than a bare "Processing…".
  bool _atCardMachine = false;

  // The keys of a sale that stopped with its card still at the machine are
  // held in [heldCardPaymentProvider], not here: this state is gone the moment
  // the cashier goes Back to Sale, and a payment still at the machine is not.

  @override
  void initState() {
    super.initState();
    _phoneCtrl = TextEditingController(text: ref.read(posWalkInPhoneProvider));
    // Back on the screen with a card payment still held for this same sale: its
    // tenders are staged again, so Complete Sale carries on that payment under
    // the same keys. Only for the same sale — the basket never let go, or the
    // held one put back, of the business that holds it. Another basket starts
    // with nothing staged, however like it, and its press settles the held
    // payment first: the next customer buying the same thing must never be
    // recorded as paid by the earlier customer's card.
    final held = ref.read(heldCardPaymentProvider);
    if (held != null &&
        held.belongsTo(_tenantId()) &&
        held.saleId == _saleIdentity() &&
        held.orderSignature != null &&
        _draft(held.sale.tenders).orderSignature == held.orderSignature) {
      _tenders.addAll(held.sale.tenders);
    }
  }

  /// Which sale is on the till, apart from its content: the exchange being
  /// settled, or the basket ([PosCartNotifier.saleId]).
  String _saleIdentity() {
    final settlement = ref.read(posExchangeSettlementProvider);
    return settlement != null
        ? 'exchange:${settlement.orderId}'
        : ref.read(posCartProvider.notifier).saleId;
  }

  /// The business the till is signed in to, from its token.
  String? _tenantId() {
    final auth = ref.read(authNotifierProvider).value;
    return auth is AuthAuthenticated ? auth.tenantId : null;
  }

  @override
  void dispose() {
    _phoneCtrl.dispose();
    super.dispose();
  }

  /// The discount actually applied to this sale, clamped to the subtotal.
  ///
  /// One accessor because there used to be two clamps that disagreed: [_due]
  /// clamped to the subtotal, while `_complete` clamped only at zero and sent the
  /// raw figure. A discount larger than the basket therefore showed a due of
  /// nothing, let the cashier tender it to zero, and was then refused by the
  /// server with `ORDER_DISCOUNT_EXCEEDS_SUBTOTAL` — a sale the till had already
  /// treated as finished. Harmless before SJ-D6, because the server discarded the
  /// discount entirely; that fix is what made the till's figure matter.
  double get _discount {
    // An exchange's new order is already priced by the server.
    if (ref.read(posExchangeSettlementProvider) != null) return 0;
    // Off the goods only: a gift card being sold is never discounted.
    return ref.read(posTotalsProvider).discountOf(ref.read(posDiscountProvider));
  }

  /// Goods less the discount, plus the return-scheme deposits on the sale's
  /// containers (09.16): the deposit is due in full whatever the discount.
  double get _due =>
      ref.read(posExchangeSettlementProvider)?.due ??
      ref.read(posTotalsProvider).due(ref.read(posDiscountProvider));

  double get _paid => _tenders.fold(0.0, (s, t) => s + t.amount);
  double get _remaining => (_due - _paid).clamp(0.0, double.infinity);
  double get _change => _tenders.fold(0.0, (s, t) => s + t.change);

  void _snack(String msg, {bool error = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(msg),
        backgroundColor: error ? Theme.of(context).colorScheme.error : null,
      ),
    );
  }

  Future<void> _addCashOrCard(String method) async {
    final result = await showDialog<({double amount, double given})>(
      context: context,
      builder: (_) => _AmountDialog(
        title: switch (method) {
          'CASH' => 'Cash',
          'UPI' => 'UPI',
          'WALLET' => 'Wallet',
          _ => 'Card',
        },
        currency: _currency,
        remaining: _remaining,
        allowOverpay: method == 'CASH',
      ),
    );
    if (result == null) return;
    final applied = method == 'CASH'
        ? result.amount.clamp(0, _remaining).toDouble()
        : result.amount;
    if (applied <= 0) return;
    // A card goes to a terminal when the store has one (07.16). The card is not
    // charged here — the amount is sent at settle, once the order exists, so a
    // decline leaves an order awaiting payment rather than money taken for
    // nothing. A store with no pinpad records the tender as it always did.
    final terminalId = method == 'CARD' ? await _chooseTerminal() : null;
    if (method == 'CARD' && terminalId == _noTerminalChosen) return;
    if (!mounted) return;
    setState(
      () => _tenders.add(
        PosTender(
          method: method,
          amount: applied,
          cashGiven: method == 'CASH' ? result.given : 0,
          terminalId: terminalId,
        ),
      ),
    );
  }

  /// Sentinel for "the cashier backed out of choosing a terminal", which is not
  /// the same as "this store has no terminal" — one adds no tender, the other
  /// adds a self-attested one.
  static const _noTerminalChosen = '';

  /// Which terminal to send the amount to, or null when the store has none.
  ///
  /// One terminal is chosen without asking: a cashier at a single till should not
  /// answer a question with one answer. Several are offered, because a shop with
  /// two counters can have the wrong pinpad light up otherwise.
  Future<String?> _chooseTerminal() async {
    final storeId = ref.read(posStoreProvider);
    if (storeId == null) return null;
    List<CardTerminalDevice> devices;
    try {
      devices = await ref.read(posTerminalsProvider(storeId).future);
    } catch (_) {
      // A terminal list that cannot be read must not stop a sale: the tender is
      // recorded the way it was before this row existed.
      return null;
    }
    if (devices.isEmpty) return null;
    if (devices.length == 1) return devices.first.id;
    if (!mounted) return _noTerminalChosen;
    final chosen = await showDialog<String>(
      context: context,
      builder: (_) => SimpleDialog(
        key: const Key('tender-choose-terminal'),
        title: const Text('Which card machine?'),
        children: [
          for (final d in devices)
            SimpleDialogOption(
              key: Key('tender-terminal-${d.id}'),
              onPressed: () => Navigator.of(context).pop(d.id),
              child: Text(d.simulated ? '${d.label} (simulated)' : d.label),
            ),
        ],
      ),
    );
    return chosen ?? _noTerminalChosen;
  }

  Future<void> _addGiftCard() async {
    final tender = await showDialog<PosTender>(
      context: context,
      builder: (_) =>
          _GiftCardTenderDialog(currency: _currency, remaining: _remaining),
    );
    if (tender != null) setState(() => _tenders.add(tender));
  }

  Future<void> _addStoreCredit() async {
    final customer = ref.read(posCustomerProvider);
    if (customer == null) {
      _snack(
        'Attach a customer on the Sale screen to use store credit.',
        error: true,
      );
      return;
    }
    final tender = await showDialog<PosTender>(
      context: context,
      builder: (_) => _StoreCreditTenderDialog(
        customer: customer,
        currency: _currency,
        remaining: _remaining,
      ),
    );
    if (tender != null) setState(() => _tenders.add(tender));
  }

  String get _currency {
    final settlement = ref.read(posExchangeSettlementProvider);
    if (settlement != null) return settlement.currency;
    final cart = ref.read(posCartProvider);
    return cart.isNotEmpty ? cart.first.currency : '';
  }

  Future<void> _complete() async {
    if (_processing) return;
    // An exchange's new order already exists: only its difference is paid here.
    final settlement = ref.read(posExchangeSettlementProvider);
    final List<PosLine> cart =
        settlement?.lines ?? ref.read(posCartProvider);
    final storeId = ref.read(posStoreProvider);
    final customer =
        settlement != null ? null : ref.read(posCustomerProvider);
    final walkInPhone = ref.read(posWalkInPhoneProvider);
    final tillPhone = ref.read(posTillPhoneProvider);
    if (cart.isEmpty) return;
    if (storeId == null) {
      _snack('Select a store before tendering.', error: true);
      return;
    }
    // Only a Required store blocks here (phone-at-the-till) — Optional and
    // Don't ask complete with the field blank, or absent altogether.
    if (settlement == null &&
        tillPhone == 'REQUIRED' &&
        customer == null &&
        walkInPhone.isEmpty) {
      setState(() =>
          _phoneError = "Enter the customer's number, or attach the customer");
      return;
    }
    if (_remaining > 0.001) {
      _snack('Balance not fully tendered.', error: true);
      return;
    }
    setState(() {
      _processing = true;
      _phoneError = null;
    });

    // One press at a time on this till (HeldCardPaymentNotifier.claimPress). A
    // press whose screen has gone is still acting on the hold until its request
    // in flight answers; this one starts only once it has stopped, and finds
    // what it left.
    final endPress =
        await ref.read(heldCardPaymentProvider.notifier).claimPress();
    if (endPress == null) {
      if (!mounted) return;
      setState(() => _processing = false);
      _snack(
          'The till is still finishing the last press of Complete Sale. Wait '
          'a moment, then press it again.',
          error: true);
      return;
    }
    try {
      if (mounted) await _completeClaimed(endPress);
    } finally {
      endPress();
    }
  }

  /// A press of Complete Sale with the till claimed for it ([_complete]).
  /// [endPress] lets the next press start: called once this one does nothing
  /// more to the hold.
  Future<void> _completeClaimed(void Function() endPress) async {
    // The till as it is now: the press waited for may have changed it — a sale
    // it finished out of sight empties it.
    final settlement = ref.read(posExchangeSettlementProvider);
    final List<PosLine> cart =
        settlement?.lines ?? ref.read(posCartProvider);
    final storeId = ref.read(posStoreProvider);
    final customer =
        settlement != null ? null : ref.read(posCustomerProvider);
    final walkInPhone = ref.read(posWalkInPhoneProvider);
    final tillPhone = ref.read(posTillPhoneProvider);
    final discount = _discount;
    if (cart.isEmpty || storeId == null || _remaining > 0.001) {
      setState(() => _processing = false);
      return;
    }

    // Everything this press needs from the till is read here, before its first
    // await. The screen can be gone by the time an answer comes back — the
    // cashier went to another tab — and `ref` goes with it: a read after that
    // throws. A press that threw there used to end without its hold, and the
    // next press sent a second amount to the card machine.
    final dio = ref.read(apiClientProvider).dio;
    final holdCtl = ref.read(heldCardPaymentProvider.notifier);
    final wait = ref.read(terminalWaitProvider);
    final clock = ref.read(terminalClockProvider);
    final offlineQueue = ref.read(offlineQueueProvider.notifier);
    final session = ref.read(posSessionProvider.notifier);
    final clearTill = _tillClearer();
    final currency = _currency;
    final due = _due;
    final rungUpBy = _signedInUserId();
    final saleId = _saleIdentity();
    final tenant = _tenantId();
    // The press's own copy of the tenders: what it sends, whatever happens to
    // the screen's list meanwhile.
    final tenders = [..._tenders];

    // What the sale sends, built before its keys are chosen: a press that
    // follows "still waiting for the card machine" may keep the keys it had, but
    // only while it sends what that press sent (HeldCardPayment.carriesOn).
    final draft = _draft(tenders);
    final orderRequest = draft.orderRequest;
    final owed = draft.owed;
    // The sale as it is now, so a press that stops with its card at the machine
    // can be put back exactly as it was.
    final asItWas = HeldSale(
      storeId: storeId,
      lines: [...ref.read(posCartProvider)],
      settlement: settlement,
      customer: ref.read(posCustomerProvider),
      walkInPhone: walkInPhone,
      discount: ref.read(posDiscountProvider),
      discountReason: ref.read(posDiscountReasonProvider),
      tenders: tenders,
    );

    // A hold kept on the device from before the app last closed is read before
    // anything is decided.
    await holdCtl.ready;
    if (!mounted) return;

    // A hold this till could not read back may stand for a card at a machine
    // that it can no longer settle, so no card goes to a machine from here
    // until a manager has cleared it. Any other tender goes ahead.
    if (holdCtl.corrupt.value && tenders.any((t) => t.terminalId != null)) {
      setState(() => _processing = false);
      _snack(
          'A card payment saved on this till could not be read, so the card '
          'machine is not used from here until a manager clears it. Take '
          'another tender, or ask a manager.',
          error: true);
      return;
    }

    // One idempotency base for the whole sale, fixed here rather than per
    // attempt. If the network drops partway it is carried into the offline
    // queue with the sale, so every later replay presents the same keys — which
    // is what makes replaying a half-finished sale safe rather than a double
    // charge.
    //
    // The same goes for a press that follows "still waiting for the card
    // machine": it is the same payment, so it presents the same keys and the
    // server hands back the attempt it already has — the amount is never sent to
    // the machine a second time. Only while the order and the tenders the server
    // may have acted on are exactly what they were: a changed basket, number or
    // taken tender is a different request, since a key stands for one request.
    //
    // A sale that changed while a card payment of the held one may still be at
    // the machine, or was taken by it, is a new request — but it starts nothing
    // until that payment is settled (_settleHeld): otherwise its new order and
    // new amount would sit beside a payment the machine may yet approve, and the
    // customer would pay twice.
    //
    // A hold another business made on this device — the live business's,
    // seen from its sandbox, or one signed in here before — is not this
    // business's to read, settle or let go: payment-svc answers a business's
    // own attempts only, so its order would read as having none. A sale here
    // that sends nothing to a card machine goes ahead beside it; one that
    // would is refused, since its own hold would have to replace that one.
    final current = holdCtl.current;
    final held = current != null && current.belongsTo(tenant) ? current : null;
    if (current != null &&
        held == null &&
        tenders.any((t) => t.terminalId != null)) {
      setState(() => _processing = false);
      _snack(
          'A card payment is held on this till for another business, so the '
          'card machine cannot be used for this sale. Take another tender, or '
          'sign in to that business to finish its sale first.',
          error: true);
      return;
    }
    var carried = held != null &&
            held.carriesOn(saleId, draft.orderSignature, draft.tenderPrints)
        ? held
        : null;
    if (held != null && carried == null) {
      if (!await _settleHeld(dio, holdCtl, held)) {
        if (mounted) setState(() => _processing = false);
        return;
      }
      // What settling left: nothing, and the till is free — or the earlier
      // sale with the tenders recorded on its order, which are that order's
      // (HeldCardPayment.mayBeRecorded). A new order would take them again
      // under keys of its own: a gift card or store credit charged twice,
      // cash recorded twice. So this press carries that order on when it
      // sends it with those tenders as they were, and otherwise starts
      // nothing.
      final left = holdCtl.current;
      if (left != null && left.belongsTo(tenant)) {
        if (!left.carriesOn(saleId, draft.orderSignature, draft.tenderPrints)) {
          if (!mounted) return;
          setState(() => _processing = false);
          await _tellHeldRecorded(dio, holdCtl, left);
          return;
        }
        carried = left;
      }
    }
    final idemBase = carried?.base ?? newId();
    // How many of the sale's first tenders the server may have acted on under
    // these keys; the card tries spent at each place; the places taken.
    var fixed = carried?.fixed ?? 0;
    final termTries = <int, int>{...?carried?.termTries};
    final approvedBefore = <int>{...?carried?.approved};
    // The tenders the server has recorded under these keys: never sent again.
    final paidBefore = <int>{...?carried?.paid};
    final paidNow = <int>{};
    // The attempt each card place has on its machine, as the till heard it.
    final attemptIds = <int, String>{...?carried?.attemptIds};
    // The tenders a card machine approved during this press. A card on a
    // machine with no answer is never queued as paid (below).
    final approvedOnMachine = <int>{};
    // What [fixed] goes down to when the server refuses what place [i] sent:
    // that place may change, nothing having been taken there, but never a
    // place before a card taken. Nor, unless the answer is [definite] (a card
    // declined: nothing after it was ever sent), below what the held sale had
    // sent: a press carrying it on presents every tender again from the first,
    // and a refusal of one an earlier press already sent — a gateway's 503 or
    // 429, a check payment-svc makes before it looks the key up — says nothing
    // about the card sent after it, still at the machine under its place's key.
    int reopenAt(int i, {bool definite = false}) => [
          i,
          if (!definite) carried?.fixed ?? 0,
          for (final p in {...approvedBefore, ...approvedOnMachine}) p + 1,
        ].reduce(math.max);
    // A sale with a card on a machine, or one carrying such a sale on, is held
    // from the moment its order exists until it completes.
    final holding = carried != null || tenders.any((t) => t.terminalId != null);
    var sale = OfflineSale(
      id: idemBase,
      capturedAt: DateTime.now(),
      rungUpBy: rungUpBy,
      storeId: storeId,
      currency: currency,
      orderId: settlement?.orderId,
      total: due,
      itemCount: cart.fold<int>(0, (s, l) => s + l.itemCount),
      orderRequest: orderRequest,
      tenders: owed,
    );

    // The hold as this press stands: written through to the device, true when
    // the device took it. The order it is against is the one this press
    // placed, or the held one when this press never heard back from the order
    // post.
    Future<bool> keepHold() {
      if (!holding) return Future<bool>.value(true);
      return holdCtl.hold(HeldCardPayment(
        base: idemBase,
        saleId: saleId,
        tenantId: tenant,
        signature: draft.signature,
        orderSignature: draft.orderSignature,
        tenderPrints: draft.tenderPrints,
        orderId: sale.orderId ?? carried?.orderId,
        sale: asItWas,
        fixed: fixed,
        termTries: Map.unmodifiable(termTries),
        approved: {...approvedBefore, ...approvedOnMachine},
        paid: {...paidBefore, ...paidNow},
        attemptIds: Map.unmodifiable(attemptIds),
        // A reopened place this press has sent to, or may have, is free no
        // longer: its key may stand for something the server has.
        freed: {
          for (final p in carried?.freed ?? const <int>{})
            if (p >= fixed) p,
        },
      ));
    }

    // The card line a machine gave for place [i] on an earlier press, back
    // onto the tender the receipt is built from, when the tender staged now
    // has none (taken off and put on again by hand).
    void receiptLineFromHold(int i) {
      final was = carried?.sale.tenders;
      if (was == null || i >= was.length || i >= tenders.length) return;
      final line = was[i].terminalReceiptLine;
      if (line == null || line.isEmpty) return;
      if (tenders[i].terminalReceiptLine?.isNotEmpty == true) return;
      tenders[i] = tenders[i].withTerminalOutcome(line);
      if (mounted && i < _tenders.length) _tenders[i] = tenders[i];
    }

    // The screen is gone mid-sale: a sale holding a card starts nothing more.
    // What it sent is held, and the next press carries it on under the same
    // keys — nobody is there to be told, and a sale finished out of sight would
    // leave the till looking unpaid.
    void stopIfLeft() {
      if (holding && !mounted) throw const _PressLeft();
    }

    try {
      // 1. Place the POS order (server is authoritative for the total). An
      // exchange's new order is already placed: it is settled as it stands.
      final String orderId;
      var order = <String, dynamic>{};
      if (settlement != null) {
        orderId = settlement.orderId;
      } else {
        final orderResp = await dio.post(
          '/${ApiConstants.order}/orders',
          data: sale.orderRequest,
          options: Options(headers: {'Idempotency-Key': derivedId(idemBase, 'order')}),
        );
        order = orderResp.data['data'] as Map<String, dynamic>;
        orderId = order['id'] as String? ?? '';
        sale = sale.copyWith(orderId: orderId);
      }
      // The hold is on the device before any amount goes to a card machine, so
      // neither leaving the screen nor closing the app can lose it.
      await keepHold();

      // 2. Take each tender against the order. Progress is tracked on `sale`
      // step by step, so if the network drops here only the steps that have not
      // landed are queued. A gift card is charged FIRST, through its redeem: it
      // is the tender, and a refused card stops the sale here, before any
      // further tender is taken.
      for (var i = 0; i < sale.tenders.length; i++) {
        stopIfLeft();
        final t = sale.tenders[i];
        final giftCode = t.giftCardCode;
        // Recorded on an earlier press under these keys: not sent again. The
        // server would only hand the record back — or, for a check it makes
        // before it looks the key up (the cash limit), refuse the copy on
        // every press, and the till could never finish the sale.
        if (paidBefore.contains(i)) {
          receiptLineFromHold(i);
          sale = sale.markTender(i,
              tenderDone: true, redeemDone: giftCode != null ? true : null);
          continue;
        }
        if (giftCode != null) {
          fixed = math.max(fixed, i + 1);
          await keepHold();
          try {
            await dio.post(
              '/${ApiConstants.order}/gift-cards/$giftCode/redeem',
              data: {'amount': t.amount, 'orderId': orderId},
              options: Options(
                  headers: {'Idempotency-Key': derivedId(idemBase, 'gift:$i')}),
            );
          } catch (e) {
            // Refused: nothing was charged, so this place may change — unless
            // the refusal was of an earlier press's redeem (reopenAt).
            if (!isOfflineError(e)) fixed = reopenAt(i);
            rethrow;
          }
          sale = sale.markTender(i, tenderDone: true, redeemDone: true);
          paidNow.add(i);
          await keepHold();
          continue;
        }
        // A card on a terminal is approved BEFORE it is recorded (07.16). The
        // key is derived from the sale and the tender's position, never freshly
        // generated: a second press with the same key finds the first attempt
        // instead of starting a second EMV transaction on a real card.
        // The terminal is read from the till's own tender, not from the queued
        // one. Deliberate: an OfflineTender is persisted and replayed, and a
        // card must never be sent to a terminal minutes or hours after the
        // customer has left. So a sale is queued with a card on a machine only
        // once the machine has approved it; one the machine has not answered
        // is never queued as paid (the catch below).
        final terminalId = tenders[i].terminalId;
        // Whether a card at this place may already be at a machine under its
        // key: sent by the held sale this press carries on, or heard of.
        final sentBefore =
            i < (carried?.fixed ?? 0) || attemptIds.containsKey(i);
        final fixedBefore = fixed;
        fixed = math.max(fixed, i + 1);
        final kept = await keepHold();
        if (terminalId != null && approvedBefore.contains(i)) {
          // The machine approved this card on an earlier press: that is final,
          // and the money is taken. It is not asked after again — a machine
          // retired since refuses the ask before it looks the key up, every
          // time — and goes straight to its record, with the card line the
          // machine gave for the receipt.
          receiptLineFromHold(i);
        } else if (terminalId != null) {
          // Write-ahead: a new amount goes to a machine only once its hold is
          // on the device, or closing the app would forget a card at the
          // machine. One already sent is only asked after again under its
          // key, which sends nothing new.
          if (!kept && !sentBefore) {
            fixed = fixedBefore;
            throw _HoldNotKept(i);
          }
          stopIfLeft();
          TerminalOutcome outcome;
          if (mounted) setState(() => _atCardMachine = true);
          // One wait for this card, whatever happens in it: what is read after
          // a refusal shares the time the press began with.
          final until = clock().add(wait.limit);
          var unheard = false;
          try {
            // A press the till hears nothing back from — its own receive timeout
            // while the cardholder is at the PIN — is not the till being
            // offline: it is asked again under the same key, which hands back
            // the attempt the server already has (REQUESTED, or settled) and
            // never starts a second payment, then read until it settles.
            outcome = await takeCardAndWait(
              dio,
              terminalId: terminalId,
              orderId: orderId,
              amount: t.amount,
              currency: currency,
              idempotencyKey: terminalKey(idemBase, i, termTries[i] ?? 0),
              wait: wait,
              now: clock,
              until: until,
              abandoned: () => !mounted,
              onUnheard: () => unheard = true,
            );
          } catch (e) {
            // Refused before any machine was asked, for a card this hold never
            // sent and with every ask answered: nothing can be at a machine
            // for this place, so it may change — another tender, another
            // machine — like a decline (reopenAt). A card an earlier press
            // sent, or an ask that went unanswered, may be at the machine.
            if (!sentBefore && !unheard && _machineNotAsked(e)) {
              fixed = reopenAt(i);
            }
            // The machine was retired (or moved to another store) after this
            // card went to it: payment-svc refuses the ask before it looks the
            // key up, on every press, and nothing brings the machine back. The
            // attempt the till heard is read instead — a read starts nothing —
            // and waited for while it is still at the machine, within the time
            // this press began with. One the till never heard is found among
            // the order's attempts (attemptOfPlace). With none to read, the
            // refusal stands and the hold with it.
            if (!sentBefore || !_terminalGone(e)) rethrow;
            var known = attemptIds[i];
            if (known == null && until.isAfter(clock())) {
              try {
                known = attemptOfPlace(
                  await readTerminalAttemptsOfOrder(dio, orderId,
                      within: until.difference(clock())),
                  place: i,
                  terminalId: terminalId,
                  amount: t.amount,
                  currency: currency,
                  attemptIds: attemptIds,
                )?.id;
              } catch (_) {
                // Nothing read: the refusal stands.
              }
            }
            if (known == null) rethrow;
            final read = await _readKnownAttempt(dio, known,
                wait: wait, now: clock, until: until, abandoned: () => !mounted);
            if (read == null) rethrow;
            outcome = read;
          } finally {
            if (mounted) setState(() => _atCardMachine = false);
          }
          // The attempt this place has on the machine, for the next press to
          // read if the machine is retired under it.
          if (outcome.heard) attemptIds[i] = outcome.id;
          // The wait ran out with the card still at the machine, or with no
          // answer at all: not a decline and not an error, so nothing is
          // recorded and nothing is queued.
          if (outcome.pending) throw TerminalStillPending(outcome);
          if (!outcome.approved) {
            // A decline took nothing, so this place may change, and its key is
            // spent: the server would only hand the decline back. A timeout may
            // have taken the card, so it keeps both until somebody has looked.
            if (!outcome.uncertain) {
              termTries[i] = (termTries[i] ?? 0) + 1;
              attemptIds.remove(i);
              fixed = reopenAt(i, definite: true);
            }
            throw TerminalNotApproved(outcome);
          }
          approvedOnMachine.add(i);
          // Back onto the till's own tender, because that is what the receipt is
          // built from. Kept in a side map it would print nothing, which is how a
          // card receipt ends up without the line the scheme rules require.
          final line = outcome.receiptLine;
          if (line != null && line.isNotEmpty) {
            tenders[i] = tenders[i].withTerminalOutcome(line);
            if (mounted && i < _tenders.length) _tenders[i] = tenders[i];
          }
          // The card is taken: whatever happens next, the next press finds it.
          await keepHold();
          stopIfLeft();
        }
        // A card a machine took is recorded as exactly that payment
        // (terminalPaymentId), which is what frees the machine for its next
        // card; kept on the sale, so a replay from the offline queue names it
        // too. A machine's approval the till never heard (an older hold) is
        // matched by payment-svc to the order's unrecorded approval.
        final attemptId = terminalId != null ? attemptIds[i] : null;
        if (attemptId != null) {
          final had = sale.tenders[i];
          sale = sale.copyWith(tenders: [
            for (var j = 0; j < sale.tenders.length; j++)
              if (j == i)
                OfflineTender(
                  body: {...had.body, 'terminalPaymentId': attemptId},
                  amount: had.amount,
                  giftCardCode: had.giftCardCode,
                  tenderDone: had.tenderDone,
                  redeemDone: had.redeemDone,
                )
              else
                sale.tenders[j],
          ]);
        }
        try {
          await dio.post(
            '/${ApiConstants.payment}/payments',
            data: {...sale.tenders[i].body, 'orderId': orderId},
            options: Options(headers: {'Idempotency-Key': derivedId(idemBase, 'pay:$i')}),
          );
        } catch (e) {
          // This place's own approval, recorded on this order by somebody
          // else — another till on the same machine finishing it from its own
          // refusal, this till's answer having been lost — is this tender,
          // recorded at exactly what the machine took. payment-svc says so
          // only of an approval of this same order (another order's it
          // refuses first), and says it on every press: taken for a refusal,
          // the till could never finish the sale.
          final recordedElsewhere = attemptId != null &&
              cardApprovalAlreadyRecorded(e, sale.tenders[i].body);
          if (!recordedElsewhere) {
            // Refused, so nothing was recorded and this place may change —
            // unless a machine took the card for it, which stays taken
            // whatever the books say, under its keys, or the refusal was of
            // an earlier press's payment (reopenAt).
            if (!isOfflineError(e)) fixed = reopenAt(i);
            rethrow;
          }
        }
        sale = sale.markTender(i, tenderDone: true);
        // Recorded: the next press, if there is one, does not send it again.
        paidNow.add(i);
        await keepHold();
      }

      // 3. Journal the sale to the POS transaction journal. Its own try: by this
      // point the money is taken and the order is confirmed, so a journal that
      // fails must not be reported to the cashier as a failed sale. A *network*
      // failure is rethrown so the sale is queued and the journal replays with
      // it; anything else means the server answered and refused, which no retry
      // fixes and which the customer must not be made to wait for.
      try {
        await dio.post('/${ApiConstants.order}/pos/log/orders/$orderId');
        sale = sale.copyWith(posLogDone: true);
      } catch (e) {
        if (isOfflineError(e)) rethrow;
        debugPrint('Sale $orderId completed but was not journalled: $e');
      }

      // A completed sale is the strongest activity signal — keep the session alive.
      session.touch();

      // The legal receipt number — and the regime's stamp on the sale — is
      // issued when the payment reaches order-svc, a few seconds after the
      // last tender. Wait a bounded time for it rather than print a receipt
      // without one.
      final fiscalStamp = await awaitFiscalReceipt(dio, orderId);
      final fiscalNumber = fiscalStamp?.fullNumber;

      // The cards this sale issued, whose codes the customer takes away: asked
      // once the payment has landed, since that is when they exist.
      final soldCards = cart.any((l) => l.giftCard)
          ? await fetchSoldGiftCards(dio, orderId)
          : const <SoldGiftCard>[];

      // Paid, with the screen gone. A sale holding a card stays held, so the
      // next press finishes it under the same keys — the same order, the same
      // card, its receipt — and never takes it again. One without is cleared,
      // so it cannot be rung up and paid a second time.
      if (!mounted) {
        if (!holding) clearTill(settlement != null);
        return;
      }

      // A sale at shelf prices is receipted from the server's own document, so
      // the printed VAT table is the one in the books; asked only then, and
      // never blocking the receipt (the till makes the table itself if it fails).
      final vatFromServer = cart.isNotEmpty &&
              productLines(cart).isNotEmpty &&
              productLines(cart).every((l) => l.taxInclusive)
          ? await fetchReceiptVat(dio, orderId)
          : null;

      // Capture everything needed for the receipt before clearing state.
      final receiptData = _buildReceiptData(
        orderId: orderId,
        vatFromServer: vatFromServer,
        fiscalNumber: fiscalNumber,
        fiscalStamp: fiscalStamp,
        fiscalNumberNote: fiscalNumber == null
            ? 'Receipt number not issued yet. Reprint once it is.'
            : null,
        cartSnapshot: [...cart],
        tenderSnapshot: [...tenders],
        discount: discount,
        total: (order['total'] as num?)?.toDouble() ?? due,
        currency: currency,
        customerName: customer?.fullName.isNotEmpty == true
            ? customer!.fullName
            : customer?.email,
      ).withSoldCards(soldCards);

      final change = _change;
      final email = customer?.email;
      _clearAfterSale(settlement != null);
      _tenders.clear();
      // The sale is complete: nothing is held for it any more, and the next
      // press need not wait for the receipt.
      if (holding) unawaited(holdCtl.release());
      endPress();
      setState(() => _processing = false);

      // The receipt comes out the way this till is set up to (09.12): the
      // browser's dialog, a thermal printer, a file. A cash sale may open the drawer.
      await _produceReceipt(receiptData, kickDrawer: true);
      if (!mounted) return;

      // The customer's side of the counter: what was paid and the change due.
      ref.read(customerDisplayChannelProvider).post(customerDisplayPaid(
            storeName: receiptData.storeName,
            currency: currency,
            total: receiptData.total,
            paid: receiptData.tenders.fold<double>(0, (s, t) => s + t.amount),
            change: change,
          ));

      await _showReceiptDialog(
        orderId,
        currency,
        change,
        email,
        receiptData: receiptData,
      );
    } catch (e) {
      // What the cards of this sale came to, in the till's words: taken, maybe
      // still on a machine, or not taken at all. Every place before [fixed] was
      // sent; a card there that this press did not hear approved may yet be.
      final taken = {...approvedBefore, ...approvedOnMachine};
      List<double> amountsAt(Iterable<int> places) => [
            for (final i in places.toList()..sort())
              if (i < tenders.length) tenders[i].amount,
          ];

      // A manager's record of what the machine shows for the card this press
      // stopped at. Taken: a card taken, recorded on the next press under its
      // keys and naming its attempt, never asked for again. Nothing taken:
      // its place may change, under a key of its own; a card taken before it
      // stays held — and so does the sale's order, whatever else it sent. The
      // machine's own answer may still come, and payment-svc keeps an
      // approval that follows a person's "nothing taken": let go, the order
      // would be left reading unpaid while the same basket was paid on a
      // second one, and that approval recorded on it would pay the basket
      // twice. Kept, the next tender is on this order — the approval finds it
      // paid, or, the sale still open here, finishes it once.
      Future<void> recordDecision(TerminalOutcome decided) async {
        final failedAt = sale.tenders.indexWhere((t) => !t.isComplete);
        if (decided.approved) {
          if (failedAt >= 0) {
            approvedBefore.add(failedAt);
            attemptIds[failedAt] = decided.id;
          }
          await keepHold();
          _snack('Recorded as taken on the card. Press Complete Sale to '
              'finish the sale — do not take the card again.');
        } else if (decided.notTaken) {
          if (failedAt >= 0) {
            termTries[failedAt] = (termTries[failedAt] ?? 0) + 1;
            attemptIds.remove(failedAt);
            fixed = reopenAt(failedAt, definite: true);
          }
          await keepHold();
          _snack('Recorded as not taken. Take the card again, or another '
              'tender.');
        }
      }

      // The screen went while the sale was under way: what it sent is held,
      // and there is nobody to tell.
      if (e is _PressLeft) {
        await keepHold();
        return;
      }
      // The hold would not go onto the device, so the card machine was not
      // asked: closing the app would have forgotten a card at the machine.
      // What was sent before it is held on this till as before.
      if (e is _HoldNotKept) {
        await keepHold();
        if (!mounted) return;
        setState(() => _processing = false);
        final already = _takenWords(amountsAt(taken), currency);
        final notAsked = amountsAt([e.place]);
        await showDialog<void>(
          context: context,
          builder: (ctx) => AlertDialog(
            key: const Key('tender-hold-not-saved'),
            title: const Text('Card machine not asked'),
            content: Text(
              'This till could not save the card payment on the device, so the '
              'card machine was not asked for '
              '${notAsked.isEmpty ? 'it' : AppFormat.money(notAsked.single, currencyCode: currency)}'
              ' and nothing was taken on that card.'
              '${already.isEmpty ? '' : ' $already'}'
              '\n\nPress Complete Sale to try again, or take another tender. If '
              'it keeps happening, restart the till.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(ctx).pop(),
                child: const Text('OK'),
              ),
            ],
          ),
        );
        return;
      }
      // The card machine has not answered and nothing went wrong, so this is
      // neither a decline nor a failure and there is nothing to queue: the order
      // is placed and awaiting payment, the card is still at the machine, and
      // the keys are kept so pressing Complete Sale again asks after this same
      // payment instead of starting another.
      if (e is TerminalStillPending) {
        await keepHold();
        if (!mounted) return;
        setState(() => _processing = false);
        final heard = e.outcome.heard;
        final already = _takenWords(amountsAt(taken), currency);
        // A request left at the machine — its call gone, payment-svc
        // restarted under it — never answers, and every press would wait for
        // it again. A manager may record what the machine shows instead, once
        // it has had its time to answer (payment-svc says when).
        final may = heard && _mayDecideCards();
        final settle = await showDialog<bool>(
          context: context,
          builder: (ctx) => AlertDialog(
            key: const Key('tender-terminal-pending'),
            title: Text(heard
                ? 'Still waiting for the card machine'
                : 'No answer from the card machine yet'),
            content: Text(
              '${heard ? 'The card payment has not finished on the machine yet. '
                  'The sale is saved and still awaiting payment.' : 'The till has '
                  'not heard back about this card payment, so it may still be on '
                  'the machine. The sale is saved and still awaiting payment.'}'
              '${already.isEmpty ? '' : '\n\n$already'}'
              '\n\nDo not take the card again. '
              '${heard ? 'When the machine has answered, press Complete Sale to '
                  'carry on with this same payment.' : 'Look at the card machine, '
                  'then press Complete Sale to carry on with this same payment.'}'
              '${may ? '\n\nIf the machine shows it finished, or shows nothing, '
                  'record what it shows.' : ''}',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(ctx).pop(false),
                child: const Text('OK'),
              ),
              if (may)
                FilledButton(
                  key: const Key('tender-pending-settle'),
                  onPressed: () => Navigator.of(ctx).pop(true),
                  child: const Text('Record what the machine shows'),
                ),
            ],
          ),
        );
        if (settle != true || !mounted) return;
        final decided = await _settleOnMachine(dio, e.outcome);
        if (decided == null || !mounted) return;
        await recordDecision(decided);
        return;
      }
      // A card the terminal did not approve (07.16). A definite answer, so the
      // sale is never queued for replay — but the order IS placed and awaiting
      // payment, so the cashier can take another tender rather than start again.
      if (e is TerminalNotApproved) {
        // The card the machine did not take: the first tender not done.
        final failedAt = sale.tenders.indexWhere((t) => !t.isComplete);
        // A card taken earlier in this sale keeps it held, so the next press
        // carries on that card instead of taking it again; with nothing taken
        // and nothing else sent, the hold goes once the server confirms that
        // no card attempt on the order took anything — the order let go
        // remembered, never to have a card recorded on it from here. Only on
        // the machine's own word, though: one a manager recorded as not
        // taken (from another till: this press was handed it back) may yet
        // be answered as approved, so its sale keeps its order
        // (recordDecision).
        if (!e.outcome.uncertain &&
            !e.outcome.notTaken &&
            taken.isEmpty &&
            fixed == 0 &&
            holding) {
          final check = await checkOrderCardPayments(
              dio, sale.orderId ?? carried?.orderId);
          if (check.state == HeldCardState.settled) {
            await holdCtl
                .releaseUnfinished(sale.orderId ?? carried?.orderId);
          } else {
            await keepHold();
          }
        } else {
          await keepHold();
        }
        if (!mounted) return;
        setState(() => _processing = false);
        final already = _takenWords(amountsAt(taken), currency);
        if (e.outcome.uncertain) {
          // A timeout may have charged the card. Nothing the cashier presses
          // here lets it go: it holds this sale (and payment-svc holds the
          // machine) until a manager has looked at the machine and recorded
          // what it shows, with a reason. A snackbar can be missed and this
          // must not be, so it blocks until somebody answers it.
          final decided = await _askAboutTimedOut(dio, e.outcome,
              already: already);
          if (decided == null || !mounted) return;
          await recordDecision(decided);
        } else if (already.isEmpty) {
          _snack('${e.outcome.message} — try another tender.', error: true);
        } else {
          final declined = failedAt >= 0
              ? AppFormat.money(tenders[failedAt].amount, currencyCode: currency)
              : null;
          _snack(
              '${e.outcome.message}. $already Take another tender for '
              '${declined == null ? 'the rest' : 'the $declined still owed'}.',
              error: true);
        }
        return;
      }
      if (!isOfflineError(e)) {
        // The server answered and said no — to the order, a tender, or a card
        // asked after again (a machine retired since, a gateway that could not
        // reach payment-svc). Replaying would get the same answer, so the sale
        // must not be queued — the cashier has to deal with it now. Whatever it
        // sent to a card machine stays held: the next press carries it on.
        await keepHold();
        if (!mounted) return;
        setState(() => _processing = false);
        // A refusal about the contact phone (phone-at-the-till) sits at the
        // field, the same place the cashier would go to fix it — never a
        // snackbar — and the sale is kept exactly as it was, ready to retry.
        final code = apiErrorCode(e);
        if (code == 'ORDER_CONTACT_PHONE_REQUIRED' ||
            code == 'ORDER_CONTACT_PHONE_INVALID') {
          setState(() => _phoneError = _phoneServerErrorMessage(code!, tillPhone));
          return;
        }
        // The card machine holds an earlier card payment that is not settled,
        // so payment-svc took no card for this sale (the place reopened
        // above). What holds it, and the only ways out, are shown.
        if (code == 'TERMINAL_UNSETTLED_APPROVAL') {
          await _tellMachineHeld(dio, e, offlineQueue);
          return;
        }
        // This sale's order was cancelled or voided since it was rung up:
        // nothing more is taken or recorded for it, and payment-svc puts back
        // on the card anything a machine took for it. The hold has nothing
        // left to finish.
        if (code == 'PAYMENT_ORDER_GIVEN_UP') {
          if (holding) await holdCtl.release();
          if (!mounted) return;
          _snack(
              'This sale was cancelled or voided, so nothing more is taken or '
              'recorded for it. Anything a card machine took for it goes back '
              'on the card. Press Complete Sale to ring it up as a new sale.',
              error: true);
          return;
        }
        // A gift card the server would not charge says why in words; the sale
        // stays as it was, ready for another tender.
        final already = _takenWords(amountsAt(taken), currency);
        final refused = returnRefusalLabel(code) ??
            friendlyError(e, fallback: 'Sale failed.');
        _snack(
            already.isEmpty
                ? refused
                : '$refused $already Press Complete Sale again to carry on '
                    'with it — do not take the card again.',
            error: true);
        return;
      }
      // A gift card is not issued offline. If no order has reached the server
      // — not this press's, nor a held sale's it carries on — and no card was
      // taken, nothing has been sent: the sale is kept as it is rather than
      // queued, so the customer is not handed a receipt with no card behind
      // it. A sale carried on has sent its order and maybe its cards: it is
      // said below, card by card, never "nothing was sent".
      final sellsGiftCard = cart.any((l) => l.giftCard);
      if (sellsGiftCard &&
          sale.orderId == null &&
          carried?.orderId == null &&
          taken.isEmpty) {
        await keepHold();
        if (!mounted) return;
        setState(() => _processing = false);
        _snack(
            'A gift card cannot be sold while the till is offline. Nothing was '
            'sent: take the payment back, or try again once the network is back.',
            error: true);
        return;
      }
      // A card on a machine is paid only when the machine has said so, and the
      // till cannot ask a machine without the network. A sale with such a card
      // the machine has not approved is never queued as paid — it would print
      // a receipt for a card nobody took, and replay as a card tender whatever
      // the machine decided. It is kept on the till under the same keys, so the
      // next press finds the order (and any card payment) the server already
      // has instead of starting another.
      final unanswered = [
        for (var i = 0; i < sale.tenders.length; i++)
          if (i < tenders.length &&
              tenders[i].terminalId != null &&
              !approvedOnMachine.contains(i))
            i,
      ];
      if (unanswered.isNotEmpty) {
        await keepHold();
        if (!mounted) return;
        setState(() => _processing = false);
        // A card at a place this sale has sent to a machine (in this press or
        // an earlier one) may have been taken; one after it was never asked.
        // A card taken before them is said as taken — never "nothing was
        // taken" over a split sale whose first card went through.
        final takenAmounts = amountsAt(taken);
        final maybe = [
          for (final i in unanswered)
            if (i < fixed && !taken.contains(i)) i,
        ];
        final notAsked = [
          for (final i in unanswered)
            if (i >= fixed && !taken.contains(i)) i,
        ];
        final already = _takenWords(takenAmounts, currency);
        final String title;
        final String body;
        if (maybe.isNotEmpty) {
          title = 'No answer from the card machine yet';
          body = 'The till lost the network before it heard about '
              '${_cardPaymentWords(amountsAt(maybe), currency)}. '
              '${already.isEmpty ? '' : '$already '}'
              '${notAsked.isEmpty ? '' : '${_notAskedWords(amountsAt(notAsked), currency)} '}'
              'The sale is kept on this till and is not paid yet.\n\nDo not take '
              'the card again. When the network is back, press Complete Sale to '
              'carry on with this same payment.';
        } else if (already.isNotEmpty && notAsked.isNotEmpty) {
          title = 'Card not taken for the rest';
          body = '$already ${_notAskedWords(amountsAt(notAsked), currency)} '
              'The sale is kept on this till and is not fully paid yet.\n\n'
              'Do not take the first card again. When the network is back, press '
              'Complete Sale to carry on with this same sale.';
        } else if (already.isNotEmpty) {
          title = 'Sale not finished';
          body = '$already The till lost the network before the sale was '
              'finished, so it is kept on this till.\n\nDo not take the card '
              'again. When the network is back, press Complete Sale to finish it.';
        } else {
          title = 'Card not taken';
          body = 'The till lost the network before the card machine was asked, '
              'so nothing was taken on the card. The sale is kept on this till '
              'and is not paid yet.\n\nWhen the network is back, press Complete '
              'Sale to try again, or take another tender.';
        }
        await showDialog<void>(
          context: context,
          builder: (ctx) => AlertDialog(
            key: const Key('tender-terminal-offline'),
            title: Text(title),
            content: Text(body),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(ctx).pop(),
                child: const Text('OK'),
              ),
            ],
          ),
        );
        return;
      }
      // A gift card sale carried on, its order already placed and its cards
      // all approved, whose press did not reach the server: still not issued
      // offline. It is kept on the till under the same keys, and what was
      // taken is said as taken.
      if (sellsGiftCard && sale.orderId == null) {
        await keepHold();
        if (!mounted) return;
        setState(() => _processing = false);
        final already = _takenWords(amountsAt(taken), currency);
        _snack(
            'A gift card cannot be sold while the till is offline. '
            '${already.isEmpty ? '' : '$already '}'
            'The sale is kept on this till: press Complete Sale again once the '
            'network is back.',
            error: true);
        return;
      }
      // The server could not be reached. The customer has paid and is standing
      // there, so the sale completes at the till and whatever it still owes the
      // server is held until the network is back. The queue now has the sale
      // under the same keys, so nothing is held for it here any more.
      await offlineQueue.enqueue(sale);
      if (holding) unawaited(holdCtl.release());
      if (!mounted) {
        clearTill(settlement != null);
        return;
      }
      await _finishOffline(sale, [...cart], discount, currency, customer,
          exchange: settlement != null);
    }
  }

  /// "£6.00 was taken on the card." — what a sale's cards have taken, said as
  /// taken; empty when none.
  String _takenWords(List<double> amounts, String currency) {
    if (amounts.isEmpty) return '';
    final what = _join([
      for (final a in amounts) AppFormat.money(a, currencyCode: currency),
    ]);
    return amounts.length == 1
        ? '$what was taken on the card, and stays with this sale.'
        : '$what were taken on cards, and stay with this sale.';
  }

  /// "the card payment of £6.00".
  String _cardPaymentWords(List<double> amounts, String currency) {
    final what = _join([
      for (final a in amounts) AppFormat.money(a, currencyCode: currency),
    ]);
    return amounts.length == 1
        ? 'the card payment of $what'
        : 'the card payments of $what';
  }

  /// "The card machine was not asked for the £6.00 still owed, so nothing was
  /// taken for it."
  String _notAskedWords(List<double> amounts, String currency) {
    if (amounts.isEmpty) return '';
    final what = _join([
      for (final a in amounts) AppFormat.money(a, currencyCode: currency),
    ]);
    return 'The card machine was not asked for the $what still owed, so '
        'nothing was taken for ${amounts.length == 1 ? 'it' : 'them'}.';
  }

  static String _join(List<String> parts) => parts.length <= 1
      ? parts.join()
      : '${parts.sublist(0, parts.length - 1).join(', ')} and ${parts.last}';

  /// Empties the till after a sale through notifiers read now, so it can be
  /// done when the screen is already gone.
  void Function(bool exchange) _tillClearer() {
    final settlementCtl = ref.read(posExchangeSettlementProvider.notifier);
    final cartCtl = ref.read(posCartProvider.notifier);
    final customerCtl = ref.read(posCustomerProvider.notifier);
    final discountCtl = ref.read(posDiscountProvider.notifier);
    final reasonCtl = ref.read(posDiscountReasonProvider.notifier);
    final phoneCtl = ref.read(posWalkInPhoneProvider.notifier);
    return (exchange) {
      if (exchange) {
        settlementCtl.state = null;
        return;
      }
      cartCtl.clear();
      customerCtl.state = null;
      discountCtl.state = 0;
      reasonCtl.state = '';
      phoneCtl.state = '';
    };
  }

  /// What the sale sends with [tenders] staged: the order (unless an exchange's
  /// already exists), each tender's request, and a fingerprint of the lot — a
  /// press that sends exactly what a held sale sent may carry on its payment
  /// under the same keys. Read from the till's state as it is now.
  ({
    Map<String, dynamic> orderRequest,
    List<OfflineTender> owed,
    String? signature,
    String? orderSignature,
    List<String?> tenderPrints,
  }) _draft(List<PosTender> tenders) {
    final settlement = ref.read(posExchangeSettlementProvider);
    final List<PosLine> cart = settlement?.lines ?? ref.read(posCartProvider);
    final storeId = ref.read(posStoreProvider);
    final customer = settlement != null ? null : ref.read(posCustomerProvider);
    final walkInPhone = ref.read(posWalkInPhoneProvider);
    final discount = _discount;
    final currency = _currency;
    final orderRequest = <String, dynamic>{
      'storeId': storeId,
      'channel': 'POS',
      // INSTORE: the goods leave with the customer now. This said PICKUP,
      // which means "collect later" everywhere else in the platform. The
      // server treats a paid POS sale as handed over either way (SJ-D40),
      // because sales already queued offline replay with PICKUP — but the
      // label on new sales should say what actually happened.
      'fulfilmentType': 'INSTORE',
      'currency': currency,
      if (discount > 0) 'discountAmount': discount,
      if (discount > 0)
        'discountReason': ref.read(posDiscountReasonProvider).trim(),
      if (customer != null) 'customerId': customer.id,
      'contactPhone': customer != null ? '' : walkInPhone,
      // Cards sold on this sale: issued when it is paid, for what was paid.
      if (cart.any((l) => l.giftCard)) 'giftCardLoads': giftCardLoadsOf(cart),
      'items': [
        for (final l in productLines(cart))
          {
            'variantId': l.variantId,
            'qty': l.qty,
            'unitPrice': l.unitPrice,
            if (l.weighingInstrumentId != null)
              'weighingInstrumentId': l.weighingInstrumentId,
            if (l.markdownId != null) 'markdownId': l.markdownId,
            // The pack's own lot and expiry, when a 2D code carried them:
            // order-svc checks them against open recalls as the till does.
            if (l.batchNo != null) 'batchNo': l.batchNo,
            if (l.expiry != null)
              'expiry': l.expiry!.toIso8601String().substring(0, 10),
          },
      ],
    };
    // STORE_CREDIT redemption is done server-side by payment-svc (it redeems the
    // customer's balance as part of capturing the tender). A GIFT_CARD tender is
    // taken by redeeming the card, and payment-svc records the tender itself from
    // the redemption: no GIFT_CARD payment is posted (it would be refused).
    final owed = <OfflineTender>[
      for (final t in tenders)
        OfflineTender(
          amount: t.amount,
          giftCardCode: t.method == 'GIFT_CARD' ? t.giftCardCode : null,
          body: {
            'amount': t.amount,
            'method': t.paymentMethod,
            'storeId': storeId,
            if (t.method == 'GIFT_CARD') 'reference': t.giftCardCode,
            if (t.method == 'STORE_CREDIT') 'reference': 'STORE_CREDIT',
            if (t.method == 'STORE_CREDIT') 'customerId': t.customerId,
            if (t.method == 'STORE_CREDIT') 'currency': currency,
          },
        ),
    ];
    final signature = saleFingerprint([
      settlement?.orderId,
      orderRequest,
      [for (final t in owed) t.body],
      [for (final t in tenders) t.terminalId],
    ]);
    // The order alone, and each tender alone: a press may change a tender the
    // server never acted on and still carry on the held order and cards.
    final orderSignature = saleFingerprint([settlement?.orderId, orderRequest]);
    final tenderPrints = [
      for (var i = 0; i < tenders.length; i++)
        saleFingerprint([owed[i].body, tenders[i].terminalId]),
    ];
    return (
      orderRequest: orderRequest,
      owed: owed,
      signature: signature,
      orderSignature: orderSignature,
      tenderPrints: tenderPrints,
    );
  }

  /// Settles the held card payment before a changed sale starts anything: true
  /// when it took nothing (or what it took has been put back on the card) and
  /// left nothing at the machine; false when the sale must not go on, the
  /// cashier having been told why. True lets the hold go only when nothing is
  /// recorded on its order — and then gives that order up, since no sale will
  /// ever finish it: one with a tender recorded, or that may be, stays with
  /// its card places reopened ([_letGoOrKeep]), and the press goes on only by
  /// carrying that order on.
  ///
  /// It reads what the machine said. A payment still at the machine is waited
  /// for, or cancelled on it at the cashier's word and then waited for: a
  /// cancel settles nothing until the machine answers it, and one that loses
  /// the race to an approval is a card taken. One the machine still has not
  /// answered — a request left at it, payment-svc restarted under it — is
  /// settled by a manager's record of what the machine shows. A card taken
  /// means the customer paid for the sale as it was: it is recorded in the
  /// hold, so putting the sale back finishes it without asking the machine
  /// again, or a manager puts the money back on the card. An approval the
  /// order has recorded — another till finished it — is a tender of that
  /// order, never sent again. A timeout may have taken the card: only a
  /// manager's record of what the machine shows settles it. A payment that
  /// cannot be read is held back as it is.
  Future<bool> _settleHeld(
      Dio dio, HeldCardPaymentNotifier holdCtl, HeldCardPayment held) async {
    final wait = ref.read(terminalWaitProvider);
    final clock = ref.read(terminalClockProvider);
    var check = await checkHeldCardPayment(dio, held);
    if (!mounted) return false;
    if (check.state != HeldCardState.settled) {
      // A question or a notice, never "Processing…" behind it.
      setState(() => _processing = false);
    }
    if (check.state == HeldCardState.atMachine) {
      final cancel = await _askAboutHeldAtMachine(check.atMachine);
      if (cancel != true || !mounted) return false;
      setState(() => _processing = true);
      check = await cancelHeldAtMachine(dio, check, wait: wait, now: clock);
      if (!mounted) return false;
      if (check.state != HeldCardState.settled) {
        setState(() => _processing = false);
      }
    }
    // What the machine took is final, whichever place of the held sale took
    // it: recorded in the hold with its attempt, the press that carries the
    // sale on records it without asking the machine again — which a machine
    // retired since would refuse, every time.
    if (check.state == HeldCardState.taken) {
      final found = placesOfTaken(held, check);
      if (found.isNotEmpty) {
        held = held.withTaken(found);
        await holdCtl.hold(held);
        if (!mounted) return false;
      }
    }
    // An approval of the held sale another till recorded on its order —
    // finishing it from its own refusal, this till's answer lost — is a
    // tender of that order: the press that carries the sale on does not send
    // it again, which payment-svc would refuse as already recorded.
    final recorded = placesRecorded(held, check);
    if (recorded.isNotEmpty) {
      held = held.withRecorded(recorded);
      await holdCtl.hold(held);
      if (!mounted) return false;
    }
    switch (check.state) {
      case HeldCardState.settled:
        return _letGoOrKeep(dio, holdCtl, held, check);
      case HeldCardState.atMachine:
        // The machine has not answered the cancel in the time it had. A
        // request left at it never will: only a manager's record of what the
        // machine shows settles it, and the hold is read again from that.
        final decided =
            await _tellHeldStillAtMachine(dio, check.atMachine.first);
        if (decided == null || !mounted) return false;
        setState(() => _processing = true);
        return _settleHeld(dio, holdCtl, holdCtl.current ?? held);
      case HeldCardState.taken:
        return _tellHeldTaken(dio, holdCtl, held, check);
      case HeldCardState.mayBeTaken:
        // payment-svc can never say what a timed-out payment took, and the
        // cashier's look is not enough to let it go: a manager records what
        // the machine shows, and the hold is read again from that.
        final decided = await _tellHeldMayBeTaken(dio, check.mayBeTaken.first);
        if (decided == null || !mounted) return false;
        setState(() => _processing = true);
        return _settleHeld(dio, holdCtl, holdCtl.current ?? held);
      case HeldCardState.unknown:
        await _tellHeldUnknown(dio, holdCtl, held);
        return false;
    }
  }

  /// The held sale's cards took nothing ([check]): the hold goes, unless its
  /// order has a tender recorded, or one that may be — a gift card redeemed,
  /// store credit, cash ([HeldCardPayment.mayBeRecorded]). Those are that
  /// order's, and a new order would take them again under keys of its own.
  /// So the hold stays, its card places after them reopened, each under a
  /// key of its own ([HeldCardPayment.reopenedAfter]): a press of the same
  /// sale carries the order on with them; any other is refused until the
  /// earlier sale is put back and finished, or cancelled, which refunds them.
  ///
  /// A hold that goes takes its order with it: the sale on the till is
  /// another one, so nothing will ever finish that order. It is given up
  /// first ([_giveUpOrder]) and remembered as let go
  /// ([HeldCardPaymentNotifier.releaseUnfinished]), never left awaiting
  /// payment for a late answer from the machine to land on.
  /// True while the screen stands.
  Future<bool> _letGoOrKeep(Dio dio, HeldCardPaymentNotifier holdCtl,
      HeldCardPayment held, HeldCardCheck check) async {
    if (held.mayBeRecorded.isEmpty) {
      await _giveUpOrder(dio, held);
      await holdCtl.releaseUnfinished(held.orderId);
    } else {
      final reopened = held.reopenedAfter(check);
      if (!identical(reopened, held)) await holdCtl.hold(reopened);
    }
    return mounted;
  }

  /// Why order-svc is told an order was cancelled when the till lets its
  /// sale go unfinished: kept in the order's history.
  static const _letGoReason =
      'Let go at the till, unfinished: its card payment took nothing, or was '
      'put back on the card, and the sale was rung up again.';

  /// Gives up the order of a held sale the till is letting go of unfinished:
  /// cancelled at order-svc (any staff at its store may cancel an order
  /// nobody paid for), which gives its stock back. payment-svc then takes no
  /// card and records no tender for it, and puts back on the card by itself
  /// whatever a card machine turns out to have taken for it — an approval
  /// that arrives after a manager recorded "nothing taken", a press that
  /// reaches the machine late.
  ///
  /// An exchange's order is not the sale's to cancel: it stands with the
  /// return it settles. An order that cannot be cancelled now is still let
  /// go, and remembered by the till as such.
  static Future<void> _giveUpOrder(Dio dio, HeldCardPayment held) async {
    final orderId = held.orderId;
    if (orderId == null || orderId.isEmpty || held.sale.settlement != null) {
      return;
    }
    try {
      await dio.post('/${ApiConstants.order}/orders/$orderId/cancel',
          data: {'reason': _letGoReason});
    } catch (e) {
      debugPrint('The order $orderId let go at the till was not cancelled: $e');
    }
  }

  /// The held payment is still at the machine after the cancel's wait. A
  /// cancel settles nothing until the machine answers it, and a request left
  /// at the machine — its call gone, payment-svc restarted under it — never
  /// answers: a manager looks at the machine and records what it shows, once
  /// it has had its time to answer (payment-svc says when). Anyone else waits,
  /// or asks for one. The attempt with the decision on it, or null.
  Future<TerminalOutcome?> _tellHeldStillAtMachine(
      Dio dio, TerminalOutcome attempt) async {
    if (!mounted) return null;
    final may = _mayDecideCards();
    final amount = _attemptAmount(attempt);
    final settle = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const Key('tender-held-still-at-machine'),
        title: const Text('The card machine has not stopped the earlier '
            'payment'),
        content: Text(
          'The card machine has not answered about the earlier card payment'
          '${amount.isEmpty ? '' : ' of $amount'}, so it may still take the '
          'card. Do not take the card again.\n\nWait for it, then press '
          'Complete Sale again.'
          '${may ? ' If the machine shows the payment finished, or nothing on '
              'it, record what it shows.' : '\n\nAsk a manager to look at the '
              'card machine and record what it shows if it never answers.'}',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('OK'),
          ),
          if (may)
            FilledButton(
              key: const Key('tender-held-still-settle'),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('Record what the machine shows'),
            ),
        ],
      ),
    );
    if (settle != true || !mounted) return null;
    return _settleOnMachine(dio, attempt);
  }

  /// Whether the signed-in login may say what money a card machine took or put
  /// back: a manager or owner whose role holds `sales.refund`, as payment-svc
  /// judges the settle and the linked refund.
  bool _mayDecideCards() {
    final auth = ref.read(authNotifierProvider).value;
    return auth is AuthAuthenticated &&
        auth.isManager &&
        auth.hasPermission('sales.refund');
  }

  /// A manager records what the card machine shows for [attempt], one it did
  /// not answer, with a reason (payment-svc's settle). The attempt with the
  /// decision on it, or null when nothing was recorded — the cashier having
  /// been told why.
  Future<TerminalOutcome?> _settleOnMachine(
      Dio dio, TerminalOutcome attempt) async {
    if (!mounted) return null;
    final said = await showDialog<({MachineShows shows, String reason})>(
      context: context,
      builder: (_) => _MachineShowsDialog(
        amount: _attemptAmount(attempt),
        refund: attempt.kind == 'REFUND',
      ),
    );
    if (said == null || !mounted) return null;
    setState(() => _processing = true);
    try {
      return await settleTerminalAttempt(dio, attempt.id,
          shows: said.shows, reason: said.reason, idempotencyKey: newId());
    } catch (e) {
      final code = apiErrorCode(e);
      // Somebody recorded it first, or the machine answered after all: what
      // it is now is the answer.
      if (code == 'TERMINAL_ATTEMPT_ALREADY_DECIDED' ||
          code == 'TERMINAL_NOT_TIMED_OUT') {
        try {
          final now = await readTerminalAttempt(dio, attempt.id);
          _snack(code == 'TERMINAL_NOT_TIMED_OUT'
              ? 'The card machine has answered: ${now.message}.'
              : 'Someone has already recorded what the card machine shows: '
                  '${now.message}.');
          return now;
        } catch (_) {
          // Said below.
        }
      }
      if (code == 'TERMINAL_REQUEST_IN_FLIGHT') {
        final from = decidableFrom(e);
        _snack(
            'The card machine may still answer this payment. Wait for it'
            '${from == null ? '' : ', or record what it shows after ${AppFormat.time(from)}'}.',
            error: true);
        return null;
      }
      _snack(
          friendlyError(e,
              fallback: 'What the card machine shows could not be recorded.'),
          error: true);
      return null;
    } finally {
      if (mounted) setState(() => _processing = false);
    }
  }

  /// A card payment of this sale timed out on the machine: it may have taken
  /// the card. Only a manager's record of what the machine shows settles it;
  /// anyone else is told to ask for one, and the sale stays held. The attempt
  /// with the decision on it, or null when nothing was recorded.
  Future<TerminalOutcome?> _askAboutTimedOut(Dio dio, TerminalOutcome outcome,
      {required String already}) async {
    final may = _mayDecideCards();
    final settle = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        key: const Key('tender-terminal-uncertain'),
        title: const Text('Check the card machine'),
        content: Text(
          '${outcome.message}\n\n'
          '${already.isEmpty ? '' : '$already\n\n'}'
          'The sale is saved and still awaiting payment. Do not take the card '
          'again: a manager looks at the card machine and records what it '
          'shows first.'
          '${may ? '' : '\n\nAsk a manager to record it. Until then this sale '
              'and the card machine wait.'}',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Not now'),
          ),
          if (may)
            FilledButton(
              key: const Key('tender-uncertain-settle'),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('Record what the machine shows'),
            ),
        ],
      ),
    );
    if (settle != true || !mounted) return null;
    return _settleOnMachine(dio, outcome);
  }

  /// "12.00" in GBP as `£12.00`; empty when the attempt did not say.
  String _attemptAmount(TerminalOutcome a) {
    final amount = double.tryParse(a.amount ?? '');
    if (amount == null) return '';
    return AppFormat.money(amount, currencyCode: a.currency);
  }

  /// The held payment is still at the machine: wait, or cancel it on the
  /// machine. True when the cashier chose to cancel.
  Future<bool?> _askAboutHeldAtMachine(List<TerminalOutcome> atMachine) {
    final amounts = [
      for (final a in atMachine)
        if (_attemptAmount(a).isNotEmpty) _attemptAmount(a),
    ];
    final what = amounts.isEmpty
        ? 'a card payment'
        : 'a card payment of ${amounts.join(' and ')}';
    return showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const Key('tender-held-at-machine'),
        title: const Text('The earlier card payment is still on the machine'),
        content: Text(
          'This sale changed after $what was sent to the card machine, and '
          'the machine has not answered yet. Starting the sale again now could '
          'take the card twice.\n\nWait for the customer to finish on the '
          'machine, or cancel that payment on it first.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Wait'),
          ),
          FilledButton(
            key: const Key('tender-held-cancel'),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Cancel the card payment'),
          ),
        ],
      ),
    );
  }

  /// The machine took the card for the sale as it was. The changed sale is
  /// refused, and there are two ways out and no third: put the sale back and
  /// finish it — the card is recorded on it as the payment the machine took,
  /// never asked for again — or, a manager with a reason, on an order with no
  /// tender recorded yet, put the money back on the card through payment-svc's
  /// linked refund, which frees the till for the changed sale. True only then.
  ///
  /// Cancelling the earlier sale is not a way out: it left the money on the
  /// card. A card machine that can no longer put the money back (retired)
  /// leaves the first.
  ///
  /// While money being put back on the card is still at the machine, or the
  /// machine did not answer about it, neither is: payment-svc refuses to
  /// record the card while a refund of it is undecided, so a sale put back
  /// would be refused on every press. A manager records what the machine
  /// shows for that refund, and the hold is read again from that.
  Future<bool> _tellHeldTaken(Dio dio, HeldCardPaymentNotifier holdCtl,
      HeldCardPayment held, HeldCardCheck check) async {
    final what = _takenAmounts(check);
    final manager = _mayDecideCards();
    final refundsOpen = [
      for (final a in check.taken)
        for (final r in check.refundsOf(a))
          if (r.pending || r.uncertain) r,
    ];
    final unsettled = refundsOpen.isNotEmpty;
    // An approval that followed a manager's "nothing taken": the customer was
    // told the card was not charged, and may have paid another way since.
    final late = check.taken.any((a) => a.approvedAfterNotTaken);
    // A sale with a tender recorded is part-paid in the books: it is finished,
    // then returned, so the sale, its receipt and its refund tell one story.
    // A tender the hold sent may be recorded before the server shows it —
    // payment-svc writes a gift card's from the redeem, after it — so the
    // hold's own say counts as much as the read.
    final mayBeRecorded = held.mayBeRecorded.isNotEmpty;
    final recorded =
        manager && !unsettled && !mayBeRecorded && held.orderId != null
            ? await tendersRecordedOn(dio, held.orderId!)
            : null;
    if (!mounted) return false;
    final canReverse =
        manager && !unsettled && !mayBeRecorded && recorded == false;
    final choice = await showDialog<_TakenChoice>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const Key('tender-held-taken'),
        title: const Text('The card was taken for this sale as it was'),
        content: Text(
          'The card machine approved $what before this sale changed, so the '
          'customer has paid that on the card. Do not take the card again.'
          '${late ? '\n\nThe card machine approved it after a manager had '
              'recorded it as not taken. If the customer has paid for that '
              'sale another way since, do not finish it with this payment: a '
              'manager puts it back on the card.' : ''}'
          '${unsettled ? '\n\nMoney being put back on the card is still on the '
              'machine, or the machine did not answer about it. Until a manager '
              'looks at the card machine and records what it shows, this sale '
              'and the card machine wait.'
              '${manager ? '' : ' Ask a manager to record it.'}' : '\n\nPut the '
              'sale back as it was and press Complete Sale to finish it: the '
              'card is recorded on it as it was taken, without asking the '
              'machine again. To change it, finish it first, then return or '
              'exchange.'}'
          '${canReverse ? '\n\nOr put $what back on the card, and the changed '
              'sale goes ahead on its own.' : ''}'
          '${manager && !unsettled && (recorded == true || mayBeRecorded) ? '\n\n'
              'A payment is already recorded against this sale, so the card '
              'cannot be put back from here: finish the sale, then return it.' : ''}',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(_TakenChoice.notNow),
            child: const Text('Not now'),
          ),
          if (canReverse)
            OutlinedButton(
              key: const Key('tender-held-reverse'),
              onPressed: () => Navigator.of(ctx).pop(_TakenChoice.reverse),
              child: const Text('Put the money back on the card'),
            ),
          if (unsettled && manager)
            FilledButton(
              key: const Key('tender-held-refund-settle'),
              onPressed: () => Navigator.of(ctx).pop(_TakenChoice.settleRefund),
              child: const Text('Record what the machine shows'),
            ),
          if (!unsettled)
            FilledButton(
              key: const Key('tender-held-put-back'),
              onPressed: () => Navigator.of(ctx).pop(_TakenChoice.putBack),
              child: const Text('Put the sale back'),
            ),
        ],
      ),
    );
    // The dialog can outlive the screen: nothing more is done without it.
    if (!mounted) return false;
    switch (choice) {
      case _TakenChoice.putBack:
        await _putBack(held);
        return false;
      case _TakenChoice.reverse:
        return _reverseHeld(dio, holdCtl, held, check, what);
      case _TakenChoice.settleRefund:
        final decided = await _settleOnMachine(dio, refundsOpen.first);
        if (decided == null || !mounted) return false;
        setState(() => _processing = true);
        return _settleHeld(dio, holdCtl, holdCtl.current ?? held);
      case _TakenChoice.close:
      case _TakenChoice.notNow:
      case null:
        return false;
    }
  }

  /// The held sale's cards took nothing, but its order has tenders recorded
  /// on it, or that may be — a gift card redeemed, store credit, cash — and
  /// this press sends another order, or changed one of them. They are that
  /// order's: a new order would take them again. So nothing starts. The sale
  /// is put back and finished — only what is still owed is taken — or a
  /// manager cancels it with a reason, and payment-svc refunds what was
  /// recorded on it ([_closeHeld]).
  Future<void> _tellHeldRecorded(Dio dio, HeldCardPaymentNotifier holdCtl,
      HeldCardPayment held) async {
    if (!mounted) return;
    final auth = ref.read(authNotifierProvider).value;
    final manager = auth is AuthAuthenticated && auth.isManager;
    final was = held.sale;
    final currency = was.settlement?.currency ??
        (was.lines.isNotEmpty ? was.lines.first.currency : _currency);
    final places = held.mayBeRecorded.toList()..sort();
    final words = [
      for (final i in places)
        if (i < was.tenders.length) _tenderWords(was.tenders[i], currency),
    ];
    final what = words.isEmpty ? 'A payment' : _join(words);
    final one = words.length <= 1;
    final choice = await showDialog<_TakenChoice>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const Key('tender-held-recorded'),
        title: const Text('Part of the earlier sale is already paid'),
        content: Text(
          '$what ${one ? 'was' : 'were'} already taken for the sale as it was, '
          'and ${one ? 'stays' : 'stay'} with it: a new sale would take '
          '${one ? 'it' : 'them'} again.\n\nPut the sale back as it was and '
          'press Complete Sale to finish it — only what is still owed is '
          'taken. To change it, finish it first, then return or exchange.'
          '${manager ? '\n\nOr cancel the earlier sale with a reason: what was '
              'taken for it is refunded the way it was paid, and this sale goes '
              'ahead on its own.' : ''}',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(_TakenChoice.notNow),
            child: const Text('Not now'),
          ),
          if (manager)
            TextButton(
              key: const Key('tender-held-close'),
              onPressed: () => Navigator.of(ctx).pop(_TakenChoice.close),
              child: const Text('Cancel the earlier sale'),
            ),
          FilledButton(
            key: const Key('tender-held-put-back'),
            onPressed: () => Navigator.of(ctx).pop(_TakenChoice.putBack),
            child: const Text('Put the sale back'),
          ),
        ],
      ),
    );
    if (!mounted) return;
    switch (choice) {
      case _TakenChoice.putBack:
        await _putBack(held);
      case _TakenChoice.close:
        await _closeHeld(dio, holdCtl, held);
      case _TakenChoice.reverse:
      case _TakenChoice.settleRefund:
      case _TakenChoice.notNow:
      case null:
        break;
    }
  }

  /// "£6.00 in cash", "£8.00 on a gift card": a tender as it was taken.
  static String _tenderWords(PosTender t, String currency) {
    final amount = AppFormat.money(t.amount, currencyCode: currency);
    return switch (t.method) {
      'CASH' => '$amount in cash',
      'GIFT_CARD' => '$amount on a gift card',
      'STORE_CREDIT' => '$amount in store credit',
      'CARD' => '$amount on a card',
      _ => '$amount by ${t.label.toLowerCase()}',
    };
  }

  /// A held sale whose card payment cannot be read — the server cannot be
  /// reached about it — and that cannot wait: a manager cancels it, with a
  /// reason, and the till sells again. Recorded where the sale is: order-svc
  /// cancels the order with the reason and who gave it in its history and
  /// gives its stock back, and payment-svc puts back on the card whatever a
  /// card machine took for it — through the machine that took it, a payment
  /// still at the machine as soon as it answers, and any it cannot put back is
  /// shown to a manager among the money owed back to cards.
  ///
  /// Nothing new is started by it — the cashier presses Complete Sale again.
  Future<void> _closeHeld(
    Dio dio,
    HeldCardPaymentNotifier holdCtl,
    HeldCardPayment held,
  ) async {
    if (!mounted) return;
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => const _ManagerReasonDialog(
        keyPrefix: 'tender-close',
        title: 'Cancel the earlier sale?',
        body: 'The earlier sale is cancelled with your reason, its goods go '
            'back into stock, and the till can sell again.\n\nWhat was taken '
            'for it is refunded the way it was paid: hand back any cash it '
            'took. Anything a card machine took for it goes back on the card, '
            'through the machine that took it. If that machine cannot put it '
            'back, a manager sees it among the money owed back to cards. Look '
            'at the card machine first.',
        label: 'Why is it being cancelled?',
        confirm: 'Cancel the earlier sale',
      ),
    );
    if (reason == null || !mounted) return;
    setState(() => _processing = true);
    final orderId = held.orderId;
    if (orderId != null && orderId.isNotEmpty) {
      try {
        await dio.post('/${ApiConstants.order}/orders/$orderId/cancel',
            data: {'reason': reason});
      } catch (e) {
        // A cancel whose answer was lost, then sent again, finds the order
        // already cancelled: that is the cancel done.
        if (!await _orderCancelled(dio, orderId)) {
          if (!mounted) return;
          setState(() => _processing = false);
          _snack(
              friendlyError(e,
                  fallback: 'The earlier sale could not be cancelled.'),
              error: true);
          return;
        }
      }
    }
    await holdCtl.release();
    if (!mounted) return;
    setState(() => _processing = false);
    _snack('The earlier sale is cancelled: what was taken for it is refunded '
        'the way it was paid, and anything its card took goes back on the '
        'card. Press Complete Sale to take this one.');
  }

  /// Whether order-svc has [orderId] cancelled; false when it cannot be read.
  static Future<bool> _orderCancelled(Dio dio, String orderId) async {
    try {
      final resp = await dio.get('/${ApiConstants.order}/orders/$orderId');
      final data = resp.data is Map ? (resp.data as Map)['data'] : null;
      return data is Map && data['status'] == 'CANCELLED';
    } catch (_) {
      return false;
    }
  }

  /// `£12.00`, or `£6.00 and £4.00`: what the taken cards of [check] still hold.
  String _takenAmounts(HeldCardCheck check) {
    final amounts = [
      for (final a in check.taken)
        if (_attemptAmount(a).isNotEmpty) _attemptAmount(a),
    ];
    return amounts.isEmpty ? 'the card' : _join(amounts);
  }

  /// Puts what the held sale's cards took back on them (payment-svc's linked
  /// refund on the machine, MANAGER or OWNER with `sales.refund`), after the
  /// manager has said why: payment-svc keeps the reason on each refund, with
  /// who asked and when. True when every card attempt on the held order is
  /// then read as having taken nothing: the hold goes and the changed sale
  /// goes ahead.
  Future<bool> _reverseHeld(Dio dio, HeldCardPaymentNotifier holdCtl,
      HeldCardPayment held, HeldCardCheck check, String what) async {
    if (!mounted) return false;
    final wait = ref.read(terminalWaitProvider);
    final clock = ref.read(terminalClockProvider);
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => _ManagerReasonDialog(
        keyPrefix: 'tender-reverse',
        title: 'Put $what back on the card?',
        body: 'The card machine puts the money back on the card that paid. '
            'The earlier sale is then given up, and the changed sale goes '
            'ahead on its own.',
        label: 'Why is it going back?',
        confirm: 'Put it back on the card',
      ),
    );
    if (reason == null || !mounted) return false;
    setState(() => _processing = true);
    HeldCardCheck after;
    try {
      after = await reverseHeldOnMachine(dio, held, check,
          reason: reason, wait: wait, now: clock);
    } catch (e) {
      if (!mounted) return false;
      setState(() => _processing = false);
      _snack(
          friendlyError(e,
              fallback: 'The card machine would not put the money back.'),
          error: true);
      return false;
    }
    if (!mounted) return false;
    if (after.state == HeldCardState.settled) {
      final free = await _letGoOrKeep(dio, holdCtl, held, after);
      _snack('$what put back on the card.');
      return free;
    }
    setState(() => _processing = false);
    _snack(
        after.state == HeldCardState.unknown
            ? 'Cannot check whether the money went back on the card. Do not '
                'start the sale again yet: look at the card machine.'
            : 'The money has not gone back on the card yet. Look at the card '
                'machine, then press Complete Sale again.',
        error: true);
    return false;
  }

  /// The held payment timed out on the machine: the card may have been
  /// charged. Nothing the cashier presses lets it go: a manager looks at the
  /// machine and records what it shows, with a reason. The attempt with the
  /// decision on it, or null when nothing was recorded.
  Future<TerminalOutcome?> _tellHeldMayBeTaken(
      Dio dio, TerminalOutcome outcome) async {
    final may = _mayDecideCards();
    final settle = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        key: const Key('tender-held-uncertain'),
        title: const Text('Check the card machine'),
        content: Text(
          '${outcome.message}\n\nThis was the earlier card payment for this '
          'sale. Do not take the card again: a manager looks at the card '
          'machine and records what it shows first.'
          '${may ? '' : '\n\nAsk a manager to record it. Until then this sale '
              'and the card machine wait.'}',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Not now'),
          ),
          if (may)
            FilledButton(
              key: const Key('tender-held-settle'),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('Record what the machine shows'),
            ),
        ],
      ),
    );
    if (settle != true || !mounted) return null;
    return _settleOnMachine(dio, outcome);
  }

  /// What the machine said about the held payment cannot be read: nothing new
  /// starts, and the sale can be put back to carry on with that payment. A
  /// manager may cancel it instead, with a reason ([_closeHeld]): a machine
  /// that cannot be reached, nor its payment stopped, must not keep the till
  /// from selling for ever, and payment-svc puts back on the card whatever it
  /// took for a sale cancelled.
  Future<void> _tellHeldUnknown(Dio dio, HeldCardPaymentNotifier holdCtl,
      HeldCardPayment held) async {
    final auth = ref.read(authNotifierProvider).value;
    final manager = auth is AuthAuthenticated && auth.isManager;
    final choice = await showDialog<_TakenChoice>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const Key('tender-held-unknown'),
        title: const Text('Cannot check the earlier card payment'),
        content: Text(
          'This sale changed after a card payment was sent to the card machine, '
          'and the till cannot reach the server to see what the machine said. '
          'Do not take the card again.\n\nPut the sale back as it was to carry '
          'on with that payment, or press Complete Sale again once the network '
          'is back.'
          '${manager ? '\n\nIf the earlier sale cannot be finished, cancel it '
              'with a reason: anything its card took goes back on the card.' : ''}',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(_TakenChoice.notNow),
            child: const Text('OK'),
          ),
          if (manager)
            TextButton(
              key: const Key('tender-held-close'),
              onPressed: () => Navigator.of(ctx).pop(_TakenChoice.close),
              child: const Text('Cancel the earlier sale'),
            ),
          FilledButton(
            key: const Key('tender-held-put-back'),
            onPressed: () => Navigator.of(ctx).pop(_TakenChoice.putBack),
            child: const Text('Put the sale back'),
          ),
        ],
      ),
    );
    if (!mounted) return;
    switch (choice) {
      case _TakenChoice.putBack:
        await _putBack(held);
      case _TakenChoice.close:
        await _closeHeld(dio, holdCtl, held);
      case _TakenChoice.reverse:
      case _TakenChoice.settleRefund:
      case _TakenChoice.notNow:
      case null:
        break;
    }
  }

  /// payment-svc refused a card because the machine holds an earlier card
  /// payment that is not settled (409 TERMINAL_UNSETTLED_APPROVAL): this sale's
  /// card was not taken. Shows what holds the machine — from the refusal's
  /// details, filled in from each order's attempts — and the only ways out:
  /// finish that sale (its approval recorded on its own order), a manager
  /// putting the money back on the card with a reason, or, for a payment the
  /// machine never answered, a manager recording what the machine shows.
  ///
  /// Finishing is offered only where it pays nobody twice. On this till's own
  /// held sale, the approval is recorded in the hold at the place that took
  /// it and the sale put back, so the press records it under the sale's keys
  /// and never asks the machine for it again; one no place of the sale is,
  /// is not this sale's to finish as it stands.
  ///
  /// On any other sale the till cannot know that its customer has not paid
  /// another way, on another order, so it is never a default button. Never
  /// at all on a sale paid already, given up, or that it would pay twice; on
  /// an approval that arrived after a manager recorded it as not taken (that
  /// sale was told nothing was taken); or on an order this till let go of
  /// unfinished — those go back on the card. Otherwise it is a person's
  /// call, as payment-svc leaves it: a manager's, told first what makes it
  /// right — that sale's customer took the goods and has not paid another
  /// way — and then told its customer has paid: never to press again, since
  /// the customer at the till may be that one.
  ///
  /// A sale queued on this till for one of those orders is sent first,
  /// whatever waits ahead of it in line: its tender is what settles the
  /// machine. While it still waits, its approval is neither finished nor put
  /// back from here — recorded under another key, the queued tender would be
  /// refused on every replay, and its sale is complete: the customer paid by
  /// that card.
  Future<void> _tellMachineHeld(
      Dio dio, Object error, OfflineQueueNotifier offlineQueue) async {
    final cards = unsettledCardsOf(error);
    final named = {for (final c in cards) c.orderId};
    // The orders named that a sale on this till still waits to be sent for.
    Set<String> waiting() => {
          for (final s in ref.read(offlineQueueProvider))
            if (s.status != OfflineSaleStatus.failed &&
                named.contains(s.orderId))
              s.orderId!,
        };
    if (waiting().isNotEmpty) {
      await offlineQueue.syncOrders(named);
      if (!mounted) return;
      if (waiting().isEmpty) {
        _snack('A sale kept on this till was sent to the server just now, and '
            'it may be what held the card machine. Press Complete Sale again.');
        return;
      }
    }
    final queued = waiting();
    // Each payment as its order's attempts have it: the card's last digits,
    // what a manager recorded. The refusal's own details when unreadable.
    final byOrder = <String, List<TerminalOutcome>>{};
    for (final orderId in named) {
      try {
        byOrder[orderId] = await readTerminalAttemptsOfOrder(dio, orderId);
      } catch (_) {
        byOrder[orderId] = const [];
      }
    }
    if (!mounted) return;
    final read = {
      for (final c in cards)
        c.attemptId:
            byOrder[c.orderId]?.where((a) => a.id == c.attemptId).firstOrNull,
    };
    final shown = [
      for (final c in cards)
        (card: c, attempt: read[c.attemptId] ?? c.asOutcome),
    ];
    final holdCtl = ref.read(heldCardPaymentProvider.notifier);
    final current = ref.read(heldCardPaymentProvider);
    final mine = current != null && current.belongsTo(_tenantId())
        ? current
        : null;
    final may = _mayDecideCards();
    // Which approvals nobody recorded may be finished from here.
    final finish = <String, _Finish>{};
    for (final s in shown) {
      if (s.card.isRefund || s.card.standing != 'APPROVED_UNRECORDED') continue;
      final id = s.card.attemptId;
      if (queued.contains(s.card.orderId)) {
        finish[id] = _Finish.queued;
        continue;
      }
      if (mine != null && mine.orderId == s.card.orderId) {
        finish[id] = placeOfApproval(mine, s.attempt) == null
            ? _Finish.notThisSale
            : _Finish.yes;
        continue;
      }
      // Another sale's. What the till knows was not finished with that card
      // is never finished with it from here, whatever its order reads as.
      if (holdCtl.wasLetGo(s.card.orderId)) {
        finish[id] = _Finish.letGo;
        continue;
      }
      if (s.attempt.approvedAfterNotTaken) {
        finish[id] = _Finish.afterNotTaken;
        continue;
      }
      // An attempt the till could not read may be one a manager recorded as
      // not taken: the refusal's own details do not say.
      if (read[id] == null) {
        finish[id] = _Finish.unknown;
        continue;
      }
      final owes = await _orderStillOwes(
          dio,
          s.card.orderId,
          s.attempt.amount ?? s.card.amount,
          s.attempt.currency ?? s.card.currency);
      finish[id] = switch (owes) {
        true => _Finish.personsCall,
        false => _Finish.paidAlready,
        null => _Finish.unknown,
      };
    }
    if (!mounted) return;
    final cs = Theme.of(context).colorScheme;

    String money(String? amount, String? currency) {
      final v = double.tryParse(amount ?? '');
      return v == null ? '' : AppFormat.money(v, currencyCode: currency);
    }

    String describe(UnsettledCard c, TerminalOutcome a) {
      final card = a.panLast4 == null ? '' : ' (card ending ${a.panLast4})';
      final amount = money(c.amount, c.currency);
      if (c.isRefund) {
        return switch (c.standing) {
          'AT_MACHINE' => '$amount being put back on a card$card is still at '
              'the card machine.',
          _ => '$amount being put back on a card$card: the card machine did '
              'not answer, so nobody knows yet whether it went back.',
        };
      }
      return switch (c.standing) {
        'AT_MACHINE' => '$amount$card is still at the card machine, waiting '
            'for the customer.',
        'APPROVED_UNRECORDED' =>
          '${money(c.onCard ?? c.amount, c.currency)} was taken on the card'
              '$card for an earlier sale, and is not recorded on it.'
              '${switch (finish[c.attemptId]) {
                _Finish.paidAlready => ' That sale is already paid, so it is '
                    'not recorded on it: a manager puts it back on the card.',
                _Finish.unknown => ' The till cannot check whether that sale '
                    'is already paid, so it is not finished from here now.',
                _Finish.notThisSale => ' It was taken for this sale, but no '
                    'card on it now is that payment: a manager puts it back '
                    'on the card.',
                _Finish.afterNotTaken => ' The card machine approved it after '
                    'a manager recorded it as not taken, so that sale was not '
                    'finished with it: a manager puts it back on the card.',
                _Finish.letGo => ' That sale was let go at this till before '
                    'the card machine approved it, so it was not finished '
                    'with it: a manager puts it back on the card.',
                _Finish.queued => ' Its sale is kept on this till, waiting to '
                    'reach the server, and records this payment when it does: '
                    'see Pending sales. Until then the card machine waits.',
                // This till's own sale, still open: finishing it is right
                // unless its customer, told nothing was taken, paid elsewhere.
                _Finish.yes when a.approvedAfterNotTaken => ' The card machine '
                    'approved it after a manager recorded it as not taken. If '
                    'the customer has paid for this sale another way since, do '
                    'not finish it with this payment: a manager puts it back '
                    'on the card.',
                _Finish.personsCall => may
                    ? ' If that sale\'s customer took the goods and has not '
                        'paid another way, record it on that sale; otherwise '
                        'put it back on the card.'
                    : ' If that sale\'s customer took the goods and has not '
                        'paid another way, a manager records it on that sale; '
                        'otherwise a manager puts it back on the card.',
                _ => '',
              }}',
        _ => '$amount$card: the card machine did not answer, so the card may '
            'have been charged.',
      };
    }

    final choice = await showDialog<
        ({UnsettledCard card, TerminalOutcome attempt, _HeldMachineAction action})>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const Key('tender-terminal-unsettled'),
        title: const Text('The card machine has an earlier payment to settle'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'The card was not taken for this sale. The card machine takes '
                'no new card until this is settled:',
                style: TextStyle(color: cs.onSurfaceVariant),
              ),
              for (final s in shown) ...[
                const SizedBox(height: AppSpacing.md),
                Text(describe(s.card, s.attempt),
                    key: Key('tender-unsettled-${s.card.attemptId}')),
                const SizedBox(height: AppSpacing.xs),
                Wrap(
                  spacing: AppSpacing.sm,
                  runSpacing: AppSpacing.xs,
                  children: [
                    if (finish[s.card.attemptId] == _Finish.yes)
                      FilledButton(
                        key: Key('tender-unsettled-finish-${s.card.attemptId}'),
                        onPressed: () => Navigator.of(ctx).pop((
                          card: s.card,
                          attempt: s.attempt,
                          action: _HeldMachineAction.finish,
                        )),
                        child: const Text('Finish that sale'),
                      ),
                    if (may && finish[s.card.attemptId] == _Finish.personsCall)
                      OutlinedButton(
                        key: Key('tender-unsettled-finish-${s.card.attemptId}'),
                        onPressed: () => Navigator.of(ctx).pop((
                          card: s.card,
                          attempt: s.attempt,
                          action: _HeldMachineAction.finish,
                        )),
                        child: const Text('Record it on that sale'),
                      ),
                    if (may &&
                        !s.card.isRefund &&
                        s.card.standing == 'APPROVED_UNRECORDED' &&
                        finish[s.card.attemptId] != _Finish.queued)
                      OutlinedButton(
                        key: Key('tender-unsettled-reverse-${s.card.attemptId}'),
                        onPressed: () => Navigator.of(ctx).pop((
                          card: s.card,
                          attempt: s.attempt,
                          action: _HeldMachineAction.reverse,
                        )),
                        child: const Text('Put it back on the card'),
                      ),
                    if (may &&
                        (s.card.standing == 'UNDECIDED' ||
                            s.card.standing == 'AT_MACHINE'))
                      OutlinedButton(
                        key: Key('tender-unsettled-settle-${s.card.attemptId}'),
                        onPressed: () => Navigator.of(ctx).pop((
                          card: s.card,
                          attempt: s.attempt,
                          action: _HeldMachineAction.settle,
                        )),
                        child: const Text('Record what the machine shows'),
                      ),
                  ],
                ),
              ],
              if (!may) ...[
                const SizedBox(height: AppSpacing.md),
                Text(
                  'Recording a card payment on an earlier sale, putting money '
                  'back on a card, or saying what the card machine shows when '
                  'it did not answer, is for a manager.',
                  style: TextStyle(color: cs.onSurfaceVariant),
                ),
              ],
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Close'),
          ),
        ],
      ),
    );
    if (choice == null || !mounted) return;
    final attempt = choice.attempt;
    final amount = _attemptAmount(attempt);
    switch (choice.action) {
      case _HeldMachineAction.finish:
        // This till's own held sale: the approval is recorded in the hold at
        // the place that took it, then the sale put back, so the press
        // records it under its keys, naming it, with its receipt — and never
        // asks the machine for it again, which would only be refused again.
        if (mine != null && mine.orderId == choice.card.orderId) {
          final place = placeOfApproval(mine, attempt);
          if (place == null) return;
          final known = mine.withTaken({place: attempt.id});
          await holdCtl.hold(known);
          if (!mounted) return;
          if (await _putBack(known)) {
            _snack('The card payment is recorded on this sale as taken. Press '
                'Complete Sale to finish it — the card is not taken again.');
          }
          return;
        }
        // Another sale's, and a person's call: only a manager's, and only
        // once told what makes it right.
        if (finish[attempt.id] != _Finish.personsCall || !may) return;
        if (!await _askToRecordOnEarlierSale(attempt) || !mounted) return;
        setState(() => _processing = true);
        try {
          await recordTerminalApproval(dio, attempt,
              idempotencyKey: derivedId(attempt.id, 'record'));
        } catch (e) {
          _snack(
              friendlyError(e,
                  fallback: 'The card payment could not be recorded on its '
                      'sale.'),
              error: true);
          return;
        } finally {
          if (mounted) setState(() => _processing = false);
        }
        await _tellFinished(attempt);
      case _HeldMachineAction.reverse:
        final reason = await showDialog<String>(
          context: context,
          builder: (_) => _ManagerReasonDialog(
            keyPrefix: 'tender-unsettled-reverse',
            title: 'Put ${amount.isEmpty ? 'it' : amount} back on the card?',
            body: 'The card machine puts the money back on the card that '
                'paid. The earlier sale is then left unpaid.',
            label: 'Why is it going back?',
            confirm: 'Put it back on the card',
          ),
        );
        if (reason == null || !mounted) return;
        final wait = ref.read(terminalWaitProvider);
        final clock = ref.read(terminalClockProvider);
        setState(() => _processing = true);
        try {
          final after = await reverseCardsOnMachine(
            dio,
            keyBase: attempt.id,
            orderId: choice.card.orderId,
            check: HeldCardCheck.of(byOrder[choice.card.orderId]?.isNotEmpty ==
                    true
                ? byOrder[choice.card.orderId]!
                : [attempt]),
            reason: reason,
            only: attempt.id,
            wait: wait,
            now: clock,
          );
          final left = after.taken.where((a) => a.id == attempt.id);
          if (left.isEmpty && after.state != HeldCardState.unknown) {
            _snack('${amount.isEmpty ? 'The money' : amount} put back on the '
                'card. Press Complete Sale again.');
          } else {
            _snack(
                'The money has not gone back on the card yet. Look at the card '
                'machine.',
                error: true);
          }
        } catch (e) {
          _snack(
              friendlyError(e,
                  fallback: 'The card machine would not put the money back.'),
              error: true);
        } finally {
          if (mounted) setState(() => _processing = false);
        }
      case _HeldMachineAction.settle:
        final decided = await _settleOnMachine(dio, attempt);
        if (decided == null || !mounted) return;
        if (decided.approved && !choice.card.isRefund) {
          _snack('Recorded as taken on the card. Finish that sale, or put the '
              'money back on the card, before the machine takes another.');
        } else {
          _snack('Recorded. Press Complete Sale again.');
        }
    }
  }

  /// A manager is asked before a card machine's approval is recorded on an
  /// earlier sale that is not on this till: the till sees that sale as still
  /// owing it, and cannot see whether its customer paid another way since —
  /// on another order, after being told the card was not taken. True when the
  /// manager says to record it.
  Future<bool> _askToRecordOnEarlierSale(TerminalOutcome attempt) async {
    final amount = _attemptAmount(attempt);
    final card =
        attempt.panLast4 == null ? '' : ' (card ending ${attempt.panLast4})';
    final record = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const Key('tender-unsettled-finish-ask'),
        title: const Text('Record this card payment on the earlier sale?'),
        content: Text(
          '${amount.isEmpty ? 'The card payment' : amount}$card was taken for '
          'an earlier sale that is still unpaid. Record it on that sale only '
          'if its customer took the goods and has not paid for them another '
          'way.\n\nIf they paid another way, left without the goods, or the '
          'sale was rung up again, put the money back on the card instead.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            key: const Key('tender-unsettled-finish-confirm'),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Record it on that sale'),
          ),
        ],
      ),
    );
    return record == true;
  }

  /// An approval on another sale is recorded on it: that sale's customer has
  /// paid it. The customer at the till may be that one — rung up again after
  /// the till lost the sale — so the cashier is told so, and never told to
  /// press again: that would take their card a second time.
  Future<void> _tellFinished(TerminalOutcome attempt) async {
    if (!mounted) return;
    final amount = _attemptAmount(attempt);
    final card =
        attempt.panLast4 == null ? '' : ' (card ending ${attempt.panLast4})';
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const Key('tender-unsettled-finished'),
        title: const Text('The earlier sale has its card payment'),
        content: Text(
          '${amount.isEmpty ? 'The card payment' : amount}$card is recorded on '
          'the sale it was taken for.\n\nIf the customer at the till is the '
          'one who paid it, they have paid that already: do not take their '
          'card again for it — clear this sale from the till instead.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('OK'),
          ),
        ],
      ),
    );
  }

  /// Whether the order [orderId] still owes [amount] in [currency]: it is
  /// awaiting payment, and what is recorded on it, with [amount], is not more
  /// than it costs. False when it is paid already, given up, or [amount]
  /// would pay it twice; null when the till cannot read it.
  static Future<bool?> _orderStillOwes(
      Dio dio, String orderId, String? amount, String? currency) async {
    try {
      final resp = await dio.get('/${ApiConstants.order}/orders/$orderId');
      final order = resp.data is Map ? (resp.data as Map)['data'] : null;
      if (order is! Map) return null;
      final status = order['status'];
      if (status is! String) return null;
      if (status != 'PENDING') return false;
      final digits = AppFormat.minorUnits(
          currency ?? (order['currency'] is String ? order['currency'] : null));
      int? minor(Object? v) {
        final d = double.tryParse('${v ?? ''}');
        return d == null ? null : (d * math.pow(10, digits)).round();
      }

      final total = minor(order['total']);
      final taking = minor(amount);
      if (total == null || taking == null) return null;
      final paid =
          await dio.get('/${ApiConstants.payment}/payments/by-order/$orderId');
      final rows = (paid.data['data'] as List?) ?? const [];
      var recorded = 0;
      for (final r in rows) {
        if (r is! Map || r['status'] == 'FAILED') continue;
        recorded += minor(r['amount']) ?? 0;
      }
      return recorded + taking <= total;
    } catch (_) {
      return null;
    }
  }

  /// A manager clears a held card payment this till could not read back, once
  /// they have looked at the card machine: the till may then use the machine
  /// again, payment-svc refusing a card on a machine an earlier payment holds.
  Future<void> _clearUnreadableHold(HeldCardPaymentNotifier holdCtl) async {
    final clear = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const Key('tender-hold-corrupt-dialog'),
        title: const Text('Clear the unreadable card payment?'),
        content: const Text(
          'Look at the card machine first. If a card payment from that sale is '
          'still on it, or was taken, the card machine takes no other card '
          'until it is settled, and the till says what holds it.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            key: const Key('tender-hold-corrupt-confirm'),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Clear it'),
          ),
        ],
      ),
    );
    if (clear != true || !mounted) return;
    await holdCtl.clearCorrupt();
    if (!mounted) return;
    _snack('Cleared. The card machine can be used from this till again.');
  }

  /// Puts the till back to the held sale exactly as it was, so the next press
  /// sends what it sent and presents the same keys: the server hands back the
  /// order and the card payment it already has, and nothing is taken twice.
  ///
  /// It is put back as that sale ([PosCartNotifier.restoreSale]): only then
  /// does a press carry it on, never for a basket that merely looks the same.
  ///
  /// A basket with other items in it is not thrown away unasked: the cashier
  /// says so first. True when the sale was put back.
  Future<bool> _putBack(HeldCardPayment held) async {
    if (!mounted) return false;
    // Another business's sale is never put on this till.
    if (!held.belongsTo(_tenantId())) return false;
    final was = held.sale;
    final List<PosLine> now =
        ref.read(posExchangeSettlementProvider)?.lines ??
            ref.read(posCartProvider);
    final List<PosLine> then = was.settlement?.lines ?? was.lines;
    if (now.isNotEmpty && !_sameLines(now, then)) {
      final items = now.fold<int>(0, (s, l) => s + l.itemCount);
      final replace = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          key: const Key('tender-put-back-confirm'),
          title: const Text('Replace the sale on the till?'),
          content: Text(
            'The till has $items item${items == 1 ? '' : 's'} on it now. '
            'Putting the earlier sale back takes '
            '${items == 1 ? 'it' : 'them'} off and puts back the sale the '
            'card paid for.\n\nRing ${items == 1 ? 'it' : 'them'} up again once '
            'that sale is finished.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(false),
              child: const Text('Keep this sale'),
            ),
            FilledButton(
              key: const Key('tender-put-back-replace'),
              onPressed: () => Navigator.of(ctx).pop(true),
              child: const Text('Put the earlier sale back'),
            ),
          ],
        ),
      );
      if (replace != true || !mounted) return false;
    }
    ref.read(posStoreProvider.notifier).state = was.storeId;
    ref.read(posExchangeSettlementProvider.notifier).state = was.settlement;
    if (was.settlement == null) {
      final cartCtl = ref.read(posCartProvider.notifier);
      final saleId = held.saleId;
      if (saleId != null) {
        cartCtl.restoreSale([...was.lines], saleId);
      } else {
        cartCtl.loadLines([...was.lines]);
      }
    }
    ref.read(posCustomerProvider.notifier).state = was.customer;
    ref.read(posWalkInPhoneProvider.notifier).state = was.walkInPhone;
    ref.read(posDiscountProvider.notifier).state = was.discount;
    ref.read(posDiscountReasonProvider.notifier).state = was.discountReason;
    _phoneCtrl.text = was.walkInPhone;
    setState(() {
      _phoneError = null;
      _tenders
        ..clear()
        ..addAll(was.tenders);
    });
    return true;
  }

  /// The same goods at the same prices, line for line.
  static bool _sameLines(List<PosLine> a, List<PosLine> b) {
    String key(PosLine l) => [
          l.variantId,
          l.qty,
          l.unitPrice,
          l.markdownId,
          l.weighingInstrumentId,
          l.giftCard,
          l.giftCardCode,
        ].join('|');
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (key(a[i]) != key(b[i])) return false;
    }
    return true;
  }

  /// Empties the till after a sale: the cart and everything attached to it, or
  /// only the exchange being settled (its basket was never the cart).
  void _clearAfterSale(bool exchange) {
    if (exchange) {
      ref.read(posExchangeSettlementProvider.notifier).state = null;
      return;
    }
    ref.read(posCartProvider.notifier).clear();
    ref.read(posCustomerProvider.notifier).state = null;
    ref.read(posDiscountProvider.notifier).state = 0;
    ref.read(posDiscountReasonProvider.notifier).state = '';
    ref.read(posWalkInPhoneProvider.notifier).state = '';
  }

  /// Finish a sale the server was never told about: print the receipt, clear the
  /// till, and say plainly that it is held rather than sent.
  Future<void> _finishOffline(
    OfflineSale sale,
    List<PosLine> cartSnapshot,
    double discount,
    String currency,
    Customer? customer, {
    bool exchange = false,
  }) async {
    // The total here is the till's own (subtotal − discount) rather than the
    // server's, which is not knowable offline. It is the amount actually
    // tendered, which is what the customer's paper receipt has to show.
    final receiptData = _buildReceiptData(
      orderId: sale.reference,
      fiscalNumberNote:
          'Held offline. The receipt number is issued when this sale reaches the server.',
      cartSnapshot: cartSnapshot,
      tenderSnapshot: [..._tenders],
      discount: discount,
      total: sale.total,
      currency: currency,
      customerName: customer?.fullName.isNotEmpty == true
          ? customer!.fullName
          : customer?.email,
    );
    final change = _change;
    // No session heartbeat here: it exists to tell the server the till is active,
    // which is exactly what cannot be done right now — and awaiting it would sit
    // on the connect timeout with the customer waiting for their receipt.
    _clearAfterSale(exchange);
    _tenders.clear();
    if (!mounted) return;
    setState(() => _processing = false);

    await _produceReceipt(receiptData, kickDrawer: true);
    await _showOfflineSavedDialog(sale, currency, change, receiptData);
  }

  Future<void> _showOfflineSavedDialog(
    OfflineSale sale,
    String currency,
    double change,
    PosReceiptData receiptData,
  ) {
    return showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        icon: Icon(
          Icons.cloud_off_outlined,
          color: Theme.of(ctx).colorScheme.tertiary,
          size: 40,
        ),
        title: const Text('Saved offline'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('Sale #${sale.reference}'),
            if (change > 0) ...[
              const SizedBox(height: 8),
              Text(
                'Change due: ${AppFormat.money(change, currencyCode: currency)}',
                style: TextStyle(
                  color: Theme.of(ctx).colorScheme.onSurface,
                  fontWeight: FontWeight.bold,
                  fontSize: 18,
                ),
              ),
            ],
            const SizedBox(height: 12),
            Text(
              "The server couldn't be reached. This sale is held on this till and "
              'sent automatically when the network is back — see Pending.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Theme.of(ctx).colorScheme.onSurfaceVariant),
            ),
            if (receiptData.items.any((l) => l.giftCard)) ...[
              const SizedBox(height: 8),
              Text(
                'The gift card is issued when this sale is sent. Its code is not '
                'available until then.',
                key: const Key('offline-gift-card-note'),
                textAlign: TextAlign.center,
                style: TextStyle(color: Theme.of(ctx).colorScheme.onSurfaceVariant),
              ),
            ],
            const SizedBox(height: 16),
            // No "Email receipt": that needs the server this sale is waiting for.
            Wrap(
              spacing: 8,
              alignment: WrapAlignment.center,
              children: [
                OutlinedButton.icon(
                  onPressed: () => _produceReceipt(receiptData),
                  icon: const Icon(Icons.print_outlined, size: 18),
                  label: const Text('Reprint'),
                ),
                OutlinedButton.icon(
                  onPressed: () => _saveReceipt(receiptData),
                  icon: const Icon(Icons.save_alt_outlined, size: 18),
                  label: const Text('Save'),
                ),
              ],
            ),
          ],
        ),
        actions: [
          FilledButton(
            onPressed: () {
              Navigator.pop(ctx);
              context.go('/pos/cart');
            },
            child: const Text('New sale'),
          ),
        ],
      ),
    );
  }

  /// Who is signed in at the till now, as the sale is made. Kept with a sale
  /// that may be queued: the queue outlives a sign-out and anybody may press
  /// Sync now, and the audit trail must name who rang the sale up.
  String? _signedInUserId() {
    final auth = ref.read(authNotifierProvider).value;
    return auth is AuthAuthenticated && auth.userId.isNotEmpty
        ? auth.userId
        : null;
  }

  PosReceiptData _buildReceiptData({
    required String orderId,
    required List<PosLine> cartSnapshot,
    required List<PosTender> tenderSnapshot,
    required double discount,
    required double total,
    required String currency,
    String? customerName,
    String? fiscalNumber,
    String? fiscalNumberNote,
    FiscalStamp? fiscalStamp,
    PosReceiptVat? vatFromServer,
  }) {
    final subtotal = cartSnapshot.fold<double>(0, (s, l) => s + l.lineTotal);
    final deposit = cartSnapshot.fold<double>(0, (s, l) => s + l.depositTotal);
    final storeId = ref.read(posStoreProvider);
    final stores = ref.read(posStoresProvider).value ?? [];
    final store = stores.firstWhere(
      (s) => s.id == storeId,
      orElse: () => stores.isNotEmpty ? stores.first : _emptyStore(),
    );
    final addressParts = [
      if (store.line1 != null && store.line1!.isNotEmpty) store.line1!,
      if (store.city != null && store.city!.isNotEmpty) store.city!,
      if (store.pincode != null && store.pincode!.isNotEmpty) store.pincode!,
      if (store.country != null && store.country!.isNotEmpty) store.country!,
    ];
    final authState = ref.read(authNotifierProvider).value;
    final cashierEmail = authState is AuthAuthenticated
        ? authState.email
        : null;
    // A sale at shelf prices carries the seller and a VAT table: the server's
    // when it was asked, else the till's own from what it rang up (so a sale made
    // offline prints the same table the server would have).
    final goods = productLines(cartSnapshot);
    final PosReceiptVat? vat = goods.isNotEmpty && goods.every((l) => l.taxInclusive)
        ? PosReceiptVat(
            sellerName: vatFromServer?.sellerName ?? store.businessName,
            vatNumber: vatFromServer?.vatNumber ?? store.vatNumber,
            rows: vatFromServer != null && vatFromServer.rows.isNotEmpty
                ? vatFromServer.rows
                : offlineVatTable(
                    cartSnapshot,
                    discount,
                    AppFormat.minorUnits(currency),
                  ),
          )
        : null;
    return PosReceiptData(
      orderId: orderId,
      storeName: store.name,
      storeAddress: addressParts.isNotEmpty ? addressParts.join(', ') : null,
      dateTime: DateTime.now(),
      cashierEmail: cashierEmail,
      items: cartSnapshot,
      subtotal: subtotal,
      discount: discount,
      deposit: deposit,
      total: total,
      currency: currency,
      tenders: tenderSnapshot,
      change: tenderSnapshot.fold<double>(0, (s, t) => s + t.change),
      customerName: customerName,
      fiscalNumber: fiscalNumber,
      fiscalNumberNote: fiscalNumberNote,
      fiscalStamp: fiscalStamp,
      vat: vat,
    );
  }

  /// Produces the receipt the way this till is set up to (09.12), says what
  /// happened when that was not the browser dialog, and records it against the
  /// order when asked. A cash sale may open the drawer; a reprint never does.
  Future<void> _produceReceipt(
    PosReceiptData data, {
    String? recordFor,
    bool kickDrawer = false,
  }) async {
    final printer = ref.read(receiptPrinterProvider);
    final cash = data.tenders.any((t) => t.method == 'CASH');
    final outcome = await printer.print(
      data,
      openDrawer: kickDrawer && cash && printer.settings.openDrawer,
    );
    if (!mounted) return;
    if (!outcome.ok) {
      _snack(outcome.message, error: true);
      return;
    }
    if (outcome.method == ReceiptMethod.thermal || outcome.method == ReceiptMethod.save) {
      _snack(outcome.message);
    }
    if (recordFor != null && outcome.method != ReceiptMethod.none) {
      await _recordReceipt(recordFor, outcome.method.code, null);
    }
  }

  /// Keeps a copy of the receipt as a file — downloaded on the web, written to
  /// the documents folder on a native till — whatever the printer mode.
  Future<void> _saveReceipt(PosReceiptData data, {String? recordFor}) async {
    final outcome = await ref.read(receiptPrinterProvider).save(data);
    if (!mounted) return;
    _snack(outcome.message, error: !outcome.ok);
    if (outcome.ok && recordFor != null) {
      await _recordReceipt(recordFor, ReceiptMethod.save.code, null);
    }
  }

  /// Record a printed, saved or emailed receipt (best-effort — never blocks completion).
  Future<void> _recordReceipt(
    String orderId,
    String type,
    String? email,
  ) async {
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.order}/admin/orders/$orderId/receipts',
            data: {
              'receiptType': type,
              if (type == 'EMAIL' && email != null) 'emailedTo': email,
              'printCount': 1,
            },
          );
      if (!mounted) return;
      _snack(switch (type) {
        'EMAIL' => 'Receipt emailed.',
        'SAVE' => 'Receipt saved.',
        _ => 'Receipt printed.',
      });
    } catch (e) {
      if (!mounted) return;
      _snack(
        friendlyError(e, fallback: 'Could not record receipt.'),
        error: true,
      );
    }
  }

  Future<void> _showReceiptDialog(
    String orderId,
    String currency,
    double change,
    String? customerEmail, {
    required PosReceiptData receiptData,
  }) {
    return showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        icon: Icon(
          Icons.check_circle_outline,
          color: Theme.of(ctx).colorScheme.primary,
          size: 40,
        ),
        title: const Text('Sale complete'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              receiptData.fiscalNumber != null
                  ? 'Receipt no. ${receiptData.fiscalNumber}'
                  : 'Order #${shortRef(orderId).toUpperCase()}',
            ),
            if (change > 0) ...[
              const SizedBox(height: 8),
              Text(
                'Change due: ${AppFormat.money(change, currencyCode: currency)}',
                style: TextStyle(
                  color: Theme.of(ctx).colorScheme.onSurface,
                  fontWeight: FontWeight.bold,
                  fontSize: 18,
                ),
              ),
            ],
            // The card's code, once: it is the customer's to keep.
            for (final c in receiptData.soldCards) ...[
              const SizedBox(height: 12),
              Text(
                '${c.topUp ? 'Gift card top-up' : 'Gift card'} ${AppFormat.money(c.amount, currencyCode: currency)}',
                style: Theme.of(ctx).textTheme.labelLarge,
              ),
              c.code != null
                  ? SelectableText(
                      c.code!,
                      key: const Key('sold-gift-card-code'),
                      style: const TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 20,
                        fontWeight: FontWeight.bold,
                      ),
                    )
                  : Text(
                      'The card is not issued yet, so it has no code to show. It is issued once the payment lands.',
                      key: const Key('sold-gift-card-no-code'),
                      style: TextStyle(color: Theme.of(ctx).colorScheme.onSurfaceVariant),
                    ),
            ],
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              alignment: WrapAlignment.center,
              children: [
                OutlinedButton.icon(
                  onPressed: () async {
                    // A number that was not issued in time for the first print
                    // is usually there by now.
                    var data = receiptData;
                    if (data.fiscalNumber == null) {
                      final stamp = await awaitFiscalReceipt(
                        ref.read(apiClientProvider).dio,
                        orderId,
                        attempts: 1,
                      );
                      if (stamp != null) data = data.withFiscalStamp(stamp);
                    }
                    await _produceReceipt(data, recordFor: orderId);
                  },
                  icon: const Icon(Icons.print_outlined, size: 18),
                  label: const Text('Reprint'),
                ),
                OutlinedButton.icon(
                  onPressed: () => _saveReceipt(receiptData, recordFor: orderId),
                  icon: const Icon(Icons.save_alt_outlined, size: 18),
                  label: const Text('Save'),
                ),
                if (customerEmail != null && customerEmail.isNotEmpty)
                  OutlinedButton.icon(
                    onPressed: () =>
                        _recordReceipt(orderId, 'EMAIL', customerEmail),
                    icon: const Icon(Icons.email_outlined, size: 18),
                    label: const Text('Email'),
                  ),
              ],
            ),
          ],
        ),
        actions: [
          FilledButton(
            onPressed: () {
              Navigator.pop(ctx);
              context.go('/pos/cart');
            },
            child: const Text('New sale'),
          ),
        ],
      ),
    );
  }

  // ── Catalog mode: order-only checkout (no prices, no payment) ──────────────

  Widget _orderOnlyView(List<PosLine> cart) {
    final qty = cart.fold<int>(0, (s, l) => s + l.itemCount);
    // A reading column, not a full-width bar across a desktop window.
    return ContentBounds.form(
      child: Padding(
        padding: EdgeInsets.all(context.pageGutter),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'Place order',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            const SizedBox(height: 4),
            Text(
              '$qty item${qty == 1 ? '' : 's'}',
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 16),
            Expanded(
              child: ListView.separated(
                itemCount: cart.length,
                separatorBuilder: (_, _) => const Divider(height: 1),
                itemBuilder: (_, i) {
                  final l = cart[i];
                  return ListTile(
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    leading: const Icon(Icons.inventory_2_outlined),
                    title: Text(l.name),
                    subtitle: Text(l.sku),
                    trailing: Text(
                      '× ${l.qtyLabel}',
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  );
                },
              ),
            ),
            FilledButton.icon(
              style: FilledButton.styleFrom(
                backgroundColor: context.channelAccent.color,
                foregroundColor: context.channelAccent.onColor,
                padding: const EdgeInsets.symmetric(vertical: 16),
              ),
              onPressed: _processing ? null : _placeOrderOnly,
              icon: _processing
                  ? SizedBox(
                      height: 20,
                      width: 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: context.channelAccent.onColor,
                      ),
                    )
                  : const Icon(Icons.receipt_long),
              label: Text(
                _processing ? 'Placing…' : 'Place order',
                style: const TextStyle(fontSize: 17),
              ),
            ),
            const SizedBox(height: 12),
            OutlinedButton(
              onPressed: _processing ? null : () => context.go('/pos/cart'),
              child: const Text('Back to Sale'),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _placeOrderOnly() async {
    final cart = ref.read(posCartProvider);
    final storeId = ref.read(posStoreProvider);
    final customer = ref.read(posCustomerProvider);
    final walkInPhone = ref.read(posWalkInPhoneProvider);
    if (cart.isEmpty) return;
    if (storeId == null) {
      _snack('Select a store before placing the order.', error: true);
      return;
    }
    // The same rule as a priced sale (phone-at-the-till): only a store whose
    // till requires a number insists on one.
    if (ref.read(posTillPhoneProvider) == 'REQUIRED' &&
        customer == null &&
        walkInPhone.isEmpty) {
      _snack("Enter the customer's number, or attach the customer.",
          error: true);
      return;
    }
    setState(() => _processing = true);
    final dio = ref.read(apiClientProvider).dio;
    final currency = _currency;

    // Same shape as the tendered path: one idempotency base per sale, kept with
    // the sale so a queued order replays under the key it was placed with. A
    // catalog-mode sale takes no payment, so it queues with no tenders.
    final idemBase = newId();
    final idem = derivedId(idemBase, 'order');
    final sale = OfflineSale(
      id: idemBase,
      capturedAt: DateTime.now(),
      rungUpBy: _signedInUserId(),
      storeId: storeId,
      currency: currency,
      total: 0,
      itemCount: cart.fold<int>(0, (s, l) => s + l.itemCount),
      tenders: const [],
      orderRequest: {
        'storeId': storeId,
        'channel': 'POS',
        'fulfilmentType': 'PICKUP',
        'currency': currency,
        // SJ-D41: placed without prices, for a manager to price — not a
        // PENDING order the stranded-order sweeper would cancel overnight.
        'awaitingPrice': true,
        if (customer != null) 'customerId': customer.id,
        'contactPhone': customer != null ? '' : walkInPhone,
        'items': [
          for (final l in cart)
            {
              'variantId': l.variantId,
              'qty': l.qty,
              'unitPrice': l.unitPrice,
              if (l.weighingInstrumentId != null)
                'weighingInstrumentId': l.weighingInstrumentId,
              if (l.markdownId != null) 'markdownId': l.markdownId,
              // The pack's own lot and expiry, when a 2D code carried them:
              // order-svc checks them against open recalls as the till does.
              if (l.batchNo != null) 'batchNo': l.batchNo,
              if (l.expiry != null)
                'expiry': l.expiry!.toIso8601String().substring(0, 10),
            },
        ],
      },
    );
    try {
      final resp = await dio.post(
        '/${ApiConstants.order}/orders',
        data: sale.orderRequest,
        options: Options(headers: {'Idempotency-Key': idem}),
      );
      final order = resp.data['data'] as Map<String, dynamic>;
      final orderId = order['id'] as String? ?? '';
      ref.read(posSessionProvider.notifier).touch();
      final receiptData = _buildReceiptData(
        orderId: orderId,
        cartSnapshot: [...cart],
        tenderSnapshot: const [],
        discount: 0,
        total: 0,
        currency: currency,
        customerName: customer?.fullName.isNotEmpty == true
            ? customer!.fullName
            : customer?.email,
      );
      final email = customer?.email;
      ref.read(posCartProvider.notifier).clear();
      ref.read(posCustomerProvider.notifier).state = null;
      ref.read(posDiscountProvider.notifier).state = 0;
      ref.read(posDiscountReasonProvider.notifier).state = '';
      ref.read(posWalkInPhoneProvider.notifier).state = '';
      if (!mounted) return;
      setState(() => _processing = false);
      await _produceReceipt(receiptData);
      await _showOrderPlacedDialog(orderId, email, receiptData: receiptData);
    } catch (e) {
      if (!isOfflineError(e)) {
        if (!mounted) return;
        setState(() => _processing = false);
        _snack(
          friendlyError(e, fallback: 'Could not place order.'),
          error: true,
        );
        return;
      }
      // Catalog mode takes no money, but the order is still a commitment the
      // customer has been given a ticket for — queue it rather than losing it.
      await ref.read(offlineQueueProvider.notifier).enqueue(sale);
      await _finishOffline(sale, [...cart], 0, currency, customer);
    }
  }

  Future<void> _showOrderPlacedDialog(
    String orderId,
    String? customerEmail, {
    required PosReceiptData receiptData,
  }) {
    return showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (ctx) => AlertDialog(
        icon: Icon(
          Icons.check_circle_outline,
          color: Theme.of(ctx).colorScheme.primary,
          size: 40,
        ),
        title: const Text('Order placed'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('Order #${shortRef(orderId).toUpperCase()}'),
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              alignment: WrapAlignment.center,
              children: [
                OutlinedButton.icon(
                  onPressed: () => _produceReceipt(receiptData, recordFor: orderId),
                  icon: const Icon(Icons.print_outlined, size: 18),
                  label: const Text('Reprint'),
                ),
                OutlinedButton.icon(
                  onPressed: () => _saveReceipt(receiptData, recordFor: orderId),
                  icon: const Icon(Icons.save_alt_outlined, size: 18),
                  label: const Text('Save'),
                ),
                if (customerEmail != null && customerEmail.isNotEmpty)
                  OutlinedButton.icon(
                    onPressed: () =>
                        _recordReceipt(orderId, 'EMAIL', customerEmail),
                    icon: const Icon(Icons.email_outlined, size: 18),
                    label: const Text('Email'),
                  ),
              ],
            ),
          ],
        ),
        actions: [
          FilledButton(
            onPressed: () {
              Navigator.pop(ctx);
              context.go('/pos/cart');
            },
            child: const Text('New sale'),
          ),
        ],
      ),
    );
  }

  /// One staged tender: its method, what was handed over for cash, the amount,
  /// and a button to take it off again.
  Widget _tenderTile(int i, String Function(double) money) {
    final t = _tenders[i];
    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.check_circle, size: 20),
      title: Text(t.label),
      subtitle: t.method == 'CASH' && t.change > 0
          ? Text('Given ${money(t.cashGiven)} · change ${money(t.change)}')
          : (t.method == 'GIFT_CARD' ? Text('Code ${t.giftCardCode}') : null),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Its own type: the trailing slot's default is an 11px label.
          Text(
            money(t.amount),
            style: Theme.of(context).textTheme.titleSmall?.copyWith(
              fontWeight: FontWeight.bold,
            ),
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline, size: 20),
            tooltip: 'Remove tender',
            onPressed: _processing
                ? null
                : () => setState(() => _tenders.removeAt(i)),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final settlement = ref.watch(posExchangeSettlementProvider);
    final List<PosLine> cart =
        settlement?.lines ?? ref.watch(posCartProvider);
    final showPrices = ref.watch(posShowPricesProvider);
    final customer = settlement != null ? null : ref.watch(posCustomerProvider);
    final tillPhone = ref.watch(posTillPhoneProvider);
    final currency = _currency;
    // Recompute reactively (watch so discount/cart edits and the server's quote
    // of the basket refresh the figures).
    ref.watch(posDiscountProvider);
    ref.watch(posTotalsProvider);
    final due = _due;
    final remaining = _remaining;
    final settled = remaining <= 0.001;
    String money(double v) => AppFormat.money(v, currencyCode: currency);

    if (cart.isEmpty) {
      // A sale held with its card at (or taken by) the machine — kept on the
      // device when the app closed — is offered back before anything new, to
      // the business that rang it up. Another business signed in here is told
      // only that one is held: its lines and its customer are not theirs.
      final held = ref.watch(heldCardPaymentProvider);
      ref.watch(authNotifierProvider);
      if (held != null && held.belongsTo(_tenantId())) {
        return EmptyState(
          key: const Key('tender-held-sale'),
          icon: Icons.credit_card,
          title: 'A card payment is held on this till',
          message: 'A sale stopped with its card at the card machine. Put it '
              'back and press Complete Sale to carry on with that same payment '
              '— the card is never taken twice.',
          action: FilledButton(
            key: const Key('tender-held-sale-put-back'),
            onPressed: () => _putBack(held),
            child: const Text('Put the sale back'),
          ),
        );
      }
      if (held != null) {
        return EmptyState(
          key: const Key('tender-held-elsewhere'),
          icon: Icons.credit_card,
          title: 'A card payment is held for another business',
          message: 'A sale rung up under another business on this till stopped '
              'with its card at the card machine. It is finished by signing '
              'in to that business. Until then, sales here can be taken by any '
              'tender but the card machine.',
          action: OutlinedButton(
            onPressed: () => context.go('/pos/cart'),
            child: const Text('Back to Sale'),
          ),
        );
      }
      return EmptyState(
        icon: Icons.point_of_sale,
        title: 'No sale in progress',
        action: OutlinedButton(
          onPressed: () => context.go('/pos/cart'),
          child: const Text('Back to Sale'),
        ),
      );
    }

    // Catalog mode (store shows no prices): order-only checkout — no tender,
    // no amounts, just confirm the items and place the order.
    if (!showPrices) {
      return _orderOnlyView(cart);
    }

    // A held card payment this till could not read back keeps the card
    // machine out of use here until a manager clears it: said where the
    // payment buttons are, with the way out.
    final holdCtl = ref.watch(heldCardPaymentProvider.notifier);
    ref.watch(authNotifierProvider);
    final mayClear = _mayDecideCards();
    final unreadableHold = ValueListenableBuilder<bool>(
      valueListenable: holdCtl.corrupt,
      builder: (context, corrupt, _) => corrupt
          ? _UnreadableHoldNotice(
              key: const Key('tender-hold-corrupt'),
              mayClear: mayClear,
              onClear: () => _clearUnreadableHold(holdCtl),
            )
          : const SizedBox.shrink(),
    );

    // The figures, the payment buttons and the tenders added so far scroll as
    // one column, so large text or a long split never overflows the screen.
    final details = <Widget>[
      unreadableHold,
      Text(settlement != null ? 'Exchange: collect the difference' : 'Tender',
          style: theme.textTheme.headlineMedium),
      const SizedBox(height: 12),
      _SummaryRow(label: settlement != null ? 'Difference due' : 'Total due', value: money(due), bold: true),
      _SummaryRow(label: 'Paid', value: money(_paid)),
      // An unpaid balance is the normal state while tendering, not a failure:
      // bold on-surface ink like the total, never the error colour.
      _SummaryRow(
        label: settled ? 'Change' : 'Remaining',
        value: money(settled ? _change : remaining),
        bold: true,
      ),
      const SizedBox(height: 16),
      Text('Add payment', style: theme.textTheme.titleMedium),
      const SizedBox(height: 8),
      Builder(
        builder: (context) {
          // Only the tenders the owner enabled for this store (gift card and
          // store credit are store-issued instruments — always available).
          final enabled = ref.watch(posEnabledPaymentMethodsProvider);
          return Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              if (enabled.contains('CASH'))
                _TenderButton(
                  icon: Icons.payments_outlined,
                  label: 'Cash',
                  onTap: _processing || settled
                      ? null
                      : () => _addCashOrCard('CASH'),
                ),
              if (enabled.contains('CARD'))
                _TenderButton(
                  icon: Icons.credit_card,
                  label: 'Card',
                  onTap: _processing || settled
                      ? null
                      : () => _addCashOrCard('CARD'),
                ),
              if (enabled.contains('UPI'))
                _TenderButton(
                  icon: Icons.qr_code_2,
                  label: 'UPI',
                  onTap: _processing || settled
                      ? null
                      : () => _addCashOrCard('UPI'),
                ),
              if (enabled.contains('WALLET'))
                _TenderButton(
                  icon: Icons.wallet_outlined,
                  label: 'Wallet',
                  onTap: _processing || settled
                      ? null
                      : () => _addCashOrCard('WALLET'),
                ),
              _TenderButton(
                icon: Icons.card_giftcard,
                label: 'Gift card',
                onTap: _processing || settled ? null : _addGiftCard,
              ),
              _TenderButton(
                icon: Icons.account_balance_wallet_outlined,
                label: 'Store credit',
                onTap: _processing || settled ? null : _addStoreCredit,
              ),
            ],
          );
        },
      ),
      const SizedBox(height: 16),
      if (_tenders.isEmpty)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: AppSpacing.xl),
          child: Text(
            'No payments added yet',
            textAlign: TextAlign.center,
            style: TextStyle(color: cs.onSurfaceVariant),
          ),
        )
      else
        for (var i = 0; i < _tenders.length; i++) ...[
          if (i > 0) const Divider(height: 1),
          _tenderTile(i, money),
        ],
    ];

    // The walk-in phone field (phone-at-the-till): the same field as the Sale
    // tab's, right above the action that completes the sale, whenever no
    // registered customer is attached and this store's till asks at all.
    if (settlement == null && customer == null && tillPhone != 'OFF') {
      details.addAll([
        const SizedBox(height: 16),
        TextField(
          key: const Key('tender-phone-field'),
          controller: _phoneCtrl,
          keyboardType: TextInputType.phone,
          decoration: InputDecoration(
            labelText: posPhoneFieldLabel(tillPhone),
            hintText: posPhoneFieldHint(tillPhone),
            errorText: _phoneError,
            errorMaxLines: 3,
            prefixIcon: const Icon(Icons.phone_outlined, size: 18),
          ),
          onChanged: (v) {
            ref.read(posWalkInPhoneProvider.notifier).state = v.trim();
            if (_phoneError != null) setState(() => _phoneError = null);
          },
        ),
      ]);
    }

    final complete = FilledButton.icon(
      style: FilledButton.styleFrom(
        backgroundColor: context.channelAccent.color,
        foregroundColor: context.channelAccent.onColor,
        padding: const EdgeInsets.symmetric(vertical: 16),
      ),
      onPressed: (_processing || !settled) ? null : _complete,
      icon: _processing
          ? SizedBox(
              height: 20,
              width: 20,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: context.channelAccent.onColor,
              ),
            )
          : const Icon(Icons.check_circle_outline),
      label: Text(
        _processing ? 'Processing…' : 'Complete Sale',
        style: const TextStyle(fontSize: 17),
      ),
    );
    // While a card is at the machine the till says what it is waiting on, just
    // above the button the cashier pressed.
    final action = _atCardMachine
        ? Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const _WaitingForCardMachine(key: Key('tender-terminal-waiting')),
              const SizedBox(height: AppSpacing.md),
              complete,
            ],
          )
        : complete;
    final back = settlement != null
        // The exchange itself is done; what is owed stays on its order. Leaving
        // it drops the settlement so the next sale is not mistaken for it.
        ? OutlinedButton(
            key: const Key('tender-exchange-leave'),
            onPressed: _processing
                ? null
                : () {
                    ref.read(posExchangeSettlementProvider.notifier).state =
                        null;
                    context.go('/pos/cart');
                  },
            child: const Text('Leave it unpaid'),
          )
        : OutlinedButton(
            onPressed: _processing ? null : () => context.go('/pos/cart'),
            child: const Text('Back to Sale'),
          );
    final gutter = context.pageGutter;

    return LayoutBuilder(
      builder: (context, constraints) {
        if (AppBreakpoints.classOf(constraints.maxWidth) ==
            WindowClass.compact) {
          // A phone: the column scrolls, so Complete Sale stays under the
          // thumb in a bar at the foot of the screen.
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Expanded(
                child: ListView(
                  padding: EdgeInsets.all(gutter),
                  children: [...details, const SizedBox(height: 16), back],
                ),
              ),
              BottomActionBar(child: action),
            ],
          );
        }
        // Wider: a form-width column, so Complete Sale is not a bar across a
        // desktop window and each amount sits by its label.
        return ContentBounds.form(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Expanded(
                child: ListView(
                  padding: EdgeInsets.fromLTRB(gutter, gutter, gutter, 0),
                  children: details,
                ),
              ),
              Padding(
                padding: EdgeInsets.fromLTRB(
                  gutter,
                  AppSpacing.lg,
                  gutter,
                  gutter,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [action, const SizedBox(height: 12), back],
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

/// A held card payment on this till could not be read back: the card machine
/// is not used from here until a manager clears it. A warning, not an error:
/// every other tender still works.
class _UnreadableHoldNotice extends StatelessWidget {
  final bool mayClear;
  final VoidCallback onClear;

  const _UnreadableHoldNotice({
    super.key,
    required this.mayClear,
    required this.onClear,
  });

  @override
  Widget build(BuildContext context) {
    final status = context.status;
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: Semantics(
        liveRegion: true,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: status.warningContainer,
            borderRadius: AppRadius.input,
          ),
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.md),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'A card payment saved on this till could not be read',
                  style: text.titleSmall?.copyWith(
                    color: status.onWarningContainer,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: AppSpacing.xs),
                Text(
                  'The card machine is not used from this till until a '
                  'manager clears it: a payment from that sale may still be on '
                  'it. Every other tender can be taken.',
                  style: text.bodySmall?.copyWith(
                    color: status.onWarningContainer,
                  ),
                ),
                if (mayClear)
                  Align(
                    alignment: AlignmentDirectional.centerEnd,
                    child: TextButton(
                      key: const Key('tender-hold-corrupt-clear'),
                      style: TextButton.styleFrom(
                          foregroundColor: status.onWarningContainer),
                      onPressed: onClear,
                      child: const Text('Clear it'),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// What the till is waiting on while a card is at the machine: the amount has
/// been sent and the cardholder is still to tap, insert or enter a PIN. A notice,
/// not an error — nothing has gone wrong — so it reads on the info container.
class _WaitingForCardMachine extends StatelessWidget {
  const _WaitingForCardMachine({super.key});

  @override
  Widget build(BuildContext context) {
    final status = context.status;
    final text = Theme.of(context).textTheme;
    // A live region, so a screen reader says it when it appears.
    return Semantics(
      liveRegion: true,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: status.infoContainer,
          borderRadius: AppRadius.input,
        ),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(Icons.credit_card, size: 20, color: status.onInfoContainer),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Waiting for the card machine…',
                      style: text.titleSmall?.copyWith(
                        color: status.onInfoContainer,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: AppSpacing.xs),
                    Text(
                      'The customer can finish paying on the machine. Do not '
                      'take the card again.',
                      style: text.bodySmall?.copyWith(
                        color: status.onInfoContainer,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SummaryRow extends StatelessWidget {
  final String label;
  final String value;
  final bool bold;
  const _SummaryRow({
    required this.label,
    required this.value,
    this.bold = false,
  });

  @override
  Widget build(BuildContext context) {
    // On-surface ink for every figure, the balance included.
    final style = Theme.of(context).textTheme.titleMedium?.copyWith(
      fontWeight: bold ? FontWeight.bold : FontWeight.normal,
    );
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        children: [
          Expanded(child: Text(label, style: style)),
          Text(value, style: style),
        ],
      ),
    );
  }
}

class _TenderButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback? onTap;
  const _TenderButton({required this.icon, required this.label, this.onTap});

  @override
  Widget build(BuildContext context) {
    return OutlinedButton.icon(
      onPressed: onTap,
      icon: Icon(icon, size: 18),
      label: Text(label),
      style: OutlinedButton.styleFrom(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      ),
    );
  }
}

/// Prompts for an amount; for cash the default is the remaining balance but the
/// cashier may hand over more (to compute change).
class _AmountDialog extends StatefulWidget {
  final String title;
  final String currency;
  final double remaining;
  final bool allowOverpay;
  const _AmountDialog({
    required this.title,
    required this.currency,
    required this.remaining,
    required this.allowOverpay,
  });

  @override
  State<_AmountDialog> createState() => _AmountDialogState();
}

/// The amount a cashier typed, when the till can send it as money: a finite
/// figure above zero with at most [minorUnits] decimals. Null for anything
/// else — `Infinity`, `NaN`, `1e3`, a comma, a third decimal of a pound — which
/// `double.tryParse` would otherwise read as a number (NaN is not <= 0).
@visibleForTesting
double? tenderAmount(String text, int minorUnits) {
  final t = text.trim();
  final pattern = minorUnits <= 0
      ? RegExp(r'^\d+$')
      : RegExp('^(\\d+(\\.\\d{0,$minorUnits})?|\\.\\d{1,$minorUnits})\$');
  if (!pattern.hasMatch(t)) return null;
  final value = double.tryParse(t);
  if (value == null || !value.isFinite || value <= 0) return null;
  return value;
}

class _AmountDialogState extends State<_AmountDialog> {
  late final int _minor = AppFormat.minorUnits(widget.currency);
  late final TextEditingController _ctrl = TextEditingController(
    text: widget.remaining.toStringAsFixed(_minor),
  );

  /// Why Add is off, in words; null while the field is empty or fine.
  String? _problem() {
    final t = _ctrl.text.trim();
    if (t.isEmpty || tenderAmount(t, _minor) != null) return null;
    final value = double.tryParse(t);
    if (!RegExp(r'^[\d.]+$').hasMatch(t) ||
        value == null ||
        !value.isFinite ||
        '.'.allMatches(t).length > 1) {
      return 'Enter the amount in figures.';
    }
    if (value <= 0) return 'Enter an amount above zero.';
    return _minor <= 0
        ? 'This currency has no decimal places.'
        : 'At most $_minor decimal places.';
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  List<double> _quick() {
    final notes = [5, 10, 20, 50, 100];
    return [
      for (final n in notes)
        if (n >= widget.remaining) n.toDouble(),
    ].take(4).toList();
  }

  @override
  Widget build(BuildContext context) {
    // Only an amount the till can send as money; anything else leaves Add off.
    final amount = tenderAmount(_ctrl.text, _minor);
    final entered = amount ?? 0;
    final change = widget.allowOverpay && entered > widget.remaining
        ? entered - widget.remaining
        : 0.0;
    final symbol = AppFormat.currencySymbol(widget.currency);
    return AlertDialog(
      title: Text('${widget.title} payment'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            controller: _ctrl,
            autofocus: true,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            onChanged: (_) => setState(() {}),
            decoration: InputDecoration(
              labelText: 'Amount',
              prefixText: symbol.isEmpty ? null : '$symbol ',
              errorText: _problem(),
            ),
          ),
          if (widget.allowOverpay) ...[
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              children: [
                ActionChip(
                  label: const Text('Exact'),
                  onPressed: () => setState(
                    () => _ctrl.text = widget.remaining.toStringAsFixed(_minor),
                  ),
                ),
                for (final amt in _quick())
                  ActionChip(
                    label: Text(
                      AppFormat.money(amt, currencyCode: widget.currency),
                    ),
                    onPressed: () => setState(
                        () => _ctrl.text = amt.toStringAsFixed(_minor)),
                  ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              'Change: ${AppFormat.money(change, currencyCode: widget.currency)}',
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurface,
                fontWeight: FontWeight.bold,
              ),
            ),
          ],
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: amount == null
              ? null
              : () => Navigator.pop(context, (amount: amount, given: amount)),
          child: const Text('Add'),
        ),
      ],
    );
  }
}

/// Validates a gift-card code, shows its balance, and stages a gift-card tender
/// (capped at the lower of the card balance and the remaining balance).
class _GiftCardTenderDialog extends ConsumerStatefulWidget {
  final String currency;
  final double remaining;
  const _GiftCardTenderDialog({
    required this.currency,
    required this.remaining,
  });

  @override
  ConsumerState<_GiftCardTenderDialog> createState() =>
      _GiftCardTenderDialogState();
}

class _GiftCardTenderDialogState extends ConsumerState<_GiftCardTenderDialog> {
  final _codeCtrl = TextEditingController();
  bool _checking = false;
  String? _error;
  double? _balance;
  String? _validCode;

  @override
  void dispose() {
    _codeCtrl.dispose();
    super.dispose();
  }

  Future<void> _check() async {
    final code = _codeCtrl.text.trim();
    if (code.isEmpty) return;
    setState(() {
      _checking = true;
      _error = null;
      _balance = null;
    });
    try {
      final r = await giftCardLookup(ref, code);
      if (r.status.toUpperCase() != 'ACTIVE') {
        setState(() => _error = 'Card is ${r.status.toLowerCase()}.');
      } else if (r.balance <= 0) {
        setState(() => _error = 'Card has no balance.');
      } else {
        setState(() {
          _balance = r.balance;
          _validCode = code;
        });
      }
    } catch (e) {
      setState(
        () => _error = (e is DioException && e.response?.statusCode == 404)
            ? 'No gift card with that code.'
            : friendlyError(e, fallback: 'Lookup failed.'),
      );
    } finally {
      if (mounted) setState(() => _checking = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final applied = _balance == null
        ? 0.0
        : _balance!.clamp(0, widget.remaining).toDouble();
    return AlertDialog(
      title: const Text('Gift card'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            controller: _codeCtrl,
            autofocus: true,
            textCapitalization: TextCapitalization.characters,
            decoration: InputDecoration(
              labelText: 'Card code',
              suffixIcon: _checking
                  ? const Padding(
                      padding: EdgeInsets.all(12),
                      child: SizedBox(
                        height: 16,
                        width: 16,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      ),
                    )
                  : IconButton(
                      icon: const Icon(Icons.search),
                      tooltip: 'Check balance',
                      onPressed: _check,
                    ),
            ),
            onSubmitted: (_) => _check(),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(
              _error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ],
          if (_balance != null) ...[
            const SizedBox(height: 12),
            Text(
              'Balance: ${AppFormat.money(_balance!, currencyCode: widget.currency)}',
            ),
            Text(
              'Applies: ${AppFormat.money(applied, currencyCode: widget.currency)}',
              style: const TextStyle(fontWeight: FontWeight.bold),
            ),
          ],
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: (_balance == null || applied <= 0)
              ? null
              : () => Navigator.pop(
                  context,
                  PosTender(
                    method: 'GIFT_CARD',
                    amount: applied,
                    giftCardCode: _validCode,
                  ),
                ),
          child: const Text('Add'),
        ),
      ],
    );
  }
}

/// Shows the customer's store-credit balance and stages a store-credit tender
/// (capped at the lower of the balance and the remaining balance).
class _StoreCreditTenderDialog extends ConsumerWidget {
  final Customer customer;
  final String currency;
  final double remaining;
  const _StoreCreditTenderDialog({
    required this.customer,
    required this.currency,
    required this.remaining,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(customerStoreCreditProvider(customer.id));
    return AlertDialog(
      title: const Text('Store credit'),
      content: SizedBox(
        width: 320,
        child: async.when(
          loading: () => const SizedBox(
            height: 80,
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (e, _) =>
              Text(friendlyError(e, fallback: 'Could not load store credit.')),
          data: (acct) {
            final applied = acct.balance.clamp(0, remaining).toDouble();
            return Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  customer.fullName.isEmpty
                      ? customer.email
                      : customer.fullName,
                ),
                const SizedBox(height: 8),
                Text(
                  'Balance: ${AppFormat.money(acct.balance, currencyCode: currency)}',
                ),
                Text(
                  'Applies: ${AppFormat.money(applied, currencyCode: currency)}',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
                if (applied <= 0) ...[
                  const SizedBox(height: 8),
                  Text(
                    'No store credit available.',
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.error,
                    ),
                  ),
                ],
              ],
            );
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        Consumer(
          builder: (context, ref, _) {
            final async = ref.watch(customerStoreCreditProvider(customer.id));
            final applied = async.maybeWhen(
              data: (a) => a.balance.clamp(0, remaining).toDouble(),
              orElse: () => 0.0,
            );
            return FilledButton(
              onPressed: applied <= 0
                  ? null
                  : () => Navigator.pop(
                      context,
                      PosTender(
                        method: 'STORE_CREDIT',
                        amount: applied,
                        customerId: customer.id,
                      ),
                    ),
              child: const Text('Add'),
            );
          },
        ),
      ],
    );
  }
}

// Fallback used when no matching store is found in posStoresProvider.
StoreInfo _emptyStore() => const StoreInfo(
  id: '',
  name: 'Store',
  code: '',
  type: 'STORE',
  status: 'ACTIVE',
);
