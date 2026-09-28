import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';

// ---------------------------------------------------------------------------
// The reductions on offer (03.12), and whether each may be announced.
//
// Directive 98/6/EC art.6a: a price reduction is announced against the lowest
// price of the 30 days before it. pricing-svc records every price applied and
// says, for each promotional price on offer, whether that prior price is
// proven. Where it is not, labels, the storefront and the till show the price
// without a was price, and the storefront banner leaves item promotions out.
// This tab tells a manager which reductions those are, and why.
// ---------------------------------------------------------------------------

/// Why a promotional price may not be shown as a reduction, in a manager's words.
String reductionReason(String? status) => switch (status) {
      'NO_HISTORY' => 'no price was recorded before the promotion began',
      'SHORT_HISTORY' => 'fewer than 30 days of prices are recorded before it',
      'NOT_LOWER' => 'it sold at this price or less within the last 30 days',
      'PENDING' => 'a change to it is still being recorded',
      'UNCERTAIN' => 'what it was charged in the last 30 days could not be established',
      _ => 'its prior price is not known',
    };

class ReductionView {
  final String variantId;
  final String? storeId;
  final double price;
  final double regularPrice;
  final String? promotionName;
  final String currency;
  final double? priorPrice;
  final String? priorPriceStatus;
  final bool priorPriceRequired;
  final bool reductionAnnounceable;

  const ReductionView({
    required this.variantId,
    this.storeId,
    required this.price,
    required this.regularPrice,
    this.promotionName,
    required this.currency,
    this.priorPrice,
    this.priorPriceStatus,
    required this.priorPriceRequired,
    required this.reductionAnnounceable,
  });

  factory ReductionView.fromJson(Map<String, dynamic> j) => ReductionView(
        variantId: j['variantId'] as String? ?? '',
        storeId: j['storeId'] as String?,
        price: (j['price'] as num?)?.toDouble() ?? 0,
        regularPrice: (j['regularPrice'] as num?)?.toDouble() ?? 0,
        promotionName: j['promotionName'] as String?,
        currency: j['currency'] as String? ?? '',
        priorPrice: (j['priorPrice'] as num?)?.toDouble(),
        priorPriceStatus: j['priorPriceStatus'] as String?,
        // Unknown is bound: a reduction is never shown as announceable on a guess.
        priorPriceRequired: j['priorPriceRequired'] as bool? ?? true,
        reductionAnnounceable: j['reductionAnnounceable'] == true,
      );
}

class ReductionsView {
  final String channel;
  final int pending;
  final List<ReductionView> rows;

  const ReductionsView({required this.channel, required this.pending, required this.rows});

  factory ReductionsView.fromJson(Map<String, dynamic> j) => ReductionsView(
        channel: j['channel'] as String? ?? 'ONLINE',
        pending: (j['pending'] as num?)?.toInt() ?? 0,
        rows: [
          for (final r in (j['rows'] as List?) ?? const [])
            ReductionView.fromJson(r as Map<String, dynamic>),
        ],
      );
}

final priceReductionsProvider =
    FutureProvider.autoDispose.family<ReductionsView, String>((ref, channel) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.pricing}/admin/prices/reductions',
    queryParameters: {'channel': channel},
  );
  return ReductionsView.fromJson(resp.data['data'] as Map<String, dynamic>);
});

class PriceReductionsTab extends ConsumerStatefulWidget {
  const PriceReductionsTab({super.key});

  @override
  ConsumerState<PriceReductionsTab> createState() => _PriceReductionsTabState();
}

class _PriceReductionsTabState extends ConsumerState<PriceReductionsTab> {
  String _channel = 'ONLINE';

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final reductions = ref.watch(priceReductionsProvider(_channel));
    return ListView(
      padding: context.pagePadding,
      children: [
        Text('Reductions on offer', style: theme.textTheme.titleMedium),
        const SizedBox(height: 4),
        Text(
          'A reduction is announced against the lowest price of the 30 days before it. '
          'Where that is not proven, labels, the shop and the till show the price without '
          'a was price, and the shop banner leaves item promotions out.',
          style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: 12),
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(value: 'ONLINE', label: Text('Online'), icon: Icon(Icons.language)),
            ButtonSegment(value: 'POS', label: Text('In store'), icon: Icon(Icons.point_of_sale)),
          ],
          selected: {_channel},
          onSelectionChanged: (s) => setState(() => _channel = s.first),
        ),
        const SizedBox(height: 12),
        reductions.when(
          loading: () => const LoadingView(label: 'Loading reductions…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the reductions.'),
            onRetry: () => ref.invalidate(priceReductionsProvider(_channel)),
          ),
          data: (v) => _ReductionList(view: v),
        ),
      ],
    );
  }
}

class _ReductionList extends ConsumerWidget {
  final ReductionsView view;

  const _ReductionList({required this.view});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final names =
        ref.watch(variantLabelsProvider(variantIdsKey(view.rows.map((r) => r.variantId)))).value ??
            const {};
    String money(double v, String c) => AppFormat.money(v, currencyCode: c);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (view.pending > 0)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
              '${view.pending} price ${view.pending == 1 ? 'change is' : 'changes are'} still being '
              'recorded; until then no reduction it touches is announced.',
              key: const Key('reductions-pending'),
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error),
            ),
          ),
        if (view.rows.isEmpty)
          const Text('No reduced prices on offer here.', key: Key('reductions-empty'))
        else
          Card(
            child: Column(
              children: [
                for (final r in view.rows)
                  ListTile(
                    key: Key('reduction-${r.variantId}-${r.storeId ?? 'all'}'),
                    leading: Icon(
                      r.reductionAnnounceable ? Icons.check_circle_outline : Icons.block,
                      color: r.reductionAnnounceable ? null : theme.colorScheme.error,
                    ),
                    title: Text(variantDisplayName(r.variantId, names)),
                    subtitle: Text([
                      '${r.promotionName ?? 'Promotion'}: ${money(r.price, r.currency)}'
                          ' (regular ${money(r.regularPrice, r.currency)})',
                      if (r.storeId != null) 'at one store only',
                      if (!r.priorPriceRequired)
                        'the prior-price rule does not bind here'
                      else if (r.reductionAnnounceable && r.priorPrice != null)
                        'may be announced · was ${money(r.priorPrice!, r.currency)}'
                      else
                        'not to be shown as a reduction: ${reductionReason(r.priorPriceStatus)}',
                    ].join(' · ')),
                  ),
              ],
            ),
          ),
      ],
    );
  }
}
