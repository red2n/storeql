import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import 'fx_rates_card.dart';

/// The business's return policy (return-controls): how many days a sale can
/// come back, what a cashier may refund without a manager, and whether a
/// return with no receipt is taken at all. Ceilings are in the business's home
/// currency; empty means no limit.
class ReturnPolicy {
  final int windowDays;
  final double? cashierCeiling;
  final bool noReceiptAllowed;
  final double? noReceiptCeiling;
  const ReturnPolicy({
    required this.windowDays,
    this.cashierCeiling,
    required this.noReceiptAllowed,
    this.noReceiptCeiling,
  });

  factory ReturnPolicy.fromJson(Map<String, dynamic> j) => ReturnPolicy(
        windowDays: (j['windowDays'] as num?)?.toInt() ?? 30,
        cashierCeiling: (j['cashierCeiling'] as num?)?.toDouble(),
        noReceiptAllowed: j['noReceiptAllowed'] as bool? ?? false,
        noReceiptCeiling: (j['noReceiptCeiling'] as num?)?.toDouble(),
      );
}

final returnPolicyProvider = FutureProvider.autoDispose<ReturnPolicy>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.order}/admin/return-policy');
  return ReturnPolicy.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// The Return policy card on the Stores screen, for management only (the
/// caller decides who sees it; the server refuses anyone else). It reads the
/// policy in force and offers **Edit**.
class ReturnPolicyCard extends ConsumerWidget {
  const ReturnPolicyCard({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final policy = ref.watch(returnPolicyProvider);
    final home = ref.watch(fxRatesProvider).value?.home ?? '';
    final gutter = context.pageGutter;
    return Padding(
      padding: EdgeInsetsDirectional.fromSTEB(gutter, 0, gutter, AppSpacing.sm),
      child: Card(
        key: const Key('return-policy-card'),
        child: Padding(
          padding: AppSpacing.cardPadding,
          child: policy.when(
            loading: () => Row(children: [
              const SizedBox.square(
                  dimension: AppSpacing.lg, child: CircularProgressIndicator(strokeWidth: 2)),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Text('Loading the return policy…',
                    style: TextStyle(color: cs.onSurfaceVariant)),
              ),
            ]),
            error: (e, _) => Row(children: [
              Icon(Icons.assignment_return_outlined, color: cs.onSurfaceVariant),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(
                  friendlyError(e, fallback: 'Could not load the return policy.'),
                  key: const Key('return-policy-error'),
                  style: TextStyle(color: cs.error),
                ),
              ),
              TextButton(
                onPressed: () => ref.invalidate(returnPolicyProvider),
                child: const Text('Retry'),
              ),
            ]),
            data: (p) => Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Wrap(
                  alignment: WrapAlignment.spaceBetween,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  spacing: AppSpacing.sm,
                  runSpacing: AppSpacing.sm,
                  children: [
                    Row(mainAxisSize: MainAxisSize.min, children: [
                      Icon(Icons.assignment_return_outlined, color: cs.onSurfaceVariant),
                      const SizedBox(width: AppSpacing.sm),
                      Flexible(
                        child: Text('Return policy',
                            style: Theme.of(context).textTheme.titleMedium),
                      ),
                    ]),
                    FilledButton.tonalIcon(
                      key: const Key('return-policy-edit'),
                      onPressed: () => showDialog<void>(
                          context: context,
                          builder: (_) => ReturnPolicyDialog(policy: p, home: home)),
                      icon: const Icon(Icons.edit_outlined, size: 18),
                      label: const Text('Edit'),
                    ),
                  ],
                ),
                const SizedBox(height: AppSpacing.xs),
                Text(
                  _summary(p, home),
                  key: const Key('return-policy-summary'),
                  style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  static String _summary(ReturnPolicy p, String home) {
    String money(double v) => AppFormat.money(v, currencyCode: home);
    final window = 'Sales can come back within ${p.windowDays} '
        '${p.windowDays == 1 ? 'day' : 'days'} of handover.';
    final limit = p.cashierCeiling == null
        ? 'A cashier can refund any amount.'
        : 'A cashier can refund up to ${money(p.cashierCeiling!)}; more needs a manager.';
    final noReceipt = !p.noReceiptAllowed
        ? 'Returns with no receipt are not taken.'
        : p.noReceiptCeiling == null
            ? 'Returns with no receipt are taken by a manager, with no limit.'
            : 'Returns with no receipt are taken by a manager, up to ${money(p.noReceiptCeiling!)}.';
    return '$window $limit $noReceipt';
  }
}

/// Edit the policy: the window in days, the cashier's limit (empty for none),
/// and whether no-receipt returns are taken, with their limit. A refusal is
/// shown in words and the dialog stays open.
class ReturnPolicyDialog extends ConsumerStatefulWidget {
  const ReturnPolicyDialog({super.key, required this.policy, this.home = ''});
  final ReturnPolicy policy;

  /// The business's home currency, which the limits are in; empty if unknown.
  final String home;

  @override
  ConsumerState<ReturnPolicyDialog> createState() => _ReturnPolicyDialogState();
}

class _ReturnPolicyDialogState extends ConsumerState<ReturnPolicyDialog> {
  late final TextEditingController _window;
  late final TextEditingController _ceiling;
  late final TextEditingController _noReceiptCeiling;
  late bool _noReceipt;
  bool _busy = false;
  String? _error;

  static String _num(double? v) =>
      v == null ? '' : (v == v.roundToDouble() ? v.toInt().toString() : v.toString());

  @override
  void initState() {
    super.initState();
    final p = widget.policy;
    _window = TextEditingController(text: '${p.windowDays}');
    _ceiling = TextEditingController(text: _num(p.cashierCeiling));
    _noReceiptCeiling = TextEditingController(text: _num(p.noReceiptCeiling));
    _noReceipt = p.noReceiptAllowed;
  }

  @override
  void dispose() {
    _window.dispose();
    _ceiling.dispose();
    _noReceiptCeiling.dispose();
    super.dispose();
  }

  /// Blank is "no limit" (null); anything else must be a number of at least 0.
  /// Returns false when the text is not usable.
  bool _limit(String text, void Function(double?) set) {
    final t = text.trim();
    if (t.isEmpty) {
      set(null);
      return true;
    }
    final v = double.tryParse(t);
    if (v == null || v < 0) return false;
    set(v);
    return true;
  }

  Future<void> _save() async {
    final days = int.tryParse(_window.text.trim());
    if (days == null || days < 0) {
      setState(() => _error = 'The window is a whole number of days.');
      return;
    }
    double? cashier;
    double? noReceipt;
    if (!_limit(_ceiling.text, (v) => cashier = v)) {
      setState(() => _error = "The cashier's limit is an amount of 0 or more, or empty for none.");
      return;
    }
    if (_noReceipt && !_limit(_noReceiptCeiling.text, (v) => noReceipt = v)) {
      setState(() => _error = 'The no-receipt limit is an amount of 0 or more, or empty for none.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.order}/admin/return-policy',
        data: {
          'windowDays': days,
          'cashierCeiling': cashier,
          'noReceiptAllowed': _noReceipt,
          'noReceiptCeiling': _noReceipt ? noReceipt : null,
        },
      );
      ref.invalidate(returnPolicyProvider);
      if (!mounted) return;
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Return policy saved.')));
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = friendlyError(e, fallback: 'Could not save the return policy.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final unit = widget.home.isEmpty ? '' : ' (${widget.home})';
    return AlertDialog(
      title: const Text('Return policy'),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'Anything outside this policy needs a manager to take the return. '
                'Faulty goods past the window are never refused, a manager takes them.',
                style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
              ),
              const SizedBox(height: AppSpacing.md),
              TextField(
                key: const Key('policy-window'),
                controller: _window,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                    labelText: 'Return window (days)',
                    helperText: 'Counted from the day the goods were handed over'),
              ),
              const SizedBox(height: AppSpacing.sm),
              TextField(
                key: const Key('policy-ceiling'),
                controller: _ceiling,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                decoration: InputDecoration(
                    labelText: "Cashier's refund limit$unit",
                    helperText: 'Empty for no limit'),
              ),
              const SizedBox(height: AppSpacing.sm),
              SwitchListTile(
                key: const Key('policy-no-receipt'),
                contentPadding: EdgeInsets.zero,
                title: const Text('Take returns with no receipt'),
                subtitle: const Text('A manager only, to store credit or a gift card'),
                value: _noReceipt,
                onChanged: (v) => setState(() => _noReceipt = v),
              ),
              if (_noReceipt)
                TextField(
                  key: const Key('policy-no-receipt-ceiling'),
                  controller: _noReceiptCeiling,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  decoration: InputDecoration(
                      labelText: 'No-receipt limit$unit', helperText: 'Empty for no limit'),
                ),
              if (_error != null) ...[
                const SizedBox(height: AppSpacing.md),
                Text(_error!, key: const Key('policy-error'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: _busy ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
          key: const Key('policy-save'),
          onPressed: _busy ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}
