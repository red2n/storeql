import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/reference/iso_reference.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';

// ---------------------------------------------------------------------------
// The laws this business trades under.
//
// tenant-svc keeps which obligations reach which country, directly or through
// the EU, with the day each takes effect and the instrument behind it. Every
// legal feature asks it rather than carrying its own list; this screen shows
// the business the same list.
// ---------------------------------------------------------------------------

class LegalObligation {
  final String code;
  final String scope;
  final String effectiveFrom;
  final String? effectiveTo;
  final String citation;
  final String summary;
  final String status;

  const LegalObligation({
    required this.code,
    required this.scope,
    required this.effectiveFrom,
    this.effectiveTo,
    required this.citation,
    required this.summary,
    required this.status,
  });

  bool get inForce => status == 'IN_FORCE';

  factory LegalObligation.fromJson(Map<String, dynamic> j) => LegalObligation(
        code: j['code'] as String? ?? '',
        scope: j['scope'] as String? ?? '',
        effectiveFrom: j['effectiveFrom'] as String? ?? '',
        effectiveTo: j['effectiveTo'] as String?,
        citation: j['citation'] as String? ?? '',
        summary: j['summary'] as String? ?? '',
        status: j['status'] as String? ?? '',
      );
}

/// A cash payment limit reaching the country (09.17): cash of [fromAmount] or
/// more, in [currency], is refused at the till while it is in force.
class CashLimit {
  final String scope;
  final String currency;
  final num fromAmount;
  final String effectiveFrom;
  final String? effectiveTo;
  final String citation;
  final String summary;
  final String status;
  const CashLimit({
    required this.scope,
    required this.currency,
    required this.fromAmount,
    required this.effectiveFrom,
    this.effectiveTo,
    required this.citation,
    required this.summary,
    required this.status,
  });
  bool get inForce => status == 'IN_FORCE';
  factory CashLimit.fromJson(Map<String, dynamic> j) => CashLimit(
        scope: j['scope'] as String? ?? '',
        currency: j['currency'] as String? ?? '',
        fromAmount: j['fromAmount'] as num? ?? 0,
        effectiveFrom: j['effectiveFrom'] as String? ?? '',
        effectiveTo: j['effectiveTo'] as String?,
        citation: j['citation'] as String? ?? '',
        summary: j['summary'] as String? ?? '',
        status: j['status'] as String? ?? '',
      );
}

class ObligationSheet {
  final String country;
  final String on;
  final List<LegalObligation> obligations;

  final List<CashLimit> cashLimits;
  const ObligationSheet(
      {required this.country, required this.on, required this.obligations,
      this.cashLimits = const []});

  factory ObligationSheet.fromJson(Map<String, dynamic> j) => ObligationSheet(
        country: j['country'] as String? ?? '',
        on: j['on'] as String? ?? '',
        obligations: [
          for (final o in (j['obligations'] as List?) ?? const [])
            LegalObligation.fromJson(o as Map<String, dynamic>)
        ],
        cashLimits: [
          for (final l in (j['cashLimits'] as List?) ?? const [])
            CashLimit.fromJson(l as Map<String, dynamic>)
        ],
      );
}

final obligationsProvider =
    FutureProvider.autoDispose<ObligationSheet>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.tenant}/admin/tenant/obligations');
  return ObligationSheet.fromJson(resp.data['data'] as Map<String, dynamic>);
});

class ObligationsScreen extends ConsumerWidget {
  const ObligationsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final sheet = ref.watch(obligationsProvider);
    final gutter = context.pageGutter;
    return ListView(
      padding: EdgeInsetsDirectional.only(bottom: gutter),
      children: [
        ContentBounds(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const PageHeader(
                title: 'Legal obligations',
                subtitle: 'The laws this business trades under, from its '
                    'country and, where it applies, EU law — with the day each '
                    'takes effect and the instrument behind it. The platform '
                    'checks features against this list. It is not legal advice.',
              ),
              Padding(
                padding: EdgeInsets.symmetric(horizontal: gutter),
                child: sheet.when(
                  loading: () =>
                      const LoadingView(label: 'Loading obligations…'),
                  error: (e, _) => ErrorView(
                    message: friendlyError(e,
                        fallback: 'Could not load the legal obligations.'),
                    onRetry: () => ref.invalidate(obligationsProvider),
                  ),
                  data: (s) => _Sheet(sheet: s),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _Sheet extends StatelessWidget {
  final ObligationSheet sheet;
  const _Sheet({required this.sheet});

  @override
  Widget build(BuildContext context) {
    final s = sheet;
    final country = countryInSentence(s.country);
    final inForce = s.obligations.where((o) => o.inForce).toList();
    final coming = s.obligations.where((o) => !o.inForce).toList();
    final cash = [
      if (s.cashLimits.isNotEmpty) ...[
        const SizedBox(height: AppSpacing.lg),
        _CashLimits(limits: s.cashLimits, country: country),
      ],
    ];
    if (s.obligations.isEmpty) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('No obligations are recorded for $country. That means the '
              'platform tracks none for this country yet, not that none '
              'apply.'),
          ...cash,
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (inForce.isNotEmpty)
          _ObligationGroup(title: 'In force in $country', items: inForce),
        if (coming.isNotEmpty) ...[
          const SizedBox(height: AppSpacing.lg),
          _ObligationGroup(title: 'Coming', items: coming),
        ],
        ...cash,
      ],
    );
  }
}

class _ObligationGroup extends StatelessWidget {
  final String title;
  final List<LegalObligation> items;

  const _ObligationGroup({required this.title, required this.items});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(title, style: theme.textTheme.titleMedium),
        const SizedBox(height: AppSpacing.sm),
        Card(
          child: LayoutBuilder(builder: (context, bc) {
            // On a phone a date at the end of the row left the obligation a
            // third of the width, six or seven lines a row: there the date
            // goes above the text, which then takes the whole row.
            final compact =
                AppBreakpoints.classOf(bc.maxWidth) == WindowClass.compact;
            return Column(
              children: [
                for (final o in items)
                  _ObligationRow(obligation: o, compact: compact),
              ],
            );
          }),
        ),
      ],
    );
  }
}

class _ObligationRow extends StatelessWidget {
  final LegalObligation obligation;
  final bool compact;
  const _ObligationRow({required this.obligation, required this.compact});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final o = obligation;
    // A date, not a control: plain words in the secondary ink, never a chip.
    final date = Text(
      o.inForce
          ? 'Since ${AppFormat.date(o.effectiveFrom)}'
          : 'From ${AppFormat.date(o.effectiveFrom)}',
      key: Key('obligation-date-${o.code}-${o.scope}'),
      style: theme.textTheme.labelMedium
          ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
    );
    final summary = Text(o.summary);
    return ListTile(
      key: Key('obligation-${o.code}-${o.scope}'),
      title: compact
          ? Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [date, const SizedBox(height: AppSpacing.xs), summary],
            )
          : summary,
      subtitle: Text(
          '${o.citation} · ${o.scope == 'EU' ? 'EU law' : 'National law'}'
          '${o.effectiveTo != null ? ' · until ${AppFormat.date(o.effectiveTo)}' : ''}'),
      trailing: compact ? null : date,
    );
  }
}

/// The cash payment limits that reach the country (09.17): what the till
/// refuses, in the law's currency, and from when.
class _CashLimits extends StatelessWidget {
  const _CashLimits({required this.limits, required this.country});
  final List<CashLimit> limits;

  /// The country in words, as it reads after "in".
  final String country;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Cash limits in $country', style: theme.textTheme.titleMedium),
            const SizedBox(height: AppSpacing.xs),
            Text(
              'The till refuses cash of these amounts or more, counting what was '
              'already taken in cash for the same sale.',
              style: theme.textTheme.bodySmall,
            ),
            for (final l in limits)
              ListTile(
                key: Key('cash-limit-${l.scope}-${l.currency}'),
                dense: true,
                contentPadding: EdgeInsets.zero,
                leading: Icon(l.inForce ? Icons.block : Icons.schedule),
                title: Text(
                    '${AppFormat.money(l.fromAmount, currencyCode: l.currency)} or more'
                    '${l.inForce ? '' : ' — from ${AppFormat.date(l.effectiveFrom)}'}'),
                subtitle: Text('${l.summary}\n${l.citation}'),
                isThreeLine: true,
                // A state, in the one badge every list uses — not a chip,
                // which reads as a filter to tap.
                trailing: l.inForce
                    ? const StatusBadge('In force', tone: StatusTone.success)
                    : const StatusBadge('Coming', tone: StatusTone.info),
              ),
          ],
        ),
      ),
    );
  }
}
