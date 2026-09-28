import 'package:flutter/material.dart';
import '../../core/format.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/constants.dart';
import '../../core/input_mode.dart';
import '../../core/network/api_client.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';
import 'providers/admin_providers.dart';
import 'providers/inventory_levels_pagination.dart';
import 'providers/live_alerts_provider.dart';
import '../../shared/util/short_ref.dart';

/// The days the dashboard's Revenue and Orders cover: the last 30, today
/// included (inclusive `yyyy-MM-dd`, as reporting-svc takes them). The
/// dashboard's own period, never the Reports screen's date range, so a range
/// picked there never changes what the dashboard says.
({String from, String to}) dashboardSalesPeriod([DateTime? now]) {
  final today = now ?? DateTime.now();
  return (
    // Calendar arithmetic, so a clock change never moves the first day.
    from: yyyyMmDd(DateTime(today.year, today.month, today.day - 29)),
    to: yyyyMmDd(today),
  );
}

/// The words for [dashboardSalesPeriod] under the Revenue and Orders figures.
const _salesPeriod = 'Last 30 days';

/// reporting-svc's sales summary for [dashboardSalesPeriod], one row per
/// currency. Revenue is each row's `net`: what was sold, less refunds, with
/// voided sales already left out by reporting-svc.
final dashboardSalesProvider =
    FutureProvider.autoDispose<List<SalesSummaryRow>>((ref) async {
  final period = dashboardSalesPeriod();
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.reporting}/admin/reports/sales/summary',
    queryParameters: {'from': period.from, 'to': period.to},
  );
  final rows = (resp.data['data']?['rows'] as List?) ?? [];
  return rows
      .map((e) => SalesSummaryRow.fromJson(e as Map<String, dynamic>))
      .toList();
});

class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ordersAsync = ref.watch(recentOrdersProvider);
    final inventoryAsync = ref.watch(inventoryLevelsSummaryProvider);
    final tenantAsync = ref.watch(tenantInfoProvider);
    final alertsAsync = ref.watch(shortageAlertsProvider);
    // Revenue and Orders come from reporting-svc's sales summary for the
    // dashboard's own last 30 days, not from the ten orders listed at the
    // bottom, and not from whatever range the Reports screen was left on.
    final salesAsync = ref.watch(dashboardSalesProvider);

    // Live push (MQTT) is a "refetch now + toast" nudge on top of the polled feed above, not a
    // second data source — see live_alerts_provider.dart.
    ref.listen(liveAlertsProvider, (previous, next) {
      if (next != null && next != previous) {
        ref.invalidate(shortageAlertsProvider);
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('${next.subject}: ${next.body}')));
      }
    });

    void refresh() {
      ref.invalidate(recentOrdersProvider);
      ref.invalidate(inventoryLevelsSummaryProvider);
      ref.invalidate(tenantInfoProvider);
      ref.invalidate(shortageAlertsProvider);
      ref.invalidate(dashboardSalesProvider);
    }

    final theme = Theme.of(context);
    final gutter = context.pageGutter;
    final tenant = tenantAsync.value;
    final currency = tenant?.currency ?? '';

    // Revenue and Orders cover one currency: the business's own, or the one
    // it sold in when it sold in nothing else. A count of every currency's
    // sales beside one currency's total would say the two go together. When it
    // sold in more than one, the caption names the currency both cards cover;
    // Reports has the rest.
    final salesRows = salesAsync.value ?? const <SalesSummaryRow>[];
    SalesSummaryRow? sales;
    for (final r in salesRows) {
      if (r.currency.toUpperCase() == currency.toUpperCase()) sales = r;
    }
    if (sales == null && salesRows.isNotEmpty) sales = salesRows.first;
    final orderCount = sales?.orders ?? 0;
    final period = salesRows.length > 1 && sales != null
        ? '$_salesPeriod · ${sales.currency.toUpperCase()} sales'
        : _salesPeriod;
    final salesFailed = salesAsync.hasError && !salesAsync.hasValue;
    final inventory = inventoryAsync.value;
    final inventoryFailed = inventoryAsync.hasError && !inventoryAsync.hasValue;
    final lowStock = inventory?.lowStockCount ?? 0;

    return RefreshIndicator.adaptive(
      onRefresh: () async {
        refresh();
      },
      child: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: EdgeInsets.only(bottom: gutter),
        child: ContentBounds(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // The business's name and currency under the title.
              PageHeader(
                title: 'Dashboard',
                subtitle: tenant == null
                    ? null
                    : currency.isEmpty
                        ? tenant.name
                        : '${tenant.name} · $currency',
                actions: [
                  // Touch screens pull to refresh; a mouse can't.
                  if (pointerFirst)
                    IconButton(
                      icon: const Icon(Icons.refresh),
                      tooltip: 'Refresh',
                      onPressed: refresh,
                    ),
                ],
              ),
              Padding(
                padding: EdgeInsets.symmetric(horizontal: gutter),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // Low-stock shortage alerts (notification-svc)
                    alertsAsync.maybeWhen(
                      data: (alerts) => alerts.isEmpty
                          ? const SizedBox.shrink()
                          : _ShortageAlertsBanner(alerts: alerts),
                      orElse: () => const SizedBox.shrink(),
                    ),
                    // Stat cards — two to a row, four in one row from 840 wide
                    _StatGrid(children: [
                      _StatCard(
                        label: 'Revenue',
                        caption: period,
                        value: salesFailed
                            ? '—'
                            : AppFormat.money(sales?.net ?? 0,
                                currencyCode: sales?.currency ?? currency),
                        icon: Icons.payments_outlined,
                        loading: salesAsync.isLoading,
                      ),
                      _StatCard(
                        label: 'Orders',
                        caption: period,
                        value:
                            salesFailed ? '—' : AppFormat.count(orderCount),
                        icon: Icons.receipt_long_outlined,
                        loading: salesAsync.isLoading,
                      ),
                      _StatCard(
                        label: 'Low stock',
                        value:
                            inventoryFailed ? '—' : AppFormat.count(lowStock),
                        icon: Icons.warning_amber_outlined,
                        loading: inventoryAsync.isLoading,
                        alert: lowStock > 0,
                      ),
                      _StatCard(
                        label: 'SKUs',
                        value: inventoryFailed
                            ? '—'
                            : AppFormat.count(inventory?.skuCount ?? 0),
                        icon: Icons.inventory_2_outlined,
                        loading: inventoryAsync.isLoading,
                      ),
                    ]),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.xxl),

              // Stores strip (brings its own space below it when it shows)
              storesSection(context, ref),

              // Recent orders
              Padding(
                padding: EdgeInsets.symmetric(horizontal: gutter),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text('Recent orders',
                              style: theme.textTheme.titleLarge),
                        ),
                        TextButton(
                          onPressed: () => context.go('/admin/orders'),
                          child: const Text('See all →'),
                        ),
                      ],
                    ),
                    const SizedBox(height: AppSpacing.md),
                    ordersAsync.when(
                      loading: () => const Center(
                        child: Padding(
                            padding: EdgeInsets.all(32),
                            child: CircularProgressIndicator()),
                      ),
                      error: (e, _) => ErrorView(
                        message: 'Could not load orders',
                        onRetry: () => ref.invalidate(recentOrdersProvider),
                      ),
                      data: (orders) => orders.isEmpty
                          ? const EmptyState(
                              icon: Icons.receipt_long_outlined,
                              title: 'No orders yet',
                              message:
                                  'Orders will appear here once customers start buying.',
                            )
                          : Card(
                              child: LayoutBuilder(
                                  builder: (context, constraints) {
                                final compact = AppBreakpoints.classOf(
                                        constraints.maxWidth) ==
                                    WindowClass.compact;
                                return ListView.separated(
                                  shrinkWrap: true,
                                  physics: const NeverScrollableScrollPhysics(),
                                  padding: EdgeInsets.zero,
                                  itemCount: orders.length,
                                  separatorBuilder: (_, _) =>
                                      const Divider(height: 1),
                                  itemBuilder: (context, i) => _RecentOrderTile(
                                      order: orders[i], compact: compact),
                                );
                              }),
                            ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget storesSection(BuildContext context, WidgetRef ref) {
    final storesAsync = ref.watch(storesProvider);
    return storesAsync.when(
      loading: () => const SizedBox.shrink(),
      error: (_, _) => const SizedBox.shrink(),
      data: (stores) {
        if (stores.isEmpty) return const SizedBox.shrink();
        final gutter = context.pageGutter;
        return Padding(
          padding: const EdgeInsets.only(bottom: AppSpacing.xxl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: EdgeInsets.symmetric(horizontal: gutter),
                child: Text('Your stores',
                    style: Theme.of(context).textTheme.titleLarge),
              ),
              const SizedBox(height: AppSpacing.md),
              // Scrolls sideways to the page's edges; its padding keeps the
              // first card under the title. The cards take the tallest one's
              // height instead of a fixed one, so large text never clips.
              SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                padding: EdgeInsets.symmetric(horizontal: gutter),
                child: IntrinsicHeight(
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      for (var i = 0; i < stores.length; i++) ...[
                        if (i > 0) const SizedBox(width: AppSpacing.md),
                        SizedBox(width: 200, child: _StoreCard(stores[i])),
                      ],
                    ],
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

/// When an order was placed: the time for one placed today (*Today, 14:05*),
/// the date for anything older (*23 Sep 2026*).
String _placedAt(String iso) {
  final placed = DateTime.tryParse(iso)?.toLocal();
  final now = DateTime.now();
  if (placed != null &&
      placed.year == now.year &&
      placed.month == now.month &&
      placed.day == now.day) {
    return 'Today, ${AppFormat.time(iso)}';
  }
  return AppFormat.date(iso);
}

/// One low-stock line: the product (with its SKU) and the store by name, then
/// how far down it is. An id shows only while its name is unknown.
String _alertLine(ShortageAlert a, Map<String, VariantLabel> labels,
    Map<String, String> storeNames) {
  final label = labels[a.variantId];
  final item = label == null || label.productName.isEmpty
      ? 'Variant …${shortRef(a.variantId)}'
      : label.sku.isEmpty
          ? label.productName
          : '${label.productName} (${label.sku})';
  final store = storeNames[a.storeId] ?? 'store …${shortRef(a.storeId)}';
  final where = a.storeId.isEmpty ? '' : ' at $store';
  return '$item$where: ${a.available.toStringAsFixed(0)} available, '
      'threshold ${a.threshold.toStringAsFixed(0)}';
}

/// The stat cards: two to a row, and all four in one row from 840 wide,
/// decided by the width the grid actually has. Cards in a row share the
/// tallest one's height and nothing fixes it, so a wrapped caption or large
/// text makes the row taller instead of overflowing it.
class _StatGrid extends StatelessWidget {
  final List<Widget> children;
  const _StatGrid({required this.children});

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      final cols = AppBreakpoints.classOf(constraints.maxWidth) >=
              WindowClass.expanded
          ? 4
          : 2;
      return Column(
        children: [
          for (var i = 0; i < children.length; i += cols) ...[
            if (i > 0) const SizedBox(height: AppSpacing.md),
            IntrinsicHeight(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (var j = i; j < i + cols; j++) ...[
                    if (j > i) const SizedBox(width: AppSpacing.md),
                    Expanded(
                      child: j < children.length
                          ? children[j]
                          : const SizedBox.shrink(),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ],
      );
    });
  }
}

class _StatCard extends StatelessWidget {
  final String label;

  /// What the figure covers, on a line under the label (*Last 30 days*).
  final String? caption;
  final String value;
  final IconData icon;
  final bool loading;
  final bool alert;

  const _StatCard({
    required this.label,
    this.caption,
    required this.value,
    required this.icon,
    this.loading = false,
    this.alert = false,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final bg = alert ? cs.errorContainer : cs.primaryContainer;
    final fg = alert ? cs.onErrorContainer : cs.onPrimaryContainer;
    return Card(
      color: bg,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Icon(icon, color: fg, size: 22),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const SizedBox(height: AppSpacing.md),
                if (loading)
                  SizedBox(
                    height: 24,
                    child: LinearProgressIndicator(
                      backgroundColor: fg.withAlpha(40),
                      valueColor: AlwaysStoppedAnimation(fg),
                    ),
                  )
                else
                  // A long amount shrinks to fit rather than wrapping mid-figure.
                  FittedBox(
                    fit: BoxFit.scaleDown,
                    alignment: AlignmentDirectional.centerStart,
                    child: Text(value,
                        maxLines: 1,
                        style: theme.textTheme.headlineSmall?.copyWith(
                          color: fg,
                          fontWeight: FontWeight.bold,
                        )),
                  ),
                const SizedBox(height: 2),
                Text(label,
                    style: theme.textTheme.labelMedium?.copyWith(color: fg)),
                if (caption != null)
                  Text(caption!,
                      style: theme.textTheme.bodySmall?.copyWith(color: fg)),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// One of the latest orders. On a phone the reference and the total share the
/// first line and the status gets a line of its own, so the text is never
/// squeezed beside a trailing column; wider, the status and total sit at the end.
class _RecentOrderTile extends StatelessWidget {
  final OrderSummary order;
  final bool compact;
  const _RecentOrderTile({required this.order, required this.compact});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final o = order;
    final pos = o.channel == 'POS';
    const numberStyle = TextStyle(fontFamily: 'monospace');
    final total = Text(
      AppFormat.money(o.total, currencyCode: o.currency),
      style: theme.textTheme.titleSmall,
    );
    final placed = [channelLabel(o.channel), _placedAt(o.createdAt)]
        .where((s) => s.isNotEmpty)
        .join(' · ');
    return ListTile(
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
                Expanded(
                  child: Text('#${shortRef(o.id)}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: numberStyle),
                ),
                const SizedBox(width: AppSpacing.sm),
                total,
              ],
            )
          : Text('#${shortRef(o.id)}', style: numberStyle),
      subtitle: compact
          ? Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(placed),
                const SizedBox(height: AppSpacing.xs),
                StatusBadge.order(o.status),
              ],
            )
          : Text(placed),
      trailing: compact
          ? null
          : Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                StatusBadge.order(o.status),
                const SizedBox(width: AppSpacing.md),
                total,
              ],
            ),
    );
  }
}

/// A store in the strip: the default card fill, since `surfaceContainerHighest`
/// is kept for skeletons and made the strip read as still loading.
class _StoreCard extends StatelessWidget {
  final StoreInfo store;
  const _StoreCard(this.store);

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final s = store;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Row(
              children: [
                Icon(
                  s.type == 'WAREHOUSE'
                      ? Icons.warehouse_outlined
                      : Icons.store_outlined,
                  size: 16,
                  color: cs.primary,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(s.name,
                      style: const TextStyle(
                          fontWeight: FontWeight.bold, fontSize: 13),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.sm),
            Text(s.code,
                style: TextStyle(
                    fontFamily: 'monospace', fontSize: 11, color: cs.outline)),
            const SizedBox(height: AppSpacing.sm),
            _StoreStatusDot(s.status),
          ],
        ),
      ),
    );
  }
}

/// A store's status in words beside a dot: green while trading, grey once
/// deactivated, amber for anything else.
class _StoreStatusDot extends StatelessWidget {
  final String status;
  const _StoreStatusDot(this.status);

  @override
  Widget build(BuildContext context) {
    final (label, color) = switch (status.toUpperCase()) {
      'ACTIVE' => ('Active', context.status.success),
      'INACTIVE' => ('Inactive', Theme.of(context).colorScheme.outline),
      _ => (humanizeCode(status), context.status.warning),
    };
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 6,
          height: 6,
          decoration: BoxDecoration(
            color: color,
            shape: BoxShape.circle,
          ),
        ),
        const SizedBox(width: 4),
        Flexible(
          child: Text(label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 11, color: color)),
        ),
      ],
    );
  }
}

/// Low-stock alert banner shown at the top of the dashboard when stock has
/// fallen below threshold (sourced from notification-svc shortage alerts).
/// Each line names the product and the store, resolved from the alert's ids.
class _ShortageAlertsBanner extends ConsumerStatefulWidget {
  final List<ShortageAlert> alerts;
  const _ShortageAlertsBanner({required this.alerts});

  @override
  ConsumerState<_ShortageAlertsBanner> createState() =>
      _ShortageAlertsBannerState();
}

class _ShortageAlertsBannerState extends ConsumerState<_ShortageAlertsBanner> {
  bool _dismissed = false;

  @override
  Widget build(BuildContext context) {
    if (_dismissed) return const SizedBox.shrink();
    final cs = Theme.of(context).colorScheme;
    final alerts = widget.alerts;
    final shown = alerts.take(5).toList();
    final labels = ref
            .watch(variantLabelsProvider(
                variantIdsKey(shown.map((a) => a.variantId))))
            .value ??
        const <String, VariantLabel>{};
    final storeNames = {
      for (final s in ref.watch(storesProvider).value ?? const <StoreInfo>[])
        s.id: s.name,
    };
    return Padding(
      padding: const EdgeInsets.only(bottom: 24),
      child: MaterialBanner(
        backgroundColor: cs.errorContainer,
        leading: Icon(Icons.warning_amber_rounded, color: cs.onErrorContainer),
        content: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('${alerts.length} low-stock alert${alerts.length == 1 ? '' : 's'}',
                style: TextStyle(
                    color: cs.onErrorContainer, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            for (final a in shown)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 2),
                child: Text(
                  _alertLine(a, labels, storeNames),
                  style: TextStyle(color: cs.onErrorContainer, fontSize: 12),
                ),
              ),
            if (alerts.length > 5)
              Text('…and ${alerts.length - 5} more',
                  style: TextStyle(
                      color: cs.onErrorContainer,
                      fontSize: 12,
                      fontStyle: FontStyle.italic)),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => context.go('/admin/inventory'),
            child: Text('View inventory',
                style: TextStyle(color: cs.onErrorContainer)),
          ),
          TextButton(
            onPressed: () => setState(() => _dismissed = true),
            child: Text('Dismiss', style: TextStyle(color: cs.onErrorContainer)),
          ),
        ],
      ),
    );
  }
}
