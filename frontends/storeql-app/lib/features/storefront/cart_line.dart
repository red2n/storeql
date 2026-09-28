import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';

import '../../core/spacing.dart';
import '../../core/theme.dart';
import 'storefront_widgets.dart';

/// One line of a cart (the design system's *CartLine*; the name `CartLine` is
/// the cart's data model): the thumbnail, the name with its pack and unit
/// price, the amber quantity stepper and the line total.
///
/// Lines sit together in one card, separated by a divider inset past the
/// thumbnail ([dividerIndent]). The stepper and the total sit beside the text
/// in a wide row and on a line under it below 600, so the name keeps the
/// row's width instead of wrapping to three lines.
///
/// At one, the stepper's minus is a bin — *Remove from cart*. With
/// [swipeToRemove] (touch screens) the line also swipes away towards the
/// start; a screen reader gets the same as a *Remove from cart* action. The
/// consumer offers *Undo* for both.
class CartLineTile extends StatelessWidget {
  /// Tells the lines apart for the swipe; the variant id in a cart.
  final String id;

  final String name;

  /// The pack or SKU, and the unit price when prices are shown.
  final String detail;

  /// Anything more under [detail], such as the price per kilogram.
  final Widget? extra;

  /// The product's picture; its coloured initials, seeded by [thumbSeed],
  /// when null.
  final Widget? image;
  final String thumbSeed;

  final int qty;
  final VoidCallback onInc;

  /// Takes one off; the stepper calls [onRemove] instead at one.
  final VoidCallback onDec;
  final VoidCallback onRemove;

  /// The line's total, formatted; null in a shop that hides prices.
  final String? lineTotal;

  final bool swipeToRemove;

  const CartLineTile({
    super.key,
    required this.id,
    required this.name,
    required this.detail,
    required this.thumbSeed,
    required this.qty,
    required this.onInc,
    required this.onDec,
    required this.onRemove,
    this.extra,
    this.image,
    this.lineTotal,
    this.swipeToRemove = false,
  });

  static const double _thumb = 48;
  static const EdgeInsetsGeometry _padding = EdgeInsetsDirectional.symmetric(
      horizontal: AppSpacing.lg, vertical: AppSpacing.md);

  /// Where the divider between two lines starts: past the thumbnail, under
  /// the text.
  static const double dividerIndent = AppSpacing.lg + _thumb + AppSpacing.md;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final thumb = SizedBox.square(
      dimension: _thumb,
      // Decorative: the name is beside it, and its initials would otherwise be
      // read out as a word.
      child: image ??
          ExcludeSemantics(
            child: ProductThumb(
              seed: thumbSeed,
              label: name,
              fontSize: 16,
              borderRadius: AppRadius.chip,
            ),
          ),
    );
    final text = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(name,
            style: theme.textTheme.bodyLarge?.copyWith(color: cs.onSurface)),
        const SizedBox(height: 2),
        Text(
          detail,
          style:
              theme.textTheme.bodyMedium?.copyWith(color: cs.onSurfaceVariant),
        ),
        ?extra,
      ],
    );
    final stepper = QuantityStepper(
      qty: qty,
      onDec: onDec,
      onInc: onInc,
      onRemove: onRemove,
    );
    final total = lineTotal;
    // Sized and inked as a figure the shopper reads, never the smallest text on the screen.
    final totalText = total == null
        ? null
        : Text(
            total,
            textAlign: TextAlign.end,
            style: theme.textTheme.titleSmall?.copyWith(
              color: cs.onSurface,
              fontWeight: FontWeight.w700,
            ),
          );
    final row = Padding(
      padding: _padding,
      child: LayoutBuilder(
        builder: (context, constraints) {
          if (AppBreakpoints.classOf(constraints.maxWidth) ==
              WindowClass.compact) {
            return Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                thumb,
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      text,
                      const SizedBox(height: AppSpacing.sm),
                      Row(
                        children: [
                          stepper,
                          if (totalText != null) ...[
                            const SizedBox(width: AppSpacing.lg),
                            Expanded(child: totalText),
                          ],
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            );
          }
          return Row(
            children: [
              thumb,
              const SizedBox(width: AppSpacing.md),
              Expanded(child: text),
              const SizedBox(width: AppSpacing.lg),
              stepper,
              if (totalText != null) ...[
                const SizedBox(width: AppSpacing.lg),
                ConstrainedBox(
                  constraints: const BoxConstraints(minWidth: 80),
                  child: totalText,
                ),
              ],
            ],
          );
        },
      ),
    );
    if (!swipeToRemove) return row;
    return Semantics(
      customSemanticsActions: {
        const CustomSemanticsAction(label: 'Remove from cart'): onRemove,
      },
      child: Dismissible(
        key: ValueKey('cart-line-swipe-$id'),
        direction: DismissDirection.endToStart,
        onDismissed: (_) => onRemove(),
        background: ColoredBox(
          color: cs.errorContainer,
          child: Align(
            alignment: AlignmentDirectional.centerEnd,
            child: Padding(
              padding: const EdgeInsetsDirectional.only(end: AppSpacing.xl),
              child: Icon(Icons.delete_outline, color: cs.onErrorContainer),
            ),
          ),
        ),
        child: row,
      ),
    );
  }
}
