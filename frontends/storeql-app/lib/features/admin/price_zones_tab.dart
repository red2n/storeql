import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/theme.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/widgets/loading_view.dart';
import 'pricing_providers.dart';
import 'providers/admin_providers.dart';
import 'widgets/variant_picker.dart';

// ---------------------------------------------------------------------------
// Price zones and competitor-driven repricing (03.x).
//
// A zone is the stores that price alike; a price list bound to it is what
// those stores charge, and every other store falls back to the tenant-wide
// list. What rivals charge is recorded as seen; a rule on a list turns the
// freshest rival price into a proposal, and a manager applies or dismisses it.
// Nothing here changes a price on its own.
// ---------------------------------------------------------------------------

class PriceZone {
  final String id;
  final String name;
  final String? description;
  final List<String> storeIds;
  const PriceZone({
    required this.id,
    required this.name,
    this.description,
    required this.storeIds,
  });

  factory PriceZone.fromJson(Map<String, dynamic> j) => PriceZone(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        description: j['description'] as String?,
        storeIds: ((j['storeIds'] as List?) ?? const []).map((e) => e.toString()).toList(),
      );
}

class CompetitorPrice {
  final String id;
  final String variantId;
  final String competitor;
  final double price;
  final String currency;
  final String? zoneId;
  final String observedOn;
  final String source;
  const CompetitorPrice({
    required this.id,
    required this.variantId,
    required this.competitor,
    required this.price,
    required this.currency,
    this.zoneId,
    required this.observedOn,
    required this.source,
  });

  factory CompetitorPrice.fromJson(Map<String, dynamic> j) => CompetitorPrice(
        id: j['id'] as String? ?? '',
        variantId: j['variantId'] as String? ?? '',
        competitor: j['competitor'] as String? ?? '-',
        price: (j['price'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
        zoneId: j['zoneId'] as String?,
        observedOn: j['observedOn'] as String? ?? '',
        source: j['source'] as String? ?? 'MANUAL',
      );
}

class RepricingRule {
  final String id;
  final String name;
  final String priceListId;
  final String? zoneId;
  final String strategy;
  final double value;
  final double floorPercent;
  final String rounding;
  final int maxAgeDays;
  final bool active;
  const RepricingRule({
    required this.id,
    required this.name,
    required this.priceListId,
    this.zoneId,
    required this.strategy,
    required this.value,
    required this.floorPercent,
    required this.rounding,
    required this.maxAgeDays,
    required this.active,
  });

  factory RepricingRule.fromJson(Map<String, dynamic> j) => RepricingRule(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        priceListId: j['priceListId'] as String? ?? '',
        zoneId: j['zoneId'] as String?,
        strategy: j['strategy'] as String? ?? 'MATCH_LOWEST',
        value: (j['value'] as num?)?.toDouble() ?? 0,
        floorPercent: (j['floorPercent'] as num?)?.toDouble() ?? 0,
        rounding: j['rounding'] as String? ?? 'NONE',
        maxAgeDays: (j['maxAgeDays'] as num?)?.toInt() ?? 14,
        active: j['active'] as bool? ?? true,
      );

  /// The rule in a sentence: "undercut the lowest rival by 1%, to a .99, never below 80%".
  String describe() {
    final how = switch (strategy) {
      'UNDERCUT_PERCENT' => 'undercut the lowest rival by ${_trim(value)}%',
      'UNDERCUT_AMOUNT' => 'undercut the lowest rival by ${_trim(value)}',
      _ => 'match the lowest rival',
    };
    final tidy = rounding == 'ENDING_99' ? ', to a .99' : '';
    return '$how$tidy, never below ${_trim(floorPercent)}% of the current price; '
        'rivals seen within $maxAgeDays days';
  }
}

class RepricingProposal {
  final String id;
  final String ruleId;
  final String variantId;
  final double currentPrice;
  final String competitor;
  final double competitorPrice;
  final String observedOn;
  final double proposedPrice;
  final String currency;
  final String status;
  const RepricingProposal({
    required this.id,
    required this.ruleId,
    required this.variantId,
    required this.currentPrice,
    required this.competitor,
    required this.competitorPrice,
    required this.observedOn,
    required this.proposedPrice,
    required this.currency,
    required this.status,
  });

  factory RepricingProposal.fromJson(Map<String, dynamic> j) => RepricingProposal(
        id: j['id'] as String? ?? '',
        ruleId: j['ruleId'] as String? ?? '',
        variantId: j['variantId'] as String? ?? '',
        currentPrice: (j['currentPrice'] as num?)?.toDouble() ?? 0,
        competitor: j['competitor'] as String? ?? '-',
        competitorPrice: (j['competitorPrice'] as num?)?.toDouble() ?? 0,
        observedOn: j['observedOn'] as String? ?? '',
        proposedPrice: (j['proposedPrice'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
        status: j['status'] as String? ?? 'PROPOSED',
      );
}

String _trim(double v) {
  var t = v.toStringAsFixed(4);
  t = t.replaceFirst(RegExp(r'0+$'), '');
  return t.endsWith('.') ? t.substring(0, t.length - 1) : t;
}

String _money(double v, String currency) => AppFormat.money(v, currencyCode: currency);

/// The day a price was seen, as a date: `20 Sept 2026`.
String _seen(String iso) => AppFormat.date(iso);

final priceZonesProvider = FutureProvider.autoDispose<List<PriceZone>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.pricing}/admin/price-zones');
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => PriceZone.fromJson(e as Map<String, dynamic>))
      .toList();
});

final competitorPricesProvider = FutureProvider.autoDispose<List<CompetitorPrice>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.pricing}/admin/competitor-prices', queryParameters: {'limit': 50});
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => CompetitorPrice.fromJson(e as Map<String, dynamic>))
      .toList();
});

final repricingRulesProvider = FutureProvider.autoDispose<List<RepricingRule>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.pricing}/admin/repricing/rules');
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => RepricingRule.fromJson(e as Map<String, dynamic>))
      .toList();
});

final repricingProposalsProvider = FutureProvider.autoDispose<List<RepricingProposal>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.pricing}/admin/repricing/proposals');
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => RepricingProposal.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// The Pricing screen's "Zones & repricing" tab.
class PriceZonesTab extends ConsumerWidget {
  const PriceZonesTab({super.key, required this.management});

  /// Whether the signed-in person may create zones and rules and decide
  /// proposals. Staff who may not still read the tab.
  final bool management;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return ListView(
      padding: context.pagePadding,
      children: [
        _ZonesSection(management: management),
        const SizedBox(height: 12),
        _CompetitorSection(management: management),
        const SizedBox(height: 12),
        _RepricingSection(management: management),
      ],
    );
  }
}

Widget _sectionHeader(BuildContext context, String title, String detail, Widget? action) {
  final cs = Theme.of(context).colorScheme;
  final text = Theme.of(context).textTheme;
  return Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Expanded(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: text.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
            const SizedBox(height: 2),
            Text(detail, style: text.bodySmall?.copyWith(color: cs.onSurfaceVariant)),
          ],
        ),
      ),
      if (action != null) ...[const SizedBox(width: 12), action],
    ],
  );
}

Widget _compactState(BuildContext context, String text, {bool error = false}) {
  final cs = Theme.of(context).colorScheme;
  return Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Text(text, style: TextStyle(color: error ? cs.error : cs.onSurfaceVariant)),
  );
}

void _say(BuildContext context, String text) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
}

// ── Zones ────────────────────────────────────────────────────────────────────

class _ZonesSection extends ConsumerWidget {
  const _ZonesSection({required this.management});
  final bool management;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final zones = ref.watch(priceZonesProvider);
    final stores = ref.watch(storesProvider).value ?? const <StoreInfo>[];
    String storeName(String id) =>
        stores.where((s) => s.id == id).map((s) => s.name).firstOrNull ?? shortRef(id);
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _sectionHeader(
              context,
              'Price zones',
              'The stores that price alike. A price list bound to a zone is what its stores charge;'
                  ' every other store falls back to the tenant-wide list. A store sits in one zone at most.',
              management
                  ? FilledButton.icon(
                      key: const Key('zone-new'),
                      onPressed: () => showDialog(
                        context: context,
                        builder: (_) => const NewPriceZoneDialog(),
                      ),
                      icon: const Icon(Icons.add),
                      label: const Text('New zone'),
                    )
                  : null,
            ),
            const SizedBox(height: 8),
            zones.when(
              loading: () => _compactState(context, 'Loading zones…'),
              error: (e, _) => _compactState(
                context,
                friendlyError(e, fallback: 'Could not load price zones.'),
                error: true,
              ),
              data: (list) {
                if (list.isEmpty) {
                  return _compactState(context, 'No price zones yet: every store charges the tenant-wide price.');
                }
                return Column(
                  children: [
                    for (final z in list)
                      ListTile(
                        key: Key('zone-${z.id}'),
                        contentPadding: EdgeInsets.zero,
                        leading: CircleAvatar(
                          backgroundColor: cs.secondaryContainer,
                          child: Icon(Icons.map_outlined, color: cs.onSecondaryContainer),
                        ),
                        title: Text(z.name, style: const TextStyle(fontWeight: FontWeight.w600)),
                        subtitle: Padding(
                          padding: const EdgeInsets.only(top: 4),
                          child: Wrap(
                            spacing: 6,
                            runSpacing: 6,
                            children: [
                              if (z.description != null && z.description!.isNotEmpty)
                                Text(z.description!),
                              if (z.storeIds.isEmpty)
                                Text('No stores yet', style: TextStyle(color: cs.onSurfaceVariant)),
                              for (final id in z.storeIds)
                                Container(
                                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                                  decoration: BoxDecoration(
                                    color: cs.surfaceContainerHigh,
                                    borderRadius: AppRadius.chip,
                                  ),
                                  child: Text(storeName(id), style: const TextStyle(fontSize: 12)),
                                ),
                            ],
                          ),
                        ),
                        trailing: management
                            ? TextButton(
                                key: Key('zone-stores-${z.id}'),
                                onPressed: () => showDialog(
                                  context: context,
                                  builder: (_) => ZoneStoresDialog(zone: z, stores: stores),
                                ),
                                child: const Text('Stores'),
                              )
                            : null,
                      ),
                  ],
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}

/// Name a zone. Its stores come next, from the Stores button on the row.
class NewPriceZoneDialog extends ConsumerStatefulWidget {
  const NewPriceZoneDialog({super.key});

  @override
  ConsumerState<NewPriceZoneDialog> createState() => _NewPriceZoneDialogState();
}

class _NewPriceZoneDialogState extends ConsumerState<NewPriceZoneDialog> {
  final _name = TextEditingController();
  final _description = TextEditingController();
  bool _busy = false;
  String? _refusal;

  @override
  void dispose() {
    _name.dispose();
    _description.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_name.text.trim().isEmpty) {
      setState(() => _refusal = 'Give the zone a name.');
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.pricing}/admin/price-zones',
        data: {
          'name': _name.text.trim(),
          if (_description.text.trim().isNotEmpty) 'description': _description.text.trim(),
        },
      );
      ref.invalidate(priceZonesProvider);
      if (!mounted) return;
      _say(context, 'Zone ${_name.text.trim()} made. Add its stores from the Stores button.');
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not make the zone.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('New price zone'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              key: const Key('zone-name'),
              controller: _name,
              decoration: const InputDecoration(labelText: 'Name *', hintText: 'North'),
            ),
            const SizedBox(height: 8),
            TextField(
              key: const Key('zone-description'),
              controller: _description,
              decoration: const InputDecoration(labelText: 'Description'),
            ),
            if (_refusal != null) ...[
              const SizedBox(height: 12),
              Text(_refusal!, key: const Key('zone-refusal'), style: TextStyle(color: cs.error)),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('zone-save'), onPressed: _busy ? null : _save, child: const Text('Create')),
      ],
    );
  }
}

/// Tick the stores in a zone. A store ticked here leaves any other zone.
class ZoneStoresDialog extends ConsumerStatefulWidget {
  const ZoneStoresDialog({super.key, required this.zone, required this.stores});
  final PriceZone zone;
  final List<StoreInfo> stores;

  @override
  ConsumerState<ZoneStoresDialog> createState() => _ZoneStoresDialogState();
}

class _ZoneStoresDialogState extends ConsumerState<ZoneStoresDialog> {
  late final Set<String> _chosen = {...widget.zone.storeIds};
  bool _busy = false;
  String? _refusal;

  Future<void> _save() async {
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.pricing}/admin/price-zones/${widget.zone.id}/stores',
        data: {'storeIds': _chosen.toList()},
      );
      ref.invalidate(priceZonesProvider);
      if (!mounted) return;
      _say(context, '${widget.zone.name}: ${_chosen.length} store${_chosen.length == 1 ? '' : 's'}.');
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not set the stores.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text('Stores in ${widget.zone.name}'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (widget.stores.isEmpty)
              _compactState(context, 'No stores to choose from.')
            else
              for (final s in widget.stores)
                CheckboxListTile(
                  key: Key('zone-store-${s.id}'),
                  contentPadding: EdgeInsets.zero,
                  value: _chosen.contains(s.id),
                  title: Text(s.name),
                  subtitle: Text(s.code),
                  onChanged: (v) => setState(() {
                    if (v == true) {
                      _chosen.add(s.id);
                    } else {
                      _chosen.remove(s.id);
                    }
                  }),
                ),
            if (_refusal != null) ...[
              const SizedBox(height: 12),
              Text(_refusal!, key: const Key('zone-stores-refusal'), style: TextStyle(color: cs.error)),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('zone-stores-save'), onPressed: _busy ? null : _save, child: const Text('Save')),
      ],
    );
  }
}

// ── Competitor prices ────────────────────────────────────────────────────────

class _CompetitorSection extends ConsumerWidget {
  const _CompetitorSection({required this.management});
  final bool management;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final seen = ref.watch(competitorPricesProvider);
    final zones = ref.watch(priceZonesProvider).value ?? const <PriceZone>[];
    String zoneName(String? id) =>
        id == null ? 'everywhere' : zones.where((z) => z.id == id).map((z) => z.name).firstOrNull ?? 'a zone';
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _sectionHeader(
              context,
              'Competitor prices',
              'What rivals charge, as seen: per product, on a day, in your own currency, optionally in one'
                  ' zone. Each sighting is kept; the freshest per rival is what a rule answers.',
              management
                  ? FilledButton.icon(
                      key: const Key('competitor-record'),
                      onPressed: () => showDialog(
                        context: context,
                        builder: (_) => RecordCompetitorPriceDialog(zones: zones),
                      ),
                      icon: const Icon(Icons.add),
                      label: const Text('Record a price'),
                    )
                  : null,
            ),
            const SizedBox(height: 8),
            seen.when(
              loading: () => _compactState(context, 'Loading…'),
              error: (e, _) => _compactState(
                context,
                friendlyError(e, fallback: 'Could not load competitor prices.'),
                error: true,
              ),
              data: (list) {
                if (list.isEmpty) return _compactState(context, 'No competitor prices recorded yet.');
                // Each sighting by its product's name; the end of its id only
                // while the names load.
                final labels = ref
                        .watch(variantLabelsProvider(
                            variantIdsKey(list.take(20).map((c) => c.variantId))))
                        .value ??
                    const <String, VariantLabel>{};
                return Column(
                  children: [
                    for (final c in list.take(20))
                      ListTile(
                        key: Key('competitor-${c.id}'),
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(Icons.storefront_outlined, color: cs.onSurfaceVariant),
                        title: Text('${c.competitor} · ${_money(c.price, c.currency)}'),
                        subtitle: Text(
                          '${variantDisplayName(c.variantId, labels)} · seen ${_seen(c.observedOn)} ${zoneName(c.zoneId)}'
                          '${c.source == 'IMPORT' ? ' · imported' : ''}',
                        ),
                      ),
                  ],
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}

/// One sighting: which product, which rival, how much, where and when.
class RecordCompetitorPriceDialog extends ConsumerStatefulWidget {
  const RecordCompetitorPriceDialog({super.key, required this.zones});
  final List<PriceZone> zones;

  @override
  ConsumerState<RecordCompetitorPriceDialog> createState() => _RecordCompetitorPriceDialogState();
}

class _RecordCompetitorPriceDialogState extends ConsumerState<RecordCompetitorPriceDialog> {
  String? _productId;
  String? _variantId;
  String? _zoneId;
  final _competitor = TextEditingController();
  final _price = TextEditingController();
  final _observedOn = TextEditingController();
  bool _busy = false;
  String? _refusal;

  @override
  void dispose() {
    _competitor.dispose();
    _price.dispose();
    _observedOn.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_variantId == null) {
      setState(() => _refusal = 'Pick the product and variant the rival sells.');
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.pricing}/admin/competitor-prices',
        data: {
          'variantId': _variantId,
          'competitor': _competitor.text.trim(),
          'price': double.tryParse(_price.text.trim()) ?? -1,
          if (_zoneId != null) 'zoneId': _zoneId,
          if (_observedOn.text.trim().isNotEmpty) 'observedOn': _observedOn.text.trim(),
        },
      );
      ref.invalidate(competitorPricesProvider);
      if (!mounted) return;
      _say(context, 'Recorded: ${_competitor.text.trim()} at ${_price.text.trim()}.');
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not record the price.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Record a competitor price'),
      content: SizedBox(
        width: 440,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
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
              TextField(
                key: const Key('competitor-name'),
                controller: _competitor,
                decoration: const InputDecoration(labelText: 'Competitor *'),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const Key('competitor-price'),
                controller: _price,
                decoration: const InputDecoration(labelText: 'Their price *', helperText: 'In your own currency'),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
              ),
              const SizedBox(height: 8),
              DropdownButtonFormField<String?>(
                key: const Key('competitor-zone'),
                initialValue: _zoneId,
                decoration: const InputDecoration(labelText: 'Seen in'),
                items: [
                  const DropdownMenuItem<String?>(value: null, child: Text('Everywhere')),
                  for (final z in widget.zones) DropdownMenuItem<String?>(value: z.id, child: Text(z.name)),
                ],
                onChanged: (v) => setState(() => _zoneId = v),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const Key('competitor-observed'),
                controller: _observedOn,
                decoration: const InputDecoration(labelText: 'Seen on (yyyy-MM-dd)', helperText: 'Blank: today'),
              ),
              if (_refusal != null) ...[
                const SizedBox(height: 12),
                Text(_refusal!, key: const Key('competitor-refusal'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('competitor-save'), onPressed: _busy ? null : _save, child: const Text('Record')),
      ],
    );
  }
}

// ── Repricing ────────────────────────────────────────────────────────────────

class _RepricingSection extends ConsumerWidget {
  const _RepricingSection({required this.management});
  final bool management;

  Future<void> _run(BuildContext context, WidgetRef ref, RepricingRule rule) async {
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .post('/${ApiConstants.pricing}/admin/repricing/rules/${rule.id}/run', data: const {});
      final data = resp.data['data'] as Map<String, dynamic>? ?? const {};
      ref.invalidate(repricingProposalsProvider);
      if (!context.mounted) return;
      _say(
        context,
        '${rule.name}: ${data['proposed'] ?? 0} proposal${data['proposed'] == 1 ? '' : 's'} from'
        ' ${data['examined'] ?? 0} priced item${data['examined'] == 1 ? '' : 's'} with a fresh rival price.',
      );
    } on DioException catch (e) {
      if (!context.mounted) return;
      _say(context, friendlyError(e, fallback: 'Could not run the rule.'));
    }
  }

  Future<void> _decide(BuildContext context, WidgetRef ref, RepricingProposal p, String verb) async {
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post('/${ApiConstants.pricing}/admin/repricing/proposals/${p.id}/$verb', data: const {});
      ref.invalidate(repricingProposalsProvider);
      ref.invalidate(priceListsProvider);
      if (!context.mounted) return;
      _say(
        context,
        verb == 'apply'
            ? 'Applied: ${_money(p.proposedPrice, p.currency)} against ${p.competitor}.'
            : 'Dismissed: the price stays at ${_money(p.currentPrice, p.currency)}.',
      );
    } on DioException catch (e) {
      if (!context.mounted) return;
      _say(context, friendlyError(e, fallback: 'Could not decide the proposal.'));
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final rules = ref.watch(repricingRulesProvider);
    final proposals = ref.watch(repricingProposalsProvider);
    final lists = ref.watch(priceListsProvider).value ?? const <PriceList>[];
    String listName(String id) => lists.where((l) => l.id == id).map((l) => l.name).firstOrNull ?? 'a price list';
    final cs = Theme.of(context).colorScheme;
    final status = context.status;
    return Card(
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _sectionHeader(
              context,
              'Repricing',
              'A rule on a price list answers the lowest fresh rival price: match it or undercut it, rounded'
                  ' to a .99 or not, never below a floor that is a share of the current price (this service holds'
                  ' no cost, so the floor protects the price, not a margin). A run proposes; you apply.',
              management
                  ? FilledButton.icon(
                      key: const Key('rule-new'),
                      onPressed: () => showDialog(
                        context: context,
                        builder: (_) => NewRepricingRuleDialog(lists: lists),
                      ),
                      icon: const Icon(Icons.add),
                      label: const Text('New rule'),
                    )
                  : null,
            ),
            const SizedBox(height: 8),
            rules.when(
              loading: () => _compactState(context, 'Loading rules…'),
              error: (e, _) => _compactState(
                context,
                friendlyError(e, fallback: 'Could not load repricing rules.'),
                error: true,
              ),
              data: (list) {
                if (list.isEmpty) return _compactState(context, 'No repricing rules yet.');
                return Column(
                  children: [
                    for (final r in list)
                      ListTile(
                        key: Key('rule-${r.id}'),
                        contentPadding: EdgeInsets.zero,
                        leading: CircleAvatar(
                          backgroundColor: cs.tertiaryContainer,
                          child: Icon(Icons.rule_outlined, color: cs.onTertiaryContainer),
                        ),
                        title: Text(r.name, style: const TextStyle(fontWeight: FontWeight.w600)),
                        subtitle: Text('${listName(r.priceListId)} · ${r.describe()}'),
                        trailing: management
                            ? FilledButton.tonalIcon(
                                key: Key('rule-run-${r.id}'),
                                onPressed: () => _run(context, ref, r),
                                icon: const Icon(Icons.play_arrow_outlined, size: 18),
                                label: const Text('Run'),
                              )
                            : null,
                      ),
                  ],
                );
              },
            ),
            const Divider(height: 24),
            Text('Open proposals', style: Theme.of(context).textTheme.titleSmall),
            const SizedBox(height: 4),
            proposals.when(
              loading: () => _compactState(context, 'Loading proposals…'),
              error: (e, _) => _compactState(
                context,
                friendlyError(e, fallback: 'Could not load proposals.'),
                error: true,
              ),
              data: (list) {
                if (list.isEmpty) return _compactState(context, 'Nothing proposed. Run a rule after recording rival prices.');
                final labels = ref
                        .watch(variantLabelsProvider(
                            variantIdsKey(list.map((p) => p.variantId))))
                        .value ??
                    const <String, VariantLabel>{};
                return Column(
                  children: [
                    for (final p in list)
                      ListTile(
                        key: Key('proposal-${p.id}'),
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(Icons.trending_down, color: status.warning),
                        title: Text(
                          '${_money(p.currentPrice, p.currency)} → ${_money(p.proposedPrice, p.currency)}',
                          style: const TextStyle(fontWeight: FontWeight.w600),
                        ),
                        subtitle: Text(
                          '${variantDisplayName(p.variantId, labels)} · ${p.competitor} at '
                          '${_money(p.competitorPrice, p.currency)}, seen ${_seen(p.observedOn)}',
                        ),
                        trailing: management
                            ? Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  TextButton(
                                    key: Key('proposal-dismiss-${p.id}'),
                                    onPressed: () => _decide(context, ref, p, 'dismiss'),
                                    child: const Text('Dismiss'),
                                  ),
                                  const SizedBox(width: 4),
                                  FilledButton(
                                    key: Key('proposal-apply-${p.id}'),
                                    onPressed: () => _decide(context, ref, p, 'apply'),
                                    child: const Text('Apply'),
                                  ),
                                ],
                              )
                            : null,
                      ),
                  ],
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}

/// A rule: which list, how to answer the rival, the floor, the rounding.
class NewRepricingRuleDialog extends ConsumerStatefulWidget {
  const NewRepricingRuleDialog({super.key, required this.lists});
  final List<PriceList> lists;

  @override
  ConsumerState<NewRepricingRuleDialog> createState() => _NewRepricingRuleDialogState();
}

class _NewRepricingRuleDialogState extends ConsumerState<NewRepricingRuleDialog> {
  final _name = TextEditingController();
  final _value = TextEditingController(text: '0');
  final _floor = TextEditingController(text: '80');
  final _maxAge = TextEditingController(text: '14');
  String? _priceListId;
  String _strategy = 'MATCH_LOWEST';
  String _rounding = 'NONE';
  bool _busy = false;
  String? _refusal;

  @override
  void dispose() {
    _name.dispose();
    _value.dispose();
    _floor.dispose();
    _maxAge.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_priceListId == null) {
      setState(() => _refusal = 'Pick the price list the rule writes into.');
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.pricing}/admin/repricing/rules',
        data: {
          'name': _name.text.trim(),
          'priceListId': _priceListId,
          'strategy': _strategy,
          'value': double.tryParse(_value.text.trim()) ?? 0,
          'floorPercent': double.tryParse(_floor.text.trim()) ?? 0,
          'rounding': _rounding,
          'maxAgeDays': int.tryParse(_maxAge.text.trim()) ?? 14,
        },
      );
      ref.invalidate(repricingRulesProvider);
      if (!mounted) return;
      _say(context, 'Rule ${_name.text.trim()} made. Run it to see proposals.');
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not make the rule.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final undercuts = _strategy != 'MATCH_LOWEST';
    return AlertDialog(
      title: const Text('New repricing rule'),
      content: SizedBox(
        width: 440,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                key: const Key('rule-name'),
                controller: _name,
                decoration: const InputDecoration(labelText: 'Name *', hintText: 'North undercut'),
              ),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                key: const Key('rule-list'),
                initialValue: _priceListId,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Price list *', helperText: "The rule's zone is the list's"),
                items: [
                  for (final l in widget.lists) DropdownMenuItem(value: l.id, child: Text(l.name)),
                ],
                onChanged: (v) => setState(() => _priceListId = v),
              ),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                key: const Key('rule-strategy'),
                initialValue: _strategy,
                decoration: const InputDecoration(labelText: 'Against the lowest fresh rival price'),
                items: const [
                  DropdownMenuItem(value: 'MATCH_LOWEST', child: Text('Match it')),
                  DropdownMenuItem(value: 'UNDERCUT_PERCENT', child: Text('Undercut it by a percentage')),
                  DropdownMenuItem(value: 'UNDERCUT_AMOUNT', child: Text('Undercut it by an amount')),
                ],
                onChanged: (v) => setState(() => _strategy = v ?? 'MATCH_LOWEST'),
              ),
              if (undercuts) ...[
                const SizedBox(height: 8),
                TextField(
                  key: const Key('rule-value'),
                  controller: _value,
                  decoration: InputDecoration(labelText: _strategy == 'UNDERCUT_PERCENT' ? 'Percent' : 'Amount'),
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                ),
              ],
              const SizedBox(height: 8),
              TextField(
                key: const Key('rule-floor'),
                controller: _floor,
                decoration: const InputDecoration(
                  labelText: 'Floor, % of the current price *',
                  helperText: 'Never below this share. No cost is known here; protect a margin at the buyer\'s cost.',
                ),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
              ),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                key: const Key('rule-rounding'),
                initialValue: _rounding,
                decoration: const InputDecoration(labelText: 'Rounding'),
                items: const [
                  DropdownMenuItem(value: 'NONE', child: Text('To the penny')),
                  DropdownMenuItem(value: 'ENDING_99', child: Text('Down to a .99')),
                ],
                onChanged: (v) => setState(() => _rounding = v ?? 'NONE'),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const Key('rule-max-age'),
                controller: _maxAge,
                decoration: const InputDecoration(labelText: 'A rival price counts for (days)'),
                keyboardType: TextInputType.number,
              ),
              if (_refusal != null) ...[
                const SizedBox(height: 12),
                Text(_refusal!, key: const Key('rule-refusal'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('rule-save'), onPressed: _busy ? null : _save, child: const Text('Create')),
      ],
    );
  }
}

/// Keeps the shared loading/error widgets referenced for readers of this file: the tab shows
/// compact rows instead because three sections stack in one scroll.
// ignore: unused_element
const _sharedStates = [LoadingView, ErrorView];
