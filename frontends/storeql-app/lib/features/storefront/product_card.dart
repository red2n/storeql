import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/spacing.dart';
import '../../shared/widgets/skeleton.dart';
import '../../shared/widgets/status_badge.dart';
import 'storefront_providers.dart';
import 'storefront_widgets.dart';

/// The storefront's product tile: the picture, the name, and under it the
/// price, the unit price, any announced reduction, the stock and the add
/// control ([OfferPriceAdd], which carries the [StockBadge] and the stepper).
///
/// A flat card on its `outline-variant` hairline with the large corner, the
/// picture edge to edge above `space-md` padding. The price is information,
/// not an action, so it stays in the page's ink. The whole tile opens the
/// product; the add control inside it is its own button.
///
/// For grids — the shop, search results, a category — sized by
/// [ProductCard.gridFor], so the picture is square and the text under it has
/// room at any text size. The picture takes whatever height the tile leaves,
/// so the text is never cut off.
class ProductCard extends StatelessWidget {
  final StoreProduct product;

  /// A short promotion word over the picture (*Offer*, *New*), on the
  /// `primary-container` badge; none when null.
  final String? promoLabel;

  const ProductCard({super.key, required this.product, this.promoLabel});

  /// The narrowest and widest a tile aims to be at 100% text; both grow with
  /// the text size, up to double, so enlarged text gets wider tiles.
  static const double minTileWidth = 180;
  static const double maxTileWidth = 220;

  /// Room for two lines of name, the price, a unit price and the stock under
  /// the picture at 100% text; it grows with the text size. More lines than
  /// that (a reduction, a price in another currency) take their room from
  /// the picture, never from the text.
  static const double textExtent = 130;

  /// The grid for [width] of content (inside the page gutters) at
  /// [textScale]: as many columns as keep tiles near 180–220 wide, never
  /// fewer than two, and each tile as tall as its width plus the text under
  /// the picture.
  static ({int columns, double tileWidth, double tileHeight}) gridFor(
    double width, {
    double textScale = 1,
    double spacing = AppSpacing.md,
  }) {
    final grow = textScale.clamp(1.0, 2.0);
    final minTile = minTileWidth * grow;
    final maxTile = maxTileWidth * grow;
    double tile(int n) => (width - spacing * (n - 1)) / n;
    // The fewest columns that keep a tile no wider than the widest…
    var columns = ((width + spacing) / (maxTile + spacing)).ceil();
    // …unless that squeezes a tile under the narrowest.
    while (columns > 2 && tile(columns) < minTile) {
      columns--;
    }
    if (columns < 2) columns = 2;
    final tileWidth = tile(columns);
    return (
      columns: columns,
      tileWidth: tileWidth,
      tileHeight: tileWidth + textExtent * textScale.clamp(1.0, 3.0),
    );
  }

  @override
  Widget build(BuildContext context) {
    final promo = promoLabel;
    return Semantics(
      button: true,
      hint: 'Opens the product details',
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () => context.go('/store/products/${product.id}'),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Expanded(
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    ProductImageThumb(
                      key: const Key('product-card-media'),
                      productId: product.id,
                      label: product.name,
                      fontSize: 36,
                    ),
                    if (promo != null)
                      PositionedDirectional(
                        top: AppSpacing.sm,
                        start: AppSpacing.sm,
                        child: StatusBadge(promo, tone: StatusTone.accent),
                      ),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.all(AppSpacing.md),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      product.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.w600),
                    ),
                    const SizedBox(height: AppSpacing.xs),
                    OfferPriceAdd(product: product),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// A [ProductCard] while the shop loads: the picture, two lines of name and
/// the price, in grey. Put a grid of them inside one [Skeleton].
class ProductCardSkeleton extends StatelessWidget {
  const ProductCardSkeleton({super.key});

  @override
  Widget build(BuildContext context) => const Card(
        clipBehavior: Clip.antiAlias,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(child: SkeletonBlock(borderRadius: BorderRadius.zero)),
            Padding(
              padding: EdgeInsets.all(AppSpacing.md),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SkeletonLine(),
                  SizedBox(height: AppSpacing.xs),
                  SkeletonLine(widthFactor: 0.6),
                  SizedBox(height: AppSpacing.md),
                  SkeletonLine(widthFactor: 0.35, fontSize: 16),
                  SizedBox(height: AppSpacing.sm),
                  SkeletonLine(widthFactor: 0.45, fontSize: 12),
                ],
              ),
            ),
          ],
        ),
      );
}
