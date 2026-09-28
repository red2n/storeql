import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';

/// One exchange rate the business keeps (03.x): home units per one unit of
/// the other currency, from a day, with a reason.
class FxRate {
  final String currency;
  final double rate;
  final String effectiveFrom;
  final String? reason;
  const FxRate({
    required this.currency,
    required this.rate,
    required this.effectiveFrom,
    this.reason,
  });

  factory FxRate.fromJson(Map<String, dynamic> j) => FxRate(
        currency: j['currency'] as String? ?? '',
        rate: (j['rate'] as num?)?.toDouble() ?? 0,
        effectiveFrom: j['effectiveFrom'] as String? ?? '',
        reason: j['reason'] as String?,
      );
}

/// The sheet: the home currency and the rate in force per other currency.
class FxRateSheet {
  final String home;
  final List<FxRate> rates;
  const FxRateSheet({required this.home, required this.rates});

  factory FxRateSheet.fromJson(Map<String, dynamic> j) => FxRateSheet(
        home: j['home'] as String? ?? '',
        rates: ((j['rates'] as List?) ?? [])
            .map((e) => FxRate.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

final fxRatesProvider = FutureProvider.autoDispose<FxRateSheet>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.tenant}/admin/tenant/fx-rates');
  return FxRateSheet.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// The business's exchange rates on the Pricing screen (03.x): the home
/// currency, each rate in force with its day and reason, and — for management —
/// **Set a rate**. A rate is the business's own; the platform fetches none.
class FxRatesCard extends ConsumerWidget {
  const FxRatesCard({super.key, required this.management});

  /// Whether the viewer may set a rate (OWNER, MANAGER, PLATFORM_ADMIN).
  final bool management;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final sheet = ref.watch(fxRatesProvider);
    // The page's gutter, so the card lines up with the Pricing title and the lists under it.
    final gutter = context.pageGutter;
    return Padding(
      padding: EdgeInsetsDirectional.fromSTEB(gutter, AppSpacing.md, gutter, 0),
      child: Card(
        key: const Key('fx-rates-card'),
        child: Padding(
          padding: AppSpacing.cardPadding,
          child: sheet.when(
            // Compact states: the card sits above a list and must not claim the page.
            loading: () => Row(children: [
              const SizedBox.square(
                  dimension: AppSpacing.lg, child: CircularProgressIndicator(strokeWidth: 2)),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Text('Loading exchange rates…',
                    style: TextStyle(color: cs.onSurfaceVariant)),
              ),
            ]),
            error: (e, _) => Row(children: [
              Icon(Icons.currency_exchange_outlined, color: cs.onSurfaceVariant),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(
                  friendlyError(e, fallback: 'Could not load the exchange rates.'),
                  key: const Key('fx-rates-error'),
                  style: TextStyle(color: cs.error),
                ),
              ),
              TextButton(
                onPressed: () => ref.invalidate(fxRatesProvider),
                child: const Text('Retry'),
              ),
            ]),
            data: (s) => Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // The title at the start, Set a rate at the end; the button moves under the
                // title when the two do not fit one line (a phone at large text).
                Wrap(
                  alignment: WrapAlignment.spaceBetween,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  spacing: AppSpacing.sm,
                  runSpacing: AppSpacing.sm,
                  children: [
                    Row(mainAxisSize: MainAxisSize.min, children: [
                      Icon(Icons.currency_exchange_outlined, color: cs.onSurfaceVariant),
                      const SizedBox(width: AppSpacing.sm),
                      Flexible(
                        child: Text('Exchange rates',
                            style: Theme.of(context).textTheme.titleMedium),
                      ),
                    ]),
                    if (management)
                      FilledButton.tonalIcon(
                        key: const Key('fx-set-rate'),
                        onPressed: () => showDialog<void>(
                            context: context, builder: (_) => SetFxRateDialog(home: s.home)),
                        icon: const Icon(Icons.add, size: 18),
                        label: const Text('Set a rate'),
                      ),
                  ],
                ),
                const SizedBox(height: AppSpacing.xs),
                Text(
                  s.rates.isEmpty
                      ? 'Prices are in ${s.home}. Keep a rate for another currency and shoppers can '
                          'see prices in it, and a supplier\'s order in it is measured in ${s.home} '
                          'for spend authority.'
                      : 'Home units of ${s.home} per one unit of each currency, as the business set them.',
                  style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
                ),
                if (s.rates.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      for (final r in s.rates)
                        Tooltip(
                          message: '${r.reason ?? ''} · from ${AppFormat.date(r.effectiveFrom)}'.trim(),
                          child: Container(
                            key: Key('fx-rate-${r.currency}'),
                            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                            decoration: BoxDecoration(
                                color: cs.surfaceContainerHigh, borderRadius: AppRadius.chip),
                            child: Text(
                              '1 ${r.currency} = ${_trim(r.rate)} ${s.home}',
                              style: const TextStyle(fontWeight: FontWeight.w600),
                            ),
                          ),
                        ),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }

  static String _trim(double v) {
    var t = v.toStringAsFixed(6);
    t = t.replaceFirst(RegExp(r'0+$'), '');
    return t.endsWith('.') ? t.substring(0, t.length - 1) : t;
  }
}

/// Set a rate: the currency, how many home units one unit buys, from a day,
/// and why. A refusal is shown by name.
class SetFxRateDialog extends ConsumerStatefulWidget {
  const SetFxRateDialog({super.key, required this.home});
  final String home;

  @override
  ConsumerState<SetFxRateDialog> createState() => _SetFxRateDialogState();
}

class _SetFxRateDialogState extends ConsumerState<SetFxRateDialog> {
  final _currency = TextEditingController();
  final _rate = TextEditingController();
  final _from = TextEditingController();
  final _reason = TextEditingController();
  bool _busy = false;
  String? _refusal;

  @override
  void dispose() {
    _currency.dispose();
    _rate.dispose();
    _from.dispose();
    _reason.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      final code = _currency.text.trim().toUpperCase();
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.tenant}/admin/tenant/fx-rates/$code',
        data: {
          'rate': double.tryParse(_rate.text.trim()) ?? -1,
          if (_from.text.trim().isNotEmpty) 'effectiveFrom': _from.text.trim(),
          'reason': _reason.text.trim(),
        },
      );
      ref.invalidate(fxRatesProvider);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('1 $code = ${_rate.text.trim()} ${widget.home} from '
              '${_from.text.trim().isEmpty ? 'today' : _from.text.trim()}.')));
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
      title: const Text('Set an exchange rate'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'How many ${widget.home} one unit of the currency buys today, e.g. 0.79 for USD.'
              ' Rates are yours to keep; a new rate never edits an old one.',
              style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
            ),
            const SizedBox(height: 12),
            TextField(
              key: const Key('fx-currency'),
              controller: _currency,
              decoration: const InputDecoration(labelText: 'Currency (ISO 4217)', hintText: 'USD'),
              textCapitalization: TextCapitalization.characters,
            ),
            const SizedBox(height: 8),
            TextField(
              key: const Key('fx-rate'),
              controller: _rate,
              decoration: InputDecoration(labelText: '${widget.home} per one unit'),
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
            ),
            const SizedBox(height: 8),
            TextField(
              key: const Key('fx-from'),
              controller: _from,
              decoration: const InputDecoration(
                  labelText: 'From (yyyy-MM-dd)', helperText: 'Blank: today; at most a month ahead'),
            ),
            const SizedBox(height: 8),
            TextField(
              key: const Key('fx-reason'),
              controller: _reason,
              decoration: const InputDecoration(labelText: 'Reason (the source and its day)'),
            ),
            if (_refusal != null) ...[
              const SizedBox(height: 12),
              Text(_refusal!, key: const Key('fx-refusal'), style: TextStyle(color: cs.error)),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(
          key: const Key('fx-save'),
          onPressed: _busy ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}
