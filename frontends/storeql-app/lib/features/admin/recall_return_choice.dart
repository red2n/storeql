import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/spacing.dart';
import '../../core/theme.dart';
import 'providers/admin_providers.dart';
import 'recall_providers.dart';

/// "This is a recall return" in the two return dialogs (the admin
/// Return / Refund and the till's Returns screen).
///
/// When the order has recall notices still open, the person may say the goods
/// are coming back because of a recall and pick the notice; the return then
/// carries its `recallNoticeId`, asks no condition for the recalled lines (the
/// server sends the goods to recalled stock) and is not held by the return
/// policy. With no open notice — or while they load, or if they cannot be read —
/// nothing is shown at all.
class RecallReturnChoice extends ConsumerWidget {
  const RecallReturnChoice({
    super.key,
    required this.orderId,
    required this.selected,
    required this.onChanged,
  });

  final String orderId;

  /// The notice the return settles, or null for an ordinary return.
  final RecallNotice? selected;
  final ValueChanged<RecallNotice?> onChanged;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final notices = ref.watch(orderRecallNoticesProvider(orderId)).value ?? const <RecallNotice>[];
    if (notices.isEmpty) return const SizedBox.shrink();
    final labels = ref
            .watch(variantLabelsProvider(variantIdsKey([
              for (final n in notices)
                for (final l in n.lines) l.variantId,
            ])))
            .value ??
        const <String, VariantLabel>{};
    final cs = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    /// "Recall R-2026-017 · Crunchy peanut butter, lot L1, best before …".
    String describe(RecallNotice n) {
      final what = [
        for (final l in n.lines)
          [
            (l.productName ?? '').isNotEmpty ? l.productName! : variantDisplayName(l.variantId, labels),
            if ((l.batchNo ?? '').isNotEmpty) 'lot ${l.batchNo}',
          ].join(', '),
      ].join('; ');
      return 'Recall ${n.reference}${what.isEmpty ? '' : ' · $what'}';
    }

    return Padding(
      padding: const EdgeInsetsDirectional.only(top: AppSpacing.md),
      child: Material(
        key: const Key('recall-return-choice'),
        color: cs.surfaceContainerHighest,
        borderRadius: AppRadius.chip,
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SwitchListTile(
                key: const Key('recall-return-switch'),
                contentPadding: EdgeInsets.zero,
                title: const Text('This is a recall return'),
                subtitle: Text(notices.length == 1
                    ? describe(notices.single)
                    : 'This order is affected by ${notices.length} recalls. Choose which one.'),
                value: selected != null,
                onChanged: (on) => onChanged(on ? notices.first : null),
              ),
              if (selected != null) ...[
                if (notices.length > 1)
                  Wrap(
                    spacing: AppSpacing.sm,
                    runSpacing: AppSpacing.xs,
                    children: [
                      for (final n in notices)
                        ChoiceChip(
                          key: Key('recall-return-notice-${n.id}'),
                          label: Text(describe(n)),
                          selected: selected!.id == n.id,
                          onSelected: (_) => onChanged(n),
                        ),
                    ],
                  ),
                const SizedBox(height: AppSpacing.xs),
                Text(
                  'The recalled goods go to recalled stock, so no condition is asked for them. '
                  'The refund is not held by the return policy: no window or limit applies.',
                  key: const Key('recall-return-hint'),
                  style: textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
