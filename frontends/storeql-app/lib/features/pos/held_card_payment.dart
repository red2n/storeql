import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show ValueNotifier, debugPrint;
import 'package:flutter_riverpod/legacy.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/ids.dart';
import '../../core/storage/app_storage.dart';
import '../admin/customer_providers.dart' show Customer;
import 'pos_providers.dart';
import 'pos_terminal.dart';

// ---------------------------------------------------------------------------
// A card payment the till left at the machine (07.16).
//
// When a sale stops with its card still at the machine, or with no answer from
// it, the order is placed and the amount may still be on the card machine. The
// till keeps that sale's keys, so pressing Complete Sale again presents the same
// keys and payment-svc hands back the attempt it already has: the amount is
// never sent twice.
//
// That only holds while nothing that sale sent has changed, because a key
// stands for one request. Before this was kept here, the hold lived in the
// tender screen's own state and was dropped silently: by editing the phone or
// the basket, by taking the card off and cash instead, or by pressing Back. The
// next press then placed a second order and sent a second amount to the machine
// while the first was still waiting there. If the first then approved, the
// customer paid twice.
//
// So the hold lives here, beyond the screen and beyond the app: it is written
// to the device (the same storage the offline sale queue keeps its sales in)
// before any amount goes to a machine — a hold the device would not take
// sends nothing to a machine — and read back when the app starts. A hold the
// till cannot read back is set aside, and the card machine is not used from
// this till until a manager has cleared it ([HeldCardPaymentNotifier.corrupt]).
// A press that would start anything new while it stands first settles it: the
// till reads what the machine has said. A payment still at the machine is
// waited for, or cancelled on it and waited for: a cancel settles nothing until
// the machine answers it. A card the machine approved means the customer has
// paid for the sale as it was, so the sale is put back and finished, or — by a
// manager, with a reason, which payment-svc keeps on the refund — the money is
// put back on the card through payment-svc's linked refund. A payment the
// machine never answered — timed out, or a request left at the machine that
// no cancel stops — is settled only by a manager recording what the machine
// shows, with a reason; a cashier's "I have checked" never was enough. So is
// a refund of it the machine never answered: until then the sale's card can
// be neither recorded nor put back. Only payments known to have taken nothing
// free the till for the changed sale — and only when nothing is recorded on
// the held order ([HeldCardPayment.mayBeRecorded]). A tender recorded there,
// or that may be — a gift card redeemed, store credit, cash — is that
// order's: the hold stays with its card places reopened, the same sale
// carries that order on, and any other is refused until the earlier sale is
// put back and finished, or cancelled, which refunds it. A new order would
// take it again.
//
// Letting a hold go with its sale unfinished is giving its order up. A
// machine's answer can arrive after a person has said what it shows: payment-svc
// keeps an approval that follows a manager's "nothing taken", the money being
// on the card. An order left awaiting payment then reads as a sale still owed
// that card, when its customer paid another way on another order — recorded
// on it, one basket would be paid twice in the books. So a sale whose card a
// manager recorded as not taken keeps its hold and its order: paid another
// way, it is that same order, and the late approval finds it paid. And when
// the sale on the till is another one, the order let go is cancelled first
// (payment-svc then puts back by itself whatever a machine takes for it) and
// remembered on the device ([HeldCardPaymentNotifier.wasLetGo]): this till
// never records a card on an order it let go.
//
// payment-svc now holds the same line on its side: a machine with an earlier
// card payment unsettled refuses the next card (409
// TERMINAL_UNSETTLED_APPROVAL). The hold is still kept here, because it is
// what lets the till carry the same sale on under the same keys and finish it.
//
// A hold is one sale's, of one business. It carries on only the sale that made
// it ([HeldCardPayment.saleId]): the basket never let go, or the held sale put
// back. Another basket — the next customer buying the same thing — sends the
// same order, but carried on under these keys it would be recorded as paid by
// the earlier customer's card and the machine never asked for this one; so any
// other basket, identical or not, settles the hold first. And it is read,
// settled and let go only under the business that made it
// ([HeldCardPayment.tenantId]): payment-svc answers a business's own attempts
// only, so read under another — the sandbox, or another business signed in on
// this device — the held order has none, and would look settled.
//
// What is final is not asked again. A card a machine approved
// ([HeldCardPayment.approved]) has taken the money; asking after it again
// would only meet a machine retired since (payment-svc refuses a retired
// terminal before it looks the key up), so the press goes straight to its
// record. A tender the server recorded ([HeldCardPayment.paid]) is not sent
// again either: a check made before the key is looked up (the cash limit)
// would refuse the copy. A card the till heard from the machine has its
// attempt ([HeldCardPayment.attemptIds]): when the machine is retired under
// it, the attempt is read instead of asked again.
//
// What may change and what may not. A sale's tenders are taken in order, so the
// first [HeldCardPayment.fixed] of them are the ones the server may have acted
// on under the held keys: a card sent to a machine, a payment or gift card sent
// to be recorded. Those, and the order, must stay exactly as they were. A tender
// after them never reached the server and may change — a declined card swapped
// for cash — and the press carries on the same order and the same approved
// cards. A declined card's own key is spent (the server would only hand the
// decline back), so the next card at that place goes under a key of its own
// ([HeldCardPayment.termTries]).
// ---------------------------------------------------------------------------

/// Where the held card payment is kept on the device. Beside the offline sale
/// queue, in the same storage, for the same reason: it outlives the app, and a
/// sign-out keeps it ([StorageKeys.keptOnSignOut]).
const heldCardPaymentStorageKey = StorageKeys.posHeldCardPayment;

/// Where an unreadable held payment is set aside, so it is not overwritten by
/// the next one and can still be looked at.
const heldCardPaymentCorruptStorageKey = StorageKeys.posHeldCardPaymentCorrupt;

/// Where the orders this till let go of unfinished are kept
/// ([HeldCardPaymentNotifier.releaseUnfinished]).
const heldCardPaymentLetGoStorageKey = StorageKeys.posLetGoCardOrders;

/// The key a card at place [i] of the sale [base] is sent to the machine under:
/// the first try's is `term:i`, as it always was; a try after a decline has
/// one of its own, since the declined one's key would only find the decline.
String terminalKey(String base, int i, int tries) =>
    derivedId(base, tries == 0 ? 'term:$i' : 'term:$i:$tries');

/// The sale as it was when its card went to the machine: enough to put it
/// back exactly, so the next press presents the same keys.
class HeldSale {
  final String? storeId;
  final List<PosLine> lines;
  final PosExchangeSettlement? settlement;
  final Customer? customer;
  final String walkInPhone;
  final double discount;
  final String discountReason;
  final List<PosTender> tenders;

  const HeldSale({
    required this.storeId,
    required this.lines,
    required this.settlement,
    required this.customer,
    required this.walkInPhone,
    required this.discount,
    required this.discountReason,
    required this.tenders,
  });

  Map<String, dynamic> toJson() => {
        'storeId': storeId,
        'lines': [for (final l in lines) _lineToJson(l)],
        'settlement': settlement == null
            ? null
            : {
                'orderId': settlement!.orderId,
                'due': settlement!.due,
                'currency': settlement!.currency,
                'lines': [for (final l in settlement!.lines) _lineToJson(l)],
              },
        'customer': customer == null
            ? null
            : {
                'id': customer!.id,
                'email': customer!.email,
                'phone': customer!.phone,
                'firstName': customer!.firstName,
                'lastName': customer!.lastName,
                'status': customer!.status,
                'dob': customer!.dob,
                'gender': customer!.gender,
              },
        'walkInPhone': walkInPhone,
        'discount': discount,
        'discountReason': discountReason,
        'tenders': [for (final t in tenders) _tenderToJson(t)],
      };

  factory HeldSale.fromJson(Map<String, dynamic> j) {
    final s = j['settlement'];
    final c = j['customer'];
    return HeldSale(
      storeId: j['storeId'] as String?,
      lines: _linesFromJson(j['lines']),
      settlement: s is Map
          ? PosExchangeSettlement(
              orderId: s['orderId'] as String? ?? '',
              due: (s['due'] as num?)?.toDouble() ?? 0,
              currency: s['currency'] as String? ?? '',
              lines: _linesFromJson(s['lines']),
            )
          : null,
      customer: c is Map ? Customer.fromJson(Map<String, dynamic>.from(c)) : null,
      walkInPhone: j['walkInPhone'] as String? ?? '',
      discount: (j['discount'] as num?)?.toDouble() ?? 0,
      discountReason: j['discountReason'] as String? ?? '',
      tenders: [
        for (final t in (j['tenders'] as List?) ?? const [])
          if (t is Map) _tenderFromJson(Map<String, dynamic>.from(t)),
      ],
    );
  }
}

Map<String, dynamic> _lineToJson(PosLine l) => {
      'variantId': l.variantId,
      'sku': l.sku,
      'name': l.name,
      'qty': l.qty,
      'unitPrice': l.unitPrice,
      'currency': l.currency,
      'soldBy': l.soldBy,
      'unit': l.unit,
      'weighingInstrumentId': l.weighingInstrumentId,
      'markdownId': l.markdownId,
      'originalPrice': l.originalPrice,
      'batchNo': l.batchNo,
      'expiry': l.expiry?.toIso8601String(),
      'depositMaterial': l.depositMaterial,
      'depositVolumeMl': l.depositVolumeMl,
      'depositEach': l.depositEach,
      'giftCard': l.giftCard,
      'giftCardCode': l.giftCardCode,
    };

List<PosLine> _linesFromJson(Object? rows) => [
      for (final e in (rows as List?) ?? const [])
        if (e is Map)
          PosLine(
            variantId: e['variantId'] as String? ?? '',
            sku: e['sku'] as String? ?? '',
            name: e['name'] as String? ?? '',
            qty: (e['qty'] as num?)?.toDouble() ?? 0,
            unitPrice: (e['unitPrice'] as num?)?.toDouble() ?? 0,
            currency: e['currency'] as String? ?? '',
            soldBy: e['soldBy'] as String? ?? 'EACH',
            unit: e['unit'] as String?,
            weighingInstrumentId: e['weighingInstrumentId'] as String?,
            markdownId: e['markdownId'] as String?,
            originalPrice: (e['originalPrice'] as num?)?.toDouble(),
            batchNo: e['batchNo'] as String?,
            expiry: DateTime.tryParse(e['expiry'] as String? ?? ''),
            depositMaterial: e['depositMaterial'] as String?,
            depositVolumeMl: (e['depositVolumeMl'] as num?)?.toInt(),
            depositEach: (e['depositEach'] as num?)?.toDouble() ?? 0,
            giftCard: e['giftCard'] as bool? ?? false,
            giftCardCode: e['giftCardCode'] as String?,
          ),
    ];

Map<String, dynamic> _tenderToJson(PosTender t) => {
      'method': t.method,
      'amount': t.amount,
      'cashGiven': t.cashGiven,
      'giftCardCode': t.giftCardCode,
      'customerId': t.customerId,
      'terminalId': t.terminalId,
      'terminalReceiptLine': t.terminalReceiptLine,
      'reference': t.reference,
    };

PosTender _tenderFromJson(Map<String, dynamic> j) => PosTender(
      method: j['method'] as String? ?? '',
      amount: (j['amount'] as num?)?.toDouble() ?? 0,
      cashGiven: (j['cashGiven'] as num?)?.toDouble() ?? 0,
      giftCardCode: j['giftCardCode'] as String?,
      customerId: j['customerId'] as String?,
      terminalId: j['terminalId'] as String?,
      terminalReceiptLine: j['terminalReceiptLine'] as String?,
      reference: j['reference'] as String?,
    );

/// A sale that stopped with a card payment sent to the machine and no answer
/// from it yet, or with a card taken and the sale not finished.
class HeldCardPayment {
  /// The sale's idempotency base: every key it sent is derived from it.
  final String base;

  /// Which sale this is ([PosCartNotifier.saleId], or the exchange it
  /// settles): only that sale carries the hold on. Null for a hold that cannot
  /// say, which no press carries on.
  final String? saleId;

  /// The business the sale was rung up under, from the till's token. Under any
  /// other the hold is never read, settled, let go or put back.
  final String? tenantId;

  /// A fingerprint of everything the sale sent: the order and every tender.
  /// Null when the sale could not be fingerprinted.
  final String? signature;

  /// A fingerprint of the order alone (and the exchange it settles). A press
  /// that sends another order is another sale. Null when it could not be
  /// fingerprinted: then no press carries the hold on, and every press settles
  /// it first.
  final String? orderSignature;

  /// A fingerprint of each tender as it was sent, in order.
  final List<String?> tenderPrints;

  /// How many of the sale's first tenders the server may have acted on under
  /// these keys. They cannot change; a tender after them can.
  final int fixed;

  /// For each place a card declined at, how many tries it has had: the next
  /// card there goes under [terminalKey] with this count.
  final Map<int, int> termTries;

  /// The places whose card a machine approved: final, never asked again.
  final Set<int> approved;

  /// The places whose tender the server recorded (a payment, or a gift card
  /// redeemed): never sent again.
  final Set<int> paid;

  /// For a place whose card the till heard from the machine, the attempt the
  /// machine has under its current key.
  final Map<int, String> attemptIds;

  /// Card places reopened once every card the sale sent was read as having
  /// taken nothing ([reopenedAfter]), each under a key never sent: a press of
  /// the same sale may change them, past [fixed] too.
  final Set<int> freed;

  /// The order the card was sent against; null when the order was never placed,
  /// and then no card can have reached a machine.
  final String? orderId;

  /// What to put back.
  final HeldSale sale;

  const HeldCardPayment({
    required this.base,
    required this.signature,
    required this.orderId,
    required this.sale,
    this.saleId,
    this.tenantId,
    this.orderSignature,
    this.tenderPrints = const [],
    this.fixed = 0,
    this.termTries = const {},
    this.approved = const {},
    this.paid = const {},
    this.attemptIds = const {},
    this.freed = const {},
  });

  HeldCardPayment _copy({
    int? fixed,
    Map<int, int>? termTries,
    Set<int>? approved,
    Set<int>? paid,
    Map<int, String>? attemptIds,
    Set<int>? freed,
  }) =>
      HeldCardPayment(
        base: base,
        signature: signature,
        orderId: orderId,
        sale: sale,
        saleId: saleId,
        tenantId: tenantId,
        orderSignature: orderSignature,
        tenderPrints: tenderPrints,
        fixed: fixed ?? this.fixed,
        termTries: termTries ?? this.termTries,
        approved: approved ?? this.approved,
        paid: paid ?? this.paid,
        attemptIds: attemptIds ?? this.attemptIds,
        freed: freed ?? this.freed,
      );

  /// This hold with the approvals [found] — place to attempt, read from what
  /// the server has on the order — recorded as taken: the press that carries
  /// it on records each without asking its machine again, naming the attempt.
  /// A card taken is a place the server acted on, so it lies before [fixed].
  HeldCardPayment withTaken(Map<int, String> found) => _copy(
        fixed: [fixed, for (final p in found.keys) p + 1].reduce(math.max),
        approved: {...approved, ...found.keys},
        attemptIds: {...attemptIds, ...found},
      );

  /// This hold with the tenders at [places] recorded on the order — an
  /// approval somebody else recorded, read back with its payment: the press
  /// that carries it on does not send them again.
  HeldCardPayment withRecorded(Set<int> places) =>
      _copy(paid: {...paid, ...places});

  /// The places whose tender the server recorded on the order, or may have:
  /// every one the server may have acted on ([fixed]) that is not a card sent
  /// to a card machine — a gift card redeemed, store credit, cash, a card
  /// taken without a machine, sent and maybe recorded with the answer lost —
  /// and every one it is known to have recorded ([paid]). An order never
  /// placed has none.
  ///
  /// They are that order's. The hold is never let go to a new order while
  /// any stands: the new order would take them again under keys of its own.
  Set<int> get mayBeRecorded {
    if (orderId == null || orderId!.isEmpty) return const {};
    final tenders = sale.tenders;
    return {
      ...paid,
      for (var i = 0; i < fixed; i++)
        if (i >= tenders.length || tenders[i].terminalId == null) i,
    };
  }

  /// This hold once its cards are known to have taken nothing but what the
  /// order keeps ([check] read with nothing at a machine, nothing undecided):
  /// [fixed] lowered to just after the last place the order keeps — a tender
  /// recorded or that may be ([mayBeRecorded]), a card taken and not put back
  /// in full — so a press of this same sale and order may change the cards
  /// after it, and carry the order on with what it recorded. Each card place
  /// reopened goes under a key of its own next ([termTries]): its own may
  /// stand for an attempt the machine finished, which it would only hand back.
  /// A card taken that was put back in full, never recorded, took nothing.
  HeldCardPayment reopenedAfter(HeldCardCheck check) {
    bool putBackInFull(int place) {
      final id = attemptIds[place];
      if (id == null || paid.contains(place)) return false;
      for (final a in check.attempts) {
        if (a.id != id) continue;
        return a.approved &&
            check.outstanding(a) == 0 &&
            !check.reversalUnsettled(a);
      }
      return false;
    }

    final putBack = {for (final p in approved) if (putBackInFull(p)) p};
    final keep = {...mayBeRecorded, ...approved.difference(putBack)};
    final to = keep.isEmpty ? 0 : keep.reduce(math.max) + 1;
    if (to >= fixed) return this;
    final tries = {...termTries};
    final ids = {...attemptIds};
    for (var i = to; i < fixed; i++) {
      tries[i] = (tries[i] ?? 0) + 1;
      ids.remove(i);
    }
    return _copy(
      fixed: to,
      termTries: Map.unmodifiable(tries),
      approved: approved.difference(putBack),
      attemptIds: Map.unmodifiable(ids),
      freed: {...freed, for (var i = to; i < fixed; i++) i},
    );
  }

  /// Whether the hold is [tenant]'s: made under that business. A hold that
  /// does not name one is no business's: under any business signed in, it is
  /// another's. (A hold is written only once an order exists, which takes a
  /// till signed in to a business, so every hold names one.)
  bool belongsTo(String? tenant) => tenantId == tenant;

  /// Whether a press of the sale [sale] sending [order] with tenders
  /// fingerprinted [prints] carries on this sale under its keys: the same
  /// sale — never another with the same content — the same order, and the
  /// tenders the server may have acted on exactly as they were.
  ///
  /// A hold that says a card was taken, or declined, at a place past what it
  /// fixed is one whose [fixed] was lowered below a card it sent; the keys
  /// there may already stand for an amount at the machine, so it never carries
  /// on: it is settled first. A card taken lies before [fixed]; a declined one
  /// frees its own place (its next try has a key of its own) and no later one.
  /// Places reopened after its cards were read as having taken nothing
  /// ([freed]) are free whatever their place: their keys were never sent.
  bool carriesOn(String? sale, String? order, List<String?> prints) {
    if (saleId == null || sale != saleId) return false;
    if (orderSignature == null || order != orderSignature) return false;
    if (approved.any((p) => p >= fixed)) return false;
    if (paid.any((p) => p >= fixed)) return false;
    if (termTries.keys.any((p) => p > fixed && !freed.contains(p))) {
      return false;
    }
    if (prints.length < fixed || tenderPrints.length < fixed) return false;
    for (var i = 0; i < fixed; i++) {
      if (prints[i] == null || prints[i] != tenderPrints[i]) return false;
    }
    return true;
  }

  Map<String, dynamic> toJson() => {
        'v': 2,
        'base': base,
        'saleId': saleId,
        'tenantId': tenantId,
        'signature': signature,
        'orderSignature': orderSignature,
        'tenderPrints': tenderPrints,
        'fixed': fixed,
        'termTries': {
          for (final e in termTries.entries) '${e.key}': e.value,
        },
        'approved': [...approved],
        'paid': [...paid],
        'attemptIds': {
          for (final e in attemptIds.entries) '${e.key}': e.value,
        },
        'freed': [...freed],
        'orderId': orderId,
        'sale': sale.toJson(),
      };

  factory HeldCardPayment.fromJson(Map<String, dynamic> j) {
    final tries = j['termTries'];
    final attempts = j['attemptIds'];
    return HeldCardPayment(
      base: j['base'] as String,
      saleId: j['saleId'] as String?,
      tenantId: j['tenantId'] as String?,
      signature: j['signature'] as String?,
      orderSignature: j['orderSignature'] as String?,
      tenderPrints: [
        for (final p in (j['tenderPrints'] as List?) ?? const []) p as String?,
      ],
      fixed: (j['fixed'] as num?)?.toInt() ?? 0,
      termTries: {
        if (tries is Map)
          for (final e in tries.entries)
            int.parse('${e.key}'): (e.value as num).toInt(),
      },
      approved: {
        for (final p in (j['approved'] as List?) ?? const []) (p as num).toInt(),
      },
      paid: {
        for (final p in (j['paid'] as List?) ?? const []) (p as num).toInt(),
      },
      attemptIds: {
        if (attempts is Map)
          for (final e in attempts.entries)
            int.parse('${e.key}'): e.value as String,
      },
      freed: {
        for (final p in (j['freed'] as List?) ?? const []) (p as num).toInt(),
      },
      orderId: j['orderId'] as String?,
      sale: HeldSale.fromJson(Map<String, dynamic>.from(j['sale'] as Map)),
    );
  }
}

/// The till's held card payment, kept on the device so it outlives the screen
/// and the app.
///
/// [ready] completes once what was on the device has been read: a press reads
/// the hold only after it, so a till that has just started cannot miss a card
/// left at the machine before it was closed. Every change is written through,
/// in order; [hold] completes once it is written, true when the device took it,
/// so the amount goes to the machine only after its hold is on the device. Only
/// one press acts on it at a time ([claimPress]).
///
/// A copy on the device that cannot be read is set aside and [corrupt] says so
/// until a manager clears it ([clearCorrupt]): it may stand for a card at the
/// machine that this till can no longer settle, so the till sends no card to a
/// machine meanwhile. payment-svc's own guard is the backstop once it is cleared.
class HeldCardPaymentNotifier extends StateNotifier<HeldCardPayment?> {
  HeldCardPaymentNotifier({AppStorage storage = const AppStorage()})
      : _storage = storage,
        super(null) {
    ready = _restore();
  }

  final AppStorage _storage;

  /// Completes when the device's copy has been read.
  late final Future<void> ready;

  /// Whether a held card payment on this device could not be read and has not
  /// been cleared: the card machine is not used from this till until it is.
  final ValueNotifier<bool> corrupt = ValueNotifier(false);

  @override
  void dispose() {
    corrupt.dispose();
    super.dispose();
  }

  /// Clears an unreadable hold, once a manager has looked at the card machine:
  /// the copy set aside goes and the till may use the card machine again, with
  /// payment-svc refusing a card on a machine an earlier payment still holds.
  Future<void> clearCorrupt() async {
    await ready;
    try {
      await _storage.delete(key: heldCardPaymentCorruptStorageKey);
    } catch (e) {
      debugPrint('The unreadable held card payment could not be removed: $e');
    }
    // The unreadable copy itself goes too, unless a hold has been written over
    // it since: read again at the next start, it would be set aside again.
    if (mounted && state == null) await _persist();
    if (mounted) corrupt.value = false;
  }

  /// The hold as it stands now.
  HeldCardPayment? get current => state;

  /// The orders this till let go of unfinished, oldest first
  /// ([releaseUnfinished]).
  final List<String> _letGo = [];

  /// How many orders let go are remembered; the oldest are forgotten first. A
  /// card machine's late answer comes within its own time to answer, not
  /// weeks on.
  static const letGoRemembered = 200;

  /// Whether this till let go of [orderId] with its sale unfinished: a card
  /// payment sent for it, nothing paid, and the till gone on to another sale.
  /// A card machine's approval that turns up on such an order is never
  /// recorded on it from here: its goods did not leave on that card.
  bool wasLetGo(String? orderId) => orderId != null && _letGo.contains(orderId);

  /// Lets the hold go with its sale unfinished — its cards read as having
  /// taken nothing, nothing recorded on its order [orderId] — and remembers
  /// that order on the device first. A machine's answer can still arrive after a person
  /// has said what it shows (payment-svc keeps an approval that follows a
  /// manager's "nothing taken"), and to anything that reads the order then it
  /// looks like a sale still owed its card.
  Future<void> releaseUnfinished(String? orderId) async {
    await ready;
    if (orderId != null && orderId.isNotEmpty && !_letGo.contains(orderId)) {
      _letGo.add(orderId);
      if (_letGo.length > letGoRemembered) {
        _letGo.removeRange(0, _letGo.length - letGoRemembered);
      }
      final snapshot = jsonEncode(_letGo);
      final written = _writes.then((_) async {
        try {
          await _storage.write(
              key: heldCardPaymentLetGoStorageKey, value: snapshot);
        } catch (e) {
          // Still remembered on this till for as long as the app runs.
          debugPrint('The orders let go could not be kept on the device: $e');
        }
      });
      _writes = written;
      await written;
    }
    await release();
  }

  Future<void> _writes = Future<void>.value();

  /// The press acting on the hold now, if any: done when it has stopped.
  Completer<void>? _press;

  /// Claims the till for one press of Complete Sale, after any press before it
  /// has stopped; returns what ends the claim, or null when the press before
  /// is still going after [patience].
  ///
  /// A press works on whatever hold is current: it settles it, lets it go,
  /// writes its own. A press whose screen has gone keeps running until the
  /// request it has in flight answers — the card's own post can take the
  /// receive timeout — and then it lets go of a hold, or writes one, as its
  /// answer says. Run beside a newer press, that would let go of the newer
  /// sale's hold while its card is at the machine, or bring back the hold of a
  /// sale the newer press has finished. So one press at a time: the next one
  /// starts only when the last has stopped, and finds what it left. One that
  /// waited too long starts nothing at all.
  Future<void Function()?> claimPress(
      {Duration patience = const Duration(seconds: 30)}) async {
    while (true) {
      final before = _press;
      if (before == null) break;
      try {
        await before.future.timeout(patience);
      } on TimeoutException {
        return null;
      }
    }
    final mine = Completer<void>();
    _press = mine;
    return () {
      if (mine.isCompleted) return;
      if (identical(_press, mine)) _press = null;
      mine.complete();
    };
  }

  Future<void> _restore() async {
    // The orders let go before the app last closed. Read by itself: a list
    // that cannot be read says nothing about a card at a machine, so it never
    // puts the card machine out of use.
    try {
      final gone = await _storage.read(key: heldCardPaymentLetGoStorageKey);
      if (gone != null && gone.isNotEmpty) {
        for (final id in jsonDecode(gone) as List) {
          if (id is String && !_letGo.contains(id)) _letGo.add(id);
        }
      }
    } catch (e) {
      debugPrint('The orders let go on this device could not be read: $e');
    }
    String? raw;
    try {
      // One set aside before, and not cleared since, still stands.
      final aside = await _storage.read(key: heldCardPaymentCorruptStorageKey);
      if (aside != null && aside.isNotEmpty && mounted) corrupt.value = true;
      raw = await _storage.read(key: heldCardPaymentStorageKey);
      // A hold written while the device was being read is the newer one.
      if (raw == null || raw.isEmpty || !mounted || state != null) return;
      state = HeldCardPayment.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (e) {
      // A hold that cannot be read cannot be settled: it is set aside, never
      // overwritten by the next one, and the till still opens — without the
      // card machine until a manager clears it. A device that cannot be read
      // at all may hold one too.
      debugPrint('The held card payment on this device could not be read: $e');
      if (mounted) corrupt.value = true;
      try {
        if (raw != null && raw.isNotEmpty) {
          await _storage.write(
              key: heldCardPaymentCorruptStorageKey, value: raw);
        }
      } catch (_) {
        // Nothing further to try.
      }
    }
  }

  /// Holds [held], and completes once it is on the device: true when the
  /// device took it. False leaves it held on this till for as long as the app
  /// runs, and then no amount goes to a card machine on its strength.
  Future<bool> hold(HeldCardPayment held) {
    state = held;
    return _persist();
  }

  /// Lets the hold go: the sale completed, or every card it sent is known to
  /// have taken nothing.
  Future<void> release() {
    state = null;
    return _persist();
  }

  Future<bool> _persist() {
    final snapshot = state;
    final written = _writes.then((_) async {
      try {
        if (snapshot == null) {
          await _storage.delete(key: heldCardPaymentStorageKey);
        } else {
          await _storage.write(
              key: heldCardPaymentStorageKey,
              value: jsonEncode(snapshot.toJson()));
        }
        return true;
      } catch (e) {
        // The hold still stands on this till for as long as the app runs.
        debugPrint('The held card payment could not be kept on the device: $e');
        return false;
      }
    });
    _writes = written;
    return written;
  }
}

/// The till's held card payment, if any. Not tied to the tender screen, so
/// leaving it (Back to Sale) does not forget a payment still at the machine,
/// and kept on the device, so closing the app does not either.
final heldCardPaymentProvider =
    StateNotifierProvider<HeldCardPaymentNotifier, HeldCardPayment?>(
        (ref) => HeldCardPaymentNotifier());

/// What a held card payment has come to.
enum HeldCardState {
  /// Nothing was taken and nothing is left at the machine: the till is free.
  settled,

  /// The amount is still at the machine and it has not said.
  atMachine,

  /// The machine approved: the customer has paid for the sale as it was.
  taken,

  /// The machine timed out: the card may have been charged.
  mayBeTaken,

  /// The till could not read what the machine said.
  unknown,
}

/// The held sale's card attempts as the server has them, and what they come to.
class HeldCardCheck {
  final HeldCardState state;

  /// The sale attempts against the held order.
  final List<TerminalOutcome> attempts;

  /// The refunds against the held order: money put back on a card it took.
  final List<TerminalOutcome> refunds;

  const HeldCardCheck(this.state, this.attempts, {this.refunds = const []});

  /// Still at the machine wins over everything: whatever else happened, a
  /// payment the machine may yet approve must not be started again. Then a card
  /// taken and not put back, then one that may have been.
  factory HeldCardCheck.of(List<TerminalOutcome> attempts) {
    final sales = [
      for (final a in attempts)
        if (a.kind == null || a.kind == 'SALE') a,
    ];
    final refunds = [
      for (final a in attempts)
        if (a.kind == 'REFUND') a,
    ];
    final check = HeldCardCheck(HeldCardState.settled, sales, refunds: refunds);
    final state = sales.any((a) => a.pending)
        ? HeldCardState.atMachine
        : check.taken.isNotEmpty
            ? HeldCardState.taken
            : sales.any((a) => a.uncertain)
                ? HeldCardState.mayBeTaken
                : HeldCardState.settled;
    return HeldCardCheck(state, sales, refunds: refunds);
  }

  static const unreadable = HeldCardCheck(HeldCardState.unknown, []);

  List<TerminalOutcome> get atMachine => [
        for (final a in attempts)
          if (a.pending) a,
      ];

  /// The cards approved and not put back in full.
  List<TerminalOutcome> get taken => [
        for (final a in attempts)
          if (a.approved && outstanding(a) > 0) a,
      ];

  List<TerminalOutcome> get mayBeTaken => [
        for (final a in attempts)
          if (a.uncertain) a,
      ];

  /// The refunds against [sale].
  List<TerminalOutcome> refundsOf(TerminalOutcome sale) => [
        for (final r in refunds)
          if (r.refundOf == sale.id) r,
      ];

  /// What [sale] took that has not been put back, in its currency's minor
  /// units: whole numbers, so a refund of exactly what was taken leaves none.
  int outstanding(TerminalOutcome sale) {
    if (!sale.approved) return 0;
    final taken = _minor(sale.amount, sale.currency);
    // An approval with no amount cannot be shown to have been put back.
    if (taken == null) return 1;
    var back = 0;
    for (final r in refundsOf(sale)) {
      if (r.approved) back += _minor(r.amount, sale.currency) ?? 0;
    }
    return math.max(0, taken - back);
  }

  /// A refund of [sale] that is still on the machine, or that may or may not
  /// have gone through: until it is known, nothing more is put back.
  bool reversalUnsettled(TerminalOutcome sale) =>
      refundsOf(sale).any((r) => r.pending || r.uncertain);

  static int? _minor(String? amount, String? currency) {
    final value = double.tryParse(amount ?? '');
    if (value == null) return null;
    return (value * math.pow(10, AppFormat.minorUnits(currency))).round();
  }
}

/// Reads what the machine has said about [held]'s card payments. Only reads:
/// it starts nothing. An order never placed sent no card anywhere, so it is
/// settled without asking; a read that fails is [HeldCardState.unknown], never
/// "nothing there".
Future<HeldCardCheck> checkHeldCardPayment(Dio dio, HeldCardPayment held) =>
    checkOrderCardPayments(dio, held.orderId);

/// What every card attempt against [orderId] comes to, as
/// [checkHeldCardPayment] reads it.
Future<HeldCardCheck> checkOrderCardPayments(Dio dio, String? orderId) async {
  if (orderId == null || orderId.isEmpty) {
    return const HeldCardCheck(HeldCardState.settled, []);
  }
  try {
    return HeldCardCheck.of(await readTerminalAttemptsOfOrder(dio, orderId));
  } catch (_) {
    return HeldCardCheck.unreadable;
  }
}

/// Asks the machine to cancel every attempt of [check] still at it, then reads
/// each until the machine has answered: a cancel settles nothing by itself, and
/// one that loses the race to an approval comes back approved — the card taken.
/// One the machine has not answered when [wait]'s time is up, on [now], is
/// still at the machine. A cancel that cannot be sent leaves the payment
/// [HeldCardState.unknown].
Future<HeldCardCheck> cancelHeldAtMachine(
  Dio dio,
  HeldCardCheck check, {
  TerminalWait wait = const TerminalWait(),
  TerminalClock? now,
}) async {
  final clock = now ?? DateTime.now;
  final deadline = clock().add(wait.limit);
  final after = <String, TerminalOutcome>{};
  try {
    for (final a in check.atMachine) {
      var answer = await cancelTerminalAttempt(dio, a.id);
      if (answer.id.isEmpty) answer = a;
      after[a.id] = answer.pending
          ? await awaitTerminalOutcome(dio, answer,
              wait: wait, now: clock, until: deadline)
          : answer;
    }
  } catch (_) {
    return HeldCardCheck.unreadable;
  }
  return HeldCardCheck.of([
    for (final a in check.attempts) after[a.id] ?? a,
    ...check.refunds,
  ]);
}

/// Which place of [held] each approval of [check] is: the place the till heard
/// it at ([HeldCardPayment.attemptIds]), else the one place it sent a card from
/// and never heard back — on that attempt's machine, for exactly its amount —
/// in the order the places were sent. Only approvals not yet recorded in the
/// hold as taken; an approval no place can be shown to have sent is left out.
///
/// A press stops at the first card it hears nothing from, so at most one place
/// sent a card the till never heard: a place's earlier tries were each heard
/// (declined) before the next was sent.
Map<int, String> placesOfTaken(HeldCardPayment held, HeldCardCheck check) {
  final found = <int, String>{};
  final heardAt = {for (final e in held.attemptIds.entries) e.value: e.key};
  for (final a in check.taken) {
    final heard = heardAt[a.id];
    if (heard != null) {
      if (!held.approved.contains(heard)) found[heard] = a.id;
      continue;
    }
    final tenders = held.sale.tenders;
    for (var i = 0; i < held.fixed && i < tenders.length; i++) {
      final t = tenders[i];
      if (t.terminalId == null ||
          held.approved.contains(i) ||
          held.paid.contains(i) ||
          held.attemptIds.containsKey(i) ||
          found.containsKey(i)) {
        continue;
      }
      if (a.terminalId != null && a.terminalId != t.terminalId) continue;
      final currency = a.currency ?? '';
      if (HeldCardCheck._minor(a.amount, currency) !=
          HeldCardCheck._minor('${t.amount}', currency)) {
        continue;
      }
      found[i] = a.id;
      break;
    }
  }
  return found;
}

/// The places of [held] whose card a machine approved and [check] reads as
/// recorded on the order (the attempt names its payment) — by another till
/// finishing it, its answer lost to this one — not yet recorded in the hold.
Set<int> placesRecorded(HeldCardPayment held, HeldCardCheck check) => {
      for (final e in held.attemptIds.entries)
        if (!held.paid.contains(e.key) &&
            check.attempts.any((a) => a.id == e.value && a.paymentId != null))
          e.key,
    };

/// Which place of [held] — this till's hold on the order [approval] is on —
/// took the card [approval] is: the place it was heard at, else the first card
/// on its machine for exactly its amount that is neither taken nor recorded
/// and stands for no other attempt. Past what the hold fixed too: a late
/// approval after a manager's "nothing taken" lands on a place that was
/// reopened. Null when no place of the sale is that card: then it is not this
/// sale's to finish as it stands.
int? placeOfApproval(HeldCardPayment held, TerminalOutcome approval) {
  for (final e in held.attemptIds.entries) {
    if (e.value != approval.id) continue;
    return held.approved.contains(e.key) || held.paid.contains(e.key)
        ? null
        : e.key;
  }
  final tenders = held.sale.tenders;
  final currency = approval.currency ?? '';
  final want = HeldCardCheck._minor(approval.amount, currency);
  if (want == null) return null;
  for (var i = 0; i < tenders.length; i++) {
    final t = tenders[i];
    if (t.terminalId == null ||
        held.approved.contains(i) ||
        held.paid.contains(i) ||
        held.attemptIds.containsKey(i)) {
      continue;
    }
    if (approval.terminalId != null && approval.terminalId != t.terminalId) {
      continue;
    }
    if (HeldCardCheck._minor('${t.amount}', currency) != want) continue;
    return i;
  }
  return null;
}

/// The sale attempt among [attempts] (an order's, oldest first) that place
/// [place] of a press sent and never heard back about: the newest on
/// [terminalId] for exactly [amount] that no other place has. Null when there
/// is none to read.
TerminalOutcome? attemptOfPlace(
  List<TerminalOutcome> attempts, {
  required int place,
  required String terminalId,
  required double amount,
  required String currency,
  required Map<int, String> attemptIds,
}) {
  final others = {
    for (final e in attemptIds.entries)
      if (e.key != place) e.value,
  };
  final want = HeldCardCheck._minor('$amount', currency);
  for (final a in attempts.reversed) {
    if (a.kind != null && a.kind != 'SALE') continue;
    if (others.contains(a.id)) continue;
    if (a.terminalId != null && a.terminalId != terminalId) continue;
    if (HeldCardCheck._minor(a.amount, a.currency ?? currency) != want) continue;
    return a;
  }
  return null;
}

/// Whether any tender has been recorded against [orderId] (payment-svc's
/// `GET /payments/by-order/{orderId}`); null when it cannot be read.
///
/// A card put back on the machine leaves a recorded tender standing for money
/// the business no longer has, so a card is only put back on an order with
/// nothing recorded: one with a tender recorded is finished, then returned.
Future<bool?> tendersRecordedOn(Dio dio, String orderId) async {
  try {
    final resp =
        await dio.get('/${ApiConstants.payment}/payments/by-order/$orderId');
    final rows = (resp.data['data'] as List?) ?? const [];
    return rows.isNotEmpty;
  } catch (_) {
    return null;
  }
}

/// Puts back on the card, through payment-svc's linked refund on the machine
/// that took it, everything [check]'s taken cards took and have not had back,
/// with the manager's [reason] (payment-svc keeps it on each refund, with who
/// asked and when), then reads what the order's card attempts come to. Each
/// refund is under a key derived from the held sale and the attempt, so a press
/// repeated finds the refund already made rather than making a second; a try
/// after a refund the machine declined has a key of its own.
///
/// One wait for the lot: each refund is sent, and waited for while it is still
/// at the machine, within what is left of [wait]'s time on [now], counted from
/// the first. When the time is up no further refund is sent.
///
/// A refusal from the server (not a manager, a retired machine, more than was
/// taken) is thrown as it came. A refund that cannot be sent leaves the payment
/// [HeldCardState.unknown].
Future<HeldCardCheck> reverseHeldOnMachine(
  Dio dio,
  HeldCardPayment held,
  HeldCardCheck check, {
  required String reason,
  TerminalWait wait = const TerminalWait(),
  TerminalClock? now,
}) =>
    reverseCardsOnMachine(dio,
        keyBase: held.base,
        orderId: held.orderId,
        check: check,
        reason: reason,
        wait: wait,
        now: now);

/// [reverseHeldOnMachine] for any order's cards: what [check]'s taken cards
/// still hold — only [only]'s when it names one — goes back on them under keys
/// derived from [keyBase], then [orderId]'s card attempts are read again.
Future<HeldCardCheck> reverseCardsOnMachine(
  Dio dio, {
  required String keyBase,
  required String? orderId,
  required HeldCardCheck check,
  required String reason,
  String? only,
  TerminalWait wait = const TerminalWait(),
  TerminalClock? now,
}) async {
  final clock = now ?? DateTime.now;
  final deadline = clock().add(wait.limit);
  Duration left() => deadline.difference(clock());
  for (final a in check.taken) {
    if (only != null && a.id != only) continue;
    if (check.reversalUnsettled(a)) continue;
    final owed = check.outstanding(a);
    if (owed <= 0) continue;
    if (left() <= Duration.zero) break;
    final currency = a.currency ?? '';
    final amount = owed / math.pow(10, AppFormat.minorUnits(currency));
    final tries =
        check.refundsOf(a).where((r) => !r.approved && !r.pending).length;
    try {
      final refund = await refundTerminalAttempt(
        dio,
        a.id,
        amount: amount,
        currency: currency,
        reason: reason,
        idempotencyKey: derivedId(
            keyBase, tries == 0 ? 'reverse:${a.id}' : 'reverse:${a.id}:$tries'),
        within: left(),
      );
      if (refund.pending) {
        await awaitTerminalOutcome(dio, refund,
            wait: wait, now: clock, until: deadline);
      }
    } on DioException catch (e) {
      if (e.response != null) rethrow;
      return HeldCardCheck.unreadable;
    }
  }
  return checkOrderCardPayments(dio, orderId);
}
