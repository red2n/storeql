import 'package:flutter/material.dart';

import '../../core/theme.dart';

/// Availability, in words, wherever a shop hides prices and shows stock
/// instead: *Out of stock*, *In stock*, or — once the business has set a
/// storefront stock-signal threshold and this variant is at or under it —
/// *Only N left*. The one badge for the product card and the product page
/// alike, so a variant reads the same everywhere it is offered.
///
/// [onlyLeft] is never a number above the business's threshold, and never a
/// number at all when no threshold is set: pass exactly what
/// `storefrontAvailabilityProvider` reports, and it never
/// shows a count it wasn't given.
class StockBadge extends StatelessWidget {
  final bool inStock;

  /// The whole units left at this store; null when no threshold is set,
  /// the quantity is above it, the item is out of stock, or it is a weighed
  /// good with no whole-unit count.
  final int? onlyLeft;

  const StockBadge({super.key, required this.inStock, this.onlyLeft});

  @override
  Widget build(BuildContext context) {
    final left = onlyLeft;
    final low = inStock && left != null;
    final color = !inStock
        ? Theme.of(context).colorScheme.onSurfaceVariant
        : low
        ? context.status.warning
        : context.status.success;
    final label = !inStock
        ? 'Out of stock'
        : low
        ? 'Only $left left'
        : 'In stock';
    final icon = !inStock
        ? Icons.remove_circle_outline
        : low
        ? Icons.error_outline
        : Icons.check_circle;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 15, color: color),
        const SizedBox(width: 4),
        Flexible(
          child: Text(
            label,
            style: TextStyle(
              color: color,
              fontWeight: FontWeight.w600,
              fontSize: 13,
            ),
          ),
        ),
      ],
    );
  }
}
