import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/barcode_scanner_sheet.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/scrollable_table.dart';
import '../../shared/widgets/status_badge.dart';
import 'providers/admin_providers.dart';
import 'providers/inventory_levels_pagination.dart';
import 'inventory_forecast_tab.dart';
import 'inventory_bond_tab.dart';
import 'inventory_markdown_tab.dart';
import 'inventory_network_tab.dart';
import 'inventory_waves_tab.dart';
import 'inventory_yield_tab.dart';
import 'inventory_warehouse_tabs.dart';
import 'procurement_providers.dart';
import 'widgets/variant_search.dart';
import '../../shared/util/short_ref.dart';
import 'package:storeql_app/core/ids.dart';

/// Reads a barcode with the camera and answers it, or null when the person
/// closes the scanner; a provider so tests hand one in.
final inventoryBarcodeScannerProvider =
    Provider<Future<String?> Function(BuildContext)>(
      (ref) => scanBarcodeWithCamera,
    );

class InventoryScreen extends ConsumerStatefulWidget {
  const InventoryScreen({super.key});

  @override
  ConsumerState<InventoryScreen> createState() => _InventoryScreenState();
}

class _InventoryScreenState extends ConsumerState<InventoryScreen> {
  @override
  Widget build(BuildContext context) {
    final gutter = context.pageGutter;
    final auth = ref.watch(authNotifierProvider).value;
    // Storefront stock signal: a business-wide setting, so
    // only an owner, or a manager held to no store, may see or set it — a
    // store-held manager, storekeeper or cashier gets none of this button;
    // the server refuses them too (403), worded, if they ever reach it.
    final canSetStockSignal = auth is AuthAuthenticated &&
        (auth.roles.contains(UserRoles.owner) ||
            (auth.isManager && auth.storeIds.isEmpty));
    return DefaultTabController(
      length: 11,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // The tabs follow straight under the title, so no bottom inset.
          PageHeader(
            title: 'Inventory',
            padding: EdgeInsetsDirectional.fromSTEB(gutter, gutter, gutter, 0),
            actions: [
              if (canSetStockSignal)
                IconButton(
                  key: const Key('storefront-stock-signal'),
                  tooltip: 'Storefront stock signal',
                  onPressed: () => showDialog<void>(
                    context: context,
                    builder: (_) => const StorefrontStockSignalDialog(),
                  ),
                  icon: const Icon(Icons.visibility_outlined),
                ),
              FilledButton.icon(
                onPressed: () => _showReceiveDialog(context, ref),
                icon: const Icon(Icons.add),
                label: const Text('Receive Stock'),
              ),
            ],
          ),
          // Start-aligned at the page gutter, under the title, rather than at
          // Material's 52px scrollable offset.
          TabBar(
            isScrollable: true,
            tabAlignment: TabAlignment.start,
            padding: EdgeInsetsDirectional.only(start: gutter),
            tabs: const [
              Tab(text: 'Levels'),
              Tab(text: 'Batches'),
              Tab(text: 'Transfers'),
              Tab(text: 'Movements'),
              Tab(text: 'Thresholds'),
              Tab(text: 'Forecast'),
              Tab(text: 'Reduce to clear'),
              Tab(text: 'Bond & duty'),
              Tab(text: 'Yield & prep'),
              Tab(text: 'Picking & putaway'),
              Tab(text: 'Depot & shops'),
            ],
          ),
          const Expanded(
            child: TabBarView(
              children: [
                _LevelsTab(),
                _BatchesTab(),
                InventoryTransfersTab(),
                InventoryMovementsTab(),
                _ThresholdsTab(),
                InventoryForecastTab(),
                InventoryMarkdownTab(),
                // Bonded and duty-suspended stock: approvals, duty per unit, releases.
                InventoryBondTab(),
                // Fresh yield: what a primal breaks into, and the butchery loss against expected.
                InventoryYieldTab(),
                // Wave picking of the orders waiting at the store, and directed putaway.
                InventoryWavesTab(),
                InventoryNetworkTab(),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _showReceiveDialog(BuildContext context, WidgetRef ref) {
    showDialog(
      context: context,
      builder: (_) => _ReceiveStockDialog(
        onReceived: () {
          ref.read(inventoryLevelsPaginationProvider.notifier).refresh();
          ref.invalidate(inventoryLevelsSummaryProvider);
        },
      ),
    );
  }
}

class _LevelsTab extends ConsumerStatefulWidget {
  const _LevelsTab();

  @override
  ConsumerState<_LevelsTab> createState() => _LevelsTabState();
}

class _LevelsTabState extends ConsumerState<_LevelsTab> {
  final _searchCtrl = TextEditingController();
  String _search = '';
  bool _lowOnly = false;

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  /// Back to every loaded row — the search box emptied too, so it never shows
  /// a search that no longer applies.
  void _clearFilters() {
    _searchCtrl.clear();
    setState(() {
      _search = '';
      _lowOnly = false;
    });
  }

  /// What the filters found nothing of, in words: *No items match “teapot”*,
  /// *No items are low on stock*.
  String _noMatchTitle(bool moreToLoad) {
    final items = moreToLoad ? 'loaded items' : 'items';
    if (_search.isEmpty) return 'No $items are low on stock';
    final low = _lowOnly ? 'low-stock ' : '';
    return 'No $low$items match “$_search”';
  }

  /// Reloads the levels and what they are judged against. Completes when the
  /// first page is back, so a pull on the phone list can wait for it.
  Future<void> _refreshLevels() {
    final reload = ref
        .read(inventoryLevelsPaginationProvider.notifier)
        .refresh();
    ref.invalidate(inventoryLevelsSummaryProvider);
    ref.invalidate(thresholdsMapProvider);
    ref.invalidate(thresholdsProvider(''));
    return reload;
  }

  @override
  Widget build(BuildContext context) {
    final page = ref.watch(inventoryLevelsPaginationProvider);
    final summaryAsync = ref.watch(inventoryLevelsSummaryProvider);
    final labels =
        ref.watch(inventoryVariantLabelsProvider).value ??
        const <String, VariantLabel>{};
    final thresholds =
        ref.watch(thresholdsMapProvider).value ?? const <String, double>{};
    // Store names for the Store column and the phone rows.
    final storeNames = {
      for (final s in ref.watch(storesProvider).value ?? const <StoreInfo>[])
        s.id: s.name,
    };
    final cs = Theme.of(context).colorScheme;
    final gutter = context.pageGutter;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Search + filter bar
        Padding(
          padding: EdgeInsetsDirectional.fromSTEB(
            gutter,
            AppSpacing.lg,
            gutter,
            0,
          ),
          child: Builder(
            builder: (context) {
              final search = SearchBar(
                controller: _searchCtrl,
                hintText: 'Search product, SKU or ID…',
                leading: const Icon(Icons.search),
                onChanged: (v) => setState(() => _search = v.trim()),
              );
              final lowStock = FilterChip(
                label: const Text('Low stock'),
                avatar: Icon(
                  Icons.warning_amber_outlined,
                  size: 14,
                  color: _lowOnly ? cs.onErrorContainer : null,
                ),
                selected: _lowOnly,
                selectedColor: cs.errorContainer,
                onSelected: (v) => setState(() => _lowOnly = v),
              );
              final refresh = IconButton(
                icon: const Icon(Icons.refresh),
                tooltip: 'Refresh inventory',
                onPressed: _refreshLevels,
              );
              // On a phone the search gets the whole row (its hint was cut to a
              // few words); the filter and refresh go on the row under it.
              if (context.isCompact) {
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    search,
                    const SizedBox(height: AppSpacing.sm),
                    // The filter at the start, refresh at the end; with large
                    // text the icon drops to a line of its own, never off-screen.
                    Wrap(
                      alignment: WrapAlignment.spaceBetween,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [lowStock, refresh],
                    ),
                  ],
                );
              }
              return Row(
                children: [
                  Expanded(child: search),
                  const SizedBox(width: 12),
                  lowStock,
                  const SizedBox(width: 8),
                  refresh,
                ],
              );
            },
          ),
        ),
        const SizedBox(height: 16),

        // Summary strip — tenant-wide totals from the server-side aggregate, so
        // the counts stay accurate regardless of how many pages are loaded.
        summaryAsync.when(
          loading: () => const SizedBox.shrink(),
          error: (_, _) => const SizedBox.shrink(),
          data: (summary) {
            return Padding(
              padding: EdgeInsets.symmetric(horizontal: gutter),
              child: Row(
                children: [
                  _SummaryChip(
                    icon: Icons.inventory_2_outlined,
                    label: '${summary.skuCount} SKUs',
                    color: cs.secondaryContainer,
                  ),
                  const SizedBox(width: 8),
                  if (summary.lowStockCount > 0)
                    _SummaryChip(
                      icon: Icons.warning_amber_outlined,
                      label: '${summary.lowStockCount} low stock',
                      color: cs.errorContainer,
                    ),
                ],
              ),
            );
          },
        ),
        const SizedBox(height: 12),

        // Table — one cursor page at a time; free-text search / low-stock filtering
        // stays a client-side filter over the rows loaded so far.
        Expanded(
          child: Builder(
            builder: (context) {
              if (page.isLoadingInitial) {
                return const LoadingView(label: 'Loading inventory…');
              }
              if (page.error != null && page.levels.isEmpty) {
                return ErrorView(
                  message: friendlyError(
                    page.error!,
                    fallback: 'Could not load inventory levels.',
                  ),
                  onRetry: () => ref
                      .read(inventoryLevelsPaginationProvider.notifier)
                      .refresh(),
                );
              }

              final filtered = page.levels.where((l) {
                if (_lowOnly && !l.isLowAgainst(thresholds)) return false;
                if (_search.isNotEmpty) {
                  final label = labels[l.variantId];
                  final hay =
                      '${label?.productName ?? ''} ${label?.sku ?? ''} ${l.variantId}'
                          .toLowerCase();
                  if (!hay.contains(_search.toLowerCase())) return false;
                }
                return true;
              }).toList();

              final loadMore = (page.hasMore || page.isLoadingMore)
                  ? Padding(
                      padding: const EdgeInsets.all(12),
                      child: page.isLoadingMore
                          ? const SizedBox(
                              height: 20,
                              width: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : OutlinedButton(
                              onPressed: () => ref
                                  .read(
                                    inventoryLevelsPaginationProvider.notifier,
                                  )
                                  .loadMore(),
                              child: const Text('Load more'),
                            ),
                    )
                  : null;

              if (filtered.isEmpty) {
                final filtering = _search.isNotEmpty || _lowOnly;
                return Column(
                  children: [
                    Expanded(
                      child: _Roomy(
                        child: filtering
                            ? EmptyState(
                                icon: Icons.search_off,
                                title: _noMatchTitle(page.hasMore),
                                // The search runs over the rows loaded so far;
                                // say so rather than imply it searched them all.
                                message: page.hasMore
                                    ? 'Only the items loaded so far are searched.'
                                    : null,
                                action: TextButton(
                                  onPressed: _clearFilters,
                                  child: const Text('Clear filters'),
                                ),
                              )
                            : const EmptyState(
                                icon: Icons.inventory_2_outlined,
                                title: 'No stock recorded yet',
                                message:
                                    'Stock shows here, store by store, once it '
                                    'is received.',
                              ),
                      ),
                    ),
                    ?loadMore,
                  ],
                );
              }

              return Column(
                children: [
                  Expanded(
                    child: LayoutBuilder(
                      builder: (context, bc) {
                        final wide =
                            AppBreakpoints.classOf(bc.maxWidth) !=
                            WindowClass.compact;
                        if (wide) {
                          return _WideTable(
                            levels: filtered,
                            labels: labels,
                            storeNames: storeNames,
                            thresholds: thresholds,
                            onChanged: _refreshLevels,
                          );
                        }
                        return _NarrowList(
                          levels: filtered,
                          labels: labels,
                          storeNames: storeNames,
                          thresholds: thresholds,
                          onChanged: _refreshLevels,
                          onRefresh: _refreshLevels,
                        );
                      },
                    ),
                  ),
                  ?loadMore,
                ],
              );
            },
          ),
        ),
      ],
    );
  }
}

class _BatchesTab extends ConsumerStatefulWidget {
  const _BatchesTab();

  @override
  ConsumerState<_BatchesTab> createState() => _BatchesTabState();
}

class _BatchesTabState extends ConsumerState<_BatchesTab> {
  String? _storeId;
  String? _zoneId;
  String? _materialStatus;
  String _search = '';

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);
    final gutter = context.pageGutter;

    final filters = Padding(
      padding: EdgeInsetsDirectional.fromSTEB(gutter, AppSpacing.lg, gutter, 0),
      child: Wrap(
        spacing: 12,
        runSpacing: 12,
        children: [
          SizedBox(
            width: 220,
            child: storesAsync.when(
              loading: () => const LinearProgressIndicator(),
              error: (e, _) => Text(
                'Could not load stores',
                style: TextStyle(color: cs.error),
              ),
              data: (stores) => DropdownButtonFormField<String>(
                initialValue: _storeId,
                isExpanded: true,
                decoration: const InputDecoration(
                  labelText: 'Store',
                  isDense: true,
                  prefixIcon: Icon(Icons.store_outlined),
                ),
                items: stores
                    .map(
                      (s) => DropdownMenuItem(
                        value: s.id,
                        child: Text(
                          '${s.name} (${s.code})',
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    )
                    .toList(),
                onChanged: (v) => setState(() {
                  _storeId = v;
                  _zoneId = null;
                }),
              ),
            ),
          ),
          if (_storeId != null)
            SizedBox(
              width: 200,
              child: Consumer(
                builder: (context, ref, _) {
                  final zonesAsync = ref.watch(zonesProvider(_storeId!));
                  return zonesAsync.when(
                    loading: () => const LinearProgressIndicator(),
                    error: (_, _) => const SizedBox.shrink(),
                    data: (zones) => DropdownButtonFormField<String?>(
                      initialValue: _zoneId,
                      isExpanded: true,
                      decoration: const InputDecoration(
                        labelText: 'Zone',
                        isDense: true,
                        prefixIcon: Icon(Icons.grid_view_outlined),
                      ),
                      items: [
                        const DropdownMenuItem(
                          value: null,
                          child: Text('All zones'),
                        ),
                        ...zones.map(
                          (z) => DropdownMenuItem(
                            value: z.id,
                            child: Text(
                              '${z.name} (${z.code})',
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ),
                      ],
                      onChanged: (v) => setState(() => _zoneId = v),
                    ),
                  );
                },
              ),
            ),
          SizedBox(
            width: 190,
            child: DropdownButtonFormField<String?>(
              initialValue: _materialStatus,
              isExpanded: true,
              decoration: const InputDecoration(
                labelText: 'Material status',
                isDense: true,
              ),
              items: [
                const DropdownMenuItem(value: null, child: Text('Any status')),
                // The same words the rows' badges use.
                for (final m in batchMaterialStatuses)
                  DropdownMenuItem(
                    value: m,
                    child: Text(materialStatusLabel(m)),
                  ),
              ],
              onChanged: (v) => setState(() => _materialStatus = v),
            ),
          ),
          SizedBox(
            width: 240,
            child: SearchBar(
              hintText: 'Search batch or product…',
              leading: const Icon(Icons.search),
              onChanged: (v) => setState(() => _search = v.trim()),
            ),
          ),
          if (_storeId != null)
            IconButton(
              icon: const Icon(Icons.refresh),
              tooltip: 'Refresh batches',
              onPressed: () => ref.invalidate(batchesProvider(_storeId!)),
            ),
        ],
      ),
    );
    final header = <Widget>[
      filters,
      if (_storeId != null) ...[
        const SizedBox(height: 8),
        _ExpiringBanner(storeId: _storeId!),
      ],
      const SizedBox(height: 8),
    ];

    return LayoutBuilder(
      builder: (context, tab) {
        // Under the table's width the batches are cards, and the filters and
        // the expiring banner scroll away with them: on a phone at large text
        // the fixed rows alone are taller than the screen.
        if (tab.maxWidth < _batchTableWidth) {
          return CustomScrollView(
            slivers: [
              SliverToBoxAdapter(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: header,
                ),
              ),
              _batchesBody(sliver: true),
            ],
          );
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ...header,
            Expanded(child: _batchesBody(sliver: false)),
          ],
        );
      },
    );
  }

  /// The batches under the filters: a box to fill the rest of the tab beside
  /// the table, or a sliver under the scrolling filters on a narrow screen.
  Widget _batchesBody({required bool sliver}) {
    Widget roomy(Widget child) => sliver
        ? SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsetsDirectional.only(top: AppSpacing.xl),
              child: child,
            ),
          )
        : _Roomy(child: child);
    Widget box(Widget child) =>
        sliver ? SliverToBoxAdapter(child: child) : child;
    return _storeId == null
        ? roomy(
            const EmptyState(
              icon: Icons.store_outlined,
              title: 'Choose a store',
              message:
                  'Its batches show here, with their expiry and '
                  'material status.',
            ),
          )
        : Consumer(
            builder: (context, ref, _) {
              final batchesAsync = ref.watch(batchesProvider(_storeId!));
              final zonesAsync = ref.watch(zonesProvider(_storeId!));
              final zoneNames = <String, String>{
                for (final z in zonesAsync.value ?? const <ZoneInfo>[])
                  z.id: '${z.name} (${z.code})',
              };
              return batchesAsync.when(
                loading: () =>
                    box(const LoadingView(label: 'Loading batches…')),
                error: (e, _) => box(
                  ErrorView(
                    message: friendlyError(
                      e,
                      fallback: 'Could not load batches.',
                    ),
                    onRetry: () => ref.invalidate(batchesProvider(_storeId!)),
                  ),
                ),
                data: (batches) {
                  // Each batch by its product's name; a short handle
                  // only while the names load.
                  final labels =
                      ref
                          .watch(
                            variantLabelsProvider(
                              variantIdsKey(batches.map((b) => b.variantId)),
                            ),
                          )
                          .value ??
                      const <String, VariantLabel>{};
                  final q = _search.toLowerCase();
                  final filtered = batches.where((b) {
                    if (_zoneId != null && b.zoneId != _zoneId) {
                      return false;
                    }
                    if (_materialStatus != null &&
                        b.materialStatus != _materialStatus) {
                      return false;
                    }
                    if (q.isNotEmpty) {
                      final hay =
                          '${b.batchNo} '
                                  '${labels[b.variantId]?.productName ?? ''} '
                                  '${labels[b.variantId]?.sku ?? ''} '
                                  '${b.variantId}'
                              .toLowerCase();
                      if (!hay.contains(q)) return false;
                    }
                    return true;
                  }).toList();

                  if (filtered.isEmpty) {
                    return roomy(
                      batches.isEmpty
                          ? const EmptyState(
                              icon: Icons.inventory_outlined,
                              title: 'No batches at this store yet',
                              message:
                                  'Stock received here is kept '
                                  'batch by batch.',
                            )
                          : const EmptyState(
                              icon: Icons.search_off,
                              title: 'No batches match',
                              message:
                                  'Try another zone, status or '
                                  'search.',
                            ),
                    );
                  }

                  void refresh() => ref.invalidate(batchesProvider(_storeId!));

                  if (sliver) {
                    return _BatchNarrowList(
                      batches: filtered,
                      labels: labels,
                      zoneNames: zoneNames,
                      onMaterialStatus: (b) => showMaterialStatusDialog(
                        context,
                        ref,
                        batch: b,
                        onChanged: refresh,
                      ),
                    );
                  }
                  return _BatchWideTable(
                    batches: filtered,
                    labels: labels,
                    zoneNames: zoneNames,
                    onMaterialStatus: (b) => showMaterialStatusDialog(
                      context,
                      ref,
                      batch: b,
                      onChanged: refresh,
                    ),
                  );
                },
              );
            },
          );
  }
}

/// From this width the Batches tab shows a table under pinned filters;
/// narrower, cards that scroll with the filters.
const double _batchTableWidth = 700;

/// Banner of batches expiring within 30 days for the selected store.
class _ExpiringBanner extends ConsumerStatefulWidget {
  final String storeId;
  const _ExpiringBanner({required this.storeId});

  @override
  ConsumerState<_ExpiringBanner> createState() => _ExpiringBannerState();
}

class _ExpiringBannerState extends ConsumerState<_ExpiringBanner> {
  bool _dismissed = false;

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(
      expiringBatchesProvider((storeId: widget.storeId, withinDays: 30)),
    );
    return async.when(
      loading: () => const SizedBox.shrink(),
      error: (_, _) => const SizedBox.shrink(),
      data: (rows) {
        if (rows.isEmpty || _dismissed) return const SizedBox.shrink();
        final cs = Theme.of(context).colorScheme;
        return Padding(
          padding: EdgeInsets.symmetric(horizontal: context.pageGutter),
          child: MaterialBanner(
            backgroundColor: cs.errorContainer.withValues(alpha: 0.45),
            padding: const EdgeInsetsDirectional.symmetric(
              horizontal: AppSpacing.md,
              vertical: AppSpacing.sm,
            ),
            leading: Icon(
              Icons.event_busy,
              size: 18,
              color: cs.onErrorContainer,
            ),
            content: Text(
              '${rows.length == 1 ? '1 batch expires' : '${rows.length} batches expire'}'
              ' within 30 days — e.g. ${rows.first.batchNo}'
              '${rows.first.expiryDate != null ? ', on ${AppFormat.date(rows.first.expiryDate)}' : ''}',
              style: Theme.of(
                context,
              ).textTheme.bodySmall?.copyWith(color: cs.onErrorContainer),
            ),
            actions: [
              TextButton(
                onPressed: () => setState(() => _dismissed = true),
                child: Text(
                  'Dismiss',
                  style: TextStyle(color: cs.onErrorContainer),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _ReceiveStockDialog extends ConsumerStatefulWidget {
  final VoidCallback onReceived;
  const _ReceiveStockDialog({required this.onReceived});

  @override
  ConsumerState<_ReceiveStockDialog> createState() =>
      _ReceiveStockDialogState();
}

class _ReceiveStockDialogState extends ConsumerState<_ReceiveStockDialog> {
  /// The tenant's currency symbol before the cost, or none while it is unknown.
  String? _costPrefix() {
    final symbol = AppFormat.currencySymbol(
      ref.watch(tenantInfoProvider).value?.currency,
    );
    return symbol.isEmpty ? null : '$symbol ';
  }

  final _formKey = GlobalKey<FormState>();

  /// The product being received, found by name or SKU or scanned; the
  /// request carries its variant id. [_variantCtrl] shows it in words.
  VariantChoice? _variant;
  final _variantCtrl = TextEditingController();
  final _qtyCtrl = TextEditingController();
  final _costCtrl = TextEditingController();
  final _batchCtrl = TextEditingController();
  /// The expiry as inventory-svc takes it (`2026-10-04`), or null. The field
  /// shows it as a date (*4 Oct 2026*); the request carries this.
  String? _expiry;
  final _expiryCtrl = TextEditingController();

  /// What the last scanned label filled in, so the operator can see that the lot
  /// and expiry came off the case rather than from their own typing.
  List<String> _fromLabel = const [];
  String? _storeId;
  String? _zoneId;
  // Whose the stock is: ours, or the supplier's until it sells (consignment).
  String _ownership = 'OWNED';
  String? _supplierId;
  // Bonded stock: excise goods may arrive with the duty suspended, at an approved store.
  String _dutyStatus = 'DUTY_PAID';
  bool _loading = false;
  bool _resolving = false;
  String? _error;

  @override
  void dispose() {
    _variantCtrl.dispose();
    _qtyCtrl.dispose();
    _costCtrl.dispose();
    _batchCtrl.dispose();
    _expiryCtrl.dispose();
    super.dispose();
  }

  Future<void> _scanVariant() async {
    final code = await ref.read(inventoryBarcodeScannerProvider)(context);
    if (code == null || code.isEmpty || !mounted) return;
    setState(() {
      _resolving = true;
      _fromLabel = const [];
      _error = null;
    });
    try {
      // /catalog/scan reads whatever the case carried (07.15): a linear barcode,
      // a GS1 DataMatrix element string or a Digital Link QR. A case label is
      // where a 2D code earns its keep — it names the lot and the expiry as well
      // as the item, which are exactly the two fields this form asks for next,
      // and typing them off a printed label is where they get mistyped.
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(
            '/${ApiConstants.product}/catalog/scan',
            queryParameters: {'code': code},
          );
      final d = resp.data['data'] as Map<String, dynamic>;
      final v = d['item'] as Map<String, dynamic>? ?? const {};
      final scanned = d['code'] as Map<String, dynamic>?;
      final variantId = v['variantId'] as String? ?? '';
      if (variantId.isEmpty) throw Exception('No product found for "$code"');
      final batch = scanned?['batch'] as String?;
      final expiry = scanned?['expiry'] as String?;
      setState(() {
        _choose(
          VariantChoice(
            variantId: variantId,
            productName: v['productName'] as String? ?? '',
            sku: v['sku'] as String? ?? '',
            variant: variantWords(v['attributes']),
          ),
        );
        // Filled from the label, never overwritten: what somebody typed is their
        // decision, and a scan silently replacing it would be the worse of the
        // two errors. An empty field is filled; a filled one is left alone.
        if (batch != null && _batchCtrl.text.trim().isEmpty) {
          _batchCtrl.text = batch;
        }
        if (expiry != null && _expiry == null) {
          _expiry = expiry;
          _expiryCtrl.text = AppFormat.date(expiry);
        }
        _fromLabel = [
          if (batch != null) 'lot $batch',
          if (expiry != null) 'expiry ${AppFormat.date(expiry)}',
        ];
      });
    } catch (e) {
      setState(() => _error = 'No product found for barcode "$code".');
    } finally {
      if (mounted) setState(() => _resolving = false);
    }
  }

  void _choose(VariantChoice choice) {
    _variant = choice;
    _variantCtrl.text = choice.label;
  }

  Future<void> _findVariant() async {
    final choice = await showVariantSearch(context);
    if (choice == null || !mounted) return;
    setState(() {
      _choose(choice);
      _error = null;
    });
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    if (_storeId == null) {
      setState(() => _error = 'Select a store.');
      return;
    }
    final variant = _variant;
    if (variant == null) return;
    if (_ownership == 'CONSIGNMENT' && _supplierId == null) {
      setState(
        () => _error = 'Consignment stock belongs to a supplier: pick one.',
      );
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.inventory}/admin/inventory/receive',
            data: {
              'storeId': _storeId,
              'variantId': variant.variantId,
              'qty': double.parse(_qtyCtrl.text.trim()),
              if (_batchCtrl.text.trim().isNotEmpty)
                'batchNo': _batchCtrl.text.trim(),
              if (_costCtrl.text.trim().isNotEmpty)
                'costPrice': double.parse(_costCtrl.text.trim()),
              'expiryDate': ?_expiry,
              if (_zoneId != null) 'zoneId': _zoneId,
              if (_ownership != 'OWNED') 'ownership': _ownership,
              if (_ownership == 'CONSIGNMENT') 'supplierId': _supplierId,
              if (_dutyStatus != 'DUTY_PAID') 'dutyStatus': _dutyStatus,
            },
          );
      if (!mounted) return;
      widget.onReceived();
      Navigator.pop(context);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Stock received.')));
    } catch (e) {
      setState(() {
        _loading = false;
        _error = _friendly(e);
      });
    }
  }

  String _friendly(Object e) {
    // Read the backend's structured error; fall back to a screen-specific hint.
    final code = apiErrorCode(e);
    if (code == 'INVALID_UUID') {
      return 'Check the product and the quantity.';
    }
    if (e is DioException && e.response?.statusCode == 404) {
      return 'That product is no longer in the catalogue: find it again.';
    }
    return friendlyError(e, fallback: 'Could not receive stock.');
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('Receive Stock'),
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
                    child: Text(
                      _error!,
                      style: TextStyle(color: cs.onErrorContainer),
                    ),
                  ),
                  const SizedBox(height: 12),
                ],
                storesAsync.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, _) => Text(
                    friendlyError(e, fallback: 'Could not load stores.'),
                    style: TextStyle(color: cs.error),
                  ),
                  data: (stores) => DropdownButtonFormField<String>(
                    initialValue: _storeId,
                    isExpanded: true,
                    decoration: const InputDecoration(
                      labelText: 'Store *',
                      prefixIcon: Icon(Icons.store_outlined),
                    ),
                    items: stores
                        .map(
                          (s) => DropdownMenuItem(
                            value: s.id,
                            child: Text(
                              '${s.name} (${s.code})',
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        )
                        .toList(),
                    onChanged: (v) => setState(() {
                      _storeId = v;
                      _zoneId = null;
                    }),
                    validator: (v) => v == null ? 'Required' : null,
                  ),
                ),
                if (_storeId != null) ...[
                  const SizedBox(height: 12),
                  Consumer(
                    builder: (context, ref, _) {
                      final zonesAsync = ref.watch(zonesProvider(_storeId!));
                      return zonesAsync.when(
                        loading: () => const LinearProgressIndicator(),
                        error: (e, _) => const SizedBox.shrink(),
                        data: (zones) => zones.isEmpty
                            ? const SizedBox.shrink()
                            : DropdownButtonFormField<String>(
                                initialValue: _zoneId,
                                isExpanded: true,
                                decoration: const InputDecoration(
                                  labelText: 'Zone / aisle',
                                  prefixIcon: Icon(Icons.grid_view_outlined),
                                ),
                                items: zones
                                    .map(
                                      (z) => DropdownMenuItem(
                                        value: z.id,
                                        child: Text(
                                          '${z.name} (${z.code})',
                                          overflow: TextOverflow.ellipsis,
                                        ),
                                      ),
                                    )
                                    .toList(),
                                onChanged: (v) => setState(() => _zoneId = v),
                              ),
                      );
                    },
                  ),
                ],
                const SizedBox(height: 12),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: VariantField(
                        controller: _variantCtrl,
                        onTap: _findVariant,
                        helperText:
                            'Find it by name or SKU, or scan its barcode',
                      ),
                    ),
                    const SizedBox(width: 8),
                    IconButton.filledTonal(
                      tooltip: 'Scan barcode',
                      onPressed: _resolving ? null : _scanVariant,
                      icon: _resolving
                          ? const SizedBox(
                              height: 18,
                              width: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.camera_alt_outlined),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _qtyCtrl,
                        keyboardType: const TextInputType.numberWithOptions(
                          decimal: true,
                        ),
                        decoration: const InputDecoration(
                          labelText: 'Quantity *',
                        ),
                        validator: (v) {
                          if (v == null || v.trim().isEmpty) return 'Required';
                          final n = double.tryParse(v.trim());
                          if (n == null || n <= 0) return '> 0';
                          return null;
                        },
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _costCtrl,
                        keyboardType: const TextInputType.numberWithOptions(
                          decimal: true,
                        ),
                        decoration: InputDecoration(
                          labelText: 'Cost price',
                          prefixText: _costPrefix(),
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                // Consignment stock ownership: the supplier's until it sells.
                Row(
                  children: [
                    Expanded(
                      child: DropdownButtonFormField<String>(
                        key: const Key('receive-ownership'),
                        initialValue: _ownership,
                        isExpanded: true,
                        decoration: const InputDecoration(
                          labelText: 'Whose stock',
                        ),
                        items: const [
                          DropdownMenuItem(value: 'OWNED', child: Text('Ours')),
                          DropdownMenuItem(
                            value: 'CONSIGNMENT',
                            child: Text("The supplier's (consignment)"),
                          ),
                        ],
                        onChanged: (v) => setState(() {
                          _ownership = v ?? 'OWNED';
                          if (_ownership == 'OWNED') _supplierId = null;
                        }),
                      ),
                    ),
                    if (_ownership == 'CONSIGNMENT') ...[
                      const SizedBox(width: 12),
                      Expanded(
                        child: ref
                            .watch(suppliersProvider)
                            .when(
                              loading: () => const LinearProgressIndicator(),
                              error: (e, _) => Text(
                                friendlyError(
                                  e,
                                  fallback: 'Could not load suppliers.',
                                ),
                                style: TextStyle(color: cs.error),
                              ),
                              data: (suppliers) =>
                                  DropdownButtonFormField<String>(
                                    key: const Key('receive-supplier'),
                                    initialValue: _supplierId,
                                    isExpanded: true,
                                    decoration: const InputDecoration(
                                      labelText: 'Supplier *',
                                    ),
                                    items: [
                                      for (final s in suppliers)
                                        DropdownMenuItem(
                                          value: s.id,
                                          child: Text(
                                            s.name,
                                            overflow: TextOverflow.ellipsis,
                                          ),
                                        ),
                                    ],
                                    onChanged: (v) =>
                                        setState(() => _supplierId = v),
                                  ),
                            ),
                      ),
                    ],
                  ],
                ),
                const SizedBox(height: 12),
                DropdownButtonFormField<String>(
                  key: const Key('receive-duty'),
                  initialValue: _dutyStatus,
                  isExpanded: true,
                  decoration: const InputDecoration(
                    labelText: 'Duty',
                    helperText:
                        'Duty-suspended stock goes only into a store approved as a bonded warehouse',
                    helperMaxLines: 2,
                  ),
                  items: const [
                    DropdownMenuItem(
                      value: 'DUTY_PAID',
                      child: Text('Duty paid'),
                    ),
                    DropdownMenuItem(
                      value: 'DUTY_SUSPENDED',
                      child: Text('Duty suspended (in bond)'),
                    ),
                  ],
                  onChanged: (v) =>
                      setState(() => _dutyStatus = v ?? 'DUTY_PAID'),
                ),
                const SizedBox(height: 12),
                if (_fromLabel.isNotEmpty)
                  Padding(
                    key: const Key('receive-from-label'),
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Row(
                      children: [
                        const Icon(Icons.qr_code_2, size: 16),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            'Read off the label: ${_fromLabel.join(' · ')}',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ),
                      ],
                    ),
                  ),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _batchCtrl,
                        decoration: const InputDecoration(
                          labelText: 'Batch no.',
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: TextFormField(
                        controller: _expiryCtrl,
                        readOnly: true,
                        decoration: const InputDecoration(
                          labelText: 'Expiry',
                          suffixIcon: Icon(Icons.calendar_today_outlined),
                        ),
                        onTap: () async {
                          final now = DateTime.now();
                          final picked = await showDatePicker(
                            context: context,
                            initialDate:
                                DateTime.tryParse(_expiry ?? '') ?? now,
                            firstDate: DateTime(now.year - 5),
                            lastDate: DateTime(now.year + 20),
                          );
                          if (picked != null) {
                            _expiry =
                                '${picked.year.toString().padLeft(4, '0')}-'
                                '${picked.month.toString().padLeft(2, '0')}-'
                                '${picked.day.toString().padLeft(2, '0')}';
                            _expiryCtrl.text = AppFormat.date(_expiry);
                          }
                        },
                      ),
                    ),
                  ],
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
              ? SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Theme.of(context).colorScheme.onPrimary,
                  ),
                )
              : const Text('Receive'),
        ),
      ],
    );
  }
}

/// Product name for a variant, falling back to a short UUID while labels resolve.
String _productNameOf(String variantId, Map<String, VariantLabel> labels) {
  final l = labels[variantId];
  if (l != null && l.productName.isNotEmpty) return l.productName;
  return '…${shortRef(variantId)}';
}

String _skuOf(String variantId, Map<String, VariantLabel> labels) =>
    labels[variantId]?.sku ?? '';

/// A store's name, falling back to a short handle cut from the end of its id
/// while the store list loads (or for a store the list does not have).
String _storeNameOf(String storeId, Map<String, String> storeNames) =>
    storeNames[storeId] ?? '…${shortRef(storeId)}';

void _showAdjustDialog(
  BuildContext context,
  InventoryLevel level,
  VoidCallback onChanged,
) {
  showDialog(
    context: context,
    builder: (_) => _AdjustStockDialog(level: level, onDone: onChanged),
  );
}

void _showSetThresholdDialog(
  BuildContext context,
  InventoryLevel level,
  VoidCallback onChanged,
) {
  showDialog(
    context: context,
    builder: (_) => _SetThresholdDialog(
      storeId: level.storeId,
      variantId: level.variantId,
      onDone: onChanged,
    ),
  );
}

class _WideTable extends StatelessWidget {
  final List<InventoryLevel> levels;
  final Map<String, VariantLabel> labels;
  final Map<String, String> storeNames;
  final Map<String, double> thresholds;
  final VoidCallback onChanged;
  const _WideTable({
    required this.levels,
    required this.labels,
    required this.storeNames,
    required this.thresholds,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final gutter = context.pageGutter;
    return Padding(
      // The page gutter, so the table lines up with the title and the search.
      padding: EdgeInsetsDirectional.fromSTEB(gutter, 0, gutter, AppSpacing.lg),
      child: Card(
        // Scrolls both ways inside the card, so on a tablet the Available,
        // Status and actions columns are a swipe away instead of cut off.
        child: ScrollableTable(
          child: DataTable(
            headingRowColor: WidgetStatePropertyAll(cs.surfaceContainerHigh),
            columnSpacing: 24,
            // Rows grow with their text (product and SKU, larger text sizes)
            // instead of clipping at the default 48.
            dataRowMaxHeight: double.infinity,
            columns: const [
              DataColumn(label: Text('Product')),
              DataColumn(label: Text('Store')),
              DataColumn(label: Text('On-Hand'), numeric: true),
              DataColumn(label: Text('Reserved'), numeric: true),
              DataColumn(label: Text('Available'), numeric: true),
              DataColumn(label: Text('Status')),
              DataColumn(label: Text('')),
            ],
            rows: levels.map((l) {
              final isLow = l.isLowAgainst(thresholds);
              final sku = _skuOf(l.variantId, labels);
              final threshold = thresholds['${l.storeId}:${l.variantId}'];
              return DataRow(
                color: isLow
                    ? WidgetStatePropertyAll(cs.errorContainer.withAlpha(80))
                    : null,
                cells: [
                  // Capped, so one long name wraps instead of widening the
                  // column until the whole table scrolls sideways on desktop.
                  DataCell(
                    ConstrainedBox(
                      constraints: const BoxConstraints(maxWidth: 280),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            _productNameOf(l.variantId, labels),
                            style: const TextStyle(
                              fontWeight: FontWeight.w600,
                              fontSize: 13,
                            ),
                          ),
                          if (sku.isNotEmpty)
                            Text(
                              sku,
                              style: TextStyle(fontSize: 11, color: cs.outline),
                            ),
                        ],
                      ),
                    ),
                  ),
                  DataCell(Text(_storeNameOf(l.storeId, storeNames))),
                  DataCell(Text(l.onHand.toStringAsFixed(0))),
                  DataCell(Text(l.reserved.toStringAsFixed(0))),
                  DataCell(
                    Text(
                      l.available.toStringAsFixed(0),
                      style: TextStyle(
                        color: isLow ? cs.error : cs.onSurface,
                        fontWeight: isLow ? FontWeight.bold : null,
                      ),
                    ),
                  ),
                  DataCell(
                    Semantics(
                      label: isLow ? 'Low stock' : 'Stock OK',
                      child: isLow
                          ? Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(
                                  Icons.warning_amber_outlined,
                                  size: 14,
                                  color: cs.error,
                                ),
                                const SizedBox(width: 4),
                                Text(
                                  threshold != null
                                      ? 'Low (≤${threshold.toStringAsFixed(0)})'
                                      : 'Low',
                                  style: TextStyle(
                                    color: cs.error,
                                    fontWeight: FontWeight.bold,
                                    fontSize: 12,
                                  ),
                                ),
                              ],
                            )
                          : Text(
                              'OK',
                              style: TextStyle(
                                color: context.status.success,
                                fontSize: 12,
                              ),
                            ),
                    ),
                  ),
                  DataCell(
                    PopupMenuButton<String>(
                      tooltip: 'Actions',
                      onSelected: (v) {
                        if (v == 'adjust') {
                          _showAdjustDialog(context, l, onChanged);
                        } else if (v == 'threshold') {
                          _showSetThresholdDialog(context, l, onChanged);
                        }
                      },
                      itemBuilder: (_) => const [
                        PopupMenuItem(
                          value: 'adjust',
                          child: Text('Adjust stock'),
                        ),
                        PopupMenuItem(
                          value: 'threshold',
                          child: Text('Set reorder level'),
                        ),
                      ],
                    ),
                  ),
                ],
              );
            }).toList(),
          ),
        ),
      ),
    );
  }
}

class _NarrowList extends StatelessWidget {
  final List<InventoryLevel> levels;
  final Map<String, VariantLabel> labels;
  final Map<String, String> storeNames;
  final Map<String, double> thresholds;
  final VoidCallback onChanged;
  final Future<void> Function() onRefresh;
  const _NarrowList({
    required this.levels,
    required this.labels,
    required this.storeNames,
    required this.thresholds,
    required this.onChanged,
    required this.onRefresh,
  });

  @override
  Widget build(BuildContext context) {
    final gutter = context.pageGutter;
    return RefreshIndicator.adaptive(
      onRefresh: onRefresh,
      child: ListView.separated(
        // Scrollable even when short, so a pull always refreshes.
        physics: const AlwaysScrollableScrollPhysics(),
        padding: EdgeInsetsDirectional.fromSTEB(
          gutter,
          AppSpacing.sm,
          gutter,
          AppSpacing.sm,
        ),
        itemCount: levels.length,
        separatorBuilder: (_, _) => const SizedBox(height: 4),
        itemBuilder: (context, i) {
          final l = levels[i];
          final theme = Theme.of(context);
          final cs = theme.colorScheme;
          final sku = _skuOf(l.variantId, labels);
          final isLow = l.isLowAgainst(thresholds);
          return Card(
            color: isLow ? cs.errorContainer.withAlpha(80) : null,
            child: ListTile(
              leading: Icon(
                Icons.inventory_2_outlined,
                color: isLow ? cs.error : cs.primary,
              ),
              title: Text(
                _productNameOf(l.variantId, labels),
                style: const TextStyle(
                  fontWeight: FontWeight.w600,
                  fontSize: 14,
                ),
              ),
              // Which store, then the figures: under the name rather than
              // beside it, so the name keeps most of a phone's width and the
              // same product at two stores reads as two stores.
              subtitle: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    [
                      if (sku.isNotEmpty) sku,
                      _storeNameOf(l.storeId, storeNames),
                    ].join('  ·  '),
                  ),
                  const SizedBox(height: AppSpacing.xs),
                  Text.rich(
                    TextSpan(
                      children: [
                        // The figure that matters, in on-surface ink.
                        TextSpan(
                          text: 'Avail: ${l.available.toStringAsFixed(0)}',
                          style: theme.textTheme.titleSmall?.copyWith(
                            color: isLow ? cs.error : cs.onSurface,
                            fontWeight: isLow ? FontWeight.bold : null,
                          ),
                        ),
                        TextSpan(
                          text:
                              '  ·  On-hand: ${l.onHand.toStringAsFixed(0)}  ·  Reserved: ${l.reserved.toStringAsFixed(0)}',
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              trailing: PopupMenuButton<String>(
                tooltip: 'Actions',
                onSelected: (v) {
                  if (v == 'adjust') {
                    _showAdjustDialog(context, l, onChanged);
                  } else if (v == 'threshold') {
                    _showSetThresholdDialog(context, l, onChanged);
                  }
                },
                itemBuilder: (_) => const [
                  PopupMenuItem(value: 'adjust', child: Text('Adjust stock')),
                  PopupMenuItem(
                    value: 'threshold',
                    child: Text('Set reorder level'),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}

class _BatchWideTable extends StatelessWidget {
  final List<BatchInfo> batches;
  final Map<String, VariantLabel> labels;
  final Map<String, String> zoneNames;
  final void Function(BatchInfo batch) onMaterialStatus;
  const _BatchWideTable({
    required this.batches,
    required this.labels,
    required this.zoneNames,
    required this.onMaterialStatus,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return SingleChildScrollView(
      padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
      child: Card(
        child: SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: DataTable(
            headingRowColor: WidgetStatePropertyAll(cs.surfaceContainerHigh),
            columnSpacing: AppSpacing.xl,
            // Rows grow with the product name and SKU, and with larger text,
            // instead of clipping at the default 48.
            dataRowMaxHeight: double.infinity,
            columns: const [
              DataColumn(label: Text('Batch No.')),
              DataColumn(label: Text('Product')),
              DataColumn(label: Text('Remaining'), numeric: true),
              DataColumn(label: Text('Zone')),
              DataColumn(label: Text('Expiry')),
              DataColumn(label: Text('Grade')),
              DataColumn(label: Text('Material status')),
              DataColumn(label: Text('Whose')),
              DataColumn(label: Text('Duty')),
              DataColumn(label: Text('')),
            ],
            rows: batches.map((b) {
              return DataRow(
                cells: [
                  DataCell(
                    Text(b.batchNo, style: const TextStyle(fontSize: 12)),
                  ),
                  DataCell(
                    _BatchProduct(variantId: b.variantId, labels: labels),
                  ),
                  DataCell(
                    Text(
                      '${b.remainingQty.toStringAsFixed(0)} / ${b.receivedQty.toStringAsFixed(0)}',
                    ),
                  ),
                  DataCell(
                    Text(
                      b.zoneId == null
                          ? '—'
                          : zoneNames[b.zoneId] ?? 'Unassigned',
                    ),
                  ),
                  DataCell(Text(_expiry(b.expiryDate) ?? '—')),
                  DataCell(Text(b.grade ?? '—')),
                  DataCell(_MaterialStatusBadge(status: b.materialStatus)),
                  DataCell(
                    Text(
                      b.ownership == 'CONSIGNMENT'
                          ? 'Supplier (consignment)'
                          : 'Ours',
                      style: const TextStyle(fontSize: 12),
                    ),
                  ),
                  DataCell(
                    Text(
                      b.dutyStatus == 'DUTY_SUSPENDED' ? 'In bond' : 'Paid',
                      style: const TextStyle(fontSize: 12),
                    ),
                  ),
                  DataCell(
                    IconButton(
                      icon: const Icon(Icons.tune, size: 18),
                      tooltip: 'Change material status',
                      onPressed: () => onMaterialStatus(b),
                    ),
                  ),
                ],
              );
            }).toList(),
          ),
        ),
      ),
    );
  }
}

class _BatchNarrowList extends StatelessWidget {
  final List<BatchInfo> batches;
  final Map<String, VariantLabel> labels;
  final Map<String, String> zoneNames;
  final void Function(BatchInfo batch) onMaterialStatus;
  const _BatchNarrowList({
    required this.batches,
    required this.labels,
    required this.zoneNames,
    required this.onMaterialStatus,
  });

  @override
  Widget build(BuildContext context) {
    // A sliver: the cards scroll on from the filters above them.
    return SliverPadding(
      padding: EdgeInsetsDirectional.symmetric(
        horizontal: context.pageGutter,
        vertical: AppSpacing.sm,
      ),
      sliver: SliverList.separated(
        itemCount: batches.length,
        separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.xs),
        itemBuilder: (context, i) {
          final b = batches[i];
          final expiry = _expiry(b.expiryDate);
          final zone = b.zoneId == null
              ? '—'
              : zoneNames[b.zoneId] ?? 'Unassigned';
          return Card(
            child: ListTile(
              leading: const Icon(Icons.inventory_outlined),
              title: Text(
                _productNameOf(b.variantId, labels),
                style: Theme.of(context).textTheme.titleSmall,
              ),
              // The batch, where it sits, when it expires, its status and what
              // is left, all under the name: the trailing slot keeps only the
              // action, so the name has the width and the badge a line of its
              // own at any text size.
              subtitle: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(b.batchNo),
                  Text(
                    'Zone: $zone'
                    '${expiry != null ? '  ·  Expires $expiry' : ''}',
                  ),
                  const SizedBox(height: AppSpacing.xs),
                  Wrap(
                    spacing: AppSpacing.sm,
                    runSpacing: AppSpacing.xs,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      _MaterialStatusBadge(status: b.materialStatus),
                      Text(
                        '${AppFormat.count(b.remainingQty)} of '
                        '${AppFormat.count(b.receivedQty)} left',
                      ),
                    ],
                  ),
                ],
              ),
              trailing: IconButton(
                icon: const Icon(Icons.tune, size: 18),
                tooltip: 'Change material status',
                onPressed: () => onMaterialStatus(b),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// A batch's expiry as a date (*4 Oct 2026*), or null when it has none.
String? _expiry(String? iso) =>
    iso == null || iso.isEmpty ? null : AppFormat.date(iso);

/// Centres an [EmptyState] in the space under a tab's filters, and scrolls it
/// when that space is shorter than it — a phone with large text — rather than
/// overflowing.
class _Roomy extends StatelessWidget {
  final Widget child;
  const _Roomy({required this.child});

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, bc) => SingleChildScrollView(
      child: ConstrainedBox(
        constraints: BoxConstraints(minHeight: bc.maxHeight),
        child: child,
      ),
    ),
  );
}

/// A batch's material status as the shared badge, in words.
class _MaterialStatusBadge extends StatelessWidget {
  final String status;
  const _MaterialStatusBadge({required this.status});

  @override
  Widget build(BuildContext context) {
    final words = materialStatusLabel(status);
    return StatusBadge(
      words.isEmpty ? 'Unknown' : words,
      tone: materialStatusTone(status),
    );
  }
}

/// A batch's product: its name, and its SKU under it when known.
class _BatchProduct extends StatelessWidget {
  final String variantId;
  final Map<String, VariantLabel> labels;
  const _BatchProduct({required this.variantId, required this.labels});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final sku = _skuOf(variantId, labels);
    // Capped, so one long name wraps instead of widening the column.
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 240),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _productNameOf(variantId, labels),
            style: theme.textTheme.bodyMedium?.copyWith(
              fontWeight: FontWeight.w600,
            ),
          ),
          if (sku.isNotEmpty)
            Text(
              sku,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
        ],
      ),
    );
  }
}

class _SummaryChip extends StatelessWidget {
  final IconData icon;
  final String label;
  final Color color;

  const _SummaryChip({
    required this.icon,
    required this.label,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(color: color, borderRadius: AppRadius.badge),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14),
          const SizedBox(width: 6),
          Text(
            label,
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }
}

// ── Adjust stock ─────────────────────────────────────────────────────────────

/// A fresh key per attempt at an adjustment: a UUIDv7, which every service requires.
String _newIdempotencyKey() => newId();

class _AdjustStockDialog extends ConsumerStatefulWidget {
  final InventoryLevel level;
  final VoidCallback onDone;
  const _AdjustStockDialog({required this.level, required this.onDone});

  @override
  ConsumerState<_AdjustStockDialog> createState() => _AdjustStockDialogState();
}

class _AdjustStockDialogState extends ConsumerState<_AdjustStockDialog> {
  final _formKey = GlobalKey<FormState>();
  final _deltaCtrl = TextEditingController();
  final _reasonCtrl = TextEditingController();
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _deltaCtrl.dispose();
    _reasonCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.inventory}/admin/inventory/adjust',
            data: {
              'storeId': widget.level.storeId,
              'variantId': widget.level.variantId,
              'delta': double.parse(_deltaCtrl.text.trim()),
              if (_reasonCtrl.text.trim().isNotEmpty)
                'reason': _reasonCtrl.text.trim(),
            },
            options: Options(
              headers: {'Idempotency-Key': _newIdempotencyKey()},
            ),
          );
      if (!mounted) return;
      widget.onDone();
      Navigator.pop(context);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Stock adjusted.')));
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not adjust stock.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final l = widget.level;
    return AlertDialog(
      title: const Text('Adjust stock'),
      content: SizedBox(
        width: 400,
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
                  child: Text(
                    _error!,
                    style: TextStyle(color: cs.onErrorContainer),
                  ),
                ),
                const SizedBox(height: 12),
              ],
              Text(
                'Available: ${l.available.toStringAsFixed(0)}  ·  On-hand: ${l.onHand.toStringAsFixed(0)}',
                style: TextStyle(color: cs.outline, fontSize: 13),
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _deltaCtrl,
                keyboardType: const TextInputType.numberWithOptions(
                  decimal: true,
                  signed: true,
                ),
                decoration: const InputDecoration(
                  labelText: 'Delta *',
                  helperText: 'Positive adds stock, negative removes',
                  prefixIcon: Icon(Icons.exposure_outlined),
                ),
                validator: (v) {
                  if (v == null || v.trim().isEmpty) return 'Required';
                  final n = double.tryParse(v.trim());
                  if (n == null || n == 0) return 'Non-zero number';
                  return null;
                },
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _reasonCtrl,
                decoration: const InputDecoration(
                  labelText: 'Reason',
                  hintText: 'e.g. shrinkage, damage, found stock',
                  prefixIcon: Icon(Icons.notes_outlined),
                ),
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
              ? SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Theme.of(context).colorScheme.onPrimary,
                  ),
                )
              : const Text('Adjust'),
        ),
      ],
    );
  }
}

// ── Set reorder level ────────────────────────────────────────────────────────

class _SetThresholdDialog extends ConsumerStatefulWidget {
  final String storeId;
  final String variantId;
  final VoidCallback onDone;
  const _SetThresholdDialog({
    required this.storeId,
    required this.variantId,
    required this.onDone,
  });

  @override
  ConsumerState<_SetThresholdDialog> createState() =>
      _SetThresholdDialogState();
}

class _SetThresholdDialogState extends ConsumerState<_SetThresholdDialog> {
  final _formKey = GlobalKey<FormState>();
  final _thresholdCtrl = TextEditingController();
  final _maxQtyCtrl = TextEditingController();

  /// The product found by name or SKU when the dialog was not opened on one;
  /// [_variantCtrl] shows it in words.
  VariantChoice? _variant;
  final _variantCtrl = TextEditingController();
  String? _storeId;
  bool _loading = false;
  String? _error;

  bool get _needsStorePick => widget.storeId.isEmpty;
  bool get _needsVariantPick => widget.variantId.isEmpty;

  @override
  void initState() {
    super.initState();
    _storeId = widget.storeId.isEmpty ? null : widget.storeId;
  }

  Future<void> _findVariant() async {
    final choice = await showVariantSearch(context);
    if (choice == null || !mounted) return;
    setState(() {
      _variant = choice;
      _variantCtrl.text = choice.label;
      _error = null;
    });
  }

  @override
  void dispose() {
    _thresholdCtrl.dispose();
    _maxQtyCtrl.dispose();
    _variantCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    final storeId = _storeId ?? widget.storeId;
    final variantId = _variant?.variantId ?? widget.variantId;
    if (storeId.isEmpty) {
      setState(() => _error = 'Select a store.');
      return;
    }
    if (variantId.isEmpty) {
      setState(() => _error = 'Choose a product.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.inventory}/admin/inventory/thresholds',
            data: {
              'storeId': storeId,
              'variantId': variantId,
              'threshold': double.parse(_thresholdCtrl.text.trim()),
              if (_maxQtyCtrl.text.trim().isNotEmpty)
                'maxQty': double.parse(_maxQtyCtrl.text.trim()),
            },
          );
      if (!mounted) return;
      widget.onDone();
      Navigator.pop(context);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Reorder level saved.')));
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not set reorder level.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: const Text('Set reorder level'),
      content: SizedBox(
        width: 400,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
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
                    child: Text(
                      _error!,
                      style: TextStyle(color: cs.onErrorContainer),
                    ),
                  ),
                  const SizedBox(height: 12),
                ],
                Text(
                  'Raises a low-stock signal when available qty is at or below this level.',
                  style: TextStyle(color: cs.outline, fontSize: 13),
                ),
                if (_needsStorePick) ...[
                  const SizedBox(height: 12),
                  storesAsync.when(
                    loading: () => const LinearProgressIndicator(),
                    error: (e, _) => Text(
                      friendlyError(e, fallback: 'Could not load stores.'),
                      style: TextStyle(color: cs.error),
                    ),
                    data: (stores) => DropdownButtonFormField<String>(
                      initialValue: _storeId,
                      isExpanded: true,
                      decoration: const InputDecoration(
                        labelText: 'Store *',
                        prefixIcon: Icon(Icons.store_outlined),
                      ),
                      items: stores
                          .map(
                            (s) => DropdownMenuItem(
                              value: s.id,
                              child: Text(
                                '${s.name} (${s.code})',
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          )
                          .toList(),
                      onChanged: (v) => setState(() => _storeId = v),
                      validator: (v) => v == null ? 'Required' : null,
                    ),
                  ),
                ],
                if (_needsVariantPick) ...[
                  const SizedBox(height: 12),
                  VariantField(
                    controller: _variantCtrl,
                    onTap: _findVariant,
                    helperText: 'Find it by name or SKU',
                  ),
                ],
                const SizedBox(height: 12),
                TextFormField(
                  controller: _thresholdCtrl,
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                  ),
                  decoration: const InputDecoration(
                    labelText: 'Threshold *',
                    prefixIcon: Icon(Icons.vertical_align_bottom),
                  ),
                  validator: (v) {
                    if (v == null || v.trim().isEmpty) return 'Required';
                    final n = double.tryParse(v.trim());
                    if (n == null || n <= 0) return '> 0';
                    return null;
                  },
                ),
                const SizedBox(height: 12),
                TextFormField(
                  controller: _maxQtyCtrl,
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                  ),
                  decoration: const InputDecoration(
                    labelText: 'Max qty (optional)',
                    helperText: 'Cap on suggested replenishment qty',
                    prefixIcon: Icon(Icons.vertical_align_top),
                  ),
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
              ? SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: Theme.of(context).colorScheme.onPrimary,
                  ),
                )
              : const Text('Save'),
        ),
      ],
    );
  }
}

// ── Thresholds tab ───────────────────────────────────────────────────────────

class _ThresholdsTab extends ConsumerStatefulWidget {
  const _ThresholdsTab();

  @override
  ConsumerState<_ThresholdsTab> createState() => _ThresholdsTabState();
}

class _ThresholdsTabState extends ConsumerState<_ThresholdsTab> {
  String? _storeId;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);
    final storeNames = {
      for (final s in storesAsync.value ?? const <StoreInfo>[]) s.id: s.name,
    };
    final storeKey = _storeId ?? '';
    final async = ref.watch(thresholdsProvider(storeKey));
    final gutter = context.pageGutter;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: EdgeInsetsDirectional.fromSTEB(
            gutter,
            AppSpacing.lg,
            gutter,
            0,
          ),
          child: Row(
            children: [
              SizedBox(
                width: 240,
                child: storesAsync.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, _) => Text(
                    friendlyError(e, fallback: 'Could not load stores.'),
                    style: TextStyle(color: cs.error),
                  ),
                  data: (stores) => DropdownButtonFormField<String?>(
                    initialValue: _storeId,
                    isExpanded: true,
                    decoration: const InputDecoration(
                      labelText: 'Store',
                      isDense: true,
                      prefixIcon: Icon(Icons.store_outlined),
                    ),
                    items: [
                      const DropdownMenuItem(
                        value: null,
                        child: Text('All stores'),
                      ),
                      ...stores.map(
                        (s) => DropdownMenuItem(
                          value: s.id,
                          child: Text(
                            '${s.name} (${s.code})',
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ),
                    ],
                    onChanged: (v) => setState(() => _storeId = v),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              IconButton(
                icon: const Icon(Icons.refresh),
                tooltip: 'Refresh thresholds',
                onPressed: () {
                  ref.invalidate(thresholdsProvider(storeKey));
                  ref.invalidate(thresholdsMapProvider);
                },
              ),
              const Spacer(),
              FilledButton.icon(
                onPressed: () => showDialog(
                  context: context,
                  builder: (_) => _SetThresholdDialog(
                    storeId: _storeId ?? '',
                    variantId: '',
                    onDone: () {
                      ref.invalidate(thresholdsProvider(storeKey));
                      ref.invalidate(thresholdsMapProvider);
                    },
                  ),
                ),
                icon: const Icon(Icons.add),
                label: const Text('Set reorder level'),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Expanded(
          child: async.when(
            loading: () =>
                const LoadingView(label: 'Loading reorder thresholds…'),
            error: (e, _) => ErrorView(
              message: friendlyError(
                e,
                fallback: 'Could not load reorder thresholds.',
              ),
              onRetry: () => ref.invalidate(thresholdsProvider(storeKey)),
            ),
            data: (rows) {
              if (rows.isEmpty) {
                return const _Roomy(
                  child: EmptyState(
                    icon: Icons.tune,
                    title: 'No reorder levels set',
                    message:
                        'Set a reorder level on a stock row, or use the '
                        'button above.',
                  ),
                );
              }
              final labels =
                  ref
                      .watch(
                        variantLabelsProvider(
                          variantIdsKey(rows.map((r) => r.variantId)),
                        ),
                      )
                      .value ??
                  const <String, VariantLabel>{};
              return ListView.separated(
                padding: EdgeInsets.symmetric(
                  horizontal: gutter,
                  vertical: AppSpacing.sm,
                ),
                itemCount: rows.length,
                separatorBuilder: (_, _) => const SizedBox(height: 4),
                itemBuilder: (_, i) {
                  final t = rows[i];
                  final sku = _skuOf(t.variantId, labels);
                  return Card(
                    child: ListTile(
                      leading: Icon(
                        Icons.vertical_align_bottom,
                        color: cs.primary,
                      ),
                      title: Text(
                        _productNameOf(t.variantId, labels),
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                      subtitle: Text(
                        [
                          if (sku.isNotEmpty) sku,
                          storeNames[t.storeId] ??
                              'Store ${shortRef(t.storeId)}',
                          if (t.maxQty != null)
                            'max ${t.maxQty!.toStringAsFixed(0)}',
                        ].join(' · '),
                      ),
                      trailing: Text(
                        '≤ ${t.threshold.toStringAsFixed(0)}',
                        style: const TextStyle(fontWeight: FontWeight.bold),
                      ),
                      onTap: () => showDialog(
                        context: context,
                        builder: (_) => _SetThresholdDialog(
                          storeId: t.storeId,
                          variantId: t.variantId,
                          onDone: () {
                            ref.invalidate(thresholdsProvider(storeKey));
                            ref.invalidate(thresholdsMapProvider);
                          },
                        ),
                      ),
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

// ---------------------------------------------------------------------------
// Storefront stock signal: a business-wide "Only N left"
// threshold — off until an owner (or a business-wide manager) sets it. Below
// or at it, `GET /inventory/availability` starts naming a whole count; above
// it, out of stock, dropship, or a weighed good, it never does.
// ---------------------------------------------------------------------------

const _stockSignal = '/${ApiConstants.inventory}/admin/inventory/storefront-settings';

/// The business's storefront stock-signal threshold, or null while it is off.
final storefrontStockSignalProvider = FutureProvider.autoDispose<int?>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(_stockSignal);
  final data = resp.data['data'];
  return data is Map ? (data['lowStockThreshold'] as num?)?.toInt() : null;
});

/// "Show 'Only N left' at or below …": on/off, and — while on — the whole
/// number 1–1000 it takes effect at.
class StorefrontStockSignalDialog extends ConsumerStatefulWidget {
  const StorefrontStockSignalDialog({super.key});

  @override
  ConsumerState<StorefrontStockSignalDialog> createState() =>
      _StorefrontStockSignalDialogState();
}

class _StorefrontStockSignalDialogState
    extends ConsumerState<StorefrontStockSignalDialog> {
  bool _on = false;
  late final _thresholdCtrl = TextEditingController(text: '5');
  bool _loaded = false;
  bool _saving = false;
  String? _error;

  void _applyLoaded(int? threshold) {
    if (_loaded) return;
    _loaded = true;
    _on = threshold != null;
    if (threshold != null) _thresholdCtrl.text = '$threshold';
  }

  @override
  void dispose() {
    _thresholdCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    int? threshold;
    if (_on) {
      threshold = int.tryParse(_thresholdCtrl.text.trim());
      if (threshold == null || threshold < 1 || threshold > 1000) {
        setState(() => _error = 'Enter a number from 1 to 1000, or switch it off.');
        return;
      }
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .put(_stockSignal, data: {'lowStockThreshold': threshold});
      ref.invalidate(storefrontStockSignalProvider);
      if (!mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          key: const Key('stock-signal-saved'),
          content: Text(_on
              ? 'Storefront stock signal saved.'
              : 'Storefront stock signal switched off.'),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'Could not save the setting.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(storefrontStockSignalProvider);
    async.whenData(_applyLoaded);
    return AlertDialog(
      title: const Text('Storefront stock signal'),
      content: SizedBox(
        width: 360,
        child: async.isLoading && !_loaded
            ? const SizedBox(
                height: 80, child: Center(child: CircularProgressIndicator()))
            : Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    'When a variant is running low at a store, the storefront can '
                    'say so instead of just "In stock" — never a number above what '
                    'you set here, and never one at all while this is off.',
                    style: TextStyle(color: cs.onSurfaceVariant),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  SwitchListTile.adaptive(
                    key: const Key('stock-signal-on'),
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Show "Only N left" at or below …'),
                    value: _on,
                    onChanged: (v) => setState(() => _on = v),
                  ),
                  if (_on)
                    TextField(
                      key: const Key('stock-signal-threshold'),
                      controller: _thresholdCtrl,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(labelText: 'Units (1–1000)'),
                    )
                  else
                    Text('Off', style: TextStyle(color: cs.onSurfaceVariant)),
                  if (_error != null) ...[
                    const SizedBox(height: AppSpacing.sm),
                    Text(_error!, key: const Key('stock-signal-error'),
                        style: TextStyle(color: cs.error)),
                  ],
                ],
              ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('stock-signal-save'),
          onPressed: _saving ? null : _save,
          child: _saving
              ? const SizedBox(
                  height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
        ),
      ],
    );
  }
}
