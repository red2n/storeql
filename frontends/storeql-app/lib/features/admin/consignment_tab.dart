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
import 'procurement_providers.dart';
import 'providers/admin_providers.dart';
import 'widgets/variant_picker.dart';

// ---------------------------------------------------------------------------
// Consignment stock, the buyer's side (readiness review: "Consignment and
// dropship stock ownership"). A supplier's stock on our shelves is owed to the
// supplier only as it sells; each sale inventory-svc announces is listed here,
// and a settlement gathers a period's sales into one statement the supplier
// invoices against. A sale is settled once.
// ---------------------------------------------------------------------------

class ConsignmentSale {
  final String id;
  final String supplierId;
  final String? storeId;
  final String variantId;
  final double qty;
  final double unitCost;
  final double amount;
  final String currency;
  final String soldOn;
  final bool settled;
  const ConsignmentSale({
    required this.id,
    required this.supplierId,
    this.storeId,
    required this.variantId,
    required this.qty,
    required this.unitCost,
    required this.amount,
    required this.currency,
    required this.soldOn,
    required this.settled,
  });

  factory ConsignmentSale.fromJson(Map<String, dynamic> j) => ConsignmentSale(
        id: j['id'] as String? ?? '',
        supplierId: j['supplierId'] as String? ?? '',
        storeId: j['storeId'] as String?,
        variantId: j['variantId'] as String? ?? '',
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
        unitCost: (j['unitCost'] as num?)?.toDouble() ?? 0,
        amount: (j['amount'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
        soldOn: j['soldOn'] as String? ?? '',
        settled: j['settled'] as bool? ?? false,
      );
}

class ConsignmentSettlement {
  final String id;
  final String supplierId;
  final String reference;
  final String periodFrom;
  final String periodTo;
  final String currency;
  final double total;
  final int salesCount;
  const ConsignmentSettlement({
    required this.id,
    required this.supplierId,
    required this.reference,
    required this.periodFrom,
    required this.periodTo,
    required this.currency,
    required this.total,
    required this.salesCount,
  });

  factory ConsignmentSettlement.fromJson(Map<String, dynamic> j) => ConsignmentSettlement(
        id: j['id'] as String? ?? '',
        supplierId: j['supplierId'] as String? ?? '',
        reference: j['reference'] as String? ?? '',
        periodFrom: j['periodFrom'] as String? ?? '',
        periodTo: j['periodTo'] as String? ?? '',
        currency: j['currency'] as String? ?? '',
        total: (j['total'] as num?)?.toDouble() ?? 0,
        salesCount: (j['salesCount'] as num?)?.toInt() ?? 0,
      );
}

/// Which supplier fulfils a variant per order, at what cost (dropship).
class DropshipArrangement {
  final String id;
  final String variantId;
  final String supplierId;
  final double unitCost;
  final String vatCode;
  final bool active;
  const DropshipArrangement({
    required this.id,
    required this.variantId,
    required this.supplierId,
    required this.unitCost,
    required this.vatCode,
    required this.active,
  });

  factory DropshipArrangement.fromJson(Map<String, dynamic> j) => DropshipArrangement(
        id: j['id'] as String? ?? '',
        variantId: j['variantId'] as String? ?? '',
        supplierId: j['supplierId'] as String? ?? '',
        unitCost: (j['unitCost'] as num?)?.toDouble() ?? 0,
        vatCode: j['vatCode'] as String? ?? 'T1',
        active: j['active'] as bool? ?? true,
      );
}

final dropshipArrangementsProvider =
    FutureProvider.autoDispose<List<DropshipArrangement>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.purchase}/admin/dropship/arrangements',
    queryParameters: {'limit': 100},
  );
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => DropshipArrangement.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// What has sold of suppliers' stock and is not yet on a statement.
final unsettledConsignmentSalesProvider =
    FutureProvider.autoDispose<List<ConsignmentSale>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.purchase}/admin/consignment/sales',
    queryParameters: {'settled': false, 'limit': 100},
  );
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => ConsignmentSale.fromJson(e as Map<String, dynamic>))
      .toList();
});

final consignmentSettlementsProvider =
    FutureProvider.autoDispose<List<ConsignmentSettlement>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.purchase}/admin/consignment/settlements',
    queryParameters: {'limit': 50},
  );
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => ConsignmentSettlement.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// What one supplier is owed so far: its unsettled sales added up.
class _Owed {
  final String supplierId;
  final String currency;
  int count = 0;
  double total = 0;
  _Owed(this.supplierId, this.currency);
}

/// The Procurement screen's Consignment tab.
class ConsignmentTab extends ConsumerWidget {
  const ConsignmentTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final management = auth is AuthAuthenticated && auth.isManager;
    final sales = ref.watch(unsettledConsignmentSalesProvider);
    final settlements = ref.watch(consignmentSettlementsProvider);
    final arrangements = ref.watch(dropshipArrangementsProvider);
    final suppliers = ref.watch(suppliersProvider).value ?? const <Supplier>[];
    String supplierName(String id) =>
        suppliers.where((s) => s.id == id).map((s) => s.name).firstOrNull ?? '…${shortRef(id)}';
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;

    return ListView(
      padding: context.pagePadding,
      children: [
        // ── Dropship: stock the business never holds ──
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Dropship arrangements',
                      style: text.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 2),
                  Text(
                    'Products a supplier ships straight to the customer, per order. The shop sells'
                    ' them with none on the shelf; each paid order raises a draft purchase order for'
                    ' the supplier at the agreed cost, shipped to the customer, for you to submit.',
                    style: text.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                  ),
                ],
              ),
            ),
            if (management) ...[
              const SizedBox(width: 12),
              FilledButton.icon(
                key: const Key('dropship-new'),
                onPressed: () => showDialog(
                  context: context,
                  builder: (_) => NewDropshipArrangementDialog(suppliers: suppliers),
                ),
                icon: const Icon(Icons.add),
                label: const Text('New arrangement'),
              ),
            ],
          ],
        ),
        const SizedBox(height: 12),
        arrangements.when(
          loading: () => const LoadingView(label: 'Loading arrangements…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load dropship arrangements.'),
            onRetry: () => ref.invalidate(dropshipArrangementsProvider),
          ),
          data: (list) {
            if (list.isEmpty) {
              return const EmptyState(
                icon: Icons.local_shipping_outlined,
                title: 'Nothing is dropshipped',
                detail: 'Arrange for a supplier to ship a product straight to customers.',
              );
            }
            final names = ref
                    .watch(variantLabelsProvider(variantIdsKey(list.map((a) => a.variantId))))
                    .value ??
                const <String, VariantLabel>{};
            return Column(
              children: [
                for (final a in list)
                  Card(
                    child: ListTile(
                      key: Key('arrangement-${a.id}'),
                      leading: Icon(
                        Icons.local_shipping_outlined,
                        color: a.active ? cs.onSurfaceVariant : cs.outline,
                      ),
                      title: Text(
                        '${variantDisplayName(a.variantId, names)} from ${supplierName(a.supplierId)}',
                        style: TextStyle(
                          fontWeight: FontWeight.w600,
                          color: a.active ? null : cs.outline,
                        ),
                      ),
                      subtitle: Text(
                        '${AppFormat.money(a.unitCost, currencyCode: suppliers.where((s) => s.id == a.supplierId).map((s) => s.currency).firstOrNull)} each · VAT ${a.vatCode}'
                        '${a.active ? '' : ' · ended'}',
                      ),
                      trailing: management && a.active
                          ? TextButton(
                              key: Key('arrangement-end-${a.id}'),
                              onPressed: () async {
                                try {
                                  await ref.read(apiClientProvider).dio.post(
                                    '/${ApiConstants.purchase}/admin/dropship/arrangements/${a.id}/end',
                                    data: const {},
                                  );
                                  ref.invalidate(dropshipArrangementsProvider);
                                  if (!context.mounted) return;
                                  ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                                      content: Text('Ended: the product is stocked again.')));
                                } on DioException catch (e) {
                                  if (!context.mounted) return;
                                  ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                                      content: Text(friendlyError(e, fallback: 'Could not end it.'))));
                                }
                              },
                              child: const Text('End'),
                            )
                          : null,
                    ),
                  ),
              ],
            );
          },
        ),
        const SizedBox(height: 24),
        Text('Owed to suppliers', style: text.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
        const SizedBox(height: 2),
        Text(
          "A supplier's consignment stock is owed only as it sells, at the order's price. Each sale"
          ' below is not yet on a statement; settle a period to gather them into one the supplier'
          ' invoices against.',
          style: text.bodySmall?.copyWith(color: cs.onSurfaceVariant),
        ),
        const SizedBox(height: 12),
        sales.when(
          loading: () => const LoadingView(label: 'Loading consignment sales…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load consignment sales.'),
            onRetry: () => ref.invalidate(unsettledConsignmentSalesProvider),
          ),
          data: (list) {
            if (list.isEmpty) {
              return const EmptyState(
                icon: Icons.handshake_outlined,
                title: 'Nothing owed on consignment',
                detail: 'Sales of consignment stock appear here as the tills make them.',
              );
            }
            final soldNames = ref
                    .watch(variantLabelsProvider(variantIdsKey(list.map((s) => s.variantId))))
                    .value ??
                const <String, VariantLabel>{};
            final owed = <String, _Owed>{};
            for (final s in list) {
              final o = owed.putIfAbsent('${s.supplierId}|${s.currency}', () => _Owed(s.supplierId, s.currency));
              o.count += 1;
              o.total += s.amount;
            }
            return Column(
              children: [
                for (final o in owed.values)
                  Card(
                    child: ListTile(
                      key: Key('owed-${o.supplierId}'),
                      leading: CircleAvatar(
                        backgroundColor: cs.tertiaryContainer,
                        child: Icon(Icons.handshake_outlined, color: cs.onTertiaryContainer),
                      ),
                      title: Text(
                        supplierName(o.supplierId),
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                      subtitle: Text(
                        '${o.count} sale${o.count == 1 ? '' : 's'} · ${AppFormat.money(o.total, currencyCode: o.currency)} unsettled',
                      ),
                      trailing: management
                          ? FilledButton.tonal(
                              key: Key('settle-${o.supplierId}'),
                              onPressed: () => showDialog(
                                context: context,
                                builder: (_) => SettleConsignmentDialog(
                                  supplierId: o.supplierId,
                                  supplierName: supplierName(o.supplierId),
                                ),
                              ),
                              child: const Text('Settle…'),
                            )
                          : null,
                    ),
                  ),
                const SizedBox(height: 8),
                ExpansionTile(
                  title: Text('Every unsettled sale (${list.length})', style: text.titleSmall),
                  children: [
                    for (final s in list)
                      ListTile(
                        dense: true,
                        title: Text(
                          '${s.qty.toStringAsFixed(0)} × ${variantDisplayName(s.variantId, soldNames)} at ${AppFormat.money(s.unitCost, currencyCode: s.currency)}',
                        ),
                        subtitle: Text('${supplierName(s.supplierId)} · sold ${AppFormat.date(s.soldOn)}'),
                        trailing: Text(AppFormat.money(s.amount, currencyCode: s.currency)),
                      ),
                  ],
                ),
              ],
            );
          },
        ),
        const SizedBox(height: 24),
        Text('Statements', style: text.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
        const SizedBox(height: 2),
        Text(
          'Each settlement is the statement a supplier invoices against; the sales in it are settled once.',
          style: text.bodySmall?.copyWith(color: cs.onSurfaceVariant),
        ),
        const SizedBox(height: 12),
        settlements.when(
          loading: () => const LoadingView(label: 'Loading statements…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load settlements.'),
            onRetry: () => ref.invalidate(consignmentSettlementsProvider),
          ),
          data: (list) {
            if (list.isEmpty) {
              return const EmptyState(
                icon: Icons.receipt_long_outlined,
                title: 'No statements yet',
                detail: 'Settle a supplier above to make the first one.',
              );
            }
            return Column(
              children: [
                for (final s in list)
                  Card(
                    child: ListTile(
                      key: Key('settlement-${s.id}'),
                      leading: Icon(Icons.receipt_long_outlined, color: cs.onSurfaceVariant),
                      title: Text('${s.reference} · ${supplierName(s.supplierId)}'),
                      subtitle: Text(
                        '${AppFormat.date(s.periodFrom)} to ${AppFormat.date(s.periodTo)} · ${s.salesCount} sale${s.salesCount == 1 ? '' : 's'}',
                      ),
                      trailing: Text(
                        AppFormat.money(s.total, currencyCode: s.currency),
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                    ),
                  ),
              ],
            );
          },
        ),
      ],
    );
  }
}

/// Settle a supplier's consignment sales for a period into one statement.
class SettleConsignmentDialog extends ConsumerStatefulWidget {
  const SettleConsignmentDialog({super.key, required this.supplierId, required this.supplierName});
  final String supplierId;
  final String supplierName;

  @override
  ConsumerState<SettleConsignmentDialog> createState() => _SettleConsignmentDialogState();
}

class _SettleConsignmentDialogState extends ConsumerState<SettleConsignmentDialog> {
  late final TextEditingController _from;
  late final TextEditingController _to;
  bool _busy = false;
  String? _refusal;

  @override
  void initState() {
    super.initState();
    final today = DateTime.now();
    final firstOfMonth = DateTime(today.year, today.month, 1);
    _from = TextEditingController(text: _day(firstOfMonth));
    _to = TextEditingController(text: _day(today));
  }

  static String _day(DateTime d) => d.toIso8601String().split('T').first;

  @override
  void dispose() {
    _from.dispose();
    _to.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.purchase}/admin/consignment/settlements',
        data: {
          'supplierId': widget.supplierId,
          'from': _from.text.trim(),
          'to': _to.text.trim(),
        },
      );
      final data = resp.data['data'] as Map<String, dynamic>? ?? const {};
      ref.invalidate(unsettledConsignmentSalesProvider);
      ref.invalidate(consignmentSettlementsProvider);
      if (!mounted) return;
      final total = (data['total'] as num?)?.toDouble() ?? 0;
      final count = (data['salesCount'] as num?)?.toInt() ?? 0;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(
          'Statement ${data['reference'] ?? ''}: ${AppFormat.money(total, currencyCode: data['currency'] as String?)}'
          ' for $count sale${count == 1 ? '' : 's'} of ${widget.supplierName}.',
        ),
      ));
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not settle.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text('Settle ${widget.supplierName}'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              "Every sale of this supplier's stock in the period that is not yet on a statement goes"
              ' onto this one, once.',
              style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
            ),
            const SizedBox(height: 12),
            TextField(
              key: const Key('settle-from'),
              controller: _from,
              decoration: const InputDecoration(labelText: 'From (yyyy-MM-dd)'),
            ),
            const SizedBox(height: 8),
            TextField(
              key: const Key('settle-to'),
              controller: _to,
              decoration: const InputDecoration(labelText: 'To (yyyy-MM-dd)'),
            ),
            if (_refusal != null) ...[
              const SizedBox(height: 12),
              Text(_refusal!, key: const Key('settle-refusal'), style: TextStyle(color: cs.error)),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('settle-save'),
          onPressed: _busy ? null : _save,
          child: const Text('Settle'),
        ),
      ],
    );
  }
}

/// Arrange for a supplier to ship a product straight to customers, per order.
class NewDropshipArrangementDialog extends ConsumerStatefulWidget {
  const NewDropshipArrangementDialog({super.key, required this.suppliers});
  final List<Supplier> suppliers;

  @override
  ConsumerState<NewDropshipArrangementDialog> createState() => _NewDropshipArrangementDialogState();
}

class _NewDropshipArrangementDialogState extends ConsumerState<NewDropshipArrangementDialog> {
  String? _productId;
  String? _variantId;
  String? _supplierId;
  final _cost = TextEditingController();
  bool _busy = false;
  String? _refusal;

  @override
  void dispose() {
    _cost.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_variantId == null || _supplierId == null) {
      setState(() => _refusal = 'Pick the product, its variant and the supplier that ships it.');
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.purchase}/admin/dropship/arrangements',
        data: {
          'variantId': _variantId,
          'supplierId': _supplierId,
          'unitCost': double.tryParse(_cost.text.trim()) ?? -1,
        },
      );
      ref.invalidate(dropshipArrangementsProvider);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('Arranged: the product is now shipped by the supplier, per order.')));
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not arrange it.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('New dropship arrangement'),
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
              DropdownButtonFormField<String>(
                key: const Key('dropship-supplier'),
                initialValue: _supplierId,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Supplier that ships it *'),
                items: [
                  for (final s in widget.suppliers)
                    DropdownMenuItem(value: s.id, child: Text(s.name, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (v) => setState(() => _supplierId = v),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const Key('dropship-cost'),
                controller: _cost,
                decoration: const InputDecoration(
                  labelText: 'What the supplier charges per unit *',
                  helperText: "In the supplier's currency",
                ),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
              ),
              if (_refusal != null) ...[
                const SizedBox(height: 12),
                Text(_refusal!, key: const Key('dropship-refusal'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('dropship-save'), onPressed: _busy ? null : _save, child: const Text('Arrange')),
      ],
    );
  }
}
