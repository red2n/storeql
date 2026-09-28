import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/format.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/status_badge.dart';
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
import 'widgets/variant_picker.dart';

// ---------------------------------------------------------------------------
// Wave picking and directed putaway. Confirmed online orders wait at their
// store; a wave gathers them into one walk through the zones, directed to the
// batch the picking rule chooses, and completing it deducts what was picked and
// fulfils the orders. Stock arriving with no zone is placed by the store's
// rules, or waits on the putaway list for a person to place.
// ---------------------------------------------------------------------------

class AwaitingLine {
  final String variantId;
  final double qtyOutstanding;
  const AwaitingLine({required this.variantId, required this.qtyOutstanding});
  factory AwaitingLine.fromJson(Map<String, dynamic> j) => AwaitingLine(
        variantId: j['variantId'] as String? ?? '',
        qtyOutstanding: (j['qtyOutstanding'] as num?)?.toDouble() ?? 0,
      );
}

class AwaitingOrder {
  final String orderId;
  final String fulfilmentType;
  final String confirmedAt;
  final String? waveId;
  final List<AwaitingLine> lines;
  const AwaitingOrder({
    required this.orderId,
    required this.fulfilmentType,
    required this.confirmedAt,
    this.waveId,
    required this.lines,
  });
  factory AwaitingOrder.fromJson(Map<String, dynamic> j) => AwaitingOrder(
        orderId: j['orderId'] as String? ?? '',
        fulfilmentType: j['fulfilmentType'] as String? ?? '',
        confirmedAt: j['confirmedAt'] as String? ?? '',
        waveId: j['waveId'] as String?,
        lines: ((j['lines'] as List?) ?? const [])
            .map((e) => AwaitingLine.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

class WaveOrderShare {
  final String orderId;
  final double qty;
  final double? pickedQty;
  const WaveOrderShare({required this.orderId, required this.qty, this.pickedQty});
  factory WaveOrderShare.fromJson(Map<String, dynamic> j) => WaveOrderShare(
        orderId: j['orderId'] as String? ?? '',
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
        pickedQty: (j['pickedQty'] as num?)?.toDouble(),
      );
}

class PickWaveLine {
  final String id;
  final int walkOrder;
  final String? zoneId;
  final String batchId;
  final String? batchNo;
  final String variantId;
  final double directedQty;
  final double? pickedQty;
  final List<WaveOrderShare> orders;
  const PickWaveLine({
    required this.id,
    required this.walkOrder,
    this.zoneId,
    required this.batchId,
    this.batchNo,
    required this.variantId,
    required this.directedQty,
    this.pickedQty,
    required this.orders,
  });
  factory PickWaveLine.fromJson(Map<String, dynamic> j) => PickWaveLine(
        id: j['id'] as String? ?? '',
        walkOrder: (j['walkOrder'] as num?)?.toInt() ?? 0,
        zoneId: j['zoneId'] as String?,
        batchId: j['batchId'] as String? ?? '',
        batchNo: j['batchNo'] as String?,
        variantId: j['variantId'] as String? ?? '',
        directedQty: (j['directedQty'] as num?)?.toDouble() ?? 0,
        pickedQty: (j['pickedQty'] as num?)?.toDouble(),
        orders: ((j['orders'] as List?) ?? const [])
            .map((e) => WaveOrderShare.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

class PickWave {
  final String id;
  final String storeId;
  final String status;
  final String createdAt;
  final String? completedAt;
  final int orderCount;
  final List<PickWaveLine> lines;
  const PickWave({
    required this.id,
    required this.storeId,
    required this.status,
    required this.createdAt,
    this.completedAt,
    required this.orderCount,
    required this.lines,
  });
  factory PickWave.fromJson(Map<String, dynamic> j) => PickWave(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        status: j['status'] as String? ?? 'OPEN',
        createdAt: j['createdAt'] as String? ?? '',
        completedAt: j['completedAt'] as String?,
        orderCount: (j['orderCount'] as num?)?.toInt() ?? 0,
        lines: ((j['lines'] as List?) ?? const [])
            .map((e) => PickWaveLine.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

class PutawayTask {
  final String id;
  final String storeId;
  final String batchId;
  /// The batch's number as printed on its label; null if it has none.
  final String? batchNo;
  final String variantId;
  final double qty;
  final String? suggestedZoneId;
  final String status;
  const PutawayTask({
    required this.id,
    required this.storeId,
    required this.batchId,
    this.batchNo,
    required this.variantId,
    required this.qty,
    this.suggestedZoneId,
    required this.status,
  });
  factory PutawayTask.fromJson(Map<String, dynamic> j) => PutawayTask(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        batchId: j['batchId'] as String? ?? '',
        batchNo: j['batchNo'] as String?,
        variantId: j['variantId'] as String? ?? '',
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
        suggestedZoneId: j['suggestedZoneId'] as String?,
        status: j['status'] as String? ?? 'OPEN',
      );
}

class PutawayRule {
  final String id;
  final String storeId;
  final String? variantId;
  final String zoneId;
  const PutawayRule({required this.id, required this.storeId, this.variantId, required this.zoneId});
  factory PutawayRule.fromJson(Map<String, dynamic> j) => PutawayRule(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        variantId: j['variantId'] as String?,
        zoneId: j['zoneId'] as String? ?? '',
      );
}

const _inv = '/${ApiConstants.inventory}/admin/inventory';

/// The store the tab works in; the first of the caller's stores until chosen.
final wavesStoreProvider = StateProvider.autoDispose<String?>((ref) => null);

final awaitingOrdersProvider =
    FutureProvider.autoDispose.family<List<AwaitingOrder>, String>((ref, storeId) async {
  final resp = await ref.read(apiClientProvider).dio.get('$_inv/waves/awaiting', queryParameters: {'storeId': storeId});
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => AwaitingOrder.fromJson(e as Map<String, dynamic>))
      .toList();
});

final pickWavesProvider = FutureProvider.autoDispose.family<List<PickWave>, String>((ref, storeId) async {
  final resp = await ref.read(apiClientProvider).dio.get('$_inv/waves', queryParameters: {'storeId': storeId});
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => PickWave.fromJson(e as Map<String, dynamic>))
      .toList();
});

final pickWaveProvider = FutureProvider.autoDispose.family<PickWave, String>((ref, id) async {
  final resp = await ref.read(apiClientProvider).dio.get('$_inv/waves/$id');
  return PickWave.fromJson(resp.data['data'] as Map<String, dynamic>);
});

final putawayTasksProvider =
    FutureProvider.autoDispose.family<List<PutawayTask>, String>((ref, storeId) async {
  final resp = await ref.read(apiClientProvider).dio.get('$_inv/putaway/tasks', queryParameters: {'storeId': storeId});
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => PutawayTask.fromJson(e as Map<String, dynamic>))
      .toList();
});

final putawayRulesProvider =
    FutureProvider.autoDispose.family<List<PutawayRule>, String>((ref, storeId) async {
  final resp = await ref.read(apiClientProvider).dio.get('$_inv/putaway/rules', queryParameters: {'storeId': storeId});
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => PutawayRule.fromJson(e as Map<String, dynamic>))
      .toList();
});

String _q(num v) => v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toStringAsFixed(3);

bool _mayPick(AuthState? auth) => auth is AuthAuthenticated && (auth.isManager || auth.isStorekeeper);

/// The Inventory screen's "Picking & putaway" tab.
/// A wave's status in words and tone.
(String, StatusTone) _waveStatus(String status) => switch (status) {
      'OPEN' => ('To pick', StatusTone.info),
      'COMPLETED' => ('Picked', StatusTone.success),
      'CANCELLED' => ('Cancelled', StatusTone.neutral),
      _ => (humanizeCode(status), StatusTone.neutral),
    };

/// The product names of [ids], read once for the list.
Map<String, VariantLabel> _names(WidgetRef ref, Iterable<String> ids) =>
    ref.watch(variantLabelsProvider(variantIdsKey(ids))).value ??
    const <String, VariantLabel>{};

class InventoryWavesTab extends ConsumerWidget {
  const InventoryWavesTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final mayPick = _mayPick(auth);
    final management = auth is AuthAuthenticated && auth.isManager;
    final stores = ref.watch(storesProvider).value ?? const <StoreInfo>[];
    final chosen = ref.watch(wavesStoreProvider) ?? (stores.isEmpty ? null : stores.first.id);
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;

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

    Future<void> buildWave() async {
      if (chosen == null) return;
      try {
        final resp = await ref.read(apiClientProvider).dio.post(
          '$_inv/waves',
          data: {'storeId': chosen},
          options: Options(headers: {'Idempotency-Key': newId()}),
        );
        final wave = PickWave.fromJson(resp.data['data'] as Map<String, dynamic>);
        ref.invalidate(awaitingOrdersProvider(chosen));
        ref.invalidate(pickWavesProvider(chosen));
        if (!context.mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('Wave built: ${wave.lines.length} lines for ${wave.orderCount} orders.'),
        ));
        showDialog<void>(context: context, builder: (_) => WaveDialog(id: wave.id, storeId: chosen));
      } on DioException catch (e) {
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text(friendlyError(e, fallback: 'Could not build a wave.'))));
        }
      }
    }

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        DropdownButtonFormField<String>(
          key: const Key('waves-store'),
          initialValue: chosen,
          isExpanded: true,
          decoration: const InputDecoration(labelText: 'Store'),
          items: [
            for (final s in stores) DropdownMenuItem(value: s.id, child: Text(s.name, overflow: TextOverflow.ellipsis)),
          ],
          onChanged: (v) => ref.read(wavesStoreProvider.notifier).state = v,
        ),
        const SizedBox(height: 16),
        if (chosen == null)
          const EmptyState(icon: Icons.store_outlined, title: 'No store to pick for')
        else ...[
          header(
            'Waiting to be picked',
            'Confirmed online orders for pickup or delivery at this store, earliest first. A wave gathers'
                ' them into one walk through the zones, directed to the batch the picking rule chooses.',
            mayPick
                ? FilledButton.icon(
                    key: const Key('wave-build'),
                    onPressed: buildWave,
                    icon: const Icon(Icons.route_outlined),
                    label: const Text('Build wave'),
                  )
                : null,
          ),
          const SizedBox(height: 8),
          ref.watch(awaitingOrdersProvider(chosen)).when(
                loading: () => const LoadingView(label: 'Loading orders…'),
                error: (e, _) => ErrorView(
                  message: friendlyError(e, fallback: 'Could not load the waiting orders.'),
                  onRetry: () => ref.invalidate(awaitingOrdersProvider(chosen)),
                ),
                data: (list) {
                  if (list.isEmpty) {
                    return const EmptyState(icon: Icons.inbox_outlined, title: 'Nothing waiting to be picked');
                  }
                  final labels = _names(ref, [for (final o in list) ...o.lines.map((l) => l.variantId)]);
                  return Column(
                        children: [
                          for (final o in list)
                            ListTile(
                              key: Key('awaiting-${o.orderId}'),
                              dense: true,
                              leading: Icon(o.fulfilmentType == 'DELIVERY' ? Icons.local_shipping_outlined : Icons.storefront_outlined),
                              title: Text('Order ${shortRef(o.orderId)} · ${o.fulfilmentType.toLowerCase()}'),
                              subtitle: Text(
                                '${o.lines.map((l) => '${_q(l.qtyOutstanding)} × ${variantDisplayName(l.variantId, labels)}').join(', ')}'
                                '${o.waveId == null ? '' : ' · in wave ${shortRef(o.waveId!)}'}',
                              ),
                            ),
                        ],
                      );
                },
              ),
          const SizedBox(height: 24),
          header('Waves', 'Newest first. Open a wave to record the picks and complete it.', null),
          const SizedBox(height: 8),
          ref.watch(pickWavesProvider(chosen)).when(
                loading: () => const LoadingView(label: 'Loading waves…'),
                error: (e, _) => ErrorView(
                  message: friendlyError(e, fallback: 'Could not load the waves.'),
                  onRetry: () => ref.invalidate(pickWavesProvider(chosen)),
                ),
                data: (list) => list.isEmpty
                    ? const EmptyState(icon: Icons.route_outlined, title: 'No waves yet')
                    : Column(
                        children: [
                          for (final w in list)
                            Card(
                              child: ListTile(
                                key: Key('wave-${w.id}'),
                                title: Text('Wave ${shortRef(w.id)}',
                                    style: const TextStyle(fontWeight: FontWeight.w600)),
                                subtitle: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text('${w.orderCount} orders · ${AppFormat.dateTime(w.createdAt)}'),
                                    const SizedBox(height: AppSpacing.xs),
                                    Builder(builder: (context) {
                                      final (words, tone) = _waveStatus(w.status);
                                      return StatusBadge(words, tone: tone);
                                    }),
                                  ],
                                ),
                                trailing: const Icon(Icons.chevron_right),
                                onTap: () => showDialog<void>(
                                  context: context,
                                  builder: (_) => WaveDialog(id: w.id, storeId: chosen),
                                ),
                              ),
                            ),
                        ],
                      ),
              ),
          const SizedBox(height: 24),
          header(
            'Waiting to be placed',
            'Stock that arrived with no zone and no rule to place it. Say where it went.',
            null,
          ),
          const SizedBox(height: 8),
          _PutawayTasks(storeId: chosen, mayPlace: mayPick),
          const SizedBox(height: 24),
          header(
            'Putaway rules',
            'Where a product goes when it arrives with no zone; the default catches anything no rule names.',
            management
                ? FilledButton.tonalIcon(
                    key: const Key('putaway-rule-new'),
                    onPressed: () => showDialog<void>(
                      context: context,
                      builder: (_) => NewPutawayRuleDialog(storeId: chosen),
                    ),
                    icon: const Icon(Icons.add),
                    label: const Text('Add rule'),
                  )
                : null,
          ),
          const SizedBox(height: 8),
          _PutawayRules(storeId: chosen, management: management),
        ],
      ],
    );
  }
}

String zoneName(List<ZoneInfo> zones, String? zoneId) {
  if (zoneId == null) return 'no zone';
  return zones.where((z) => z.id == zoneId).map((z) => z.name).firstOrNull ?? shortRef(zoneId);
}

class _PutawayTasks extends ConsumerStatefulWidget {
  const _PutawayTasks({required this.storeId, required this.mayPlace});
  final String storeId;
  final bool mayPlace;

  @override
  ConsumerState<_PutawayTasks> createState() => _PutawayTasksState();
}

class _PutawayTasksState extends ConsumerState<_PutawayTasks> {
  final Map<String, String?> _zone = {};

  Future<void> _place(PutawayTask t) async {
    final zone = _zone[t.id] ?? t.suggestedZoneId;
    if (zone == null) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Pick the zone it went in.')));
      return;
    }
    try {
      await ref.read(apiClientProvider).dio.post('$_inv/putaway/tasks/${t.id}/place', data: {'zoneId': zone});
      ref.invalidate(putawayTasksProvider(widget.storeId));
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Placed.')));
    } on DioException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: 'Could not place it.'))));
    }
  }

  @override
  Widget build(BuildContext context) {
    final zones = ref.watch(zonesProvider(widget.storeId)).value ?? const <ZoneInfo>[];
    return ref.watch(putawayTasksProvider(widget.storeId)).when(
          loading: () => const LoadingView(label: 'Loading the putaway list…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the putaway list.'),
            onRetry: () => ref.invalidate(putawayTasksProvider(widget.storeId)),
          ),
          data: (tasks) => tasks.isEmpty
              ? const EmptyState(icon: Icons.check_circle_outline, title: 'Everything has a zone')
              : Builder(builder: (context) {
                  final labels = _names(ref, tasks.map((t) => t.variantId));
                  return Column(
                  children: [
                    for (final t in tasks)
                      ListTile(
                        key: Key('putaway-task-${t.id}'),
                        dense: true,
                        title: Text(
                          '${_q(t.qty)} × ${variantDisplayName(t.variantId, labels)}'
                          ' · batch ${t.batchNo ?? '…${shortRef(t.batchId)}'}',
                          key: Key('putaway-task-title-${t.id}'),
                        ),
                        trailing: widget.mayPlace
                            ? Row(mainAxisSize: MainAxisSize.min, children: [
                                SizedBox(
                                  width: 180,
                                  child: DropdownButtonFormField<String>(
                                    key: Key('putaway-zone-${t.id}'),
                                    initialValue: _zone[t.id] ?? t.suggestedZoneId,
                                    isExpanded: true,
                                    decoration: const InputDecoration(labelText: 'Zone', isDense: true),
                                    items: [
                                      for (final z in zones)
                                        DropdownMenuItem(value: z.id, child: Text(z.name, overflow: TextOverflow.ellipsis)),
                                    ],
                                    onChanged: (v) => setState(() => _zone[t.id] = v),
                                  ),
                                ),
                                const SizedBox(width: 8),
                                FilledButton.tonal(
                                  key: Key('putaway-place-${t.id}'),
                                  onPressed: () => _place(t),
                                  child: const Text('Place'),
                                ),
                              ])
                            : null,
                      ),
                  ],
                );
                }),
        );
  }
}

class _PutawayRules extends ConsumerWidget {
  const _PutawayRules({required this.storeId, required this.management});
  final String storeId;
  final bool management;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final zones = ref.watch(zonesProvider(storeId)).value ?? const <ZoneInfo>[];
    return ref.watch(putawayRulesProvider(storeId)).when(
          loading: () => const LoadingView(label: 'Loading rules…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the rules.'),
            onRetry: () => ref.invalidate(putawayRulesProvider(storeId)),
          ),
          data: (rules) => rules.isEmpty
              ? const EmptyState(icon: Icons.alt_route_outlined, title: 'No putaway rules', detail: 'Everything arriving with no zone waits to be placed.')
              : Builder(builder: (context) {
                  final labels = _names(ref, [for (final r in rules) ?r.variantId]);
                  return Column(
                  children: [
                    for (final r in rules)
                      ListTile(
                        key: Key('putaway-rule-${r.id}'),
                        dense: true,
                        title: Text(r.variantId == null ? 'Anything else' : variantDisplayName(r.variantId!, labels)),
                        subtitle: Text('→ ${zoneName(zones, r.zoneId)}'),
                        trailing: management
                            ? IconButton(
                                key: Key('putaway-rule-delete-${r.id}'),
                                tooltip: 'Remove rule',
                                icon: const Icon(Icons.delete_outline),
                                onPressed: () async {
                                  await ref.read(apiClientProvider).dio.delete('$_inv/putaway/rules/${r.id}');
                                  ref.invalidate(putawayRulesProvider(storeId));
                                },
                              )
                            : null,
                      ),
                  ],
                );
                }),
        );
  }
}

/// One wave: the walk, the picks, the completion.
class WaveDialog extends ConsumerStatefulWidget {
  const WaveDialog({super.key, required this.id, required this.storeId});
  final String id;
  final String storeId;

  @override
  ConsumerState<WaveDialog> createState() => _WaveDialogState();
}

class _WaveDialogState extends ConsumerState<WaveDialog> {
  final Map<String, TextEditingController> _picked = {};
  bool _busy = false;

  @override
  void dispose() {
    for (final c in _picked.values) {
      c.dispose();
    }
    super.dispose();
  }

  TextEditingController _ctrl(PickWaveLine l) =>
      _picked.putIfAbsent(l.id, () => TextEditingController(text: _q(l.pickedQty ?? l.directedQty)));

  /// Posts to the wave and says whether it went through, so a step that depends on the one
  /// before it (Complete after the picks) can stop when that one was refused.
  Future<bool> _post(String path, Map<String, dynamic> body, String? done) async {
    setState(() => _busy = true);
    try {
      await ref.read(apiClientProvider).dio.post('$_inv/waves/${widget.id}$path', data: body);
      ref.invalidate(pickWaveProvider(widget.id));
      ref.invalidate(pickWavesProvider(widget.storeId));
      ref.invalidate(awaitingOrdersProvider(widget.storeId));
      if (mounted && done != null) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(done)));
      }
      return true;
    } on DioException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: 'That did not go through.'))));
      }
      return false;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Map<String, dynamic> _picksBody(PickWave w) => {
        'lines': [
          for (final l in w.lines)
            {'lineId': l.id, 'pickedQty': double.tryParse(_ctrl(l).text.trim()) ?? 0},
        ],
      };

  @override
  Widget build(BuildContext context) {
    final wave = ref.watch(pickWaveProvider(widget.id));
    final zones = ref.watch(zonesProvider(widget.storeId)).value ?? const <ZoneInfo>[];
    final labels = _names(ref, [for (final l in wave.value?.lines ?? const []) l.variantId as String]);
    final mayPick = _mayPick(ref.watch(authNotifierProvider).value);
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text('Wave ${shortRef(widget.id)}'),
      content: SizedBox(
        width: 640,
        child: wave.when(
          loading: () => const LoadingView(label: 'Loading the wave…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the wave.'),
            onRetry: () => ref.invalidate(pickWaveProvider(widget.id)),
          ),
          data: (w) => SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('${_waveStatus(w.status).$1} · ${w.orderCount} orders · ${w.lines.length} lines, walked in order',
                    style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12)),
                const SizedBox(height: 8),
                for (final l in w.lines)
                  ListTile(
                    key: Key('wave-line-${l.id}'),
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    leading: CircleAvatar(radius: 14, child: Text('${l.walkOrder}', style: const TextStyle(fontSize: 12))),
                    title: Text(
                      '${zoneName(zones, l.zoneId)} · ${l.batchNo ?? '…${shortRef(l.batchId)}'} · ${variantDisplayName(l.variantId, labels)} × ${_q(l.directedQty)}',
                      key: Key('wave-line-title-${l.id}'),
                    ),
                    subtitle: Text('for ${l.orders.map((o) => '${shortRef(o.orderId)} (${_q(o.qty)})').join(', ')}'),
                    trailing: w.status == 'OPEN' && mayPick
                        ? SizedBox(
                            width: 90,
                            child: TextField(
                              key: Key('wave-pick-${l.id}'),
                              controller: _ctrl(l),
                              decoration: const InputDecoration(labelText: 'Picked', isDense: true),
                              keyboardType: const TextInputType.numberWithOptions(decimal: true),
                            ),
                          )
                        : Text(l.pickedQty == null ? '' : 'picked ${_q(l.pickedQty!)}'),
                  ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        ...wave.maybeWhen(
          data: (w) => w.status == 'OPEN' && mayPick
              ? [
                  TextButton(
                    key: const Key('wave-cancel'),
                    onPressed: _busy ? null : () => _post('/cancel', {}, 'Wave cancelled; the orders wait again.'),
                    child: const Text('Cancel wave'),
                  ),
                  TextButton(
                    key: const Key('wave-save-picks'),
                    onPressed: _busy ? null : () => _post('/picks', _picksBody(w), 'Picks saved.'),
                    child: const Text('Save picks'),
                  ),
                  FilledButton(
                    key: const Key('wave-complete'),
                    onPressed: _busy
                        ? null
                        : () async {
                            // The picks first; a wave completed with picks the server refused
                            // would deduct what was directed, not what was picked.
                            if (!await _post('/picks', _picksBody(w), null)) return;
                            await _post('/complete', {}, 'Wave completed: the stock has left and the orders are being fulfilled.');
                          },
                    child: const Text('Complete'),
                  ),
                ]
              : const <Widget>[],
          orElse: () => const <Widget>[],
        ),
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Close')),
      ],
    );
  }
}

/// Where a product goes when it arrives with no zone.
class NewPutawayRuleDialog extends ConsumerStatefulWidget {
  const NewPutawayRuleDialog({super.key, required this.storeId});
  final String storeId;

  @override
  ConsumerState<NewPutawayRuleDialog> createState() => _NewPutawayRuleDialogState();
}

class _NewPutawayRuleDialogState extends ConsumerState<NewPutawayRuleDialog> {
  bool _default = false;
  String? _productId;
  String? _variantId;
  String? _zoneId;
  String? _refusal;

  Future<void> _save() async {
    if (_zoneId == null || (!_default && _variantId == null)) {
      setState(() => _refusal = 'Pick the product (or make it the default) and the zone.');
      return;
    }
    try {
      await ref.read(apiClientProvider).dio.put(
        '$_inv/putaway/rules',
        data: {'storeId': widget.storeId, if (!_default) 'variantId': _variantId, 'zoneId': _zoneId},
      );
      ref.invalidate(putawayRulesProvider(widget.storeId));
      if (!mounted) return;
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not save the rule.'));
    }
  }

  @override
  Widget build(BuildContext context) {
    final zones = ref.watch(zonesProvider(widget.storeId)).value ?? const <ZoneInfo>[];
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Putaway rule'),
      content: SizedBox(
        width: 440,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SwitchListTile.adaptive(
                key: const Key('putaway-rule-default'),
                contentPadding: EdgeInsets.zero,
                value: _default,
                onChanged: (v) => setState(() => _default = v),
                title: const Text('The store default'),
                subtitle: const Text('Catches anything no rule names'),
              ),
              if (!_default)
                VariantPicker(
                  productId: _productId,
                  variantId: _variantId,
                  onProduct: (v) => setState(() {
                    _productId = v;
                    _variantId = null;
                  }),
                  onVariant: (v) => setState(() => _variantId = v),
                ),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                key: const Key('putaway-rule-zone'),
                initialValue: _zoneId,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Zone *'),
                items: [for (final z in zones) DropdownMenuItem(value: z.id, child: Text(z.name, overflow: TextOverflow.ellipsis))],
                onChanged: (v) => setState(() => _zoneId = v),
              ),
              if (_refusal != null) ...[
                const SizedBox(height: 12),
                Text(_refusal!, key: const Key('putaway-rule-refusal'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('putaway-rule-save'), onPressed: _save, child: const Text('Save')),
      ],
    );
  }
}
