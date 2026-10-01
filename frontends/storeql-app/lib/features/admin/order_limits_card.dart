import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import 'widgets/business_wide_note.dart';

/// How long an unpaid order is held, and how long an order may wait for its
/// price (order-svc, 30 Sep 2026). The price waits are off until set.
class OrderLimits {
  /// The business's own hold on an unpaid order, in hours; null while it has
  /// set none and the platform's default stands.
  final int? pendingLimitHours;

  /// The hours in force: the business's own, else the platform's default.
  final int effectiveHours;
  final bool usingDefault;

  /// Minutes an order waits for a price before a manager is told, and before it
  /// is cancelled and its stock released; null for never.
  final int? flagMinutes;
  final int? cancelMinutes;

  const OrderLimits({
    this.pendingLimitHours,
    required this.effectiveHours,
    required this.usingDefault,
    this.flagMinutes,
    this.cancelMinutes,
  });
}

final orderLimitsProvider = FutureProvider.autoDispose<OrderLimits>((ref) async {
  final dio = ref.read(apiClientProvider).dio;
  final pending = await dio.get('/${ApiConstants.order}/admin/orders/settings/pending-limit');
  final wait = await dio.get('/${ApiConstants.order}/admin/orders/settings/price-wait');
  final p = pending.data['data'] as Map<String, dynamic>;
  final w = wait.data['data'] as Map<String, dynamic>;
  return OrderLimits(
    pendingLimitHours: (p['pendingLimitHours'] as num?)?.toInt(),
    effectiveHours: (p['effectiveHours'] as num?)?.toInt() ?? 0,
    usingDefault: p['usingDefault'] as bool? ?? p['pendingLimitHours'] == null,
    flagMinutes: (w['flagMinutes'] as num?)?.toInt(),
    cancelMinutes: (w['cancelMinutes'] as num?)?.toInt(),
  );
});

/// A number of minutes in words: `45 minutes`, `1 hour`, `2 hours 30 minutes`.
String minutesInWords(int minutes) {
  final h = minutes ~/ 60;
  final m = minutes % 60;
  final parts = [
    if (h > 0) '$h ${h == 1 ? 'hour' : 'hours'}',
    if (m > 0 || h == 0) '$m ${m == 1 ? 'minute' : 'minutes'}',
  ];
  return parts.join(' ');
}

String _hoursInWords(int hours) => '$hours ${hours == 1 ? 'hour' : 'hours'}';

/// The two order time limits: an owner's or manager's to read, a caller held to
/// no store's to change. Any other is told who does.
class OrderLimitsCard extends ConsumerWidget {
  const OrderLimitsCard({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final limits = ref.watch(orderLimitsProvider);
    final auth = ref.watch(authNotifierProvider).value;
    final canChange = auth is AuthAuthenticated && auth.isManager && !auth.heldToStores;
    return Card(
        key: const Key('order-limits-card'),
        child: Padding(
          padding: AppSpacing.cardPadding,
          child: limits.when(
            loading: () => Row(children: [
              const SizedBox.square(
                  dimension: AppSpacing.lg, child: CircularProgressIndicator(strokeWidth: 2)),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Text('Loading the order time limits…',
                    style: TextStyle(color: cs.onSurfaceVariant)),
              ),
            ]),
            error: (e, _) => Row(children: [
              Icon(Icons.timer_outlined, color: cs.onSurfaceVariant),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(
                  friendlyError(e, fallback: 'Could not load the order time limits.'),
                  key: const Key('order-limits-error'),
                  style: TextStyle(color: cs.error),
                ),
              ),
              TextButton(
                onPressed: () => ref.invalidate(orderLimitsProvider),
                child: const Text('Retry'),
              ),
            ]),
            data: (l) => Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Wrap(
                  alignment: WrapAlignment.spaceBetween,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  spacing: AppSpacing.sm,
                  runSpacing: AppSpacing.sm,
                  children: [
                    Row(mainAxisSize: MainAxisSize.min, children: [
                      Icon(Icons.timer_outlined, color: cs.onSurfaceVariant),
                      const SizedBox(width: AppSpacing.sm),
                      Flexible(
                        child: Text('Order time limits',
                            style: Theme.of(context).textTheme.titleMedium),
                      ),
                    ]),
                    if (canChange)
                      FilledButton.tonalIcon(
                        key: const Key('order-limits-edit'),
                        onPressed: () => showDialog<void>(
                            context: context, builder: (_) => OrderLimitsDialog(limits: l)),
                        icon: const Icon(Icons.edit_outlined, size: 18),
                        label: const Text('Edit'),
                      ),
                  ],
                ),
                const SizedBox(height: AppSpacing.xs),
                Text(
                  summary(l),
                  key: const Key('order-limits-summary'),
                  style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
                ),
                if (!canChange) ...[
                  const SizedBox(height: AppSpacing.sm),
                  const BusinessWideNote(key: Key('order-limits-business-wide-note')),
                ],
              ],
            ),
          ),
        ),
    );
  }

  static String summary(OrderLimits l) {
    final unpaid = l.usingDefault
        ? "An unpaid order is held for ${_hoursInWords(l.effectiveHours)}, the platform's own limit, then cancelled."
        : 'An unpaid order is held for ${_hoursInWords(l.effectiveHours)}, then cancelled.';
    final flag = l.flagMinutes;
    final cancel = l.cancelMinutes;
    final String wait;
    if (flag == null && cancel == null) {
      wait = 'An order waiting for a price has no limit: nobody is told and it is never cancelled.';
    } else {
      wait = [
        if (flag != null)
          'A manager is told when an order has waited ${minutesInWords(flag)} for a price.'
        else
          'Nobody is told when an order waits for a price.',
        if (cancel != null)
          'It is cancelled, and its stock released, after ${minutesInWords(cancel)}.'
        else
          'It is never cancelled for waiting.',
      ].join(' ');
    }
    return '$unpaid $wait';
  }
}

/// Edit the limits: the unpaid hold in whole hours (empty for the platform's
/// default) and the two price waits in minutes (empty for never). A refusal is
/// shown in words and the dialog stays open.
class OrderLimitsDialog extends ConsumerStatefulWidget {
  const OrderLimitsDialog({super.key, required this.limits});
  final OrderLimits limits;

  @override
  ConsumerState<OrderLimitsDialog> createState() => _OrderLimitsDialogState();
}

class _OrderLimitsDialogState extends ConsumerState<OrderLimitsDialog> {
  late final TextEditingController _hours;
  late final TextEditingController _flag;
  late final TextEditingController _cancel;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final l = widget.limits;
    _hours = TextEditingController(text: l.pendingLimitHours?.toString() ?? '');
    _flag = TextEditingController(text: l.flagMinutes?.toString() ?? '');
    _cancel = TextEditingController(text: l.cancelMinutes?.toString() ?? '');
  }

  @override
  void dispose() {
    _hours.dispose();
    _flag.dispose();
    _cancel.dispose();
    super.dispose();
  }

  /// Empty is "not set" (null, true); otherwise a whole number of at least 1.
  static (bool, int?) _whole(String text) {
    final t = text.trim();
    if (t.isEmpty) return (true, null);
    final v = int.tryParse(t);
    return v == null || v < 1 ? (false, null) : (true, v);
  }

  Future<void> _save() async {
    final (hoursOk, hours) = _whole(_hours.text);
    final (flagOk, flag) = _whole(_flag.text);
    final (cancelOk, cancel) = _whole(_cancel.text);
    if (!hoursOk) {
      setState(() => _error = 'The unpaid hold is a whole number of hours, at least 1, or empty for the default.');
      return;
    }
    if (!flagOk || !cancelOk) {
      setState(() => _error = 'A price wait is a whole number of minutes, at least 1, or empty for never.');
      return;
    }
    if (flag != null && cancel != null && cancel < flag) {
      setState(() => _error = 'An order cannot be cancelled before a manager is told: the second wait is not shorter than the first.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final dio = ref.read(apiClientProvider).dio;
      await dio.put('/${ApiConstants.order}/admin/orders/settings/pending-limit',
          data: {'pendingLimitHours': hours});
      await dio.put('/${ApiConstants.order}/admin/orders/settings/price-wait',
          data: {'flagMinutes': flag, 'cancelMinutes': cancel});
      ref.invalidate(orderLimitsProvider);
      if (!mounted) return;
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Order time limits saved.')));
    } catch (e) {
      // A limit may have landed before the other was refused: show what is now held.
      ref.invalidate(orderLimitsProvider);
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = friendlyError(e, fallback: 'Could not save the order time limits.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Order time limits'),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                'How long an order may wait. Leave a field empty to use the default, or for no limit.',
                style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
              ),
              const SizedBox(height: AppSpacing.md),
              TextField(
                key: const Key('order-limit-hours'),
                controller: _hours,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  labelText: 'Hold an unpaid order for (hours)',
                  helperText: "Empty uses the platform's own limit.",
                ),
              ),
              const SizedBox(height: AppSpacing.md),
              TextField(
                key: const Key('order-limit-flag'),
                controller: _flag,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  labelText: 'Tell a manager after waiting for a price (minutes)',
                  helperText: 'Empty: nobody is told.',
                ),
              ),
              const SizedBox(height: AppSpacing.md),
              TextField(
                key: const Key('order-limit-cancel'),
                controller: _cancel,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  labelText: 'Cancel after waiting for a price (minutes)',
                  helperText: 'Empty: never cancelled. Its stock is released when it is.',
                ),
              ),
              if (_error != null) ...[
                const SizedBox(height: AppSpacing.md),
                Text(_error!, key: const Key('order-limits-form-error'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('order-limits-save'),
          onPressed: _busy ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}
