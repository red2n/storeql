import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import '../../core/format.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'customer_providers.dart';

/// The business's loyalty programme (13.x): the ladder of tiers — a name, the
/// qualifying points that reach it and what it earns per base point — how many
/// months a point lives, how many months of earning count towards a tier, and
/// why. Management only; the platform's default shows as such until the
/// business saves its own. "Run the sweep now" writes off dead points and
/// re-tiers everyone, and says what it did.
class LoyaltyProgrammeDialog extends ConsumerStatefulWidget {
  const LoyaltyProgrammeDialog({super.key});

  @override
  ConsumerState<LoyaltyProgrammeDialog> createState() => _LoyaltyProgrammeDialogState();
}

class _TierRow {
  final TextEditingController name;
  final TextEditingController threshold;
  final TextEditingController multiplier;
  _TierRow(LoyaltyTier t)
      : name = TextEditingController(text: t.name),
        threshold = TextEditingController(text: _num(t.threshold)),
        multiplier = TextEditingController(text: _num(t.multiplier));

  static String _num(double v) =>
      v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toString();

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
      _tiers.add(_TierRow(t));
    }
  }

  Map<String, dynamic> _body() => {
        if (_expiry.text.trim().isNotEmpty) 'expiryMonths': int.tryParse(_expiry.text.trim()) ?? -1,
        if (_qualifying.text.trim().isNotEmpty)
          'qualifyingMonths': int.tryParse(_qualifying.text.trim()) ?? -1,
        'tiers': [
          for (final t in _tiers)
            {
              'name': t.name.text.trim().toUpperCase(),
              'threshold': double.tryParse(t.threshold.text.trim()) ?? -1,
              'multiplier': double.tryParse(t.multiplier.text.trim()) ?? 1,
            }
        ],
        'reason': _reason.text.trim(),
      };

  Future<void> _save() async {
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
                            'above a point a pound, points that never expire. Save your own to change it.'
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
                              decoration: const InputDecoration(labelText: 'From (pts)'),
                              keyboardType: TextInputType.number,
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            flex: 2,
                            child: TextField(
                              key: Key('tier-multiplier-$i'),
                              controller: _tiers[i].multiplier,
                              decoration: const InputDecoration(labelText: 'Earns ×'),
                              keyboardType: TextInputType.number,
                            ),
                          ),
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
                  Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: TextButton.icon(
                      key: const Key('tier-add'),
                      onPressed: _tiers.length >= 6
                          ? null
                          : () => setState(() => _tiers.add(_TierRow(
                                const LoyaltyTier(name: '', threshold: 0, multiplier: 1),
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
                          decoration: const InputDecoration(
                            labelText: 'Points live (months)',
                            helperText: 'Blank: never expire',
                          ),
                          keyboardType: TextInputType.number,
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: TextField(
                          key: const Key('programme-qualifying'),
                          controller: _qualifying,
                          decoration: const InputDecoration(
                            labelText: 'Earning counts for (months)',
                            helperText: 'Blank: a lifetime',
                          ),
                          keyboardType: TextInputType.number,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    key: const Key('programme-reason'),
                    controller: _reason,
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
        TextButton.icon(
          key: const Key('programme-sweep'),
          onPressed: _busy ? null : _sweep,
          icon: const Icon(Icons.hourglass_bottom_outlined, size: 18),
          label: const Text('Run the sweep now'),
        ),
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Close')),
        FilledButton(
          key: const Key('programme-save'),
          onPressed: _busy || !_loaded ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}
