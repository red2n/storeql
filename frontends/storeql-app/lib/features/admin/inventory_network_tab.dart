import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/ids.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';
import 'widgets/variant_names.dart';
import 'widgets/variant_picker.dart';

// ---------------------------------------------------------------------------
// Depot / DC replenishment. A business says which warehouse serves which
// shop; a shop can still buy some products direct. The warehouse proposes
// transfers to the shops below their reorder point, sharing fairly when it is
// short, and a person releases each one for the warehouse to ship.
// ---------------------------------------------------------------------------

class ServingInfo {
  final String storeId;
  final String warehouseId;
  final int leadTimeDays;
  final List<String> direct;
  const ServingInfo({
    required this.storeId,
    required this.warehouseId,
    required this.leadTimeDays,
    required this.direct,
  });
  factory ServingInfo.fromJson(Map<String, dynamic> j) => ServingInfo(
        storeId: j['storeId'] as String? ?? '',
        warehouseId: j['warehouseId'] as String? ?? '',
        leadTimeDays: (j['leadTimeDays'] as num?)?.toInt() ?? 0,
        direct: ((j['direct'] as List?) ?? const []).map((e) => e as String).toList(),
      );
}

const _inv = '/${ApiConstants.inventory}/admin/inventory';

final networkProvider = FutureProvider.autoDispose<List<ServingInfo>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get('$_inv/network/serving');
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => ServingInfo.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// The proposed transfers from a warehouse still waiting to be released.
final draftTransfersProvider =
    FutureProvider.autoDispose.family<List<TransferOrder>, String>((ref, warehouseId) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '$_inv/transfers',
    queryParameters: {'store': warehouseId, 'status': 'DRAFT', 'limit': 100},
  );
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => TransferOrder.fromJson(e as Map<String, dynamic>))
      .where((t) => t.fromStoreId == warehouseId)
      .toList();
});

final networkWarehouseProvider = StateProvider.autoDispose<String?>((ref) => null);

String _q(num v) => v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toStringAsFixed(3);

bool _management(AuthState? auth) => auth is AuthAuthenticated && auth.isManager;
bool _mayTransfer(AuthState? auth) => auth is AuthAuthenticated && (auth.isManager || auth.isStorekeeper);

/// The Inventory screen's "Depot & shops" tab.
class InventoryNetworkTab extends ConsumerWidget {
  const InventoryNetworkTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final management = _management(auth);
    final mayTransfer = _mayTransfer(auth);
    final stores = ref.watch(storesProvider).value ?? const <StoreInfo>[];
    final names = {for (final s in stores) s.id: s.name};
    final warehouses = stores.where((s) => s.type == 'WAREHOUSE').toList();
    final chosen = ref.watch(networkWarehouseProvider) ?? (warehouses.isEmpty ? null : warehouses.first.id);
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    String name(String id) => names[id] ?? '…${shortRef(id)}';
    // A product a shop buys direct, by name (the catalogue's first page); the
    // end of its id only while it loads, or past that page.
    final products = {
      for (final p in ref.watch(productsProvider).value ?? const <ProductInfo>[]) p.id: p.name,
    };
    String productName(String id) => products[id] ?? '…${shortRef(id)}';

    Widget header(String title, String detail, Widget? action) => Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: text.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 2),
                  Text(detail, style: text.bodySmall?.copyWith(color: cs.onSurfaceVariant)),
                ],
              ),
            ),
            if (action != null) ...[const SizedBox(width: 12), action],
          ],
        );

    Future<void> send(Future<void> Function() call, String done, String fallback, VoidCallback refresh) async {
      try {
        await call();
        refresh();
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(done)));
        }
      } on DioException catch (e) {
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: fallback))));
        }
      }
    }

    Future<void> propose() async {
      if (chosen == null) return;
      try {
        final resp = await ref.read(apiClientProvider).dio.post(
          '$_inv/network/proposals',
          data: {'warehouseId': chosen},
          options: Options(headers: {'Idempotency-Key': newId()}),
        );
        final run = resp.data['data'] as Map<String, dynamic>;
        ref.invalidate(draftTransfersProvider(chosen));
        if (!context.mounted) return;
        final transfers = (run['transfers'] as num?)?.toInt() ?? 0;
        final short = (run['shortLines'] as num?)?.toInt() ?? 0;
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(transfers == 0
              ? 'No shop needs anything from this warehouse now.'
              : '$transfers transfers proposed${short == 0 ? '' : ', $short lines cut: the warehouse is short'}.'),
        ));
      } on DioException catch (e) {
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text(friendlyError(e, fallback: 'Could not propose transfers.'))));
        }
      }
    }

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        header(
          'Which warehouse serves which shop',
          'A shop served by a warehouse is replenished by transfer from it; the products it buys direct'
              ' stay on its own purchase proposal.',
          management
              ? FilledButton.icon(
                  key: const Key('network-serve'),
                  onPressed: () => showDialog<void>(context: context, builder: (_) => const ServeShopDialog()),
                  icon: const Icon(Icons.hub_outlined),
                  label: const Text('Serve a shop'),
                )
              : null,
        ),
        const SizedBox(height: 8),
        ref.watch(networkProvider).when(
              loading: () => const LoadingView(label: 'Loading the network…'),
              error: (e, _) => ErrorView(
                message: friendlyError(e, fallback: 'Could not load the network.'),
                onRetry: () => ref.invalidate(networkProvider),
              ),
              data: (list) => list.isEmpty
                  ? const EmptyState(
                      icon: Icons.hub_outlined,
                      title: 'Every shop buys direct',
                      detail: 'Add a store of type Warehouse, then say which shops it serves.',
                    )
                  : Column(
                      children: [
                        for (final s in list)
                          ListTile(
                            key: Key('serving-${s.storeId}'),
                            dense: true,
                            leading: const Icon(Icons.storefront_outlined),
                            title: Text('${name(s.storeId)} ← ${name(s.warehouseId)}'),
                            subtitle: Text(
                              '${s.leadTimeDays} ${s.leadTimeDays == 1 ? 'day' : 'days'} from the warehouse'
                              '${s.direct.isEmpty ? '' : ' · bought direct: ${s.direct.map(productName).join(', ')}'}',
                            ),
                            trailing: management
                                ? Row(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      IconButton(
                                        key: Key('serving-direct-${s.storeId}'),
                                        tooltip: 'Buy a product direct',
                                        icon: const Icon(Icons.local_shipping_outlined),
                                        onPressed: () => showDialog<void>(
                                          context: context,
                                          builder: (_) => BuyDirectDialog(storeId: s.storeId),
                                        ),
                                      ),
                                      IconButton(
                                        key: Key('serving-remove-${s.storeId}'),
                                        tooltip: 'Buy everything direct',
                                        icon: const Icon(Icons.link_off),
                                        onPressed: () => send(
                                          () => ref.read(apiClientProvider).dio.delete('$_inv/network/serving/${s.storeId}'),
                                          '${name(s.storeId)} buys everything direct now.',
                                          'Could not take the shop out of the network.',
                                          () => ref.invalidate(networkProvider),
                                        ),
                                      ),
                                    ],
                                  )
                                : null,
                          ),
                      ],
                    ),
            ),
        const Divider(height: 32),
        header(
          'Replenish shops',
          'The warehouse proposes a transfer to every shop at or below its reorder point, sharing fairly'
              ' when it is short. A person releases each one for the warehouse to ship.',
          mayTransfer && chosen != null
              ? FilledButton.icon(
                  key: const Key('network-propose'),
                  onPressed: propose,
                  icon: const Icon(Icons.move_up_outlined),
                  label: const Text('Propose transfers'),
                )
              : null,
        ),
        const SizedBox(height: 8),
        if (warehouses.isEmpty)
          const EmptyState(icon: Icons.warehouse_outlined, title: 'No warehouse yet')
        else ...[
          DropdownButtonFormField<String>(
            key: const Key('network-warehouse'),
            initialValue: chosen,
            isExpanded: true,
            decoration: const InputDecoration(labelText: 'Warehouse'),
            items: [
              for (final w in warehouses) DropdownMenuItem(value: w.id, child: Text(w.name, overflow: TextOverflow.ellipsis)),
            ],
            onChanged: (v) => ref.read(networkWarehouseProvider.notifier).state = v,
          ),
          const SizedBox(height: 8),
          if (chosen != null)
            ref.watch(draftTransfersProvider(chosen)).when(
                  loading: () => const LoadingView(label: 'Loading proposals…'),
                  error: (e, _) => ErrorView(
                    message: friendlyError(e, fallback: 'Could not load the proposals.'),
                    onRetry: () => ref.invalidate(draftTransfersProvider(chosen)),
                  ),
                  data: (drafts) => drafts.isEmpty
                      ? const EmptyState(icon: Icons.inventory_2_outlined, title: 'No proposal waiting')
                      : VariantNames(
                        ids: [for (final t in drafts) ...t.lines.map((l) => l.variantId)],
                        builder: (context, labels) => Column(
                          children: [
                            for (final t in drafts)
                              Card(
                                key: Key('draft-${t.id}'),
                                child: Padding(
                                  padding: const EdgeInsets.all(12),
                                  child: Column(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      Text('To ${name(t.toStoreId)}',
                                          style: text.titleSmall?.copyWith(fontWeight: FontWeight.w600)),
                                      for (final l in t.lines)
                                        Padding(
                                          padding: const EdgeInsets.only(top: 6),
                                          child: Column(
                                            crossAxisAlignment: CrossAxisAlignment.start,
                                            children: [
                                              Text('${_q(l.requestedQty)} × ${variantDisplayName(l.variantId, labels)}',
                                                  key: Key('draft-line-${l.id}')),
                                              if (l.reason != null)
                                                Text(l.reason!,
                                                    style: text.bodySmall?.copyWith(color: cs.onSurfaceVariant)),
                                            ],
                                          ),
                                        ),
                                      if (mayTransfer)
                                        Align(
                                          alignment: Alignment.centerRight,
                                          child: Wrap(
                                            spacing: 8,
                                            children: [
                                              TextButton(
                                                key: Key('draft-discard-${t.id}'),
                                                onPressed: () => send(
                                                  () => ref.read(apiClientProvider).dio.post('$_inv/transfers/${t.id}/cancel'),
                                                  'Proposal discarded.',
                                                  'Could not discard the proposal.',
                                                  () => ref.invalidate(draftTransfersProvider(chosen)),
                                                ),
                                                child: const Text('Discard'),
                                              ),
                                              FilledButton(
                                                key: Key('draft-release-${t.id}'),
                                                onPressed: () => send(
                                                  () => ref.read(apiClientProvider).dio.post('$_inv/transfers/${t.id}/release'),
                                                  'Released: the warehouse can ship it.',
                                                  'Could not release the transfer.',
                                                  () => ref.invalidate(draftTransfersProvider(chosen)),
                                                ),
                                                child: const Text('Release'),
                                              ),
                                            ],
                                          ),
                                        ),
                                    ],
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                ),
        ],
      ],
    );
  }
}

/// Serve a shop from a warehouse.
class ServeShopDialog extends ConsumerStatefulWidget {
  const ServeShopDialog({super.key});

  @override
  ConsumerState<ServeShopDialog> createState() => _ServeShopDialogState();
}

class _ServeShopDialogState extends ConsumerState<ServeShopDialog> {
  String? _shop;
  String? _warehouse;
  final _lead = TextEditingController(text: '1');
  bool _busy = false;

  @override
  void dispose() {
    _lead.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final lead = int.tryParse(_lead.text.trim());
    if (_shop == null || _warehouse == null || lead == null) return;
    setState(() => _busy = true);
    try {
      await ref.read(apiClientProvider).dio.put(
        '$_inv/network/serving',
        data: {'storeId': _shop, 'warehouseId': _warehouse, 'leadTimeDays': lead},
      );
      ref.invalidate(networkProvider);
      if (!mounted) return;
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('The shop is served by the warehouse.')));
    } on DioException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: 'Could not set the warehouse.'))));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final stores = ref.watch(storesProvider).value ?? const <StoreInfo>[];
    final shops = stores.where((s) => s.type != 'WAREHOUSE').toList();
    final warehouses = stores.where((s) => s.type == 'WAREHOUSE').toList();
    return AlertDialog(
      title: const Text('Serve a shop'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            DropdownButtonFormField<String>(
              key: const Key('serve-shop'),
              initialValue: _shop,
              isExpanded: true,
              decoration: const InputDecoration(labelText: 'Shop'),
              items: [for (final s in shops) DropdownMenuItem(value: s.id, child: Text(s.name))],
              onChanged: (v) => setState(() => _shop = v),
            ),
            DropdownButtonFormField<String>(
              key: const Key('serve-warehouse'),
              initialValue: _warehouse,
              isExpanded: true,
              decoration: const InputDecoration(labelText: 'Served by warehouse'),
              items: [for (final w in warehouses) DropdownMenuItem(value: w.id, child: Text(w.name))],
              onChanged: (v) => setState(() => _warehouse = v),
            ),
            TextField(
              key: const Key('serve-lead'),
              controller: _lead,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: 'Days from the warehouse to the shop'),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('serve-save'), onPressed: _busy ? null : _save, child: const Text('Save')),
      ],
    );
  }
}

/// Mark a product the shop buys direct from its supplier.
class BuyDirectDialog extends ConsumerStatefulWidget {
  final String storeId;
  const BuyDirectDialog({super.key, required this.storeId});

  @override
  ConsumerState<BuyDirectDialog> createState() => _BuyDirectDialogState();
}

class _BuyDirectDialogState extends ConsumerState<BuyDirectDialog> {
  String? _product;
  String? _variant;

  Future<void> _save() async {
    if (_variant == null) return;
    try {
      await ref.read(apiClientProvider).dio.put('$_inv/network/serving/${widget.storeId}/direct/$_variant');
      ref.invalidate(networkProvider);
      if (!mounted) return;
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Bought direct: it stays on the shop\'s own purchase proposal.')));
    } on DioException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: 'Could not mark it bought direct.'))));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Buy a product direct'),
      content: SizedBox(
        width: 420,
        child: VariantPicker(
          productId: _product,
          variantId: _variant,
          onProduct: (v) => setState(() {
            _product = v;
            _variant = null;
          }),
          onVariant: (v) => setState(() => _variant = v),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('direct-save'), onPressed: _variant == null ? null : _save, child: const Text('Save')),
      ],
    );
  }
}
