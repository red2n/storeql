import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/format.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/bottom_action_bar.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'storefront_providers.dart';
import 'storefront_widgets.dart';
import 'unit_price.dart';
import 'allergen_summary.dart';
import 'product_safety_section.dart';

/// A product's page. The shell's app bar already names the shop above it, so
/// the page has no bar of its own: a slim row at the top of the content
/// carries the way back and the product's name.
class ProductDetailScreen extends ConsumerWidget {
  final String productId;

  const ProductDetailScreen({super.key, required this.productId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final productAsync = ref.watch(storefrontProductProvider(productId));
    return productAsync.when(
      loading: () => const Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _BackRow(),
          Expanded(child: LoadingView(label: 'Loading…')),
        ],
      ),
      error: (e, _) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const _BackRow(),
          Expanded(
            child: ErrorView(
              message: friendlyError(e, fallback: 'Could not load product.'),
              onRetry: () => ref.invalidate(storefrontProductProvider(productId)),
            ),
          ),
        ],
      ),
      data: (product) => _ProductDetail(product: product),
    );
  }
}

/// The way back to the shop, and the product's name once it has loaded.
class _BackRow extends StatelessWidget {
  final String? title;

  const _BackRow({this.title});

  @override
  Widget build(BuildContext context) {
    final gutter = context.pageGutter;
    final name = title;
    return Padding(
      // Less than the gutter by the button's own inset, so its arrow lines up
      // with the content under it.
      padding: EdgeInsetsDirectional.fromSTEB(
          gutter - AppSpacing.md, AppSpacing.sm, gutter, 0),
      child: Row(
        children: [
          IconButton(
            icon: const Icon(Icons.arrow_back),
            tooltip: 'Back to products',
            onPressed: () => context.go('/store/products'),
          ),
          if (name != null) ...[
            const SizedBox(width: AppSpacing.xs),
            Expanded(
              child: Semantics(
                header: true,
                child: Text(name, style: Theme.of(context).textTheme.titleLarge),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// The picture, the options to buy with their prices, then what the product is
/// and its safety information. From 840 wide the picture takes the start
/// column and the rest sits beside it; below that it is one column, where a
/// phone keeps a lone option's price and *Add* in a bar under the scroll.
class _ProductDetail extends ConsumerWidget {
  final StoreProduct product;

  const _ProductDetail({required this.product});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final variantsAsync = ref.watch(storefrontVariantsProvider(product.id));
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final gutter = context.pageGutter;

    return LayoutBuilder(builder: (context, constraints) {
      final windowClass = AppBreakpoints.classOf(constraints.maxWidth);
      final loaded = variantsAsync.value;
      // A product sold one way: on a phone its price and Add stay in view in the
      // bar. With several options each card carries its own.
      final barVariant = windowClass == WindowClass.compact &&
              loaded != null &&
              loaded.length == 1
          ? loaded.first
          : null;

      final image = ProductImageThumb(
        productId: product.id,
        label: product.name,
        fontSize: 72,
        borderRadius: AppRadius.input,
      );
      // The options (price and Add) first, where they are seen without
      // scrolling; the description and the safety card after them.
      final details = <Widget>[
        Text('Options',
            style: theme.textTheme.titleMedium
                ?.copyWith(fontWeight: FontWeight.bold)),
        const SizedBox(height: AppSpacing.sm),
        variantsAsync.when(
          loading: () => const Padding(
            padding: EdgeInsets.all(16),
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (e, _) => Text(
              friendlyError(e, fallback: 'Could not load options.'),
              style: TextStyle(color: cs.error)),
          data: (variants) {
            if (variants.isEmpty) {
              return Text('No purchasable options.',
                  style: TextStyle(color: cs.outline));
            }
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (final v in variants)
                  Padding(
                    padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                    child: _VariantRow(
                      product: product,
                      variant: v,
                      showBuy: barVariant == null,
                    ),
                  ),
              ],
            );
          },
        ),
        if (product.description != null) ...[
          const SizedBox(height: AppSpacing.lg),
          Text(product.description!,
              style: TextStyle(color: cs.onSurfaceVariant)),
        ],
        // Shown with the offer, before anyone signs in (GPSR art.19).
        ProductSafetySection(productId: product.id),
      ];

      final Widget content;
      if (windowClass >= WindowClass.expanded) {
        content = ContentBounds(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              _BackRow(title: product.name),
              Padding(
                padding: EdgeInsetsDirectional.fromSTEB(
                    gutter, AppSpacing.lg, gutter, gutter),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      flex: 2,
                      child: AspectRatio(aspectRatio: 1, child: image),
                    ),
                    const SizedBox(width: AppSpacing.xl),
                    Expanded(
                      flex: 3,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        mainAxisSize: MainAxisSize.min,
                        children: details,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        );
      } else {
        content = Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            _BackRow(title: product.name),
            Padding(
              padding: EdgeInsetsDirectional.fromSTEB(
                  gutter, AppSpacing.md, gutter, gutter),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                mainAxisSize: MainAxisSize.min,
                children: [
                  SizedBox(height: 220, child: image),
                  const SizedBox(height: AppSpacing.xl),
                  ...details,
                ],
              ),
            ),
          ],
        );
      }

      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(child: SingleChildScrollView(child: content)),
          if (barVariant != null)
            BottomActionBar(
              child: _VariantBuy(
                product: product,
                variant: barVariant,
                stacked: true,
              ),
            ),
        ],
      );
    });
  }
}

/// One purchasable option: its SKU, unit and barcode and what the shopper is
/// told about allergens, with its price and *Add* beside that in a wide row
/// and on a line under it below 600, so the text keeps the row's width.
class _VariantRow extends StatelessWidget {
  final StoreProduct product;
  final StoreVariant variant;

  /// False on a phone when the bar under the page carries this option's price
  /// and Add.
  final bool showBuy;

  const _VariantRow({
    required this.product,
    required this.variant,
    this.showBuy = true,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final meta = [
      if (variant.unit != null) variant.unit!,
      if (variant.barcode != null) 'EAN: ${variant.barcode}',
    ].join('  ·  ');
    final details = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(variant.sku,
            style: theme.textTheme.bodyLarge?.copyWith(fontFamily: 'monospace')),
        if (meta.isNotEmpty)
          Text(meta,
              style: theme.textTheme.bodyMedium
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
        // A shopper is entitled to this before buying, and it is the one line
        // on the page where a wrong answer can put someone in hospital.
        AllergenSummary(variantId: variant.id),
      ],
    );
    return Card(
      child: Padding(
        padding: const EdgeInsets.symmetric(
            horizontal: AppSpacing.lg, vertical: AppSpacing.md),
        child: !showBuy
            ? details
            : LayoutBuilder(
                builder: (context, constraints) {
                  if (AppBreakpoints.classOf(constraints.maxWidth) ==
                      WindowClass.compact) {
                    return Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        details,
                        const SizedBox(height: AppSpacing.md),
                        _VariantBuy(
                          product: product,
                          variant: variant,
                          stacked: true,
                        ),
                      ],
                    );
                  }
                  return Row(
                    children: [
                      Expanded(child: details),
                      const SizedBox(width: AppSpacing.lg),
                      _VariantBuy(product: product, variant: variant),
                    ],
                  );
                },
              ),
      ),
    );
  }
}

/// An option's price and *Add* (stock and *Add* in a shop that hides prices).
class _VariantBuy extends ConsumerWidget {
  final StoreProduct product;
  final StoreVariant variant;

  /// Across the full width — the price at the start, Add at the end — as under
  /// an option's text on a phone and in the bar at the foot of the page;
  /// otherwise a compact group at the end of a wide row.
  final bool stacked;

  const _VariantBuy({
    required this.product,
    required this.variant,
    this.stacked = false,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final showPrices = ref.watch(storefrontShowPricesProvider);
    // .select() so this only rebuilds when *its own* variant's availability changes,
    // not on every store switch's whole-map refetch.
    final (inStock, onlyLeft, dropship) =
        ref.watch(storefrontAvailabilityProvider.select((async) {
      final map = async.value;
      final info = map == null ? null : map[variant.id];
      return (info?.inStock ?? true, info?.onlyLeft, info?.dropship ?? false);
    }));
    // Out of stock holds Add back — unless the supplier ships it per order,
    // which has no shelf to be out of.
    final canAdd = inStock || dropship;

    void addLine(double unitPrice, String currency) {
      ref.read(cartProvider.notifier).add(CartLine(
            variantId: variant.id,
            productId: product.id,
            productName: product.name,
            sku: variant.sku,
            unitPrice: unitPrice,
            currency: currency,
          ));
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Added ${product.name} to cart'),
          duration: const Duration(seconds: 1),
        ),
      );
    }

    Widget layout(Widget info, [Widget? add]) => stacked
        ? Row(
            children: [
              Expanded(
                child: Align(
                  alignment: AlignmentDirectional.centerStart,
                  child: info,
                ),
              ),
              if (add != null) ...[const SizedBox(width: AppSpacing.md), add],
            ],
          )
        : Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              info,
              if (add != null) ...[const SizedBox(width: AppSpacing.sm), add],
            ],
          );

    // Catalog mode: no price, no price-resolve call — stock + add only.
    if (!showPrices) {
      return layout(
        StockBadge(inStock: inStock, onlyLeft: onlyLeft),
        canAdd
            ? FilledButton(
                onPressed: () => addLine(0, ''),
                child: const Text('Add'),
              )
            : null,
      );
    }

    final priceAsync = ref.watch(variantPriceProvider(variant.id));
    return priceAsync.when(
      loading: () => layout(const SizedBox(
          height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))),
      error: (_, _) => layout(Text('Unavailable',
          style: TextStyle(color: cs.outline, fontSize: 12))),
      data: (p) => layout(
        Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment:
              stacked ? CrossAxisAlignment.start : CrossAxisAlignment.end,
          children: [
            // Its own size, so it is never the smallest text in the row; the
            // money format of the was and unit prices under it; the page's ink.
            Text(
              AppFormat.money(p.totalWithVat, currencyCode: p.currency),
              style: theme.textTheme.titleMedium?.copyWith(
                color: cs.onSurface,
                fontWeight: FontWeight.w700,
              ),
            ),
            // The same price in the currency the shopper chose to see (03.x):
            // shown, never charged.
            if (p.shownLine.isNotEmpty)
              Text(p.shownLine,
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: cs.onSurfaceVariant)),
            WasPriceText(price: p),
            UnitPriceText(price: p),
          ],
        ),
        FilledButton(
          onPressed: canAdd ? () => addLine(p.totalWithVat, p.currency) : null,
          child: Text(canAdd ? 'Add' : 'Out of stock'),
        ),
      ),
    );
  }
}
