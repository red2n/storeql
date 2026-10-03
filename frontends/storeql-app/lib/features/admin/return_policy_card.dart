import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/amount_entry.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import 'fx_rates_card.dart';
import 'widgets/business_wide_note.dart';
import 'widgets/figure_field.dart';

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
/// policy in force and offers **Edit** — except to a manager held to stores,
/// whom order-svc refuses the whole business's policy (BUSINESS_WIDE_ONLY) and
/// who is told who changes it instead.
class ReturnPolicyCard extends ConsumerWidget {
  const ReturnPolicyCard({super.key});

  static const heldNote = 'Only an owner or a head-office manager changes the return policy.';

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final canEdit = !heldToStores(ref.watch(authNotifierProvider).value);
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
                    if (canEdit)
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
                if (!canEdit) ...[
                  const SizedBox(height: AppSpacing.sm),
                  const BusinessWideNote(
                      key: Key('return-policy-business-wide-note'), message: heldNote),
                ],
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

  // The limits are money in the home currency, to its places, read the way
  // the app's language writes a number ([AmountMarks]) and sent as the
  // decimals typed. Blank is no limit; anything else that cannot be read is
  // refused under its field and nothing is saved. The window is a whole
  // number of days read the same way: read as a number literal, `0x1e` was
  // saved as thirty days.
  final _marks = AmountMarks.ofApp();
  late final _money = AmountShape.money(widget.home);

  /// [v] written at the currency's places the way the app's language writes
  /// a number, so it reads back unchanged; empty for no limit.
  String _written(double? v) => v == null ? '' : _marks.writeAt(v, _money.decimals);

  bool get _refused => figureRefused(_marks, [
        (_window, wholeNumber),
        (_ceiling, _money),
        if (_noReceipt) (_noReceiptCeiling, _money),
      ]);

  @override
  void initState() {
    super.initState();
    final p = widget.policy;
    _window = TextEditingController(text: '${p.windowDays}');
    _ceiling = TextEditingController(text: _written(p.cashierCeiling));
    _noReceiptCeiling = TextEditingController(text: _written(p.noReceiptCeiling));
    _noReceipt = p.noReceiptAllowed;
  }

  @override
  void dispose() {
    _window.dispose();
    _ceiling.dispose();
    _noReceiptCeiling.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_refused) return;
    final days = wholeOf(_window, _marks);
    if (days == null) {
      setState(() => _error = 'The window is a whole number of days.');
      return;
    }
    // The plain decimals typed (JSON-B reads them exactly); blank is no limit.
    final cashier = figureOf(_ceiling, _money, _marks);
    final noReceipt = figureOf(_noReceiptCeiling, _money, _marks);
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
              FigureField(
                fieldKey: const Key('policy-window'),
                controller: _window,
                shape: wholeNumber,
                marks: _marks,
                label: 'Return window (days)',
                helper: 'Counted from the day the goods were handed over',
                hint: '',
                onChanged: (_) => setState(() {}),
              ),
              const SizedBox(height: AppSpacing.sm),
              FigureField(
                fieldKey: const Key('policy-ceiling'),
                controller: _ceiling,
                shape: _money,
                marks: _marks,
                label: "Cashier's refund limit$unit",
                helper: 'Empty for no limit',
                onChanged: (_) => setState(() {}),
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
                FigureField(
                  fieldKey: const Key('policy-no-receipt-ceiling'),
                  controller: _noReceiptCeiling,
                  shape: _money,
                  marks: _marks,
                  label: 'No-receipt limit$unit',
                  helper: 'Empty for no limit',
                  onChanged: (_) => setState(() {}),
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
          onPressed: _busy || _refused ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}
