import 'package:flutter/material.dart';
import '../../core/format.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/theme.dart';
import '../../core/spacing.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';

// ---------------------------------------------------------------------------
// Supplier lead-time tracking and scorecards. Every goods receipt measures the
// delivery against the order's promise (the date it named, or the supplier's
// quoted lead time); a period's deliveries, fill, returns and invoice matches
// are weighed into one card per supplier, ranked. The buyer reads who delivers
// on time and in full, and who to ring.
// ---------------------------------------------------------------------------

class DeliveryStats {
  final int count;
  final double? avgLeadDays;
  final double? medianLeadDays;
  final int? maxLeadDays;
  final int promised;
  final int onTime;
  final int late;
  final double? onTimePct;
  final double? avgDaysLate;
  final double receivedQty;
  const DeliveryStats({
    required this.count,
    this.avgLeadDays,
    this.medianLeadDays,
    this.maxLeadDays,
    required this.promised,
    required this.onTime,
    required this.late,
    this.onTimePct,
    this.avgDaysLate,
    required this.receivedQty,
  });

  factory DeliveryStats.fromJson(Map<String, dynamic> j) => DeliveryStats(
        count: (j['count'] as num?)?.toInt() ?? 0,
        avgLeadDays: (j['avgLeadDays'] as num?)?.toDouble(),
        medianLeadDays: (j['medianLeadDays'] as num?)?.toDouble(),
        maxLeadDays: (j['maxLeadDays'] as num?)?.toInt(),
        promised: (j['promised'] as num?)?.toInt() ?? 0,
        onTime: (j['onTime'] as num?)?.toInt() ?? 0,
        late: (j['late'] as num?)?.toInt() ?? 0,
        onTimePct: (j['onTimePct'] as num?)?.toDouble(),
        avgDaysLate: (j['avgDaysLate'] as num?)?.toDouble(),
        receivedQty: (j['receivedQty'] as num?)?.toDouble() ?? 0,
      );
}

class FillStats {
  final int orders;
  final double orderedQty;
  final double receivedQty;
  final double? fillRatePct;
  final int shortClosed;
  const FillStats({
    required this.orders,
    required this.orderedQty,
    required this.receivedQty,
    this.fillRatePct,
    required this.shortClosed,
  });

  factory FillStats.fromJson(Map<String, dynamic> j) => FillStats(
        orders: (j['orders'] as num?)?.toInt() ?? 0,
        orderedQty: (j['orderedQty'] as num?)?.toDouble() ?? 0,
        receivedQty: (j['receivedQty'] as num?)?.toDouble() ?? 0,
        fillRatePct: (j['fillRatePct'] as num?)?.toDouble(),
        shortClosed: (j['shortClosed'] as num?)?.toInt() ?? 0,
      );
}

class QualityStats {
  final int returns;
  final double returnedQty;
  final double? returnRatePct;
  const QualityStats({required this.returns, required this.returnedQty, this.returnRatePct});

  factory QualityStats.fromJson(Map<String, dynamic> j) => QualityStats(
        returns: (j['returns'] as num?)?.toInt() ?? 0,
        returnedQty: (j['returnedQty'] as num?)?.toDouble() ?? 0,
        returnRatePct: (j['returnRatePct'] as num?)?.toDouble(),
      );
}

class InvoiceStats {
  final int invoices;
  final int flagged;
  final double? accuracyPct;
  const InvoiceStats({required this.invoices, required this.flagged, this.accuracyPct});

  factory InvoiceStats.fromJson(Map<String, dynamic> j) => InvoiceStats(
        invoices: (j['invoices'] as num?)?.toInt() ?? 0,
        flagged: (j['flagged'] as num?)?.toInt() ?? 0,
        accuracyPct: (j['accuracyPct'] as num?)?.toDouble(),
      );
}

class SupplierScorecard {
  final String supplierId;
  final String supplierName;
  final int? leadTimeDays;
  final String from;
  final String to;
  final DeliveryStats deliveries;
  final FillStats fill;
  final QualityStats quality;
  final InvoiceStats invoices;
  final double? score;
  final String? grade;
  const SupplierScorecard({
    required this.supplierId,
    required this.supplierName,
    this.leadTimeDays,
    required this.from,
    required this.to,
    required this.deliveries,
    required this.fill,
    required this.quality,
    required this.invoices,
    this.score,
    this.grade,
  });

  factory SupplierScorecard.fromJson(Map<String, dynamic> j) => SupplierScorecard(
        supplierId: j['supplierId'] as String? ?? '',
        supplierName: j['supplierName'] as String? ?? '',
        leadTimeDays: (j['leadTimeDays'] as num?)?.toInt(),
        from: j['from'] as String? ?? '',
        to: j['to'] as String? ?? '',
        deliveries: DeliveryStats.fromJson((j['deliveries'] as Map<String, dynamic>?) ?? const {}),
        fill: FillStats.fromJson((j['fill'] as Map<String, dynamic>?) ?? const {}),
        quality: QualityStats.fromJson((j['quality'] as Map<String, dynamic>?) ?? const {}),
        invoices: InvoiceStats.fromJson((j['invoices'] as Map<String, dynamic>?) ?? const {}),
        score: (j['score'] as num?)?.toDouble(),
        grade: j['grade'] as String?,
      );
}

class SupplierDelivery {
  final String id;
  final String poId;
  final String orderedAt;
  final String? promisedDate;
  final String receivedAt;
  final int leadDays;
  final int? lateDays;
  final bool complete;
  final double receivedQty;
  const SupplierDelivery({
    required this.id,
    required this.poId,
    required this.orderedAt,
    this.promisedDate,
    required this.receivedAt,
    required this.leadDays,
    this.lateDays,
    required this.complete,
    required this.receivedQty,
  });

  factory SupplierDelivery.fromJson(Map<String, dynamic> j) => SupplierDelivery(
        id: j['id'] as String? ?? '',
        poId: j['poId'] as String? ?? '',
        orderedAt: j['orderedAt'] as String? ?? '',
        promisedDate: j['promisedDate'] as String?,
        receivedAt: j['receivedAt'] as String? ?? '',
        leadDays: (j['leadDays'] as num?)?.toInt() ?? 0,
        lateDays: (j['lateDays'] as num?)?.toInt(),
        complete: j['complete'] as bool? ?? false,
        receivedQty: (j['receivedQty'] as num?)?.toDouble() ?? 0,
      );
}

/// The last ninety days, the server's own default; sent so the period the card
/// names is the one the page asked for.
Map<String, String> scorecardPeriod([DateTime? today]) {
  final t = today ?? DateTime.now();
  final from = t.subtract(const Duration(days: 90));
  return {
    'from': from.toIso8601String().split('T').first,
    'to': t.toIso8601String().split('T').first,
  };
}

final supplierScorecardsProvider = FutureProvider.autoDispose<List<SupplierScorecard>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.purchase}/suppliers/scorecards',
    queryParameters: scorecardPeriod(),
  );
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => SupplierScorecard.fromJson(e as Map<String, dynamic>))
      .toList();
});

final supplierDeliveriesProvider =
    FutureProvider.autoDispose.family<List<SupplierDelivery>, String>((ref, supplierId) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.purchase}/suppliers/$supplierId/deliveries',
    queryParameters: scorecardPeriod(),
  );
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => SupplierDelivery.fromJson(e as Map<String, dynamic>))
      .toList();
});

String _n(num? v, {String unit = ''}) {
  if (v == null) return '–';
  final s = v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toStringAsFixed(1);
  return '$s$unit';
}

/// The grade as a chip: the colour says at a glance who is delivering.
class GradeChip extends StatelessWidget {
  const GradeChip(this.grade, {super.key});
  final String? grade;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final status = context.status;
    final (Color bg, Color fg) = switch (grade) {
      'A' => (status.successContainer, status.onSuccessContainer),
      'B' => (cs.secondaryContainer, cs.onSecondaryContainer),
      'C' => (status.warningContainer, status.onWarningContainer),
      'D' => (cs.errorContainer, cs.onErrorContainer),
      _ => (cs.surfaceContainerHighest, cs.onSurfaceVariant),
    };
    return Container(
      key: Key('grade-${grade ?? 'none'}'),
      width: 28,
      height: 28,
      alignment: Alignment.center,
      decoration: BoxDecoration(color: bg, borderRadius: AppRadius.badge),
      child: Text(grade ?? '–',
          style: TextStyle(color: fg, fontWeight: FontWeight.w700, fontSize: 13)),
    );
  }
}

/// The ranked scorecards, at the top of the Suppliers tab for management.
class SupplierScorecardsCard extends ConsumerWidget {
  const SupplierScorecardsCard({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cards = ref.watch(supplierScorecardsProvider);
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: EdgeInsetsDirectional.fromSTEB(
          context.pageGutter, AppSpacing.xs, context.pageGutter, AppSpacing.sm),
      child: Card(
        child: Padding(
          padding: AppSpacing.cardPadding,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Scorecards, last 90 days',
                  style: text.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
              const SizedBox(height: 2),
              Text(
                'Each delivery is measured against what the order promised, or the supplier\'s quoted lead'
                ' time. On time weighs 40, fill 30, quality 20, invoice accuracy 10, over what is known.',
                style: text.bodySmall?.copyWith(color: cs.onSurfaceVariant),
              ),
              const SizedBox(height: 12),
              cards.when(
                loading: () => const LoadingView(label: 'Weighing the period…'),
                error: (e, _) => ErrorView(
                  message: friendlyError(e, fallback: 'Could not load the scorecards.'),
                  onRetry: () => ref.invalidate(supplierScorecardsProvider),
                ),
                data: (list) => list.isEmpty
                    ? const EmptyState(
                        icon: Icons.local_shipping_outlined,
                        title: 'No suppliers to score')
                    : Column(
                        children: [
                          for (final c in list)
                            ListTile(
                              key: Key('scorecard-${c.supplierId}'),
                              dense: true,
                              contentPadding: EdgeInsets.zero,
                              leading: GradeChip(c.grade),
                              title: Text(
                                '${c.supplierName}${c.score == null ? '' : ' · ${_n(c.score)}'}',
                                style: const TextStyle(fontWeight: FontWeight.w600),
                              ),
                              subtitle: Text(
                                c.deliveries.count == 0
                                    ? 'No deliveries in the period'
                                    : '${c.deliveries.count} deliveries · on time ${_n(c.deliveries.onTimePct, unit: ' %')}'
                                        ' · lead ${_n(c.deliveries.avgLeadDays, unit: ' d')}'
                                        '${c.leadTimeDays == null ? '' : ' against ${c.leadTimeDays} quoted'}'
                                        ' · fill ${_n(c.fill.fillRatePct, unit: ' %')}'
                                        ' · returns ${_n(c.quality.returnRatePct, unit: ' %')}',
                              ),
                              trailing: const Icon(Icons.chevron_right),
                              onTap: () => showDialog<void>(
                                context: context,
                                builder: (_) => SupplierScorecardDialog(card: c),
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
}

/// One supplier's card in full, with the deliveries it was made from.
class SupplierScorecardDialog extends ConsumerWidget {
  const SupplierScorecardDialog({super.key, required this.card});
  final SupplierScorecard card;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final deliveries = ref.watch(supplierDeliveriesProvider(card.supplierId));
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final d = card.deliveries;
    Widget line(String label, String value, {Key? key}) => Padding(
          padding: const EdgeInsets.symmetric(vertical: 2),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                flex: 2,
                child: Text(label, style: text.bodyMedium?.copyWith(color: cs.onSurfaceVariant)),
              ),
              const SizedBox(width: 12),
              Expanded(
                flex: 3,
                child: Text(
                  value,
                  key: key,
                  textAlign: TextAlign.end,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
            ],
          ),
        );
    Widget section(String title) => Padding(
          padding: const EdgeInsets.only(top: 12, bottom: 4),
          child: Text(title, style: text.labelLarge),
        );
    return AlertDialog(
      title: Row(
        children: [
          GradeChip(card.grade),
          const SizedBox(width: 10),
          Expanded(child: Text(card.supplierName, overflow: TextOverflow.ellipsis)),
        ],
      ),
      content: SizedBox(
        width: 480,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('${AppFormat.date(card.from)} to ${AppFormat.date(card.to)}', style: text.bodySmall?.copyWith(color: cs.onSurfaceVariant)),
              line('Score', card.score == null ? 'Nothing to judge yet' : _n(card.score),
                  key: const Key('scorecard-score')),
              section('Deliveries'),
              line('Deliveries', '${d.count}'),
              line('On time', d.promised == 0 ? 'Nothing promised' : '${d.onTime} of ${d.promised} (${_n(d.onTimePct, unit: ' %')})'),
              line('Lead time',
                  '${_n(d.avgLeadDays, unit: ' d')} average, ${_n(d.medianLeadDays, unit: ' d')} median, ${_n(d.maxLeadDays, unit: ' d')} longest'
                  '${card.leadTimeDays == null ? '' : ' · quoted ${card.leadTimeDays} d'}'),
              if (d.late > 0) line('Late', '${d.late}, by ${_n(d.avgDaysLate, unit: ' d')} on average'),
              section('Fill'),
              line('Orders finished', '${card.fill.orders}${card.fill.shortClosed > 0 ? ' (${card.fill.shortClosed} closed short)' : ''}'),
              line('Received of ordered',
                  '${_n(card.fill.receivedQty)} of ${_n(card.fill.orderedQty)} (${_n(card.fill.fillRatePct, unit: ' %')})'),
              section('Quality'),
              line('Returned', '${_n(card.quality.returnedQty)} in ${card.quality.returns} returns (${_n(card.quality.returnRatePct, unit: ' %')} of what arrived)'),
              section('Invoices'),
              line('Matched', card.invoices.invoices == 0
                  ? 'None in the period'
                  : '${card.invoices.invoices - card.invoices.flagged} of ${card.invoices.invoices} (${_n(card.invoices.accuracyPct, unit: ' %')})'),
              section('Deliveries measured'),
              deliveries.when(
                loading: () => const LoadingView(label: 'Loading deliveries…'),
                error: (e, _) => ErrorView(
                  message: friendlyError(e, fallback: 'Could not load the deliveries.'),
                  onRetry: () => ref.invalidate(supplierDeliveriesProvider(card.supplierId)),
                ),
                data: (list) => list.isEmpty
                    ? Text('None in the period', style: TextStyle(color: cs.onSurfaceVariant))
                    : Column(
                        children: [
                          for (final x in list)
                            ListTile(
                              key: Key('delivery-${x.id}'),
                              dense: true,
                              contentPadding: EdgeInsets.zero,
                              title: Text(
                                'Order ${shortRef(x.poId)} · ${_n(x.receivedQty)} received ${AppFormat.date(x.receivedAt)}'
                                '${x.complete ? '' : ' (part)'}',
                              ),
                              subtitle: Text(
                                '${x.leadDays} days from order'
                                '${x.lateDays == null ? ' · nothing promised' : x.lateDays! > 0 ? ' · ${x.lateDays} days late' : x.lateDays! < 0 ? ' · ${-x.lateDays!} days early' : ' · on the day'}',
                              ),
                            ),
                        ],
                      ),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Close')),
      ],
    );
  }
}
