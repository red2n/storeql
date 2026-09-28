import 'package:flutter/material.dart';

import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/bottom_action_bar.dart';

/// The price breakdown and the one primary action of a cart or a checkout:
/// the items and their subtotal, any savings (in the success colour, with a
/// minus), delivery, the total with VAT included, notes under it, and the
/// button — with an optional nudge under that (*Spend £4.50 more for free
/// delivery*).
///
/// Labels in `on-surface-variant`, values in `on-surface`, the total 22/700.
/// Amounts come in formatted (`AppFormat.money`); a shop that hides prices
/// passes none, and the summary counts the items only.
///
/// On a phone, pass no [action] and put the button in an [OrderSummaryBar]
/// under the scroll: the button stays where the thumb is while the summary
/// scrolls with the page.
class OrderSummary extends StatelessWidget {
  final int itemCount;

  /// What the items come to; null hides every amount.
  final String? subtotal;

  /// What the shopper saves, as a positive amount; shown with a minus.
  final String? savings;

  /// The delivery charge, or a word for it (*Free*).
  final String? delivery;

  final String totalLabel;

  /// The amount to pay; null in a shop that hides prices.
  final String? total;

  /// Lines under the total: the amount in another currency, a deposit.
  final List<Widget> notes;

  /// The one primary action, full width; null when an [OrderSummaryBar]
  /// holds it.
  final Widget? action;

  /// A line under the button.
  final Widget? nudge;

  const OrderSummary({
    super.key,
    required this.itemCount,
    this.subtotal,
    this.savings,
    this.delivery,
    this.totalLabel = 'Total (incl. VAT)',
    this.total,
    this.notes = const [],
    this.action,
    this.nudge,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final labelStyle =
        theme.textTheme.bodyMedium?.copyWith(color: cs.onSurfaceVariant);
    final valueStyle = theme.textTheme.bodyMedium?.copyWith(color: cs.onSurface);

    Widget row(String label, String? value, {Color? valueColor}) =>
        MergeSemantics(
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 2),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(child: Text(label, style: labelStyle)),
                if (value != null) ...[
                  const SizedBox(width: AppSpacing.md),
                  Text(value,
                      textAlign: TextAlign.end,
                      style: valueColor == null
                          ? valueStyle
                          : valueStyle?.copyWith(color: valueColor)),
                ],
              ],
            ),
          ),
        );

    final totalAmount = total;
    final button = action;
    final under = nudge;
    return Card(
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            row('Items ($itemCount)', subtotal),
            if (savings != null)
              row('Savings', '−$savings', valueColor: context.status.success),
            if (delivery != null) row('Delivery', delivery),
            if (totalAmount != null) ...[
              const Divider(height: AppSpacing.xl),
              MergeSemantics(
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    Expanded(
                      child: Text(totalLabel,
                          style: theme.textTheme.titleMedium
                              ?.copyWith(color: cs.onSurface)),
                    ),
                    const SizedBox(width: AppSpacing.md),
                    Text(
                      totalAmount,
                      textAlign: TextAlign.end,
                      style: theme.textTheme.titleLarge?.copyWith(
                        fontSize: 22,
                        fontWeight: FontWeight.w700,
                        color: cs.onSurface,
                      ),
                    ),
                  ],
                ),
              ),
            ],
            for (final note in notes)
              Padding(
                padding: const EdgeInsets.only(top: AppSpacing.xs),
                child: note,
              ),
            if (button != null) ...[
              const SizedBox(height: AppSpacing.lg),
              button,
            ],
            if (under != null) ...[
              const SizedBox(height: AppSpacing.sm),
              DefaultTextStyle.merge(
                style: labelStyle,
                textAlign: TextAlign.center,
                child: under,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// A phone's [OrderSummary] action, held under the scroll: the total (or, in
/// a shop that hides prices, the item count) and the button beside it, the
/// button taking three fifths so a label like *Review order* stays on one
/// line on a 360-wide phone.
class OrderSummaryBar extends StatelessWidget {
  final int itemCount;
  final String totalLabel;

  /// The amount to pay; null in a shop that hides prices.
  final String? total;

  final Widget action;

  const OrderSummaryBar({
    super.key,
    required this.itemCount,
    required this.action,
    this.total,
    this.totalLabel = 'Total (incl. VAT)',
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final amount = total;
    return BottomActionBar(
      // The summary scrolls under it, so it floats (shadow-2).
      raised: true,
      child: Row(
        children: [
          Expanded(
            flex: 2,
            child: amount != null
                ? MergeSemantics(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(totalLabel,
                            style: theme.textTheme.labelMedium
                                ?.copyWith(color: cs.onSurfaceVariant)),
                        Text(amount,
                            style: theme.textTheme.titleLarge?.copyWith(
                                fontWeight: FontWeight.bold,
                                color: cs.onSurface)),
                      ],
                    ),
                  )
                : Text('$itemCount item${itemCount == 1 ? '' : 's'}',
                    style: theme.textTheme.titleMedium),
          ),
          const SizedBox(width: AppSpacing.lg),
          Expanded(flex: 3, child: action),
        ],
      ),
    );
  }
}
