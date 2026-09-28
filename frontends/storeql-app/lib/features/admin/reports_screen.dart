import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../core/format.dart';
import '../../core/ids.dart';
import '../../core/network/api_error.dart';
import '../../shared/util/file_download.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';
import 'providers/staff_names.dart';
import 'post_journal_dialog.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/util/status_labels.dart';
import '../../core/theme.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/scrollable_table.dart';
import '../../core/spacing.dart';

enum _ReportType {
  sales,
  salesByDay,
  salesByCategory,
  onHand,
  supplyDemand,
  movements,
  lowStock,
  valuation,
  shrinkage,
  taxSummary,
  exceptions,
  salesByHour,
  salesByStaff,
  tenderMix,
  stockTurn,
  grossMargin,
  deadStock,
  trialBalance,
  deferredRevenue,
}

class ReportsScreen extends ConsumerStatefulWidget {
  const ReportsScreen({super.key});

  @override
  ConsumerState<ReportsScreen> createState() => _ReportsScreenState();
}

class _ReportsScreenState extends ConsumerState<ReportsScreen> {
  _ReportType _selected = _ReportType.onHand;

  @override
  void initState() {
    super.initState();
    // Staff named by one report stay named while the screen lives. A report
    // reloads for a new period, grouping or report, and its rows unmount
    // while it does; held here, the names outlive that, so a row never drops
    // back to an id for a round trip and iam-svc is asked about each person
    // once.
    ref.listenManual(staffNameCacheProvider, (_, _) {});
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, bc) {
      final wide = bc.maxWidth >= AppBreakpoints.rail;

      if (wide) {
        // Side-by-side: report list on left, content on right
        return Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 220,
              child: _ReportSidebar(
                  selected: _selected,
                  onSelect: (r) => setState(() => _selected = r)),
            ),
            const VerticalDivider(width: 1),
            Expanded(child: _ReportContent(type: _selected)),
          ],
        );
      }

      // Mobile: top tabs
      final gutter = context.pageGutter;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: EdgeInsetsDirectional.fromSTEB(gutter, gutter, gutter, 0),
            child: Text('Reports',
                style: context.isCompact
                    ? Theme.of(context).textTheme.headlineSmall
                    : Theme.of(context).textTheme.headlineMedium),
          ),
          const SizedBox(height: AppSpacing.lg),
          _ReportChips(
            selected: _selected,
            onSelect: (r) => setState(() => _selected = r),
          ),
          Expanded(child: _ReportContent(type: _selected)),
        ],
      );
    });
  }
}

/// The reports as a sideways-scrolling row of chips, for widths without room
/// for the sidebar. The selected one is always scrolled into view — when the
/// screen opens (On-Hand Inventory is fourth, past a phone's right edge) and
/// whenever the selection changes — so the row never hides which report the
/// page below is showing.
class _ReportChips extends StatefulWidget {
  final _ReportType selected;
  final ValueChanged<_ReportType> onSelect;

  const _ReportChips({required this.selected, required this.onSelect});

  @override
  State<_ReportChips> createState() => _ReportChipsState();
}

class _ReportChipsState extends State<_ReportChips> {
  final _scroll = ScrollController();
  final _keys = {for (final r in _ReportType.values) r: GlobalKey()};

  @override
  void initState() {
    super.initState();
    _revealSelected(animate: false);
  }

  @override
  void didUpdateWidget(_ReportChips old) {
    super.didUpdateWidget(old);
    if (old.selected != widget.selected) _revealSelected(animate: true);
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  /// Centres the selected chip in the row, as far as the row can scroll. Only
  /// this row moves: `Scrollable.ensureVisible` would scroll every scrollable
  /// around it too.
  void _revealSelected({required bool animate}) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients) return;
      final chip = _keys[widget.selected]?.currentContext?.findRenderObject();
      if (chip == null || !chip.attached) return;
      final viewport = RenderAbstractViewport.maybeOf(chip);
      if (viewport == null) return;
      final position = _scroll.position;
      final target = viewport
          .getOffsetToReveal(chip, 0.5)
          .offset
          .clamp(position.minScrollExtent, position.maxScrollExtent);
      if ((target - position.pixels).abs() < 1) return;
      if (animate && !MediaQuery.disableAnimationsOf(context)) {
        _scroll.animateTo(target,
            duration: const Duration(milliseconds: 250),
            curve: Curves.easeOutCubic);
      } else {
        _scroll.jumpTo(target);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      controller: _scroll,
      scrollDirection: Axis.horizontal,
      padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
      child: Row(
        children: [
          for (final r in _ReportType.values)
            Padding(
              key: _keys[r],
              padding: const EdgeInsetsDirectional.only(end: AppSpacing.sm),
              child: ChoiceChip(
                label: Text(_reportLabel(r)),
                avatar: Icon(_reportIcon(r), size: 16),
                selected: widget.selected == r,
                onSelected: (_) => widget.onSelect(r),
              ),
            ),
        ],
      ),
    );
  }
}

class _ReportSidebar extends StatelessWidget {
  final _ReportType selected;
  final ValueChanged<_ReportType> onSelect;

  const _ReportSidebar({required this.selected, required this.onSelect});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 24, 16, 16),
          child: Text('Reports',
              style: Theme.of(context).textTheme.headlineMedium),
        ),
        // The list scrolls. There are more reports than fit a laptop window
        // now, and a bare Column silently overflows: the tiles past the fold
        // are not merely off-screen but unreachable, and the ones that are
        // visible get a yellow-and-black bar across them.
        //
        // The selected tile's background is ListTile's own, not a DecoratedBox
        // wrapped around it: ListTile paints its ink on the nearest Material
        // ancestor, so a coloured box in between hides the splash entirely and
        // tapping the selected report gives no feedback at all. Flutter asserts
        // on exactly this, which is also what made the screen untestable.
        Expanded(
          child: ListView(
            padding: EdgeInsets.zero,
            children: _ReportType.values.map((r) {
              final active = selected == r;
              return Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                child: ListTile(
                  dense: true,
                  selected: active,
                  selectedTileColor: cs.secondaryContainer,
                  selectedColor: cs.onSecondaryContainer,
                  iconColor: cs.onSurfaceVariant,
                  textColor: cs.onSurfaceVariant,
                  shape: const RoundedRectangleBorder(
                      borderRadius: AppRadius.chip),
                  leading: Icon(_reportIcon(r)),
                  title: Text(_reportLabel(r),
                      style: TextStyle(
                          fontWeight:
                              active ? FontWeight.bold : FontWeight.normal)),
                  onTap: () => onSelect(r),
                ),
              );
            }).toList(),
          ),
        ),
      ],
    );
  }
}

class _ReportContent extends ConsumerWidget {
  final _ReportType type;
  const _ReportContent({required this.type});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    switch (type) {
      case _ReportType.sales:
        return _SalesReport();
      case _ReportType.salesByDay:
        return _SalesByDayReport();
      case _ReportType.salesByCategory:
        return _SalesByCategoryReport();
      case _ReportType.onHand:
        return _OnHandReport();
      case _ReportType.supplyDemand:
        return _SupplyDemandReport();
      case _ReportType.movements:
        return _MovementStatsReport();
      case _ReportType.lowStock:
        return _LowStockReport();
      case _ReportType.valuation:
        return _ValuationReport();
      case _ReportType.shrinkage:
        return _ShrinkageReport();
      case _ReportType.taxSummary:
        return _TaxSummaryReport();
      case _ReportType.exceptions:
        return _ExceptionReport();
      case _ReportType.salesByHour:
        return _SalesByHourReport();
      case _ReportType.salesByStaff:
        return _SalesByStaffReport();
      case _ReportType.tenderMix:
        return _TenderMixReport();
      case _ReportType.stockTurn:
        return _StockTurnReport();
      case _ReportType.grossMargin:
        return _GrossMarginReport();
      case _ReportType.deadStock:
        return _DeadStockReport();
      case _ReportType.trialBalance:
        return _TrialBalanceReport();
      case _ReportType.deferredRevenue:
        return _DeferredRevenueReport();
    }
  }
}

/// Shared header (title + subtitle + refresh + optional export) used by table reports.
///
/// Inset by the page gutter, like the summary, filters and table under it, so
/// their edges line up. Where the header is narrow — a phone — the text takes
/// the whole width and the actions go on the line below it, as the shared
/// page header does; beside the text they would squeeze the title and the
/// description onto two lines each.
class _ReportHeader extends StatelessWidget {
  /// Narrower than this, the actions go under the text.
  static const double _stackBelow = 480;

  final String title;
  final String subtitle;
  final VoidCallback onRefresh;
  final VoidCallback? onExportCsv;
  const _ReportHeader({
    required this.title,
    required this.subtitle,
    required this.onRefresh,
    this.onExportCsv,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final gutter = context.pageGutter;
    final text = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(title,
            style: theme.textTheme.titleLarge
                ?.copyWith(fontWeight: FontWeight.bold)),
        Text(subtitle,
            style: theme.textTheme.bodyMedium
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
      ],
    );
    final actions = [
      if (onExportCsv != null)
        TextButton.icon(
          onPressed: onExportCsv,
          icon: const Icon(Icons.download_outlined, size: 18),
          label: const Text('Export CSV'),
        ),
      IconButton(
          icon: const Icon(Icons.refresh),
          tooltip: 'Refresh',
          onPressed: onRefresh),
    ];
    return Padding(
      padding: EdgeInsetsDirectional.fromSTEB(gutter, gutter, gutter, AppSpacing.lg),
      child: LayoutBuilder(builder: (context, constraints) {
        final largeText =
            MediaQuery.textScalerOf(context).scale(16) > 16 * 1.3;
        final stacked = constraints.maxWidth < _stackBelow ||
            (largeText && constraints.maxWidth < AppBreakpoints.expanded);
        if (stacked) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              text,
              const SizedBox(height: AppSpacing.sm),
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.sm,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: actions,
              ),
            ],
          );
        }
        return Row(
          children: [
            Expanded(child: text),
            const SizedBox(width: AppSpacing.lg),
            ...actions,
          ],
        );
      }),
    );
  }
}

/// From/to date pickers bound to [reportDateRangeProvider].
class _DateRangeBar extends ConsumerWidget {
  const _DateRangeBar();

  Future<void> _pick(
      BuildContext context, WidgetRef ref, {required bool isFrom}) async {
    final range = ref.read(reportDateRangeProvider);
    final current = isFrom ? range.from : range.to;
    DateTime initial;
    try {
      initial = current != null ? DateTime.parse(current) : DateTime.now();
    } catch (_) {
      initial = DateTime.now();
    }
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: initial,
      firstDate: DateTime(now.year - 5),
      lastDate: DateTime(now.year + 1),
    );
    if (picked == null) return;
    final s = yyyyMmDd(picked);
    ref.read(reportDateRangeProvider.notifier).state = isFrom
        ? range.copyWith(from: s)
        : range.copyWith(to: s);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final range = ref.watch(reportDateRangeProvider);
    return Padding(
      padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
      child: Wrap(
        spacing: 12,
        runSpacing: 8,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          OutlinedButton.icon(
            onPressed: () => _pick(context, ref, isFrom: true),
            icon: const Icon(Icons.event_outlined, size: 18),
            label: Text('From: ${_day(range.from)}'),
          ),
          OutlinedButton.icon(
            onPressed: () => _pick(context, ref, isFrom: false),
            icon: const Icon(Icons.event_outlined, size: 18),
            label: Text('To: ${_day(range.to)}'),
          ),
          TextButton(
            onPressed: () {
              final now = DateTime.now();
              ref.read(reportDateRangeProvider.notifier).state = ReportDateRange(
                from: yyyyMmDd(now.subtract(const Duration(days: 30))),
                to: yyyyMmDd(now),
              );
            },
            child: const Text('Last 30 days'),
          ),
        ],
      ),
    );
  }
}

void _downloadCsv(String filename, String csv) =>
    downloadTextFile(filename, csv, mimeType: 'text/csv;charset=utf-8');

String _csvEscape(Object? v) {
  final s = v?.toString() ?? '';
  if (s.contains(',') || s.contains('"') || s.contains('\n')) {
    return '"${s.replaceAll('"', '""')}"';
  }
  return s;
}

/// A store in a report's table by its name; the end of its id only while the
/// stores load, or for one no longer listed.
class _StoreName extends ConsumerWidget {
  const _StoreName(this.storeId);
  final String storeId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stores = ref.watch(storesProvider).value ?? const <StoreInfo>[];
    for (final s in stores) {
      if (s.id == storeId) return Text(s.name);
    }
    return Text('…${shortRef(storeId)}', style: _idStyle);
  }
}

/// A variant in a report's table by its product's name, its SKU under it.
/// Every row asks with the same [keys] — the table's variants — so the names
/// are read once for the table.
class _VariantName extends ConsumerWidget {
  const _VariantName(this.variantId, {required this.keys});
  final String variantId;
  final Iterable<String> keys;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final labels = _variantLabels(ref, keys);
    final sku = variantSku(variantId, labels);
    if (!labels.containsKey(variantId)) {
      return Text(variantDisplayName(variantId, labels), style: _idStyle);
    }
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Text(variantDisplayName(variantId, labels)),
        if (sku.isNotEmpty)
          Text(sku, style: Theme.of(context).textTheme.bodySmall),
      ],
    );
  }
}

/// A member of staff in a report's table by their login email (iam-svc's);
/// the end of their id only while iam-svc has not named them, or will not (a
/// login gone from the business, a viewer without a management role). Every
/// row asks with the same [keys], the table's staff, and the names land in
/// the screen's [StaffNameCache], so each person is asked about once.
///
/// A bucket that is nobody is said in words, and never sent to iam-svc, which
/// refuses a whole request for one id that is not a UUIDv7: `SYSTEM` (stock
/// the platform moved itself) and `UNATTRIBUTED` (a journal entry naming no
/// one).
class _StaffName extends ConsumerWidget {
  const _StaffName(this.userId, {required this.keys});
  final String userId;
  final Iterable<String> keys;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final nobody = switch (userId) {
      'SYSTEM' => 'System',
      'UNATTRIBUTED' => 'Unattributed',
      _ => null,
    };
    if (nobody != null) {
      return Text(nobody,
          style: TextStyle(
              fontStyle: FontStyle.italic,
              color: Theme.of(context).colorScheme.outline));
    }
    final email = ref.watch(staffNameCacheProvider)[userId];
    if (email != null) return Text(email);
    final names = ref.read(staffNameCacheProvider.notifier);
    Future.microtask(() => names.resolve(keys.where(isV7)));
    return Text('…${shortRef(userId)}', style: _idStyle);
  }
}

/// What a grouped report's row is about, in words: a store or a product by
/// name, a reason in words, a staff member by their login, a rate code, a
/// month or an age band as sent.
class _GroupName extends StatelessWidget {
  const _GroupName({
    required this.grouping,
    required this.groupKey,
    required this.keys,
  });
  final String grouping;
  final String groupKey;

  /// Every key in the table, so a product table reads its names once.
  final Iterable<String> keys;

  @override
  Widget build(BuildContext context) => switch (grouping) {
        'STORE' => _StoreName(groupKey),
        'VARIANT' => _VariantName(groupKey, keys: keys),
        'REASON' => Text(humanizeCode(groupKey)),
        'ACTOR' => _StaffName(groupKey, keys: keys),
        _ => Text(groupKey),
      };
}

const _idStyle = TextStyle(fontFamily: 'monospace', fontSize: 12);

/// A report's day, as a date (`26 Aug 2026`), read as written — the API's
/// `2026-08-26` stays the 26th in any time zone; '—' when none is chosen.
String _day(String? iso) {
  if (iso == null || iso.isEmpty) return '—';
  final d = DateTime.tryParse(iso);
  return d == null ? iso : AppFormat.dateOf(d);
}

/// ` · 26 Aug 2026 → 25 Sept 2026` after a report's caption, or nothing when
/// no period is chosen.
String _period(ReportDateRange range) =>
    range.from == null ? '' : ' · ${_day(range.from)} → ${_day(range.to)}';

/// An amount as money: in the row's own [currency] where it has one, else the
/// business's home currency (the caller's `home`). Grouped, with its symbol.
String _money(num v, String? currency) => AppFormat.money(v, currencyCode: currency);

/// A tender method in words: `GIFT_CARD` → *Gift card*.
String _methodWords(String method) => switch (method.toUpperCase()) {
      'CASH' => 'Cash',
      'CARD' => 'Card',
      'GIFT_CARD' => 'Gift card',
      'STORE_CREDIT' => 'Store credit',
      'UPI' => 'UPI',
      'WALLET' => 'Wallet',
      'LOYALTY' => 'Loyalty points',
      'VOUCHER' => 'Voucher',
      _ => humanizeCode(method),
    };

class _SupplyDemandReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(supplyDemandReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading supply / demand…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e,
            fallback: 'Could not load supply / demand report.'),
        onRetry: () => ref.invalidate(supplyDemandReportProvider),
      ),
      data: (rows) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ReportHeader(
            title: 'Supply / Demand Netting',
            subtitle: 'On-hand + in-transit → net available',
            onRefresh: () => ref.invalidate(supplyDemandReportProvider),
            onExportCsv: rows.isEmpty
                ? null
                : () {
                    final buf = StringBuffer(
                        'storeId,variantId,onHand,supplyInTransit,netAvailable\n');
                    for (final r in rows) {
                      buf.writeln([
                        _csvEscape(r.storeId),
                        _csvEscape(r.variantId),
                        r.onHand,
                        r.supplyInTransit,
                        r.netAvailable,
                      ].join(','));
                    }
                    _downloadCsv('supply-demand.csv', buf.toString());
                  },
          ),
          if (rows.isEmpty)
            const Expanded(child: EmptyState(title: 'No netting data yet.'))
          else
            Expanded(
              child: SingleChildScrollView(
                padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                child: Card(
                  child: DataTable(
                    headingRowColor:
                        WidgetStatePropertyAll(cs.surfaceContainerHigh),
                    columnSpacing: 24,
                    columns: const [
                      DataColumn(label: Text('Store')),
                      DataColumn(label: Text('Variant')),
                      DataColumn(label: Text('On-Hand'), numeric: true),
                      DataColumn(label: Text('In-Transit'), numeric: true),
                      DataColumn(label: Text('Net Avail.'), numeric: true),
                    ],
                    rows: rows
                        .map((r) => DataRow(cells: [
                              DataCell(_StoreName(r.storeId)),
                              DataCell(
                                  _VariantName(r.variantId, keys: rows.map((x) => x.variantId))),
                              DataCell(Text(r.onHand.toStringAsFixed(0))),
                              DataCell(
                                  Text(r.supplyInTransit.toStringAsFixed(0))),
                              DataCell(Text(r.netAvailable.toStringAsFixed(0))),
                            ]))
                        .toList(),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _SalesReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final range = ref.watch(reportDateRangeProvider);
    final async = ref.watch(salesSummaryReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading sales…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load sales report.'),
        onRetry: () => ref.invalidate(salesSummaryReportProvider),
      ),
      data: (rows) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ReportHeader(
            title: 'Sales Revenue',
            subtitle:
                'Gross / refunded / net revenue by currency${_period(range)}',
            onRefresh: () => ref.invalidate(salesSummaryReportProvider),
            onExportCsv: rows.isEmpty
                ? null
                : () {
                    final buf =
                        StringBuffer('currency,orders,gross,refunded,net\n');
                    for (final r in rows) {
                      buf.writeln([
                        _csvEscape(r.currency),
                        r.orders,
                        r.gross,
                        r.refunded,
                        r.net,
                      ].join(','));
                    }
                    _downloadCsv('sales-summary.csv', buf.toString());
                  },
          ),
          const _DateRangeBar(),
          const SizedBox(height: 12),
          if (rows.isEmpty)
            const Expanded(child: EmptyState(title: 'No sales yet.'))
          else
            Expanded(
              child: SingleChildScrollView(
                padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                child: Card(
                  child: DataTable(
                    headingRowColor:
                        WidgetStatePropertyAll(cs.surfaceContainerHigh),
                    columnSpacing: 24,
                    columns: const [
                      DataColumn(label: Text('Currency')),
                      DataColumn(label: Text('Orders'), numeric: true),
                      DataColumn(label: Text('Gross'), numeric: true),
                      DataColumn(label: Text('Refunded'), numeric: true),
                      DataColumn(label: Text('Net'), numeric: true),
                    ],
                    rows: rows
                        .map((r) => DataRow(cells: [
                              DataCell(Text(r.currency)),
                              DataCell(Text('${r.orders}')),
                              DataCell(Text(_money(r.gross, r.currency))),
                              DataCell(Text(_money(r.refunded, r.currency))),
                              DataCell(Text(_money(r.net, r.currency),
                                  style: const TextStyle(
                                      fontWeight: FontWeight.bold))),
                            ]))
                        .toList(),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _SalesByDayReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final range = ref.watch(reportDateRangeProvider);
    final async = ref.watch(salesByDayReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading sales by day…'),
      error: (e, _) => ErrorView(
        message:
            friendlyError(e, fallback: 'Could not load sales-by-day report.'),
        onRetry: () => ref.invalidate(salesByDayReportProvider),
      ),
      data: (rows) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ReportHeader(
            title: 'Sales by Day',
            subtitle:
                'Daily revenue buckets${_period(range)}',
            onRefresh: () => ref.invalidate(salesByDayReportProvider),
            onExportCsv: rows.isEmpty
                ? null
                : () {
                    final buf = StringBuffer(
                        'day,currency,orders,gross,refunded,net\n');
                    for (final r in rows) {
                      buf.writeln([
                        _csvEscape(r.day),
                        _csvEscape(r.currency),
                        r.orders,
                        r.gross,
                        r.refunded,
                        r.net,
                      ].join(','));
                    }
                    _downloadCsv('sales-by-day.csv', buf.toString());
                  },
          ),
          const _DateRangeBar(),
          const SizedBox(height: 12),
          if (rows.isEmpty)
            const Expanded(
                child: EmptyState(title: 'No daily sales in this range.'))
          else
            Expanded(
              child: SingleChildScrollView(
                padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                child: Card(
                  child: DataTable(
                    headingRowColor:
                        WidgetStatePropertyAll(cs.surfaceContainerHigh),
                    columnSpacing: 24,
                    columns: const [
                      DataColumn(label: Text('Day')),
                      DataColumn(label: Text('Currency')),
                      DataColumn(label: Text('Orders'), numeric: true),
                      DataColumn(label: Text('Gross'), numeric: true),
                      DataColumn(label: Text('Refunded'), numeric: true),
                      DataColumn(label: Text('Net'), numeric: true),
                    ],
                    rows: rows
                        .map((r) => DataRow(cells: [
                              DataCell(Text(r.day)),
                              DataCell(Text(r.currency)),
                              DataCell(Text('${r.orders}')),
                              DataCell(Text(_money(r.gross, r.currency))),
                              DataCell(Text(_money(r.refunded, r.currency))),
                              DataCell(Text(_money(r.net, r.currency),
                                  style: const TextStyle(
                                      fontWeight: FontWeight.bold))),
                            ]))
                        .toList(),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// What each category took (19.x). The server groups; the catalogue names.
class _SalesByCategoryReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final range = ref.watch(reportDateRangeProvider);
    final level = ref.watch(salesByCategoryLevelProvider);
    final async = ref.watch(salesByCategoryReportProvider);
    final names = ref.watch(categoriesProvider).maybeWhen(
          data: (cats) => {for (final c in cats) c.id: c.name},
          orElse: () => const <String, String>{},
        );
    // A category's name, or null for one the catalogue does not list (still
    // loading, or retired since the sale): that row shows the end of its id,
    // marked as one, and the CSV leaves the name blank beside the id.
    String? nameOf(SalesCategoryRow r) =>
        r.categoryId == null ? 'Uncategorised' : names[r.categoryId];
    return async.when(
      loading: () => const LoadingView(label: 'Loading sales by category…'),
      error: (e, _) => ErrorView(
        message:
            friendlyError(e, fallback: 'Could not load sales by category.'),
        onRetry: () => ref.invalidate(salesByCategoryReportProvider),
      ),
      data: (rows) {
        final unplaced = rows.any((r) => r.categoryId == null);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _ReportHeader(
              title: 'Sales by Category',
              subtitle:
                  '${level == 'top' ? 'Rolled up to the top of the tree' : "By the product's own category"}'
                  '${_period(range)}',
              onRefresh: () => ref.invalidate(salesByCategoryReportProvider),
              onExportCsv: rows.isEmpty
                  ? null
                  : () {
                      final buf = StringBuffer(
                          'category,categoryId,currency,orders,units,gross,share\n');
                      for (final r in rows) {
                        buf.writeln([
                          _csvEscape(nameOf(r)),
                          _csvEscape(r.categoryId ?? ''),
                          _csvEscape(r.currency),
                          r.orders,
                          r.units,
                          r.gross,
                          r.share,
                        ].join(','));
                      }
                      _downloadCsv('sales-by-category-$level.csv', buf.toString());
                    },
            ),
            const _DateRangeBar(),
            Padding(
              padding: EdgeInsetsDirectional.fromSTEB(context.pageGutter, 4, context.pageGutter, 0),
              child: SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: 'leaf', label: Text('Own category')),
                  ButtonSegment(value: 'top', label: Text('Top level')),
                ],
                selected: {level},
                onSelectionChanged: (s) => ref
                    .read(salesByCategoryLevelProvider.notifier)
                    .state = s.first,
              ),
            ),
            const SizedBox(height: 12),
            if (rows.isEmpty)
              const Expanded(
                  child: EmptyState(title: 'No sale lines in this range.'))
            else
              Expanded(
                child: SingleChildScrollView(
                  padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      if (unplaced)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 8),
                          child: Text(
                            'Uncategorised is lines the catalogue cannot place: a product with no '
                            'category, or a variant the catalogue has not announced yet. '
                            'Re-announcing the catalogue from Products places them.',
                            style: Theme.of(context)
                                .textTheme
                                .bodySmall
                                ?.copyWith(color: cs.onSurfaceVariant),
                          ),
                        ),
                      Card(
                        child: DataTable(
                          headingRowColor:
                              WidgetStatePropertyAll(cs.surfaceContainerHigh),
                          columnSpacing: 24,
                          columns: const [
                            DataColumn(label: Text('Category')),
                            DataColumn(label: Text('Currency')),
                            DataColumn(label: Text('Orders'), numeric: true),
                            DataColumn(label: Text('Units'), numeric: true),
                            DataColumn(label: Text('Gross'), numeric: true),
                            DataColumn(label: Text('Share %'), numeric: true),
                          ],
                          rows: rows
                              .map((r) => DataRow(cells: [
                                    DataCell(nameOf(r) == null
                                        ? Text('…${shortRef(r.categoryId!)}',
                                            style: _idStyle)
                                        : Text(nameOf(r)!,
                                            style: r.categoryId == null
                                                ? TextStyle(
                                                    fontStyle: FontStyle.italic,
                                                    color: cs.onSurfaceVariant)
                                                : null)),
                                    DataCell(Text(r.currency)),
                                    DataCell(Text('${r.orders}')),
                                    DataCell(Text(r.units.toStringAsFixed(
                                        r.units == r.units.roundToDouble()
                                            ? 0
                                            : 3))),
                                    DataCell(Text(_money(r.gross, r.currency),
                                        style: const TextStyle(
                                            fontWeight: FontWeight.bold))),
                                    DataCell(Text(r.share.toStringAsFixed(2))),
                                  ]))
                              .toList(),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _MovementStatsReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(movementStatsReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading movement stats…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load movement stats.'),
        onRetry: () => ref.invalidate(movementStatsReportProvider),
      ),
      data: (rows) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ReportHeader(
            title: 'Movement Statistics',
            subtitle: 'Stock in / out / net per period',
            onRefresh: () => ref.invalidate(movementStatsReportProvider),
            onExportCsv: rows.isEmpty
                ? null
                : () {
                    final buf = StringBuffer(
                        'storeId,variantId,bucket,totalIn,totalOut,net\n');
                    for (final r in rows) {
                      buf.writeln([
                        _csvEscape(r.storeId),
                        _csvEscape(r.variantId),
                        _csvEscape(r.bucket),
                        r.totalIn,
                        r.totalOut,
                        r.net,
                      ].join(','));
                    }
                    _downloadCsv('movement-stats.csv', buf.toString());
                  },
          ),
          if (rows.isEmpty)
            const Expanded(child: EmptyState(title: 'No movement data yet.'))
          else
            Expanded(
              child: SingleChildScrollView(
                padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                child: Card(
                  child: DataTable(
                    headingRowColor:
                        WidgetStatePropertyAll(cs.surfaceContainerHigh),
                    columnSpacing: 24,
                    columns: const [
                      DataColumn(label: Text('Store')),
                      DataColumn(label: Text('Variant')),
                      DataColumn(label: Text('Period')),
                      DataColumn(label: Text('In'), numeric: true),
                      DataColumn(label: Text('Out'), numeric: true),
                      DataColumn(label: Text('Net'), numeric: true),
                    ],
                    rows: rows
                        .map((r) => DataRow(cells: [
                              DataCell(_StoreName(r.storeId)),
                              DataCell(
                                  _VariantName(r.variantId, keys: rows.map((x) => x.variantId))),
                              DataCell(Text(r.bucket, style: _idStyle)),
                              DataCell(Text(r.totalIn.toStringAsFixed(0))),
                              DataCell(Text(r.totalOut.toStringAsFixed(0))),
                              DataCell(Text(r.net.toStringAsFixed(0))),
                            ]))
                        .toList(),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// Up to this many variant ids go into one call to product-svc's resolve
/// endpoint, which reads no more than 200.
const _resolveChunk = 200;

/// Product name and SKU for each of [variantIds], asked for in chunks the
/// resolve endpoint accepts. What has not arrived yet (or is unknown) is simply
/// missing from the map, and the caller shows a short id until it is.
Map<String, VariantLabel> _variantLabels(
    WidgetRef ref, Iterable<String> variantIds) {
  final ids = variantIdsKey(variantIds);
  if (ids.isEmpty) return const {};
  final all = ids.split(',');
  final labels = <String, VariantLabel>{};
  for (var i = 0; i < all.length; i += _resolveChunk) {
    final chunk = all.sublist(
        i, i + _resolveChunk > all.length ? all.length : i + _resolveChunk);
    labels.addAll(
        ref.watch(variantLabelsProvider(chunk.join(','))).value ?? const {});
  }
  return labels;
}

class _OnHandReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reportAsync = ref.watch(onHandReportProvider);
    final cs = Theme.of(context).colorScheme;
    final gutter = context.pageGutter;

    return reportAsync.when(
      loading: () => const LoadingView(label: 'Loading on-hand report…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load on-hand report.'),
        onRetry: () => ref.invalidate(onHandReportProvider),
      ),
      data: (rows) {
        final grandTotal = rows.fold<double>(0, (s, r) => s + r.onHand);
        // A row is a product in a store: one stocked in two stores is two
        // rows but one SKU of the range.
        final skus = rows.map((r) => r.variantId).toSet().length;
        final storeNames = {
          for (final s in ref.watch(storesProvider).value ?? const <StoreInfo>[])
            s.id: s.name,
        };
        final labels = _variantLabels(ref, rows.map((r) => r.variantId));
        String storeName(String id) => storeNames[id] ?? '…${shortRef(id)}';
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _ReportHeader(
              title: 'On-Hand Inventory',
              subtitle: 'Total units across all stores',
              onRefresh: () => ref.invalidate(onHandReportProvider),
              onExportCsv: rows.isEmpty
                  ? null
                  : () {
                      final buf = StringBuffer(
                          'storeId,store,variantId,product,sku,onHand\n');
                      for (final r in rows) {
                        buf.writeln([
                          _csvEscape(r.storeId),
                          _csvEscape(storeNames[r.storeId]),
                          _csvEscape(r.variantId),
                          _csvEscape(labels[r.variantId]?.productName),
                          _csvEscape(labels[r.variantId]?.sku),
                          r.onHand,
                        ].join(','));
                      }
                      _downloadCsv('on-hand.csv', buf.toString());
                    },
            ),
            // Summary chip
            Padding(
              padding: EdgeInsetsDirectional.symmetric(horizontal: gutter),
              child: Chip(
                avatar: const Icon(Icons.inventory_2_outlined, size: 16),
                label: Text('$skus ${skus == 1 ? 'SKU' : 'SKUs'} · '
                    '${grandTotal.toStringAsFixed(0)} units total'),
                backgroundColor: cs.primaryContainer,
              ),
            ),
            const SizedBox(height: AppSpacing.lg),
            if (rows.isEmpty)
              const Expanded(
                child: EmptyState(
                  icon: Icons.bar_chart,
                  title: 'No inventory data yet.',
                  message: 'Receive stock to see the report.',
                ),
              )
            else
              Expanded(
                child: SingleChildScrollView(
                  padding: EdgeInsetsDirectional.fromSTEB(
                      gutter, 0, gutter, AppSpacing.lg),
                  child: Card(
                    clipBehavior: Clip.antiAlias,
                    child: ScrollableTable(
                      child: DataTable(
                        headingRowColor:
                            WidgetStatePropertyAll(cs.surfaceContainerHigh),
                        columnSpacing: AppSpacing.xl,
                        columns: const [
                          DataColumn(label: Text('Store')),
                          DataColumn(label: Text('Product')),
                          DataColumn(label: Text('On hand'), numeric: true),
                        ],
                        rows: rows.map((r) {
                          final sku = variantSku(r.variantId, labels);
                          return DataRow(cells: [
                            DataCell(Text(storeName(r.storeId))),
                            // The product by name, its SKU after it in grey.
                            DataCell(Text.rich(TextSpan(children: [
                              TextSpan(
                                  text: variantDisplayName(r.variantId, labels)),
                              if (sku.isNotEmpty)
                                TextSpan(
                                    text: ' · $sku',
                                    style: TextStyle(color: cs.onSurfaceVariant)),
                            ]))),
                            DataCell(Text(r.onHand.toStringAsFixed(0))),
                          ]);
                        }).toList(),
                      ),
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

String _reportLabel(_ReportType r) {
  switch (r) {
    case _ReportType.sales:
      return 'Sales Revenue';
    case _ReportType.salesByDay:
      return 'Sales by Day';
    case _ReportType.salesByCategory:
      return 'Sales by Category';
    case _ReportType.onHand:
      return 'On-Hand Inventory';
    case _ReportType.supplyDemand:
      return 'Supply / Demand';
    case _ReportType.movements:
      return 'Movement Stats';
    case _ReportType.lowStock:
      return 'Low Stock';
    case _ReportType.valuation:
      return 'Stock Valuation';
    case _ReportType.shrinkage:
      return 'Shrinkage';
    case _ReportType.taxSummary:
      return 'Tax Summary';
    case _ReportType.exceptions:
      return 'Staff Exceptions';
    case _ReportType.salesByHour:
      return 'Sales by Hour';
    case _ReportType.salesByStaff:
      return 'Sales by Staff';
    case _ReportType.tenderMix:
      return 'Tender Mix';
    case _ReportType.stockTurn:
      return 'Stock Turn';
    case _ReportType.grossMargin:
      return 'Gross Margin';
    case _ReportType.deadStock:
      return 'Dead Stock';
    case _ReportType.trialBalance:
      return 'Trial Balance';
    case _ReportType.deferredRevenue:
      return 'Deferred Revenue';
  }
}

IconData _reportIcon(_ReportType r) {
  switch (r) {
    case _ReportType.sales:
      return Icons.payments_outlined;
    case _ReportType.salesByDay:
      return Icons.calendar_view_day_outlined;
    case _ReportType.salesByCategory:
      return Icons.category_outlined;
    case _ReportType.onHand:
      return Icons.inventory_2_outlined;
    case _ReportType.supplyDemand:
      return Icons.balance_outlined;
    case _ReportType.movements:
      return Icons.swap_horiz;
    case _ReportType.lowStock:
      return Icons.production_quantity_limits_outlined;
    case _ReportType.valuation:
      return Icons.savings_outlined;
    case _ReportType.shrinkage:
      return Icons.trending_down;
    case _ReportType.taxSummary:
      return Icons.receipt_long_outlined;
    case _ReportType.exceptions:
      return Icons.gpp_maybe_outlined;
    case _ReportType.salesByHour:
      return Icons.schedule_outlined;
    case _ReportType.salesByStaff:
      return Icons.badge_outlined;
    case _ReportType.tenderMix:
      return Icons.account_balance_wallet_outlined;
    case _ReportType.stockTurn:
      return Icons.autorenew_outlined;
    case _ReportType.grossMargin:
      return Icons.percent_outlined;
    case _ReportType.deadStock:
      return Icons.hourglass_bottom_outlined;
    case _ReportType.trialBalance:
      return Icons.account_balance_outlined;
    case _ReportType.deferredRevenue:
      return Icons.card_giftcard_outlined;
  }
}

// ── The four reports built in horizon 1, finally given a screen ──────────────
//
// Each of these endpoints has existed and been tested server-side for a while.
// None had a client, so nobody using the product could reach them. Everything
// below is the half that was missing.

/// Grouping chips shared by the three reports that offer a `groupBy`.
class _GroupingBar extends ConsumerWidget {
  final StateProvider<String> provider;
  final Map<String, String> options;

  const _GroupingBar({required this.provider, required this.options});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selected = ref.watch(provider);
    return Padding(
      padding: EdgeInsetsDirectional.fromSTEB(context.pageGutter, 0, context.pageGutter, 4),
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          for (final entry in options.entries)
            ChoiceChip(
              label: Text(entry.value),
              selected: selected == entry.key,
              onSelected: (_) =>
                  ref.read(provider.notifier).state = entry.key,
            ),
        ],
      ),
    );
  }
}

class _LowStockReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(lowStockReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading low stock…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load low-stock report.'),
        onRetry: () => ref.invalidate(lowStockReportProvider),
      ),
      data: (rows) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ReportHeader(
            title: 'Low Stock',
            subtitle: 'Items below their own reorder level, worst shortfall first',
            onRefresh: () => ref.invalidate(lowStockReportProvider),
            onExportCsv: rows.isEmpty
                ? null
                : () {
                    final buf = StringBuffer(
                        'storeId,variantId,signal,reorderLevel,availableQty,shortfall\n');
                    for (final r in rows) {
                      buf.writeln([
                        _csvEscape(r.storeId),
                        _csvEscape(r.variantId),
                        _csvEscape(r.signal),
                        r.reorderLevel,
                        r.availableQty,
                        r.shortfall,
                      ].join(','));
                    }
                    _downloadCsv('low-stock.csv', buf.toString());
                  },
          ),
          if (rows.isEmpty)
            const Expanded(
                child: EmptyState(title: 'Nothing is below its reorder level.'))
          else
            Expanded(
              child: SingleChildScrollView(
                padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                child: Card(
                  child: DataTable(
                    headingRowColor:
                        WidgetStatePropertyAll(cs.surfaceContainerHigh),
                    columnSpacing: 24,
                    columns: const [
                      DataColumn(label: Text('Store')),
                      DataColumn(label: Text('Variant')),
                      // Which configured level bound the row: without this a
                      // manager cannot tell a hand-set minimum from a computed
                      // reorder point, and so cannot tell what to change.
                      DataColumn(label: Text('Signal')),
                      DataColumn(label: Text('Level'), numeric: true),
                      DataColumn(label: Text('Available'), numeric: true),
                      DataColumn(label: Text('Short by'), numeric: true),
                    ],
                    rows: rows
                        .map((r) => DataRow(cells: [
                              DataCell(_StoreName(r.storeId)),
                              DataCell(_VariantName(r.variantId, keys: rows.map((x) => x.variantId))),
                              DataCell(Chip(
                                label: Text(r.signal,
                                    style: const TextStyle(fontSize: 11)),
                                visualDensity: VisualDensity.compact,
                              )),
                              DataCell(Text(r.reorderLevel.toStringAsFixed(0))),
                              DataCell(Text(r.availableQty.toStringAsFixed(0))),
                              DataCell(Text(r.shortfall.toStringAsFixed(0),
                                  style: TextStyle(
                                      fontWeight: FontWeight.bold,
                                      color: cs.error))),
                            ]))
                        .toList(),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _ValuationReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Amounts in the business's home currency: the report carries none.
    final home = ref.watch(tenantInfoProvider).value?.currency;
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(valuationReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading valuation…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load valuation report.'),
        onRetry: () => ref.invalidate(valuationReportProvider),
      ),
      data: (rows) {
        final totalValue = rows.fold<double>(0, (s, r) => s + r.value);
        final totalUnvalued = rows.fold<double>(0, (s, r) => s + r.unvaluedQty);
        final totalConsignmentQty = rows.fold<double>(0, (s, r) => s + r.consignmentQty);
        final totalConsignmentValue = rows.fold<double>(0, (s, r) => s + r.consignmentValue);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _ReportHeader(
              title: 'Stock Valuation',
              subtitle:
                  "What the business's own holding is worth on its configured cost basis; consignment stock, the supplier's until it sells, is shown apart",
              onRefresh: () => ref.invalidate(valuationReportProvider),
              onExportCsv: rows.isEmpty
                  ? null
                  : () {
                      final buf = StringBuffer(
                          'groupKey,method,onHandQty,unvaluedQty,value,consignmentQty,consignmentValue\n');
                      for (final r in rows) {
                        buf.writeln([
                          _csvEscape(r.groupKey),
                          _csvEscape(r.method),
                          r.onHandQty,
                          r.unvaluedQty,
                          r.value,
                          r.consignmentQty,
                          r.consignmentValue,
                        ].join(','));
                      }
                      _downloadCsv('valuation.csv', buf.toString());
                    },
            ),
            _GroupingBar(
              provider: valuationGroupingProvider,
              options: const {'STORE': 'By store', 'VARIANT': 'By variant'},
            ),
            // Uncosted stock is called out rather than folded into the total:
            // valuing it at zero would quietly understate the holding, which is
            // the one number this report exists to get right.
            if (totalUnvalued > 0)
              Padding(
                padding: EdgeInsetsDirectional.fromSTEB(context.pageGutter, 8, context.pageGutter, 0),
                child: Row(children: [
                  Icon(Icons.info_outline, size: 16, color: cs.outline),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '${totalUnvalued.toStringAsFixed(0)} units carry no cost and are '
                      'excluded from the value below, not counted as zero.',
                      style: TextStyle(color: cs.outline, fontSize: 13),
                    ),
                  ),
                ]),
              ),
            const SizedBox(height: 8),
            if (rows.isEmpty)
              const Expanded(child: EmptyState(title: 'No stock to value.'))
            else
              Expanded(
                child: SingleChildScrollView(
                  padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                  child: Card(
                    child: DataTable(
                      headingRowColor:
                          WidgetStatePropertyAll(cs.surfaceContainerHigh),
                      columnSpacing: 24,
                      columns: const [
                        DataColumn(label: Text('Group')),
                        DataColumn(label: Text('Basis')),
                        DataColumn(label: Text('On hand'), numeric: true),
                        DataColumn(label: Text('Uncosted'), numeric: true),
                        DataColumn(label: Text('Value'), numeric: true),
                        DataColumn(label: Text('On consignment'), numeric: true),
                        DataColumn(label: Text("Suppliers' value"), numeric: true),
                      ],
                      rows: [
                        ...rows.map((r) => DataRow(cells: [
                              DataCell(_GroupName(grouping: ref.watch(valuationGroupingProvider), groupKey: r.groupKey, keys: rows.map((x) => x.groupKey))),
                              DataCell(Text(humanizeCode(r.method))),
                              DataCell(Text(r.onHandQty.toStringAsFixed(0))),
                              DataCell(Text(r.unvaluedQty.toStringAsFixed(0),
                                  style: TextStyle(
                                      color: r.unvaluedQty > 0
                                          ? cs.outline
                                          : null))),
                              DataCell(Text(_money(r.value, home))),
                              DataCell(Text(r.consignmentQty.toStringAsFixed(0),
                                  style: TextStyle(
                                      color: r.consignmentQty > 0
                                          ? null
                                          : cs.outline))),
                              DataCell(Text(_money(r.consignmentValue, home),
                                  style: TextStyle(
                                      color: r.consignmentValue > 0
                                          ? null
                                          : cs.outline))),
                            ])),
                        DataRow(cells: [
                          const DataCell(Text('Total',
                              style: TextStyle(fontWeight: FontWeight.bold))),
                          const DataCell(Text('')),
                          const DataCell(Text('')),
                          DataCell(Text(totalUnvalued.toStringAsFixed(0))),
                          DataCell(Text(_money(totalValue, home),
                              style:
                                  const TextStyle(fontWeight: FontWeight.bold))),
                          DataCell(Text(totalConsignmentQty.toStringAsFixed(0))),
                          DataCell(Text(_money(totalConsignmentValue, home),
                              style:
                                  const TextStyle(fontWeight: FontWeight.bold))),
                        ]),
                      ],
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _ShrinkageReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final range = ref.watch(reportDateRangeProvider);
    final async = ref.watch(shrinkageReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading shrinkage…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load shrinkage report.'),
        onRetry: () => ref.invalidate(shrinkageReportProvider),
      ),
      data: (rows) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ReportHeader(
            title: 'Shrinkage',
            subtitle:
                'Stock written off and found${_period(range)}',
            onRefresh: () => ref.invalidate(shrinkageReportProvider),
            onExportCsv: rows.isEmpty
                ? null
                : () {
                    final buf = StringBuffer(
                        'groupKey,qtyWrittenOff,qtyFound,netQty,movements\n');
                    for (final r in rows) {
                      buf.writeln([
                        _csvEscape(r.groupKey),
                        r.qtyWrittenOff,
                        r.qtyFound,
                        r.netQty,
                        r.movements,
                      ].join(','));
                    }
                    _downloadCsv('shrinkage.csv', buf.toString());
                  },
          ),
          _GroupingBar(
            provider: shrinkageGroupingProvider,
            options: const {
              'REASON': 'By reason',
              'ACTOR': 'By staff member',
              'STORE': 'By store',
            },
          ),
          const _DateRangeBar(),
          const SizedBox(height: 12),
          if (rows.isEmpty)
            const Expanded(
                child: EmptyState(title: 'No stock adjustments in this range.'))
          else
            Expanded(
              child: SingleChildScrollView(
                padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                child: Card(
                  child: DataTable(
                    headingRowColor:
                        WidgetStatePropertyAll(cs.surfaceContainerHigh),
                    columnSpacing: 24,
                    columns: const [
                      DataColumn(label: Text('Group')),
                      // Written off and found are kept apart on purpose: a store
                      // that wrote off 100 and found 100 others is not a store
                      // that did nothing, and a net column alone would say it was.
                      DataColumn(label: Text('Written off'), numeric: true),
                      DataColumn(label: Text('Found'), numeric: true),
                      DataColumn(label: Text('Net'), numeric: true),
                      DataColumn(label: Text('Movements'), numeric: true),
                    ],
                    rows: rows
                        .map((r) => DataRow(cells: [
                              DataCell(_GroupName(grouping: ref.watch(shrinkageGroupingProvider), groupKey: r.groupKey, keys: rows.map((x) => x.groupKey))),
                              DataCell(Text(r.qtyWrittenOff.toStringAsFixed(0),
                                  style: TextStyle(
                                      color: r.qtyWrittenOff > 0
                                          ? cs.error
                                          : null))),
                              DataCell(Text(r.qtyFound.toStringAsFixed(0))),
                              DataCell(Text(r.netQty.toStringAsFixed(0),
                                  style: const TextStyle(
                                      fontWeight: FontWeight.bold))),
                              DataCell(Text('${r.movements}')),
                            ]))
                        .toList(),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _TaxSummaryReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Amounts in the business's home currency: the report carries none.
    final home = ref.watch(tenantInfoProvider).value?.currency;
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(taxSummaryReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading tax summary…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load tax summary.'),
        onRetry: () => ref.invalidate(taxSummaryReportProvider),
      ),
      data: (report) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ReportHeader(
            title: 'Tax Summary',
            subtitle:
                'Reconciles to VAT return boxes 1 and 6${report.periodFrom != null ? ' · ${_day(report.periodFrom)} → ${_day(report.periodTo)}' : ''}',
            onRefresh: () => ref.invalidate(taxSummaryReportProvider),
            onExportCsv: report.rows.isEmpty
                ? null
                : () {
                    final buf = StringBuffer(
                        'groupKey,exempt,netAmount,vatAmount,grossAmount,transactions\n');
                    for (final r in report.rows) {
                      buf.writeln([
                        _csvEscape(r.groupKey),
                        r.exempt,
                        r.netAmount,
                        r.vatAmount,
                        r.grossAmount,
                        r.transactions,
                      ].join(','));
                    }
                    _downloadCsv('tax-summary.csv', buf.toString());
                  },
          ),
          _GroupingBar(
            provider: taxGroupingProvider,
            options: const {
              'CODE': 'By rate',
              'STORE': 'By store',
              'MONTH': 'By month',
            },
          ),
          const _DateRangeBar(),
          // The server's own DTO notes that a mismatch here means an exempt line
          // is carrying VAT, and that the Box 1 query drops it silently. Silent
          // is the one thing it must not be on the screen a return is filed from.
          if (report.boxOneDisagrees)
            Container(
              margin: EdgeInsetsDirectional.fromSTEB(context.pageGutter, 12, context.pageGutter, 0),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: cs.errorContainer,
                borderRadius: AppRadius.chip,
              ),
              child: Row(children: [
                Icon(Icons.warning_amber_outlined, color: cs.onErrorContainer),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'Box 1 (${_money(report.totals.outputVat, home)}) does not match total VAT '
                    '(${_money(report.totals.vatAmount, home)}): a line marked exempt is carrying VAT. '
                    'Check the rows below before filing.',
                    style: TextStyle(color: cs.onErrorContainer, fontSize: 13),
                  ),
                ),
              ]),
            ),
          const SizedBox(height: 12),
          if (report.rows.isEmpty)
            const Expanded(
                child: EmptyState(title: 'No tax transactions in this range.'))
          else
            Expanded(
              child: SingleChildScrollView(
                padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                child: Card(
                  child: DataTable(
                    headingRowColor:
                        WidgetStatePropertyAll(cs.surfaceContainerHigh),
                    columnSpacing: 24,
                    columns: const [
                      DataColumn(label: Text('Group')),
                      DataColumn(label: Text('Net'), numeric: true),
                      DataColumn(label: Text('VAT'), numeric: true),
                      DataColumn(label: Text('Gross'), numeric: true),
                      DataColumn(label: Text('Txns'), numeric: true),
                    ],
                    rows: [
                      ...report.rows.map((r) => DataRow(cells: [
                            DataCell(Row(children: [
                              _GroupName(grouping: ref.watch(taxGroupingProvider), groupKey: r.groupKey, keys: report.rows.map((x) => x.groupKey)),
                              if (r.exempt) ...[
                                const SizedBox(width: 6),
                                const Chip(
                                  label: Text('exempt',
                                      style: TextStyle(fontSize: 10)),
                                  visualDensity: VisualDensity.compact,
                                  padding: EdgeInsets.zero,
                                ),
                              ],
                            ])),
                            DataCell(Text(_money(r.netAmount, home))),
                            DataCell(Text(_money(r.vatAmount, home))),
                            DataCell(Text(_money(r.grossAmount, home))),
                            DataCell(Text('${r.transactions}')),
                          ])),
                      DataRow(cells: [
                        const DataCell(Text('Total (Box 6 / Box 1)',
                            style: TextStyle(fontWeight: FontWeight.bold))),
                        DataCell(Text(_money(report.totals.netAmount, home),
                            style:
                                const TextStyle(fontWeight: FontWeight.bold))),
                        DataCell(Text(_money(report.totals.outputVat, home),
                            style:
                                const TextStyle(fontWeight: FontWeight.bold))),
                        DataCell(
                            Text(_money(report.totals.grossAmount, home))),
                        DataCell(Text('${report.totals.transactions}')),
                      ]),
                    ],
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// Loss prevention's view: who is discounting, voiding and opening the drawer
/// without a sale, against how much they actually sold.
class _ExceptionReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Amounts in the business's home currency: the report carries none.
    final home = ref.watch(tenantInfoProvider).value?.currency;
    final cs = Theme.of(context).colorScheme;
    final range = ref.watch(reportDateRangeProvider);
    final async = ref.watch(exceptionReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading staff exceptions…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load the exception report.'),
        onRetry: () => ref.invalidate(exceptionReportProvider),
      ),
      data: (report) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ReportHeader(
            title: 'Staff Exceptions',
            subtitle:
                'Discounts, voids and no-sales${_period(range)}',
            onRefresh: () => ref.invalidate(exceptionReportProvider),
            onExportCsv: report.rows.isEmpty
                ? null
                : () {
                    final buf = StringBuffer(
                        'groupKey,discounts,discountAmount,voids,noSales,sales,salesValue\n');
                    for (final r in report.rows) {
                      buf.writeln([
                        _csvEscape(r.groupKey),
                        r.discounts,
                        r.discountAmount,
                        r.voids,
                        r.noSales,
                        r.sales,
                        r.salesValue,
                      ].join(','));
                    }
                    _downloadCsv('staff-exceptions.csv', buf.toString());
                  },
          ),
          _GroupingBar(
            provider: exceptionGroupingProvider,
            options: const {'ACTOR': 'By staff member', 'STORE': 'By store'},
          ),
          const _DateRangeBar(),
          // Without a denominator this table ranks people by how much they
          // worked. Saying so is the difference between a report and a list that
          // looks like evidence.
          if (!report.journalCoverage && report.rows.isNotEmpty)
            Container(
              margin: EdgeInsetsDirectional.fromSTEB(context.pageGutter, 12, context.pageGutter, 0),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: cs.tertiaryContainer,
                borderRadius: AppRadius.chip,
              ),
              child: Row(children: [
                Icon(Icons.info_outline, color: cs.onTertiaryContainer),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'No sales were journalled in this period, so these are raw counts with '
                    'nothing to divide by — a cashier who served a hundred customers and one '
                    'who served three look the same here. Compare rates only once the Sales '
                    'column is populated.',
                    style: TextStyle(color: cs.onTertiaryContainer, fontSize: 13),
                  ),
                ),
              ]),
            ),
          const SizedBox(height: 12),
          if (report.rows.isEmpty)
            const Expanded(
                child: EmptyState(title: 'No staff exceptions in this range.'))
          else
            Expanded(
              child: SingleChildScrollView(
                padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                child: Card(
                  child: DataTable(
                    headingRowColor:
                        WidgetStatePropertyAll(cs.surfaceContainerHigh),
                    columnSpacing: 20,
                    columns: const [
                      DataColumn(label: Text('Who')),
                      DataColumn(label: Text('Discounts'), numeric: true),
                      DataColumn(label: Text('Value'), numeric: true),
                      DataColumn(label: Text('Voids'), numeric: true),
                      DataColumn(label: Text('No-sales'), numeric: true),
                      DataColumn(label: Text('Sales'), numeric: true),
                      DataColumn(label: Text('Per 100'), numeric: true),
                    ],
                    rows: report.rows.map((r) {
                      final rate = r.ratePerHundredSales;
                      final unattributed = r.groupKey == 'UNATTRIBUTED';
                      return DataRow(cells: [
                        DataCell(unattributed
                            // Kept and labelled rather than dropped: exceptions
                            // nobody is accountable for are the ones to look at.
                            ? Text('Unattributed',
                                style: TextStyle(
                                    fontStyle: FontStyle.italic, color: cs.outline))
                            : _GroupName(grouping: ref.watch(exceptionGroupingProvider), groupKey: r.groupKey, keys: report.rows.map((x) => x.groupKey))),
                        DataCell(Text('${r.discounts}')),
                        DataCell(Text(_money(r.discountAmount, home))),
                        DataCell(Text('${r.voids}')),
                        DataCell(Text('${r.noSales}')),
                        DataCell(Text('${r.sales}')),
                        DataCell(Text(
                          rate == null ? '—' : rate.toStringAsFixed(1),
                          style: TextStyle(
                              fontWeight: FontWeight.bold, color: cs.outline),
                        )),
                      ]);
                    }).toList(),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

// ── The four reports that finish the pack ────────────────────────────────────
//
// Sales by hour, sales by staff, tender mix, and stock turn with dead-stock
// ageing. Built where each one's data lives rather than in reporting-svc, whose
// sales projection carries no tender, no cashier and no cost.

class _SalesByHourReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Amounts in the business's home currency: the report carries none.
    final home = ref.watch(tenantInfoProvider).value?.currency;
    final cs = Theme.of(context).colorScheme;
    final range = ref.watch(reportDateRangeProvider);
    final async = ref.watch(salesByHourReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading sales by hour…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load sales by hour.'),
        onRetry: () => ref.invalidate(salesByHourReportProvider),
      ),
      data: (rows) {
        // The busiest hour, used to scale the bars. Taken from the rows rather
        // than assumed, so a quiet week still fills the width.
        final peak = rows.fold<double>(
            0, (m, r) => r.grossAmount > m ? r.grossAmount : m);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _ReportHeader(
              title: 'Sales by Hour',
              subtitle:
                  'When the shop is actually busy, on your local clock${_period(range)}',
              onRefresh: () => ref.invalidate(salesByHourReportProvider),
              onExportCsv: rows.isEmpty
                  ? null
                  : () {
                      final buf = StringBuffer(
                          'hourOfDay,orders,grossAmount,discountAmount,averageBasket\n');
                      for (final r in rows) {
                        buf.writeln([
                          r.hourOfDay,
                          r.orders,
                          r.grossAmount,
                          r.discountAmount,
                          r.averageBasket,
                        ].join(','));
                      }
                      _downloadCsv('sales-by-hour.csv', buf.toString());
                    },
            ),
            _GroupingBar(
              provider: salesByHourChannelProvider,
              options: const {
                '': 'All channels',
                'POS': 'In store',
                'ONLINE': 'Online',
              },
            ),
            const _DateRangeBar(),
            const SizedBox(height: 12),
            if (rows.isEmpty)
              const Expanded(
                  child: EmptyState(title: 'No sales in this range.'))
            else
              Expanded(
                child: SingleChildScrollView(
                  padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                  child: Card(
                    child: DataTable(
                      headingRowColor:
                          WidgetStatePropertyAll(cs.surfaceContainerHigh),
                      columnSpacing: 20,
                      columns: const [
                        DataColumn(label: Text('Hour')),
                        DataColumn(label: Text('Orders'), numeric: true),
                        DataColumn(label: Text('Gross'), numeric: true),
                        DataColumn(label: Text('Discount'), numeric: true),
                        DataColumn(label: Text('Avg basket'), numeric: true),
                        DataColumn(label: Text('')),
                      ],
                      rows: rows.map((r) {
                        return DataRow(cells: [
                          DataCell(Text(_hourLabel(r.hourOfDay))),
                          DataCell(Text('${r.orders}')),
                          DataCell(Text(_money(r.grossAmount, home))),
                          DataCell(Text(_money(r.discountAmount, home))),
                          DataCell(Text(_money(r.averageBasket, home))),
                          DataCell(SizedBox(
                            width: 90,
                            child: LinearProgressIndicator(
                              value: peak == 0 ? 0 : r.grossAmount / peak,
                              backgroundColor: cs.surfaceContainerHighest,
                            ),
                          )),
                        ]);
                      }).toList(),
                    ),
                  ),
                ),
              ),
            // Hours with no trade produce no row at all. Saying so is the
            // difference between "we were shut" and "nobody came".
            if (rows.isNotEmpty && rows.length < 24)
              Padding(
                padding: EdgeInsetsDirectional.fromSTEB(context.pageGutter, 8, context.pageGutter, 16),
                child: Text(
                  'Hours with no sales are not listed — ${24 - rows.length} of the 24 are absent '
                  'from this range rather than shown as zero.',
                  style: TextStyle(color: cs.outline, fontSize: 12.5),
                ),
              ),
          ],
        );
      },
    );
  }
}

String _hourLabel(int hour) {
  final h = hour.toString().padLeft(2, '0');
  final next = ((hour + 1) % 24).toString().padLeft(2, '0');
  return '$h:00–$next:00';
}

class _SalesByStaffReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Amounts in the business's home currency: the report carries none.
    final home = ref.watch(tenantInfoProvider).value?.currency;
    final cs = Theme.of(context).colorScheme;
    final range = ref.watch(reportDateRangeProvider);
    final async = ref.watch(salesByStaffReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading sales by staff…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load sales by staff.'),
        onRetry: () => ref.invalidate(salesByStaffReportProvider),
      ),
      data: (rows) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ReportHeader(
            title: 'Sales by Staff',
            subtitle:
                'What each cashier rang up${_period(range)}',
            onRefresh: () => ref.invalidate(salesByStaffReportProvider),
            onExportCsv: rows.isEmpty
                ? null
                : () {
                    final buf = StringBuffer(
                        'groupKey,sales,grossAmount,discountAmount,averageBasket,discountRate\n');
                    for (final r in rows) {
                      buf.writeln([
                        _csvEscape(r.groupKey),
                        r.sales,
                        r.grossAmount,
                        r.discountAmount,
                        r.averageBasket ?? '',
                        r.discountRate ?? '',
                      ].join(','));
                    }
                    _downloadCsv('sales-by-staff.csv', buf.toString());
                  },
          ),
          const _DateRangeBar(),
          // This is the POS journal, not the order book. A manager comparing it
          // against Sales Revenue and finding it short is looking at the online
          // orders, which have no cashier to attribute.
          Container(
            margin: EdgeInsetsDirectional.fromSTEB(context.pageGutter, 12, context.pageGutter, 0),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: cs.surfaceContainerHigh,
              borderRadius: AppRadius.chip,
            ),
            child: Row(children: [
              Icon(Icons.storefront_outlined, size: 18, color: cs.outline),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  'In-store sales only. Online orders have no cashier, so these totals will '
                  'not add up to Sales Revenue for the same period.',
                  style: TextStyle(color: cs.outline, fontSize: 13),
                ),
              ),
            ]),
          ),
          const SizedBox(height: 12),
          if (rows.isEmpty)
            const Expanded(
                child: EmptyState(title: 'No journalled sales in this range.'))
          else
            Expanded(
              child: SingleChildScrollView(
                padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                child: Card(
                  child: DataTable(
                    headingRowColor:
                        WidgetStatePropertyAll(cs.surfaceContainerHigh),
                    columnSpacing: 20,
                    columns: const [
                      DataColumn(label: Text('Cashier')),
                      DataColumn(label: Text('Sales'), numeric: true),
                      DataColumn(label: Text('Gross'), numeric: true),
                      DataColumn(label: Text('Avg basket'), numeric: true),
                      DataColumn(label: Text('Discounted'), numeric: true),
                      DataColumn(label: Text('Disc %'), numeric: true),
                    ],
                    rows: rows.map((r) {
                      final unattributed = r.groupKey == 'UNATTRIBUTED';
                      return DataRow(cells: [
                        DataCell(unattributed
                            // Kept and labelled: a sale with no cashier is a
                            // gap in the audit trail, not a row to tidy away.
                            ? Text('Unattributed',
                                style: TextStyle(
                                    fontStyle: FontStyle.italic,
                                    color: cs.outline))
                            : _GroupName(grouping: 'ACTOR', groupKey: r.groupKey, keys: rows.map((x) => x.groupKey))),
                        DataCell(Text('${r.sales}')),
                        DataCell(Text(_money(r.grossAmount, home))),
                        DataCell(Text(
                            r.averageBasket == null ? '—' : _money(r.averageBasket!, home))),
                        DataCell(Text(_money(r.discountAmount, home))),
                        DataCell(Text(
                          r.discountRate == null
                              ? '—'
                              : '${r.discountRate!.toStringAsFixed(1)}%',
                          style: TextStyle(
                              fontWeight: FontWeight.bold, color: cs.outline),
                        )),
                      ]);
                    }).toList(),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _TenderMixReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Amounts in the business's home currency: the report carries none.
    final home = ref.watch(tenantInfoProvider).value?.currency;
    final cs = Theme.of(context).colorScheme;
    final range = ref.watch(reportDateRangeProvider);
    final async = ref.watch(tenderMixReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading tender mix…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load the tender mix.'),
        onRetry: () => ref.invalidate(tenderMixReportProvider),
      ),
      data: (rows) {
        final failures = rows.fold<int>(0, (n, r) => n + r.failedCount);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _ReportHeader(
              title: 'Tender Mix',
              subtitle:
                  'How the take split across payment methods${_period(range)}',
              onRefresh: () => ref.invalidate(tenderMixReportProvider),
              onExportCsv: rows.isEmpty
                  ? null
                  : () {
                      final buf = StringBuffer(
                          'method,capturedAmount,capturedCount,refundedAmount,refundedCount,failedCount,netAmount,shareOfNet\n');
                      for (final r in rows) {
                        buf.writeln([
                          _csvEscape(r.method),
                          r.capturedAmount,
                          r.capturedCount,
                          r.refundedAmount,
                          r.refundedCount,
                          r.failedCount,
                          r.netAmount,
                          r.shareOfNet ?? '',
                        ].join(','));
                      }
                      _downloadCsv('tender-mix.csv', buf.toString());
                    },
            ),
            const _DateRangeBar(),
            // Declines are the one thing here that is not a sales figure, and
            // the one a manager can act on today.
            if (failures > 0)
              Container(
                margin: EdgeInsetsDirectional.fromSTEB(context.pageGutter, 12, context.pageGutter, 0),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: cs.tertiaryContainer,
                  borderRadius: AppRadius.chip,
                ),
                child: Row(children: [
                  Icon(Icons.error_outline, color: cs.onTertiaryContainer),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      '$failures tender${failures == 1 ? '' : 's'} did not capture in this '
                      'period. A method whose failures climb against healthy volume is a '
                      'terminal or acquirer problem, not a sales one.',
                      style: TextStyle(
                          color: cs.onTertiaryContainer, fontSize: 13),
                    ),
                  ),
                ]),
              ),
            const SizedBox(height: 12),
            if (rows.isEmpty)
              const Expanded(
                  child: EmptyState(title: 'No tenders in this range.'))
            else
              Expanded(
                child: SingleChildScrollView(
                  padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                  child: Card(
                    child: DataTable(
                      headingRowColor:
                          WidgetStatePropertyAll(cs.surfaceContainerHigh),
                      columnSpacing: 20,
                      columns: const [
                        DataColumn(label: Text('Method')),
                        DataColumn(label: Text('Captured'), numeric: true),
                        DataColumn(label: Text('#'), numeric: true),
                        DataColumn(label: Text('Refunded'), numeric: true),
                        DataColumn(label: Text('Failed'), numeric: true),
                        DataColumn(label: Text('Net'), numeric: true),
                        DataColumn(label: Text('Share'), numeric: true),
                      ],
                      rows: rows.map((r) {
                        return DataRow(cells: [
                          DataCell(Text(_methodWords(r.method))),
                          DataCell(Text(_money(r.capturedAmount, home))),
                          DataCell(Text('${r.capturedCount}')),
                          DataCell(Text(_money(r.refundedAmount, home))),
                          DataCell(Text(
                            '${r.failedCount}',
                            style: TextStyle(
                                color: r.failedCount > 0 ? cs.error : null),
                          )),
                          DataCell(Text(_money(r.netAmount, home))),
                          DataCell(Text(
                            r.shareOfNet == null
                                ? '—'
                                : '${r.shareOfNet!.toStringAsFixed(1)}%',
                            style: TextStyle(
                                fontWeight: FontWeight.bold,
                                color: cs.outline),
                          )),
                        ]);
                      }).toList(),
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _StockTurnReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Amounts in the business's home currency: the report carries none.
    final home = ref.watch(tenantInfoProvider).value?.currency;
    final cs = Theme.of(context).colorScheme;
    final range = ref.watch(reportDateRangeProvider);
    final async = ref.watch(stockTurnReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading stock turn…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load stock turn.'),
        onRetry: () => ref.invalidate(stockTurnReportProvider),
      ),
      data: (report) {
        final uncosted = report.rows
            .fold<double>(0, (n, r) => n + r.uncostedSaleQty);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _ReportHeader(
              title: 'Stock Turn',
              subtitle:
                  'How many times the holding sold through over ${report.windowDays} day${report.windowDays == 1 ? '' : 's'}${_period(range)}',
              onRefresh: () => ref.invalidate(stockTurnReportProvider),
              onExportCsv: report.rows.isEmpty
                  ? null
                  : () {
                      final buf = StringBuffer(
                          'groupKey,cogs,uncostedSaleQty,openingValue,closingValue,averageValue,turnoverRatio,daysOnHand\n');
                      for (final r in report.rows) {
                        buf.writeln([
                          _csvEscape(r.groupKey),
                          r.cogs,
                          r.uncostedSaleQty,
                          r.openingValue,
                          r.closingValue,
                          r.averageValue,
                          r.turnoverRatio ?? '',
                          r.daysOnHand ?? '',
                        ].join(','));
                      }
                      _downloadCsv('stock-turn.csv', buf.toString());
                    },
            ),
            _GroupingBar(
              provider: stockTurnGroupingProvider,
              options: const {'STORE': 'By store', 'VARIANT': 'By product'},
            ),
            const _DateRangeBar(),
            // Two separate caveats, and they mean different things: one says
            // the opening figures are a floor, the other says some of what sold
            // could not be costed at all.
            if (!report.historyComplete)
              const _Caveat(
                icon: Icons.history_toggle_off,
                text:
                    'Part of the movement history for this window has been archived, so opening '
                    'values are a floor rather than a figure and the ratios read high.',
              ),
            if (uncosted > 0)
              _Caveat(
                icon: Icons.help_outline,
                text:
                    '${uncosted.toStringAsFixed(3)} units sold out of batches with no cost price. '
                    'They are excluded from cost of goods sold rather than costed at zero, which '
                    'would have understated the turns.',
              ),
            const SizedBox(height: 12),
            _ReportTable(
              emptyText: 'No stock movement in this range.',
              columns: const [
                DataColumn(label: Text('Group')),
                DataColumn(label: Text('COGS'), numeric: true),
                DataColumn(label: Text('Opening'), numeric: true),
                DataColumn(label: Text('Closing'), numeric: true),
                DataColumn(label: Text('Turns'), numeric: true),
                DataColumn(label: Text('Days on hand'), numeric: true),
              ],
              rows: [
                for (final r in report.rows)
                  DataRow(cells: [
                    DataCell(_GroupName(grouping: ref.watch(stockTurnGroupingProvider), groupKey: r.groupKey, keys: report.rows.map((x) => x.groupKey))),
                    DataCell(Text(_money(r.cogs, home))),
                    DataCell(Text(_money(r.openingValue, home))),
                    DataCell(Text(_money(r.closingValue, home))),
                    DataCell(Text(
                      // A dash, not a zero: nothing to turn is not the
                      // same finding as turning it zero times.
                      r.turnoverRatio?.toStringAsFixed(2) ?? '—',
                      style: TextStyle(
                          fontWeight: FontWeight.bold, color: cs.outline),
                    )),
                    DataCell(Text(r.daysOnHand?.toStringAsFixed(1) ?? '—')),
                  ]),
              ],
            ),
          ],
        );
      },
    );
  }
}

class _GrossMarginReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Amounts in the business's home currency: the report carries none.
    final home = ref.watch(tenantInfoProvider).value?.currency;
    final cs = Theme.of(context).colorScheme;
    final range = ref.watch(reportDateRangeProvider);
    final async = ref.watch(grossMarginReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading gross margin…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load gross margin.'),
        onRetry: () => ref.invalidate(grossMarginReportProvider),
      ),
      data: (report) {
        double total(double Function(GrossMarginRow) f) =>
            report.rows.fold<double>(0, (n, r) => n + f(r));
        final revenue = total((r) => r.revenue);
        final margin = total((r) => r.grossMargin);
        final unpriced = total((r) => r.unpricedSaleQty);
        final uncosted = total((r) => r.uncostedSaleQty);
        // The report carries no currency code (inventory-svc stores the
        // amounts order-svc sent), so they are shown in the business's own
        // home currency, read from tenant-svc — never a platform default
        // (SJ-D53).
        String amount(double v) => _money(v, home);
        final share = revenue > 0
            ? ' (${(margin * 100 / revenue).toStringAsFixed(1)}%)'
            : '';
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _ReportHeader(
              title: 'Gross Margin',
              subtitle:
                  'What sales earned against what they cost over ${report.windowDays} day${report.windowDays == 1 ? '' : 's'} · margin ${amount(margin)}$share${_period(range)}',
              onRefresh: () => ref.invalidate(grossMarginReportProvider),
              onExportCsv: report.rows.isEmpty
                  ? null
                  : () {
                      final buf = StringBuffer(
                          'groupKey,revenue,cogs,grossMargin,marginPercent,averageValue,gmroi,annualisedGmroi,uncostedSaleQty,unpricedSaleQty\n');
                      for (final r in report.rows) {
                        buf.writeln([
                          _csvEscape(r.groupKey),
                          r.revenue,
                          r.cogs,
                          r.grossMargin,
                          r.marginPercent ?? '',
                          r.averageValue,
                          r.gmroi ?? '',
                          r.annualisedGmroi ?? '',
                          r.uncostedSaleQty,
                          r.unpricedSaleQty,
                        ].join(','));
                      }
                      _downloadCsv('gross-margin.csv', buf.toString());
                    },
            ),
            _GroupingBar(
              provider: grossMarginGroupingProvider,
              options: const {'STORE': 'By store', 'VARIANT': 'By product'},
            ),
            const _DateRangeBar(),
            // The two quantity caveats pull the margin in opposite directions,
            // so each says which way.
            if (!report.historyComplete)
              const _Caveat(
                icon: Icons.history_toggle_off,
                text:
                    'Part of the movement history for this window has been archived, so average '
                    'holdings are a floor and GMROI reads high.',
              ),
            if (unpriced > 0)
              _Caveat(
                icon: Icons.money_off_outlined,
                text:
                    '${unpriced.toStringAsFixed(3)} units sold with no revenue recorded, from sales '
                    'made before orders carried it. Their cost is still counted, so the margin '
                    'reads low rather than being invented.',
              ),
            if (uncosted > 0)
              _Caveat(
                icon: Icons.help_outline,
                text:
                    '${uncosted.toStringAsFixed(3)} units sold out of batches with no cost price. '
                    'They are left out of cost of goods sold, so the margin reads high.',
              ),
            const SizedBox(height: 12),
            _ReportTable(
              emptyText: 'No sales in this range.',
              columns: const [
                DataColumn(label: Text('Group')),
                DataColumn(label: Text('Revenue'), numeric: true),
                DataColumn(label: Text('COGS'), numeric: true),
                DataColumn(label: Text('Margin'), numeric: true),
                DataColumn(label: Text('Margin %'), numeric: true),
                DataColumn(label: Text('GMROI'), numeric: true),
                DataColumn(label: Text('Per year'), numeric: true),
              ],
              rows: [
                for (final r in report.rows)
                  DataRow(cells: [
                    DataCell(_GroupName(grouping: ref.watch(grossMarginGroupingProvider), groupKey: r.groupKey, keys: report.rows.map((x) => x.groupKey))),
                    DataCell(Text(amount(r.revenue))),
                    DataCell(Text(amount(r.cogs))),
                    DataCell(Text(
                      amount(r.grossMargin),
                      style: TextStyle(
                          fontWeight: FontWeight.bold,
                          color: r.grossMargin < 0 ? cs.error : null),
                    )),
                    // Dashes, not zeros: nothing earned has no margin
                    // percentage and nothing held has no return on it.
                    DataCell(Text(r.marginPercent == null
                        ? '—'
                        : '${r.marginPercent!.toStringAsFixed(1)}%')),
                    DataCell(Text(r.gmroi?.toStringAsFixed(2) ?? '—')),
                    DataCell(Text(r.annualisedGmroi?.toStringAsFixed(2) ?? '—')),
                  ]),
              ],
            ),
          ],
        );
      },
    );
  }
}

class _DeadStockReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Amounts in the business's home currency: the report carries none.
    final home = ref.watch(tenantInfoProvider).value?.currency;
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(deadStockReportProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading dead stock…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load dead stock.'),
        onRetry: () => ref.invalidate(deadStockReportProvider),
      ),
      data: (rows) {
        final atRisk = rows.fold<double>(0, (n, r) => n + r.value);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _ReportHeader(
              title: 'Dead Stock',
              subtitle:
                  'Stock aged by how long since it last sold · ${_money(atRisk, home)} at risk',
              onRefresh: () => ref.invalidate(deadStockReportProvider),
              onExportCsv: rows.isEmpty
                  ? null
                  : () {
                      final buf = StringBuffer(
                          'groupKey,onHandQty,value,uncostedQty,daysSinceLastSale,neverSold\n');
                      for (final r in rows) {
                        buf.writeln([
                          _csvEscape(r.groupKey),
                          r.onHandQty,
                          r.value,
                          r.uncostedQty,
                          r.daysSinceLastSale ?? '',
                          r.neverSold,
                        ].join(','));
                      }
                      _downloadCsv('dead-stock.csv', buf.toString());
                    },
            ),
            _GroupingBar(
              provider: deadStockGroupingProvider,
              options: const {
                'BUCKET': 'Ageing ladder',
                'STORE': 'By store',
                'VARIANT': 'By product',
              },
            ),
            // No date bar: dead stock is a question about now, not a period.
            const SizedBox(height: 12),
            if (rows.isEmpty)
              const Expanded(
                  child: EmptyState(title: 'No stock on hand.'))
            else
              Expanded(
                child: SingleChildScrollView(
                  padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                  child: Card(
                    child: DataTable(
                      headingRowColor:
                          WidgetStatePropertyAll(cs.surfaceContainerHigh),
                      columnSpacing: 20,
                      columns: const [
                        DataColumn(label: Text('Group')),
                        DataColumn(label: Text('On hand'), numeric: true),
                        DataColumn(label: Text('Value'), numeric: true),
                        DataColumn(label: Text('Idle days'), numeric: true),
                        DataColumn(label: Text('Since')),
                      ],
                      rows: rows.map((r) {
                        final old = (r.daysSinceLastSale ?? 0) > 90;
                        return DataRow(cells: [
                          DataCell(_GroupName(grouping: ref.watch(deadStockGroupingProvider), groupKey: r.groupKey, keys: rows.map((x) => x.groupKey))),
                          DataCell(Text(r.onHandQty.toStringAsFixed(3))),
                          DataCell(Text(_money(r.value, home))),
                          DataCell(Text(
                            '${r.daysSinceLastSale ?? '—'}',
                            style: TextStyle(
                                fontWeight: FontWeight.bold,
                                color: old ? cs.error : cs.outline),
                          )),
                          // Which date the age is measured from changes what
                          // the number means, so the table says which.
                          DataCell(Text(
                            r.neverSold ? 'received' : 'last sale',
                            style: TextStyle(
                                fontSize: 12.5,
                                fontStyle: r.neverSold
                                    ? FontStyle.italic
                                    : FontStyle.normal,
                                color: cs.outline),
                          )),
                        ]);
                      }).toList(),
                    ),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

/// A one-line note under a report's controls, for the caveats that change how a
/// figure should be read rather than merely decorating it.
/// The table a period report sits in below its caveats: a scrolling card, or a
/// centred line saying there is nothing to show when there are no rows.
class _ReportTable extends StatelessWidget {
  final String emptyText;
  final List<DataColumn> columns;
  final List<DataRow> rows;

  const _ReportTable(
      {required this.emptyText, required this.columns, required this.rows});

  @override
  Widget build(BuildContext context) {
    if (rows.isEmpty) {
      return Expanded(child: Center(child: Text(emptyText)));
    }
    return Expanded(
      child: SingleChildScrollView(
        padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
        child: Card(
          child: DataTable(
            headingRowColor: WidgetStatePropertyAll(
                Theme.of(context).colorScheme.surfaceContainerHigh),
            columnSpacing: 20,
            columns: columns,
            rows: rows,
          ),
        ),
      ),
    );
  }
}

class _Caveat extends StatelessWidget {
  final IconData icon;
  final String text;
  const _Caveat({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      margin: EdgeInsetsDirectional.fromSTEB(context.pageGutter, 12, context.pageGutter, 0),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: cs.tertiaryContainer,
        borderRadius: AppRadius.chip,
      ),
      child: Row(children: [
        Icon(icon, color: cs.onTertiaryContainer),
        const SizedBox(width: 10),
        Expanded(
          child: Text(text,
              style:
                  TextStyle(color: cs.onTertiaryContainer, fontSize: 13)),
        ),
      ]),
    );
  }
}

// ── The trial balance (17.1) ─────────────────────────────────────────────────

/// Every nominal code's debits, credits and balance over the range, from the
/// ledger purchase-svc writes on goods receipts, supplier invoices, credit
/// notes, intercompany invoices and manual journals — and the way in to post
/// a manual journal.
/// Sales whose takings did not clear (17.7). A sale paid in full nets 1105
/// Sales Receipts Clearing to zero for its order, so each order listed here is
/// a reconciliation exception: taken but never confirmed, confirmed for more
/// than was taken, or refunded against a sale the ledger never saw.
class _SalesClearingCard extends ConsumerWidget {
  const _SalesClearingCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Amounts in the business's home currency: the report carries none.
    final home = ref.watch(tenantInfoProvider).value?.currency;
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(salesClearingProvider);
    return Padding(
      padding: EdgeInsetsDirectional.fromSTEB(context.pageGutter, 12, context.pageGutter, 0),
      child: async.when(
        loading: () => const LinearProgressIndicator(),
        error: (e, _) => Text(
          friendlyError(e, fallback: 'Could not load the sales clearing.'),
          style: TextStyle(color: cs.error),
        ),
        data: (open) {
          if (open.isEmpty) {
            return Row(children: [
              Icon(Icons.check_circle_outline, color: cs.primary, size: 18),
              const SizedBox(width: 8),
              const Expanded(
                child: Text(
                    "Every sale's takings cleared: nothing is left open on 1105 Sales Receipts Clearing."),
              ),
            ]);
          }
          return Card(
            color: cs.tertiaryContainer,
            child: ExpansionTile(
              key: const Key('sales-clearing'),
              leading: Icon(Icons.rule_folder_outlined, color: cs.onTertiaryContainer),
              title: Text('Open sales clearing: ${open.length} order(s)'),
              subtitle: const Text(
                  'Takings that did not clear against a confirmed sale. Check each before closing the period.'),
              children: [
                for (final o in open)
                  ListTile(
                    dense: true,
                    title: Text('Order ${shortRef(o.orderId)}'),
                    subtitle: Text([
                      if (o.balance < 0) 'taken, no confirmed sale' else 'confirmed for more than was taken',
                      if (o.firstPosted != null) 'since ${o.firstPosted}',
                    ].join(' · ')),
                    trailing: Text(
                      _money(o.balance, home),
                      style: const TextStyle(fontFamily: 'monospace'),
                    ),
                  ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _TrialBalanceReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Amounts in the business's home currency: the report carries none.
    final home = ref.watch(tenantInfoProvider).value?.currency;
    final cs = Theme.of(context).colorScheme;
    final range = ref.watch(reportDateRangeProvider);
    final async = ref.watch(trialBalanceProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading trial balance…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load the trial balance.'),
        onRetry: () => ref.invalidate(trialBalanceProvider),
      ),
      data: (report) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ReportHeader(
            title: 'Trial Balance',
            subtitle:
                'Debits, credits and balance per nominal code${_period(range)}',
            onRefresh: () => ref.invalidate(trialBalanceProvider),
            onExportCsv: report.rows.isEmpty
                ? null
                : () {
                    final buf = StringBuffer(
                        'nominalCode,nominalName,debit,credit,balance\n');
                    for (final r in report.rows) {
                      buf.writeln([
                        _csvEscape(r.nominalCode),
                        _csvEscape(r.nominalName),
                        r.debit.toStringAsFixed(2),
                        r.credit.toStringAsFixed(2),
                        r.balance.toStringAsFixed(2),
                      ].join(','));
                    }
                    _downloadCsv('trial-balance.csv', buf.toString());
                  },
          ),
          Row(
            children: [
              const Expanded(child: _DateRangeBar()),
              Padding(
                padding: EdgeInsetsDirectional.only(end: context.pageGutter),
                child: FilledButton.tonalIcon(
                  key: const Key('post-journal'),
                  onPressed: () => showDialog<bool>(
                    context: context,
                    builder: (_) => const PostJournalDialog(),
                  ).then((posted) {
                    if (posted == true) ref.invalidate(trialBalanceProvider);
                  }),
                  icon: const Icon(Icons.post_add_outlined, size: 18),
                  label: const Text('Post journal'),
                ),
              ),
            ],
          ),
          // Every posting the service writes balances, so two totals that
          // disagree mean a fault, not a finding — and it must not be read as
          // a figure.
          if (!report.balanced)
            Container(
              margin: EdgeInsetsDirectional.fromSTEB(context.pageGutter, 12, context.pageGutter, 0),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: cs.errorContainer,
                borderRadius: AppRadius.chip,
              ),
              child: Row(children: [
                Icon(Icons.warning_amber_outlined, color: cs.onErrorContainer),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'The ledger does not balance over this range: debits '
                    '${_money(report.totalDebit, home)} against credits '
                    '${_money(report.totalCredit, home)}. Every posting the '
                    'service writes balances, so this is a fault to investigate '
                    'before these figures are used.',
                    style: TextStyle(color: cs.onErrorContainer, fontSize: 13),
                  ),
                ),
              ]),
            ),
          const _SalesClearingCard(),
          const SizedBox(height: 12),
          if (report.rows.isEmpty)
            const Expanded(
                child: EmptyState(title: 'Nothing was posted in this range.'))
          else
            Expanded(
              child: SingleChildScrollView(
                padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
                child: Card(
                  child: DataTable(
                    headingRowColor:
                        WidgetStatePropertyAll(cs.surfaceContainerHigh),
                    columnSpacing: 24,
                    columns: const [
                      DataColumn(label: Text('Code')),
                      DataColumn(label: Text('Account')),
                      DataColumn(label: Text('Debit'), numeric: true),
                      DataColumn(label: Text('Credit'), numeric: true),
                      DataColumn(label: Text('Balance'), numeric: true),
                    ],
                    rows: [
                      ...report.rows.map((r) => DataRow(cells: [
                            DataCell(Text(r.nominalCode,
                                style:
                                    const TextStyle(fontFamily: 'monospace'))),
                            DataCell(Text(r.nominalName)),
                            DataCell(Text(_money(r.debit, home))),
                            DataCell(Text(_money(r.credit, home))),
                            DataCell(Text(
                              _money(r.balance, home),
                              style: TextStyle(
                                  color: r.balance < 0 ? cs.outline : null),
                            )),
                          ])),
                      DataRow(cells: [
                        const DataCell(Text('Total',
                            style: TextStyle(fontWeight: FontWeight.bold))),
                        const DataCell(Text('')),
                        DataCell(Text(_money(report.totalDebit, home),
                            style:
                                const TextStyle(fontWeight: FontWeight.bold))),
                        DataCell(Text(_money(report.totalCredit, home),
                            style:
                                const TextStyle(fontWeight: FontWeight.bold))),
                        DataCell(Text(
                            _money(report.totalDebit - report.totalCredit, home),
                            style:
                                const TextStyle(fontWeight: FontWeight.bold))),
                      ]),
                    ],
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

// ── Deferred revenue (17.11) ─────────────────────────────────────────────────

/// Loyalty points and gift cards on the ledger (FRS 102 section 23): the
/// estimates the deferral rests on, where the points and the gift card liability
/// stand, and the way to set the estimates, without which loyalty events wait.
class _DeferredRevenueReport extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(deferredRevenueProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading deferred revenue…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load deferred revenue.'),
        onRetry: () => ref.invalidate(deferredRevenueProvider),
      ),
      data: (d) {
        final est = d.estimates;
        String money(double v) => _money(v, est?.currency);
        return ListView(
          padding: const EdgeInsets.only(bottom: 24),
          children: [
            _ReportHeader(
              title: 'Deferred Revenue',
              subtitle: 'Loyalty points and gift cards, under FRS 102 section 23',
              onRefresh: () => ref.invalidate(deferredRevenueProvider),
            ),
            Padding(
              padding: EdgeInsetsDirectional.symmetric(horizontal: context.pageGutter),
              child: Align(
                alignment: Alignment.centerLeft,
                child: FilledButton.tonalIcon(
                  key: const Key('set-estimates'),
                  onPressed: () => showDialog<bool>(
                    context: context,
                    builder: (_) => _EstimatesDialog(current: est),
                  ).then((saved) {
                    if (saved == true) ref.invalidate(deferredRevenueProvider);
                  }),
                  icon: const Icon(Icons.tune, size: 18),
                  label: Text(est == null ? 'Set estimates' : 'Change estimates'),
                ),
              ),
            ),
            if (est == null)
              _Caveat(
                icon: Icons.hourglass_empty,
                text: d.eventsAwaitingEstimates > 0
                    ? '${d.eventsAwaitingEstimates} loyalty event(s) are waiting: nothing is deferred until a point\'s value and the breakage estimates are set.'
                    : 'No estimates are set, so points earned will wait unposted until a point\'s value and the breakage estimates are set.',
              )
            else
              _FigureCard(title: 'Estimates', rows: [
                ('A point is worth', AppFormat.money(est.pointValue, currencyCode: est.currency, maxDecimals: 4)),
                ('Points never spent', '${est.pointsBreakagePct}%'),
                ('Gift card value never claimed', '${est.giftCardBreakagePct}%'),
                ('Why', est.reason),
              ]),
            _FigureCard(title: 'Loyalty points', rows: [
              ('Points outstanding', d.pointsOutstanding.toStringAsFixed(2)),
              ('Deferred income (2330)', money(d.deferredIncome)),
              if (d.pointsUnmatched > 0)
                ('Spent before their earning arrived', d.pointsUnmatched.toStringAsFixed(2)),
            ]),
            _FigureCard(title: 'Gift cards', rows: [
              ('Loaded', money(d.giftCardsLoaded)),
              ('Spent', money(d.giftCardsRedeemed)),
              ('Breakage recognised (4031)', money(d.giftCardBreakage)),
              ('Liability left', money(d.giftCardLiability)),
            ]),
          ],
        );
      },
    );
  }
}

class _FigureCard extends StatelessWidget {
  final String title;
  final List<(String, String)> rows;
  const _FigureCard({required this.title, required this.rows});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsetsDirectional.fromSTEB(context.pageGutter, 12, context.pageGutter, 0),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Semantics(
                header: true,
                child: Text(title, style: Theme.of(context).textTheme.titleSmall),
              ),
              for (final (label, value) in rows)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(child: Text(label)),
                      const SizedBox(width: 12),
                      Flexible(
                        child: Text(value,
                            textAlign: TextAlign.end,
                            style: const TextStyle(fontFamily: 'monospace')),
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
}

/// The tenant accountant's estimates. A change applies from now on; the server
/// keeps every earlier set and posts the loyalty events that waited for these.
class _EstimatesDialog extends ConsumerStatefulWidget {
  final DeferredRevenueEstimates? current;
  const _EstimatesDialog({this.current});

  @override
  ConsumerState<_EstimatesDialog> createState() => _EstimatesDialogState();
}

class _EstimatesDialogState extends ConsumerState<_EstimatesDialog> {
  late final _value =
      TextEditingController(text: widget.current?.pointValue.toString() ?? '');
  late final _points = TextEditingController(
      text: widget.current?.pointsBreakagePct.toString() ?? '');
  late final _cards = TextEditingController(
      text: widget.current?.giftCardBreakagePct.toString() ?? '');
  final _reason = TextEditingController();
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    for (final c in [_value, _points, _cards, _reason]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    final value = double.tryParse(_value.text.trim());
    final points = double.tryParse(_points.text.trim());
    final cards = double.tryParse(_cards.text.trim());
    if (value == null || points == null || cards == null || _reason.text.trim().isEmpty) {
      setState(() => _error =
          'Enter a point value, both breakage estimates and the reason for them.');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.purchase}/nominal-ledger/deferred-revenue/settings',
        data: {
          'pointValue': value,
          'pointsBreakagePct': points,
          'giftCardBreakagePct': cards,
          'reason': _reason.text.trim(),
        },
      );
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'Could not save the estimates.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    TextField field(String key, TextEditingController c, String label, String help) =>
        TextField(
          key: Key(key),
          controller: c,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(labelText: label, helperText: help),
        );
    return AlertDialog(
      title: const Text('Deferred revenue estimates'),
      content: SizedBox(
        width: 380,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                Text(_error!, style: TextStyle(color: cs.error)),
                const SizedBox(height: 8),
              ],
              field('estimate-point-value', _value, 'Value of one point',
                  "What a point is worth to the shopper, in the tenant's currency"),
              const SizedBox(height: 8),
              field('estimate-points-breakage', _points, 'Points never spent (%)', '0 to 95'),
              const SizedBox(height: 8),
              field('estimate-gift-card-breakage', _cards,
                  'Gift card value never claimed (%)', '0 to 95'),
              const SizedBox(height: 8),
              TextField(
                key: const Key('estimate-reason'),
                controller: _reason,
                maxLines: 2,
                decoration: const InputDecoration(
                    labelText: 'Reason', helperText: 'What the estimates rest on'),
              ),
              const SizedBox(height: 12),
              Text('A change applies from now on; earlier estimates are kept.',
                  style: TextStyle(color: cs.outline, fontSize: 12)),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('save-estimates'),
          onPressed: _saving ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}
