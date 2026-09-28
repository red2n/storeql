import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/util/status_labels.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';
import '../../shared/util/short_ref.dart';

// ── Transfers ────────────────────────────────────────────────────────────────

class InventoryTransfersTab extends ConsumerStatefulWidget {
  const InventoryTransfersTab({super.key});

  @override
  ConsumerState<InventoryTransfersTab> createState() =>
      _InventoryTransfersTabState();
}

class _InventoryTransfersTabState extends ConsumerState<InventoryTransfersTab> {
  String? _storeFilter;

  @override
  Widget build(BuildContext context) {
    final storesAsync = ref.watch(storesProvider);
    final storeNames = <String, String>{
      for (final s in storesAsync.value ?? const <StoreInfo>[]) s.id: s.name,
    };
    final listAsync =
        ref.watch(transferOrdersProvider(_storeFilter ?? ''));

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: EdgeInsetsDirectional.fromSTEB(
              context.pageGutter, AppSpacing.lg, context.pageGutter, 0),
          // A wrapping row: on a phone the button moves under the filter
          // rather than running off the screen.
          child: Wrap(
            spacing: AppSpacing.md,
            runSpacing: AppSpacing.sm,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              SizedBox(
                width: 220,
                child: storesAsync.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (_, _) => const SizedBox.shrink(),
                  data: (stores) => DropdownButtonFormField<String?>(
                    initialValue: _storeFilter,
                    isExpanded: true,
                    decoration: const InputDecoration(
                      labelText: 'Filter by store',
                      isDense: true,
                    ),
                    items: [
                      const DropdownMenuItem(
                          value: null, child: Text('All stores')),
                      ...stores.map((s) => DropdownMenuItem(
                            value: s.id,
                            child: Text(s.name, overflow: TextOverflow.ellipsis),
                          )),
                    ],
                    onChanged: (v) => setState(() => _storeFilter = v),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              IconButton(
                icon: const Icon(Icons.refresh),
                tooltip: 'Refresh transfer orders',
                onPressed: () => ref.invalidate(
                    transferOrdersProvider(_storeFilter ?? '')),
              ),
              FilledButton.icon(
                onPressed: () => _showCreateDialog(context),
                icon: const Icon(Icons.swap_horiz),
                label: const Text('New transfer'),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        Expanded(
          child: listAsync.when(
            loading: () => const LoadingView(label: 'Loading transfers…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load transfers.'),
              onRetry: () => ref.invalidate(
                  transferOrdersProvider(_storeFilter ?? '')),
            ),
            data: (orders) {
              if (orders.isEmpty) {
                return Center(
                  child: Text('No transfer orders',
                      style: Theme.of(context).textTheme.titleMedium),
                );
              }
              return ListView.separated(
                padding: EdgeInsetsDirectional.symmetric(
                    horizontal: context.pageGutter, vertical: AppSpacing.sm),
                itemCount: orders.length,
                separatorBuilder: (_, _) => const Divider(height: 1),
                itemBuilder: (context, i) {
                  final o = orders[i];
                  final from = storeNames[o.fromStoreId] ?? '…${shortRef(o.fromStoreId)}';
                  final to = storeNames[o.toStoreId] ?? '…${shortRef(o.toStoreId)}';
                  return ListTile(
                    leading: Icon(_statusIcon(o.status)),
                    title: Text('$from → $to'),
                    subtitle: Text(
                      '${transferStatusLabel(o.status)} · ${o.lines.length} line${o.lines.length == 1 ? '' : 's'}'
                      '${o.source == 'CROSSDOCK' ? ' · cross-docked from order ${shortRef(o.purchaseOrderId ?? '')}' : o.source == 'PROPOSAL' ? ' · proposed by the warehouse' : ''}'
                      '${o.notes != null && o.notes!.isNotEmpty && o.source != 'CROSSDOCK' ? ' · ${o.notes}' : ''}',
                    ),
                    trailing: _TransferActions(
                      order: o,
                      onChanged: () => ref.invalidate(
                          transferOrdersProvider(_storeFilter ?? '')),
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

  IconData _statusIcon(String status) => switch (status) {
        'DRAFT' => Icons.edit_note_outlined,
        'PENDING' => Icons.hourglass_empty,
        'SHIPPED' => Icons.local_shipping_outlined,
        'RECEIVED' => Icons.check_circle_outline,
        'CANCELLED' => Icons.cancel_outlined,
        _ => Icons.swap_horiz,
      };

  Future<void> _showCreateDialog(BuildContext context) async {
    await showDialog(
      context: context,
      builder: (_) => _CreateTransferDialog(
        onCreated: () =>
            ref.invalidate(transferOrdersProvider(_storeFilter ?? '')),
      ),
    );
  }
}

class _TransferActions extends ConsumerWidget {
  final TransferOrder order;
  final VoidCallback onChanged;

  const _TransferActions({required this.order, required this.onChanged});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final status = order.status;
    return PopupMenuButton<String>(
      onSelected: (a) => _act(context, ref, a),
      itemBuilder: (_) => [
        if (status == 'DRAFT') ...[
          const PopupMenuItem(value: 'release', child: Text('Release')),
          const PopupMenuItem(value: 'cancel', child: Text('Discard')),
        ],
        if (status == 'PENDING') ...[
          const PopupMenuItem(value: 'ship', child: Text('Ship')),
          const PopupMenuItem(value: 'cancel', child: Text('Cancel')),
        ],
        if (status == 'SHIPPED')
          const PopupMenuItem(value: 'receive', child: Text('Receive')),
      ],
    );
  }

  Future<void> _act(BuildContext context, WidgetRef ref, String action) async {
    final path = switch (action) {
      'ship' => 'ship',
      'receive' => 'receive',
      'cancel' => 'cancel',
      'release' => 'release',
      _ => null,
    };
    if (path == null) return;
    try {
      await ref.read(apiClientProvider).dio.post(
            '/${ApiConstants.inventory}/admin/inventory/transfers/${order.id}/$path',
          );
      onChanged();
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(switch (action) {
              'ship' => 'Transfer shipped.',
              'receive' => 'Transfer received.',
              'release' => 'Released: the warehouse can ship it.',
              _ => 'Transfer cancelled.',
            }),
          ),
        );
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
              content: Text(
                  friendlyError(e, fallback: 'Could not $action transfer.'))),
        );
      }
    }
  }
}

class _CreateTransferDialog extends ConsumerStatefulWidget {
  final VoidCallback onCreated;
  const _CreateTransferDialog({required this.onCreated});

  @override
  ConsumerState<_CreateTransferDialog> createState() =>
      _CreateTransferDialogState();
}

class _CreateTransferDialogState extends ConsumerState<_CreateTransferDialog> {
  String? _fromStore;
  String? _toStore;
  final _variantCtrl = TextEditingController();
  final _qtyCtrl = TextEditingController(text: '1');
  final _notesCtrl = TextEditingController();
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _variantCtrl.dispose();
    _qtyCtrl.dispose();
    _notesCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_fromStore == null || _toStore == null) {
      setState(() => _error = 'Select from and to stores.');
      return;
    }
    if (_fromStore == _toStore) {
      setState(() => _error = 'From and to stores must differ.');
      return;
    }
    final variantId = _variantCtrl.text.trim();
    final qty = double.tryParse(_qtyCtrl.text.trim());
    if (variantId.isEmpty || qty == null || qty <= 0) {
      setState(() => _error = 'Variant ID and positive qty are required.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.inventory}/admin/inventory/transfers',
        data: {
          'fromStoreId': _fromStore,
          'toStoreId': _toStore,
          'transferType': 'STANDARD',
          if (_notesCtrl.text.trim().isNotEmpty) 'notes': _notesCtrl.text.trim(),
          'lines': [
            {'variantId': variantId, 'requestedQty': qty},
          ],
        },
      );
      if (!mounted) return;
      widget.onCreated();
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Transfer order created.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not create transfer.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('New transfer order'),
      content: SizedBox(
        width: 420,
        child: storesAsync.when(
          loading: () => const LinearProgressIndicator(),
          error: (e, _) => Text(friendlyError(e, fallback: 'Stores unavailable')),
          data: (stores) => SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                DropdownButtonFormField<String>(
                  initialValue: _fromStore,
                  decoration: const InputDecoration(labelText: 'From store'),
                  items: stores
                      .map((s) => DropdownMenuItem(
                            value: s.id, child: Text(s.name)))
                      .toList(),
                  onChanged: (v) => setState(() => _fromStore = v),
                ),
                const SizedBox(height: 12),
                DropdownButtonFormField<String>(
                  initialValue: _toStore,
                  decoration: const InputDecoration(labelText: 'To store'),
                  items: stores
                      .map((s) => DropdownMenuItem(
                            value: s.id, child: Text(s.name)))
                      .toList(),
                  onChanged: (v) => setState(() => _toStore = v),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _variantCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Variant ID (UUID)',
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _qtyCtrl,
                  keyboardType:
                      const TextInputType.numberWithOptions(decimal: true),
                  decoration: const InputDecoration(labelText: 'Quantity'),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _notesCtrl,
                  decoration: const InputDecoration(labelText: 'Notes (optional)'),
                ),
                if (_error != null) ...[
                  const SizedBox(height: 12),
                  Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ],
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: _loading ? null : () => Navigator.pop(context),
            child: const Text('Cancel')),
        FilledButton(
            onPressed: _loading ? null : _submit,
            child: _loading
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Create')),
      ],
    );
  }
}

// ── Movements ────────────────────────────────────────────────────────────────

class InventoryMovementsTab extends ConsumerStatefulWidget {
  const InventoryMovementsTab({super.key});

  @override
  ConsumerState<InventoryMovementsTab> createState() =>
      _InventoryMovementsTabState();
}

class _InventoryMovementsTabState extends ConsumerState<InventoryMovementsTab> {
  String? _storeId;

  @override
  Widget build(BuildContext context) {
    final storesAsync = ref.watch(storesProvider);
    final listAsync = ref.watch(stockMovementsProvider(_storeId ?? ''));
    final storeNames = <String, String>{
      for (final s in storesAsync.value ?? const <StoreInfo>[]) s.id: s.name,
    };

    return Column(
      children: [
        Padding(
          padding: EdgeInsetsDirectional.fromSTEB(
              context.pageGutter, AppSpacing.lg, context.pageGutter, 0),
          child: Row(
            children: [
              SizedBox(
                width: 240,
                child: storesAsync.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (_, _) => const SizedBox.shrink(),
                  data: (stores) => DropdownButtonFormField<String?>(
                    initialValue: _storeId,
                    isExpanded: true,
                    decoration: const InputDecoration(
                      labelText: 'Store',
                      isDense: true,
                    ),
                    items: [
                      const DropdownMenuItem(
                          value: null, child: Text('All stores')),
                      ...stores.map((s) => DropdownMenuItem(
                            value: s.id,
                            child: Text(s.name, overflow: TextOverflow.ellipsis),
                          )),
                    ],
                    onChanged: (v) => setState(() => _storeId = v),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              IconButton(
                icon: const Icon(Icons.refresh),
                tooltip: 'Refresh stock movements',
                onPressed: () =>
                    ref.invalidate(stockMovementsProvider(_storeId ?? '')),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        Expanded(
          child: listAsync.when(
            loading: () => const LoadingView(label: 'Loading movements…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load movements.'),
              onRetry: () =>
                  ref.invalidate(stockMovementsProvider(_storeId ?? '')),
            ),
            data: (rows) {
              if (rows.isEmpty) {
                return Center(
                  child: Text('No movements yet',
                      style: Theme.of(context).textTheme.titleMedium),
                );
              }
              // Each movement by its product's name; the end of its id only
              // while the names load.
              final labels = ref
                      .watch(variantLabelsProvider(
                          variantIdsKey(rows.map((m) => m.variantId))))
                      .value ??
                  const <String, VariantLabel>{};
              return ListView.separated(
                padding: EdgeInsetsDirectional.symmetric(
                    horizontal: context.pageGutter, vertical: AppSpacing.sm),
                itemCount: rows.length,
                separatorBuilder: (_, _) => const Divider(height: 1),
                itemBuilder: (context, i) {
                  final m = rows[i];
                  final store = storeNames[m.storeId] ?? '…${shortRef(m.storeId)}';
                  final sign = m.qty >= 0 ? '+' : '';
                  return ListTile(
                    dense: true,
                    title: Text('${movementTypeLabel(m.type)}  $sign${AppFormat.count(m.qty)}'),
                    subtitle: Text(
                      '$store · ${variantDisplayName(m.variantId, labels)}'
                      '${m.createdAt != null ? ' · ${AppFormat.dateTime(m.createdAt)}' : ''}',
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

/// A transfer's status in words.
String transferStatusLabel(String status) => switch (status.toUpperCase()) {
      'DRAFT' => 'Proposed',
      'PENDING' => 'Pending',
      'SHIPPED' => 'Shipped',
      'RECEIVED' => 'Received',
      'CANCELLED' => 'Cancelled',
      _ => humanizeCode(status),
    };

/// A stock movement's kind in words.
String movementTypeLabel(String type) => switch (type.toUpperCase()) {
      'SALE' => 'Sale',
      'RECEIPT' => 'Receipt',
      'ADJUST' => 'Adjustment',
      'TRANSFER_OUT' => 'Transfer out',
      'TRANSFER_IN' => 'Transfer in',
      'RETURN' => 'Return',
      'YIELD' => 'Breakdown',
      'BOND_RELEASE' => 'Released from bond',
      _ => humanizeCode(type),
    };

// ── Material status dialog ───────────────────────────────────────────────────

Future<void> showMaterialStatusDialog(
  BuildContext context,
  WidgetRef ref, {
  required BatchInfo batch,
  required VoidCallback onChanged,
}) async {
  String status = batch.materialStatus == '-' ? 'AVAILABLE' : batch.materialStatus;
  final reasonCtrl = TextEditingController(text: batch.materialStatusReason ?? '');
  String? error;
  var loading = false;

  await showDialog(
    context: context,
    builder: (ctx) => StatefulBuilder(
      builder: (ctx, setLocal) => AlertDialog(
        title: Text('Material status · ${batch.batchNo}'),
        content: SizedBox(
          width: 360,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<String>(
                initialValue: status,
                decoration: const InputDecoration(labelText: 'Status'),
                // The words the Batches badges and filter use.
                items: [
                  for (final m in batchMaterialStatuses)
                    DropdownMenuItem(value: m, child: Text(materialStatusLabel(m))),
                ],
                onChanged: (v) => setLocal(() => status = v ?? status),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: reasonCtrl,
                decoration: const InputDecoration(labelText: 'Reason'),
              ),
              if (error != null) ...[
                const SizedBox(height: 12),
                Text(error!,
                    style: TextStyle(color: Theme.of(ctx).colorScheme.error)),
              ],
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: loading ? null : () => Navigator.pop(ctx),
              child: const Text('Cancel')),
          FilledButton(
            onPressed: loading
                ? null
                : () async {
                    setLocal(() {
                      loading = true;
                      error = null;
                    });
                    try {
                      await ref.read(apiClientProvider).dio.put(
                        '/${ApiConstants.inventory}/admin/inventory/batches/${batch.id}/material-status',
                        data: {
                          'materialStatus': status,
                          if (reasonCtrl.text.trim().isNotEmpty)
                            'reason': reasonCtrl.text.trim(),
                        },
                      );
                      onChanged();
                      if (ctx.mounted) Navigator.pop(ctx);
                      if (context.mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('Material status updated.')),
                        );
                      }
                    } catch (e) {
                      setLocal(() {
                        loading = false;
                        error = friendlyError(e,
                            fallback: 'Could not update material status.');
                      });
                    }
                  },
            child: loading
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Save'),
          ),
        ],
      ),
    ),
  );
  reasonCtrl.dispose();
}
