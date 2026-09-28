import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

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
import '../../shared/widgets/status_badge.dart';
import 'procurement_providers.dart';
import 'providers/admin_providers.dart';
import 'supplier_scorecards.dart';
import 'widgets/variant_picker.dart';

// ---------------------------------------------------------------------------
// RFQ and sourcing. A buyer asks several suppliers to quote for the same lines,
// records what each says, reads the quotes side by side in the business's own
// money with each supplier's scorecard grade beside them, and awards the lines,
// which raises a draft order per supplier at the quoted prices.
// ---------------------------------------------------------------------------

class RfqSummary {
  final String id;
  final String reference;
  final String title;
  final String storeId;
  final String status;
  final String? neededBy;
  final int lines;
  final int suppliers;
  final int quotes;
  const RfqSummary({
    required this.id,
    required this.reference,
    required this.title,
    required this.storeId,
    required this.status,
    this.neededBy,
    required this.lines,
    required this.suppliers,
    required this.quotes,
  });

  factory RfqSummary.fromJson(Map<String, dynamic> j) => RfqSummary(
        id: j['id'] as String? ?? '',
        reference: j['reference'] as String? ?? '',
        title: j['title'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        status: j['status'] as String? ?? 'DRAFT',
        neededBy: j['neededBy'] as String?,
        lines: (j['lines'] as num?)?.toInt() ?? 0,
        suppliers: (j['suppliers'] as num?)?.toInt() ?? 0,
        quotes: (j['quotes'] as num?)?.toInt() ?? 0,
      );
}

class RfqLine {
  final String id;
  final String variantId;
  final double qty;
  final String? notes;
  const RfqLine({required this.id, required this.variantId, required this.qty, this.notes});

  factory RfqLine.fromJson(Map<String, dynamic> j) => RfqLine(
        id: j['id'] as String? ?? '',
        variantId: j['variantId'] as String? ?? '',
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
        notes: j['notes'] as String?,
      );
}

class RfqBid {
  final String supplierId;
  final String supplierName;
  final String status;
  final String? currency;
  final int? leadTimeDays;
  final String? validUntil;
  final String? grade;
  final Map<String, double> prices;
  const RfqBid({
    required this.supplierId,
    required this.supplierName,
    required this.status,
    this.currency,
    this.leadTimeDays,
    this.validUntil,
    this.grade,
    required this.prices,
  });

  factory RfqBid.fromJson(Map<String, dynamic> j) => RfqBid(
        supplierId: j['supplierId'] as String? ?? '',
        supplierName: j['supplierName'] as String? ?? '',
        status: j['status'] as String? ?? 'INVITED',
        currency: j['currency'] as String?,
        leadTimeDays: (j['leadTimeDays'] as num?)?.toInt(),
        validUntil: j['validUntil'] as String?,
        grade: j['grade'] as String?,
        prices: {
          for (final p in (j['prices'] as List?) ?? const [])
            (p as Map<String, dynamic>)['variantId'] as String: (p['unitPrice'] as num).toDouble(),
        },
      );
}

class RfqPriceComparison {
  final String supplierId;
  final double unitPrice;
  final String? currency;
  final double? homeUnitPrice;
  final double lineTotal;
  final double? homeLineTotal;
  final bool lowest;
  const RfqPriceComparison({
    required this.supplierId,
    required this.unitPrice,
    this.currency,
    this.homeUnitPrice,
    required this.lineTotal,
    this.homeLineTotal,
    required this.lowest,
  });

  factory RfqPriceComparison.fromJson(Map<String, dynamic> j) => RfqPriceComparison(
        supplierId: j['supplierId'] as String? ?? '',
        unitPrice: (j['unitPrice'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String?,
        homeUnitPrice: (j['homeUnitPrice'] as num?)?.toDouble(),
        lineTotal: (j['lineTotal'] as num?)?.toDouble() ?? 0,
        homeLineTotal: (j['homeLineTotal'] as num?)?.toDouble(),
        lowest: j['lowest'] as bool? ?? false,
      );
}

class RfqLineComparison {
  final String variantId;
  final double qty;
  final List<RfqPriceComparison> prices;
  const RfqLineComparison({required this.variantId, required this.qty, required this.prices});

  factory RfqLineComparison.fromJson(Map<String, dynamic> j) => RfqLineComparison(
        variantId: j['variantId'] as String? ?? '',
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
        prices: ((j['prices'] as List?) ?? const [])
            .map((e) => RfqPriceComparison.fromJson(e as Map<String, dynamic>))
            .toList(),
      );

  RfqPriceComparison? of(String supplierId) =>
      prices.where((p) => p.supplierId == supplierId).firstOrNull;
}

class RfqBidSummary {
  final String supplierId;
  final String? supplierName;
  final String status;
  final bool complete;
  final double? total;
  final String? currency;
  final double? homeTotal;
  final int? rank;
  const RfqBidSummary({
    required this.supplierId,
    this.supplierName,
    required this.status,
    required this.complete,
    this.total,
    this.currency,
    this.homeTotal,
    this.rank,
  });

  factory RfqBidSummary.fromJson(Map<String, dynamic> j) => RfqBidSummary(
        supplierId: j['supplierId'] as String? ?? '',
        supplierName: j['supplierName'] as String?,
        status: j['status'] as String? ?? 'INVITED',
        complete: j['complete'] as bool? ?? false,
        total: (j['total'] as num?)?.toDouble(),
        currency: j['currency'] as String?,
        homeTotal: (j['homeTotal'] as num?)?.toDouble(),
        rank: (j['rank'] as num?)?.toInt(),
      );
}

class RfqAward {
  final String variantId;
  final String supplierId;
  final String poId;
  final double unitPrice;
  final String currency;
  const RfqAward({
    required this.variantId,
    required this.supplierId,
    required this.poId,
    required this.unitPrice,
    required this.currency,
  });

  factory RfqAward.fromJson(Map<String, dynamic> j) => RfqAward(
        variantId: j['variantId'] as String? ?? '',
        supplierId: j['supplierId'] as String? ?? '',
        poId: j['poId'] as String? ?? '',
        unitPrice: (j['unitPrice'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
      );
}

class RfqDetail {
  final String id;
  final String reference;
  final String title;
  final String storeId;
  final String status;
  final String? neededBy;
  final String? closesOn;
  final String? notes;
  final String? cancelledReason;
  final List<RfqLine> lines;
  final List<RfqBid> bids;
  final String homeCurrency;
  final List<RfqLineComparison> comparison;
  final List<RfqBidSummary> summaries;
  final List<RfqAward> awards;
  const RfqDetail({
    required this.id,
    required this.reference,
    required this.title,
    required this.storeId,
    required this.status,
    this.neededBy,
    this.closesOn,
    this.notes,
    this.cancelledReason,
    required this.lines,
    required this.bids,
    required this.homeCurrency,
    required this.comparison,
    required this.summaries,
    required this.awards,
  });

  factory RfqDetail.fromJson(Map<String, dynamic> j) {
    final cmp = (j['comparison'] as Map<String, dynamic>?) ?? const {};
    return RfqDetail(
      id: j['id'] as String? ?? '',
      reference: j['reference'] as String? ?? '',
      title: j['title'] as String? ?? '',
      storeId: j['storeId'] as String? ?? '',
      status: j['status'] as String? ?? 'DRAFT',
      neededBy: j['neededBy'] as String?,
      closesOn: j['closesOn'] as String?,
      notes: j['notes'] as String?,
      cancelledReason: j['cancelledReason'] as String?,
      lines: ((j['lines'] as List?) ?? const [])
          .map((e) => RfqLine.fromJson(e as Map<String, dynamic>))
          .toList(),
      bids: ((j['bids'] as List?) ?? const [])
          .map((e) => RfqBid.fromJson(e as Map<String, dynamic>))
          .toList(),
      homeCurrency: cmp['homeCurrency'] as String? ?? '',
      comparison: ((cmp['lines'] as List?) ?? const [])
          .map((e) => RfqLineComparison.fromJson(e as Map<String, dynamic>))
          .toList(),
      summaries: ((cmp['bids'] as List?) ?? const [])
          .map((e) => RfqBidSummary.fromJson(e as Map<String, dynamic>))
          .toList(),
      awards: ((j['awards'] as List?) ?? const [])
          .map((e) => RfqAward.fromJson(e as Map<String, dynamic>))
          .toList(),
    );
  }

  /// The suppliers that quoted, in rank order, the unranked after.
  List<RfqBid> get quoted {
    final ranked = [...bids.where((b) => b.status == 'QUOTED')];
    int rankOf(String id) => summaries.where((s) => s.supplierId == id).firstOrNull?.rank ?? 1 << 20;
    ranked.sort((a, b) => rankOf(a.supplierId).compareTo(rankOf(b.supplierId)));
    return ranked;
  }
}

final rfqsProvider = FutureProvider.autoDispose<List<RfqSummary>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get('/${ApiConstants.purchase}/rfqs');
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => RfqSummary.fromJson(e as Map<String, dynamic>))
      .toList();
});

final rfqDetailProvider = FutureProvider.autoDispose.family<RfqDetail, String>((ref, id) async {
  final resp = await ref.read(apiClientProvider).dio.get('/${ApiConstants.purchase}/rfqs/$id');
  return RfqDetail.fromJson(resp.data['data'] as Map<String, dynamic>);
});

String _qty(num v) => v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toStringAsFixed(3);

bool _mayBuy(AuthState? auth) =>
    auth is AuthAuthenticated && (auth.isManager || auth.isStorekeeper);

/// The Procurement screen's "Sourcing" tab: the requests and what came back.
class SourcingTab extends ConsumerWidget {
  const SourcingTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final rfqs = ref.watch(rfqsProvider);
    final mayBuy = _mayBuy(ref.watch(authNotifierProvider).value);
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Column(
      children: [
        Padding(
          padding: EdgeInsetsDirectional.fromSTEB(
              context.pageGutter, AppSpacing.md, context.pageGutter, AppSpacing.xs),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('Requests for quotation',
                        style: text.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
                    Text(
                      'Ask several suppliers for the same lines, read their quotes side by side in your'
                      ' own money, and award — each award raises a draft order at the quoted price.',
                      style: text.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                    ),
                  ],
                ),
              ),
              if (mayBuy) ...[
                const SizedBox(width: 12),
                FilledButton.icon(
                  key: const Key('rfq-new'),
                  onPressed: () => showDialog<void>(
                    context: context,
                    builder: (_) => const NewRfqDialog(),
                  ),
                  icon: const Icon(Icons.add),
                  label: const Text('New request'),
                ),
              ],
            ],
          ),
        ),
        Expanded(
          child: rfqs.when(
            loading: () => const LoadingView(label: 'Loading requests…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load the requests.'),
              onRetry: () => ref.invalidate(rfqsProvider),
            ),
            data: (list) => list.isEmpty
                ? const EmptyState(
                    icon: Icons.request_quote_outlined,
                    title: 'No requests yet',
                    detail: 'Raise one when several suppliers could supply the same lines.',
                  )
                : ListView.separated(
                    padding: context.pagePadding,
                    itemCount: list.length,
                    separatorBuilder: (_, _) => const SizedBox(height: 4),
                    itemBuilder: (_, i) {
                      final r = list[i];
                      return Card(
                        child: ListTile(
                          key: Key('rfq-${r.id}'),
                          leading: _StatusChip(r.status),
                          title: Text('${r.reference} · ${r.title}',
                              style: const TextStyle(fontWeight: FontWeight.w600)),
                          subtitle: Text(
                            '${r.lines} lines · ${r.quotes} of ${r.suppliers} suppliers quoted'
                            '${r.neededBy == null ? '' : ' · needed by ${AppFormat.date(r.neededBy)}'}',
                          ),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: () => showDialog<void>(
                            context: context,
                            builder: (_) => RfqDetailDialog(id: r.id),
                          ),
                        ),
                      );
                    },
                  ),
          ),
        ),
      ],
    );
  }
}

/// A request's status as the shared badge, in words.
class _StatusChip extends StatelessWidget {
  const _StatusChip(this.status);
  final String status;

  @override
  Widget build(BuildContext context) {
    final (words, tone) = switch (status) {
      'DRAFT' => ('Draft', StatusTone.neutral),
      'ISSUED' => ('Out for quotes', StatusTone.info),
      'AWARDED' => ('Awarded', StatusTone.success),
      'CANCELLED' => ('Cancelled', StatusTone.neutral),
      _ => (humanizeCode(status), StatusTone.neutral),
    };
    return StatusBadge(words, key: Key('rfq-status-$status'), tone: tone);
  }
}

class _LineRow {
  String? productId;
  String? variantId;
  final qty = TextEditingController(text: '1');
  void dispose() => qty.dispose();
}

/// Raise a request: the lines wanted and the suppliers asked.
class NewRfqDialog extends ConsumerStatefulWidget {
  const NewRfqDialog({super.key});

  @override
  ConsumerState<NewRfqDialog> createState() => _NewRfqDialogState();
}

class _NewRfqDialogState extends ConsumerState<NewRfqDialog> {
  final _title = TextEditingController();
  final _neededBy = TextEditingController();
  String? _storeId;
  final List<_LineRow> _rows = [_LineRow()];
  final Set<String> _suppliers = {};
  bool _busy = false;
  String? _refusal;

  @override
  void dispose() {
    _title.dispose();
    _neededBy.dispose();
    for (final r in _rows) {
      r.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    final lines = [
      for (final r in _rows)
        if (r.variantId != null)
          {'variantId': r.variantId, 'qty': double.tryParse(r.qty.text.trim()) ?? 0},
    ];
    if (_title.text.trim().isEmpty || _storeId == null || lines.isEmpty || _suppliers.isEmpty) {
      setState(() => _refusal = 'Name the request, pick the store, at least one line and one supplier.');
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.purchase}/rfqs',
        data: {
          'title': _title.text.trim(),
          'storeId': _storeId,
          if (_neededBy.text.trim().isNotEmpty) 'neededBy': _neededBy.text.trim(),
          'lines': lines,
          'supplierIds': _suppliers.toList(),
        },
      );
      ref.invalidate(rfqsProvider);
      if (!mounted) return;
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not raise the request.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final stores = ref.watch(storesProvider).value ?? const <StoreInfo>[];
    final suppliers = ref.watch(suppliersProvider).value ?? const <Supplier>[];
    return AlertDialog(
      title: const Text('New request for quotation'),
      content: SizedBox(
        width: 560,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                key: const Key('rfq-title'),
                controller: _title,
                decoration: const InputDecoration(labelText: 'Title *', hintText: 'Autumn beef'),
              ),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                key: const Key('rfq-store'),
                initialValue: _storeId,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'For store *'),
                items: [
                  for (final s in stores)
                    DropdownMenuItem(value: s.id, child: Text(s.name, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (v) => setState(() => _storeId = v),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const Key('rfq-needed-by'),
                controller: _neededBy,
                decoration: const InputDecoration(
                  labelText: 'Needed by (yyyy-MM-dd)',
                  helperText: 'Becomes the expected delivery of the orders an award raises',
                ),
              ),
              const SizedBox(height: 12),
              Text('Lines', style: Theme.of(context).textTheme.labelLarge),
              for (var i = 0; i < _rows.length; i++) ...[
                const SizedBox(height: 8),
                VariantPicker(
                  productId: _rows[i].productId,
                  variantId: _rows[i].variantId,
                  onProduct: (v) => setState(() {
                    _rows[i].productId = v;
                    _rows[i].variantId = null;
                  }),
                  onVariant: (v) => setState(() => _rows[i].variantId = v),
                ),
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        key: Key('rfq-qty-$i'),
                        controller: _rows[i].qty,
                        decoration: const InputDecoration(labelText: 'Quantity'),
                        keyboardType: const TextInputType.numberWithOptions(decimal: true),
                      ),
                    ),
                    if (_rows.length > 1)
                      IconButton(
                        tooltip: 'Remove line',
                        onPressed: () => setState(() => _rows.removeAt(i).dispose()),
                        icon: const Icon(Icons.remove_circle_outline),
                      ),
                  ],
                ),
              ],
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  key: const Key('rfq-add-line'),
                  onPressed: () => setState(() => _rows.add(_LineRow())),
                  icon: const Icon(Icons.add),
                  label: const Text('Another line'),
                ),
              ),
              const SizedBox(height: 8),
              Text('Suppliers to ask', style: Theme.of(context).textTheme.labelLarge),
              for (final s in suppliers)
                CheckboxListTile(
                  key: Key('rfq-supplier-${s.id}'),
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  controlAffinity: ListTileControlAffinity.leading,
                  value: _suppliers.contains(s.id),
                  onChanged: (v) => setState(() {
                    if (v == true) {
                      _suppliers.add(s.id);
                    } else {
                      _suppliers.remove(s.id);
                    }
                  }),
                  title: Text('${s.name}${s.currency == null ? '' : ' · ${s.currency}'}'),
                ),
              if (_refusal != null) ...[
                const SizedBox(height: 12),
                Text(_refusal!, key: const Key('rfq-refusal'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('rfq-save'), onPressed: _busy ? null : _save, child: const Text('Raise')),
      ],
    );
  }
}

/// The request in full: the comparison, the actions, the awards.
class RfqDetailDialog extends ConsumerWidget {
  const RfqDetailDialog({super.key, required this.id});
  final String id;

  Future<void> _post(BuildContext context, WidgetRef ref, String path, Map<String, dynamic> body,
      String done) async {
    try {
      await ref.read(apiClientProvider).dio.post('/${ApiConstants.purchase}/rfqs/$id$path', data: body);
      ref.invalidate(rfqDetailProvider(id));
      ref.invalidate(rfqsProvider);
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(done)));
      }
    } on DioException catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(friendlyError(e, fallback: 'That did not go through.'))));
      }
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final detail = ref.watch(rfqDetailProvider(id));
    final mayBuy = _mayBuy(ref.watch(authNotifierProvider).value);
    // Each line by its product's name; the end of its id only while it loads.
    final labels = ref
            .watch(variantLabelsProvider(variantIdsKey([
              for (final l in detail.value?.lines ?? const <RfqLine>[]) l.variantId,
            ])))
            .value ??
        const <String, VariantLabel>{};
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return AlertDialog(
      title: detail.maybeWhen(
        data: (d) => Row(children: [
          _StatusChip(d.status),
          const SizedBox(width: 10),
          Expanded(child: Text('${d.reference} · ${d.title}', overflow: TextOverflow.ellipsis)),
        ]),
        orElse: () => const Text('Request'),
      ),
      content: SizedBox(
        width: 720,
        child: detail.when(
          loading: () => const LoadingView(label: 'Loading the request…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the request.'),
            onRetry: () => ref.invalidate(rfqDetailProvider(id)),
          ),
          data: (d) {
            final quoted = d.quoted;
            return SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    [
                      if (d.neededBy != null) 'needed by ${AppFormat.date(d.neededBy)}',
                      if (d.closesOn != null) 'quotes due ${AppFormat.date(d.closesOn)}',
                      'compared in ${d.homeCurrency}',
                      if (d.cancelledReason != null) 'cancelled: ${d.cancelledReason}',
                    ].join(' · '),
                    style: text.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                  ),
                  const SizedBox(height: 12),
                  Text('Quotes side by side', style: text.labelLarge),
                  const SizedBox(height: 4),
                  SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: DataTable(
                      key: const Key('rfq-comparison'),
                      columnSpacing: 16,
                      headingRowHeight: 40,
                      columns: [
                        const DataColumn(label: Text('Line')),
                        for (final b in quoted)
                          DataColumn(
                            label: Row(mainAxisSize: MainAxisSize.min, children: [
                              GradeChip(b.grade),
                              const SizedBox(width: 6),
                              Text('${b.supplierName}\n${b.currency ?? ''}'),
                            ]),
                          ),
                      ],
                      rows: [
                        for (final lc in d.comparison)
                          DataRow(cells: [
                            DataCell(Text('${variantDisplayName(lc.variantId, labels)} × ${_qty(lc.qty)}')),
                            for (final b in quoted)
                              DataCell(Builder(builder: (_) {
                                final p = lc.of(b.supplierId);
                                if (p == null) return Text('–', style: TextStyle(color: cs.outline));
                                final home = p.homeUnitPrice == null
                                    ? 'no rate'
                                    : AppFormat.money(p.homeUnitPrice!, currencyCode: d.homeCurrency);
                                return Text(
                                  '${AppFormat.money(p.unitPrice, currencyCode: p.currency)} · $home${p.lowest ? ' ✓' : ''}',
                                  key: p.lowest ? Key('rfq-lowest-${lc.variantId}') : null,
                                  style: TextStyle(fontWeight: p.lowest ? FontWeight.w700 : FontWeight.w400),
                                );
                              })),
                          ]),
                        DataRow(cells: [
                          const DataCell(Text('Total at home', style: TextStyle(fontWeight: FontWeight.w600))),
                          for (final b in quoted)
                            DataCell(Builder(builder: (_) {
                              final s = d.summaries.where((x) => x.supplierId == b.supplierId).firstOrNull;
                              if (s == null) return const Text('–');
                              final home = s.homeTotal == null
                                  ? (s.complete ? 'no rate' : 'partial quote')
                                  : AppFormat.money(s.homeTotal!, currencyCode: d.homeCurrency);
                              return Text(
                                '$home${s.rank == null ? '' : ' · #${s.rank}'}',
                                key: Key('rfq-total-${b.supplierId}'),
                                style: TextStyle(fontWeight: s.rank == 1 ? FontWeight.w700 : FontWeight.w600),
                              );
                            })),
                        ]),
                      ],
                    ),
                  ),
                  const SizedBox(height: 12),
                  Text('Suppliers asked', style: text.labelLarge),
                  for (final b in d.bids)
                    ListTile(
                      key: Key('rfq-bid-${b.supplierId}'),
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      leading: GradeChip(b.grade),
                      title: Text(b.supplierName),
                      subtitle: Text(
                        switch (b.status) {
                          'QUOTED' =>
                            'Quoted in ${b.currency ?? '?'}${b.leadTimeDays == null ? '' : ' · ${b.leadTimeDays} days'}${b.validUntil == null ? '' : ' · valid until ${AppFormat.date(b.validUntil)}'}',
                          'DECLINED' => 'Declined to quote',
                          _ => 'Invited, nothing back yet',
                        },
                      ),
                      trailing: mayBuy && d.status == 'ISSUED'
                          ? Row(mainAxisSize: MainAxisSize.min, children: [
                              TextButton(
                                key: Key('rfq-quote-${b.supplierId}'),
                                onPressed: () => showDialog<void>(
                                  context: context,
                                  builder: (_) => RecordQuoteDialog(rfq: d, bid: b),
                                ),
                                child: Text(b.status == 'QUOTED' ? 'Replace quote' : 'Record quote'),
                              ),
                              if (b.status != 'DECLINED')
                                TextButton(
                                  key: Key('rfq-decline-${b.supplierId}'),
                                  onPressed: () => _post(context, ref, '/quotes/${b.supplierId}/decline', {}, 'Declined.'),
                                  child: const Text('Declined'),
                                ),
                            ])
                          : null,
                    ),
                  if (d.awards.isNotEmpty) ...[
                    const SizedBox(height: 12),
                    Text('Awarded', style: text.labelLarge),
                    for (final a in d.awards)
                      ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        title: Text(
                          '${variantDisplayName(a.variantId, labels)} → ${d.bids.where((b) => b.supplierId == a.supplierId).map((b) => b.supplierName).firstOrNull ?? shortRef(a.supplierId)}'
                          ' at ${AppFormat.money(a.unitPrice, currencyCode: a.currency)}',
                        ),
                        subtitle: Text('Draft order ${shortRef(a.poId)}'),
                      ),
                  ],
                ],
              ),
            );
          },
        ),
      ),
      actions: [
        if (mayBuy)
          ...detail.maybeWhen(
            data: (d) => [
              if (d.status == 'DRAFT')
                TextButton(
                  key: const Key('rfq-issue'),
                  onPressed: () => _post(context, ref, '/issue', {}, 'Issued: quotes can be recorded.'),
                  child: const Text('Issue'),
                ),
              if (d.status == 'DRAFT' || d.status == 'ISSUED')
                TextButton(
                  key: const Key('rfq-cancel'),
                  onPressed: () => showDialog<void>(
                    context: context,
                    builder: (_) => _CancelDialog(onCancel: (reason) => _post(context, ref, '/cancel', {'reason': reason}, 'Cancelled.')),
                  ),
                  child: const Text('Cancel request'),
                ),
              if (d.status == 'ISSUED' && d.quoted.isNotEmpty)
                FilledButton(
                  key: const Key('rfq-award'),
                  onPressed: () => showDialog<void>(
                    context: context,
                    builder: (_) => AwardDialog(rfq: d),
                  ),
                  child: const Text('Award'),
                ),
            ],
            orElse: () => const <Widget>[],
          ),
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Close')),
      ],
    );
  }
}

class _CancelDialog extends StatefulWidget {
  const _CancelDialog({required this.onCancel});
  final Future<void> Function(String reason) onCancel;

  @override
  State<_CancelDialog> createState() => _CancelDialogState();
}

class _CancelDialogState extends State<_CancelDialog> {
  final _reason = TextEditingController();

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Cancel the request'),
      content: TextField(
        key: const Key('rfq-cancel-reason'),
        controller: _reason,
        decoration: const InputDecoration(labelText: 'Reason *'),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Keep it')),
        FilledButton(
          key: const Key('rfq-cancel-confirm'),
          onPressed: () async {
            if (_reason.text.trim().isEmpty) return;
            await widget.onCancel(_reason.text.trim());
            if (context.mounted) Navigator.of(context).pop();
          },
          child: const Text('Cancel request'),
        ),
      ],
    );
  }
}

/// What a supplier said: terms and a price per line, in their currency.
class RecordQuoteDialog extends ConsumerStatefulWidget {
  const RecordQuoteDialog({super.key, required this.rfq, required this.bid});
  final RfqDetail rfq;
  final RfqBid bid;

  @override
  ConsumerState<RecordQuoteDialog> createState() => _RecordQuoteDialogState();
}

class _RecordQuoteDialogState extends ConsumerState<RecordQuoteDialog> {
  late final TextEditingController _currency;
  late final TextEditingController _lead;
  final _valid = TextEditingController();
  final Map<String, TextEditingController> _prices = {};
  bool _busy = false;
  String? _refusal;

  @override
  void initState() {
    super.initState();
    _currency = TextEditingController(text: widget.bid.currency ?? '');
    _lead = TextEditingController(text: widget.bid.leadTimeDays?.toString() ?? '');
    for (final l in widget.rfq.lines) {
      final p = widget.bid.prices[l.variantId];
      _prices[l.variantId] = TextEditingController(text: p == null ? '' : p.toStringAsFixed(2));
    }
  }

  @override
  void dispose() {
    _currency.dispose();
    _lead.dispose();
    _valid.dispose();
    for (final c in _prices.values) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    final lines = [
      for (final e in _prices.entries)
        if (e.value.text.trim().isNotEmpty)
          {'variantId': e.key, 'unitPrice': double.tryParse(e.value.text.trim()) ?? 0},
    ];
    if (lines.isEmpty) {
      setState(() => _refusal = 'Give a price for at least one line.');
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.purchase}/rfqs/${widget.rfq.id}/quotes/${widget.bid.supplierId}',
        data: {
          if (_currency.text.trim().isNotEmpty) 'currency': _currency.text.trim().toUpperCase(),
          if (_lead.text.trim().isNotEmpty) 'leadTimeDays': int.tryParse(_lead.text.trim()),
          if (_valid.text.trim().isNotEmpty) 'validUntil': _valid.text.trim(),
          'lines': lines,
        },
      );
      ref.invalidate(rfqDetailProvider(widget.rfq.id));
      ref.invalidate(rfqsProvider);
      if (!mounted) return;
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not record the quote.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final labels = ref
            .watch(variantLabelsProvider(
                variantIdsKey(widget.rfq.lines.map((l) => l.variantId))))
            .value ??
        const <String, VariantLabel>{};
    return AlertDialog(
      title: Text('Quote from ${widget.bid.supplierName}'),
      content: SizedBox(
        width: 440,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(children: [
                Expanded(
                  child: TextField(
                    key: const Key('quote-currency'),
                    controller: _currency,
                    decoration: const InputDecoration(labelText: 'Currency', helperText: 'Theirs when blank'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: TextField(
                    key: const Key('quote-lead'),
                    controller: _lead,
                    decoration: const InputDecoration(labelText: 'Lead time (days)'),
                    keyboardType: TextInputType.number,
                  ),
                ),
              ]),
              const SizedBox(height: 8),
              TextField(
                key: const Key('quote-valid'),
                controller: _valid,
                decoration: const InputDecoration(labelText: 'Valid until (yyyy-MM-dd)'),
              ),
              const SizedBox(height: 8),
              for (final l in widget.rfq.lines)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: TextField(
                    key: Key('quote-price-${l.variantId}'),
                    controller: _prices[l.variantId],
                    decoration: InputDecoration(
                      labelText: 'Unit price · ${variantDisplayName(l.variantId, labels)} × ${_qty(l.qty)}',
                      helperText: 'Blank: not priced',
                    ),
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  ),
                ),
              if (_refusal != null) ...[
                const SizedBox(height: 12),
                Text(_refusal!, key: const Key('quote-refusal'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('quote-save'), onPressed: _busy ? null : _save, child: const Text('Record')),
      ],
    );
  }
}

/// Which supplier gets which line: the lowest at home is picked for you.
class AwardDialog extends ConsumerStatefulWidget {
  const AwardDialog({super.key, required this.rfq});
  final RfqDetail rfq;

  @override
  ConsumerState<AwardDialog> createState() => _AwardDialogState();
}

class _AwardDialogState extends ConsumerState<AwardDialog> {
  final Map<String, String?> _choice = {};
  bool _busy = false;
  String? _refusal;

  @override
  void initState() {
    super.initState();
    for (final lc in widget.rfq.comparison) {
      _choice[lc.variantId] = lc.prices.where((p) => p.lowest).firstOrNull?.supplierId;
    }
  }

  Future<void> _save() async {
    final awards = [
      for (final e in _choice.entries)
        if (e.value != null) {'variantId': e.key, 'supplierId': e.value},
    ];
    if (awards.isEmpty) {
      setState(() => _refusal = 'Give at least one line to a supplier.');
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.purchase}/rfqs/${widget.rfq.id}/award',
        data: {'awards': awards},
      );
      final data = resp.data['data'] as Map<String, dynamic>? ?? const {};
      ref.invalidate(rfqDetailProvider(widget.rfq.id));
      ref.invalidate(rfqsProvider);
      ref.invalidate(purchaseOrdersProvider);
      if (!mounted) return;
      final n = ((data['purchaseOrderIds'] as List?) ?? const []).length;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Awarded: $n draft order${n == 1 ? '' : 's'} raised at the quoted prices.')));
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not award.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final d = widget.rfq;
    final labels = ref
            .watch(variantLabelsProvider(variantIdsKey(d.lines.map((l) => l.variantId))))
            .value ??
        const <String, VariantLabel>{};
    String name(String id) => d.bids.where((b) => b.supplierId == id).map((b) => b.supplierName).firstOrNull ?? shortRef(id);
    return AlertDialog(
      title: Text('Award ${d.reference}'),
      content: SizedBox(
        width: 480,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Each line goes to one supplier at the price they quoted; the lowest at home is picked'
                ' for you. A line left unassigned is not awarded.',
                style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
              ),
              for (final lc in d.comparison)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: DropdownButtonFormField<String?>(
                    key: Key('award-${lc.variantId}'),
                    initialValue: _choice[lc.variantId],
                    isExpanded: true,
                    decoration: InputDecoration(labelText: '${variantDisplayName(lc.variantId, labels)} × ${_qty(lc.qty)}'),
                    items: [
                      const DropdownMenuItem<String?>(value: null, child: Text('Not awarded')),
                      for (final p in lc.prices)
                        DropdownMenuItem<String?>(
                          value: p.supplierId,
                          child: Text(
                            '${name(p.supplierId)} · ${AppFormat.money(p.unitPrice, currencyCode: p.currency)}'
                            '${p.homeUnitPrice == null ? '' : ' (${AppFormat.money(p.homeUnitPrice!, currencyCode: d.homeCurrency)})'}'
                            '${p.lowest ? ' ✓' : ''}',
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                    ],
                    onChanged: (v) => setState(() => _choice[lc.variantId] = v),
                  ),
                ),
              if (_refusal != null) ...[
                const SizedBox(height: 12),
                Text(_refusal!, key: const Key('award-refusal'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('award-save'), onPressed: _busy ? null : _save, child: const Text('Award')),
      ],
    );
  }
}
