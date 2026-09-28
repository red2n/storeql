import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'price_reductions_tab.dart';
import 'providers/admin_providers.dart';
import 'widgets/variant_picker.dart';

// ---------------------------------------------------------------------------
// Unit pricing for the business (03.13): shelf-edge labels carrying the unit
// price beside the selling price — and beside a promotional price while one
// runs — and the priced items that cannot show one because no measure was
// declared.
// ---------------------------------------------------------------------------

class UnitPriceView {
  final double amount;
  final String label;

  const UnitPriceView({required this.amount, required this.label});

  static UnitPriceView? fromJson(Object? j) {
    if (j is! Map<String, dynamic>) return null;
    final amount = (j['amount'] as num?)?.toDouble();
    return amount == null ? null : UnitPriceView(amount: amount, label: j['label'] as String? ?? '');
  }
}

class ShelfLabelView {
  final String variantId;
  final bool priced;
  final String currency;
  final double? regularPrice;
  final UnitPriceView? regularUnitPrice;
  final double? promotionalPrice;
  final UnitPriceView? promotionalUnitPrice;
  final String? promotionName;
  final bool measureDeclared;
  final bool unitPriceRequired;
  final double? priorPrice;
  final String? priorPriceStatus;
  final bool reductionAnnounceable;

  const ShelfLabelView({
    required this.variantId,
    required this.priced,
    required this.currency,
    this.regularPrice,
    this.regularUnitPrice,
    this.promotionalPrice,
    this.promotionalUnitPrice,
    this.promotionName,
    required this.measureDeclared,
    required this.unitPriceRequired,
    this.priorPrice,
    this.priorPriceStatus,
    this.reductionAnnounceable = false,
  });

  factory ShelfLabelView.fromJson(Map<String, dynamic> j) => ShelfLabelView(
        variantId: j['variantId'] as String? ?? '',
        priced: j['priced'] as bool? ?? false,
        currency: j['currency'] as String? ?? '',
        regularPrice: (j['regularPrice'] as num?)?.toDouble(),
        regularUnitPrice: UnitPriceView.fromJson(j['regularUnitPrice']),
        promotionalPrice: (j['promotionalPrice'] as num?)?.toDouble(),
        promotionalUnitPrice: UnitPriceView.fromJson(j['promotionalUnitPrice']),
        promotionName: j['promotionName'] as String?,
        measureDeclared: j['measureDeclared'] as bool? ?? false,
        unitPriceRequired: j['unitPriceRequired'] as bool? ?? false,
        priorPrice: (j['priorPrice'] as num?)?.toDouble(),
        priorPriceStatus: j['priorPriceStatus'] as String?,
        reductionAnnounceable: j['reductionAnnounceable'] as bool? ?? false,
      );
}

class UnitPriceGapsView {
  final bool required;
  final List<({String variantId, bool catalogued})> gaps;

  const UnitPriceGapsView({required this.required, required this.gaps});

  factory UnitPriceGapsView.fromJson(Map<String, dynamic> j) => UnitPriceGapsView(
        required: j['required'] as bool? ?? true,
        gaps: [
          for (final g in (j['gaps'] as List?) ?? const [])
            (
              variantId: (g as Map)['variantId'] as String? ?? '',
              catalogued: g['catalogued'] as bool? ?? false,
            ),
        ],
      );
}

final unitPriceGapsProvider = FutureProvider.autoDispose<UnitPriceGapsView>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.pricing}/admin/unit-pricing/gaps');
  return UnitPriceGapsView.fromJson(resp.data['data'] as Map<String, dynamic>);
});

String _money(double amount, String currency) =>
    AppFormat.money(amount, currencyCode: currency);

class ShelfLabelsTab extends ConsumerStatefulWidget {
  /// Variants already on the sheet, for opening it from elsewhere.
  final List<String> initialVariantIds;

  const ShelfLabelsTab({super.key, this.initialVariantIds = const []});

  @override
  ConsumerState<ShelfLabelsTab> createState() => _ShelfLabelsTabState();
}

class _ShelfLabelsTabState extends ConsumerState<ShelfLabelsTab> {
  static const _maxLabels = 200;
  String? _productId;
  String? _variantId;
  late final List<String> _ids = [...widget.initialVariantIds];
  List<ShelfLabelView>? _labels;
  bool _busy = false;
  String? _error;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final names = ref.watch(variantLabelsProvider(variantIdsKey(_ids))).value ?? const {};
    final canAdd =
        _variantId != null && !_ids.contains(_variantId) && _ids.length < _maxLabels;
    return ListView(
      padding: context.pagePadding,
      children: [
        Text('Shelf-edge labels', style: theme.textTheme.titleMedium),
        const SizedBox(height: 4),
        Text(
          'The selling price with its unit price per kg, litre, metre, m² or item and, '
          'while a promotion runs, the promotional price with its own unit price beside it '
          '(Price Marking Order 2004; Directive 98/6/EC).',
          style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: 12),
        VariantPicker(
          productId: _productId,
          variantId: _variantId,
          onProduct: (v) => setState(() {
            _productId = v;
            _variantId = null;
          }),
          onVariant: (v) => setState(() => _variantId = v),
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            FilledButton.tonal(
              key: const Key('label-add'),
              onPressed: canAdd ? () => setState(() => _ids.add(_variantId!)) : null,
              child: const Text('Add to sheet'),
            ),
            FilledButton.icon(
              key: const Key('label-make'),
              onPressed: _ids.isEmpty || _busy ? null : _make,
              icon: const Icon(Icons.local_offer_outlined),
              label: Text('Make ${_ids.length} ${_ids.length == 1 ? 'label' : 'labels'}'),
            ),
          ],
        ),
        if (_ids.isNotEmpty) ...[
          const SizedBox(height: 8),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final id in _ids)
                InputChip(
                  key: Key('label-chip-$id'),
                  label: Text(variantDisplayName(id, names)),
                  onDeleted: () => setState(() {
                    _ids.remove(id);
                    _labels = null;
                  }),
                ),
            ],
          ),
        ],
        if (_error != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(_error!,
                key: const Key('label-error'),
                style: TextStyle(color: theme.colorScheme.error)),
          ),
        if (_labels != null) ...[
          const SizedBox(height: 16),
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [for (final l in _labels!) _label(context, l, names)],
          ),
        ],
      ],
    );
  }

  Future<void> _make() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.pricing}/prices/shelf-labels',
        data: {'variantIds': _ids, 'channel': 'POS'},
      );
      final list = [
        for (final j in (resp.data['data'] as List?) ?? const [])
          ShelfLabelView.fromJson(j as Map<String, dynamic>)
      ];
      if (mounted) setState(() => _labels = list);
    } catch (e) {
      if (mounted) {
        setState(() => _error = friendlyError(e, fallback: 'Could not make the labels.'));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Widget _label(BuildContext context, ShelfLabelView l, Map<String, VariantLabel> names) {
    final theme = Theme.of(context);
    final big = theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold);
    final small = theme.textTheme.bodySmall;
    final promoted = l.promotionalPrice != null;
    return SizedBox(
      width: 240,
      child: Card(
        key: Key('label-${l.variantId}'),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(variantDisplayName(l.variantId, names), style: theme.textTheme.titleSmall),
              if (variantSku(l.variantId, names).isNotEmpty)
                Text(variantSku(l.variantId, names), style: small),
              const SizedBox(height: 8),
              if (!l.priced)
                Text('No price in force', key: const Key('label-unpriced'), style: small)
              else if (promoted) ...[
                Text(_money(l.promotionalPrice!, l.currency),
                    key: const Key('label-promo-price'), style: big),
                if (l.promotionalUnitPrice != null)
                  Text(
                      '${_money(l.promotionalUnitPrice!.amount, l.currency)} ${l.promotionalUnitPrice!.label}',
                      key: const Key('label-promo-unit')),
                // 03.12: the was price is the lowest of the 30 days before the reduction, shown
                // only when pricing-svc says the reduction may be announced.
                if (l.reductionAnnounceable && l.priorPrice != null)
                  Text(
                      '${l.promotionName ?? 'Promotion'} · was ${_money(l.priorPrice!, l.currency)}',
                      key: const Key('label-was'),
                      style: small)
                else
                  Text(
                    'Not to be shown as a reduction: ${reductionReason(l.priorPriceStatus)}.',
                    key: const Key('label-not-announceable'),
                    style: small?.copyWith(color: theme.colorScheme.error),
                  ),
              ] else ...[
                Text(_money(l.regularPrice!, l.currency), key: const Key('label-price'), style: big),
                if (l.regularUnitPrice != null)
                  Text('${_money(l.regularUnitPrice!.amount, l.currency)} ${l.regularUnitPrice!.label}',
                      key: const Key('label-regular-unit')),
              ],
              if (l.priced && !l.measureDeclared)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text(
                    l.unitPriceRequired
                        ? 'No unit price: no measure is declared, and a unit price is law here. '
                            'Add the net content and unit on the variant.'
                        : 'No unit price: no measure is declared.',
                    key: const Key('label-no-measure'),
                    style: small?.copyWith(
                        color: l.unitPriceRequired ? theme.colorScheme.error : null),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class UnitPricingGapsTab extends ConsumerWidget {
  const UnitPricingGapsTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final gaps = ref.watch(unitPriceGapsProvider);
    return gaps.when(
      loading: () => const LoadingView(label: 'Loading unit pricing…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load the unit pricing gaps.'),
        onRetry: () => ref.invalidate(unitPriceGapsProvider),
      ),
      data: (g) {
        final names = ref
                .watch(variantLabelsProvider(variantIdsKey(g.gaps.map((x) => x.variantId))))
                .value ??
            const {};
        return ListView(
          padding: context.pagePadding,
          children: [
            Text(
              g.required
                  ? 'A unit price is law for this business: every priced item needs its measure '
                      'declared, or its unit price cannot be shown.'
                  : 'A unit price is not law for this business today. Items below show none.',
              key: const Key('gaps-required'),
            ),
            const SizedBox(height: 12),
            if (g.gaps.isEmpty)
              const Text('Every priced item has a declared measure.', key: Key('gaps-empty'))
            else
              Card(
                child: Column(
                  children: [
                    for (final x in g.gaps)
                      ListTile(
                        key: Key('gap-${x.variantId}'),
                        leading: Icon(Icons.straighten,
                            color: g.required ? theme.colorScheme.error : null),
                        title: Text(variantDisplayName(x.variantId, names)),
                        subtitle: Text([
                          if (variantSku(x.variantId, names).isNotEmpty)
                            variantSku(x.variantId, names),
                          'add its net content and unit on the variant',
                          if (!x.catalogued) 'not yet in the catalogue feed',
                        ].join(' · ')),
                      ),
                  ],
                ),
              ),
          ],
        );
      },
    );
  }
}
