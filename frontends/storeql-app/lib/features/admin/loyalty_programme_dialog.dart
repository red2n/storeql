import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import '../../core/amount_entry.dart';
import '../../core/format.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'customer_providers.dart';
import 'widgets/business_wide_note.dart';
import 'widgets/figure_field.dart';

/// The business's loyalty programme (13.x): the ladder of tiers — a name, the
/// qualifying points that reach it and what it earns per base point — how many
/// months a point lives, how many months of earning count towards a tier, and
/// why. Management only; the platform's default shows as such until the
/// business saves its own. "Run the sweep now" writes off dead points and
/// re-tiers everyone, and says what it did.
///
/// The programme is the whole business's: customer-svc refuses a manager held
/// to stores a change to it (BUSINESS_WIDE_ONLY), so such a manager reads it,
/// is offered no change, and is told who makes one. So is the sweep, which
/// expires points and re-tiers every store's customers: such a manager is not
/// offered it either.
class LoyaltyProgrammeDialog extends ConsumerStatefulWidget {
  const LoyaltyProgrammeDialog({super.key});

  static const heldNote =
      'Only an owner or a head-office manager changes the loyalty programme or runs its sweep.';

  @override
  ConsumerState<LoyaltyProgrammeDialog> createState() => _LoyaltyProgrammeDialogState();
}

/// A tier's figures, read the way the app's language writes a number and
/// written back the same way ([AmountMarks]), within what customer-svc keeps:
/// a threshold of points NUMERIC(18,2), a multiplier of 1 to 10 to three
/// places. One that cannot be read is refused under its field and the
/// programme waits: read as a default, Romanian's ×1,5 was saved as ×1 and its
/// 1.000 points as 1.
const _thresholdShape = AmountShape(16, 2);
const _multiplierShape = AmountShape(2, 3);

class _TierRow {
  final AmountMarks marks;
  final TextEditingController name;
  final TextEditingController threshold;
  final TextEditingController multiplier;
  _TierRow(LoyaltyTier t, this.marks)
      : name = TextEditingController(text: t.name),
        threshold = TextEditingController(text: marks.write(_num(t.threshold))),
        multiplier = TextEditingController(text: marks.write(_num(t.multiplier)));

  static String _num(double v) =>
      v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toString();

  String? get thresholdRefusal => _thresholdShape.refusal(threshold.text.trim(), marks);
  String? get multiplierRefusal => _multiplierShape.refusal(multiplier.text.trim(), marks);
  bool get refused => thresholdRefusal != null || multiplierRefusal != null;

  /// The plain decimals typed, or null when blank (or refused).
  String? get thresholdPlain => _thresholdShape.read(threshold.text.trim(), marks);
  String? get multiplierPlain => _multiplierShape.read(multiplier.text.trim(), marks);

  void dispose() {
    name.dispose();
    threshold.dispose();
    multiplier.dispose();
  }
}

class _LoyaltyProgrammeDialogState extends ConsumerState<LoyaltyProgrammeDialog> {
  final _expiry = TextEditingController();
  final _qualifying = TextEditingController();
  final _reason = TextEditingController();
  final _marks = AmountMarks.ofApp();
  final List<_TierRow> _tiers = [];
  bool _loaded = false;
  bool _busy = false;
  String? _refusal;

  @override
  void dispose() {
    _expiry.dispose();
    _qualifying.dispose();
    _reason.dispose();
    for (final t in _tiers) {
      t.dispose();
    }
    super.dispose();
  }

  void _load(LoyaltyProgramme p) {
    if (_loaded) return;
    _loaded = true;
    _expiry.text = p.expiryMonths?.toString() ?? '';
    _qualifying.text = p.qualifyingMonths?.toString() ?? '';
    for (final t in p.tiers) {
      _tiers.add(_TierRow(t, _marks));
    }
  }

  Map<String, dynamic> _body() => {
        // Whole months as typed. Blank is left out: points never expire, and
        // earning counts for a lifetime. Text that cannot be read never gets
        // here ([_refused]).
        'expiryMonths': ?wholeOf(_expiry, _marks),
        'qualifyingMonths': ?wholeOf(_qualifying, _marks),
        'tiers': [
          for (final t in _tiers)
            {
              'name': t.name.text.trim().toUpperCase(),
              // The plain decimals typed: JSON-B reads them exactly. A blank
              // multiplier is customer-svc's own 1.
              'threshold': t.thresholdPlain,
              'multiplier': ?t.multiplierPlain,
            }
        ],
        'reason': _reason.text.trim(),
      };

  /// Why [months] cannot be read as a whole number of months, in words, or
  /// null. Blank is no refusal; text that is not a number of months is, so it
  /// is never saved as blank — points that never expire.
  String? _monthsRefusal(TextEditingController months) =>
      wholeNumber.refusal(months.text.trim(), _marks);

  bool get _refused =>
      _tiers.any((t) => t.refused) ||
      _monthsRefusal(_expiry) != null ||
      _monthsRefusal(_qualifying) != null;

  Future<void> _save() async {
    if (_refused) return;
    if (_tiers.any((t) => t.thresholdPlain == null)) {
      setState(() => _refusal = 'Give every tier a threshold: the qualifying points that reach it.');
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.put(
            '/${ApiConstants.customer}/admin/loyalty/programme',
            data: _body(),
          );
      final saved = LoyaltyProgramme.fromJson(resp.data['data'] as Map<String, dynamic>);
      ref.invalidate(loyaltyProgrammeProvider);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('Programme saved: ${saved.tiers.length} tiers, points '
              '${saved.expiryMonths == null ? 'never expire' : 'live ${saved.expiryMonths} months'}.')));
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not save the programme.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _sweep() async {
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .post('/${ApiConstants.customer}/admin/loyalty/expiry/run');
      final r = LoyaltySweepResult.fromJson(resp.data['data'] as Map<String, dynamic>);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(r.customers == 0 && r.retiered == 0
              ? 'Nothing due: every point is still alive and every tier holds.'
              : '${r.points.toStringAsFixed(0)} points expired for ${r.customers} '
                  '${r.customers == 1 ? 'customer' : 'customers'}; ${r.retiered} re-tiered.')));
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not run the sweep.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final programme = ref.watch(loyaltyProgrammeProvider);
    final readOnly = heldToStores(ref.watch(authNotifierProvider).value);
    return AlertDialog(
      title: const Text('Loyalty programme'),
      content: SizedBox(
        width: 560,
        child: programme.when(
          loading: () => const SizedBox(height: 200, child: LoadingView(label: 'Loading…')),
          error: (e, _) => SizedBox(
            height: 200,
            child: ErrorView(
              message: friendlyError(e, fallback: 'Could not load the programme.'),
              onRetry: () => ref.invalidate(loyaltyProgrammeProvider),
            ),
          ),
          data: (p) {
            _load(p);
            return SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    p.isDefault
                        ? "The platform's default: four tiers on lifetime points, nothing earned "
                            'above a point a pound, points that never expire.'
                            '${readOnly ? '' : ' Save your own to change it.'}'
                        : 'Your programme, last set ${AppFormat.dateTime(p.setAt)}'
                            '${p.reason == null ? '' : ' — ${p.reason}'}.',
                    style: TextStyle(color: cs.onSurfaceVariant),
                  ),
                  const SizedBox(height: 16),
                  Text('Tiers', style: Theme.of(context).textTheme.labelLarge),
                  const SizedBox(height: 4),
                  Text(
                    'Lowest first, the first at zero. The threshold is the qualifying points that '
                    'reach the tier; the multiplier is what a sale earns per base point.',
                    style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
                  ),
                  const SizedBox(height: 8),
                  for (var i = 0; i < _tiers.length; i++)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: Row(
                        children: [
                          Expanded(
                            flex: 3,
                            child: TextField(
                              key: Key('tier-name-$i'),
                              controller: _tiers[i].name,
                              readOnly: readOnly,
                              decoration: const InputDecoration(labelText: 'Name'),
                              textCapitalization: TextCapitalization.characters,
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            flex: 2,
                            child: TextField(
                              key: Key('tier-threshold-$i'),
                              controller: _tiers[i].threshold,
                              readOnly: readOnly,
                              decoration: InputDecoration(
                                labelText: 'From (pts)',
                                errorText: _tiers[i].thresholdRefusal,
                                errorMaxLines: 4,
                              ),
                              keyboardType:
                                  const TextInputType.numberWithOptions(decimal: true),
                              onChanged: (_) => setState(() {}),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            flex: 2,
                            child: TextField(
                              key: Key('tier-multiplier-$i'),
                              controller: _tiers[i].multiplier,
                              readOnly: readOnly,
                              decoration: InputDecoration(
                                labelText: 'Earns ×',
                                errorText: _tiers[i].multiplierRefusal,
                                errorMaxLines: 4,
                              ),
                              keyboardType:
                                  const TextInputType.numberWithOptions(decimal: true),
                              onChanged: (_) => setState(() {}),
                            ),
                          ),
                          if (!readOnly)
                            IconButton(
                              key: Key('tier-remove-$i'),
                              tooltip: 'Remove tier',
                              onPressed: _tiers.length <= 1
                                  ? null
                                  : () => setState(() => _tiers.removeAt(i).dispose()),
                              icon: const Icon(Icons.remove_circle_outline),
                            ),
                        ],
                      ),
                    ),
                  if (!readOnly)
                    Align(
                      alignment: AlignmentDirectional.centerStart,
                      child: TextButton.icon(
                        key: const Key('tier-add'),
                        onPressed: _tiers.length >= 6
                            ? null
                            : () => setState(() => _tiers.add(_TierRow(
                                  const LoyaltyTier(name: '', threshold: 0, multiplier: 1),
                                  _marks,
                                ))),
                        icon: const Icon(Icons.add),
                        label: const Text('Add a tier'),
                      ),
                    ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          key: const Key('programme-expiry'),
                          controller: _expiry,
                          readOnly: readOnly,
                          decoration: InputDecoration(
                            labelText: 'Points live (months)',
                            helperText: 'Blank: never expire',
                            errorText: _monthsRefusal(_expiry),
                            errorMaxLines: 4,
                          ),
                          keyboardType: TextInputType.number,
                          onChanged: (_) => setState(() {}),
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: TextField(
                          key: const Key('programme-qualifying'),
                          controller: _qualifying,
                          readOnly: readOnly,
                          decoration: InputDecoration(
                            labelText: 'Earning counts for (months)',
                            helperText: 'Blank: a lifetime',
                            errorText: _monthsRefusal(_qualifying),
                            errorMaxLines: 4,
                          ),
                          keyboardType: TextInputType.number,
                          onChanged: (_) => setState(() {}),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  if (readOnly)
                    const Align(
                      alignment: AlignmentDirectional.centerStart,
                      child: BusinessWideNote(
                          key: Key('programme-business-wide-note'),
                          message: LoyaltyProgrammeDialog.heldNote),
                    )
                  else
                    TextField(
                      key: const Key('programme-reason'),
                      controller: _reason,
                      // customer-svc refuses a longer reason (400).
                      maxLength: 500,
                      decoration: const InputDecoration(labelText: 'Reason for the change'),
                    ),
                  if (_refusal != null) ...[
                    const SizedBox(height: 12),
                    Text(_refusal!, key: const Key('programme-refusal'),
                        style: TextStyle(color: cs.error)),
                  ],
                  const SizedBox(height: 8),
                  Text(
                    'Points already held take a new expiry with a month\'s notice; a lifted rule lets '
                    'them live. Everyone is re-tiered from what qualifies now.',
                    style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
                  ),
                ],
              ),
            );
          },
        ),
      ),
      actionsAlignment: MainAxisAlignment.end,
      actions: [
        if (!readOnly)
          TextButton.icon(
            key: const Key('programme-sweep'),
            onPressed: _busy ? null : _sweep,
            icon: const Icon(Icons.hourglass_bottom_outlined, size: 18),
            label: const Text('Run the sweep now'),
          ),
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Close')),
        if (!readOnly)
          FilledButton(
            key: const Key('programme-save'),
            onPressed: _busy || !_loaded || _refused ? null : _save,
            child: const Text('Save'),
          ),
      ],
    );
  }
}
