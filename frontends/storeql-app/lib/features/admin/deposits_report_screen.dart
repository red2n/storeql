import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/util/status_labels.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';

// ---------------------------------------------------------------------------
// Container deposits (09.16).
//
// A deposit return scheme puts a deposit on a drink's container at the sale and
// pays it back when the empty comes back. order-svc keeps each deposit as its
// own line beside the item and each refund as its own record; this screen sums
// them over a period, by material — what the scheme administrator asks the
// business for, and, where the deposit is outside the scope of VAT, what the
// VAT return leaves out.
// ---------------------------------------------------------------------------

class DepositReportRow {
  final String material;
  final int chargedContainers;
  final num chargedAmount;
  final num chargedVat;
  final int refundedContainers;
  final num refundedAmount;
  const DepositReportRow({
    required this.material,
    required this.chargedContainers,
    required this.chargedAmount,
    required this.chargedVat,
    required this.refundedContainers,
    required this.refundedAmount,
  });
  factory DepositReportRow.fromJson(Map<String, dynamic> j) => DepositReportRow(
        material: j['material'] as String? ?? '',
        chargedContainers: (j['chargedContainers'] as num?)?.toInt() ?? 0,
        chargedAmount: j['chargedAmount'] as num? ?? 0,
        chargedVat: j['chargedVat'] as num? ?? 0,
        refundedContainers: (j['refundedContainers'] as num?)?.toInt() ?? 0,
        refundedAmount: j['refundedAmount'] as num? ?? 0,
      );
}

class DepositReport {
  final String from;
  final String to;
  final String currency;
  final int chargedContainers;
  final num chargedAmount;
  final num chargedVat;
  final int refundedContainers;
  final num refundedAmount;
  final num unredeemedAmount;
  final List<DepositReportRow> byMaterial;
  const DepositReport({
    required this.from,
    required this.to,
    required this.currency,
    required this.chargedContainers,
    required this.chargedAmount,
    required this.chargedVat,
    required this.refundedContainers,
    required this.refundedAmount,
    required this.unredeemedAmount,
    required this.byMaterial,
  });
  factory DepositReport.fromJson(Map<String, dynamic> j) => DepositReport(
        from: j['from'] as String? ?? '',
        to: j['to'] as String? ?? '',
        currency: j['currency'] as String? ?? '',
        chargedContainers: (j['chargedContainers'] as num?)?.toInt() ?? 0,
        chargedAmount: j['chargedAmount'] as num? ?? 0,
        chargedVat: j['chargedVat'] as num? ?? 0,
        refundedContainers: (j['refundedContainers'] as num?)?.toInt() ?? 0,
        refundedAmount: j['refundedAmount'] as num? ?? 0,
        unredeemedAmount: j['unredeemedAmount'] as num? ?? 0,
        byMaterial: [
          for (final r in (j['byMaterial'] as List?) ?? const [])
            DepositReportRow.fromJson(r as Map<String, dynamic>)
        ],
      );
}

/// The period the report covers: [from] inclusive to [to] exclusive, whole days
/// on the UTC clock the figures are stored on.
class DepositPeriod {
  final DateTime from;
  final DateTime to;
  const DepositPeriod(this.from, this.to);

  static DepositPeriod thisMonth() {
    final now = DateTime.now().toUtc();
    final start = DateTime.utc(now.year, now.month);
    return DepositPeriod(start, DateTime.utc(now.year, now.month + 1));
  }
}

final depositPeriodProvider =
    NotifierProvider<DepositPeriodNotifier, DepositPeriod>(DepositPeriodNotifier.new);

class DepositPeriodNotifier extends Notifier<DepositPeriod> {
  @override
  DepositPeriod build() => DepositPeriod.thisMonth();
  void set(DepositPeriod p) => state = p;
}

final depositReportProvider =
    FutureProvider.autoDispose<DepositReport>((ref) async {
  final p = ref.watch(depositPeriodProvider);
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.order}/admin/reports/deposits', queryParameters: {
    'from': p.from.toIso8601String(),
    'to': p.to.toIso8601String(),
  });
  return DepositReport.fromJson(resp.data['data'] as Map<String, dynamic>);
});

class DepositsReportScreen extends ConsumerWidget {
  const DepositsReportScreen({super.key});

  /// One of the period's whole UTC days, as a person reads it (`1 Sept 2026`).
  /// It goes to [AppFormat.date] as a bare date, so no clock west of UTC
  /// turns the first of the month into the last day of the one before.
  static String _day(DateTime d) => AppFormat.date(
      '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}');

  Future<void> _pickPeriod(BuildContext context, WidgetRef ref) async {
    final current = ref.read(depositPeriodProvider);
    final range = await showDateRangePicker(
      context: context,
      firstDate: DateTime(2020),
      lastDate: DateTime.now().add(const Duration(days: 1)),
      initialDateRange: DateTimeRange(
          start: current.from, end: current.to.subtract(const Duration(days: 1))),
    );
    if (range == null) return;
    ref.read(depositPeriodProvider.notifier).set(DepositPeriod(
          DateTime.utc(range.start.year, range.start.month, range.start.day),
          DateTime.utc(range.end.year, range.end.month, range.end.day + 1),
        ));
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final period = ref.watch(depositPeriodProvider);
    final report = ref.watch(depositReportProvider);
    return ListView(
      // 16 on a phone, 24 from tablet width.
      padding: context.pagePadding,
      children: [
        const PageHeader(
          title: 'Container deposits',
          subtitle: 'What the deposit return scheme put on drinks containers sold, less '
              'what the till paid back on empties. The difference is what the '
              'scheme holds unredeemed. Sales cancelled or voided do not count.',
          padding: EdgeInsetsDirectional.only(bottom: AppSpacing.md),
        ),
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: OutlinedButton.icon(
            key: const Key('deposits-period'),
            onPressed: () => _pickPeriod(context, ref),
            icon: const Icon(Icons.date_range),
            label: Text(
                '${_day(period.from)} to ${_day(period.to.subtract(const Duration(days: 1)))}'),
          ),
        ),
        const SizedBox(height: AppSpacing.lg),
        report.when(
          loading: () => const LoadingView(label: 'Adding up deposits…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e,
                fallback: 'Could not load the deposit report.'),
            onRetry: () => ref.invalidate(depositReportProvider),
          ),
          data: (r) => _ReportBody(report: r),
        ),
      ],
    );
  }
}

class _ReportBody extends StatelessWidget {
  const _ReportBody({required this.report});
  final DepositReport report;

  @override
  Widget build(BuildContext context) {
    final r = report;
    String money(num v) => AppFormat.money(v, currencyCode: r.currency);
    // Counts grouped (`2,598`) in the locale AppFormat writes money in.
    const count = AppFormat.count;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _FigureGrid(
          children: [
            _Figure(
                key: const Key('deposits-charged'),
                label: 'Charged',
                value: money(r.chargedAmount),
                note: '${count(r.chargedContainers)} containers'),
            _Figure(
                key: const Key('deposits-refunded'),
                label: 'Refunded',
                value: money(r.refundedAmount),
                note: '${count(r.refundedContainers)} containers'),
            _Figure(
                key: const Key('deposits-unredeemed'),
                label: 'Unredeemed',
                value: money(r.unredeemedAmount),
                note: 'held by the scheme'),
            if (r.chargedVat > 0)
              _Figure(
                  key: const Key('deposits-vat'),
                  label: 'VAT inside deposits',
                  value: money(r.chargedVat),
                  note: 'where the scheme taxes the deposit'),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        if (r.byMaterial.isEmpty)
          const EmptyState(
            icon: Icons.recycling_outlined,
            title: 'No deposit was charged or refunded in this period.',
          )
        else
          Card(
            child: Column(
              children: [
                for (var i = 0; i < r.byMaterial.length; i++) ...[
                  if (i > 0) const Divider(height: 1),
                  _MaterialRow(row: r.byMaterial[i], money: money, count: count),
                ],
              ],
            ),
          ),
      ],
    );
  }
}

/// One material: what was sold and returned, and what that leaves unredeemed.
class _MaterialRow extends StatelessWidget {
  const _MaterialRow({required this.row, required this.money, required this.count});

  /// Narrower than this, the sums and the answer beside them squeeze each
  /// other, so the answer goes under them.
  static const double _stackBelow = 420;

  final DepositReportRow row;
  final String Function(num) money;
  final String Function(num n, {String? locale}) count;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final m = row;
    // What the scheme still holds for this material. More paid back than
    // charged in the period (empties bought earlier) is said as that.
    final left = m.chargedAmount - m.refundedAmount;
    final details = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(_materialName(m.material), style: theme.textTheme.titleMedium),
        const SizedBox(height: AppSpacing.xs),
        Text(
          '${count(m.chargedContainers)} sold · ${count(m.refundedContainers)} returned',
          style: theme.textTheme.bodyMedium?.copyWith(color: cs.onSurfaceVariant),
        ),
        Text(
          'Charged ${money(m.chargedAmount)} · refunded ${money(m.refundedAmount)}',
          style: theme.textTheme.bodyMedium?.copyWith(color: cs.onSurfaceVariant),
        ),
      ],
    );
    final value = Text(money(left.abs()), style: theme.textTheme.titleMedium);
    final caption = Text(
      left >= 0 ? 'unredeemed' : 'more refunded than charged',
      style: theme.textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant),
    );
    return Padding(
      key: Key('deposits-material-${m.material}'),
      padding: const EdgeInsets.all(AppSpacing.lg),
      child: LayoutBuilder(builder: (context, constraints) {
        // Narrow, or with large text, the answer goes under the sums rather
        // than squeezing them into a sliver beside it.
        final stacked = constraints.maxWidth < _stackBelow ||
            MediaQuery.textScalerOf(context).scale(16) > 16 * 1.3;
        if (stacked) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              details,
              const SizedBox(height: AppSpacing.sm),
              Wrap(
                spacing: AppSpacing.xs,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [value, caption],
              ),
            ],
          );
        }
        return Row(
          children: [
            Expanded(child: details),
            const SizedBox(width: AppSpacing.lg),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [value, caption],
            ),
          ],
        );
      }),
    );
  }
}

String _materialName(String code) => switch (code) {
      'PET' => 'PET plastic',
      'ALUMINIUM' => 'Aluminium',
      'STEEL' => 'Steel',
      'GLASS' => 'Glass',
      _ => humanizeCode(code),
    };

/// The report's figures on a grid that fits the width: two to a row on a
/// phone, all in one row stretched across a wider page (two to a row again
/// when one row would leave each too narrow to read). A figure left alone on
/// the last row takes the whole row. Figures in a row share its height.
class _FigureGrid extends StatelessWidget {
  const _FigureGrid({required this.children});
  final List<Widget> children;

  /// Narrower than this, a figure's sum no longer fits its card comfortably.
  static const double _minWidth = 160;

  @override
  Widget build(BuildContext context) {
    const gap = AppSpacing.md;
    return LayoutBuilder(builder: (context, constraints) {
      final width = constraints.maxWidth;
      final n = children.length;
      final compact = AppBreakpoints.classOf(width) == WindowClass.compact;
      // With large text a phone gives each figure the whole row, as the
      // material rows below do: two to a row, a sum breaks mid-number.
      final largeText = MediaQuery.textScalerOf(context).scale(16) > 16 * 1.3;
      var perRow = compact && largeText ? 1 : 2;
      if (!compact && (width - gap * (n - 1)) / n >= _minWidth) {
        perRow = n;
      }
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var start = 0; start < n; start += perRow) ...[
            if (start > 0) const SizedBox(height: gap),
            IntrinsicHeight(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (var i = start; i < start + perRow && i < n; i++) ...[
                    if (i > start) const SizedBox(width: gap),
                    Expanded(child: children[i]),
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

class _Figure extends StatelessWidget {
  const _Figure(
      {super.key, required this.label, required this.value, required this.note});
  final String label;
  final String value;
  final String note;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.md),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(label, style: theme.textTheme.labelMedium),
            const SizedBox(height: AppSpacing.xs),
            Text(value, style: theme.textTheme.titleLarge),
            Text(note,
                style: theme.textTheme.bodySmall
                    ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          ],
        ),
      ),
    );
  }
}
