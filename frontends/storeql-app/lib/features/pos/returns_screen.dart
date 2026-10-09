import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/ids.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/util/status_labels.dart';
import '../../shared/widgets/barcode_scanner_sheet.dart';
import '../admin/customer_providers.dart';
import '../admin/providers/admin_providers.dart';
import '../admin/recall_providers.dart';
import '../admin/recall_return_choice.dart';
import 'cash_providers.dart';
import 'pos_providers.dart';
import 'pos_recall_check.dart';

// ---------------------------------------------------------------------------
// The till's Returns screen (return-controls, slice 2): find a sale by its
// receipt number, take items back with the condition of each, and either
// refund them or exchange them for something else. A manager can also take
// items back with no receipt at all, to store credit or a gift card.
//
// The server decides everything that matters (the window, the cashier limit,
// what a line may still return, the current price of a no-receipt item, the
// difference of an exchange). The screen collects the person's choices, sends
// them once under an Idempotency-Key, and shows every refusal in words.
// ---------------------------------------------------------------------------

/// One line of a found sale: what was sold, what has already come back and
/// what may still be returned.
class ReceiptLine {
  final String variantId;
  final double soldQty;
  final double returnedQty;
  final double returnableQty;
  final double unitPrice;

  const ReceiptLine({
    required this.variantId,
    required this.soldQty,
    required this.returnedQty,
    required this.returnableQty,
    required this.unitPrice,
  });

  factory ReceiptLine.fromJson(Map<String, dynamic> j) => ReceiptLine(
        variantId: j['variantId'] as String? ?? '',
        soldQty: (j['soldQty'] as num?)?.toDouble() ?? 0,
        returnedQty: (j['returnedQty'] as num?)?.toDouble() ?? 0,
        returnableQty: (j['returnableQty'] as num?)?.toDouble() ?? 0,
        unitPrice: (j['unitPrice'] as num?)?.toDouble() ?? 0,
      );
}

/// A sale found by `GET /orders/by-receipt`.
class ReceiptLookup {
  final String orderId;
  final String currency;
  final String? customerId;
  final String receiptNumber;
  final List<ReceiptLine> lines;

  const ReceiptLookup({
    required this.orderId,
    required this.currency,
    required this.customerId,
    required this.receiptNumber,
    required this.lines,
  });

  factory ReceiptLookup.fromJson(Map<String, dynamic> j) {
    final order = (j['order'] as Map?)?.cast<String, dynamic>() ?? const {};
    final customer = order['customerId'] as String?;
    return ReceiptLookup(
      orderId: order['id'] as String? ?? '',
      currency: order['currency'] as String? ?? '',
      customerId: customer == null || customer.isEmpty ? null : customer,
      receiptNumber: j['receiptNumber'] as String? ?? '',
      lines: [
        for (final l in (j['lines'] as List?) ?? const [])
          ReceiptLine.fromJson((l as Map).cast<String, dynamic>()),
      ],
    );
  }
}

/// The reason codes a refused return carries (`details` of the problem, else
/// the envelope's own). `reason=CEILING` keeps only `CEILING`.
List<String> _refusalReasons(Object e) {
  final raw = <String>[];
  if (e is DioException) {
    final data = e.response?.data;
    if (data is Map && data['details'] is List) {
      raw.addAll([for (final d in data['details'] as List) d.toString()]);
    }
  }
  if (raw.isEmpty) raw.addAll(apiErrorOf(e)?.details ?? const []);
  return [
    for (final d in raw) d.contains('=') ? d.substring(d.indexOf('=') + 1) : d,
  ];
}

/// A quantity as the server wants it: a whole number when it is one.
num _qtyValue(double q) => q == q.roundToDouble() ? q.toInt() : q;

/// What a submitted return or exchange ended as, for the confirmation card.
class _Done {
  final String title;
  final List<String> lines;
  final Map<String, dynamic>? giftCard;
  const _Done(this.title, this.lines, {this.giftCard});
}

enum _Mode { sale, noReceipt }

class PosReturnsScreen extends ConsumerStatefulWidget {
  const PosReturnsScreen({super.key});

  @override
  ConsumerState<PosReturnsScreen> createState() => _PosReturnsScreenState();
}

class _PosReturnsScreenState extends ConsumerState<PosReturnsScreen> {
  _Mode _mode = _Mode.sale;

  // Finding the sale.
  final _numberCtrl = TextEditingController();
  bool _looking = false;
  String? _lookupError;
  ReceiptLookup? _sale;

  // What comes back.
  final Map<String, double> _qty = {};
  final Map<String, String> _condition = {};

  /// The recall notice a refund settles, or null for an ordinary return.
  RecallNotice? _recall;
  bool _isRecalled(String variantId) =>
      _recall?.lines.any((l) => l.variantId == variantId) ?? false;
  final Map<String, PosLine> _noReceiptLines = {}; // no-receipt items, by variant
  final _reasonCtrl = TextEditingController();

  // Where the money goes.
  String _method = 'ORIGINAL';
  bool _topUp = false;
  final _giftCodeCtrl = TextEditingController();
  Customer? _creditCustomer; // no-receipt store credit
  final _contactCtrl = TextEditingController();

  // Exchange.
  bool _exchange = false;
  final List<PosLine> _newItems = [];
  final _scanCtrl = TextEditingController();
  final _scanFocus = FocusNode();
  bool _scanning = false;

  // Submitting.
  bool _submitting = false;
  String? _error;
  List<String>? _needsManager;
  _Done? _done;

  /// The key of the attempt in hand and what it was for: a retry of the same
  /// submit sends the same key, anything changed gets a new one.
  String? _key;
  String? _keyFor;

  @override
  void dispose() {
    _numberCtrl.dispose();
    _reasonCtrl.dispose();
    _giftCodeCtrl.dispose();
    _contactCtrl.dispose();
    _scanCtrl.dispose();
    _scanFocus.dispose();
    super.dispose();
  }

  bool get _canNoReceipt {
    final auth = ref.watch(authNotifierProvider).value;
    return auth is AuthAuthenticated &&
        auth.isManager &&
        auth.hasPermission('sales.refund');
  }

  void _reset() {
    setState(() {
      _mode = _Mode.sale;
      _numberCtrl.clear();
      _looking = false;
      _lookupError = null;
      _sale = null;
      _qty.clear();
      _condition.clear();
      _recall = null;
      _noReceiptLines.clear();
      _reasonCtrl.clear();
      _method = 'ORIGINAL';
      _topUp = false;
      _giftCodeCtrl.clear();
      _creditCustomer = null;
      _contactCtrl.clear();
      _exchange = false;
      _newItems.clear();
      _scanCtrl.clear();
      _submitting = false;
      _error = null;
      _needsManager = null;
      _done = null;
      _key = null;
      _keyFor = null;
    });
  }

  // ── finding the sale ──────────────────────────────────────────────────────

  Future<void> _find(String raw) async {
    final number = raw.trim();
    if (number.isEmpty || _looking) return;
    setState(() {
      _looking = true;
      _lookupError = null;
      _sale = null;
      _recall = null;
      _qty.clear();
      _condition.clear();
      _error = null;
      _needsManager = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.order}/orders/by-receipt',
        queryParameters: {'number': number},
      );
      final found =
          ReceiptLookup.fromJson((resp.data['data'] as Map).cast<String, dynamic>());
      if (!mounted) return;
      setState(() {
        _sale = found;
        _looking = false;
        // Store credit needs a named customer; otherwise back to the tender.
        if (_method == 'STORE_CREDIT' && found.customerId == null) {
          _method = 'ORIGINAL';
        }
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _looking = false;
        _lookupError = returnRefusalLabel(apiErrorCode(e)) ??
            friendlyError(e, fallback: 'Could not look that sale up.');
      });
    }
  }

  Future<void> _scanReceipt() async {
    final code = await scanBarcodeWithCamera(context);
    if (code != null && code.isNotEmpty) {
      _numberCtrl.text = code;
      await _find(code);
    }
  }

  // ── adding items (new basket, or no-receipt items) ────────────────────────

  Future<void> _scanItem(String raw) async {
    final code = raw.trim();
    if (code.isEmpty || _scanning) return;
    setState(() {
      _scanning = true;
      _error = null;
    });
    try {
      final line = await scanBarcode(ref, code);
      if (!mounted) return;
      setState(() {
        if (_mode == _Mode.noReceipt) {
          _noReceiptLines.putIfAbsent(line.variantId, () => line);
          _qty[line.variantId] = (_qty[line.variantId] ?? 0) + 1;
        } else {
          final i = _newItems.indexWhere((l) => samePackLine(l, line));
          if (i >= 0) {
            _newItems[i] = _newItems[i].copyWith(qty: _newItems[i].qty + 1);
          } else {
            _newItems.add(line);
          }
        }
        _scanCtrl.clear();
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = apiErrorCode(e) == 'PRODUCT_NOT_FOUND' ||
                (e is DioException && e.response?.statusCode == 404)
            ? 'No product found for that barcode.'
            : friendlyError(e, fallback: 'Scan failed. Please try again.');
      });
    } finally {
      if (mounted) setState(() => _scanning = false);
      _scanFocus.requestFocus();
    }
  }

  Future<void> _scanItemWithCamera() async {
    final code = await scanBarcodeWithCamera(context);
    if (code != null && code.isNotEmpty) await _scanItem(code);
  }

  /// Picking from the catalogue, the same list the Sale screen browses.
  Future<void> _browse() async {
    final product = await showDialog<ProductInfo>(
      context: context,
      builder: (_) => const _ProductPickDialog(),
    );
    if (product == null || !mounted) return;
    try {
      final line = await lineForProduct(ref, product);
      if (!mounted) return;
      setState(() {
        if (_mode == _Mode.noReceipt) {
          _noReceiptLines.putIfAbsent(line.variantId, () => line);
          _qty[line.variantId] = (_qty[line.variantId] ?? 0) + 1;
        } else {
          final i = _newItems.indexWhere((l) => samePackLine(l, line));
          if (i >= 0) {
            _newItems[i] = _newItems[i].copyWith(qty: _newItems[i].qty + 1);
          } else {
            _newItems.add(line);
          }
        }
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _error =
          friendlyError(e, fallback: 'That product cannot be added.'));
    }
  }

  Future<void> _pickCustomer() async {
    final picked = await showDialog<Customer>(
      context: context,
      builder: (_) => const _CustomerPickDialog(),
    );
    if (picked != null && mounted) setState(() => _creditCustomer = picked);
  }

  // ── sending ───────────────────────────────────────────────────────────────

  List<Map<String, dynamic>>? _chosenItems() {
    final chosen = [
      for (final e in _qty.entries)
        if (e.value > 0) e,
    ];
    if (chosen.isEmpty) {
      setState(() {
        _needsManager = null;
        _error = 'Choose at least one item to take back.';
      });
      return null;
    }
    // A recalled line has no condition: the server sends the goods to RECALLED.
    final recall = _exchange ? null : _recall;
    bool recalled(String v) => recall != null && _isRecalled(v);
    if (chosen.any((e) => _condition[e.key] == null && !recalled(e.key))) {
      setState(() {
        _needsManager = null;
        _error = 'Say what condition each returned item is in.';
      });
      return null;
    }
    if (recall != null && !chosen.any((e) => recalled(e.key))) {
      setState(() {
        _needsManager = null;
        _error = 'Choose the recalled item to take back, or turn off "This is a recall return".';
      });
      return null;
    }
    return [
      for (final e in chosen)
        {
          'variantId': e.key,
          'qty': _qtyValue(e.value),
          if (!recalled(e.key)) 'condition': _condition[e.key],
        },
    ];
  }

  /// A new key for a changed submit, the same key for a retry of the same one.
  String _keyForAttempt(String signature) {
    if (_key == null || _keyFor != signature) {
      _key = newId();
      _keyFor = signature;
    }
    return _key!;
  }

  String get _reason =>
      _reasonCtrl.text.trim().isEmpty ? 'Customer return' : _reasonCtrl.text.trim();

  String _itemsSignature(List<Map<String, dynamic>> items) => [
        for (final i in items) '${i['variantId']}:${i['qty']}:${i['condition']}',
      ].join(',');

  Future<void> _submit() async {
    if (_submitting) return;
    if (_mode == _Mode.noReceipt) return _submitNoReceipt();
    final sale = _sale;
    if (sale == null) return;
    final items = _chosenItems();
    if (items == null) return;
    if (_exchange) return _submitExchange(sale, items);

    final giftCode = _giftCodeCtrl.text.trim();
    final topUp = _method == 'GIFT_CARD' && _topUp;
    if (topUp && giftCode.isEmpty) {
      setState(() {
        _needsManager = null;
        _error = 'Enter the code of the gift card to top up.';
      });
      return;
    }
    // The drawer the cash goes back out of is settled first: a read of the open
    // till still in flight, or failed a moment ago, is given a few seconds
    // ([SaleTillNotifier.settle]) rather than the refund going out naming none.
    // Never refused over it: with no drawer the refund is "not at a till".
    final tillCtl = ref.read(saleTillProvider.notifier);
    if (!tillCtl.settled) {
      setState(() => _submitting = true);
      await tillCtl.settle();
      if (!mounted) return;
      setState(() => _submitting = false);
    }
    final body = <String, dynamic>{
      'reason': _reason,
      'refundMethod': _method,
      if (topUp) 'giftCardCode': giftCode,
      if (_recall != null) 'recallNoticeId': _recall!.id,
      // The drawer the cash goes back out of, so its report counts it.
      'tillSessionId': ?tillCtl.drawer,
      'items': items,
    };
    final key = _keyForAttempt(
        'refund|${sale.orderId}|${_recall?.id ?? ''}|$_reason|$_method|${topUp ? giftCode : ''}|${_itemsSignature(items)}');
    await _send(
      path: '/${ApiConstants.order}/orders/${sale.orderId}/returns',
      body: body,
      key: key,
      onDone: (data) {
        final refund = (data['refundAmount'] as num?)?.toDouble() ?? _previewRefund(sale);
        final card = (data['giftCard'] as Map?)?.cast<String, dynamic>();
        return _Done('Return recorded', [
          '${AppFormat.money(refund, currencyCode: sale.currency)} '
              '${_method == 'ORIGINAL' ? 'goes back to how the customer paid' : _method == 'STORE_CREDIT' ? 'to store credit' : 'to a gift card'}',
        ], giftCard: card);
      },
    );
  }

  Future<void> _submitExchange(
      ReceiptLookup sale, List<Map<String, dynamic>> items) async {
    if (_newItems.isEmpty) {
      setState(() {
        _needsManager = null;
        _error = 'Choose what the customer takes instead.';
      });
      return;
    }
    // The recall check a sale runs, over the new basket, before anything is sent.
    if (!await _newBasketPassesRecallCheck()) return;
    // The drawer the cash back goes out of, settled as a refund's is (see
    // [_submit]): a read of the open till still in flight, or failed a moment
    // ago, is given a few seconds. order-svc never refuses an exchange over the
    // drawer - payment-svc counts the cash there only while it is this
    // business's open session at the sale's store, else "not at a till" - so
    // there is no second try without it.
    final tillCtl = ref.read(saleTillProvider.notifier);
    if (!tillCtl.settled) {
      setState(() => _submitting = true);
      await tillCtl.settle();
      if (!mounted) return;
      setState(() => _submitting = false);
    }
    final newItems = [
      for (final l in _newItems)
        {'variantId': l.variantId, 'qty': _qtyValue(l.qty), ...packFieldsOf(l)},
    ];
    final body = <String, dynamic>{
      'reason': _reason,
      'returnItems': items,
      'newItems': newItems,
      if (sale.customerId != null) 'customerId': sale.customerId,
      // The drawer the cash back goes out of, so its report counts it.
      'tillSessionId': ?tillCtl.drawer,
    };
    final key = _keyForAttempt('exchange|${sale.orderId}|$_reason|'
        '${_itemsSignature(items)}|'
        '${[
      for (final n in newItems)
        '${n['variantId']}:${n['qty']}:${n['batchNo']}:${n['expiry']}:'
            '${n['markdownId']}:${n['weighingInstrumentId']}'
    ].join(',')}');
    await _send(
      path: '/${ApiConstants.order}/orders/${sale.orderId}/exchange',
      body: body,
      key: key,
      onDone: (data) {
        final due = (data['dueFromCustomer'] as num?)?.toDouble() ?? 0;
        final back = (data['refundToCustomer'] as num?)?.toDouble() ?? 0;
        final order = (data['order'] as Map?)?.cast<String, dynamic>() ?? const {};
        final orderId = order['id'] as String? ?? '';
        final currency = (order['currency'] as String?)?.isNotEmpty == true
            ? order['currency'] as String
            : sale.currency;
        if (due > 0.004 && orderId.isNotEmpty) {
          // The difference is collected on the new order with the normal
          // tender flow; the exchange part of it the server has recorded.
          ref.read(posExchangeSettlementProvider.notifier).state =
              PosExchangeSettlement(
            orderId: orderId,
            due: due,
            currency: currency,
            lines: [..._newItems],
          );
          return _Done('Exchange recorded', [
            'The customer owes ${AppFormat.money(due, currencyCode: currency)}',
          ]);
        }
        return _Done('Exchange recorded', [
          if (back > 0.004)
            '${AppFormat.money(back, currencyCode: currency)} goes back to how '
                'the customer paid. Nothing to do at the till.'
          else
            'Like for like: no money moves.',
        ]);
      },
      afterDone: () {
        if (ref.read(posExchangeSettlementProvider) != null && mounted) {
          context.go('/pos/tender');
        }
      },
    );
  }

  /// The recall check a sale runs on each line (`checkRecall`), over the
  /// exchange's new items: a recalled pack is refused with the sale's words, a
  /// pack that cannot be told apart from a recalled lot is shown to the cashier.
  Future<bool> _newBasketPassesRecallCheck() async {
    // The till's list is loaded when first asked for; wait for that first read
    // rather than check against an empty list. A failed refresh keeps what the
    // till knew, as on a sale.
    if (ref.read(activeRecallsProvider).fetchedAt == null) {
      await ref.read(activeRecallsProvider.notifier).refresh();
    }
    final recalls = ref.read(activeRecallsProvider).items;
    for (final line in [..._newItems]) {
      final result = checkRecall(line.variantId, recalls,
          batchNo: line.batchNo, expiry: line.expiry);
      if (result is RecallClear) continue;
      if (!mounted) return false;
      if (result is RecallBlocked) {
        await showDialog<void>(
          context: context,
          barrierDismissible: false,
          builder: (_) =>
              RecallStopSaleDialog(itemName: line.name, item: result.item),
        );
        return false;
      }
      final sell = await showDialog<bool>(
            context: context,
            barrierDismissible: false,
            builder: (_) => RecallCheckPackDialog(
                itemName: line.name, items: (result as RecallCheckPack).items),
          ) ??
          false;
      if (!sell) return false;
    }
    return true;
  }

  /// The server's refusal of a new line (recalled lot, uncertified scale) in the
  /// server's words, naming the line its `items[i]` detail points at.
  String? _newLineRefusal(Object e) {
    final code = apiErrorCode(e);
    if (code != 'ORDER_LINE_RECALLED' && code != 'ORDER_SCALE_NOT_CERTIFIED') {
      return null;
    }
    final err = apiErrorOf(e)!;
    final named = <String>[];
    for (final d in err.details) {
      final m = RegExp(r'^(?:new)?[iI]tems\[(\d+)\]').firstMatch(d);
      final i = m == null ? null : int.tryParse(m.group(1)!);
      if (i == null || i < 0 || i >= _newItems.length) continue;
      final l = _newItems[i];
      final label = l.batchNo == null ? l.name : '${l.name}, lot ${l.batchNo}';
      if (!named.contains(label)) named.add(label);
    }
    final said = friendlyError(e, fallback: 'The server refused a new item.');
    return named.isEmpty ? said : '$said Take off: ${named.join('; ')}.';
  }

  Future<void> _submitNoReceipt() async {
    final storeId = ref.read(posStoreProvider);
    if (storeId == null) {
      setState(() => _error = 'Clock in at a store first.');
      return;
    }
    final items = _chosenItems();
    if (items == null) return;
    if (_method != 'STORE_CREDIT' && _method != 'GIFT_CARD') {
      setState(() => _error =
          'Without a receipt the refund goes to store credit or a gift card.');
      return;
    }
    if (_method == 'STORE_CREDIT' && _creditCustomer == null) {
      setState(() => _error = 'Choose the customer who gets the store credit.');
      return;
    }
    final contact = _contactCtrl.text.trim();
    if (contact.isEmpty) {
      setState(() => _error = "Enter the customer's contact (phone or email).");
      return;
    }
    final giftCode = _giftCodeCtrl.text.trim();
    final topUp = _method == 'GIFT_CARD' && _topUp;
    if (topUp && giftCode.isEmpty) {
      setState(() => _error = 'Enter the code of the gift card to top up.');
      return;
    }
    final body = <String, dynamic>{
      'storeId': storeId,
      'reason': _reason,
      'refundMethod': _method,
      if (_method == 'STORE_CREDIT') 'customerId': _creditCustomer!.id,
      'customerContact': contact,
      if (topUp) 'giftCardCode': giftCode,
      'items': items,
    };
    final key = _keyForAttempt('noreceipt|$storeId|$_reason|$_method|'
        '${_creditCustomer?.id ?? ''}|$contact|${topUp ? giftCode : ''}|${_itemsSignature(items)}');
    final currency = _noReceiptLines.values.isEmpty
        ? ''
        : _noReceiptLines.values.first.currency;
    await _send(
      path: '/${ApiConstants.order}/returns/no-receipt',
      body: body,
      key: key,
      onDone: (data) {
        final refund = (data['refundAmount'] as num?)?.toDouble();
        final card = (data['giftCard'] as Map?)?.cast<String, dynamic>();
        return _Done('Return recorded', [
          if (refund != null)
            '${AppFormat.money(refund, currencyCode: currency)} '
                '${_method == 'STORE_CREDIT' ? 'to store credit' : 'to a gift card'}'
          else
            'Refunded to ${refundMethodLabel(_method).toLowerCase()}',
        ], giftCard: card);
      },
    );
  }

  Future<void> _send({
    required String path,
    required Map<String, dynamic> body,
    required String key,
    required _Done Function(Map<String, dynamic> data) onDone,
    VoidCallback? afterDone,
  }) async {
    setState(() {
      _submitting = true;
      _error = null;
      _needsManager = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
            path,
            data: body,
            options: Options(headers: {'Idempotency-Key': key}),
          );
      final data = (resp.data['data'] as Map?)?.cast<String, dynamic>() ?? const {};
      final done = onDone(data);
      if (!mounted) return;
      setState(() {
        _submitting = false;
        _done = done;
      });
      afterDone?.call();
    } catch (e) {
      if (!mounted) return;
      final code = apiErrorCode(e);
      setState(() {
        _submitting = false;
        if (code == 'ORDER_RETURN_NEEDS_MANAGER') {
          _needsManager = _refusalReasons(e);
        } else {
          _error = (_exchange ? _newLineRefusal(e) : null) ??
              returnRefusalLabel(code) ??
              (e is DioException && e.response?.statusCode == 404
                  ? 'Sale or item not found.'
                  : friendlyError(e, fallback: 'Could not record this.'));
        }
      });
    }
  }

  double _previewRefund(ReceiptLookup sale) {
    var sum = 0.0;
    for (final l in sale.lines) {
      sum += (_qty[l.variantId] ?? 0) * l.unitPrice;
    }
    return sum;
  }

  double get _newTotal => _newItems.fold(0.0, (s, l) => s + l.lineTotal);

  // ── building ──────────────────────────────────────────────────────────────

  Widget _panel(BuildContext context, {required List<Widget> children, Key? key}) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      key: key,
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration:
          BoxDecoration(color: cs.errorContainer, borderRadius: AppRadius.chip),
      child: DefaultTextStyle.merge(
        style: TextStyle(color: cs.onErrorContainer),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: children),
      ),
    );
  }

  Widget _refusals(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (_needsManager != null) ...[
          _panel(context, key: const Key('returns-needs-manager'), children: [
            const Text('A manager must take this return',
                style: TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: AppSpacing.xs),
            for (final r in _needsManager!) Text('· ${returnReasonLabel(r)}'),
            const SizedBox(height: AppSpacing.xs),
            const Text('Ask a manager to sign in to this till and record it '
                'in their own session.'),
          ]),
          const SizedBox(height: AppSpacing.md),
        ],
        if (_error != null) ...[
          _panel(context,
              key: const Key('returns-error'), children: [Text(_error!)]),
          const SizedBox(height: AppSpacing.md),
        ],
      ],
    );
  }

  Widget _finder(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: TextField(
                key: const Key('returns-receipt-field'),
                controller: _numberCtrl,
                autofocus: true,
                enabled: !_looking,
                decoration: InputDecoration(
                  labelText: 'Receipt number',
                  hintText: 'Scan the receipt or type its number',
                  prefixIcon: const Icon(Icons.qr_code_scanner),
                  suffixIcon: _looking
                      ? const Padding(
                          padding: EdgeInsets.all(12),
                          child: SizedBox(
                              height: 18,
                              width: 18,
                              child: CircularProgressIndicator(strokeWidth: 2)),
                        )
                      : IconButton(
                          key: const Key('returns-find'),
                          tooltip: 'Find the sale',
                          icon: const Icon(Icons.search),
                          onPressed: () => _find(_numberCtrl.text),
                        ),
                ),
                onSubmitted: _find,
              ),
            ),
            const SizedBox(width: AppSpacing.sm),
            IconButton.filledTonal(
              tooltip: 'Scan with camera',
              onPressed: _looking ? null : _scanReceipt,
              icon: const Icon(Icons.camera_alt_outlined),
            ),
          ],
        ),
        if (_lookupError != null) ...[
          const SizedBox(height: AppSpacing.sm),
          Text(_lookupError!,
              key: const Key('returns-lookup-error'),
              style: TextStyle(color: Theme.of(context).colorScheme.error)),
        ],
      ],
    );
  }

  Widget _itemAdder(BuildContext context, {required String label}) {
    return Row(
      children: [
        Expanded(
          child: TextField(
            key: const Key('returns-item-field'),
            controller: _scanCtrl,
            focusNode: _scanFocus,
            enabled: !_scanning,
            decoration: InputDecoration(
              labelText: label,
              hintText: 'Scan barcode or type SKU…',
              prefixIcon: const Icon(Icons.qr_code_scanner),
              suffixIcon: IconButton(
                key: const Key('returns-item-add'),
                tooltip: 'Add',
                icon: const Icon(Icons.add_circle_outline),
                onPressed: _scanning ? null : () => _scanItem(_scanCtrl.text),
              ),
            ),
            onSubmitted: _scanItem,
          ),
        ),
        const SizedBox(width: AppSpacing.sm),
        IconButton.filledTonal(
          tooltip: 'Scan with camera',
          onPressed: _scanning ? null : _scanItemWithCamera,
          icon: const Icon(Icons.camera_alt_outlined),
        ),
        const SizedBox(width: AppSpacing.xs),
        IconButton.outlined(
          key: const Key('returns-item-browse'),
          tooltip: 'Browse products',
          onPressed: _scanning ? null : _browse,
          icon: const Icon(Icons.grid_view),
        ),
      ],
    );
  }

  Widget _methodChips(BuildContext context, {required bool canOriginal, required bool canCredit}) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Pay the refund', style: Theme.of(context).textTheme.labelLarge),
        const SizedBox(height: AppSpacing.xs),
        Wrap(
          spacing: AppSpacing.sm,
          runSpacing: AppSpacing.xs,
          children: [
            for (final m in const ['ORIGINAL', 'STORE_CREDIT', 'GIFT_CARD'])
              if (m != 'ORIGINAL' || canOriginal)
                ChoiceChip(
                  key: Key('returns-method-$m'),
                  label: Text(refundMethodLabel(m)),
                  selected: _method == m,
                  onSelected: m == 'STORE_CREDIT' && !canCredit
                      ? null
                      : (_) => setState(() => _method = m),
                ),
          ],
        ),
        if (!canCredit && _mode == _Mode.sale) ...[
          const SizedBox(height: AppSpacing.xs),
          Text('Store credit needs a sale that names a customer.',
              key: const Key('returns-store-credit-why'),
              style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
        ],
        if (_method == 'GIFT_CARD') ...[
          const SizedBox(height: AppSpacing.sm),
          Wrap(
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.xs,
            children: [
              ChoiceChip(
                key: const Key('returns-gift-new'),
                label: const Text('New card'),
                selected: !_topUp,
                onSelected: (_) => setState(() => _topUp = false),
              ),
              ChoiceChip(
                key: const Key('returns-gift-topup'),
                label: const Text('Top up a card'),
                selected: _topUp,
                onSelected: (_) => setState(() => _topUp = true),
              ),
            ],
          ),
          if (_topUp) ...[
            const SizedBox(height: AppSpacing.sm),
            TextField(
              key: const Key('returns-gift-code'),
              controller: _giftCodeCtrl,
              textCapitalization: TextCapitalization.characters,
              decoration: const InputDecoration(labelText: 'Gift card code'),
            ),
          ],
        ],
      ],
    );
  }

  Widget _saleLines(BuildContext context, ReceiptLookup sale) {
    final labels = ref
            .watch(variantLabelsProvider(
                variantIdsKey(sale.lines.map((l) => l.variantId))))
            .value ??
        const <String, VariantLabel>{};
    String money(double v) => AppFormat.money(v, currencyCode: sale.currency);
    final returnable = sale.lines.where((l) => l.returnableQty > 0).toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('Sale ${sale.receiptNumber}',
            key: const Key('returns-sale-header'),
            style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: AppSpacing.sm),
        if (returnable.isEmpty)
          Text('Everything on this sale has already been returned.',
              key: const Key('returns-nothing-left'),
              style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant))
        else ...[
          Text('What comes back', style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: AppSpacing.xs),
          for (final l in returnable)
            _ReturnLineTile(
              variantId: l.variantId,
              name: variantDisplayName(l.variantId, labels),
              detail: '${variantSku(l.variantId, labels).isNotEmpty ? '${variantSku(l.variantId, labels)} · ' : ''}'
                  'sold ${_qtyValue(l.soldQty)}'
                  '${l.returnedQty > 0 ? ' · returned ${_qtyValue(l.returnedQty)}' : ''}'
                  ' · ${money(l.unitPrice)}',
              max: l.returnableQty.floor(),
              value: (_qty[l.variantId] ?? 0).toInt(),
              condition: _condition[l.variantId],
              recalled: !_exchange && _isRecalled(l.variantId),
              onChanged: (v) => setState(() => _qty[l.variantId] = v.toDouble()),
              onCondition: (c) => setState(() => _condition[l.variantId] = c),
            ),
        ],
      ],
    );
  }

  Widget _newBasket(BuildContext context, String currency) {
    String money(double v) => AppFormat.money(v, currencyCode: currency);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('What the customer takes instead',
            style: Theme.of(context).textTheme.labelLarge),
        const SizedBox(height: AppSpacing.xs),
        _itemAdder(context, label: 'New item'),
        const SizedBox(height: AppSpacing.sm),
        if (_newItems.isEmpty)
          Text('Nothing chosen yet.',
              style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant)),
        for (final l in _newItems)
          ListTile(
            key: Key('returns-new-${l.variantId}'
                '${l.batchNo != null ? '-${l.batchNo}' : ''}'
                '${l.markdownId != null ? '-sticker-${l.markdownId}' : ''}'),
            contentPadding: EdgeInsets.zero,
            dense: true,
            title: Text(l.name),
            subtitle: Text('${l.sku} · ${money(l.unitPrice)}'),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                IconButton(
                  tooltip: 'Decrease quantity',
                  icon: const Icon(Icons.remove_circle_outline),
                  onPressed: () => setState(() {
                    final i = _newItems.indexOf(l);
                    if (l.qty <= 1) {
                      _newItems.removeAt(i);
                    } else {
                      _newItems[i] = l.copyWith(qty: l.qty - 1);
                    }
                  }),
                ),
                Text(l.qtyLabel, style: const TextStyle(fontWeight: FontWeight.bold)),
                IconButton(
                  tooltip: 'Increase quantity',
                  icon: const Icon(Icons.add_circle_outline),
                  onPressed: () => setState(() {
                    final i = _newItems.indexOf(l);
                    _newItems[i] = l.copyWith(qty: l.qty + 1);
                  }),
                ),
              ],
            ),
          ),
        if (_newItems.isNotEmpty)
          Text(
            'New items about ${money(_newTotal)}. The till charges the difference '
            'the server works out.',
            key: const Key('returns-new-total'),
            style: TextStyle(
                fontSize: 12, color: Theme.of(context).colorScheme.onSurfaceVariant),
          ),
      ],
    );
  }

  Widget _doneCard(BuildContext context) {
    final done = _done!;
    final card = done.giftCard;
    return Card(
      key: const Key('returns-done'),
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Icon(Icons.check_circle_outline,
                size: 40, color: Theme.of(context).colorScheme.primary),
            const SizedBox(height: AppSpacing.sm),
            Text(done.title,
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: AppSpacing.sm),
            for (final line in done.lines)
              Text(line, textAlign: TextAlign.center),
            if (card != null) ...[
              const SizedBox(height: AppSpacing.md),
              const Text('The refund is on a gift card. This is the only time the '
                  'code is shown.', textAlign: TextAlign.center),
              const SizedBox(height: AppSpacing.sm),
              SelectableText(
                '${card['code'] ?? ''}',
                key: const Key('returns-gift-card-code'),
                textAlign: TextAlign.center,
                style: Theme.of(context)
                    .textTheme
                    .titleLarge
                    ?.copyWith(fontFamily: 'monospace', fontWeight: FontWeight.bold),
              ),
              Text(
                'Balance ${AppFormat.money((card['balance'] as num?)?.toDouble() ?? 0, currencyCode: _cardCurrency)}',
                key: const Key('returns-gift-card-balance'),
                textAlign: TextAlign.center,
              ),
            ],
            const SizedBox(height: AppSpacing.lg),
            FilledButton(
              key: const Key('returns-new-return'),
              onPressed: _reset,
              child: const Text('Another return'),
            ),
          ],
        ),
      ),
    );
  }

  String get _cardCurrency =>
      _sale?.currency ??
      (_noReceiptLines.values.isEmpty ? '' : _noReceiptLines.values.first.currency);

  Widget _noReceiptForm(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          'A manager takes items back with no sale to look up. The refund goes '
          'to store credit or a gift card, at the price today.',
          style: TextStyle(color: cs.onSurfaceVariant),
        ),
        const SizedBox(height: AppSpacing.md),
        _itemAdder(context, label: 'Item being returned'),
        const SizedBox(height: AppSpacing.sm),
        for (final l in _noReceiptLines.values)
          _ReturnLineTile(
            variantId: l.variantId,
            name: l.name,
            detail: '${l.sku} · ${AppFormat.money(l.unitPrice, currencyCode: l.currency)}',
            max: 99,
            value: (_qty[l.variantId] ?? 0).toInt(),
            condition: _condition[l.variantId],
            onChanged: (v) => setState(() {
              if (v <= 0) {
                _qty.remove(l.variantId);
                _condition.remove(l.variantId);
                _noReceiptLines.remove(l.variantId);
              } else {
                _qty[l.variantId] = v.toDouble();
              }
            }),
            onCondition: (c) => setState(() => _condition[l.variantId] = c),
          ),
        const SizedBox(height: AppSpacing.md),
        _methodChips(context, canOriginal: false, canCredit: true),
        if (_method == 'STORE_CREDIT') ...[
          const SizedBox(height: AppSpacing.sm),
          OutlinedButton.icon(
            key: const Key('returns-pick-customer'),
            onPressed: _pickCustomer,
            icon: const Icon(Icons.person_outline),
            label: Text(_creditCustomer == null
                ? 'Choose the customer'
                : (_creditCustomer!.fullName.isEmpty
                    ? _creditCustomer!.email
                    : _creditCustomer!.fullName)),
          ),
        ],
        const SizedBox(height: AppSpacing.md),
        TextField(
          key: const Key('returns-contact'),
          controller: _contactCtrl,
          decoration: const InputDecoration(
            labelText: 'Customer contact',
            hintText: 'Phone or email, kept with the return',
            prefixIcon: Icon(Icons.contact_phone_outlined),
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final canNoReceipt = _canNoReceipt;
    final sale = _sale;
    final noReceipt = _mode == _Mode.noReceipt;

    final children = <Widget>[
      Text('Returns', style: theme.textTheme.headlineMedium),
      const SizedBox(height: AppSpacing.xs),
      Text(
        'Find the sale by its receipt number, then refund or exchange.',
        style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
      ),
      const SizedBox(height: AppSpacing.lg),
    ];

    if (_done != null) {
      children.add(_doneCard(context));
    } else {
      // Only a manager who holds the refund decision is offered a return with
      // no receipt; the server refuses anyone else all the same.
      if (canNoReceipt) {
        children.addAll([
          SegmentedButton<_Mode>(
            key: const Key('returns-mode'),
            segments: const [
              ButtonSegment(
                  value: _Mode.sale,
                  icon: Icon(Icons.receipt_long_outlined),
                  label: Text('With receipt')),
              ButtonSegment(
                  value: _Mode.noReceipt,
                  icon: Icon(Icons.receipt_outlined),
                  label: Text('No receipt')),
            ],
            selected: {_mode},
            onSelectionChanged: (s) => setState(() {
              _mode = s.first;
              _error = null;
              _needsManager = null;
              _qty.clear();
              _condition.clear();
              _method = _mode == _Mode.noReceipt ? 'STORE_CREDIT' : 'ORIGINAL';
              _topUp = false;
              _exchange = false;
            }),
          ),
          const SizedBox(height: AppSpacing.lg),
        ]);
      }

      if (noReceipt) {
        children.add(_noReceiptForm(context));
      } else {
        children.add(_finder(context));
        if (sale != null) {
          final hasCustomer = sale.customerId != null;
          children.addAll([
            const SizedBox(height: AppSpacing.lg),
            _saleLines(context, sale),
            if (sale.lines.any((l) => l.returnableQty > 0)) ...[
              const SizedBox(height: AppSpacing.md),
              SegmentedButton<bool>(
                key: const Key('returns-action'),
                segments: const [
                  ButtonSegment(
                      value: false,
                      icon: Icon(Icons.undo),
                      label: Text('Refund')),
                  ButtonSegment(
                      value: true,
                      icon: Icon(Icons.swap_horiz),
                      label: Text('Exchange')),
                ],
                selected: {_exchange},
                onSelectionChanged: (s) => setState(() {
                  _exchange = s.first;
                  _error = null;
                  _needsManager = null;
                }),
              ),
              const SizedBox(height: AppSpacing.md),
              if (_exchange)
                _newBasket(context, sale.currency)
              else ...[
                _methodChips(context, canOriginal: true, canCredit: hasCustomer),
                // A recall's refund is the business's duty, not a favour of the
                // return policy: offered only where the order has an open notice.
                RecallReturnChoice(
                  orderId: sale.orderId,
                  selected: _recall,
                  onChanged: (n) => setState(() {
                    _recall = n;
                    _error = null;
                  }),
                ),
              ],
              if (!_exchange && _previewRefund(sale) > 0) ...[
                const SizedBox(height: AppSpacing.md),
                Row(
                  children: [
                    Text('Refund total', style: theme.textTheme.titleMedium),
                    const Spacer(),
                    Text(
                      AppFormat.money(_previewRefund(sale),
                          currencyCode: sale.currency),
                      key: const Key('returns-refund-total'),
                      style: theme.textTheme.titleMedium
                          ?.copyWith(fontWeight: FontWeight.bold),
                    ),
                  ],
                ),
              ],
            ],
          ]);
        }
      }

      final ready = noReceipt || (sale != null && sale.lines.any((l) => l.returnableQty > 0));
      if (ready) {
        children.addAll([
          const SizedBox(height: AppSpacing.md),
          TextField(
            key: const Key('returns-reason'),
            controller: _reasonCtrl,
            decoration: const InputDecoration(
              labelText: 'Reason',
              hintText: 'e.g. damaged, wrong size',
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          _refusals(context),
          FilledButton.icon(
            key: const Key('returns-submit'),
            style: FilledButton.styleFrom(
              backgroundColor: context.channelAccent.color,
              foregroundColor: context.channelAccent.onColor,
              padding: const EdgeInsets.symmetric(vertical: AppSpacing.lg),
            ),
            onPressed: _submitting ? null : _submit,
            icon: _submitting
                ? SizedBox(
                    height: 20,
                    width: 20,
                    child: CircularProgressIndicator(
                        strokeWidth: 2, color: context.channelAccent.onColor))
                : Icon(_exchange && !noReceipt ? Icons.swap_horiz : Icons.undo),
            label: Text(
              _submitting
                  ? 'Working…'
                  : (_exchange && !noReceipt ? 'Exchange' : 'Refund'),
              style: const TextStyle(fontSize: 17),
            ),
          ),
        ]);
      }
    }

    return ListView(
      padding: EdgeInsets.symmetric(
          horizontal: context.pageGutter, vertical: context.pageGutter),
      children: [ContentBounds.form(child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: children,
      ))],
    );
  }
}

/// One item coming back: how many, and what condition it is in (nothing is
/// preselected: the person looks). Where the goods go for a condition is said
/// under the chosen chip.
class _ReturnLineTile extends StatelessWidget {
  final String variantId;
  final String name;
  final String detail;
  final int max;
  final int value;
  final String? condition;

  /// Covered by the recall this refund settles: no condition is asked.
  final bool recalled;
  final ValueChanged<int> onChanged;
  final ValueChanged<String> onCondition;

  const _ReturnLineTile({
    required this.variantId,
    required this.name,
    required this.detail,
    required this.max,
    required this.value,
    required this.condition,
    this.recalled = false,
    required this.onChanged,
    required this.onCondition,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsetsDirectional.symmetric(vertical: AppSpacing.xs),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(name,
                        style: const TextStyle(
                            fontWeight: FontWeight.w600, fontSize: 14)),
                    Text(detail,
                        style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
                  ],
                ),
              ),
              IconButton(
                key: Key('returns-dec-$variantId'),
                icon: const Icon(Icons.remove_circle_outline),
                tooltip: 'Decrease quantity',
                onPressed: value > 0 ? () => onChanged(value - 1) : null,
              ),
              Text('$value',
                  key: Key('returns-qty-$variantId'),
                  style: const TextStyle(fontWeight: FontWeight.bold)),
              IconButton(
                key: Key('returns-inc-$variantId'),
                icon: const Icon(Icons.add_circle_outline),
                tooltip: 'Increase quantity',
                onPressed: value < max ? () => onChanged(value + 1) : null,
              ),
            ],
          ),
          if (value > 0 && recalled)
            Text('Recalled: goes to recalled stock, no condition needed.',
                key: Key('returns-recalled-$variantId'),
                style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant))
          else if (value > 0) ...[
            Wrap(
              spacing: AppSpacing.sm,
              runSpacing: AppSpacing.xs,
              children: [
                for (final c in returnConditions)
                  ChoiceChip(
                    key: Key('returns-condition-$variantId-$c'),
                    label: Text(returnConditionLabel(c)),
                    selected: condition == c,
                    onSelected: (_) => onCondition(c),
                  ),
              ],
            ),
            if (condition != null)
              Padding(
                padding: const EdgeInsetsDirectional.only(top: AppSpacing.xs),
                child: Text(returnConditionHint(condition),
                    style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant)),
              ),
          ],
        ],
      ),
    );
  }
}

/// Search the till's catalogue and pick a product, for the exchange's new
/// basket and a no-receipt item: the same list the Sale screen browses.
class _ProductPickDialog extends ConsumerStatefulWidget {
  const _ProductPickDialog();

  @override
  ConsumerState<_ProductPickDialog> createState() => _ProductPickDialogState();
}

class _ProductPickDialogState extends ConsumerState<_ProductPickDialog> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(posCatalogProvider((categoryId: null, query: _query)));
    return Dialog(
      child: SizedBox(
        width: 460,
        height: 520,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: SearchBar(
                      autoFocus: true,
                      hintText: 'Search products…',
                      leading: const Icon(Icons.search),
                      onSubmitted: (v) => setState(() => _query = v.trim()),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close),
                    tooltip: 'Close',
                    onPressed: () => Navigator.pop(context),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: async.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (e, _) => Center(
                    child: Text(friendlyError(e, fallback: 'Could not load products.'))),
                data: (products) => ListView.separated(
                  itemCount: products.length,
                  separatorBuilder: (_, _) => const Divider(height: 1),
                  itemBuilder: (_, i) => ListTile(
                    leading: const Icon(Icons.inventory_2_outlined),
                    title: Text(products[i].name),
                    onTap: () => Navigator.pop(context, products[i]),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Pick the customer a no-receipt store credit goes to.
class _CustomerPickDialog extends ConsumerStatefulWidget {
  const _CustomerPickDialog();

  @override
  ConsumerState<_CustomerPickDialog> createState() => _CustomerPickDialogState();
}

class _CustomerPickDialogState extends ConsumerState<_CustomerPickDialog> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(customersProvider);
    return Dialog(
      child: SizedBox(
        width: 460,
        height: 520,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: SearchBar(
                      autoFocus: true,
                      hintText: 'Search name, email or phone…',
                      leading: const Icon(Icons.search),
                      onChanged: (v) => setState(() => _query = v.trim().toLowerCase()),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close),
                    tooltip: 'Close',
                    onPressed: () => Navigator.pop(context),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: async.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (e, _) => Center(
                    child: Text(friendlyError(e, fallback: 'Could not load customers.'))),
                data: (all) {
                  final list = _query.isEmpty
                      ? all
                      : all
                          .where((c) =>
                              '${c.fullName} ${c.email} ${c.phone ?? ''}'
                                  .toLowerCase()
                                  .contains(_query))
                          .toList();
                  return ListView.separated(
                    itemCount: list.length,
                    separatorBuilder: (_, _) => const Divider(height: 1),
                    itemBuilder: (_, i) {
                      final c = list[i];
                      return ListTile(
                        leading: const Icon(Icons.person_outline),
                        title: Text(c.fullName.isEmpty ? c.email : c.fullName),
                        subtitle: Text([c.email, c.phone]
                            .where((e) => e != null && e.isNotEmpty)
                            .join(' · ')),
                        onTap: () => Navigator.pop(context, c),
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}
