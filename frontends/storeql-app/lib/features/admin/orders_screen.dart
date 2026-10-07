import 'dart:math' as math;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';
import '../../core/format.dart';
import '../../core/input_mode.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import 'providers/admin_providers.dart';
import 'recall_providers.dart';
import 'recall_return_choice.dart';
import 'providers/orders_pagination.dart';
import 'sales_invoices_dialog.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/util/slot_label.dart';
import '../../shared/util/status_labels.dart';
import 'package:storeql_app/core/ids.dart';
import '../../core/amount_entry.dart';
import 'widgets/figure_field.dart';

/// The body of a cancel. The reason is optional, and the server takes "no
/// reason" as no body at all: a body with a blank reason is refused (SJ-D49),
/// so a dialog left empty sends nothing rather than an empty string.
Map<String, dynamic>? cancelBody(String? reason) {
  final r = reason?.trim() ?? '';
  return r.isEmpty ? null : {'reason': r};
}

class AdminOrdersScreen extends ConsumerStatefulWidget {
  const AdminOrdersScreen({super.key});

  @override
  ConsumerState<AdminOrdersScreen> createState() => _AdminOrdersScreenState();
}

class _AdminOrdersScreenState extends ConsumerState<AdminOrdersScreen> {
  String _channel = 'ALL';
  String _status = 'ALL';

  static const _channels = ['ALL', 'ONLINE', 'POS'];
  static const _statuses = [
    'ALL',
    'AWAITING_PRICE',
    'PENDING',
    'CONFIRMED',
    'PARTIALLY_FULFILLED',
    'FULFILLED',
    'CANCELLED',
    'VOIDED',
  ];

  final _scrollController = ScrollController();

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  OrdersFilter get _filter => OrdersFilter(_channel, _status);

  Future<void> _refresh() =>
      ref.read(ordersPaginationProvider(_filter).notifier).refresh();

  /// Fetch the next page once the user scrolls within 300px of the bottom.
  void _onScroll() {
    if (_scrollController.position.pixels >=
        _scrollController.position.maxScrollExtent - 300) {
      ref.read(ordersPaginationProvider(_filter).notifier).loadMore();
    }
  }

  @override
  Widget build(BuildContext context) {
    final page = ref.watch(ordersPaginationProvider(_filter));
    // A void is a management action: the shared filter refuses anyone else, so the menu
    // offers it to an owner or manager rather than showing a button that always fails.
    final auth = ref.watch(authNotifierProvider).value;
    final canVoid =
        auth is AuthAuthenticated && auth.isManager && auth.hasPermission('sales.void');
    // Invoices to business buyers are management's too (18.9): the same filter refuses
    // everyone else.
    final management = auth is AuthAuthenticated && auth.isManager;
    final gutter = context.pageGutter;

    return ContentBounds(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          PageHeader(
            title: 'Orders',
            actions: [
              // Touch screens pull to refresh — the list and its empty state
              // alike — so a phone doesn't give a lone icon a row of its own
              // under the title; a mouse can't pull, so it gets the button.
              if (pointerFirst)
                IconButton(
                  icon: const Icon(Icons.refresh),
                  tooltip: 'Refresh',
                  onPressed: _refresh,
                ),
            ],
          ),

          // Filter bar — scrolls sideways when the chips don't fit
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: EdgeInsetsDirectional.symmetric(horizontal: gutter),
            child: Row(
              children: [
                // Channel chips
                ...(_channels.map((c) => Padding(
                      padding: const EdgeInsetsDirectional.only(end: AppSpacing.sm),
                      child: FilterChip(
                        label: Text(c == 'ALL' ? 'All channels' : channelLabel(c)),
                        selected: _channel == c,
                        onSelected: (_) => setState(() => _channel = c),
                        avatar: c == 'POS'
                            ? const Icon(Icons.point_of_sale, size: 14)
                            : c == 'ONLINE'
                                ? const Icon(Icons.shopping_bag_outlined, size: 14)
                                : null,
                      ),
                    ))),
                // A divider in a sideways-scrolling Row gets no height of its own
                // and paints nothing, so it is given one.
                const SizedBox(height: 24, child: VerticalDivider(width: 16)),
                const SizedBox(width: AppSpacing.sm),
                // Status chips
                ...(_statuses.map((s) => Padding(
                      padding: const EdgeInsetsDirectional.only(end: AppSpacing.sm),
                      child: FilterChip(
                        label: Text(s == 'ALL' ? 'All statuses' : orderStatusLabel(s)),
                        selected: _status == s,
                        onSelected: (_) => setState(() => _status = s),
                      ),
                    ))),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.sm),

          // Orders list
          Expanded(
            child: Builder(builder: (context) {
              if (page.isLoadingInitial) {
                return const LoadingView(label: 'Loading orders…');
              }
              if (page.error != null && page.orders.isEmpty) {
                return ErrorView(
                  message: friendlyError(page.error!,
                      fallback: 'Could not load orders.'),
                  onRetry: _refresh,
                );
              }
              final orders = page.orders;
              if (orders.isEmpty) {
                final hasFilter = _channel != 'ALL' || _status != 'ALL';
                // Pullable too, so a touch screen can look again for new
                // orders without a refresh button.
                return LayoutBuilder(
                  builder: (context, bc) => RefreshIndicator.adaptive(
                    onRefresh: _refresh,
                    child: SingleChildScrollView(
                      physics: const AlwaysScrollableScrollPhysics(),
                      child: ConstrainedBox(
                        constraints: BoxConstraints(minHeight: bc.maxHeight),
                        child: EmptyState(
                          icon: Icons.receipt_long_outlined,
                          title:
                              hasFilter ? 'No matching orders' : 'No orders yet',
                          message: hasFilter
                              ? 'Try changing the channel or status filter.'
                              : 'Orders placed by customers will appear here.',
                        ),
                      ),
                    ),
                  ),
                );
              }
              return LayoutBuilder(builder: (context, bc) {
                final compact =
                    AppBreakpoints.classOf(bc.maxWidth) == WindowClass.compact;
                return RefreshIndicator.adaptive(
                  onRefresh: _refresh,
                  child: ListView.separated(
                    controller: _scrollController,
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: EdgeInsetsDirectional.fromSTEB(
                        gutter, AppSpacing.sm, gutter, AppSpacing.xl),
                    itemCount:
                        orders.length + (page.hasMore || page.isLoadingMore ? 1 : 0),
                    separatorBuilder: (_, _) =>
                        const SizedBox(height: AppSpacing.xs),
                    itemBuilder: (context, i) {
                      if (i >= orders.length) {
                        return const Padding(
                          padding: EdgeInsets.all(AppSpacing.lg),
                          child: Center(child: CircularProgressIndicator()),
                        );
                      }
                      final o = orders[i];
                      return Card(
                        child: _OrderTile(
                          order: o,
                          compact: compact,
                          menu: OrderActionsMenu(
                            status: o.status,
                            channel: o.channel,
                            paymentMethod: o.paymentMethod,
                            canVoid: canVoid,
                            canInvoice: management,
                            onAction: (a) => _action(o, a),
                          ),
                        ),
                      );
                    },
                  ),
                );
              });
            }),
          ),
        ],
      ),
    );
  }

  Future<void> _action(OrderSummary o, String action) async {
    if (action == 'collect') {
      await showDialog<void>(
        context: context,
        builder: (_) => _CollectPaymentDialog(
          order: o,
          onDone: () {
            ref.read(ordersPaginationProvider(_filter).notifier).refresh();
            ref.invalidate(recentOrdersProvider);
          },
        ),
      );
      return;
    }
    if (action == 'price') {
      // SJ-D41: a catalog-mode till order gets its prices from a manager here.
      await showDialog<void>(
        context: context,
        builder: (_) => PriceOrderDialog(
          orderId: o.id,
          currency: o.currency,
          onDone: () {
            ref.read(ordersPaginationProvider(_filter).notifier).refresh();
            ref.invalidate(recentOrdersProvider);
          },
        ),
      );
      return;
    }
    if (action == 'fulfil') {
      // SJ-D35: which lines, and how much of each, are handed over now.
      await showDialog<void>(
        context: context,
        builder: (_) => FulfilDialog(
          orderId: o.id,
          onDone: () {
            ref.read(ordersPaginationProvider(_filter).notifier).refresh();
            ref.invalidate(recentOrdersProvider);
          },
        ),
      );
      return;
    }
    if (action == 'return') {
      await showDialog<void>(
        context: context,
        builder: (_) => ReturnDialog(
          orderId: o.id,
          onDone: () {
            ref.read(ordersPaginationProvider(_filter).notifier).refresh();
            ref.invalidate(recentOrdersProvider);
          },
        ),
      );
      return;
    }
    if (action == 'void') {
      // 09.13: a completed till sale corrected from the back office. The dialog asks why;
      // the server puts the stock back and keeps the receipt's number, marked void.
      await showDialog<void>(
        context: context,
        builder: (_) => VoidSaleDialog(
          orderId: o.id,
          onDone: () {
            ref.read(ordersPaginationProvider(_filter).notifier).refresh();
            ref.invalidate(recentOrdersProvider);
          },
        ),
      );
      return;
    }
    if (action == 'invoices') {
      // 18.9: the invoice a sale to a business was given, and its credit notes.
      await showDialog<void>(
        context: context,
        builder: (_) => OrderInvoicesDialog(orderId: o.id),
      );
      return;
    }
    if (action == 'receipt') {
      try {
        await ref.read(apiClientProvider).dio.post(
          '/${ApiConstants.order}/admin/orders/${o.id}/receipts',
          data: {'receiptType': 'SALE', 'printCount': 1},
        );
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Receipt generated.')),
        );
      } catch (e) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content:
              Text(friendlyError(e, fallback: 'Could not generate receipt.')),
          backgroundColor: Theme.of(context).colorScheme.error,
        ));
      }
      return;
    }
    String? reason;
    if (action == 'cancel') {
      reason = await _promptReason(context);
      if (reason == null) return; // dialog dismissed
    }
    try {
      await ref.read(apiClientProvider).dio.post(
            '/${ApiConstants.order}/orders/${o.id}/$action',
            data: action == 'cancel' ? cancelBody(reason) : null,
          );
      ref.read(ordersPaginationProvider(_filter).notifier).refresh();
      ref.invalidate(recentOrdersProvider);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Order ${_pastTense(action)}.')),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(friendlyError(e, fallback: 'Could not $action order.')),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    }
  }

  String _pastTense(String action) => switch (action) {
        'confirm' => 'confirmed',
        'fulfil' => 'fulfilled',
        'cancel' => 'cancelled',
        _ => action,
      };

  Future<String?> _promptReason(BuildContext context) async {
    final ctrl = TextEditingController();
    final reason = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Cancel order?'),
        content: TextField(
          controller: ctrl,
          autofocus: true,
          decoration: const InputDecoration(
            labelText: 'Reason (optional)',
            hintText: 'e.g. customer request',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Keep order'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(ctx).colorScheme.error,
              foregroundColor: Theme.of(ctx).colorScheme.onError,
            ),
            onPressed: () => Navigator.pop(ctx, ctrl.text.trim()),
            child: const Text('Cancel order'),
          ),
        ],
      ),
    );
    ctrl.dispose();
    return reason;
  }
}

/// The reason codes a refused return carries, in the order the server named
/// them: the top-level `details` of the problem, else the envelope's own.
/// A code may arrive as `reason=CEILING`; only the code is kept.
List<String> returnRefusalReasons(Object e) {
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

/// Takes items back against an order (order-svc). Each returned line says what
/// condition it is in (nothing is preselected: the person looks), and the
/// refund goes where the person chooses: back to how they paid, to the
/// customer's store credit, or to a gift card. The server refunds and restocks
/// from the return itself; the dialog only records it, once — the same
/// Idempotency-Key goes with a retry of the same submit.
class ReturnDialog extends ConsumerStatefulWidget {
  final String orderId;
  final VoidCallback onDone;
  const ReturnDialog({super.key, required this.orderId, required this.onDone});

  @override
  ConsumerState<ReturnDialog> createState() => _ReturnDialogState();
}

class _ReturnDialogState extends ConsumerState<ReturnDialog> {
  final _reasonCtrl = TextEditingController();
  final _giftCodeCtrl = TextEditingController();
  final Map<String, int> _returnQty = {}; // variantId → qty to return
  final Map<String, String> _condition = {}; // variantId → SEALED|OPENED|DAMAGED|FAULTY
  String _method = 'ORIGINAL';
  bool _topUp = false;
  bool _submitting = false;
  String? _error;

  /// The recall notice this return settles, or null for an ordinary return.
  RecallNotice? _recall;

  /// Whether the return's recall covers this line: its goods go to recalled
  /// stock, so no condition is asked for it.
  bool _isRecalled(String variantId) =>
      _recall?.lines.any((l) => l.variantId == variantId) ?? false;

  /// Why the server said a manager must take the return, as codes.
  List<String>? _needsManager;

  /// The key of the attempt in hand, and what it was for: a retry of the same
  /// submit reuses it, any change to what is sent gets a new one.
  String? _key;
  String? _keyFor;

  /// The gift card a successful return made or topped up, shown once.
  Map<String, dynamic>? _giftCard;

  @override
  void dispose() {
    _reasonCtrl.dispose();
    _giftCodeCtrl.dispose();
    super.dispose();
  }

  double _previewRefund(List<OrderLine> lines) {
    var sum = 0.0;
    for (final l in lines) {
      sum += (_returnQty[l.variantId] ?? 0) * l.unitPrice;
    }
    return sum;
  }

  Future<void> _submit(OrderDetail order) async {
    final chosen = [
      for (final entry in _returnQty.entries)
        if (entry.value > 0) entry,
    ];
    if (chosen.isEmpty) {
      setState(() {
        _needsManager = null;
        _error = 'Select at least one item to return.';
      });
      return;
    }
    if (chosen.any((e) => _condition[e.key] == null && !_isRecalled(e.key))) {
      setState(() {
        _needsManager = null;
        _error = 'Say what condition each returned item is in.';
      });
      return;
    }
    if (_recall != null && !chosen.any((e) => _isRecalled(e.key))) {
      setState(() {
        _needsManager = null;
        _error = 'Choose the recalled item to take back, or turn off "This is a recall return".';
      });
      return;
    }
    final giftCode = _giftCodeCtrl.text.trim();
    final topUp = _method == 'GIFT_CARD' && _topUp;
    if (topUp && giftCode.isEmpty) {
      setState(() {
        _needsManager = null;
        _error = 'Enter the code of the gift card to top up.';
      });
      return;
    }
    final reason =
        _reasonCtrl.text.trim().isEmpty ? 'Customer return' : _reasonCtrl.text.trim();
    final items = [
      for (final e in chosen)
        {
          'variantId': e.key,
          'qty': e.value,
          // A recalled line has none: the server sends the goods to RECALLED.
          if (!_isRecalled(e.key)) 'condition': _condition[e.key],
        },
    ];
    final body = <String, dynamic>{
      'reason': reason,
      'refundMethod': _method,
      if (_recall != null) 'recallNoticeId': _recall!.id,
      if (topUp) 'giftCardCode': giftCode,
      'items': items,
    };
    // The same submit again (a retry after a network failure) is the same
    // attempt; anything changed is a new one.
    final signature = '${body['reason']}|$_method|${topUp ? giftCode : ''}|${_recall?.id ?? ''}|'
        '${[for (final i in items) '${i['variantId']}:${i['qty']}:${i['condition']}'].join(',')}';
    if (_key == null || _keyFor != signature) {
      _key = newId();
      _keyFor = signature;
    }
    setState(() {
      _submitting = true;
      _error = null;
      _needsManager = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
            '/${ApiConstants.order}/orders/${widget.orderId}/returns',
            data: body,
            options: Options(headers: {'Idempotency-Key': _key}),
          );
      final ret = (resp.data['data'] as Map?)?.cast<String, dynamic>() ?? const {};
      final refundAmount = (ret['refundAmount'] as num?)?.toDouble() ?? _previewRefund(order.items);
      final card = (ret['giftCard'] as Map?)?.cast<String, dynamic>();
      if (!mounted) return;
      widget.onDone();
      if (card != null) {
        // The code is shown once, here; closing the dialog is the only way out.
        setState(() {
          _submitting = false;
          _giftCard = card;
        });
        return;
      }
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
            content: Text(
                'Return recorded · ${AppFormat.money(refundAmount, currencyCode: order.currency)} '
                '${_method == 'STORE_CREDIT' ? 'to store credit' : 'refunded'}')),
      );
    } catch (e) {
      if (!mounted) return;
      final code = apiErrorCode(e);
      setState(() {
        _submitting = false;
        if (code == 'ORDER_RETURN_NEEDS_MANAGER') {
          _needsManager = returnRefusalReasons(e);
        } else {
          _error = _friendly(e, code);
        }
      });
    }
  }

  String _friendly(Object e, String? code) {
    switch (code) {
      case 'ORDER_RETURN_CONDITION_REQUIRED':
      case 'ORDER_RETURN_CONDITION_INVALID':
        return 'Say what condition each returned item is in.';
      case 'ORDER_RETURN_STORE_CREDIT_NEEDS_CUSTOMER':
        return 'Store credit needs a sale that names a customer. Choose another way to pay it back.';
      case 'GIFT_CARD_NOT_FOUND':
        return 'No gift card has that code. Check it, or issue a new card.';
    }
    if (e is DioException && e.response?.statusCode == 404) {
      return 'Order or item not found.';
    }
    return friendlyError(e, fallback: 'Could not process return.');
  }

  Widget _panel(BuildContext context, {required List<Widget> children}) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(color: cs.errorContainer, borderRadius: AppRadius.chip),
      child: DefaultTextStyle.merge(
        style: TextStyle(color: cs.onErrorContainer),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: children),
      ),
    );
  }

  Widget _giftCardResult(BuildContext context, OrderDetail order) {
    final card = _giftCard!;
    final balance = (card['balance'] as num?)?.toDouble() ?? 0;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text('The return is recorded and the refund is on a gift card. '
            'This is the only time the code is shown.'),
        const SizedBox(height: AppSpacing.md),
        Text('Gift card code', style: Theme.of(context).textTheme.labelLarge),
        SelectableText(
          '${card['code'] ?? ''}',
          key: const Key('return-gift-card-code'),
          style: Theme.of(context)
              .textTheme
              .titleLarge
              ?.copyWith(fontFamily: 'monospace', fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: AppSpacing.sm),
        Text(
          'Balance ${AppFormat.money(balance, currencyCode: order.currency)}',
          key: const Key('return-gift-card-balance'),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final detailAsync = ref.watch(orderDetailProvider(widget.orderId));
    final returnsAsync = ref.watch(orderReturnsProvider(widget.orderId));

    return AlertDialog(
      title: Text(_giftCard == null ? 'Return / Refund' : 'Return recorded'),
      content: SizedBox(
        width: 460,
        child: detailAsync.when(
          loading: () => const SizedBox(
              height: 160, child: LoadingView(label: 'Loading order…')),
          error: (e, _) => SizedBox(
            height: 160,
            child: ErrorView(
              message: friendlyError(e, fallback: 'Could not load order.'),
              onRetry: () =>
                  ref.invalidate(orderDetailProvider(widget.orderId)),
            ),
          ),
          data: (order) {
            if (_giftCard != null) {
              return SingleChildScrollView(child: _giftCardResult(context, order));
            }
            final preview = _previewRefund(order.items);
            final hasCustomer = order.customerId != null;
            final labels = ref
                    .watch(variantLabelsProvider(
                        variantIdsKey(order.items.map((l) => l.variantId))))
                    .value ??
                const <String, VariantLabel>{};
            return SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (_needsManager != null) ...[
                    _panel(context, children: [
                      const Text('A manager must take this return',
                          key: Key('return-needs-manager'),
                          style: TextStyle(fontWeight: FontWeight.bold)),
                      const SizedBox(height: AppSpacing.xs),
                      for (final r in _needsManager!) Text('· ${returnReasonLabel(r)}'),
                      const SizedBox(height: AppSpacing.xs),
                      const Text('Ask a manager to sign in and record it, '
                          'or hand them this screen in their own session.'),
                    ]),
                    const SizedBox(height: AppSpacing.md),
                  ],
                  if (_error != null) ...[
                    _panel(context, children: [Text(_error!)]),
                    const SizedBox(height: AppSpacing.md),
                  ],
                  // Existing returns (if any).
                  returnsAsync.maybeWhen(
                    data: (returns) => returns.isEmpty
                        ? const SizedBox.shrink()
                        : Padding(
                            padding: const EdgeInsetsDirectional.only(bottom: AppSpacing.md),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text('Previous returns',
                                    style: Theme.of(context)
                                        .textTheme
                                        .labelLarge),
                                for (final r in returns)
                                  Text(
                                    '· ${AppFormat.money(r.refundAmount, currencyCode: order.currency)} '
                                    'via ${refundMethodLabel(r.refundMethod)} (${humanizeCode(r.status)})',
                                    style: TextStyle(
                                        fontSize: 12, color: cs.outline),
                                  ),
                                const Divider(),
                              ],
                            ),
                          ),
                    orElse: () => const SizedBox.shrink(),
                  ),
                  Text('Select quantities to return',
                      style: Theme.of(context).textTheme.labelLarge),
                  const SizedBox(height: AppSpacing.sm),
                  for (final line in order.items)
                    _ReturnLineRow(
                      line: line,
                      name: variantDisplayName(line.variantId, labels),
                      sku: variantSku(line.variantId, labels),
                      currency: order.currency,
                      value: _returnQty[line.variantId] ?? 0,
                      condition: _condition[line.variantId],
                      recalled: _isRecalled(line.variantId),
                      onChanged: (v) =>
                          setState(() => _returnQty[line.variantId] = v),
                      onCondition: (c) =>
                          setState(() => _condition[line.variantId] = c),
                    ),
                  RecallReturnChoice(
                    orderId: widget.orderId,
                    selected: _recall,
                    onChanged: (n) => setState(() {
                      _recall = n;
                      _error = null;
                    }),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  TextField(
                    controller: _reasonCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Reason',
                      hintText: 'e.g. damaged, wrong size',
                    ),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  Text('Pay the refund', style: Theme.of(context).textTheme.labelLarge),
                  const SizedBox(height: AppSpacing.xs),
                  Wrap(
                    spacing: AppSpacing.sm,
                    runSpacing: AppSpacing.xs,
                    children: [
                      for (final m in const ['ORIGINAL', 'STORE_CREDIT', 'GIFT_CARD'])
                        ChoiceChip(
                          key: Key('return-method-$m'),
                          label: Text(refundMethodLabel(m)),
                          selected: _method == m,
                          // Store credit goes to a customer: a sale that names none has nobody to credit.
                          onSelected: m == 'STORE_CREDIT' && !hasCustomer
                              ? null
                              : (_) => setState(() => _method = m),
                        ),
                    ],
                  ),
                  if (!hasCustomer) ...[
                    const SizedBox(height: AppSpacing.xs),
                    Text(
                      'Store credit needs a sale that names a customer.',
                      key: const Key('return-store-credit-why'),
                      style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                    ),
                  ],
                  if (_method == 'GIFT_CARD') ...[
                    const SizedBox(height: AppSpacing.sm),
                    Wrap(
                      spacing: AppSpacing.sm,
                      runSpacing: AppSpacing.xs,
                      children: [
                        ChoiceChip(
                          key: const Key('return-gift-new'),
                          label: const Text('New card'),
                          selected: !_topUp,
                          onSelected: (_) => setState(() => _topUp = false),
                        ),
                        ChoiceChip(
                          key: const Key('return-gift-topup'),
                          label: const Text('Top up a card'),
                          selected: _topUp,
                          onSelected: (_) => setState(() => _topUp = true),
                        ),
                      ],
                    ),
                    if (_topUp) ...[
                      const SizedBox(height: AppSpacing.sm),
                      TextField(
                        key: const Key('return-gift-code'),
                        controller: _giftCodeCtrl,
                        textCapitalization: TextCapitalization.characters,
                        decoration: const InputDecoration(labelText: 'Gift card code'),
                      ),
                    ],
                  ],
                  const SizedBox(height: AppSpacing.lg),
                  Row(
                    children: [
                      Text('Refund total',
                          style: Theme.of(context).textTheme.titleMedium),
                      const Spacer(),
                      Text(AppFormat.money(preview, currencyCode: order.currency),
                          style: Theme.of(context)
                              .textTheme
                              .titleMedium
                              ?.copyWith(fontWeight: FontWeight.bold)),
                    ],
                  ),
                ],
              ),
            );
          },
        ),
      ),
      actions: _giftCard != null
          ? [
              FilledButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Done'),
              ),
            ]
          : [
              TextButton(
                onPressed: _submitting ? null : () => Navigator.pop(context),
                child: const Text('Cancel'),
              ),
              FilledButton(
                onPressed: _submitting
                    ? null
                    : () {
                        final order = detailAsync.value;
                        if (order != null) _submit(order);
                      },
                child: _submitting
                    ? SizedBox(
                        height: 18,
                        width: 18,
                        child: CircularProgressIndicator(
                            strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                    : const Text('Process return'),
              ),
            ],
    );
  }
}

class _ReturnLineRow extends StatelessWidget {
  final OrderLine line;
  final String name;
  final String sku;
  final String currency;
  final int value;
  final String? condition;

  /// Covered by the recall this return settles: no condition is asked.
  final bool recalled;
  final ValueChanged<int> onChanged;
  final ValueChanged<String> onCondition;
  const _ReturnLineRow({
    required this.line,
    required this.name,
    required this.sku,
    required this.currency,
    required this.value,
    required this.condition,
    this.recalled = false,
    required this.onChanged,
    required this.onCondition,
  });

  @override
  Widget build(BuildContext context) {
    final maxQty = line.qty.toInt();
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
                            fontWeight: FontWeight.w600, fontSize: 13)),
                    Text(
                        '${sku.isNotEmpty ? '$sku · ' : ''}ordered $maxQty · ${AppFormat.money(line.unitPrice, currencyCode: currency)}',
                        style: TextStyle(fontSize: 11, color: cs.outline)),
                  ],
                ),
              ),
              IconButton(
                icon: const Icon(Icons.remove_circle_outline),
                tooltip: 'Decrease quantity',
                onPressed: value > 0 ? () => onChanged(value - 1) : null,
              ),
              Text('$value', style: const TextStyle(fontWeight: FontWeight.bold)),
              IconButton(
                icon: const Icon(Icons.add_circle_outline),
                tooltip: 'Increase quantity',
                onPressed: value < maxQty ? () => onChanged(value + 1) : null,
              ),
            ],
          ),
          // What state the goods are in decides where they go; nothing is preselected.
          if (value > 0 && recalled)
            Text('Recalled: goes to recalled stock, no condition needed.',
                key: Key('return-recalled-${line.variantId}'),
                style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant))
          else if (value > 0) ...[
            Wrap(
              spacing: AppSpacing.sm,
              runSpacing: AppSpacing.xs,
              children: [
                for (final c in returnConditions)
                  ChoiceChip(
                    key: Key('return-condition-${line.variantId}-$c'),
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
                    style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant)),
              ),
          ],
        ],
      ),
    );
  }
}

/// The per-order actions. Which appear is decided by the order's status and
/// channel — and, for the void, by who is looking: it is offered on a till
/// sale that is still standing, to an owner or manager only.
///
/// Each label is Flexible, so at large text it wraps inside the menu rather
/// than running off its edge.
class OrderActionsMenu extends StatelessWidget {
  final String status;
  final String channel;
  final String? paymentMethod;
  final bool canVoid;

  /// Whether the caller may see and issue invoices to business buyers (18.9).
  final bool canInvoice;
  final void Function(String action) onAction;
  const OrderActionsMenu({
    super.key,
    required this.status,
    this.channel = '',
    this.paymentMethod,
    this.canVoid = false,
    this.canInvoice = false,
    required this.onAction,
  });

  @override
  Widget build(BuildContext context) {
    final s = status.toUpperCase();
    final items = <PopupMenuEntry<String>>[];
    if (s == 'AWAITING_PRICE') {
      items.add(const PopupMenuItem(
          value: 'price',
          child: Row(children: [
            Icon(Icons.sell_outlined, size: 18),
            SizedBox(width: AppSpacing.sm),
            Flexible(child: Text('Price order')),
          ])));
    }
    if (s == 'PENDING') {
      items.add(const PopupMenuItem(
          value: 'confirm',
          child: Row(children: [
            Icon(Icons.check_circle_outline, size: 18),
            SizedBox(width: AppSpacing.sm),
            Flexible(child: Text('Confirm')),
          ])));
    }
    if (s == 'CONFIRMED' || s == 'PARTIALLY_FULFILLED') {
      items.add(const PopupMenuItem(
          value: 'fulfil',
          child: Row(children: [
            Icon(Icons.local_shipping_outlined, size: 18),
            SizedBox(width: AppSpacing.sm),
            Flexible(child: Text('Mark fulfilled')),
          ])));
    }
    // COD / pay-at-pickup settlement: record the tender when the goods change hands. Shown for
    // any live order — the dialog itself computes what's still outstanding and refuses
    // double-collection.
    if (s == 'PENDING' || s == 'CONFIRMED' || s == 'PARTIALLY_FULFILLED' || s == 'FULFILLED') {
      items.add(const PopupMenuItem(
          value: 'collect',
          child: Row(children: [
            Icon(Icons.point_of_sale_outlined, size: 18),
            SizedBox(width: AppSpacing.sm),
            Flexible(child: Text('Collect payment')),
          ])));
    }
    // A confirmed order was paid for, and cancelling it refunds the money, which is a void by
    // another name: the server asks sales.void for it, so the menu offers it only to those who
    // hold it. An unpaid order is anyone's at its store to cancel.
    if (s == 'PENDING' || (s == 'CONFIRMED' && canVoid)) {
      final cs = Theme.of(context).colorScheme;
      items.add(PopupMenuItem(
          value: 'cancel',
          child: Row(children: [
            Icon(Icons.cancel_outlined, size: 18, color: cs.error),
            const SizedBox(width: AppSpacing.sm),
            Flexible(child: Text('Cancel', style: TextStyle(color: cs.error))),
          ])));
    }
    // A till sale that is still standing can be voided from here (09.13): the stock goes
    // back and the receipt keeps its number. An online order is cancelled or returned
    // instead, and the server refuses anyone below manager, so the menu does not offer it
    // to them.
    if (canVoid && channel.toUpperCase() == 'POS' && s != 'VOIDED' && s != 'CANCELLED') {
      final cs = Theme.of(context).colorScheme;
      items.add(PopupMenuItem(
          value: 'void',
          child: Row(children: [
            Icon(Icons.block_outlined, size: 18, color: cs.error),
            const SizedBox(width: AppSpacing.sm),
            Flexible(child: Text('Void sale', style: TextStyle(color: cs.error))),
          ])));
    }
    // Returns are allowed on orders that weren't cancelled/voided.
    if (s != 'CANCELLED' && s != 'VOIDED') {
      items.add(const PopupMenuItem(
          value: 'return',
          child: Row(children: [
            Icon(Icons.assignment_return_outlined, size: 18),
            SizedBox(width: AppSpacing.sm),
            Flexible(child: Text('Return / Refund')),
          ])));
    }
    // A completed sale to a business has an invoice, or can be given one (18.9). A
    // basket that was never paid for has nothing to invoice, and a cancelled or voided
    // sale is not a sale.
    const sold = {
      'CONFIRMED',
      'FULFILLED',
      'PARTIALLY_FULFILLED',
      'PARTIALLY_REFUNDED',
      'REFUNDED',
    };
    if (canInvoice && sold.contains(s)) {
      items.add(const PopupMenuItem(
          value: 'invoices',
          child: Row(children: [
            Icon(Icons.receipt_long_outlined, size: 18),
            SizedBox(width: AppSpacing.sm),
            Flexible(child: Text('Invoices')),
          ])));
    }
    items.add(const PopupMenuItem(
        value: 'receipt',
        child: Row(children: [
          Icon(Icons.receipt_outlined, size: 18),
          SizedBox(width: AppSpacing.sm),
          Flexible(child: Text('Print receipt')),
        ])));
    if (items.isEmpty) {
      return const SizedBox(width: AppSpacing.sm);
    }
    return PopupMenuButton<String>(
      icon: const Icon(Icons.more_vert),
      tooltip: 'Actions',
      itemBuilder: (_) => items,
      onSelected: onAction,
    );
  }
}

/// One order in the list. On a phone the reference and the total share the
/// first line, the date goes under them and the badges get a line of their
/// own, so the text is never squeezed beside a trailing column. Wider, the
/// badges follow the reference and the status and total sit at the end.
class _OrderTile extends StatelessWidget {
  final OrderSummary order;
  final bool compact;

  /// The order's actions (⋮), always last on the row.
  final Widget menu;

  const _OrderTile({
    required this.order,
    required this.compact,
    required this.menu,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final o = order;
    final pos = o.channel == 'POS';
    final number = Text(
      '#${shortRef(o.id)}',
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: const TextStyle(fontFamily: 'monospace'),
    );
    final total = Text(
      AppFormat.money(o.total, currencyCode: o.currency),
      style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
      textAlign: TextAlign.end,
    );
    final channelWords = channelLabel(o.channel);
    final channel = channelWords.isEmpty ? null : StatusBadge(channelWords);
    final method = o.paymentMethod;
    final payment = method == null
        ? null
        : StatusBadge(_paymentLabel(method, o.fulfilmentType));
    final slot = o.slot;
    // The window this order holds (delivery-and-collection-slots), worded
    // with which kind it is, in the store's own local date and clock — never
    // converted on the device.
    final placed = Text(slot == null
        ? AppFormat.dateTime(o.createdAt)
        : '${AppFormat.dateTime(o.createdAt)}\n${slotWindowLabel(fulfilmentType: o.fulfilmentType, date: slot.date, startTime: slot.startTime, endTime: slot.endTime)}');

    return ListTile(
      contentPadding: const EdgeInsetsDirectional.symmetric(
          horizontal: AppSpacing.lg, vertical: AppSpacing.sm),
      titleAlignment: compact ? ListTileTitleAlignment.top : null,
      leading: CircleAvatar(
        backgroundColor:
            pos ? cs.primaryContainer : context.status.infoContainer,
        child: Icon(
          pos ? Icons.point_of_sale : Icons.shopping_bag_outlined,
          size: 18,
          color: pos ? cs.onPrimaryContainer : context.status.onInfoContainer,
        ),
      ),
      title: compact
          ? Row(
              children: [
                Expanded(child: number),
                const SizedBox(width: AppSpacing.sm),
                total,
              ],
            )
          : Wrap(
              spacing: AppSpacing.sm,
              runSpacing: AppSpacing.xs,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [number, ?channel, ?payment],
            ),
      subtitle: compact
          ? Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                placed,
                const SizedBox(height: AppSpacing.sm),
                Wrap(
                  spacing: 6,
                  runSpacing: AppSpacing.xs,
                  children: [StatusBadge.order(o.status), ?channel, ?payment],
                ),
              ],
            )
          : placed,
      trailing: compact
          ? menu
          : Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                StatusBadge.order(o.status),
                const SizedBox(width: AppSpacing.lg),
                // Totals line up in a column, and a long one still fits.
                ConstrainedBox(
                  constraints: const BoxConstraints(minWidth: 90),
                  child: total,
                ),
                menu,
              ],
            ),
    );
  }
}

/// How the customer said they'd pay, in words, with cash read by fulfilment:
/// *Cash on delivery*, *Cash at pickup*; the other tenders as themselves.
String _paymentLabel(String method, String fulfilmentType) =>
    method.toUpperCase() == 'CASH'
        ? switch (fulfilmentType.toUpperCase()) {
            'DELIVERY' => 'Cash on delivery',
            'PICKUP' => 'Cash at pickup',
            _ => 'Cash',
          }
        : _tenderLabel(method);

/// A tender in a word — *Cash*, *Card*, *UPI*, *Wallet* — the same words the
/// order list's payment badge uses, for where the money changes hands.
String _tenderLabel(String method) => switch (method.toUpperCase()) {
      'CASH' => 'Cash',
      'CARD' => 'Card',
      'UPI' => 'UPI',
      'WALLET' => 'Wallet',
      _ => humanizeCode(method),
    };

/// How a tender reads at the end of "£42.30 collected …": *in cash*, *by card*.
String _collectedBy(String method) => switch (method.toUpperCase()) {
      'CASH' => 'in cash',
      'UPI' => 'by UPI',
      _ => 'by ${_tenderLabel(method).toLowerCase()}',
    };

/// The tenders a pay-later order can be settled in at handover.
const _collectMethods = ['CASH', 'CARD', 'UPI', 'WALLET'];

/// The tender a pay-later order is settled in, in words. Across when the four
/// words each keep a line of their own — a phone's dialog at normal text —
/// and stacked when they would not, so *Wallet* never breaks mid-word.
///
/// It measures its room with a LayoutBuilder, so it sits under the dialog's
/// fixed-width box: an AlertDialog sizes itself by intrinsics, which a
/// LayoutBuilder cannot answer (hence no `scrollable: true` on that dialog).
class _TenderChoice extends StatelessWidget {
  final String selected;
  final ValueChanged<String> onChanged;
  const _TenderChoice({required this.selected, required this.onChanged});

  /// Each segment's inset either side of its word.
  static const _inset = AppSpacing.sm;

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.labelLarge;
    final scaler = MediaQuery.textScalerOf(context);
    final direction = Directionality.of(context);
    return LayoutBuilder(builder: (context, bc) {
      var widest = 0.0;
      for (final m in _collectMethods) {
        final painter = TextPainter(
          text: TextSpan(text: _tenderLabel(m), style: style),
          textDirection: direction,
          textScaler: scaler,
        )..layout();
        widest = math.max(widest, painter.width);
        painter.dispose();
      }
      // The word, its inset either side and a hairline border, four times.
      final across =
          (widest + 2 * _inset + 2) * _collectMethods.length <= bc.maxWidth;
      return SegmentedButton<String>(
        key: const Key('collect-method'),
        direction: across ? Axis.horizontal : Axis.vertical,
        segments: [
          for (final m in _collectMethods)
            ButtonSegment(value: m, label: Text(_tenderLabel(m))),
        ],
        // The selected segment is filled; a tick as well would take a word's
        // room on a phone.
        showSelectedIcon: false,
        style: const ButtonStyle(
          padding: WidgetStatePropertyAll(
              EdgeInsetsDirectional.symmetric(horizontal: _inset)),
        ),
        selected: {selected},
        onSelectionChanged: (s) => onChanged(s.first),
      );
    });
  }
}

/// Settle a pay-later (COD / pay-at-pickup) order at handover: shows what's already been
/// captured, and records one tender for the outstanding balance via payment-svc. The captured
/// tender emits PaymentCaptured, which confirms a PENDING order automatically.
class _CollectPaymentDialog extends ConsumerStatefulWidget {
  final OrderSummary order;
  final VoidCallback onDone;
  const _CollectPaymentDialog({required this.order, required this.onDone});

  @override
  ConsumerState<_CollectPaymentDialog> createState() =>
      _CollectPaymentDialogState();
}

class _CollectPaymentDialogState extends ConsumerState<_CollectPaymentDialog> {
  double? _paid; // null while loading
  late String _method;
  bool _submitting = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final declared = widget.order.paymentMethod?.toUpperCase();
    _method = _collectMethods.contains(declared) ? declared! : 'CASH';
    _loadPaid();
  }

  Future<void> _loadPaid() async {
    try {
      final resp = await ref.read(apiClientProvider).dio.get(
          '/${ApiConstants.payment}/payments/by-order/${widget.order.id}');
      final data = (resp.data['data'] as List?) ?? [];
      double paid = 0;
      for (final t in data) {
        final m = t as Map<String, dynamic>;
        final status = (m['status'] as String? ?? '').toUpperCase();
        if (status == 'CAPTURED' || status.isEmpty) {
          paid += (m['amount'] as num?)?.toDouble() ?? 0;
        }
      }
      if (mounted) setState(() => _paid = paid);
    } catch (_) {
      // Payment history unavailable — assume nothing collected; the server-side
      // idempotency key still prevents double capture on retry.
      if (mounted) setState(() => _paid = 0);
    }
  }

  Future<void> _collect(double outstanding) async {
    setState(() {
      _submitting = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.payment}/payments',
        data: {
          'orderId': widget.order.id,
          if (widget.order.storeId.isNotEmpty) 'storeId': widget.order.storeId,
          'amount': outstanding,
          'method': _method,
          'reference': 'ORDER_HANDOVER',
        },
        options: Options(headers: {
          'Idempotency-Key':
              derivedId(widget.order.id, 'collect:${outstanding.toStringAsFixed(2)}')
        }),
      );
      if (!mounted) return;
      widget.onDone();
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(
              '${AppFormat.money(outstanding, currencyCode: widget.order.currency)} '
              'collected ${_collectedBy(_method)}.')));
    } catch (e) {
      setState(() {
        _submitting = false;
        _error = friendlyError(e, fallback: 'Could not record payment.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final o = widget.order;
    final shortId = shortRef(o.id);
    final paid = _paid;
    final outstanding = paid == null ? null : (o.total - paid);
    return AlertDialog(
      title: Text('Collect payment · #$shortId'),
      content: SizedBox(
        width: 380,
        child: paid == null
            ? const SizedBox(
                height: 80, child: Center(child: CircularProgressIndicator()))
            // Scrolls when large text makes it taller than the screen.
            : SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (_error != null) ...[
                      Container(
                        padding: const EdgeInsets.all(AppSpacing.md),
                        decoration: BoxDecoration(
                          color: cs.errorContainer,
                          borderRadius: AppRadius.chip,
                        ),
                        child: Text(_error!,
                            style: TextStyle(color: cs.onErrorContainer)),
                      ),
                      const SizedBox(height: AppSpacing.md),
                    ],
                    Text('Order total: '
                        '${AppFormat.money(o.total, currencyCode: o.currency)}'),
                    if (paid > 0)
                      Text('Already collected: '
                          '${AppFormat.money(paid, currencyCode: o.currency)}'),
                    const SizedBox(height: AppSpacing.sm),
                    if (outstanding! <= 0)
                      Row(children: [
                        Icon(Icons.check_circle_outline, color: cs.primary),
                        const SizedBox(width: AppSpacing.sm),
                        const Expanded(
                            child: Text('This order is already paid in full.')),
                      ])
                    else ...[
                      Text(
                          'Outstanding: ${AppFormat.money(outstanding, currencyCode: o.currency)}',
                          style: const TextStyle(fontWeight: FontWeight.bold)),
                      const SizedBox(height: AppSpacing.md),
                      _TenderChoice(
                        selected: _method,
                        onChanged: (m) => setState(() => _method = m),
                      ),
                    ],
                  ],
                ),
              ),
      ),
      actions: [
        TextButton(
          onPressed: _submitting ? null : () => Navigator.pop(context),
          child: const Text('Close'),
        ),
        if (outstanding != null && outstanding > 0)
          FilledButton.icon(
            onPressed: _submitting ? null : () => _collect(outstanding),
            icon: _submitting
                ?  SizedBox(
                    height: 16,
                    width: 16,
                    child: CircularProgressIndicator(
                        strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                : const Icon(Icons.point_of_sale_outlined),
            label: Text(
                'Collect ${AppFormat.money(outstanding, currencyCode: o.currency)}'),
          ),
      ],
    );
  }
}

/// The product names and SKUs for an order's lines, resolved by product-svc;
/// empty while the order or the names load, or when they can't be read — the
/// lines then fall back to a short handle ([variantDisplayName]).
Map<String, VariantLabel> _lineLabels(WidgetRef ref, OrderDetail? order) {
  if (order == null) return const {};
  return ref
          .watch(variantLabelsProvider(
              variantIdsKey(order.items.map((l) => l.variantId))))
          .value ??
      const <String, VariantLabel>{};
}

/// One order line as a person reads it: the product's name, then a quieter
/// line under it (SKU, what is outstanding) when there is one.
class _LineName extends StatelessWidget {
  final String name;
  final String detail;
  const _LineName({required this.name, this.detail = ''});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(name, style: theme.textTheme.titleSmall),
        if (detail.isNotEmpty)
          Text(
            detail,
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
      ],
    );
  }
}

/// Hand over some or all of an order (SJ-D35). Each line shows what is still
/// outstanding and takes how much goes now; leaving everything at its
/// outstanding quantity hands the whole order over, as *Mark fulfilled* always
/// did. The server refuses more than is outstanding, and a part-fulfilled
/// order cannot be cancelled afterwards — the goods are in the customer's hands.
class FulfilDialog extends ConsumerStatefulWidget {
  const FulfilDialog({super.key, required this.orderId, required this.onDone});
  final String orderId;
  final VoidCallback onDone;

  @override
  ConsumerState<FulfilDialog> createState() => _FulfilDialogState();
}

class _FulfilDialogState extends ConsumerState<FulfilDialog> {
  /// What goes now of each line: a quantity, read the way the app's language
  /// writes a number ([AmountMarks]) to three places, starting at what is
  /// outstanding written the same way. One that cannot be read is refused
  /// under it and nothing is sent: read with a point, Romanian's 1,5 was no
  /// quantity at all.
  final Map<String, TextEditingController> _qty = {};
  final _marks = AmountMarks.ofApp();
  bool _saving = false;
  String? _error;

  bool get _refused =>
      figureRefused(_marks, [for (final c in _qty.values) (c, AmountShape.quantity)]);

  @override
  void dispose() {
    for (final c in _qty.values) {
      c.dispose();
    }
    super.dispose();
  }

  String _fmt(double v) => v == v.roundToDouble() ? v.toInt().toString() : v.toString();

  Future<void> _submit(
      List<OrderLine> lines, Map<String, VariantLabel> labels) async {
    final outstanding = lines.where((l) => l.remainingQty > 0).toList();
    final chosen = <Map<String, dynamic>>[];
    var everything = true;
    if (_refused) return;
    for (final l in outstanding) {
      final c = _qty[l.variantId];
      final plain = c == null ? null : figureOf(c, AmountShape.quantity, _marks);
      if (plain == null) {
        setState(() => _error = 'Enter a quantity for every line (0 for none now).');
        return;
      }
      final v = double.parse(plain);
      if (v > l.remainingQty) {
        setState(() => _error = 'Only ${_fmt(l.remainingQty)} outstanding on '
            '${variantDisplayName(l.variantId, labels)}.');
        return;
      }
      if (v != l.remainingQty) everything = false;
      // The plain decimal typed: JSON-B reads it exactly.
      if (plain != '0') chosen.add({'variantId': l.variantId, 'qty': plain});
    }
    if (chosen.isEmpty) {
      setState(() => _error = 'Nothing to hand over.');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
            '/${ApiConstants.order}/orders/${widget.orderId}/fulfil',
            // Everything outstanding: no body, the plain fulfilment. Part: the lines.
            data: everything ? null : {'lines': chosen},
          );
      widget.onDone();
      if (!mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(everything ? 'Order picked and packed.' : 'Part of the order picked.')));
    } catch (e) {
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'Could not hand over the order.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final detail = ref.watch(orderDetailProvider(widget.orderId));
    // Each line by its product's name (and SKU), as the Return dialog shows
    // them; a short handle only while the names load or for one not found.
    final labels = _lineLabels(ref, detail.value);
    return AlertDialog(
      // Picked and packed, not handed over: the handover to the shopper or the carrier is its
      // own step on the Fulfilment screen (ship-from-store and dark-store picking).
      title: const Text('Picked & packed'),
      content: SizedBox(
        width: 460,
        child: detail.when(
          loading: () => const LoadingView(label: 'Loading lines…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the order.'),
            onRetry: () => ref.invalidate(orderDetailProvider(widget.orderId)),
          ),
          data: (d) {
            final outstanding = d.items.where((l) => l.remainingQty > 0).toList();
            for (final l in outstanding) {
              _qty.putIfAbsent(
                  l.variantId, () => TextEditingController(text: _marks.writeAt(l.remainingQty, 0)));
            }
            // Scrolls: at large text on a phone the lines and their fields
            // outgrow the dialog.
            return SingleChildScrollView(
              child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Text('How much of each line is picked and packed now. '
                    'Leave the outstanding quantities to pick everything.'),
                const SizedBox(height: AppSpacing.md),
                if (outstanding.isEmpty) const Text('Every line is picked.'),
                for (final l in outstanding)
                  Padding(
                    padding: const EdgeInsetsDirectional.only(bottom: AppSpacing.sm),
                    child: Row(children: [
                      Expanded(
                        child: _LineName(
                          name: variantDisplayName(l.variantId, labels),
                          detail: [
                            variantSku(l.variantId, labels),
                            '${_fmt(l.remainingQty)} of ${_fmt(l.qty)} outstanding',
                          ].where((p) => p.isNotEmpty).join(' · '),
                        ),
                      ),
                      const SizedBox(width: AppSpacing.sm),
                      SizedBox(
                        width: 120,
                        child: FigureField(
                          fieldKey: Key('fulfil-qty-${l.variantId}'),
                          controller: _qty[l.variantId]!,
                          shape: AmountShape.quantity,
                          marks: _marks,
                          label: 'Now',
                          hint: '0',
                          onChanged: (_) => setState(() {}),
                        ),
                      ),
                    ]),
                  ),
                if (_error != null) ...[
                  const SizedBox(height: AppSpacing.sm),
                  Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ],
              ],
            ),
            );
          },
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          onPressed: _saving || !detail.hasValue || _refused
              ? null
              : () => _submit(detail.value!.items, labels),
          child: const Text('Picked & packed'),
        ),
      ],
    );
  }
}

/// A manager prices a catalog-mode till order (SJ-D41): every line on it, a
/// unit price each, the VAT for the whole order. The server recomputes the
/// totals and the order becomes PENDING, payable like any other. The catalog
/// till never showed a price, so nothing here is prefilled.
class PriceOrderDialog extends ConsumerStatefulWidget {
  const PriceOrderDialog(
      {super.key, required this.orderId, required this.currency, required this.onDone});
  final String orderId;
  final String currency;
  final VoidCallback onDone;

  @override
  ConsumerState<PriceOrderDialog> createState() => _PriceOrderDialogState();
}

class _PriceOrderDialogState extends ConsumerState<PriceOrderDialog> {
  // Each unit price and the VAT are money in the order's currency, to its
  // places, read the way the app's language writes a number ([AmountMarks])
  // and sent as the decimals typed. One that cannot be read is refused under
  // its field and nothing is sent: read with a point, Romanian's 4,50 was no
  // price at all.
  final Map<String, TextEditingController> _price = {};
  final _tax = TextEditingController(text: '0');
  final _marks = AmountMarks.ofApp();
  late final _money = AmountShape.money(widget.currency);
  bool _saving = false;
  String? _error;

  bool get _refused => figureRefused(
      _marks, [for (final c in _price.values) (c, _money), (_tax, _money)]);

  @override
  void dispose() {
    for (final c in _price.values) {
      c.dispose();
    }
    _tax.dispose();
    super.dispose();
  }

  String _fmt(double v) => v == v.roundToDouble() ? v.toInt().toString() : v.toString();

  Future<void> _submit(List<OrderLine> lines) async {
    if (_refused) return;
    final priced = <Map<String, dynamic>>[];
    for (final l in lines) {
      final c = _price[l.variantId];
      final v = c == null ? null : figureOf(c, _money, _marks);
      if (v == null) {
        setState(() => _error = 'Give every line a price of at least 0.');
        return;
      }
      // The plain decimal typed: JSON-B reads it exactly.
      priced.add({'variantId': l.variantId, 'unitPrice': v});
    }
    final tax = figureOf(_tax, _money, _marks);
    if (tax == null) {
      setState(() => _error = 'VAT must be a number of at least 0.');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
            '/${ApiConstants.order}/orders/${widget.orderId}/price',
            data: {'lines': priced, 'taxAmount': tax},
          );
      widget.onDone();
      if (!mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Order priced — it can be paid for now.')));
    } catch (e) {
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'Could not price the order.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final detail = ref.watch(orderDetailProvider(widget.orderId));
    // Each line by its product's name, so the manager prices what they can
    // recognise rather than a fragment of an id.
    final labels = _lineLabels(ref, detail.value);
    return AlertDialog(
      title: const Text('Price order'),
      content: SizedBox(
        width: 460,
        child: detail.when(
          loading: () => const LoadingView(label: 'Loading lines…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the order.'),
            onRetry: () => ref.invalidate(orderDetailProvider(widget.orderId)),
          ),
          data: (d) {
            for (final l in d.items) {
              _price.putIfAbsent(l.variantId, () => TextEditingController());
            }
            // Scrolls: at large text on a phone the lines and their fields
            // outgrow the dialog.
            return SingleChildScrollView(
              child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Placed at the till without prices. Give each line its unit price in '
                    '${widget.currency}; the totals follow.'),
                const SizedBox(height: AppSpacing.md),
                for (final l in d.items)
                  Padding(
                    padding: const EdgeInsetsDirectional.only(bottom: AppSpacing.sm),
                    child: Row(children: [
                      Expanded(
                        child: _LineName(
                          name: '${variantDisplayName(l.variantId, labels)} × ${_fmt(l.qty)}',
                          detail: variantSku(l.variantId, labels),
                        ),
                      ),
                      const SizedBox(width: AppSpacing.sm),
                      SizedBox(
                        width: 130,
                        child: FigureField(
                          fieldKey: Key('price-${l.variantId}'),
                          controller: _price[l.variantId]!,
                          shape: _money,
                          marks: _marks,
                          label: 'Unit price',
                          onChanged: (_) => setState(() {}),
                        ),
                      ),
                    ]),
                  ),
                FigureField(
                  fieldKey: const Key('price-tax'),
                  controller: _tax,
                  shape: _money,
                  marks: _marks,
                  label: 'VAT on the order',
                  onChanged: (_) => setState(() {}),
                ),
                if (_error != null) ...[
                  const SizedBox(height: AppSpacing.sm),
                  Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ],
              ],
            ),
            );
          },
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          onPressed: _saving || !detail.hasValue || _refused
              ? null
              : () => _submit(detail.value!.items),
          child: const Text('Price and release'),
        ),
      ],
    );
  }
}

/// Voids a completed till sale from the back office (09.13). A reason is
/// required — it goes on the void log the staff exception report reads and on
/// the fiscal receipt, which keeps its number rather than disappearing. The
/// server puts the stock back, and refuses an online order or a sale already
/// voided; the refusal is shown in words and the dialog stays open.
class VoidSaleDialog extends ConsumerStatefulWidget {
  const VoidSaleDialog({super.key, required this.orderId, required this.onDone});
  final String orderId;
  final VoidCallback onDone;

  @override
  ConsumerState<VoidSaleDialog> createState() => _VoidSaleDialogState();
}

class _VoidSaleDialogState extends ConsumerState<VoidSaleDialog> {
  final _reasonCtrl = TextEditingController();
  bool _saving = false;
  String? _error;

  /// The key of the attempt in hand: a retry of the same void (same reason)
  /// reuses it, so the server answers with the first; a new reason is a new attempt.
  String? _key;
  String? _keyFor;

  @override
  void dispose() {
    _reasonCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final reason = _reasonCtrl.text.trim();
    if (reason.isEmpty) {
      setState(() => _error = 'A reason is required.');
      return;
    }
    if (_key == null || _keyFor != reason) {
      _key = newId();
      _keyFor = reason;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
            '/${ApiConstants.order}/orders/${widget.orderId}/void',
            data: {'reason': reason},
            options: Options(headers: {'Idempotency-Key': _key}),
          );
      widget.onDone();
      if (!mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Sale voided. The stock goes back and the receipt keeps its number.')));
    } catch (e) {
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'Could not void the sale.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Void sale?'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('The sale is cancelled after the fact: anything handed over goes back '
                'into stock, and the receipt keeps its number, marked void with this reason. '
                'This cannot be undone.'),
            const SizedBox(height: AppSpacing.md),
            TextField(
              key: const Key('void-reason'),
              controller: _reasonCtrl,
              autofocus: true,
              // The server bounds the reason at 500; stop it here so nothing is sent to be refused.
              maxLength: 500,
              decoration: const InputDecoration(
                labelText: 'Reason',
                hintText: 'e.g. rang up twice',
              ),
            ),
            if (_error != null) ...[
              const SizedBox(height: AppSpacing.sm),
              Text(_error!, style: TextStyle(color: cs.error)),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Keep sale')),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: cs.error, foregroundColor: cs.onError),
          onPressed: _saving ? null : _submit,
          child: const Text('Void sale'),
        ),
      ],
    );
  }
}
