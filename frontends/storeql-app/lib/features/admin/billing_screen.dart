import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/reference/iso_reference.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';

// ---------------------------------------------------------------------------
// What this business pays the platform (21.9).
//
// The subscription it is on, the period it has been billed for, and its own
// invoices. Two things an owner needs to see before they matter: whether the
// subscription ends at the period end, and whether its VAT number has been
// checked — because an unchecked number means it is charged VAT it could
// otherwise account for itself, and nobody thinks to ask.
// ---------------------------------------------------------------------------

class BillingBuyer {
  final String? name;
  final String? country;
  final String? vatNumber;
  final bool vatChecked;
  final String? vatCheckSource;

  const BillingBuyer({
    required this.name,
    required this.country,
    required this.vatNumber,
    required this.vatChecked,
    required this.vatCheckSource,
  });

  factory BillingBuyer.fromJson(Map<String, dynamic> j) => BillingBuyer(
        name: j['name'] as String?,
        country: j['country'] as String?,
        vatNumber: j['vatNumber'] as String?,
        vatChecked: j['vatChecked'] == true,
        vatCheckSource: j['vatCheckSource'] as String?,
      );

  /// Said plainly, because the consequence is money.
  String get vatSays {
    if (vatNumber == null || vatNumber!.isEmpty) return 'No VAT number given';
    if (vatChecked) return '$vatNumber · ${vatCheckedHow(vatCheckSource)}';
    return '$vatNumber · not checked yet, so VAT is charged';
  }

  /// How a checked number was checked, in words: the source is stored as a
  /// code (VIES | MANUAL | SIMULATED).
  static String vatCheckedHow(String? source) => switch ((source ?? '').toUpperCase()) {
        '' => 'checked',
        'VIES' => 'checked with VIES',
        'MANUAL' => 'checked by hand',
        'SIMULATED' => 'checked by the simulator, not a real register',
        _ => 'checked (${humanizeCode(source)})',
      };
}

class Subscription {
  final String status;
  final String? planName;
  final String? planCode;
  final num? priceAmount;
  final String? currency;
  final String? billingInterval;
  final String? periodStart;
  final String? periodEnd;
  final String? trialEnd;
  final bool cancelAtPeriodEnd;
  final bool changePending;
  final BillingBuyer? buyer;
  /// Where the platform's invoices and notices go: the owner's sign-up address until changed.
  final String? billingEmail;

  const Subscription({
    required this.status,
    required this.planName,
    required this.planCode,
    required this.priceAmount,
    required this.currency,
    required this.billingInterval,
    required this.periodStart,
    required this.periodEnd,
    required this.trialEnd,
    required this.cancelAtPeriodEnd,
    required this.changePending,
    required this.buyer,
    this.billingEmail,
  });

  factory Subscription.fromJson(Map<String, dynamic> j) => Subscription(
        status: j['status'] as String? ?? '',
        planName: j['planName'] as String?,
        planCode: j['planCode'] as String?,
        priceAmount: j['priceAmount'] as num?,
        currency: j['currency'] as String?,
        billingInterval: j['billingInterval'] as String?,
        periodStart: j['periodStart'] as String?,
        periodEnd: j['periodEnd'] as String?,
        trialEnd: j['trialEnd'] as String?,
        cancelAtPeriodEnd: j['cancelAtPeriodEnd'] == true,
        changePending: j['pendingPlanId'] != null,
        buyer: j['buyer'] == null ? null : BillingBuyer.fromJson(Map<String, dynamic>.from(j['buyer'] as Map)),
        billingEmail: j['billingEmail'] as String?,
      );

  bool get trialing => status == 'TRIALING';
  bool get behind => status == 'PAST_DUE';

  /// The status in words, and the tone its badge is shown in.
  (String, StatusTone) get statusSays => switch (status) {
        'TRIALING' => ('Trial', StatusTone.info),
        'ACTIVE' => ('Active', StatusTone.success),
        'PAST_DUE' => ('Past due', StatusTone.error),
        'SUSPENDED' => ('Suspended', StatusTone.warning),
        'CANCELLED' => ('Cancelled', StatusTone.neutral),
        _ => (humanizeCode(status), StatusTone.neutral),
      };

  /// What it costs per billing interval, as money: `£29.00 per month`.
  String get priceSays {
    final per = switch ((billingInterval ?? '').toUpperCase()) {
      'MONTH' => 'month',
      'YEAR' => 'year',
      '' => 'period',
      _ => humanizeCode(billingInterval).toLowerCase(),
    };
    final price = priceAmount == null ? '—' : AppFormat.money(priceAmount!, currencyCode: currency);
    return '$price per $per';
  }

  /// The last day billed: [periodEnd] is exclusive — the day the next period
  /// starts — so the period's own last day is the one before it.
  String? get periodLastDay {
    final end = DateTime.tryParse(periodEnd ?? '');
    if (end == null) return periodEnd;
    // Calendar arithmetic, not 24 hours, so a clock change cannot shift it.
    return DateTime(end.year, end.month, end.day - 1).toIso8601String();
  }
}

class Invoice {
  final String id;
  final String number;
  final String status;
  final String? issueDate;
  final String? dueDate;
  final num? totalAmount;
  final num? outstanding;
  final String? currency;
  final String? taxTreatment;

  const Invoice({
    required this.id,
    required this.number,
    required this.status,
    required this.issueDate,
    required this.dueDate,
    required this.totalAmount,
    required this.outstanding,
    required this.currency,
    required this.taxTreatment,
  });

  factory Invoice.fromJson(Map<String, dynamic> j) => Invoice(
        id: j['id'] as String? ?? '',
        number: j['number'] as String? ?? '',
        status: j['status'] as String? ?? '',
        issueDate: j['issueDate'] as String?,
        dueDate: j['dueDate'] as String?,
        totalAmount: j['totalAmount'] as num?,
        outstanding: j['outstanding'] as num?,
        currency: j['currency'] as String?,
        taxTreatment: j['taxTreatment'] as String?,
      );

  bool get owed => status == 'OPEN';

  /// A settled invoice's status in words, and its badge's tone.
  (String, StatusTone) get statusSays => switch (status) {
        'OPEN' => ('Owed', StatusTone.error),
        'PAID' => ('Paid', StatusTone.success),
        'VOID' => ('Voided', StatusTone.neutral),
        'UNCOLLECTIBLE' => ('Written off', StatusTone.warning),
        _ => (humanizeCode(status), StatusTone.neutral),
      };

  /// The reverse charge is worth naming: it is why an invoice carries no VAT.
  String? get treatmentSays => switch (taxTreatment) {
        'REVERSE_CHARGE' => 'Reverse charge — no VAT',
        'DESTINATION' => 'Taxed where the business is',
        'OUT_OF_SCOPE' => 'Outside VAT',
        _ => null,
      };
}

class BillingFile {
  final Subscription? subscription;
  final List<Invoice> invoices;

  const BillingFile({required this.subscription, required this.invoices});

  bool get subscribed => subscription != null;
}

final billingProvider = FutureProvider.autoDispose<BillingFile>((ref) async {
  final dio = ref.watch(apiClientProvider).dio;
  final sub = await dio.get('/${ApiConstants.tenant}/admin/tenant/billing');
  final subJson = Map<String, dynamic>.from(sub.data['data'] as Map);
  final invoices = await dio.get('/${ApiConstants.tenant}/admin/tenant/billing/invoices?limit=50');
  return BillingFile(
    subscription: subJson['subscription'] == null
        ? null
        : Subscription.fromJson(Map<String, dynamic>.from(subJson['subscription'] as Map)),
    invoices: [
      for (final i in invoices.data['data'] as List<dynamic>? ?? const [])
        Invoice.fromJson(Map<String, dynamic>.from(i as Map)),
    ],
  );
});

class BillingScreen extends ConsumerWidget {
  const BillingScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final billing = ref.watch(billingProvider);
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    // 16 on a phone, 24 from tablet width up, under the header's own inset.
    final gutter = context.pageGutter;
    void refresh() => ref.invalidate(billingProvider);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        PageHeader(
          title: 'Billing',
          subtitle: 'What this business pays the platform, and the invoices it has been sent.',
          actions: [
            IconButton(icon: const Icon(Icons.refresh), tooltip: 'Refresh', onPressed: refresh),
          ],
        ),
        Expanded(
          child: billing.when(
            loading: () => const LoadingView(label: 'Loading the subscription…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load the billing details.'),
              onRetry: refresh,
            ),
            data: (f) => !f.subscribed
                ? const EmptyState(
                    key: Key('billing-none'),
                    icon: Icons.receipt_long_outlined,
                    title: 'No subscription',
                    message:
                        'This business is not subscribed to anything, so nothing is being billed.',
                  )
                : ListView(
                    padding: EdgeInsetsDirectional.fromSTEB(gutter, 0, gutter, gutter),
                    children: [
                      _SubscriptionCard(subscription: f.subscription!),
                      const SizedBox(height: AppSpacing.lg),
                      Text('Invoices', style: text.titleMedium),
                      const SizedBox(height: AppSpacing.sm),
                      if (f.invoices.isEmpty)
                        Text('None yet.', style: text.bodyMedium?.copyWith(color: cs.outline))
                      else
                        for (final invoice in f.invoices) _InvoiceRow(invoice: invoice),
                    ],
                  ),
          ),
        ),
      ],
    );
  }
}

class _SubscriptionCard extends StatelessWidget {
  final Subscription subscription;

  const _SubscriptionCard({required this.subscription});

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final s = subscription;
    final (statusWords, statusTone) = s.statusSays;
    // The day the next period starts: when a cancellation or a plan change lands.
    final nextStarts = s.periodEnd == null ? 'the period end' : AppFormat.date(s.periodEnd);
    return Card(
      key: const Key('subscription'),
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: Text(s.planName ?? s.planCode ?? 'Plan', style: text.titleLarge)),
                const SizedBox(width: AppSpacing.sm),
                StatusBadge(statusWords, key: const Key('subscription-status'), tone: statusTone),
              ],
            ),
            const SizedBox(height: AppSpacing.sm),
            Text(s.priceSays, style: text.bodyLarge),
            if (s.periodStart != null && s.periodEnd != null) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(
                'Billed ${AppFormat.date(s.periodStart)} to ${AppFormat.date(s.periodLastDay)}',
                style: text.bodyMedium?.copyWith(color: cs.outline),
              ),
            ],
            if (s.trialing && s.trialEnd != null) ...[
              const SizedBox(height: AppSpacing.sm),
              _Note(
                key: const Key('note-trial'),
                text: 'Free until ${AppFormat.date(s.trialEnd)}. The first invoice comes then.',
                tone: cs.primary,
              ),
            ],
            if (s.behind) ...[
              const SizedBox(height: AppSpacing.sm),
              _Note(key: const Key('note-behind'), text: 'An invoice is past its date.', tone: cs.error),
            ],
            if (s.cancelAtPeriodEnd) ...[
              const SizedBox(height: AppSpacing.sm),
              _Note(key: const Key('note-ending'), text: 'Ends on $nextStarts.', tone: cs.error),
            ],
            if (s.changePending) ...[
              const SizedBox(height: AppSpacing.sm),
              _Note(
                key: const Key('note-pending'),
                text: 'A plan change takes effect on $nextStarts.',
                tone: cs.primary,
              ),
            ],
            if (s.buyer != null) ...[
              const Divider(height: AppSpacing.xl),
              Text('Invoiced to', style: text.titleSmall),
              const SizedBox(height: AppSpacing.xs),
              Text(
                '${s.buyer!.name ?? '—'} · '
                '${s.buyer!.country == null ? '—' : countryName(s.buyer!.country!)}',
                style: text.bodyMedium,
              ),
              const SizedBox(height: AppSpacing.xs),
              Text(
                s.buyer!.vatSays,
                style: text.bodySmall?.copyWith(
                  color: s.buyer!.vatChecked ? cs.outline : cs.error,
                ),
              ),
            ],
            // Where a late-payment notice goes (21.12). Said here because a business that never
            // sees one is suspended without warning, and the address is the owner's from sign-up
            // unless somebody changed it.
            const SizedBox(height: AppSpacing.sm),
            Text(
              s.billingEmail == null || s.billingEmail!.isEmpty
                  ? 'No billing email: the platform cannot tell you when an invoice is late.'
                  : 'Invoices and payment notices go to ${s.billingEmail}.',
              key: const Key('billing-email'),
              style: text.bodySmall?.copyWith(
                color: s.billingEmail == null || s.billingEmail!.isEmpty ? cs.error : cs.outline,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _InvoiceRow extends StatelessWidget {
  final Invoice invoice;

  const _InvoiceRow({required this.invoice});

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final i = invoice;
    final says = i.treatmentSays;
    final (statusWords, statusTone) = i.statusSays;
    // Its own row rather than a ListTile: a dense tile caps its trailing slot
    // at 48px, and the amount over its status outgrows that as soon as the
    // text is larger than 100%.
    final number = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(i.number, style: text.bodyLarge),
        Text(
          [
            if (i.issueDate != null) 'Issued ${AppFormat.date(i.issueDate)}',
            if (i.owed && i.dueDate != null) 'due ${AppFormat.date(i.dueDate)}',
            ?says,
          ].join(' · '),
          style: text.bodySmall?.copyWith(color: cs.outline),
        ),
      ],
    );
    final amount = Text(
      i.totalAmount == null
          ? '—'
          : AppFormat.money(i.totalAmount!, currencyCode: i.currency),
      style: text.bodyLarge,
    );
    // What is still owed is the one thing to act on, so it is said with its
    // amount; a settled invoice says its status as a word.
    final status = i.owed
        ? Text(
            '${AppFormat.money(i.outstanding ?? 0, currencyCode: i.currency)} owed',
            style: text.bodySmall?.copyWith(color: cs.error),
          )
        : StatusBadge(statusWords, tone: statusTone);
    return Padding(
      key: Key('invoice-${i.number}'),
      padding: const EdgeInsetsDirectional.symmetric(vertical: AppSpacing.sm),
      child: LayoutBuilder(builder: (context, constraints) {
        // A phone at large text puts the amount under the number, so the
        // number keeps the width; otherwise the amount sits at the end.
        final largeText = MediaQuery.textScalerOf(context).scale(16) > 16 * 1.3;
        if (largeText && constraints.maxWidth < PageHeader.defaultStackBelow) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              number,
              const SizedBox(height: AppSpacing.xs),
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.xs,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [amount, status],
              ),
            ],
          );
        }
        return Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(child: number),
            const SizedBox(width: AppSpacing.md),
            Column(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [amount, const SizedBox(height: AppSpacing.xs), status],
            ),
          ],
        );
      }),
    );
  }
}

class _Note extends StatelessWidget {
  final String text;
  final Color tone;

  const _Note({required this.text, required this.tone, super.key});

  @override
  Widget build(BuildContext context) => Row(
        children: [
          Icon(Icons.info_outline, size: 16, color: tone),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Text(text, style: Theme.of(context).textTheme.bodySmall?.copyWith(color: tone)),
          ),
        ],
      );
}
