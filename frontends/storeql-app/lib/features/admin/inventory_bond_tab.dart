import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';
import 'widgets/variant_picker.dart';

// ---------------------------------------------------------------------------
// Bonded and duty-suspended stock. A store approved as a bonded warehouse holds
// excise goods with the duty suspended: on hand, never for sale, valued at
// cost without the duty. Management sets the duty one unit crystallises; a
// storekeeper releases stock to home use, which pays that duty and tells the
// ledger. The releases of a period are what an excise return is made from.
// ---------------------------------------------------------------------------

class BondApproval {
  final String storeId;
  final String approvalNumber;
  final String regime;
  final bool active;
  const BondApproval({
    required this.storeId,
    required this.approvalNumber,
    required this.regime,
    required this.active,
  });

  factory BondApproval.fromJson(Map<String, dynamic> j) => BondApproval(
        storeId: j['storeId'] as String? ?? '',
        approvalNumber: j['approvalNumber'] as String? ?? '',
        regime: j['regime'] as String? ?? 'EXCISE',
        active: j['active'] as bool? ?? true,
      );
}

class DutyRate {
  final String variantId;
  final double dutyPerUnit;
  final String currency;
  final String? note;
  const DutyRate({
    required this.variantId,
    required this.dutyPerUnit,
    required this.currency,
    this.note,
  });

  factory DutyRate.fromJson(Map<String, dynamic> j) => DutyRate(
        variantId: j['variantId'] as String? ?? '',
        dutyPerUnit: (j['dutyPerUnit'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
        note: j['note'] as String?,
      );
}

class BondStock {
  final String storeId;
  final String variantId;
  final double qty;
  final double? dutyPerUnit;
  final double dutyPotential;
  const BondStock({
    required this.storeId,
    required this.variantId,
    required this.qty,
    this.dutyPerUnit,
    required this.dutyPotential,
  });

  factory BondStock.fromJson(Map<String, dynamic> j) => BondStock(
        storeId: j['storeId'] as String? ?? '',
        variantId: j['variantId'] as String? ?? '',
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
        dutyPerUnit: (j['dutyPerUnit'] as num?)?.toDouble(),
        dutyPotential: (j['dutyPotential'] as num?)?.toDouble() ?? 0,
      );
}

class BondRelease {
  final String id;
  final String storeId;
  final String variantId;
  final double qty;
  final double dutyAmount;
  final String currency;
  final String? reference;
  final String releasedAt;
  const BondRelease({
    required this.id,
    required this.storeId,
    required this.variantId,
    required this.qty,
    required this.dutyAmount,
    required this.currency,
    this.reference,
    required this.releasedAt,
  });

  factory BondRelease.fromJson(Map<String, dynamic> j) => BondRelease(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        variantId: j['variantId'] as String? ?? '',
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
        dutyAmount: (j['dutyAmount'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
        reference: j['reference'] as String?,
        releasedAt: j['releasedAt'] as String? ?? '',
      );
}

class BondReleases {
  final List<BondRelease> releases;
  final double totalDuty;
  final String currency;
  const BondReleases({required this.releases, required this.totalDuty, required this.currency});

  factory BondReleases.fromJson(Map<String, dynamic> j) => BondReleases(
        releases: ((j['releases'] as List?) ?? const [])
            .map((e) => BondRelease.fromJson(e as Map<String, dynamic>))
            .toList(),
        totalDuty: (j['totalDuty'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
      );
}

const _bond = '/admin/inventory/bond';

final bondApprovalsProvider = FutureProvider.autoDispose<List<BondApproval>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get('/${ApiConstants.inventory}$_bond/approvals');
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => BondApproval.fromJson(e as Map<String, dynamic>))
      .toList();
});

final dutyRatesProvider = FutureProvider.autoDispose<List<DutyRate>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get('/${ApiConstants.inventory}$_bond/duty-rates');
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => DutyRate.fromJson(e as Map<String, dynamic>))
      .toList();
});

final bondStockProvider = FutureProvider.autoDispose<List<BondStock>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get('/${ApiConstants.inventory}$_bond/stock');
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => BondStock.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// This month's releases and the duty they add up to.
final bondReleasesProvider = FutureProvider.autoDispose<BondReleases>((ref) async {
  final today = DateTime.now();
  final from = DateTime(today.year, today.month, 1).toIso8601String().split('T').first;
  final to = today.toIso8601String().split('T').first;
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.inventory}$_bond/releases',
    queryParameters: {'from': from, 'to': to},
  );
  return BondReleases.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// The Inventory screen's "Bond & duty" tab.
class InventoryBondTab extends ConsumerWidget {
  const InventoryBondTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final management = auth is AuthAuthenticated && auth.isManager;
    final stores = ref.watch(storesProvider).value ?? const <StoreInfo>[];
    String storeName(String id) =>
        stores.where((s) => s.id == id).map((s) => s.name).firstOrNull ?? '…${shortRef(id)}';
    final approvals = ref.watch(bondApprovalsProvider);
    final rates = ref.watch(dutyRatesProvider);
    final stock = ref.watch(bondStockProvider);
    final releases = ref.watch(bondReleasesProvider);
    // Stock by its product's name; the end of its id only while names load.
    final names = ref
            .watch(variantLabelsProvider(variantIdsKey([
              for (final r in rates.value ?? const []) r.variantId as String,
              for (final s in stock.value ?? const []) s.variantId as String,
              for (final x in releases.value?.releases ?? const []) x.variantId as String,
            ])))
            .value ??
        const <String, VariantLabel>{};
    String product(String id) => variantDisplayName(id, names);
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;

    Widget header(String title, String detail, Widget? action) => Row(
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

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        header(
          'Bonded warehouses',
          "Stores approved by the revenue to hold excise goods with the duty suspended. Only an approved"
              ' store takes duty-suspended stock; it is on hand there but never for sale until released.',
          management
              ? FilledButton.icon(
                  key: const Key('bond-approve'),
                  onPressed: () => showDialog(
                    context: context,
                    builder: (_) => ApproveBondDialog(stores: stores),
                  ),
                  icon: const Icon(Icons.verified_outlined),
                  label: const Text('Approve a store'),
                )
              : null,
        ),
        const SizedBox(height: 12),
        approvals.when(
          loading: () => const LoadingView(label: 'Loading approvals…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load bond approvals.'),
            onRetry: () => ref.invalidate(bondApprovalsProvider),
          ),
          data: (list) => list.isEmpty
              ? const EmptyState(
                  icon: Icons.warehouse_outlined,
                  title: 'No bonded warehouse',
                  detail: 'Approve a store to hold duty-suspended stock.',
                )
              : Column(
                  children: [
                    for (final a in list)
                      Card(
                        child: ListTile(
                          key: Key('bond-${a.storeId}'),
                          leading: Icon(
                            Icons.warehouse_outlined,
                            color: a.active ? cs.onSurfaceVariant : cs.outline,
                          ),
                          title: Text(storeName(a.storeId),
                              style: const TextStyle(fontWeight: FontWeight.w600)),
                          subtitle: Text(
                            '${a.regime == 'CUSTOMS' ? 'Customs' : 'Excise'} warehouse · approval ${a.approvalNumber}'
                            '${a.active ? '' : ' · ended'}',
                          ),
                        ),
                      ),
                  ],
                ),
        ),
        const SizedBox(height: 24),
        header(
          'Duty per unit',
          'The duty one unit of a product crystallises on release, in your own currency, with a note on how'
              ' it was worked out. Nothing here is derived from strength or volume: the figure is yours.',
          management
              ? FilledButton.icon(
                  key: const Key('duty-rate-set'),
                  onPressed: () => showDialog(
                    context: context,
                    builder: (_) => const SetDutyRateDialog(),
                  ),
                  icon: const Icon(Icons.add),
                  label: const Text('Set a rate'),
                )
              : null,
        ),
        const SizedBox(height: 12),
        rates.when(
          loading: () => const LoadingView(label: 'Loading duty rates…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load duty rates.'),
            onRetry: () => ref.invalidate(dutyRatesProvider),
          ),
          data: (list) => list.isEmpty
              ? const EmptyState(
                  icon: Icons.percent_outlined,
                  title: 'No duty rates yet',
                  detail: 'A release needs the duty one unit crystallises.',
                )
              : Column(
                  children: [
                    for (final r in list)
                      ListTile(
                        dense: true,
                        title: Text(
                          '${product(r.variantId)} · ${AppFormat.money(r.dutyPerUnit, currencyCode: r.currency)} per unit',
                        ),
                        subtitle: r.note == null ? null : Text(r.note!),
                      ),
                  ],
                ),
        ),
        const SizedBox(height: 24),
        header(
          'In bond',
          'What sits in bond per store and product, and the duty it would crystallise on release.',
          FilledButton.tonalIcon(
            key: const Key('bond-release'),
            onPressed: () => showDialog(
              context: context,
              builder: (_) => ReleaseFromBondDialog(
                stores: stores.where((s) => (approvals.value ?? const []).any((a) => a.active && a.storeId == s.id)).toList(),
              ),
            ),
            icon: const Icon(Icons.outbox_outlined),
            label: const Text('Release to home use'),
          ),
        ),
        const SizedBox(height: 12),
        stock.when(
          loading: () => const LoadingView(label: 'Loading stock in bond…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the stock in bond.'),
            onRetry: () => ref.invalidate(bondStockProvider),
          ),
          data: (list) => list.isEmpty
              ? const EmptyState(icon: Icons.inventory_2_outlined, title: 'Nothing in bond')
              : Column(
                  children: [
                    for (final s in list)
                      ListTile(
                        key: Key('in-bond-${s.variantId}'),
                        dense: true,
                        title: Text('${s.qty.toStringAsFixed(0)} × ${product(s.variantId)} at ${storeName(s.storeId)}'),
                        subtitle: Text(
                          s.dutyPerUnit == null
                              ? 'No duty rate set: cannot be released until one is'
                              : 'Duty on release: ${AppFormat.money(s.dutyPotential)}',
                        ),
                      ),
                  ],
                ),
        ),
        const SizedBox(height: 24),
        header(
          'Released this month',
          'Each release to home use and the duty it paid; the total is what the excise return is made from.',
          null,
        ),
        const SizedBox(height: 12),
        releases.when(
          loading: () => const LoadingView(label: 'Loading releases…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load releases.'),
            onRetry: () => ref.invalidate(bondReleasesProvider),
          ),
          data: (r) => r.releases.isEmpty
              ? const EmptyState(icon: Icons.receipt_long_outlined, title: 'Nothing released this month')
              : Column(
                  children: [
                    for (final x in r.releases)
                      ListTile(
                        dense: true,
                        title: Text('${x.qty.toStringAsFixed(0)} × ${product(x.variantId)} from ${storeName(x.storeId)}'),
                        subtitle: Text('${AppFormat.dateTime(x.releasedAt)}${x.reference == null ? '' : ' · ${x.reference}'}'),
                        trailing: Text(AppFormat.money(x.dutyAmount, currencyCode: x.currency)),
                      ),
                    ListTile(
                      title: Text('Duty this month', style: text.titleSmall),
                      trailing: Text(
                        AppFormat.money(r.totalDuty, currencyCode: r.currency),
                        key: const Key('bond-total-duty'),
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                    ),
                  ],
                ),
        ),
      ],
    );
  }
}

/// Approve a store as a bonded warehouse.
class ApproveBondDialog extends ConsumerStatefulWidget {
  const ApproveBondDialog({super.key, required this.stores});
  final List<StoreInfo> stores;

  @override
  ConsumerState<ApproveBondDialog> createState() => _ApproveBondDialogState();
}

class _ApproveBondDialogState extends ConsumerState<ApproveBondDialog> {
  String? _storeId;
  String _regime = 'EXCISE';
  final _number = TextEditingController();
  bool _busy = false;
  String? _refusal;

  @override
  void dispose() {
    _number.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_storeId == null || _number.text.trim().isEmpty) {
      setState(() => _refusal = "Pick the store and give the revenue's approval number.");
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.inventory}$_bond/approvals/$_storeId',
        data: {'approvalNumber': _number.text.trim(), 'regime': _regime},
      );
      ref.invalidate(bondApprovalsProvider);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Approved: the store now holds duty-suspended stock.')));
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not approve the store.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Approve a bonded warehouse'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            DropdownButtonFormField<String>(
              key: const Key('bond-store'),
              initialValue: _storeId,
              isExpanded: true,
              decoration: const InputDecoration(labelText: 'Store *'),
              items: [
                for (final s in widget.stores)
                  DropdownMenuItem(value: s.id, child: Text(s.name, overflow: TextOverflow.ellipsis)),
              ],
              onChanged: (v) => setState(() => _storeId = v),
            ),
            const SizedBox(height: 8),
            TextField(
              key: const Key('bond-number'),
              controller: _number,
              decoration: const InputDecoration(labelText: 'Approval number *', hintText: 'GBWK123456789'),
            ),
            const SizedBox(height: 8),
            DropdownButtonFormField<String>(
              key: const Key('bond-regime'),
              initialValue: _regime,
              decoration: const InputDecoration(labelText: 'Regime'),
              items: const [
                DropdownMenuItem(value: 'EXCISE', child: Text('Excise warehouse')),
                DropdownMenuItem(value: 'CUSTOMS', child: Text('Customs warehouse')),
              ],
              onChanged: (v) => setState(() => _regime = v ?? 'EXCISE'),
            ),
            if (_refusal != null) ...[
              const SizedBox(height: 12),
              Text(_refusal!, key: const Key('bond-refusal'), style: TextStyle(color: cs.error)),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('bond-save'), onPressed: _busy ? null : _save, child: const Text('Approve')),
      ],
    );
  }
}

/// Set the duty one unit of a variant crystallises.
class SetDutyRateDialog extends ConsumerStatefulWidget {
  const SetDutyRateDialog({super.key});

  @override
  ConsumerState<SetDutyRateDialog> createState() => _SetDutyRateDialogState();
}

class _SetDutyRateDialogState extends ConsumerState<SetDutyRateDialog> {
  String? _productId;
  String? _variantId;
  final _duty = TextEditingController();
  final _note = TextEditingController();
  bool _busy = false;
  String? _refusal;

  @override
  void dispose() {
    _duty.dispose();
    _note.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_variantId == null) {
      setState(() => _refusal = 'Pick the product and its variant.');
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.inventory}$_bond/duty-rates/$_variantId',
        data: {
          'dutyPerUnit': double.tryParse(_duty.text.trim()) ?? -1,
          if (_note.text.trim().isNotEmpty) 'note': _note.text.trim(),
        },
      );
      ref.invalidate(dutyRatesProvider);
      ref.invalidate(bondStockProvider);
      if (!mounted) return;
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not set the rate.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Duty per unit'),
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
                key: const Key('duty-per-unit'),
                controller: _duty,
                decoration: const InputDecoration(
                  labelText: 'Duty one unit crystallises *',
                  helperText: 'In your own currency',
                ),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const Key('duty-note'),
                controller: _note,
                decoration: const InputDecoration(labelText: 'How it was worked out'),
              ),
              if (_refusal != null) ...[
                const SizedBox(height: 12),
                Text(_refusal!, key: const Key('duty-refusal'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('duty-save'), onPressed: _busy ? null : _save, child: const Text('Set')),
      ],
    );
  }
}

/// Release duty-suspended stock to home use: the duty on it is paid.
class ReleaseFromBondDialog extends ConsumerStatefulWidget {
  const ReleaseFromBondDialog({super.key, required this.stores});
  final List<StoreInfo> stores;

  @override
  ConsumerState<ReleaseFromBondDialog> createState() => _ReleaseFromBondDialogState();
}

class _ReleaseFromBondDialogState extends ConsumerState<ReleaseFromBondDialog> {
  String? _storeId;
  String? _productId;
  String? _variantId;
  final _qty = TextEditingController();
  final _reference = TextEditingController();
  bool _busy = false;
  String? _refusal;

  @override
  void dispose() {
    _qty.dispose();
    _reference.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_storeId == null || _variantId == null) {
      setState(() => _refusal = 'Pick the bonded store, the product and its variant.');
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.inventory}$_bond/releases',
        data: {
          'storeId': _storeId,
          'variantId': _variantId,
          'qty': double.tryParse(_qty.text.trim()) ?? 0,
          if (_reference.text.trim().isNotEmpty) 'reference': _reference.text.trim(),
        },
      );
      final data = resp.data['data'] as Map<String, dynamic>? ?? const {};
      ref.invalidate(bondStockProvider);
      ref.invalidate(bondReleasesProvider);
      if (!mounted) return;
      final duty = (data['dutyAmount'] as num?)?.toDouble() ?? 0;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(
          'Released ${_qty.text.trim()}: duty of ${AppFormat.money(duty, currencyCode: data['currency'] as String?)} now owed.',
        ),
      ));
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not release.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Release to home use'),
      content: SizedBox(
        width: 440,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'What leaves bond becomes duty-paid stock at the same store, and the duty on it is owed'
                ' from today.',
                style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                key: const Key('release-store'),
                initialValue: _storeId,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Bonded store *'),
                items: [
                  for (final s in widget.stores)
                    DropdownMenuItem(value: s.id, child: Text(s.name, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (v) => setState(() => _storeId = v),
              ),
              const SizedBox(height: 8),
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
                key: const Key('release-qty'),
                controller: _qty,
                decoration: const InputDecoration(labelText: 'Quantity *'),
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const Key('release-reference'),
                controller: _reference,
                decoration: const InputDecoration(labelText: 'Reference (the return or warrant)'),
              ),
              if (_refusal != null) ...[
                const SizedBox(height: 12),
                Text(_refusal!, key: const Key('release-refusal'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(key: const Key('release-save'), onPressed: _busy ? null : _save, child: const Text('Release')),
      ],
    );
  }
}
