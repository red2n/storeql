import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../core/constants.dart';
import '../../core/reference/iso_reference.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../pos/pos_age_check.dart' show AgeVerificationDialog, ageIdTypes, ageRefusalReasons;
import 'providers/admin_providers.dart';

// The age-check register: what a licensing officer asks to see. Every check
// the till made, by store and period, the refusals and their reasons, and the
// counts. Read-only by design — the record is append-only on the server and
// nothing here can change it.

class AgeCheckRecord {
  final String id;
  final String storeId;
  final String? cashierId;
  final String variantId;
  final String category;
  final int minimumAge;
  final String country;
  final bool storePolicy;

  /// The birth-date cut-off the check was judged against, yyyy-mm-dd, or null.
  final String? bornBefore;
  final bool bornBeforeStorePolicy;
  final String outcome;
  final String? reason;
  final String? idType;
  final DateTime? checkedAt;

  const AgeCheckRecord({
    required this.id,
    required this.storeId,
    this.cashierId,
    required this.variantId,
    required this.category,
    required this.minimumAge,
    required this.country,
    required this.storePolicy,
    this.bornBefore,
    this.bornBeforeStorePolicy = false,
    required this.outcome,
    this.reason,
    this.idType,
    this.checkedAt,
  });

  bool get refused => outcome == 'REFUSED';

  factory AgeCheckRecord.fromJson(Map<String, dynamic> j) => AgeCheckRecord(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        cashierId: j['cashierId'] as String?,
        variantId: j['variantId'] as String? ?? '',
        category: j['category'] as String? ?? '',
        minimumAge: (j['minimumAge'] as num?)?.toInt() ?? 0,
        country: j['country'] as String? ?? '',
        storePolicy: j['storePolicy'] as bool? ?? false,
        bornBefore: j['bornBefore'] as String?,
        bornBeforeStorePolicy: j['bornBeforeStorePolicy'] as bool? ?? false,
        outcome: j['outcome'] as String? ?? '',
        reason: j['reason'] as String?,
        idType: j['idType'] as String?,
        checkedAt: DateTime.tryParse(j['checkedAt'] as String? ?? ''),
      );
}

class AgeCheckSummary {
  final int total;
  final int passed;
  final int refused;
  final Map<String, int> refusedByReason;
  final Map<String, int> byCategory;

  const AgeCheckSummary({
    required this.total,
    required this.passed,
    required this.refused,
    required this.refusedByReason,
    required this.byCategory,
  });

  factory AgeCheckSummary.fromJson(Map<String, dynamic> j) => AgeCheckSummary(
        total: (j['total'] as num?)?.toInt() ?? 0,
        passed: (j['passed'] as num?)?.toInt() ?? 0,
        refused: (j['refused'] as num?)?.toInt() ?? 0,
        refusedByReason: {
          for (final e in ((j['refusedByReason'] as Map?) ?? {}).entries)
            e.key as String: (e.value as num).toInt(),
        },
        byCategory: {
          for (final e in ((j['byCategory'] as Map?) ?? {}).entries)
            e.key as String: (e.value as num).toInt(),
        },
      );
}

/// The register's filters: one store or all, a period, and an outcome.
class AgeCheckFilter {
  final String? storeId;
  final DateTime from;
  final DateTime to;
  final String? outcome;

  const AgeCheckFilter({
    this.storeId,
    required this.from,
    required this.to,
    this.outcome,
  });

  AgeCheckFilter copyWith({
    Object? storeId = _unset,
    DateTime? from,
    DateTime? to,
    Object? outcome = _unset,
  }) =>
      AgeCheckFilter(
        storeId: storeId == _unset ? this.storeId : storeId as String?,
        from: from ?? this.from,
        to: to ?? this.to,
        outcome: outcome == _unset ? this.outcome : outcome as String?,
      );

  static const _unset = Object();

  Map<String, dynamic> get query => {
        if (storeId != null) 'store': storeId,
        'from': from.toUtc().toIso8601String(),
        // The API's upper bound is exclusive; the picker's is a day, inclusive.
        'to': to.add(const Duration(days: 1)).toUtc().toIso8601String(),
        if (outcome != null) 'outcome': outcome,
      };
}

final ageCheckFilterProvider = StateProvider<AgeCheckFilter>((ref) {
  final today = DateTime.now();
  final day = DateTime(today.year, today.month, today.day);
  return AgeCheckFilter(from: day.subtract(const Duration(days: 29)), to: day);
});

final ageCheckSummaryProvider =
    FutureProvider.autoDispose<AgeCheckSummary>((ref) async {
  final filter = ref.watch(ageCheckFilterProvider);
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.order}/admin/pos/age-checks/summary',
        queryParameters: {
          if (filter.storeId != null) 'store': filter.storeId,
          'from': filter.from.toUtc().toIso8601String(),
          'to': filter.to.add(const Duration(days: 1)).toUtc().toIso8601String(),
        },
      );
  return AgeCheckSummary.fromJson(resp.data['data'] as Map<String, dynamic>);
});

final ageCheckRegisterProvider =
    FutureProvider.autoDispose<List<AgeCheckRecord>>((ref) async {
  final filter = ref.watch(ageCheckFilterProvider);
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.order}/admin/pos/age-checks',
        queryParameters: {...filter.query, 'limit': 100},
      );
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => AgeCheckRecord.fromJson(e as Map<String, dynamic>))
      .toList();
});

class AgeChecksScreen extends ConsumerWidget {
  const AgeChecksScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final stores = ref.watch(storesProvider);
    final filter = ref.watch(ageCheckFilterProvider);
    final summary = ref.watch(ageCheckSummaryProvider);
    final register = ref.watch(ageCheckRegisterProvider);
    final theme = Theme.of(context);
    // A day of the picker, in the app's own date format.
    String day(DateTime d) => AppFormat.date(d.toIso8601String());

    Future<void> pickRange() async {
      final picked = await showDateRangePicker(
        context: context,
        firstDate: DateTime(2020),
        lastDate: DateTime.now().add(const Duration(days: 1)),
        initialDateRange: DateTimeRange(start: filter.from, end: filter.to),
      );
      if (picked != null) {
        ref.read(ageCheckFilterProvider.notifier).state = filter.copyWith(
          from: DateTime(picked.start.year, picked.start.month, picked.start.day),
          to: DateTime(picked.end.year, picked.end.month, picked.end.day),
        );
      }
    }

    return ListView(
      // 16 on a phone, 24 from tablet width up.
      padding: context.pagePadding,
      children: [
        const PageHeader(
          title: 'Age checks',
          subtitle: 'Every age check the till made, pass or refusal — the record '
              'that shows the shop was checking. Nothing here can be edited.',
          padding: EdgeInsetsDirectional.only(bottom: AppSpacing.lg),
        ),
        Wrap(
          spacing: AppSpacing.md,
          runSpacing: AppSpacing.sm,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            SizedBox(
              width: 260,
              child: DropdownButtonFormField<String?>(
                key: const Key('age-checks-store'),
                // The chosen store ellipsizes rather than overflowing the
                // field with a long name or large text.
                isExpanded: true,
                initialValue: filter.storeId,
                decoration: const InputDecoration(labelText: 'Store'),
                items: [
                  const DropdownMenuItem<String?>(
                      value: null,
                      child: Text('All stores', overflow: TextOverflow.ellipsis)),
                  for (final s in stores.value ?? const [])
                    DropdownMenuItem<String?>(
                        value: s.id,
                        child: Text(s.name, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (v) => ref.read(ageCheckFilterProvider.notifier).state =
                    filter.copyWith(storeId: v),
              ),
            ),
            OutlinedButton.icon(
              onPressed: pickRange,
              icon: const Icon(Icons.date_range),
              label: Text('${day(filter.from)} – ${day(filter.to)}'),
            ),
            SegmentedButton<String?>(
              segments: const [
                ButtonSegment(value: null, label: Text('All')),
                ButtonSegment(value: 'REFUSED', label: Text('Refusals')),
                ButtonSegment(value: 'PASSED', label: Text('Passed')),
              ],
              selected: {filter.outcome},
              onSelectionChanged: (s) =>
                  ref.read(ageCheckFilterProvider.notifier).state =
                      filter.copyWith(outcome: s.first),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        summary.when(
          loading: () => const LoadingView(label: 'Counting…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the counts.'),
            onRetry: () => ref.invalidate(ageCheckSummaryProvider),
          ),
          data: (s) => _SummaryCards(summary: s),
        ),
        const SizedBox(height: AppSpacing.lg),
        Text('Register', style: theme.textTheme.titleLarge),
        const SizedBox(height: AppSpacing.sm),
        register.when(
          loading: () => const LoadingView(label: 'Loading the register…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the register.'),
            onRetry: () => ref.invalidate(ageCheckRegisterProvider),
          ),
          data: (rows) => rows.isEmpty
              ? const EmptyState(
                  icon: Icons.verified_user_outlined,
                  title: 'No checks in this period',
                  message: 'A shop that sells restricted items and has never '
                      'refused anyone has not been checking.',
                )
              : Card(
                  child: Column(
                    children: [
                      for (final r in rows)
                        // The outcome is said once, in the subtitle's words;
                        // the icon only helps the eye scan the list — green
                        // for a sale that went ahead, red for a refusal.
                        ListTile(
                          leading: Icon(
                            r.refused
                                ? Icons.block
                                : Icons.check_circle_outline,
                            color: r.refused
                                ? theme.colorScheme.error
                                : context.status.success,
                          ),
                          title: Text(
                            '${AgeVerificationDialog.categoryLabel(r.category)} · '
                            '${r.minimumAge}+ in ${countryInSentence(r.country)}'
                            '${r.storePolicy ? ' (store policy)' : ''}'
                            '${r.bornBefore != null ? ' · born before ${AppFormat.date(r.bornBefore)}${r.bornBeforeStorePolicy ? ' (store policy)' : ''}' : ''}',
                          ),
                          subtitle: Text([
                            if (r.checkedAt != null)
                              AppFormat.dateTime(r.checkedAt!.toIso8601String()),
                            r.refused
                                ? 'Refused — ${ageRefusalReasons[r.reason] ?? r.reason ?? ''}'
                                : 'Sale went ahead'
                                    '${r.idType != null ? ' — ${ageIdTypes[r.idType] ?? r.idType}' : ''}',
                          ].join(' · ')),
                        ),
                    ],
                  ),
                ),
        ),
      ],
    );
  }
}

class _SummaryCards extends StatelessWidget {
  const _SummaryCards({required this.summary});

  final AgeCheckSummary summary;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    Widget stat(String label, String value, {Color? color}) => Expanded(
          child: Card(
            child: Padding(
              padding: AppSpacing.cardPadding,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(value,
                      style: theme.textTheme.headlineSmall
                          ?.copyWith(color: color, fontWeight: FontWeight.w600)),
                  Text(label, style: theme.textTheme.labelMedium),
                ],
              ),
            ),
          ),
        );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // The theme's cards have no margin of their own, so the gap is here.
        // Stretched to one height, so a label that wraps on a phone does not
        // leave its neighbours short.
        IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              stat('Checks', '${summary.total}'),
              const SizedBox(width: AppSpacing.md),
              stat('Sales went ahead', '${summary.passed}'),
              const SizedBox(width: AppSpacing.md),
              stat('Refused', '${summary.refused}', color: theme.colorScheme.error),
            ],
          ),
        ),
        if (summary.refusedByReason.isNotEmpty) ...[
          const SizedBox(height: AppSpacing.sm),
          Wrap(
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.xs,
            children: [
              for (final e in summary.refusedByReason.entries)
                Chip(
                    label: Text(
                        '${ageRefusalReasons[e.key] ?? e.key}: ${e.value}')),
            ],
          ),
        ],
      ],
    );
  }
}
