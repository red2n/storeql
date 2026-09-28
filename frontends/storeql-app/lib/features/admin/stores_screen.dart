import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/adaptive_sheet.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/reference_fields.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';
import 'fulfilment_windows_screen.dart';
import 'providers/admin_providers.dart';
import 'store_instruments_dialog.dart';

/// A store's status in words: *Open* while it trades, *Closed* when switched
/// off; a status this screen does not know yet reads as words too.
String _storeStatusLabel(String status) => switch (status.toUpperCase()) {
      'ACTIVE' => 'Open',
      'INACTIVE' => 'Closed',
      _ => humanizeCode(status),
    };

/// What a store's ⋮ menu offers on a narrow list.
enum _StoreAction { edit, zones, instruments, delivery, slots, toggle }

/// Whether [auth] may set [storeId]'s delivery/collection windows: an owner,
/// anywhere; a manager only where they are store-held for it (empty
/// [AuthAuthenticated.storeIds] is unrestricted, like an owner's) — never a
/// storekeeper, who would only meet a refusal from order-svc.
bool _canManageSlots(AuthState? auth, String storeId) {
  if (auth is! AuthAuthenticated) return false;
  if (auth.roles.contains(UserRoles.owner)) return true;
  if (!auth.roles.contains(UserRoles.manager)) return false;
  return auth.storeIds.isEmpty || auth.storeIds.contains(storeId);
}

class StoresScreen extends ConsumerWidget {
  const StoresScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final storesAsync = ref.watch(storesProvider);
    final auth = ref.watch(authNotifierProvider).value;
    final isManager = auth is AuthAuthenticated && auth.isManager;
    final gutter = context.pageGutter;

    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _showAddStoreDialog(context, ref),
        icon: const Icon(Icons.add_business),
        label: const Text('Add Store'),
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          PageHeader(
            title: 'Stores',
            actions: [
              IconButton(
                icon: const Icon(Icons.refresh),
                tooltip: 'Refresh stores',
                onPressed: () => ref.invalidate(storesProvider),
              ),
            ],
          ),
          Expanded(
            child: storesAsync.when(
              loading: () => const LoadingView(label: 'Loading stores…'),
              error: (e, _) => ErrorView(
                message: friendlyError(e, fallback: 'Could not load stores.'),
                onRetry: () => ref.invalidate(storesProvider),
              ),
              data: (stores) {
                if (stores.isEmpty) {
                  return EmptyState(
                    icon: Icons.store_outlined,
                    title: 'No stores yet',
                    message:
                        'Add a store or warehouse to start managing inventory.',
                    action: OutlinedButton.icon(
                      onPressed: () => _showAddStoreDialog(context, ref),
                      icon: const Icon(Icons.add_business),
                      label: const Text('Add Store'),
                    ),
                  );
                }
                return LayoutBuilder(builder: (context, constraints) {
                  // Zones, Instruments and Delivery sit on the row only where
                  // they leave the name its room: from the expanded class,
                  // measured in text units, so large text folds them into the
                  // menu sooner. Below it they would take the whole tile.
                  final textScale =
                      MediaQuery.textScalerOf(context).scale(16) / 16;
                  final inline = AppBreakpoints.classOf(
                          constraints.maxWidth / textScale) >=
                      WindowClass.expanded;
                  return ListView.separated(
                    // The end is padded by the FAB's height, so Add Store never
                    // covers the last store's status and menu.
                    padding: EdgeInsetsDirectional.fromSTEB(
                        gutter, 0, gutter, AppSpacing.fabClearance),
                    itemCount: stores.length,
                    separatorBuilder: (_, _) =>
                        const SizedBox(height: AppSpacing.xs),
                    itemBuilder: (context, i) {
                      final s = stores[i];
                      final active = s.status.toUpperCase() == 'ACTIVE';
                      return _StoreCard(
                        store: s,
                        inline: inline,
                        canManageSlots: _canManageSlots(auth, s.id),
                        onAction: (action) {
                          switch (action) {
                            case _StoreAction.edit:
                              _showEditStoreDialog(context, ref, s);
                            case _StoreAction.zones:
                              _showZonesDialog(context, ref, s);
                            case _StoreAction.instruments:
                              showDialog<void>(
                                context: context,
                                builder: (_) => StoreInstrumentsDialog(
                                    store: s, isManager: isManager),
                              );
                            case _StoreAction.delivery:
                              _showDeliveryAreasDialog(context, ref, s);
                            case _StoreAction.slots:
                              _showFulfilmentWindowsSheet(context, s);
                            case _StoreAction.toggle:
                              _toggleStoreStatus(context, ref, s, active);
                          }
                        },
                      );
                    },
                  );
                });
              },
            ),
          ),
        ],
      ),
    );
  }

  void _showAddStoreDialog(BuildContext context, WidgetRef ref) {
    showDialog(
      context: context,
      builder: (_) => _AddStoreDialog(
        onCreated: () => ref.invalidate(storesProvider),
      ),
    );
  }

  void _showEditStoreDialog(BuildContext context, WidgetRef ref, StoreInfo store) {
    showDialog(
      context: context,
      builder: (_) => _EditStoreDialog(
        store: store,
        onSaved: () => ref.invalidate(storesProvider),
      ),
    );
  }

  void _showZonesDialog(BuildContext context, WidgetRef ref, StoreInfo store) {
    showDialog(
      context: context,
      builder: (_) => _ZonesDialog(store: store),
    );
  }

  void _showDeliveryAreasDialog(
      BuildContext context, WidgetRef ref, StoreInfo store) {
    showDialog(
      context: context,
      builder: (_) => _DeliveryAreasDialog(store: store),
    );
  }

  /// Delivery & collection slots (delivery-and-collection-slots): the windows
  /// this store offers, weekday by weekday, for delivery and collection each.
  void _showFulfilmentWindowsSheet(BuildContext context, StoreInfo store) {
    showAdaptiveSheet(
      context: context,
      title: 'Delivery & collection slots',
      maxWidth: 720,
      builder: (_) => FulfilmentWindowsScreen(store: store),
    );
  }

  /// Activate / deactivate a store. Deactivating is consequential (it hides the
  /// store from the storefront and POS), so we confirm first.
  Future<void> _toggleStoreStatus(
      BuildContext context, WidgetRef ref, StoreInfo store, bool active) async {
    final next = active ? 'INACTIVE' : 'ACTIVE';
    if (active) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: Text('Close ${store.name}?'),
          content: const Text(
              'It stops taking online orders and till sales until it is opened '
              'again. Nothing is deleted.'),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(ctx, false),
                child: const Text('Cancel')),
            FilledButton(
                onPressed: () => Navigator.pop(ctx, true),
                child: const Text('Close store')),
          ],
        ),
      );
      if (ok != true) return;
    }
    try {
      await ref.read(apiClientProvider).dio.patch(
        '/${ApiConstants.tenant}/admin/stores/${store.id}/status',
        data: {'status': next},
      );
      ref.invalidate(storesProvider);
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
            content: Text(
                '${store.name} is ${next == 'ACTIVE' ? 'open' : 'closed'}.')),
      );
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
            content: Text(
                friendlyError(e, fallback: 'Could not update store status.'))),
      );
    }
  }
}

/// One store. Wide, its tools and a labelled Open/Closed switch sit on the
/// row. Narrow, the row keeps only the name, the details and the status in
/// words underneath, and everything else — the switch included, as *Close
/// store* / *Open store* — is in one ⋮ menu.
class _StoreCard extends StatelessWidget {
  const _StoreCard({
    required this.store,
    required this.inline,
    required this.canManageSlots,
    required this.onAction,
  });

  final StoreInfo store;
  final bool inline;

  /// Whether the signed-in staff member may set this store's delivery and
  /// collection windows — an owner, or a manager store-held for it; never a
  /// storekeeper, who order-svc would only refuse.
  final bool canManageSlots;
  final void Function(_StoreAction action) onAction;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final type = store.type.toUpperCase();
    final isWarehouse = type == 'WAREHOUSE';
    final isDark = type == 'DARK_STORE';
    final open = store.status.toUpperCase() == 'ACTIVE';
    final status = _storeStatusLabel(store.status);
    final location = [store.city, store.country]
        .where((e) => e != null && e.isNotEmpty)
        .join(', ');
    final details = Text([
      store.code,
      if (location.isNotEmpty) location,
      if (isDark) 'Dark store · delivery only, no till',
      store.showPrices ? 'Prices shown' : 'Catalog mode',
    ].join(' · '));

    return Card(
      child: ListTile(
        onTap: () => onAction(_StoreAction.edit),
        leading: CircleAvatar(
          backgroundColor: cs.primaryContainer,
          child: Icon(
            isWarehouse
                ? Icons.warehouse_outlined
                : isDark
                    ? Icons.nightlight_outlined
                    : Icons.store_outlined,
            color: cs.onPrimaryContainer,
          ),
        ),
        title: Text(store.name,
            style: const TextStyle(fontWeight: FontWeight.bold)),
        subtitle: inline
            ? details
            : Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  details,
                  Padding(
                    padding: const EdgeInsetsDirectional.only(top: AppSpacing.xs),
                    child: StatusBadge(
                      status,
                      key: Key('store-status-${store.id}'),
                      tone: open ? StatusTone.success : StatusTone.neutral,
                    ),
                  ),
                ],
              ),
        trailing: inline
            ? Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextButton.icon(
                    onPressed: () => onAction(_StoreAction.zones),
                    icon: const Icon(Icons.grid_view_outlined, size: 18),
                    label: const Text('Zones'),
                  ),
                  TextButton.icon(
                    onPressed: () => onAction(_StoreAction.instruments),
                    icon: const Icon(Icons.scale_outlined, size: 18),
                    label: const Text('Instruments'),
                  ),
                  TextButton.icon(
                    onPressed: () => onAction(_StoreAction.delivery),
                    icon: const Icon(Icons.local_shipping_outlined, size: 18),
                    label: const Text('Delivery'),
                  ),
                  if (canManageSlots)
                    IconButton(
                      key: Key('store-slots-${store.id}'),
                      tooltip: 'Delivery & collection slots',
                      onPressed: () => onAction(_StoreAction.slots),
                      icon:
                          const Icon(Icons.event_available_outlined, size: 18),
                    ),
                  const SizedBox(width: AppSpacing.sm),
                  // The store's on/off switch, labelled with what it is now.
                  MergeSemantics(
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(status, style: theme.textTheme.labelLarge),
                        const SizedBox(width: AppSpacing.xs),
                        Tooltip(
                          message: open ? 'Close this store' : 'Open this store',
                          child: Switch(
                            key: Key('store-status-${store.id}'),
                            value: open,
                            onChanged: (_) => onAction(_StoreAction.toggle),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: AppSpacing.sm),
                  Icon(Icons.edit_outlined, size: 18, color: cs.outline),
                ],
              )
            : PopupMenuButton<_StoreAction>(
                key: Key('store-menu-${store.id}'),
                tooltip: 'Actions for ${store.name}',
                onSelected: onAction,
                itemBuilder: (_) => [
                  _item(_StoreAction.edit, Icons.edit_outlined, 'Edit store'),
                  _item(_StoreAction.zones, Icons.grid_view_outlined, 'Zones'),
                  _item(_StoreAction.instruments, Icons.scale_outlined,
                      'Instruments'),
                  _item(_StoreAction.delivery, Icons.local_shipping_outlined,
                      'Delivery'),
                  if (canManageSlots)
                    _item(_StoreAction.slots, Icons.event_available_outlined,
                        'Delivery & collection slots'),
                  const PopupMenuDivider(),
                  _item(
                    _StoreAction.toggle,
                    open ? Icons.storefront_outlined : Icons.store_outlined,
                    open ? 'Close store' : 'Open store',
                  ),
                ],
              ),
      ),
    );
  }

  static PopupMenuItem<_StoreAction> _item(
          _StoreAction value, IconData icon, String label) =>
      PopupMenuItem<_StoreAction>(
        value: value,
        child: Row(
          children: [
            Icon(icon, size: 20),
            const SizedBox(width: AppSpacing.md),
            Flexible(child: Text(label)),
          ],
        ),
      );
}

/// Lists, creates, edits and activates/deactivates the zones (aisles/racks)
/// within a store. Stock batches are pinned to a (store, zone).
class _ZonesDialog extends ConsumerWidget {
  final StoreInfo store;
  const _ZonesDialog({required this.store});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final zonesAsync = ref.watch(zonesProvider(store.id));
    return AlertDialog(
      title: Row(
        children: [
          Expanded(child: Text('Zones · ${store.name}')),
          FilledButton.icon(
            onPressed: () => _showZoneForm(context, ref, null),
            icon: const Icon(Icons.add, size: 18),
            label: const Text('Add zone'),
          ),
        ],
      ),
      content: SizedBox(
        width: 460,
        height: 420,
        child: zonesAsync.when(
          loading: () => const LoadingView(label: 'Loading zones…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load zones.'),
            onRetry: () => ref.invalidate(zonesProvider(store.id)),
          ),
          data: (zones) {
            if (zones.isEmpty) {
              return Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.grid_view_outlined, size: 56, color: cs.outlineVariant),
                    const SizedBox(height: 12),
                    const Text('No zones yet'),
                    const SizedBox(height: 4),
                    Text('Add aisles, racks, cold-rooms or back-store areas\n'
                        'so received stock can be pinned to a location.',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: cs.outline, fontSize: 12)),
                  ],
                ),
              );
            }
            return ListView.separated(
              itemCount: zones.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (_, i) {
                final z = zones[i];
                final active = z.status.toUpperCase() == 'ACTIVE';
                return ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(Icons.shelves, color: cs.primary),
                  title: Text(z.name,
                      style: const TextStyle(fontWeight: FontWeight.bold)),
                  // The kind in words and the status as a badge under the
                  // name; edit and switch on or off in one menu on a phone,
                  // so the name keeps the width.
                  subtitle: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('${z.code} · ${humanizeCode(z.type)}'),
                      const SizedBox(height: AppSpacing.xs),
                      StatusBadge(
                        humanizeCode(z.status),
                        tone: active ? StatusTone.success : StatusTone.neutral,
                      ),
                    ],
                  ),
                  trailing: context.isCompact
                      ? PopupMenuButton<String>(
                          key: Key('zone-actions-${z.id}'),
                          tooltip: 'Edit or ${active ? 'deactivate' : 'activate'}',
                          onSelected: (a) => a == 'edit'
                              ? _showZoneForm(context, ref, z)
                              : _toggleStatus(context, ref, z, active),
                          itemBuilder: (_) => [
                            const PopupMenuItem(value: 'edit', child: Text('Edit')),
                            PopupMenuItem(
                              value: 'toggle',
                              child: Text(active ? 'Deactivate' : 'Activate'),
                            ),
                          ],
                        )
                      : Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            IconButton(
                              tooltip: 'Edit',
                              icon: const Icon(Icons.edit_outlined, size: 18),
                              onPressed: () => _showZoneForm(context, ref, z),
                            ),
                            IconButton(
                              tooltip: active ? 'Deactivate' : 'Activate',
                              icon: Icon(
                                active ? Icons.toggle_on : Icons.toggle_off_outlined,
                                color: active ? context.status.success : cs.outline,
                              ),
                              onPressed: () => _toggleStatus(context, ref, z, active),
                            ),
                          ],
                        ),
                );
              },
            );
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Close'),
        ),
      ],
    );
  }

  void _showZoneForm(BuildContext context, WidgetRef ref, ZoneInfo? zone) {
    showDialog(
      context: context,
      builder: (_) => _ZoneFormDialog(
        storeId: store.id,
        zone: zone,
        onSaved: () => ref.invalidate(zonesProvider(store.id)),
      ),
    );
  }

  Future<void> _toggleStatus(
      BuildContext context, WidgetRef ref, ZoneInfo zone, bool active) async {
    final next = active ? 'INACTIVE' : 'ACTIVE';
    try {
      await ref.read(apiClientProvider).dio.patch(
        '/${ApiConstants.tenant}/admin/stores/${store.id}/zones/${zone.id}/status',
        data: {'status': next},
      );
      ref.invalidate(zonesProvider(store.id));
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
            content: Text(
                friendlyError(e, fallback: 'Could not update zone status.'))),
      );
    }
  }
}

/// Lists / adds / deletes pincode delivery coverage for a store.
class _DeliveryAreasDialog extends ConsumerStatefulWidget {
  final StoreInfo store;
  const _DeliveryAreasDialog({required this.store});

  @override
  ConsumerState<_DeliveryAreasDialog> createState() =>
      _DeliveryAreasDialogState();
}

class _DeliveryAreasDialogState extends ConsumerState<_DeliveryAreasDialog> {
  final _pincodeCtrl = TextEditingController();
  final _priorityCtrl = TextEditingController(text: '100');
  bool _adding = false;
  String? _error;

  @override
  void dispose() {
    _pincodeCtrl.dispose();
    _priorityCtrl.dispose();
    super.dispose();
  }

  Future<void> _add() async {
    final pincode = _pincodeCtrl.text.trim();
    if (pincode.isEmpty) {
      setState(() => _error = 'Enter a pincode.');
      return;
    }
    setState(() {
      _adding = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.tenant}/admin/stores/${widget.store.id}/delivery-areas',
        data: {
          'pincode': pincode,
          'priority': int.tryParse(_priorityCtrl.text.trim()) ?? 100,
        },
      );
      if (!mounted) return;
      _pincodeCtrl.clear();
      ref.invalidate(deliveryAreasProvider(widget.store.id));
      setState(() => _adding = false);
    } catch (e) {
      setState(() {
        _adding = false;
        final status = e is DioException ? e.response?.statusCode : null;
        _error = status == 409
            ? 'This store already covers that pincode.'
            : friendlyError(e, fallback: 'Could not add delivery area.');
      });
    }
  }

  Future<void> _delete(DeliveryArea area) async {
    try {
      await ref.read(apiClientProvider).dio.delete(
          '/${ApiConstants.tenant}/admin/stores/${widget.store.id}/delivery-areas/${area.id}');
      ref.invalidate(deliveryAreasProvider(widget.store.id));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(
              friendlyError(e, fallback: 'Could not remove delivery area.'))));
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(deliveryAreasProvider(widget.store.id));
    return AlertDialog(
      title: Text('Delivery areas · ${widget.store.name}'),
      content: SizedBox(
        width: 440,
        height: 420,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'Pincodes this store fulfils for home delivery. '
              'Lower priority wins when multiple stores cover the same pincode.',
              style: TextStyle(color: cs.outline, fontSize: 12),
            ),
            const SizedBox(height: 12),
            if (_error != null) ...[
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: cs.errorContainer,
                  borderRadius: AppRadius.chip,
                ),
                child:
                    Text(_error!, style: TextStyle(color: cs.onErrorContainer)),
              ),
              const SizedBox(height: 8),
            ],
            Row(
              children: [
                Expanded(
                  flex: 3,
                  child: TextField(
                    controller: _pincodeCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Pincode',
                      isDense: true,
                    ),
                    onSubmitted: (_) => _add(),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    controller: _priorityCtrl,
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Priority',
                      isDense: true,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton(
                  onPressed: _adding ? null : _add,
                  child: _adding
                      ?  SizedBox(
                          height: 16,
                          width: 16,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                      : const Text('Add'),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Expanded(
              child: async.when(
                loading: () =>
                    const LoadingView(label: 'Loading delivery areas…'),
                error: (e, _) => ErrorView(
                  message: friendlyError(e,
                      fallback: 'Could not load delivery areas.'),
                  onRetry: () =>
                      ref.invalidate(deliveryAreasProvider(widget.store.id)),
                ),
                data: (areas) {
                  if (areas.isEmpty) {
                    return Center(
                      child: Text('No delivery areas yet.',
                          style: TextStyle(color: cs.outline)),
                    );
                  }
                  return ListView.separated(
                    itemCount: areas.length,
                    separatorBuilder: (_, _) => const Divider(height: 1),
                    itemBuilder: (_, i) {
                      final a = areas[i];
                      return ListTile(
                        contentPadding: EdgeInsets.zero,
                        leading:
                            Icon(Icons.pin_drop_outlined, color: cs.primary),
                        title: Text(a.pincode,
                            style:
                                const TextStyle(fontWeight: FontWeight.bold)),
                        subtitle: Text('Priority ${a.priority}'),
                        trailing: IconButton(
                          tooltip: 'Remove',
                          icon: const Icon(Icons.delete_outline, size: 20),
                          onPressed: () => _delete(a),
                        ),
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Close'),
        ),
      ],
    );
  }
}

class _ZoneFormDialog extends ConsumerStatefulWidget {
  final String storeId;
  final ZoneInfo? zone;
  final VoidCallback onSaved;
  const _ZoneFormDialog({
    required this.storeId,
    required this.zone,
    required this.onSaved,
  });

  @override
  ConsumerState<_ZoneFormDialog> createState() => _ZoneFormDialogState();
}

class _ZoneFormDialogState extends ConsumerState<_ZoneFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameCtrl;
  late final TextEditingController _codeCtrl;
  late String _type;
  bool _loading = false;
  String? _error;

  static const _types = [
    'AISLE',
    'RACK',
    'SHELF',
    'COLD_ROOM',
    'BACK_STORE',
    'RECEIVING',
    'DISPLAY',
  ];

  bool get _isEdit => widget.zone != null;

  @override
  void initState() {
    super.initState();
    _nameCtrl = TextEditingController(text: widget.zone?.name ?? '');
    _codeCtrl = TextEditingController(text: widget.zone?.code ?? '');
    final t = widget.zone?.type.toUpperCase() ?? 'AISLE';
    _type = _types.contains(t) ? t : 'AISLE';
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _codeCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    final base = '/${ApiConstants.tenant}/admin/stores/${widget.storeId}/zones';
    final body = {
      'name': _nameCtrl.text.trim(),
      'code': _codeCtrl.text.trim().toUpperCase(),
      'type': _type,
    };
    try {
      final dio = ref.read(apiClientProvider).dio;
      if (_isEdit) {
        await dio.put('$base/${widget.zone!.id}', data: body);
      } else {
        await dio.post(base, data: body);
      }
      if (!mounted) return;
      widget.onSaved();
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(_isEdit ? 'Zone updated.' : 'Zone created.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        final status = e is DioException ? e.response?.statusCode : null;
        _error = status == 409
            ? 'A zone with this code already exists in this store.'
            : status == 400
                ? 'Please check the fields and try again.'
                : friendlyError(e, fallback: 'Could not save zone.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text(_isEdit ? 'Edit zone' : 'Add zone'),
      content: SizedBox(
        width: 380,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: cs.errorContainer,
                    borderRadius: AppRadius.chip,
                  ),
                  child: Text(_error!, style: TextStyle(color: cs.onErrorContainer)),
                ),
                const SizedBox(height: 12),
              ],
              TextFormField(
                controller: _nameCtrl,
                decoration: const InputDecoration(
                  labelText: 'Zone name *',
                  hintText: 'e.g. Aisle 4 / Cold Room A',
                  prefixIcon: Icon(Icons.shelves),
                ),
                validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _codeCtrl,
                textCapitalization: TextCapitalization.characters,
                decoration: const InputDecoration(
                  labelText: 'Code *',
                  hintText: 'A4',
                  prefixIcon: Icon(Icons.tag),
                ),
                validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                initialValue: _type,
                decoration: const InputDecoration(labelText: 'Type'),
                items: _types
                    .map((t) => DropdownMenuItem(
                        value: t, child: Text(humanizeCode(t))))
                    .toList(),
                onChanged: (v) => setState(() => _type = v!),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading ? null : _submit,
          child: _loading
              ?  SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
              : Text(_isEdit ? 'Save' : 'Create'),
        ),
      ],
    );
  }
}

class _EditStoreDialog extends ConsumerStatefulWidget {
  final StoreInfo store;
  final VoidCallback onSaved;
  const _EditStoreDialog({required this.store, required this.onSaved});

  @override
  ConsumerState<_EditStoreDialog> createState() => _EditStoreDialogState();
}

class _EditStoreDialogState extends ConsumerState<_EditStoreDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameCtrl;
  late final TextEditingController _line1Ctrl;
  late final TextEditingController _cityCtrl;
  late final TextEditingController _stateCtrl;
  late final TextEditingController _countryCtrl;
  late final TextEditingController _pincodeCtrl;
  // The store's own zone; never a default (SJ-D54).
  String? _timezone;
  late bool _showPrices;
  late List<String> _payMethods;
  late String _tillPhone;
  bool _loading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final s = widget.store;
    _nameCtrl = TextEditingController(text: s.name);
    _line1Ctrl = TextEditingController(text: s.line1 ?? '');
    _cityCtrl = TextEditingController(text: s.city ?? '');
    _stateCtrl = TextEditingController(text: s.state ?? '');
    _countryCtrl = TextEditingController(text: s.country ?? '');
    _pincodeCtrl = TextEditingController(text: s.pincode ?? '');
    _timezone = s.timezone;
    _showPrices = s.showPrices;
    _payMethods = [...s.enabledPaymentMethods];
    _tillPhone = s.tillPhone;
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    _line1Ctrl.dispose();
    _cityCtrl.dispose();
    _stateCtrl.dispose();
    _countryCtrl.dispose();
    _pincodeCtrl.dispose();
    super.dispose();
  }

  String? _orNull(String v) => v.trim().isEmpty ? null : v.trim();

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    final s = widget.store;
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.tenant}/admin/stores/${s.id}',
        data: {
          'name': _nameCtrl.text.trim(),
          'line1': _orNull(_line1Ctrl.text),
          // preserved (not editable in this dialog)
          'line2': s.line2,
          'city': _orNull(_cityCtrl.text),
          'state': _orNull(_stateCtrl.text),
          'country': _orNull(_countryCtrl.text),
          'pincode': _orNull(_pincodeCtrl.text),
          'geoLat': s.geoLat,
          'geoLng': s.geoLng,
          // Null keeps the store's zone; the server never fills one in.
          'timezone': _timezone,
          'businessHours': s.businessHours,
          'showPrices': _showPrices,
          'enabledPaymentMethods': _payMethods,
          'tillPhone': _tillPhone,
        },
      );
      if (!mounted) return;
      widget.onSaved();
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Store updated.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = (e is DioException && e.response?.statusCode == 400)
            ? 'Please check the fields and try again.'
            : friendlyError(e, fallback: 'Could not update store.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final s = widget.store;
    return AlertDialog(
      title: Text('Edit ${s.name}'),
      content: SizedBox(
        width: 420,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              mainAxisSize: MainAxisSize.min,
              children: [
                if (_error != null) ...[
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: cs.errorContainer,
                      borderRadius: AppRadius.chip,
                    ),
                    child:
                        Text(_error!, style: TextStyle(color: cs.onErrorContainer)),
                  ),
                  const SizedBox(height: 12),
                ],
                // Code & type are immutable after creation.
                InputDecorator(
                  decoration: const InputDecoration(
                    labelText: 'Code · Type',
                    border: OutlineInputBorder(),
                    isDense: true,
                  ),
                  child: Text('${s.code} · ${storeTypeLabel(s.type)}'),
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _nameCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Store name *',
                    prefixIcon: Icon(Icons.store),
                  ),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? 'Required' : null,
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _line1Ctrl,
                  decoration: const InputDecoration(
                    labelText: 'Address line 1',
                    prefixIcon: Icon(Icons.location_on_outlined),
                  ),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _cityCtrl,
                        decoration: const InputDecoration(labelText: 'City'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _stateCtrl,
                        decoration: const InputDecoration(labelText: 'State'),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _countryCtrl,
                        decoration:
                            const InputDecoration(labelText: 'Country (code)'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _pincodeCtrl,
                        decoration:
                            const InputDecoration(labelText: 'Pincode / ZIP'),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                TimezoneField(
                  value: _timezone,
                  onChanged: (v) => setState(() => _timezone = v),
                ),
                const SizedBox(height: 8),
                const Divider(),
                SwitchListTile.adaptive(
                  contentPadding: EdgeInsets.zero,
                  value: _showPrices,
                  onChanged: (v) => setState(() => _showPrices = v),
                  title: const Text('Show prices on storefront'),
                  subtitle: Text(
                    _showPrices
                        ? 'Customers see prices and can buy online.'
                        : 'Catalog mode: hide prices, show only "In stock / Out of stock". '
                            'Customers can still order.',
                    style: TextStyle(color: cs.outline, fontSize: 12),
                  ),
                ),
                const SizedBox(height: 8),
                PaymentMethodsPicker(
                  selected: _payMethods,
                  onChanged: (v) => setState(() => _payMethods = v),
                ),
                const SizedBox(height: 12),
                TillPhonePicker(
                  value: _tillPhone,
                  onChanged: (v) => setState(() => _tillPhone = v),
                ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading ? null : _submit,
          child: _loading
              ?  SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
              : const Text('Save changes'),
        ),
      ],
    );
  }
}

/// Owner-facing tender toggles (requirement: cash only / cash+card / cash+card+UPI+wallet …).
/// At least one method must stay selected — a store that accepts nothing can't sell.
class PaymentMethodsPicker extends StatelessWidget {
  final List<String> selected;
  final ValueChanged<List<String>> onChanged;
  const PaymentMethodsPicker(
      {super.key, required this.selected, required this.onChanged});

  static const _all = [
    ('CASH', 'Cash', Icons.payments_outlined),
    ('CARD', 'Card', Icons.credit_card),
    ('UPI', 'UPI', Icons.qr_code_2),
    ('WALLET', 'Wallet', Icons.account_balance_wallet_outlined),
  ];

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Accepted payment methods',
            style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 4),
        Text(
          'Shown to customers at checkout and on the POS tender screen. '
          'Payments with a disabled method are rejected.',
          style: TextStyle(color: cs.outline, fontSize: 12),
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 4,
          children: [
            for (final (code, label, icon) in _all)
              FilterChip(
                avatar: Icon(icon, size: 16),
                label: Text(label),
                selected: selected.contains(code),
                onSelected: (on) {
                  final next = [...selected];
                  if (on) {
                    if (!next.contains(code)) next.add(code);
                  } else {
                    next.remove(code);
                    if (next.isEmpty) {
                      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                          content:
                              Text('At least one payment method must stay enabled.')));
                      return;
                    }
                  }
                  onChanged(next);
                },
              ),
          ],
        ),
      ],
    );
  }
}

/// The till's phone choice (phone-at-the-till): required on every sale,
/// optional, or not asked at all. Sits with the till settings, beside the
/// payment methods it is read together with.
class TillPhonePicker extends StatelessWidget {
  final String value;
  final ValueChanged<String> onChanged;
  const TillPhonePicker(
      {super.key, required this.value, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text("At the till, ask for the customer's phone",
            style: Theme.of(context).textTheme.titleSmall),
        const SizedBox(height: 4),
        Text(
          'Used to warn the buyer if something they bought is recalled. '
          'Optional lets the customer say no.',
          style: TextStyle(color: cs.outline, fontSize: 12),
        ),
        const SizedBox(height: 8),
        SegmentedButton<String>(
          segments: [
            for (final choice in tillPhoneChoices)
              ButtonSegment(value: choice, label: Text(tillPhoneLabel(choice))),
          ],
          selected: {value},
          onSelectionChanged: (s) => onChanged(s.first),
        ),
      ],
    );
  }
}

class _AddStoreDialog extends ConsumerStatefulWidget {
  final VoidCallback onCreated;
  const _AddStoreDialog({required this.onCreated});

  @override
  ConsumerState<_AddStoreDialog> createState() => _AddStoreDialogState();
}

class _AddStoreDialogState extends ConsumerState<_AddStoreDialog> {
  final _formKey = GlobalKey<FormState>();
  final _nameCtrl = TextEditingController();
  final _codeCtrl = TextEditingController();
  final _line1Ctrl = TextEditingController();
  final _cityCtrl = TextEditingController();
  final _pincodeCtrl = TextEditingController();
  String _type = 'STORE';
  String? _country;
  String? _timezone;
  bool _showPrices = true;
  List<String> _payMethods = ['CASH', 'CARD'];
  // Data-minimising default (phone-at-the-till): a new store starts by not
  // insisting on a number, same as an existing store this app cannot read.
  String _tillPhone = 'OPTIONAL';
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _nameCtrl.dispose();
    _codeCtrl.dispose();
    _line1Ctrl.dispose();
    _cityCtrl.dispose();
    _pincodeCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.tenant}/admin/stores',
        data: {
          'name': _nameCtrl.text.trim(),
          'code': _codeCtrl.text.trim().toUpperCase(),
          'type': _type,
          if (_line1Ctrl.text.trim().isNotEmpty) 'line1': _line1Ctrl.text.trim(),
          if (_cityCtrl.text.trim().isNotEmpty) 'city': _cityCtrl.text.trim(),
          'country': _country ?? ref.read(tenantInfoProvider).value?.country,
          if (_pincodeCtrl.text.trim().isNotEmpty) 'pincode': _pincodeCtrl.text.trim(),
          'timezone': _timezone,
          'showPrices': _showPrices,
          'enabledPaymentMethods': _payMethods,
          'tillPhone': _tillPhone,
        },
      );
      if (!mounted) return;
      widget.onCreated();
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Store created.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = _friendly(e);
      });
    }
  }

  String _friendly(Object e) {
    if (e is DioException) {
      final status = e.response?.statusCode;
      if (status == 409) return 'A store with this code already exists.';
      if (status == 400) return 'Please check the fields and try again.';
    }
    return friendlyError(e, fallback: 'Could not create store.');
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Dialog.fullscreen(
      child: Scaffold(
        appBar: AppBar(
          leading: IconButton(
            icon: const Icon(Icons.close),
            tooltip: 'Cancel',
            onPressed: _loading ? null : () => Navigator.pop(context),
          ),
          title: const Text('Add Store'),
          actions: [
            Padding(
              padding: const EdgeInsets.only(right: 16),
              child: Center(
                child: FilledButton(
                  onPressed: _loading ? null : _submit,
                  child: _loading
                      ? SizedBox(
                          height: 18,
                          width: 18,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: cs.onPrimary))
                      : const Text('Create store'),
                ),
              ),
            ),
          ],
        ),
        body: Form(
        key: _formKey,
        child: SingleChildScrollView(
          padding: context.pagePadding,
          child: Center(
            child: ContentBounds.form(
              child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              mainAxisSize: MainAxisSize.min,
              children: [
                if (_error != null) ...[
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: cs.errorContainer,
                      borderRadius: AppRadius.chip,
                    ),
                    child: Text(_error!, style: TextStyle(color: cs.onErrorContainer)),
                  ),
                  const SizedBox(height: 12),
                ],
                TextFormField(
                  controller: _nameCtrl,
                  textInputAction: TextInputAction.next,
                  decoration: const InputDecoration(
                    labelText: 'Store name *',
                    hintText: 'e.g. Downtown Branch',
                    prefixIcon: Icon(Icons.store),
                  ),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? 'Required' : null,
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _codeCtrl,
                        textCapitalization: TextCapitalization.characters,
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(
                          labelText: 'Code *',
                          hintText: 'STR-002',
                          prefixIcon: Icon(Icons.tag),
                        ),
                        validator: (v) =>
                            v == null || v.trim().isEmpty ? 'Required' : null,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        initialValue: _type,
                        // Fills its half of the row and ellipsizes the widest
                        // type, which used to overflow it by 102px.
                        isExpanded: true,
                        decoration: const InputDecoration(labelText: 'Type'),
                        items: const [
                          DropdownMenuItem(
                              value: 'STORE',
                              child: Text('Retail Store',
                                  overflow: TextOverflow.ellipsis)),
                          DropdownMenuItem(
                              value: 'WAREHOUSE',
                              child: Text('Warehouse',
                                  overflow: TextOverflow.ellipsis)),
                          DropdownMenuItem(
                              value: 'DARK_STORE',
                              child: Text('Dark store (online only)',
                                  overflow: TextOverflow.ellipsis)),
                        ],
                        onChanged: (v) => setState(() => _type = v!),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _line1Ctrl,
                  textInputAction: TextInputAction.next,
                  decoration: const InputDecoration(
                    labelText: 'Address line 1',
                    prefixIcon: Icon(Icons.location_on_outlined),
                  ),
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _cityCtrl,
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(labelText: 'City'),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _pincodeCtrl,
                        textInputAction: TextInputAction.next,
                        decoration: const InputDecoration(labelText: 'Pincode / ZIP'),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: CountryField(
                        value: _country ?? ref.watch(tenantInfoProvider).value?.country,
                        onChanged: (v) => setState(() => _country = v),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TimezoneField(
                        value: _timezone,
                        onChanged: (v) => setState(() => _timezone = v),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                const Divider(),
                SwitchListTile.adaptive(
                  contentPadding: EdgeInsets.zero,
                  value: _showPrices,
                  onChanged: (v) => setState(() => _showPrices = v),
                  title: const Text('Show prices on storefront'),
                  subtitle: Text(
                    _showPrices
                        ? 'Customers see prices and can buy online.'
                        : 'Catalog mode: hide prices, show only "In stock / Out of stock". '
                            'Customers can still order.',
                    style: TextStyle(color: cs.outline, fontSize: 12),
                  ),
                ),
                const SizedBox(height: 8),
                PaymentMethodsPicker(
                  selected: _payMethods,
                  onChanged: (v) => setState(() => _payMethods = v),
                ),
                const SizedBox(height: 12),
                TillPhonePicker(
                  value: _tillPhone,
                  onChanged: (v) => setState(() => _tillPhone = v),
                ),
              ],
              ),
            ),
          ),
        ),
        ),
      ),
    );
  }
}

/// A store's type in words: a shop, a warehouse, a dark store.
String storeTypeLabel(String type) => switch (type.toUpperCase()) {
      'STORE' => 'Shop',
      'WAREHOUSE' => 'Warehouse',
      'DARK_STORE' => 'Dark store',
      _ => humanizeCode(type),
    };
