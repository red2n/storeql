import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../admin/providers/admin_providers.dart';

// ---------------------------------------------------------------------------
// The platform's own billing (21.9).
//
// Two things an operator comes here for: what is owed, oldest first, and
// whether the platform can invoice at all. The second is worth its own line
// because nothing is billed until the platform has said who it is, and a
// platform that has not filled that in looks like a platform with no customers.
// ---------------------------------------------------------------------------

class PlatformProfile {
  final String legalName;
  final String country;
  final String invoicePrefix;
  final int paymentTermsDays;
  final num taxRate;
  final String? vatNumber;

  const PlatformProfile({
    required this.legalName,
    required this.country,
    required this.invoicePrefix,
    required this.paymentTermsDays,
    required this.taxRate,
    required this.vatNumber,
  });

  factory PlatformProfile.fromJson(Map<String, dynamic> j) => PlatformProfile(
        legalName: j['legalName'] as String? ?? '',
        country: j['country'] as String? ?? '',
        invoicePrefix: j['invoicePrefix'] as String? ?? '',
        paymentTermsDays: (j['paymentTermsDays'] as num?)?.toInt() ?? 0,
        taxRate: j['taxRate'] as num? ?? 0,
        vatNumber: j['vatNumber'] as String?,
      );

  /// The terms, under the legal name the card already shows as its title.
  String get says =>
      '$country · $invoicePrefix-… · $paymentTermsDays days · '
      '${(taxRate * 100).toStringAsFixed(2)}%';
}

/// What the platform has done about one overdue invoice, and what it will do next (21.12).
class DunningStage {
  final String? stage;
  final String? nextStep;
  final int daysOverdue;

  /// Whose invoice it is. A receivable says so itself; this is the fallback for a receivables
  /// answer that does not (a tenant-svc from before it did).
  final String? tenantId;

  const DunningStage({
    required this.stage,
    required this.nextStep,
    required this.daysOverdue,
    this.tenantId,
  });

  factory DunningStage.fromJson(Map<String, dynamic> j) => DunningStage(
        stage: j['stage'] as String?,
        nextStep: j['nextStep'] as String?,
        daysOverdue: (j['daysOverdue'] as num?)?.toInt() ?? 0,
        tenantId: j['tenantId'] as String?,
      );

  /// A reminder reads as one; the two that matter read as themselves.
  static String? _words(String? step) {
    if (step == null) return null;
    if (step.startsWith('REMINDER_')) return 'reminder ${step.substring(9)}';
    return switch (step) {
      'SUSPENDED' => 'suspended',
      'UNCOLLECTIBLE' => 'written off',
      'DUE_DATE_EXTENDED' => 'date extended',
      'RESOLVED' => 'paid',
      _ => step.toLowerCase(),
    };
  }

  String? get stageSays => _words(stage);

  /// Said plainly, because an operator seeing "suspended next" can act before a customer calls.
  String? get nextSays => _words(nextStep) == null ? null : '${_words(nextStep)} next';

  bool get suspended => stage == 'SUSPENDED';
}

class Receivable {
  final String id;
  final String number;
  final String? issueDate;
  final String? dueDate;
  final String? currency;
  final num? totalAmount;
  final num? outstanding;
  final String? taxTreatment;

  /// Whose invoice it is, so every row can name the business — not only the overdue ones the
  /// dunning list knows about.
  final String? tenantId;

  const Receivable({
    required this.id,
    required this.number,
    required this.issueDate,
    required this.dueDate,
    required this.currency,
    required this.totalAmount,
    required this.outstanding,
    required this.taxTreatment,
    this.tenantId,
  });

  factory Receivable.fromJson(Map<String, dynamic> j) => Receivable(
        id: j['id'] as String? ?? '',
        number: j['number'] as String? ?? '',
        issueDate: j['issueDate'] as String?,
        dueDate: j['dueDate'] as String?,
        currency: j['currency'] as String?,
        totalAmount: j['totalAmount'] as num?,
        outstanding: j['outstanding'] as num?,
        taxTreatment: j['taxTreatment'] as String?,
        tenantId: j['tenantId'] as String?,
      );

  /// Overdue against today, which is the only question a receivables list answers. A due date is a
  /// day, not an instant: an invoice due today is not overdue until the day is out, which is how
  /// the dunning run counts it too.
  bool overdueOn(DateTime day) {
    final due = dueDate == null ? null : DateTime.tryParse(dueDate!);
    if (due == null) return false;
    return DateTime(day.year, day.month, day.day)
        .isAfter(DateTime(due.year, due.month, due.day));
  }
}

/// The profile may legitimately not be set yet, and that is information, not an error.
class PlatformBilling {
  final PlatformProfile? profile;
  final List<Receivable> owed;

  /// The dunning stage per invoice id: one for every overdue invoice, its stage null until it has
  /// been chased. Absent for an invoice not yet due, and when dunning cannot be read.
  final Map<String, DunningStage> stages;

  const PlatformBilling({required this.profile, required this.owed, required this.stages});

  /// What is still owed, one total per currency in the order they first appear —
  /// `£354.00 · €120.00`. Pounds and euros do not add up to one figure.
  String get owedSays {
    final totals = <String, num>{};
    for (final r in owed) {
      final code = r.currency ?? '';
      totals[code] = (totals[code] ?? 0) + (r.outstanding ?? 0);
    }
    return [
      for (final e in totals.entries) AppFormat.money(e.value, currencyCode: e.key),
    ].join(' · ');
  }

  int get suspendedCount => stages.values.where((s) => s.suspended).length;
}

final platformBillingProvider = FutureProvider.autoDispose<PlatformBilling>((ref) async {
  final dio = ref.watch(apiClientProvider).dio;
  PlatformProfile? profile;
  try {
    final resp = await dio.get('/${ApiConstants.tenant}/platform/billing/profile');
    profile = PlatformProfile.fromJson(Map<String, dynamic>.from(resp.data['data'] as Map));
  } on Object {
    // 409 BILLING_PROFILE_NOT_SET: nothing has been filled in. Shown as such below.
    profile = null;
  }
  final owed = await dio.get('/${ApiConstants.tenant}/platform/billing/receivables?limit=100');
  // The stages come from the dunning side, keyed on the invoice. Read separately and joined here
  // rather than folded into the receivables response: what is owed and what has been done about it
  // are two questions, and an operator may want the first even when dunning is switched off.
  final stages = <String, DunningStage>{};
  try {
    final chased =
        await dio.get('/${ApiConstants.tenant}/platform/billing/dunning/overdue?limit=100');
    for (final o in chased.data['data'] as List<dynamic>? ?? const []) {
      final row = Map<String, dynamic>.from(o as Map);
      stages[row['invoiceId'] as String] = DunningStage.fromJson(row);
    }
  } on Object {
    // Dunning is the platform's own and may legitimately be off; the arrears still show.
  }
  return PlatformBilling(
    profile: profile,
    owed: [
      for (final r in owed.data['data'] as List<dynamic>? ?? const [])
        Receivable.fromJson(Map<String, dynamic>.from(r as Map)),
    ],
    stages: stages,
  );
});

/// The business each receivable is owed by, by name: every tenant id the
/// receivables carry (and the overdue list's, as a fallback), looked up in the
/// platform's tenant list and — for a business past its first page — asked
/// for one by one (`GET /platform/tenants/{id}`). An id nobody can name is
/// left out: the row then shows its invoice number alone, never an id.
final receivableTenantNamesProvider =
    FutureProvider.autoDispose<Map<String, String>>((ref) async {
  final billing = await ref.watch(platformBillingProvider.future);
  final ids = <String>{
    for (final r in billing.owed) ?r.tenantId,
    for (final s in billing.stages.values) ?s.tenantId,
  };
  final names = <String, String>{};
  if (ids.isEmpty) return names;
  try {
    for (final t in await ref.watch(allTenantsProvider.future)) {
      if (ids.contains(t.id)) names[t.id] = t.name;
    }
  } on Object {
    // The list is best effort; each business can still be asked for alone.
  }
  final dio = ref.read(apiClientProvider).dio;
  await Future.wait([
    for (final id in ids.where((id) => !names.containsKey(id)))
      () async {
        try {
          final resp = await dio.get('/${ApiConstants.tenant}/platform/tenants/$id');
          final data = resp.data is Map ? resp.data['data'] : null;
          final name = data is Map ? data['name'] : null;
          if (name is String && name.trim().isNotEmpty) names[id] = name.trim();
        } on Object {
          // Unknown or unreadable: the row keeps its invoice number.
        }
      }(),
  ]);
  return names;
});

class PlatformBillingScreen extends ConsumerWidget {
  const PlatformBillingScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final billing = ref.watch(platformBillingProvider);
    // Each receivable names its business by id; the platform's tenants turn that into the name an
    // operator rings, whichever page of the list the business is on. Best effort: without it a row
    // still shows its invoice.
    final tenantNames =
        ref.watch(receivableTenantNamesProvider).value ?? const <String, String>{};
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final gutter = context.pageGutter;
    void refresh() {
      ref.invalidate(platformBillingProvider);
      ref.invalidate(allTenantsProvider);
      ref.invalidate(receivableTenantNamesProvider);
    }

    // Pull to refresh keeps its spinner until the books have been read again.
    Future<void> pullToRefresh() async {
      refresh();
      try {
        await ref.read(platformBillingProvider.future);
      } on Object {
        // A failed read replaces the list with the error view and its retry.
      }
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        PageHeader(
          title: 'Billing',
          subtitle: 'What the platform invoices as, and what is still owed.',
          actions: [
            IconButton(icon: const Icon(Icons.refresh), tooltip: 'Refresh', onPressed: refresh),
          ],
        ),
        Expanded(
          child: billing.when(
            loading: () => const LoadingView(label: 'Loading the books…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load the billing details.'),
              onRetry: refresh,
            ),
            data: (b) => RefreshIndicator.adaptive(
              onRefresh: pullToRefresh,
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: EdgeInsetsDirectional.fromSTEB(gutter, 0, gutter, gutter),
                children: [
                  if (b.profile == null)
                    Card(
                      key: const Key('profile-unset'),
                      color: cs.errorContainer,
                      child: Padding(
                        padding: AppSpacing.cardPadding,
                        child: Text(
                          'The platform has not said who it invoices as, so nothing can be billed. '
                          'An invoice with no seller is not an invoice anywhere it trades.',
                          style: text.bodyMedium?.copyWith(color: cs.onErrorContainer),
                        ),
                      ),
                    )
                  else
                    Card(
                      key: const Key('profile'),
                      child: ListTile(
                        title: Text(b.profile!.legalName, style: text.titleMedium),
                        subtitle: Text(b.profile!.says, style: text.bodySmall),
                        trailing: b.profile!.vatNumber == null
                            ? null
                            : Text('VAT ${b.profile!.vatNumber}', style: text.bodySmall),
                      ),
                    ),
                  const SizedBox(height: AppSpacing.lg),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Owed', style: text.titleMedium),
                      const SizedBox(width: AppSpacing.lg),
                      // The suspended count and a total per currency. On a phone, or with several
                      // currencies, they wrap under one another rather than run off the edge.
                      Expanded(
                        child: Wrap(
                          alignment: WrapAlignment.end,
                          crossAxisAlignment: WrapCrossAlignment.center,
                          spacing: AppSpacing.md,
                          runSpacing: AppSpacing.xs,
                          children: [
                            if (b.suspendedCount > 0)
                              Text(
                                key: const Key('suspended-count'),
                                '${b.suspendedCount} suspended',
                                style: text.bodyMedium?.copyWith(color: cs.error),
                              ),
                            Text(
                              key: const Key('total-owed'),
                              b.owed.isEmpty ? 'nothing' : b.owedSays,
                              textAlign: TextAlign.end,
                              style: text.titleMedium,
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: AppSpacing.sm),
                  if (b.owed.isEmpty)
                    Text(
                      key: const Key('owed-none'),
                      'Every invoice is settled.',
                      style: text.bodyMedium?.copyWith(color: cs.outline),
                    )
                  else
                    for (final r in b.owed)
                      _ReceivableRow(
                        receivable: r,
                        stage: b.stages[r.id],
                        // The invoice's own business first; the overdue list's as a fallback; and
                        // with neither known, the row is its invoice number alone.
                        tenantName: tenantNames[r.tenantId] ??
                            tenantNames[b.stages[r.id]?.tenantId],
                      ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _ReceivableRow extends StatelessWidget {
  final Receivable receivable;
  final DunningStage? stage;

  /// Who owes it, when the console knows the business. Unknown, the row shows its number alone —
  /// never the business's id.
  final String? tenantName;

  const _ReceivableRow({required this.receivable, this.stage, this.tenantName});

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final r = receivable;
    final overdue = r.overdueOn(DateTime.now());
    final suspended = stage?.suspended == true;
    final due = AppFormat.date(r.dueDate);
    return ListTile(
      key: Key('owed-${r.number}'),
      dense: true,
      leading: Icon(
        suspended
            ? Icons.block
            : overdue
                ? Icons.warning_amber
                : Icons.schedule,
        color: suspended || overdue ? cs.error : cs.outline,
        size: 20,
      ),
      title: Text(r.number, style: text.bodyLarge),
      subtitle: Text(
        [
          // Who to call, first.
          ?tenantName,
          if (overdue) 'Overdue since $due' else 'Due ${due.isEmpty ? '—' : due}',
          // What has been done, and what is coming. An operator who can see "suspended next" can act
          // before a customer telephones to say the till has stopped working.
          ?stage?.stageSays,
          ?stage?.nextSays,
        ].join(' · '),
        style: text.bodySmall?.copyWith(color: suspended || overdue ? cs.error : cs.outline),
      ),
      trailing: Text(
        AppFormat.money(r.outstanding ?? 0, currencyCode: r.currency),
        style: text.titleSmall?.copyWith(color: cs.onSurface),
      ),
    );
  }
}
