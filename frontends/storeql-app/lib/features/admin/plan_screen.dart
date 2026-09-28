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
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/scrollable_table.dart';

// ---------------------------------------------------------------------------
// The plan this business is on (21.8).
//
// What it is paying for, what that allows, and how much of each allowance it
// has used — because a limit is about to refuse somebody mid-shift, and an
// owner should see it coming rather than meet it as an error.
//
// A count the owning service could not give is shown as unknown rather than
// guessed at; a business on no plan is unrestricted, and says so.
// ---------------------------------------------------------------------------

class PlanAllowance {
  final String key;
  final String label;
  final int? limitValue;
  final int? used;
  final bool over;

  const PlanAllowance({
    required this.key,
    required this.label,
    required this.limitValue,
    required this.used,
    required this.over,
  });

  factory PlanAllowance.fromJson(Map<String, dynamic> j) => PlanAllowance(
        key: j['key'] as String? ?? '',
        label: j['label'] as String? ?? '',
        limitValue: (j['limitValue'] as num?)?.toInt(),
        used: (j['used'] as num?)?.toInt(),
        over: j['over'] == true,
      );

  bool get unlimited => limitValue == null;
  bool get known => used != null;

  /// How full the allowance is, for the bar; null when there is nothing to draw.
  double? get fraction {
    if (unlimited || !known || limitValue == 0) return null;
    final f = used! / limitValue!;
    return f > 1 ? 1 : f;
  }

  String get says {
    if (unlimited) return known ? '$used · no limit' : 'no limit';
    if (!known) return 'up to $limitValue';
    return '$used of $limitValue';
  }
}

class TenantPlan {
  final String? code;
  final String? name;
  final String? billingInterval;
  final List<String> includes;
  final List<PlanAllowance> allowances;
  final String? note;

  const TenantPlan({
    required this.code,
    required this.name,
    required this.billingInterval,
    required this.includes,
    required this.allowances,
    required this.note,
  });

  factory TenantPlan.fromJson(Map<String, dynamic> j) {
    final plan = j['plan'] == null ? null : Map<String, dynamic>.from(j['plan'] as Map);
    return TenantPlan(
      code: plan?['code'] as String?,
      name: plan?['name'] as String?,
      billingInterval: plan?['billingInterval'] as String?,
      includes: [
        for (final g in plan?['includes'] as List<dynamic>? ?? const [])
          '${(g as Map)['label'] ?? g['key']}: ${g['enabled'] == null ? (g['limitValue'] ?? 'unlimited') : (g['enabled'] == true ? 'included' : 'not included')}',
      ],
      allowances: [
        for (final u in j['usage'] as List<dynamic>? ?? const [])
          PlanAllowance.fromJson(Map<String, dynamic>.from(u as Map)),
      ],
      note: j['note'] as String?,
    );
  }

  bool get onAPlan => code != null;
}

// ── metered use (21.10) ─────────────────────────────────────────────────────

/// One meter this billing period, against what the plan includes.
class UsageMeter {
  final String meter;
  final String label;
  final String unit;
  final int used;
  final int? included;
  final bool hard;
  final int over;
  final String currency;
  final num? unitAmount;
  final num estimate;

  const UsageMeter({
    required this.meter,
    required this.label,
    required this.unit,
    required this.used,
    required this.included,
    required this.hard,
    required this.over,
    required this.currency,
    required this.unitAmount,
    required this.estimate,
  });

  factory UsageMeter.fromJson(Map<String, dynamic> j) => UsageMeter(
        meter: j['meter'] as String? ?? '',
        label: j['label'] as String? ?? '',
        unit: j['unit'] as String? ?? '',
        used: (j['used'] as num?)?.toInt() ?? 0,
        included: (j['included'] as num?)?.toInt(),
        hard: j['hard'] == true,
        over: (j['over'] as num?)?.toInt() ?? 0,
        currency: j['currency'] as String? ?? '',
        unitAmount: j['unitAmount'] as num?,
        estimate: j['estimate'] as num? ?? 0,
      );

  double? get fraction {
    if (included == null || included == 0) return null;
    final f = used / included!;
    return f > 1 ? 1 : f;
  }

  String get says => included == null ? '$used · no limit' : '$used of $included';
}

/// One closed period of one meter, as it was billed.
class UsagePeriodRow {
  final String meter;
  final String periodStart;
  final String periodEnd;
  final int used;
  final int? included;
  final int overage;
  final String currency;
  final num amount;
  final String? notCharged;

  const UsagePeriodRow({
    required this.meter,
    required this.periodStart,
    required this.periodEnd,
    required this.used,
    required this.included,
    required this.overage,
    required this.currency,
    required this.amount,
    required this.notCharged,
  });

  factory UsagePeriodRow.fromJson(Map<String, dynamic> j) => UsagePeriodRow(
        meter: j['meter'] as String? ?? '',
        periodStart: j['periodStart'] as String? ?? '',
        periodEnd: j['periodEnd'] as String? ?? '',
        used: (j['used'] as num?)?.toInt() ?? 0,
        included: (j['included'] as num?)?.toInt(),
        overage: (j['overage'] as num?)?.toInt() ?? 0,
        currency: j['currency'] as String? ?? '',
        amount: j['amount'] as num? ?? 0,
        notCharged: j['notCharged'] as String?,
      );

  String get charged => switch (notCharged) {
        'TRIAL' => 'not charged — trial',
        'NOT_PRICED' => 'not charged',
        _ => amount == 0 ? '—' : _money(amount, currency),
      };

  /// The period's last day (ISO): its end is the day the next begins.
  String get lastDay => _dayBefore(periodEnd);

  /// What went beyond the plan, and what it came to: `100 · £5.00`, or a dash for nothing.
  String get beyond => overage == 0 ? '—' : '$overage · $charged';
}

class TenantUsage {
  final String periodStart;
  final String periodEnd;
  final bool trial;
  final List<UsageMeter> meters;
  final List<({String meter, int threshold})> alerts;
  final List<UsagePeriodRow> history;

  const TenantUsage({
    required this.periodStart,
    required this.periodEnd,
    required this.trial,
    required this.meters,
    required this.alerts,
    required this.history,
  });

  factory TenantUsage.fromJson(Map<String, dynamic> j) => TenantUsage(
        periodStart: j['periodStart'] as String? ?? '',
        periodEnd: j['periodEnd'] as String? ?? '',
        trial: j['trial'] == true,
        meters: [
          for (final m in j['meters'] as List<dynamic>? ?? const [])
            UsageMeter.fromJson(Map<String, dynamic>.from(m as Map)),
        ],
        alerts: [
          for (final a in j['alerts'] as List<dynamic>? ?? const [])
            (meter: (a as Map)['meter'] as String? ?? '', threshold: (a['threshold'] as num?)?.toInt() ?? 0),
        ],
        history: [
          for (final h in j['history'] as List<dynamic>? ?? const [])
            UsagePeriodRow.fromJson(Map<String, dynamic>.from(h as Map)),
        ],
      );

  /// The last day of the period (ISO): its end is the day the next begins.
  String get lastDay => _dayBefore(periodEnd);
}

/// The calendar day before an ISO date (`2026-10-22` → `2026-10-21`), counted in days rather than
/// hours, so a clock change inside the day never lands it two days back. The text unchanged when
/// it is not a date.
String _dayBefore(String iso) {
  final end = DateTime.tryParse(iso);
  if (end == null) return iso;
  final day = DateTime(end.year, end.month, end.day - 1);
  String two(int n) => n.toString().padLeft(2, '0');
  return '${day.year.toString().padLeft(4, '0')}-${two(day.month)}-${two(day.day)}';
}

/// A period as people read it: `22 Sept 2026 to 21 Oct 2026`.
String _periodWords(String start, String lastDay) =>
    '${AppFormat.date(start)} to ${AppFormat.date(lastDay)}';

/// Money through [AppFormat.money] — `£11.70`, `¥3,702`. A plan prices metered use to four places
/// (0.035 a text) and bills its lines to four, and rounding 0.035 to £0.04 would overstate it by
/// 14%; such an amount keeps the places it has (`£0.035`), up to four.
String _money(num n, String currency) => AppFormat.money(n, currencyCode: currency, maxDecimals: 4);

final tenantUsageProvider = FutureProvider.autoDispose<TenantUsage>((ref) async {
  final resp = await ref.watch(apiClientProvider).dio.get('/${ApiConstants.tenant}/admin/tenant/usage');
  return TenantUsage.fromJson(Map<String, dynamic>.from(resp.data['data'] as Map));
});

final tenantPlanProvider = FutureProvider.autoDispose<TenantPlan>((ref) async {
  final resp = await ref.watch(apiClientProvider).dio.get('/${ApiConstants.tenant}/admin/tenant/plan');
  return TenantPlan.fromJson(Map<String, dynamic>.from(resp.data['data'] as Map));
});

class PlanScreen extends ConsumerWidget {
  const PlanScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final plan = ref.watch(tenantPlanProvider);
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final usage = ref.watch(tenantUsageProvider);
    void refresh() {
      ref.invalidate(tenantPlanProvider);
      ref.invalidate(tenantUsageProvider);
    }

    final gutter = context.pageGutter;
    return ContentBounds(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          PageHeader(
            title: 'Plan',
            subtitle: 'What this business is on, and how much of it is in use.',
            actions: [
              IconButton(icon: const Icon(Icons.refresh), tooltip: 'Refresh', onPressed: refresh),
            ],
          ),
          Expanded(
            child: plan.when(
              loading: () => const LoadingView(label: 'Loading the plan…'),
              error: (e, _) => ErrorView(message: friendlyError(e, fallback: 'Could not load the plan.'), onRetry: refresh),
              data: (p) => ListView(
                // The same inset as the title, so the cards and rows line up under it.
                padding: EdgeInsetsDirectional.fromSTEB(gutter, 0, gutter, gutter),
                children: [
                  Card(
                    child: Padding(
                      padding: AppSpacing.cardPadding,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          if (!p.onAPlan) ...[
                            Text('No plan', key: const Key('plan-none'), style: text.titleMedium),
                            const SizedBox(height: AppSpacing.xs),
                            Text(
                              p.note ?? 'This business is on no plan, so nothing is limited.',
                              style: text.bodyMedium,
                            ),
                          ] else ...[
                            // The name alone: the code is the platform's handle for the plan.
                            Text(
                              (p.name?.trim().isNotEmpty ?? false) ? p.name!.trim() : humanizeCode(p.code),
                              key: const Key('plan-name'),
                              style: text.titleMedium,
                            ),
                            const SizedBox(height: 2),
                            Text(
                              'billed ${p.billingInterval == 'YEAR' ? 'every year' : 'every month'}',
                              style: text.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                            ),
                            if (p.includes.isNotEmpty) ...[
                              const SizedBox(height: AppSpacing.md),
                              Wrap(
                                spacing: AppSpacing.sm,
                                runSpacing: AppSpacing.sm,
                                children: [for (final i in p.includes) Chip(label: Text(i))],
                              ),
                            ],
                          ],
                        ],
                      ),
                    ),
                  ),
                  if (p.allowances.isNotEmpty) ...[
                    const SizedBox(height: AppSpacing.lg),
                    Text('What it allows', style: text.titleSmall),
                    const SizedBox(height: AppSpacing.sm),
                    for (final a in p.allowances) _AllowanceRow(allowance: a),
                  ],
                  // What it does, as against what it has: counted each billing period, and billed
                  // beyond the plan when the period ends. A reading that fails leaves the plan shown.
                  ...usage.maybeWhen(
                    data: (u) => [const SizedBox(height: AppSpacing.lg), _UsageSection(usage: u)],
                    orElse: () => const <Widget>[],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _AllowanceRow extends StatelessWidget {
  final PlanAllowance allowance;
  const _AllowanceRow({required this.allowance});

  @override
  Widget build(BuildContext context) {
    final a = allowance;
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final full = a.fraction != null && a.fraction! >= 1;
    return Padding(
      key: Key('allowance-${a.key}'),
      padding: const EdgeInsetsDirectional.only(bottom: AppSpacing.md),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(child: Text(a.label, style: text.bodyMedium)),
              Text(
                a.says,
                style: text.bodyMedium?.copyWith(
                  color: a.over || full ? cs.error : cs.onSurfaceVariant,
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
            ],
          ),
          if (a.fraction != null) ...[
            const SizedBox(height: AppSpacing.xs),
            LinearProgressIndicator(
              value: a.fraction,
              color: full ? cs.error : cs.primary,
              backgroundColor: cs.surfaceContainerHighest,
            ),
          ],
          if (!a.known && !a.unlimited)
            Text(
              'counted by the service that holds them',
              style: text.bodySmall?.copyWith(color: cs.onSurfaceVariant),
            ),
        ],
      ),
    );
  }
}

class _UsageSection extends StatelessWidget {
  final TenantUsage usage;
  const _UsageSection({required this.usage});

  @override
  Widget build(BuildContext context) {
    final u = usage;
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final full = {for (final a in u.alerts) if (a.threshold >= 100) a.meter};
    final near = {for (final a in u.alerts) if (a.threshold < 100) a.meter}..removeAll(full);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('This billing period', style: text.titleSmall),
        Text(
          '${_periodWords(u.periodStart, u.lastDay)}${u.trial ? ' · a trial: nothing used is charged' : ''}',
          key: const Key('usage-period'),
          style: text.bodySmall?.copyWith(color: cs.onSurfaceVariant),
        ),
        const SizedBox(height: AppSpacing.sm),
        for (final m in u.meters)
          Padding(
            key: Key('usage-meter-${m.meter}'),
            padding: const EdgeInsetsDirectional.only(bottom: AppSpacing.md),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(child: Text(m.label, style: text.bodyMedium)),
                    Text(
                      m.says,
                      style: text.bodyMedium?.copyWith(
                        color: m.over > 0 ? cs.error : cs.onSurfaceVariant,
                        fontFeatures: const [FontFeature.tabularFigures()],
                      ),
                    ),
                  ],
                ),
                if (m.fraction != null) ...[
                  const SizedBox(height: AppSpacing.xs),
                  LinearProgressIndicator(
                    value: m.fraction,
                    color: m.fraction! >= 1 ? cs.error : cs.primary,
                    backgroundColor: cs.surfaceContainerHighest,
                  ),
                ],
                if (_note(m, u.trial, full.contains(m.meter), near.contains(m.meter)) case final note?)
                  Padding(
                    padding: const EdgeInsetsDirectional.only(top: AppSpacing.xs),
                    child: Text(
                      note,
                      key: Key('usage-note-${m.meter}'),
                      style: text.bodySmall?.copyWith(color: m.over > 0 || full.contains(m.meter) ? cs.error : cs.onSurfaceVariant),
                    ),
                  ),
              ],
            ),
          ),
        if (u.history.isNotEmpty) ...[
          const SizedBox(height: AppSpacing.sm),
          Text('Earlier periods', style: text.titleSmall),
          const SizedBox(height: AppSpacing.xs),
          // A table from tablet width; below 600px each period is a few lines of its own, so the
          // allowance and what went beyond it never start off-screen in a sideways scroll.
          LayoutBuilder(
            builder: (context, constraints) =>
                AppBreakpoints.classOf(constraints.maxWidth) == WindowClass.compact
                    ? Column(
                        key: const Key('usage-history'),
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          for (final h in u.history) _HistoryRow(row: h, label: _labelOf(u, h.meter)),
                        ],
                      )
                    : ScrollableTable(
                        child: DataTable(
                          key: const Key('usage-history'),
                          columns: const [
                            DataColumn(label: Text('Period')),
                            DataColumn(label: Text('What')),
                            DataColumn(label: Text('Used'), numeric: true),
                            DataColumn(label: Text('Included'), numeric: true),
                            DataColumn(label: Text('Beyond the plan')),
                          ],
                          rows: [
                            for (final h in u.history)
                              DataRow(cells: [
                                DataCell(Text(_periodWords(h.periodStart, h.lastDay))),
                                DataCell(Text(_labelOf(u, h.meter))),
                                DataCell(Text('${h.used}')),
                                DataCell(Text(h.included?.toString() ?? 'no limit')),
                                DataCell(Text(h.beyond)),
                              ]),
                          ],
                        ),
                      ),
          ),
        ],
      ],
    );
  }

  static String _labelOf(TenantUsage u, String meter) {
    for (final m in u.meters) {
      if (m.meter == meter) return m.label;
    }
    return meter;
  }

  /// What the business should know about one meter now, or nothing.
  static String? _note(UsageMeter m, bool trial, bool full, bool near) {
    if (m.included == null) return null;
    if (m.hard && full) {
      // Refusing marketing is not the whole story: what a customer must be sent still goes, and
      // each one beyond the allowance is charged when the plan prices it.
      final beyond = m.over > 0 && m.unitAmount != null && !trial
          ? ' Messages a customer must get still go, and each beyond ${m.included} is charged: ${_money(m.estimate, m.currency)} so far.'
          : ' Messages a customer must get still go.';
      return 'All ${m.included} used: marketing texts are refused until the next period.$beyond';
    }
    if (m.over > 0) {
      if (trial) return '${m.over} beyond the plan — free while the trial runs.';
      if (m.unitAmount == null) return '${m.over} beyond the plan, not charged.';
      return '${m.over} beyond the plan: ${_money(m.estimate, m.currency)} so far, on the next invoice.';
    }
    if (near) {
      return m.hard
          ? 'Nearly all used: marketing texts stop at ${m.included}.'
          : 'Nearly all used: each one beyond ${m.included}${m.unitAmount == null ? '' : ' costs ${_money(m.unitAmount!, m.currency)}'}.';
    }
    return null;
  }
}

/// One earlier period on a phone: when, what, how much of the allowance, and what went beyond it
/// — each on a line of its own, so nothing needs a sideways scroll.
class _HistoryRow extends StatelessWidget {
  final UsagePeriodRow row;
  final String label;
  const _HistoryRow({required this.row, required this.label});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final h = row;
    return Container(
      padding: const EdgeInsetsDirectional.symmetric(vertical: AppSpacing.sm),
      decoration: BoxDecoration(border: Border(bottom: BorderSide(color: cs.outlineVariant))),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(_periodWords(h.periodStart, h.lastDay), style: text.titleSmall),
          const SizedBox(height: 2),
          Text(
            '$label · ${h.included == null ? '${h.used} used · no limit' : '${h.used} used of ${h.included}'}',
            style: text.bodyMedium?.copyWith(color: cs.onSurfaceVariant),
          ),
          Text(
            h.overage == 0 ? 'Nothing beyond the plan' : 'Beyond the plan: ${h.beyond}',
            style: text.bodyMedium,
          ),
        ],
      ),
    );
  }
}
