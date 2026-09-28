import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/format.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/reference_fields.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';
import 'providers/admin_providers.dart';
import 'receipts_tab.dart';
import 'sales_providers.dart';
import 'widgets/variant_picker.dart';
import '../../shared/util/short_ref.dart';
import 'package:storeql_app/core/ids.dart';

class SalesScreen extends ConsumerWidget {
  const SalesScreen({super.key, this.initialTab});

  /// The tab to open on, by its address name (`/admin/sales?tab=receipts`); the first when null
  /// or one this screen does not have.
  final String? initialTab;

  /// Each tab's name in an address, in the order the tabs are shown.
  static const tabNames = ['gift-cards', 'layaways', 'special-orders', 'receipts'];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // One inset for the title, the tabs' labels and what each tab shows: 16 on
    // a phone, 24 from tablet width.
    final gutter = context.pageGutter;
    final start = tabNames.indexOf(initialTab ?? '');
    return DefaultTabController(
      // A link to another tab while Sales tools is open starts it again on that tab.
      key: ValueKey(initialTab),
      length: tabNames.length,
      initialIndex: start < 0 ? 0 : start,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          PageHeader(
            title: 'Sales tools',
            padding: EdgeInsetsDirectional.fromSTEB(
                gutter, gutter, gutter, AppSpacing.sm),
          ),
          TabBar(
            isScrollable: true,
            tabAlignment: TabAlignment.start,
            // A tab's label is padded 16 on each side, so the first label's
            // text starts on the gutter.
            padding: EdgeInsetsDirectional.only(start: gutter - AppSpacing.lg),
            tabs: const [
              Tab(text: 'Gift Cards'),
              Tab(text: 'Layaways'),
              Tab(text: 'Special Orders'),
              Tab(text: 'Receipts'),
            ],
          ),
          const Expanded(
            child: TabBarView(
              children: [
                _GiftCardsTab(),
                _LayawaysTab(),
                _SpecialOrdersTab(),
                ReceiptsTab(),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ── Gift cards ───────────────────────────────────────────────────────────────

class _GiftCardsTab extends ConsumerStatefulWidget {
  const _GiftCardsTab();

  @override
  ConsumerState<_GiftCardsTab> createState() => _GiftCardsTabState();
}

class _GiftCardsTabState extends ConsumerState<_GiftCardsTab> {
  final _codeCtrl = TextEditingController();
  GiftCard? _card;
  List<GiftCardTxn> _txns = [];
  bool _loading = false;
  bool _submitting = false;
  String? _error;

  @override
  void dispose() {
    _codeCtrl.dispose();
    super.dispose();
  }

  Future<void> _lookup() async {
    final code = _codeCtrl.text.trim();
    if (code.isEmpty) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final dio = ref.read(apiClientProvider).dio;
      final cardResp = await dio.get('/${ApiConstants.order}/gift-cards/$code');
      final txnResp =
          await dio.get('/${ApiConstants.order}/gift-cards/$code/transactions');
      setState(() {
        _card = GiftCard.fromJson(cardResp.data['data'] as Map<String, dynamic>);
        _txns = ((txnResp.data['data'] as List?) ?? [])
            .map((e) => GiftCardTxn.fromJson(e as Map<String, dynamic>))
            .toList();
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _loading = false;
        _card = null;
        _error = (e is DioException && e.response?.statusCode == 404)
            ? 'No gift card with that code.'
            : friendlyError(e, fallback: 'Lookup failed.');
      });
    }
  }

  Future<void> _reloadOrRedeem(String action) async {
    if (_submitting) return;
    final card = _card;
    if (card == null) return;
    final reload = action == 'reload';
    // Value put on a card says how it was paid for: the card is a liability against it (17.11).
    final value = await _valueDialog(
        context, reload ? 'Reload gift card' : 'Redeem gift card',
        askPaidBy: reload);
    if (value == null || !mounted) return;
    setState(() => _submitting = true);
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.order}/gift-cards/${card.code}/$action',
        data: {'amount': value.amount, if (reload) 'paidBy': value.paidBy},
      );
      await _lookup();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content:
              Text(friendlyError(e, fallback: 'Could not update gift card.')),
          backgroundColor: Theme.of(context).colorScheme.error));
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final card = _card;
    return ListView(
      padding: context.pagePadding,
      children: [
        _LookupBar(
          field: TextField(
            controller: _codeCtrl,
            decoration: const InputDecoration(
              labelText: 'Gift card code',
              prefixIcon: Icon(Icons.card_giftcard),
            ),
            onSubmitted: (_) => _lookup(),
          ),
          actions: [
            FilledButton(onPressed: _lookup, child: const Text('Look up')),
            OutlinedButton.icon(
              onPressed: () => showDialog(
                  context: context, builder: (_) => const _IssueGiftCardDialog()),
              icon: const Icon(Icons.add),
              label: const Text('Issue'),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        if (_loading) const LinearProgressIndicator(),
        if (_error != null)
          Text(_error!, style: TextStyle(color: cs.error)),
        if (card != null) ...[
          Card(
            child: Padding(
              padding: AppSpacing.cardPadding,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Wrap(
                    spacing: AppSpacing.sm,
                    runSpacing: AppSpacing.xs,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      Text(card.code,
                          style: theme.textTheme.titleLarge?.copyWith(
                              fontFamily: 'monospace',
                              fontWeight: FontWeight.bold)),
                      StatusBadge(_giftCardStatusLabel(card.status),
                          tone: _giftCardStatusTone(card.status)),
                    ],
                  ),
                  const SizedBox(height: AppSpacing.sm),
                  Text(
                      'Balance: ${AppFormat.money(card.currentBalance, currencyCode: card.currency)}',
                      style: theme.textTheme.titleMedium),
                  if (card.expiresAt != null)
                    Text('Expires ${AppFormat.date(card.expiresAt)}',
                        style: theme.textTheme.bodyMedium
                            ?.copyWith(color: cs.onSurfaceVariant)),
                  const SizedBox(height: AppSpacing.md),
                  Wrap(
                    spacing: AppSpacing.sm,
                    runSpacing: AppSpacing.sm,
                    children: [
                      OutlinedButton.icon(
                        onPressed: _submitting ? null : () => _reloadOrRedeem('reload'),
                        icon: const Icon(Icons.add, size: 18),
                        label: const Text('Reload'),
                      ),
                      OutlinedButton.icon(
                        onPressed: _submitting ? null : () => _reloadOrRedeem('redeem'),
                        icon: const Icon(Icons.remove, size: 18),
                        label: const Text('Redeem'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: AppSpacing.md),
          Text('Transactions', style: theme.textTheme.labelLarge),
          for (final t in _txns) _txnTile(context, t, card.currency),
        ],
      ],
    );
  }

  /// One line of the card's ledger. Whether it put value on the card or took
  /// it off is the transaction's type: order-svc stores every amount as a
  /// positive number, a redemption included.
  Widget _txnTile(BuildContext context, GiftCardTxn t, String currency) {
    final cs = Theme.of(context).colorScheme;
    final adds = _giftCardTxnAdds(t.txType);
    final amount = AppFormat.money(adds ? t.amount.abs() : -t.amount.abs(),
        currencyCode: currency);
    final after =
        'balance ${AppFormat.money(t.balanceAfter, currencyCode: currency)}';
    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: Icon(adds ? Icons.arrow_upward : Icons.arrow_downward,
          size: 16, color: adds ? context.status.success : cs.onSurfaceVariant),
      title: Text(_giftCardTxnLabel(t.txType)),
      // When it happened and what it left on the card; the subtitle wraps, so
      // only the amount has to fit beside the words.
      subtitle: Text(t.createdAt.isEmpty
          ? after
          : '${AppFormat.dateTime(t.createdAt)} · $after'),
      trailing: Text('${adds ? '+' : ''}$amount',
          style: const TextStyle(fontWeight: FontWeight.w600)),
    );
  }
}

/// A gift card's state in words.
String _giftCardStatusLabel(String status) =>
    switch (status.toUpperCase()) {
      'ACTIVE' => 'Active',
      'DEPLETED' => 'Used up',
      'CANCELLED' => 'Cancelled',
      _ => humanizeCode(status),
    };

StatusTone _giftCardStatusTone(String status) =>
    switch (status.toUpperCase()) {
      'ACTIVE' => StatusTone.success,
      _ => StatusTone.neutral,
    };

/// What a gift card transaction did, in words.
String _giftCardTxnLabel(String txType) => switch (txType.toUpperCase()) {
      'ISSUE' => 'Issued',
      'RELOAD' => 'Reloaded',
      'REDEEM' => 'Redeemed',
      'REFUND' => 'Refunded to the card',
      'CANCEL' => 'Cancelled',
      _ => humanizeCode(txType),
    };

/// Whether a transaction put value on the card (an issue, a reload, a refund
/// back to it) rather than taking it off (a redemption, a cancellation).
bool _giftCardTxnAdds(String txType) => switch (txType.toUpperCase()) {
      'ISSUE' || 'RELOAD' || 'REFUND' => true,
      _ => false,
    };

/// A lookup field and its buttons. From tablet width they share a row; on a
/// phone the buttons go under the field, which keeps the whole width for its
/// label and the code typed into it.
class _LookupBar extends StatelessWidget {
  final Widget field;
  final List<Widget> actions;

  const _LookupBar({required this.field, required this.actions});

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      if (AppBreakpoints.classOf(constraints.maxWidth) == WindowClass.compact) {
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            field,
            const SizedBox(height: AppSpacing.md),
            Wrap(
              spacing: AppSpacing.sm,
              runSpacing: AppSpacing.sm,
              children: actions,
            ),
          ],
        );
      }
      return Row(
        children: [
          Expanded(child: field),
          for (final a in actions) ...[
            const SizedBox(width: AppSpacing.sm),
            a,
          ],
        ],
      );
    });
  }
}

class _IssueGiftCardDialog extends ConsumerStatefulWidget {
  const _IssueGiftCardDialog();

  @override
  ConsumerState<_IssueGiftCardDialog> createState() =>
      _IssueGiftCardDialogState();
}

class _IssueGiftCardDialogState extends ConsumerState<_IssueGiftCardDialog> {
  String? _storeId;
  final _amountCtrl = TextEditingController();
  String? _currency;
  String? _paidBy;
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _amountCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final amount = double.tryParse(_amountCtrl.text.trim());
    if (_storeId == null || amount == null || amount <= 0 || _paidBy == null) {
      setState(() => _error = 'Pick a store, enter an amount and say how it was paid for.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.order}/gift-cards',
        data: {
          'storeId': _storeId,
          'amount': amount,
          // Omitted, order-svc issues it in the tenant's own currency (SJ-D53).
          if (_currency != null) 'currency': _currency,
          'paidBy': _paidBy,
        },
      );
      final card = resp.data['data'] as Map<String, dynamic>;
      if (!mounted) return;
      Navigator.pop(context);
      showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          icon: const Icon(Icons.card_giftcard, size: 36),
          title: const Text('Gift card issued'),
          content: SelectableText(card['code'] as String? ?? '',
              style: const TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 20,
                  fontWeight: FontWeight.bold)),
          actions: [
            FilledButton(
                onPressed: () => Navigator.pop(ctx), child: const Text('Done')),
          ],
        ),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not issue gift card.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('Issue gift card'),
      content: SizedBox(
        width: 360,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (_error != null) ...[
              Text(_error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error)),
              const SizedBox(height: 8),
            ],
            storesAsync.when(
              loading: () => const LinearProgressIndicator(),
              error: (e, _) =>
                  Text(friendlyError(e, fallback: 'Could not load stores.')),
              data: (stores) => DropdownButtonFormField<String>(
                initialValue: _storeId,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Store *'),
                items: [
                  for (final s in stores)
                    DropdownMenuItem(value: s.id, child: Text(s.name)),
                ],
                onChanged: (v) => setState(() => _storeId = v),
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _amountCtrl,
                    keyboardType:
                        const TextInputType.numberWithOptions(decimal: true),
                    decoration: const InputDecoration(labelText: 'Amount'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: CurrencyField(
                    value: _currency ?? ref.watch(tenantInfoProvider).value?.currency,
                    onChanged: (v) => setState(() => _currency = v),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            _PaidByField(
                value: _paidBy, onChanged: (v) => setState(() => _paidBy = v)),
          ],
        ),
      ),
      actions: _actions(context, _loading, _submit, 'Issue'),
    );
  }
}

// ── Layaways ─────────────────────────────────────────────────────────────────

class _LayawaysTab extends ConsumerStatefulWidget {
  const _LayawaysTab();

  @override
  ConsumerState<_LayawaysTab> createState() => _LayawaysTabState();
}

class _LayawaysTabState extends ConsumerState<_LayawaysTab> {
  /// A layaway's sums carry no currency: they are in the business's own,
  /// which its tills charge in.
  String _layawayMoney(double v) => AppFormat.money(v,
      currencyCode: ref.watch(tenantInfoProvider).value?.currency);

  final _idCtrl = TextEditingController();
  Layaway? _layaway;
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _idCtrl.dispose();
    super.dispose();
  }

  Future<void> _lookup([String? id]) async {
    final lid = (id ?? _idCtrl.text).trim();
    if (lid.isEmpty) return;
    _idCtrl.text = lid;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final resp =
          await ref.read(apiClientProvider).dio.get('/${ApiConstants.order}/layaways/$lid');
      setState(() {
        _layaway = Layaway.fromJson(resp.data['data'] as Map<String, dynamic>);
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _loading = false;
        _layaway = null;
        _error = (e is DioException && e.response?.statusCode == 404)
            ? 'No layaway with that id.'
            : friendlyError(e, fallback: 'Could not look up layaway.');
      });
    }
  }

  Future<void> _action(String action) async {
    final l = _layaway;
    if (l == null) return;
    final dio = ref.read(apiClientProvider).dio;
    try {
      if (action == 'deposit') {
        final amount = await _amountDialog(context, 'Add deposit');
        if (amount == null) return;
        await dio.post('/${ApiConstants.order}/layaways/${l.id}/deposits',
            data: {'amount': amount, 'paymentMethod': 'CASH'});
      } else {
        await dio.post('/${ApiConstants.order}/layaways/${l.id}/$action',
            data: action == 'cancel' ? {'reason': 'Cancelled by staff'} : null);
      }
      await _lookup(l.id);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content:
              Text(friendlyError(e, fallback: 'Could not update layaway.')),
          backgroundColor: Theme.of(context).colorScheme.error));
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final l = _layaway;
    return ListView(
      padding: context.pagePadding,
      children: [
        _LookupBar(
          field: TextField(
            controller: _idCtrl,
            decoration: const InputDecoration(
                labelText: 'Layaway id', prefixIcon: Icon(Icons.search)),
            onSubmitted: (_) => _lookup(),
          ),
          actions: [
            FilledButton(onPressed: () => _lookup(), child: const Text('Look up')),
            OutlinedButton.icon(
              onPressed: () => showDialog<String>(
                context: context,
                builder: (_) => const _CreateLayawayDialog(),
              ).then((newId) {
                if (newId != null) _lookup(newId);
              }),
              icon: const Icon(Icons.add),
              label: const Text('New'),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        if (_loading) const LinearProgressIndicator(),
        if (_error != null) Text(_error!, style: TextStyle(color: cs.error)),
        if (l != null)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('#${shortRef(l.id)}',
                      style: const TextStyle(fontFamily: 'monospace')),
                  const SizedBox(height: 8),
                  Text('Total: ${_layawayMoney(l.totalAmount)}'),
                  Text('Paid: ${_layawayMoney(l.depositPaid)}'),
                  Text('Balance: ${_layawayMoney(l.balance)}',
                      style: const TextStyle(fontWeight: FontWeight.bold)),
                  const SizedBox(height: AppSpacing.xs),
                  StatusBadge(
                    _layawayStatusWords(l.status),
                    tone: _layawayStatusTone(l.status),
                  ),
                  const SizedBox(height: 12),
                  if (l.status.toUpperCase() == 'ACTIVE')
                    Wrap(
                      spacing: 8,
                      children: [
                        OutlinedButton(
                            onPressed: () => _action('deposit'),
                            child: const Text('Add deposit')),
                        OutlinedButton(
                            onPressed: () => _action('complete'),
                            child: const Text('Complete')),
                        OutlinedButton(
                            onPressed: () => _action('cancel'),
                            child: const Text('Cancel')),
                      ],
                    ),
                ],
              ),
            ),
          ),
      ],
    );
  }
}

class _CreateLayawayDialog extends ConsumerStatefulWidget {
  const _CreateLayawayDialog();

  @override
  ConsumerState<_CreateLayawayDialog> createState() =>
      _CreateLayawayDialogState();
}

class _CreateLayawayDialogState extends ConsumerState<_CreateLayawayDialog> {
  String? _storeId;
  final _depositCtrl = TextEditingController();
  final List<Map<String, dynamic>> _items = [];
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _depositCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final deposit = double.tryParse(_depositCtrl.text.trim());
    if (_storeId == null || _items.isEmpty || deposit == null || deposit <= 0) {
      setState(() => _error = 'Pick a store, add items, and enter a deposit.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.order}/layaways',
        data: {
          'storeId': _storeId,
          'items': _items,
          'initialDeposit': deposit,
          'paymentMethod': 'CASH',
        },
      );
      final id = (resp.data['data'] as Map<String, dynamic>)['id'] as String?;
      if (!mounted) return;
      Navigator.pop(context, id);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not create layaway.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('New layaway'),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                Text(_error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
                const SizedBox(height: 8),
              ],
              storesAsync.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) =>
                    Text(friendlyError(e, fallback: 'Could not load stores.')),
                data: (stores) => DropdownButtonFormField<String>(
                  initialValue: _storeId,
                  isExpanded: true,
                  decoration: const InputDecoration(labelText: 'Store *'),
                  items: [
                    for (final s in stores)
                      DropdownMenuItem(value: s.id, child: Text(s.name)),
                  ],
                  onChanged: (v) => setState(() => _storeId = v),
                ),
              ),
              const SizedBox(height: 12),
              _LineItemsEditor(
                items: _items,
                onChanged: () => setState(() {}),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _depositCtrl,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(labelText: 'Initial deposit'),
              ),
            ],
          ),
        ),
      ),
      actions: _actions(context, _loading, _submit, 'Create'),
    );
  }
}

// ── Special orders ───────────────────────────────────────────────────────────

class _SpecialOrdersTab extends ConsumerWidget {
  const _SpecialOrdersTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(specialOrdersProvider);
    final cs = Theme.of(context).colorScheme;
    return Column(
      children: [
        Padding(
          padding: EdgeInsetsDirectional.fromSTEB(
              context.pageGutter, AppSpacing.md, context.pageGutter, 0),
          child: Row(
            children: [
              const Spacer(),
              FilledButton.icon(
                onPressed: () => showDialog(
                    context: context,
                    builder: (_) => const _CreateSpecialOrderDialog()),
                icon: const Icon(Icons.add),
                label: const Text('New special order'),
              ),
            ],
          ),
        ),
        Expanded(
          child: async.when(
            loading: () => const LoadingView(label: 'Loading special orders…'),
            error: (e, _) => ErrorView(
              message:
                  friendlyError(e, fallback: 'Could not load special orders.'),
              onRetry: () => ref.invalidate(specialOrdersProvider),
            ),
            data: (orders) {
              if (orders.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.assignment_outlined,
                          size: 64, color: cs.outlineVariant),
                      const SizedBox(height: 12),
                      const Text('No special orders yet'),
                    ],
                  ),
                );
              }
              return ListView.separated(
                padding: context.pagePadding,
                itemCount: orders.length,
                separatorBuilder: (_, _) => const SizedBox(height: 4),
                itemBuilder: (_, i) {
                  final o = orders[i];
                  return Card(
                    child: ListTile(
                      title: Text(o.customerName ?? 'Special order',
                          style: const TextStyle(fontWeight: FontWeight.bold)),
                      subtitle: Text([
                        AppFormat.money(o.total, currencyCode: o.currency),
                        if (o.requestedDeliveryDate != null)
                          'due ${AppFormat.date(o.requestedDeliveryDate)}',
                      ].join(' · ')),
                      trailing: _SpecialOrderActions(order: o),
                    ),
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }
}

class _SpecialOrderActions extends ConsumerWidget {
  final SpecialOrder order;
  const _SpecialOrderActions({required this.order});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final s = order.status.toUpperCase();
    final actions = <String>[];
    if (s == 'PENDING' || s == 'DRAFT') actions.add('confirm');
    if (s == 'CONFIRMED') actions.add('fulfil');
    if (s != 'CANCELLED' && s != 'FULFILLED') actions.add('cancel');
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        StatusBadge(
          _specialOrderStatusWords(order.status),
          tone: _specialOrderStatusTone(order.status),
        ),
        if (actions.isNotEmpty)
          PopupMenuButton<String>(
            icon: const Icon(Icons.more_vert),
            itemBuilder: (_) => [
              for (final a in actions)
                PopupMenuItem(value: a, child: Text(a[0].toUpperCase() + a.substring(1))),
            ],
            onSelected: (a) async {
              try {
                await ref.read(apiClientProvider).dio.post(
                    '/${ApiConstants.order}/admin/special-orders/${order.id}/$a');
                ref.invalidate(specialOrdersProvider);
              } catch (e) {
                if (!context.mounted) return;
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                    content: Text(friendlyError(e,
                        fallback: 'Could not update special order.')),
                    backgroundColor: Theme.of(context).colorScheme.error));
              }
            },
          ),
      ],
    );
  }
}

class _CreateSpecialOrderDialog extends ConsumerStatefulWidget {
  const _CreateSpecialOrderDialog();

  @override
  ConsumerState<_CreateSpecialOrderDialog> createState() =>
      _CreateSpecialOrderDialogState();
}

class _CreateSpecialOrderDialogState
    extends ConsumerState<_CreateSpecialOrderDialog> {
  String? _storeId;
  final _nameCtrl = TextEditingController();
  final _phoneCtrl = TextEditingController();
  final List<Map<String, dynamic>> _items = [];
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _nameCtrl.dispose();
    _phoneCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_storeId == null || _items.isEmpty) {
      setState(() => _error = 'Pick a store and add at least one item.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.order}/admin/special-orders',
        data: {
          'storeId': _storeId,
          'customerName': _nameCtrl.text.trim(),
          'customerPhone': _phoneCtrl.text.trim(),
          'items': _items,
        },
        options: Options(headers: {
          'Idempotency-Key': newId()
        }),
      );
      if (!mounted) return;
      ref.invalidate(specialOrdersProvider);
      Navigator.pop(context);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not create special order.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('New special order'),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                Text(_error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
                const SizedBox(height: 8),
              ],
              storesAsync.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) =>
                    Text(friendlyError(e, fallback: 'Could not load stores.')),
                data: (stores) => DropdownButtonFormField<String>(
                  initialValue: _storeId,
                  isExpanded: true,
                  decoration: const InputDecoration(labelText: 'Store *'),
                  items: [
                    for (final s in stores)
                      DropdownMenuItem(value: s.id, child: Text(s.name)),
                  ],
                  onChanged: (v) => setState(() => _storeId = v),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _nameCtrl,
                decoration: const InputDecoration(labelText: 'Customer name'),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _phoneCtrl,
                decoration: const InputDecoration(labelText: 'Customer phone'),
              ),
              const SizedBox(height: 12),
              _LineItemsEditor(items: _items, onChanged: () => setState(() {})),
            ],
          ),
        ),
      ),
      actions: _actions(context, _loading, _submit, 'Create'),
    );
  }
}

// ── Shared: line-items editor (variant + qty + price) ────────────────────────

class _LineItemsEditor extends ConsumerStatefulWidget {
  final List<Map<String, dynamic>> items;
  final VoidCallback onChanged;
  const _LineItemsEditor({required this.items, required this.onChanged});

  @override
  ConsumerState<_LineItemsEditor> createState() => _LineItemsEditorState();
}

class _LineItemsEditorState extends ConsumerState<_LineItemsEditor> {
  String? _productId;
  String? _variantId;
  final _qtyCtrl = TextEditingController(text: '1');
  final _priceCtrl = TextEditingController();

  @override
  void dispose() {
    _qtyCtrl.dispose();
    _priceCtrl.dispose();
    super.dispose();
  }

  void _add() {
    final qty = double.tryParse(_qtyCtrl.text.trim());
    final price = double.tryParse(_priceCtrl.text.trim());
    if (_variantId == null || qty == null || qty <= 0 || price == null || price <= 0) {
      return;
    }
    widget.items.add({'variantId': _variantId, 'qty': qty, 'unitPrice': price});
    _variantId = null;
    _priceCtrl.clear();
    _qtyCtrl.text = '1';
    widget.onChanged();
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('Items', style: Theme.of(context).textTheme.labelLarge),
        for (final it in widget.items)
          ListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: Text(
                '…${shortRef(it['variantId'] as String)} × ${it['qty']}',
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
            trailing: IconButton(
              icon: Icon(Icons.delete_outline, size: 18, color: cs.error),
              tooltip: 'Remove item',
              onPressed: () {
                widget.items.remove(it);
                widget.onChanged();
                setState(() {});
              },
            ),
          ),
        VariantPicker(
          productId: _productId,
          variantId: _variantId,
          onProduct: (p) => setState(() {
            _productId = p;
            _variantId = null;
          }),
          onVariant: (v) => setState(() => _variantId = v),
        ),
        const SizedBox(height: 8),
        Row(
          children: [
            Expanded(
              child: TextField(
                controller: _qtyCtrl,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Qty', isDense: true),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: TextField(
                controller: _priceCtrl,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(labelText: 'Price', isDense: true),
              ),
            ),
            const SizedBox(width: 8),
            IconButton.filledTonal(
                onPressed: _add, icon: const Icon(Icons.add)),
          ],
        ),
      ],
    );
  }
}

/// A layaway's status in words.
String _layawayStatusWords(String status) =>
    switch (status.toUpperCase()) {
      'ACTIVE' => 'Active',
      'COMPLETED' => 'Completed',
      'CANCELLED' => 'Cancelled',
      _ => humanizeCode(status),
    };

StatusTone _layawayStatusTone(String status) =>
    switch (status.toUpperCase()) {
      'ACTIVE' => StatusTone.info,
      'COMPLETED' => StatusTone.success,
      _ => StatusTone.neutral,
    };

/// A special order's status in words.
String _specialOrderStatusWords(String status) =>
    switch (status.toUpperCase()) {
      'DRAFT' => 'Draft',
      'PENDING' => 'Pending',
      'CONFIRMED' => 'Confirmed',
      'FULFILLED' => 'Fulfilled',
      'CANCELLED' => 'Cancelled',
      _ => humanizeCode(status),
    };

StatusTone _specialOrderStatusTone(String status) =>
    switch (status.toUpperCase()) {
      'DRAFT' || 'PENDING' || 'CONFIRMED' => StatusTone.info,
      'FULFILLED' => StatusTone.success,
      _ => StatusTone.neutral,
    };

// ── Shared helpers ───────────────────────────────────────────────────────────

Future<double?> _amountDialog(BuildContext context, String title) async =>
    (await _valueDialog(context, title, askPaidBy: false))?.amount;

/// An amount and, for value put on a gift card, how it was paid for (17.11).
Future<({double amount, String? paidBy})?> _valueDialog(
        BuildContext context, String title,
        {required bool askPaidBy}) =>
    showDialog<({double amount, String? paidBy})>(
      context: context,
      builder: (_) => _ValueDialog(title: title, askPaidBy: askPaidBy),
    );

class _ValueDialog extends StatefulWidget {
  final String title;
  final bool askPaidBy;
  const _ValueDialog({required this.title, required this.askPaidBy});

  @override
  State<_ValueDialog> createState() => _ValueDialogState();
}

class _ValueDialogState extends State<_ValueDialog> {
  // Owned by the dialog, so it outlives the closing animation that still draws it.
  final _amount = TextEditingController();
  String? _paidBy;

  @override
  void dispose() {
    _amount.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: _amount,
            autofocus: true,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(labelText: 'Amount'),
          ),
          if (widget.askPaidBy) ...[
            const SizedBox(height: 12),
            _PaidByField(
                value: _paidBy, onChanged: (v) => setState(() => _paidBy = v)),
          ],
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          onPressed: () {
            final v = double.tryParse(_amount.text.trim());
            if (v != null && v > 0 && (!widget.askPaidBy || _paidBy != null)) {
              Navigator.pop(context, (amount: v, paidBy: _paidBy));
            }
          },
          child: const Text('OK'),
        ),
      ],
    );
  }
}

/// How value put on a gift card was paid for (17.11). A card sold is a liability
/// against the money taken and one given away is a marketing cost, so the ledger
/// has to know which; another card, a voucher or store credit is not offered.
class _PaidByField extends StatelessWidget {
  final String? value;
  final ValueChanged<String?> onChanged;
  const _PaidByField({required this.value, required this.onChanged});

  static const _options = [
    ('CASH', 'Cash'),
    ('CARD', 'Card'),
    ('UPI', 'UPI'),
    ('WALLET', 'Wallet'),
    ('PROMOTIONAL', 'Given away (promotional)'),
  ];

  @override
  Widget build(BuildContext context) => DropdownButtonFormField<String>(
        key: const Key('gift-card-paid-by'),
        initialValue: value,
        isExpanded: true,
        decoration: const InputDecoration(labelText: 'Paid by *'),
        items: [
          for (final (code, label) in _options)
            DropdownMenuItem(value: code, child: Text(label)),
        ],
        onChanged: onChanged,
      );
}

List<Widget> _actions(
    BuildContext context, bool loading, VoidCallback onSubmit, String label) {
  return [
    TextButton(
      onPressed: loading ? null : () => Navigator.pop(context),
      child: const Text('Cancel'),
    ),
    FilledButton(
      onPressed: loading ? null : onSubmit,
      child: loading
          ?  SizedBox(
              height: 18,
              width: 18,
              child:
                  CircularProgressIndicator(strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
          : Text(label),
    ),
  ];
}
