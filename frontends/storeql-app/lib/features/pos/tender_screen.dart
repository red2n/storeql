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
import '../../shared/widgets/empty_state.dart';
import '../admin/customer_providers.dart';
import '../admin/providers/admin_providers.dart';
import 'pos_fiscal_receipt.dart';
import 'pos_providers.dart';
import 'pos_terminal.dart';
import 'pos_receipt.dart';
import 'pos_receipt_printer.dart';
import 'pos_session_providers.dart';
import '../../shared/util/short_ref.dart';
import 'customer_display.dart';
import 'customer_display_channel.dart';
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

  @override
  void initState() {
    super.initState();
    _phoneCtrl = TextEditingController(text: ref.read(posWalkInPhoneProvider));
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
    final subtotal = ref.read(posCartProvider.notifier).total;
    return ref.read(posDiscountProvider).clamp(0, subtotal).toDouble();
  }

  /// Goods less the discount, plus the return-scheme deposits on the sale's
  /// containers (09.16): the deposit is due in full whatever the discount.
  double get _due =>
      ref.read(posCartProvider.notifier).total -
      _discount +
      ref.read(posCartProvider.notifier).deposits;

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
    final cart = ref.read(posCartProvider);
    return cart.isNotEmpty ? cart.first.currency : '';
  }

  Future<void> _complete() async {
    if (_processing) return;
    final cart = ref.read(posCartProvider);
    final storeId = ref.read(posStoreProvider);
    final customer = ref.read(posCustomerProvider);
    final walkInPhone = ref.read(posWalkInPhoneProvider);
    final tillPhone = ref.read(posTillPhoneProvider);
    final discount = _discount;
    if (cart.isEmpty) return;
    if (storeId == null) {
      _snack('Select a store before tendering.', error: true);
      return;
    }
    // Only a Required store blocks here (phone-at-the-till) — Optional and
    // Don't ask complete with the field blank, or absent altogether.
    if (tillPhone == 'REQUIRED' && customer == null && walkInPhone.isEmpty) {
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
    final dio = ref.read(apiClientProvider).dio;
    final currency = _currency;

    // One idempotency base for the whole sale, fixed here rather than per attempt.
    // If the network drops partway it is carried into the offline queue with the
    // sale, so every later replay presents the same keys — which is what makes
    // replaying a half-finished sale safe rather than a double charge.
    final idemBase = newId();
    var sale = OfflineSale(
      id: idemBase,
      capturedAt: DateTime.now(),
      storeId: storeId,
      currency: currency,
      total: _due,
      itemCount: cart.fold<int>(0, (s, l) => s + l.itemCount),
      orderRequest: {
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
        'items': [
          for (final l in cart)
            {
              'variantId': l.variantId,
              'qty': l.qty,
              'unitPrice': l.unitPrice,
              if (l.weighingInstrumentId != null)
                'weighingInstrumentId': l.weighingInstrumentId,
              if (l.markdownId != null) 'markdownId': l.markdownId,
            },
        ],
      },
      // STORE_CREDIT redemption is done server-side by payment-svc (it redeems the
      // customer's balance as part of capturing the tender), so only GIFT_CARD
      // carries a redemption step of its own here.
      tenders: [
        for (final t in _tenders)
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
      ],
    );

    try {
      // 1. Place the POS order (server is authoritative for the total).
      final orderResp = await dio.post(
        '/${ApiConstants.order}/orders',
        data: sale.orderRequest,
        options: Options(headers: {'Idempotency-Key': derivedId(idemBase, 'order')}),
      );
      final order = orderResp.data['data'] as Map<String, dynamic>;
      final orderId = order['id'] as String? ?? '';
      sale = sale.copyWith(orderId: orderId);

      // 2. Record each tender against the order, then redeem any gift card it drew
      // on. Progress is tracked on `sale` step by step, so if the network drops
      // here only the steps that have not landed are queued.
      for (var i = 0; i < sale.tenders.length; i++) {
        final t = sale.tenders[i];
        // A card on a terminal is approved BEFORE it is recorded (07.16). The
        // key is derived from the sale and the tender's position, never freshly
        // generated: a second press with the same key finds the first attempt
        // instead of starting a second EMV transaction on a real card.
        // The terminal is read from the till's own tender, not from the queued
        // one. Deliberate: an OfflineTender is persisted and replayed, and a
        // card must never be sent to a terminal minutes or hours after the
        // customer has left. So a queued sale replays as a plain CARD tender —
        // which is what actually happened, because with no network the cashier
        // took the card on the terminal standalone, exactly as before this row.
        final terminalId = i < _tenders.length ? _tenders[i].terminalId : null;
        if (terminalId != null) {
          final outcome = await takeCardOnTerminal(
            dio,
            terminalId: terminalId,
            orderId: orderId,
            amount: t.amount,
            currency: currency,
            idempotencyKey: derivedId(idemBase, 'term:$i'),
          );
          if (!outcome.approved) throw TerminalNotApproved(outcome);
          // Back onto the till's own tender, because that is what the receipt is
          // built from. Kept in a side map it would print nothing, which is how a
          // card receipt ends up without the line the scheme rules require.
          final line = outcome.receiptLine;
          if (line != null && line.isNotEmpty) {
            _tenders[i] = _tenders[i].withTerminalOutcome(line);
          }
        }
        await dio.post(
          '/${ApiConstants.payment}/payments',
          data: {...t.body, 'orderId': orderId},
          options: Options(headers: {'Idempotency-Key': derivedId(idemBase, 'pay:$i')}),
        );
        sale = sale.markTender(i, tenderDone: true);
        final code = t.giftCardCode;
        if (code != null) {
          await dio.post(
            '/${ApiConstants.order}/gift-cards/$code/redeem',
            data: {'amount': t.amount, 'orderId': orderId},
          );
          sale = sale.markTender(i, redeemDone: true);
        }
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
      ref.read(posSessionProvider.notifier).touch();

      // The legal receipt number — and the regime's stamp on the sale — is
      // issued when the payment reaches order-svc, a few seconds after the
      // last tender. Wait a bounded time for it rather than print a receipt
      // without one.
      final fiscalStamp = await awaitFiscalReceipt(dio, orderId);
      final fiscalNumber = fiscalStamp?.fullNumber;

      // Capture everything needed for the receipt before clearing state.
      final receiptData = _buildReceiptData(
        orderId: orderId,
        fiscalNumber: fiscalNumber,
        fiscalStamp: fiscalStamp,
        fiscalNumberNote: fiscalNumber == null
            ? 'Receipt number not issued yet. Reprint once it is.'
            : null,
        cartSnapshot: [...cart],
        tenderSnapshot: [..._tenders],
        discount: discount,
        total: (order['total'] as num?)?.toDouble() ?? _due,
        currency: currency,
        customerName: customer?.fullName.isNotEmpty == true
            ? customer!.fullName
            : customer?.email,
      );

      final change = _change;
      final email = customer?.email;
      ref.read(posCartProvider.notifier).clear();
      ref.read(posCustomerProvider.notifier).state = null;
      ref.read(posDiscountProvider.notifier).state = 0;
      ref.read(posDiscountReasonProvider.notifier).state = '';
      ref.read(posWalkInPhoneProvider.notifier).state = '';
      _tenders.clear();
      if (!mounted) return;
      setState(() => _processing = false);

      // The receipt comes out the way this till is set up to (09.12): the
      // browser's dialog, a thermal printer, a file. A cash sale may open the drawer.
      await _produceReceipt(receiptData, kickDrawer: true);

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
      // A card the terminal did not approve (07.16). A definite answer, so the
      // sale is never queued for replay — but the order IS placed and awaiting
      // payment, so the cashier can take another tender rather than start again.
      if (e is TerminalNotApproved) {
        if (!mounted) return;
        setState(() => _processing = false);
        if (e.outcome.uncertain) {
          // A timeout may have charged the card. A snackbar can be missed and
          // this one must not be, so it blocks until somebody acknowledges it.
          await showDialog<void>(
            context: context,
            barrierDismissible: false,
            builder: (_) => AlertDialog(
              key: const Key('tender-terminal-uncertain'),
              title: const Text('Check the card machine'),
              content: Text(
                '${e.outcome.message}\n\nThe sale is saved and still awaiting '
                'payment. Do not take the card again until you know whether it '
                'went through.',
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('I have checked'),
                ),
              ],
            ),
          );
        } else {
          _snack('${e.outcome.message} — try another tender.', error: true);
        }
        return;
      }
      if (!isOfflineError(e)) {
        // The server answered and said no. Replaying would get the same answer,
        // so the sale must not be queued — the cashier has to deal with it now.
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
        _snack(friendlyError(e, fallback: 'Sale failed.'), error: true);
        return;
      }
      // The server could not be reached. The customer has paid and is standing
      // there, so the sale completes at the till and whatever it still owes the
      // server is held until the network is back.
      await ref.read(offlineQueueProvider.notifier).enqueue(sale);
      await _finishOffline(sale, [...cart], discount, currency, customer);
    }
  }

  /// Finish a sale the server was never told about: print the receipt, clear the
  /// till, and say plainly that it is held rather than sent.
  Future<void> _finishOffline(
    OfflineSale sale,
    List<PosLine> cartSnapshot,
    double discount,
    String currency,
    Customer? customer,
  ) async {
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
    ref.read(posCartProvider.notifier).clear();
    ref.read(posCustomerProvider.notifier).state = null;
    ref.read(posDiscountProvider.notifier).state = 0;
    ref.read(posDiscountReasonProvider.notifier).state = '';
    ref.read(posWalkInPhoneProvider.notifier).state = '';
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
    final cart = ref.watch(posCartProvider);
    final showPrices = ref.watch(posShowPricesProvider);
    final customer = ref.watch(posCustomerProvider);
    final tillPhone = ref.watch(posTillPhoneProvider);
    final currency = _currency;
    // Recompute reactively (watch so discount/cart edits refresh the figures).
    ref.watch(posDiscountProvider);
    final due = _due;
    final remaining = _remaining;
    final settled = remaining <= 0.001;
    String money(double v) => AppFormat.money(v, currencyCode: currency);

    if (cart.isEmpty) {
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

    // The figures, the payment buttons and the tenders added so far scroll as
    // one column, so large text or a long split never overflows the screen.
    final details = <Widget>[
      Text('Tender', style: theme.textTheme.headlineMedium),
      const SizedBox(height: 12),
      _SummaryRow(label: 'Total due', value: money(due), bold: true),
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
    if (customer == null && tillPhone != 'OFF') {
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
    final back = OutlinedButton(
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
              BottomActionBar(child: complete),
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
                  children: [complete, const SizedBox(height: 12), back],
                ),
              ),
            ],
          ),
        );
      },
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

class _AmountDialogState extends State<_AmountDialog> {
  late final TextEditingController _ctrl = TextEditingController(
    text: widget.remaining.toStringAsFixed(2),
  );

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
    final entered = double.tryParse(_ctrl.text) ?? 0;
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
                    () => _ctrl.text = widget.remaining.toStringAsFixed(2),
                  ),
                ),
                for (final amt in _quick())
                  ActionChip(
                    label: Text(
                      AppFormat.money(amt, currencyCode: widget.currency),
                    ),
                    onPressed: () =>
                        setState(() => _ctrl.text = amt.toStringAsFixed(2)),
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
          onPressed: entered <= 0
              ? null
              : () => Navigator.pop(context, (amount: entered, given: entered)),
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
