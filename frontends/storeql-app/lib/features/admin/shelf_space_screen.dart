import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';
import 'providers/admin_providers.dart';

// ---------------------------------------------------------------------------
// Shelf space and range (07.17, 07.18).
//
// Two decisions about a shelf, on one screen because a buyer makes them
// together: how much of the shelf a line gets, and which shops carry the line
// at all.
//
// The gaps tab is the one a shop opens every morning. A reorder level answers
// whether the business will run out, which is the warehouse's question; this
// answers whether the bay looks full, which is the shop floor's — and the two
// differ by exactly the shelf. Twenty units is plenty for a bay holding twelve
// and a gap in one holding sixty.
//
// The range tab shows what is DUE rather than what is set. A range change is
// recorded weeks ahead with a date and a reason, and applying it is a separate
// step, so the useful view is the one that says what has not happened yet.
// ---------------------------------------------------------------------------

class FixtureRow {
  final String id;
  final String code;
  final String name;
  final String kind;
  final int shelfCount;
  final int shelfWidthMm;
  final int totalWidthMm;
  final String status;

  const FixtureRow({
    required this.id,
    required this.code,
    required this.name,
    required this.kind,
    required this.shelfCount,
    required this.shelfWidthMm,
    required this.totalWidthMm,
    required this.status,
  });

  bool get active => status == 'ACTIVE';

  factory FixtureRow.fromJson(Map<String, dynamic> j) => FixtureRow(
        id: j['id'] as String? ?? '',
        code: j['code'] as String? ?? '',
        name: j['name'] as String? ?? '',
        kind: j['kind'] as String? ?? '',
        shelfCount: (j['shelfCount'] as num?)?.toInt() ?? 0,
        shelfWidthMm: (j['shelfWidthMm'] as num?)?.toInt() ?? 0,
        totalWidthMm: (j['totalWidthMm'] as num?)?.toInt() ?? 0,
        status: j['status'] as String? ?? 'ACTIVE',
      );
}

class ShelfGapRow {
  final String variantId;
  final int capacity;
  final int minPresentation;
  final String available;
  final String gap;
  final bool belowMinimum;

  const ShelfGapRow({
    required this.variantId,
    required this.capacity,
    required this.minPresentation,
    required this.available,
    required this.gap,
    required this.belowMinimum,
  });

  factory ShelfGapRow.fromJson(Map<String, dynamic> j) => ShelfGapRow(
        variantId: j['variantId'] as String? ?? '',
        capacity: (j['capacity'] as num?)?.toInt() ?? 0,
        minPresentation: (j['minPresentation'] as num?)?.toInt() ?? 0,
        available: j['available'] as String? ?? '0',
        gap: j['gap'] as String? ?? '0',
        belowMinimum: j['belowMinimum'] as bool? ?? false,
      );
}

class RangeChangeRow {
  final String id;
  final String productId;
  final String action;
  final String effectiveFrom;
  final String reason;
  final String? clusterId;
  final String? storeId;

  const RangeChangeRow({
    required this.id,
    required this.productId,
    required this.action,
    required this.effectiveFrom,
    required this.reason,
    this.clusterId,
    this.storeId,
  });

  bool get delisting => action == 'DELIST';

  factory RangeChangeRow.fromJson(Map<String, dynamic> j) => RangeChangeRow(
        id: j['id'] as String? ?? '',
        productId: j['productId'] as String? ?? '',
        action: j['action'] as String? ?? '',
        effectiveFrom: j['effectiveFrom'] as String? ?? '',
        reason: j['reason'] as String? ?? '',
        clusterId: j['clusterId'] as String?,
        storeId: j['storeId'] as String?,
      );
}

const _merch = '/${ApiConstants.product}/admin/merchandising';
const _range = '/${ApiConstants.product}/admin/assortment';
const _gaps = '/${ApiConstants.inventory}/admin/inventory/reports/shelf-gaps';

final shelfSpaceStoreProvider = StateProvider<String?>((_) => null);

final fixturesProvider =
    FutureProvider.autoDispose.family<List<FixtureRow>, String>(
        (ref, storeId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('$_merch/fixtures', queryParameters: {'store': storeId});
  return [
    for (final e in (resp.data['data'] as List?) ?? const [])
      if (e is Map<String, dynamic>) FixtureRow.fromJson(e),
  ];
});

final shelfGapsProvider =
    FutureProvider.autoDispose.family<List<ShelfGapRow>, String>(
        (ref, storeId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get(_gaps, queryParameters: {'storeId': storeId, 'limit': 50});
  return [
    for (final e in (resp.data['data'] as List?) ?? const [])
      if (e is Map<String, dynamic>) ShelfGapRow.fromJson(e),
  ];
});

final dueChangesProvider =
    FutureProvider.autoDispose<List<RangeChangeRow>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get('$_range/changes/due');
  return [
    for (final e in (resp.data['data'] as List?) ?? const [])
      if (e is Map<String, dynamic>) RangeChangeRow.fromJson(e),
  ];
});

/// Product id → name for the lines the due range changes are about, keyed by
/// a [variantIdsKey]-style csv. product-svc has no batch read for products, so
/// each is read on its own — a due list is short. A product that cannot be
/// read is left out, and its row falls back to a short reference.
final rangeProductNamesProvider =
    FutureProvider.autoDispose.family<Map<String, String>, String>(
        (ref, idsCsv) async {
  if (idsCsv.isEmpty) return const {};
  final dio = ref.read(apiClientProvider).dio;
  final names = <String, String>{};
  await Future.wait(idsCsv.split(',').map((id) async {
    try {
      final resp = await dio.get('/${ApiConstants.product}/admin/products/$id');
      final data = resp.data is Map ? resp.data['data'] : null;
      final name = data is Map ? data['name'] as String? : null;
      if (name != null && name.isNotEmpty) names[id] = name;
    } catch (_) {
      // Unreadable: the row names it by a short reference instead.
    }
  }));
  return names;
});

/// A count of units as people say it: `52`, not the ledger's `52.000`. A part
/// unit keeps its fraction (`2.5`), and thousands are grouped for the locale.
String _units(String quantity) {
  final n = num.tryParse(quantity);
  return n == null ? quantity : _count(n);
}

/// Grouped in the app's locale, a part unit keeping its fraction.
String _count(num n) => AppFormat.count(n);

/// A tab's list: its lead-in (the explanation, a button) spaced 16 apart, then
/// its cards 8 apart. The theme's cards have no margin, so without the gap
/// their outlines would sit on each other.
class _CardList extends StatelessWidget {
  const _CardList({required this.lead, required this.cards});

  final List<Widget> lead;
  final List<Widget> cards;

  @override
  Widget build(BuildContext context) {
    final children = [...lead, ...cards];
    return ListView.separated(
      padding: context.pagePadding,
      itemCount: children.length,
      separatorBuilder: (_, i) =>
          SizedBox(height: i < lead.length ? AppSpacing.lg : AppSpacing.sm),
      itemBuilder: (_, i) => children[i],
    );
  }
}

/// A tab's opening paragraph, in the quieter text colour.
class _Explainer extends StatelessWidget {
  const _Explainer(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Text(
      text,
      style: theme.textTheme.bodyMedium
          ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
    );
  }
}

/// Shelf space and range for one store.
class ShelfSpaceScreen extends ConsumerWidget {
  const ShelfSpaceScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final storesAsync = ref.watch(storesProvider);
    final auth = ref.watch(authNotifierProvider).value;
    final isManager = auth is AuthAuthenticated && auth.isManager;
    // A store-bound storekeeper sees only the stores they work in, which is
    // also all the shelf-gap report will let them read.
    final allowedStores =
        auth is AuthAuthenticated ? auth.storeIds : const <String>[];

    if (storesAsync.hasError) {
      return ErrorView(
        message: friendlyError(storesAsync.error!),
        onRetry: () => ref.invalidate(storesProvider),
      );
    }
    if (!storesAsync.hasValue) {
      return const LoadingView(label: 'Loading stores…');
    }
    final stores = storesAsync.value!
        .where((s) => allowedStores.isEmpty || allowedStores.contains(s.id))
        .toList();
    if (stores.isEmpty) {
      return const EmptyState(
        icon: Icons.store_outlined,
        title: 'Add a store before planning its shelves.',
      );
    }
    final chosen = ref.watch(shelfSpaceStoreProvider);
    final storeId = stores.any((s) => s.id == chosen) ? chosen! : stores.first.id;
    final gutter = context.pageGutter;

    // A storekeeper works to the gaps only: shelving and range are a buyer's
    // decisions, read from management-only endpoints the router already
    // keeps a storekeeper's other pages off.
    final tabs = [
      const Tab(text: 'Gaps to fill'),
      if (isManager) const Tab(text: 'Shelving'),
      if (isManager) const Tab(text: 'Range'),
    ];
    final views = [
      _GapsTab(storeId: storeId),
      if (isManager) _FixturesTab(storeId: storeId),
      if (isManager) const _RangeTab(),
    ];

    return DefaultTabController(
      key: ValueKey(isManager),
      length: tabs.length,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          PageHeader(
            title: 'Shelf space',
            subtitle: stores.length > 1 ? null : stores.first.name,
            actions: [
              if (stores.length > 1)
                // A field, like every other picker: the theme draws its
                // outline in both brightnesses, where a bare DropdownButton
                // draws Flutter's fixed grey underline.
                SizedBox(
                  width: 280,
                  child: DropdownButtonFormField<String>(
                    key: const Key('shelf-store'),
                    initialValue: storeId,
                    isExpanded: true,
                    decoration: const InputDecoration(
                      labelText: 'Store',
                      prefixIcon: Icon(Icons.store_outlined),
                    ),
                    items: [
                      for (final s in stores)
                        DropdownMenuItem(
                          value: s.id,
                          child: Text(s.name, overflow: TextOverflow.ellipsis),
                        ),
                    ],
                    onChanged: (v) =>
                        ref.read(shelfSpaceStoreProvider.notifier).state = v,
                  ),
                ),
            ],
          ),
          // Start-aligned, with each label at the page gutter under the title,
          // rather than at Material's 52px scrollable offset.
          TabBar(
            isScrollable: true,
            tabAlignment: TabAlignment.start,
            padding: EdgeInsetsDirectional.only(start: gutter - AppSpacing.lg),
            labelPadding:
                const EdgeInsetsDirectional.symmetric(horizontal: AppSpacing.lg),
            tabs: tabs,
          ),
          Expanded(
            child: TabBarView(children: views),
          ),
        ],
      ),
    );
  }
}

class _GapsTab extends ConsumerWidget {
  final String storeId;
  const _GapsTab({required this.storeId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final async = ref.watch(shelfGapsProvider(storeId));
    return async.when(
      loading: () => const LoadingView(label: 'Reading the shelves…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not read the shelf gaps.'),
        onRetry: () => ref.invalidate(shelfGapsProvider(storeId)),
      ),
      data: (rows) {
        if (rows.isEmpty) {
          return const EmptyState(
            key: Key('gaps-empty'),
            icon: Icons.shelves,
            title: 'No shelf plans for this store yet',
            message: 'Until a layout is published, replenishment is driven '
                'from reorder levels alone — which say whether stock will run '
                'out, not whether the bay looks full.',
          );
        }
        // The report carries variant ids; product-svc names them. A short
        // reference stands in only while a name is unknown.
        final labels = ref
                .watch(variantLabelsProvider(
                    variantIdsKey(rows.map((r) => r.variantId))))
                .value ??
            const <String, VariantLabel>{};
        return _CardList(
          lead: const [
            _Explainer(
              'What it would take to fill each bay: the shelf\'s capacity against '
              'stock that is not already held for somebody\'s order. A line below '
              'its presentation minimum looks picked over now, whatever the '
              'reorder level says.',
            ),
          ],
          cards: [
            for (final r in rows)
              Card(
                child: ListTile(
                  key: Key('gap-${r.variantId}'),
                  leading: Icon(
                    r.belowMinimum ? Icons.warning_amber : Icons.shelves,
                    color: r.belowMinimum ? theme.colorScheme.error : null,
                  ),
                  title: Text(_lineName(r.variantId, labels)),
                  // The badge sits under the counts, not in the trailing slot,
                  // so the text keeps the row's width on a phone.
                  subtitle: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text('${_units(r.gap)} to fill  ·  '
                          'shelf holds ${_count(r.capacity)}'),
                      Text('${_units(r.available)} available  ·  looks picked '
                          'over below ${_count(r.minPresentation)}'),
                      if (r.belowMinimum)
                        const Padding(
                          padding: EdgeInsetsDirectional.only(top: AppSpacing.xs),
                          child: StatusBadge('Below minimum',
                              tone: StatusTone.warning),
                        ),
                    ],
                  ),
                ),
              ),
          ],
        );
      },
    );
  }

  /// The product and its SKU (a variant's own name), e.g. *Oat milk 1L · OAT-1L*.
  static String _lineName(String variantId, Map<String, VariantLabel> labels) {
    final name = variantDisplayName(variantId, labels);
    final sku = variantSku(variantId, labels);
    return sku.isEmpty ? name : '$name  ·  $sku';
  }
}

class _FixturesTab extends ConsumerWidget {
  final String storeId;
  const _FixturesTab({required this.storeId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(fixturesProvider(storeId));
    return async.when(
      loading: () => const LoadingView(label: 'Loading shelving…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load the shelving.'),
        onRetry: () => ref.invalidate(fixturesProvider(storeId)),
      ),
      data: (rows) {
        if (rows.isEmpty) {
          return const EmptyState(
            key: Key('fixtures-empty'),
            icon: Icons.shelves,
            title: 'No shelving recorded for this store yet',
          );
        }
        return _CardList(
          lead: const [
            _Explainer(
              'The furniture a layout is drawn for. Its shelves and their width '
              'are what make a plan checkable: facings times a line\'s width '
              'either fits or does not.',
            ),
          ],
          cards: [
            for (final f in rows)
              Card(
                child: ListTile(
                  key: Key('fixture-${f.id}'),
                  title: Text('${f.name}  ·  ${f.code}'),
                  subtitle: Text(
                    '${f.kind.toLowerCase().replaceAll('_', ' ')}  ·  '
                    '${f.shelfCount} shelves of ${f.shelfWidthMm}mm  ·  '
                    '${f.totalWidthMm}mm in all',
                  ),
                  trailing: f.active ? null : const StatusBadge('Retired'),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _RangeTab extends ConsumerStatefulWidget {
  const _RangeTab();

  @override
  ConsumerState<_RangeTab> createState() => _RangeTabState();
}

class _RangeTabState extends ConsumerState<_RangeTab> {
  bool _applying = false;

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(dueChangesProvider);
    return async.when(
      loading: () => const LoadingView(label: 'Loading range changes…'),
      error: (e, _) => ErrorView(
        message: friendlyError(e, fallback: 'Could not load the range changes.'),
        onRetry: () => ref.invalidate(dueChangesProvider),
      ),
      data: (rows) {
        // A range change is about a product; product-svc names it.
        final names = ref
                .watch(rangeProductNamesProvider(
                    variantIdsKey(rows.map((c) => c.productId))))
                .value ??
            const <String, String>{};
        const explainer = _Explainer(
          'Range decisions that have reached their day and have not been put '
          'into effect yet. Each one says who decided it and why — applying is '
          'a separate step, so a range can be planned weeks ahead.',
        );
        if (rows.isEmpty) {
          return const _CardList(
            lead: [explainer],
            cards: [
              Card(
                key: Key('range-empty'),
                child: Padding(
                  padding: AppSpacing.cardPadding,
                  child:
                      Text('Nothing is waiting: every dated change is in force.'),
                ),
              ),
            ],
          );
        }
        return _CardList(
          lead: [
            explainer,
            Align(
              alignment: AlignmentDirectional.centerStart,
              child: FilledButton.icon(
                key: const Key('range-apply'),
                onPressed: _applying ? null : _apply,
                icon: const Icon(Icons.playlist_add_check),
                label: Text('Apply ${rows.length} due change'
                    '${rows.length == 1 ? '' : 's'}'),
              ),
            ),
          ],
          cards: [
            for (final c in rows)
              Card(
                child: ListTile(
                  key: Key('change-${c.id}'),
                  leading: Icon(c.delisting
                      ? Icons.remove_circle_outline
                      : Icons.add_circle_outline),
                  title: Text(names[c.productId] ??
                      'Product ${shortRef(c.productId)}'),
                  subtitle: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text('${c.delisting ? 'De-list' : 'List'}'
                          '  ·  due ${AppFormat.date(c.effectiveFrom)}'),
                      Text(c.reason),
                    ],
                  ),
                ),
              ),
          ],
        );
      },
    );
  }

  Future<void> _apply() async {
    setState(() => _applying = true);
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .post('$_range/changes/apply', data: const {});
      final applied = (resp.data['data']?['applied'] as num?)?.toInt() ?? 0;
      final notApplied =
          (resp.data['data']?['notApplied'] as List?) ?? const [];
      ref.invalidate(dueChangesProvider);
      if (!mounted) return;
      // The refusals are named, not swallowed: a change that could not be put
      // into effect stays due, so the shop can fix the cause rather than
      // re-enter the decision.
      final first = notApplied.isEmpty
          ? null
          : (notApplied.first as Map<String, dynamic>)['detail'] as String?;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(notApplied.isEmpty
              ? '$applied change${applied == 1 ? '' : 's'} in force.'
              : '$applied in force, ${notApplied.length} could not be '
                  'applied. ${first ?? ''}'),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(friendlyError(e))),
      );
    } finally {
      if (mounted) setState(() => _applying = false);
    }
  }
}
