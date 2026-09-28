import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/gestures.dart' show PointerDeviceKind;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:url_launcher/url_launcher.dart';
import '../../core/format.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/skeleton.dart';
import 'product_card.dart';
import 'storefront_providers.dart';
import 'storefront_widgets.dart';
import 'survey_widgets.dart';

// Sentinel placed in the mixed display list to mark where the ad renders.
class _AdSlot {
  const _AdSlot();
}

class ProductListScreen extends ConsumerStatefulWidget {
  const ProductListScreen({super.key});

  @override
  ConsumerState<ProductListScreen> createState() => _ProductListScreenState();
}

class _ProductListScreenState extends ConsumerState<ProductListScreen> {
  String _query = '';

  // Cached so the ad doesn't jump to a new random slot (reshuffling list
  // item identity/position) on every unrelated rebuild — e.g. an
  // availability tick or cart change — which otherwise churns the
  // autoDispose-free per-card providers for no reason.
  int? _adIndex;
  int _adIndexForCount = -1;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      // Auto-select the first store when no ?store= URL override is given.
      // This prevents a stale built-in default from making the availability
      // endpoint return an empty map (which would show everything as out of stock).
      if ((Uri.base.queryParameters['store'] ?? '').isEmpty) {
        try {
          final stores = await ref.read(storefrontStoresProvider.future);
          if (stores.isNotEmpty && mounted) {
            ref.read(storefrontStoreProvider.notifier).state = stores.first.id;
          }
        } catch (_) {}
      }
      await Future.delayed(const Duration(milliseconds: 500));
      if (!mounted) return;
      try {
        await ref.read(customerPrefsProvider.notifier).ready;
      } catch (_) {} // storage unreadable: ask, as a first visit would
      if (!mounted) return;
      final asked = ref.read(customerPrefsProvider).genderAsked;
      if (!asked) showGenderPickerSheet(context);
    });
  }

  @override
  Widget build(BuildContext context) {
    final tenant = ref.watch(storefrontTenantProvider);
    if (tenant == null) return const _NoStorefront();

    final selectedCategory = ref.watch(selectedStorefrontCategoryProvider);
    final inStockOnly = ref.watch(storefrontInStockOnlyProvider);
    final productsAsync = ref.watch(
      storefrontProductsProvider((query: _query, categoryId: selectedCategory)),
    );
    final gutter = context.pageGutter;
    // How far the shopper enlarged their text: a grid card's lines get room that grows with it.
    final textScale =
        (MediaQuery.textScalerOf(context).scale(14) / 14).clamp(1.0, 3.0);

    return CustomScrollView(
      slivers: [
        // Store switcher (only shown when the tenant has more than one store)
        const SliverToBoxAdapter(child: _StoreSwitcher()),

        // Offers hero carousel
        const SliverToBoxAdapter(child: _OffersCarousel()),

        // Search
        SliverToBoxAdapter(
          child: Padding(
            padding: EdgeInsets.fromLTRB(gutter, 4, gutter, 8),
            // The bar's own tap target carries no name, and the field inside only a hint (12.11).
            child: Semantics(
              label: 'Search products',
              child: SearchBar(
                hintText: 'Search products…',
                leading: const Icon(Icons.search),
                onSubmitted: (v) => setState(() => _query = v),
              ),
            ),
          ),
        ),

        // Browse by category + in-stock filter
        SliverToBoxAdapter(child: _FilterRow(inStockOnly: inStockOnly)),

        // Products
        productsAsync.when(
          // The first load in grey, shaped like the cards or rows to come,
          // so the page doesn't jump when they arrive.
          loading: () => _ShopSkeleton(textScale: textScale),
          error: (e, _) => SliverFillRemaining(
            hasScrollBody: false,
            child: ErrorView(
              message: friendlyError(e, fallback: 'Could not load products.'),
              onRetry: () => ref.invalidate(storefrontProductsProvider),
            ),
          ),
          data: (products) {
            // Apply in-stock filter client-side using the availability map and
            // first-variant lookup (both are lazy-cached per product).
            List<StoreProduct> displayProducts = products;
            if (inStockOnly) {
              final availMap =
                  ref.watch(storefrontAvailabilityProvider).value ?? {};
              if (availMap.isNotEmpty) {
                displayProducts = products.where((p) {
                  final variant =
                      ref.watch(productFirstVariantProvider(p.id)).value;
                  if (variant == null) return true; // include while loading
                  return availMap[variant.id]?.inStock ?? true;
                }).toList();
              }
            }

            if (displayProducts.isEmpty) {
              return const SliverFillRemaining(
                hasScrollBody: false,
                child: _EmptyProducts(),
              );
            }
            // Inject one ad at a random position among the real products.
            // The position is cached per product-list length so it doesn't
            // reshuffle (and churn item identity) on every rebuild.
            if (_adIndexForCount != displayProducts.length) {
              _adIndex = math.Random().nextInt(displayProducts.length + 1);
              _adIndexForCount = displayProducts.length;
            }
            final items = <Object>[...displayProducts]
              ..insert(_adIndex!, const _AdSlot());

            // Laid out for the width the shop actually has — the rail takes some
            // of the window: rows on a phone, then as many columns of cards as
            // keep each near 180–220 wide, wider with enlarged text.
            return SliverLayoutBuilder(
              builder: (context, constraints) {
                final width = constraints.crossAxisExtent;
                if (_listsRows(width)) {
                  return SliverPadding(
                    padding: EdgeInsets.all(gutter),
                    sliver: SliverList.separated(
                      itemCount: items.length,
                      separatorBuilder: (_, _) =>
                          const SizedBox(height: AppSpacing.md),
                      itemBuilder: (_, i) {
                        final item = items[i];
                        return item is _AdSlot
                            ? const _AdRow()
                            : _ProductRow(
                                key: ValueKey((item as StoreProduct).id),
                                product: item,
                              );
                      },
                    ),
                  );
                }
                final grid = ProductCard.gridFor(width - 2 * gutter,
                    textScale: textScale);
                return SliverPadding(
                  padding: EdgeInsets.all(gutter),
                  sliver: SliverGrid.builder(
                    gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                      crossAxisCount: grid.columns,
                      crossAxisSpacing: AppSpacing.md,
                      mainAxisSpacing: AppSpacing.md,
                      // A square picture, and room under it for the name,
                      // price and stock that grows with the text size, so
                      // enlarged text is never cut off (WCAG 1.4.4).
                      mainAxisExtent: grid.tileHeight,
                    ),
                    itemCount: items.length,
                    itemBuilder: (_, i) {
                      final item = items[i];
                      return item is _AdSlot
                          ? const _AdCard()
                          : ProductCard(
                              key: ValueKey((item as StoreProduct).id),
                              product: item,
                            );
                    },
                  ),
                );
              },
            );
          },
        ),
      ],
    );
  }
}

/// Whether the shop lists its products as rows in [width]: a phone's width
/// reads better as a list than as two narrow columns of cards.
bool _listsRows(double width) =>
    AppBreakpoints.classOf(width) == WindowClass.compact;

/// The shop's first load: grey rows on a phone, a grid of grey cards wider,
/// laid out exactly as the products will be.
class _ShopSkeleton extends StatelessWidget {
  final double textScale;
  const _ShopSkeleton({required this.textScale});

  @override
  Widget build(BuildContext context) {
    final gutter = context.pageGutter;
    return SliverLayoutBuilder(builder: (context, constraints) {
      final width = constraints.crossAxisExtent;
      final Widget shape;
      if (_listsRows(width)) {
        shape = Column(
          children: [
            for (var i = 0; i < 4; i++) ...[
              if (i > 0) const SizedBox(height: AppSpacing.md),
              const _ProductRowSkeleton(),
            ],
          ],
        );
      } else {
        final grid =
            ProductCard.gridFor(width - 2 * gutter, textScale: textScale);
        // Two rows of the grid to come, each tile its size and spacing.
        Widget gridRow() => SizedBox(
              height: grid.tileHeight,
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (var c = 0; c < grid.columns; c++) ...[
                    if (c > 0) const SizedBox(width: AppSpacing.md),
                    const Expanded(child: ProductCardSkeleton()),
                  ],
                ],
              ),
            );
        shape = Column(
          children: [
            gridRow(),
            const SizedBox(height: AppSpacing.md),
            gridRow(),
          ],
        );
      }
      return SliverPadding(
        padding: EdgeInsets.all(gutter),
        sliver: SliverToBoxAdapter(
          child: Skeleton(label: 'Loading products', child: shape),
        ),
      );
    });
  }
}

/// Lets the shopper pick which store they're browsing (dev stand-in for per-store
/// subdomains). Hidden when the tenant has a single store. Switching re-filters the
/// catalog to that store's assortment and re-reads its price/availability config.
class _StoreSwitcher extends ConsumerWidget {
  const _StoreSwitcher();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storefrontStoresProvider);
    final current = ref.watch(storefrontStoreProvider);

    return storesAsync.maybeWhen(
      orElse: () => const SizedBox.shrink(),
      data: (stores) {
        if (stores.length < 2) return const SizedBox.shrink();
        final value = stores.any((s) => s.id == current) ? current : stores.first.id;
        final gutter = context.pageGutter;
        return Padding(
          padding: EdgeInsets.fromLTRB(gutter, 12, gutter, 0),
          child: Row(
            children: [
              Icon(Icons.storefront_outlined, size: 18, color: cs.primary),
              const SizedBox(width: 8),
              Text('Shopping at', style: TextStyle(color: cs.outline)),
              const SizedBox(width: 10),
              Expanded(
                child: DropdownButton<String>(
                  isExpanded: true,
                  value: value,
                  underline: const SizedBox.shrink(),
                  items: [
                    for (final s in stores)
                      DropdownMenuItem(
                        value: s.id,
                        child: Text(
                          // A dark store sells delivery-only: said where the shopper chooses.
                          s.pickupOffered ? s.name : '${s.name} · delivery only',
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontWeight: FontWeight.w600),
                        ),
                      ),
                  ],
                  onChanged: (v) {
                    if (v != null) {
                      ref.read(storefrontStoreProvider.notifier).state = v;
                    }
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

/// Auto-rotating promotional banners. Driven by the tenant's active promotions
/// (pricing-svc); falls back to evergreen content when there are no live offers.
class _OffersCarousel extends ConsumerStatefulWidget {
  const _OffersCarousel();

  @override
  ConsumerState<_OffersCarousel> createState() => _OffersCarouselState();
}

/// The theme's container pair a banner is drawn in: flat fills with their own
/// ink, so a banner reads as calmly in dark mode as in light.
enum _OfferTone { primary, secondary, tertiary }

class _Offer {
  final String title;
  final String subtitle;
  final IconData icon;
  final _OfferTone tone;
  const _Offer(this.title, this.subtitle, this.icon, this.tone);
}

class _OffersCarouselState extends ConsumerState<_OffersCarousel> {
  // Evergreen content shown when the tenant has no live promotions configured.
  // Never a price, a currency or a threshold: no delivery charge exists
  // anywhere in the platform for this to promise against, and a business
  // trading in yen or rupees is not a business that spends "over £25".
  static List<_Offer> _fallbackOffers(String? storeName) {
    final name = storeName?.trim();
    return [
      const _Offer(
        'Everyday low prices',
        'Stock up and save on the essentials',
        Icons.local_offer_outlined,
        _OfferTone.primary,
      ),
      (name == null || name.isEmpty || name == '-')
          ? const _Offer(
              'Welcome',
              'Thanks for shopping with us',
              Icons.storefront_outlined,
              _OfferTone.secondary,
            )
          : _Offer(
              name,
              'Thanks for shopping with us',
              Icons.storefront_outlined,
              _OfferTone.secondary,
            ),
      const _Offer(
        'Fresh new arrivals',
        'Just landed in store — shop the latest',
        Icons.auto_awesome_outlined,
        _OfferTone.tertiary,
      ),
    ];
  }

  // Current offers shown; updated each build from the promotions provider so the
  // rotation timer always reads a valid length.
  List<_Offer> _offers = _fallbackOffers(null);

  // A PageView (not CarouselView) so every banner keeps the full viewport
  // width instead of being squeezed by the Material "uncontained" carousel
  // layout, and so a plain page-snap drag drives it.
  final _controller = PageController(viewportFraction: 0.92);
  int _page = 0;
  Timer? _timer;
  // Set while the user is dragging so auto-rotation doesn't fight the gesture.
  bool _paused = false;
  // Set by the pause button: the offers move by themselves, so they can be stopped (WCAG 2.2.2).
  bool _stopped = false;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 4), (_) {
      if (_paused ||
          _stopped ||
          !_controller.hasClients ||
          _offers.length < 2) {
        return;
      }
      // A device set to reduce motion gets offers that stay still (WCAG 2.2.2 and 2.3.3).
      if (mounted && (MediaQuery.maybeDisableAnimationsOf(context) ?? false)) {
        return;
      }
      final next = (_page + 1) % _offers.length;
      _controller.animateToPage(
        next,
        duration: const Duration(milliseconds: 450),
        curve: Curves.easeInOut,
      );
    });
  }

  void _goTo(int i) {
    if (!_controller.hasClients) return;
    _controller.animateToPage(
      i,
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeInOut,
    );
  }

  @override
  void dispose() {
    _timer?.cancel();
    _controller.dispose();
    super.dispose();
  }

  List<_Offer> _offersFrom(
      List<StorePromotion> all, String? currency, String? storeName) {
    final promos = advertisedPromotions(all);
    if (promos.isEmpty) return _fallbackOffers(storeName);
    return [
      for (var i = 0; i < promos.length; i++)
        _Offer(
          promos[i].headlineIn(currency),
          promos[i].minOrderAmount != null && promos[i].minOrderAmount! > 0
              ? '${promos[i].name} · spend ${AppFormat.money(promos[i].minOrderAmount!, currencyCode: currency)}+'
              : promos[i].name,
          Icons.local_offer_outlined,
          // Rotated so neighbouring banners read distinctly.
          _OfferTone.values[i % _OfferTone.values.length],
        ),
    ];
  }

  /// A banner's fill and the ink on it.
  static (Color, Color) _toneColors(ColorScheme cs, _OfferTone tone) => switch (tone) {
        _OfferTone.primary => (cs.primaryContainer, cs.onPrimaryContainer),
        _OfferTone.secondary => (cs.secondaryContainer, cs.onSecondaryContainer),
        _OfferTone.tertiary => (cs.tertiaryContainer, cs.onTertiaryContainer),
      };

  @override
  Widget build(BuildContext context) {
    final promos = ref.watch(storefrontPromotionsProvider).value ?? const [];
    // Promotions are set in the shop's own currency.
    final currency = ref.watch(storefrontCurrenciesProvider).value?.home;
    final storeName = ref.watch(storefrontConfigProvider).value?.storeName;
    _offers = _offersFrom(promos, currency, storeName);
    if (_page >= _offers.length) {
      // Promotions arrived/expired and shrank the list under the current page —
      // snap back to the first banner once this frame is laid out.
      _page = 0;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _controller.hasClients) _controller.jumpToPage(0);
      });
    }
    final cs = Theme.of(context).colorScheme;
    // Enlarged text makes the banner taller rather than cutting its words off (WCAG 1.4.4).
    final textScale =
        (MediaQuery.textScalerOf(context).scale(20) / 20).clamp(1.0, 2.0);
    return LayoutBuilder(
      builder: (context, constraints) {
        // Give the banner more room on a wide shop so it doesn't read as a
        // squashed strip; judged by the width the shop has, not the window's.
        final base = switch (AppBreakpoints.classOf(constraints.maxWidth)) {
          WindowClass.compact => 150.0,
          WindowClass.medium => 170.0,
          WindowClass.expanded || WindowClass.large => 190.0,
        };
        return Column(
          children: [
            const SizedBox(height: 12),
            SizedBox(
              height: base * textScale,
              child: NotificationListener<ScrollNotification>(
                onNotification: (n) {
                  if (n is ScrollStartNotification && n.dragDetails != null) {
                    _paused = true;
                  } else if (n is ScrollEndNotification) {
                    _paused = false;
                  }
                  return false;
                },
                // Flutter's default web/desktop scroll behaviour excludes the mouse
                // from drag devices, which left this carousel unswipeable in the
                // browser. Opt the pointer devices back in.
                child: ScrollConfiguration(
                  behavior: ScrollConfiguration.of(context).copyWith(
                    dragDevices: const {
                      PointerDeviceKind.touch,
                      PointerDeviceKind.mouse,
                      PointerDeviceKind.trackpad,
                      PointerDeviceKind.stylus,
                    },
                    scrollbars: false,
                    overscroll: false,
                  ),
                  child: PageView.builder(
                    controller: _controller,
                    itemCount: _offers.length,
                    onPageChanged: (i) => setState(() => _page = i),
                    itemBuilder: (context, i) {
                      final o = _offers[i];
                      final (bg, fg) = _toneColors(cs, o.tone);
                      return Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 6),
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color: bg,
                            borderRadius: AppRadius.card,
                          ),
                          child: Padding(
                            padding: const EdgeInsets.all(20),
                            child: Row(
                              children: [
                                Expanded(
                                  child: Column(
                                    mainAxisAlignment: MainAxisAlignment.center,
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        o.title,
                                        maxLines: 2,
                                        overflow: TextOverflow.ellipsis,
                                        style: TextStyle(
                                          color: fg,
                                          fontSize: 20,
                                          fontWeight: FontWeight.bold,
                                        ),
                                      ),
                                      const SizedBox(height: 6),
                                      Text(
                                        o.subtitle,
                                        maxLines: 2,
                                        overflow: TextOverflow.ellipsis,
                                        style: TextStyle(color: fg, fontSize: 13),
                                      ),
                                    ],
                                  ),
                                ),
                                Icon(o.icon, color: fg, size: 48),
                              ],
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                ),
              ),
            ),
            const SizedBox(height: 10),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                ...List.generate(_offers.length, (i) {
                  final active = i == _page;
                  return Semantics(
                    button: true,
                    label: 'Offer ${i + 1} of ${_offers.length}',
                    child: InkWell(
                      onTap: () => _goTo(i),
                      customBorder: const CircleBorder(),
                      // At least 24 by 24 to hit, however small the dot (WCAG 2.2, 2.5.8).
                      child: SizedBox(
                        width: 24,
                        height: 24,
                        child: Center(
                          child: AnimatedContainer(
                            duration: const Duration(milliseconds: 250),
                            width: active ? 18 : 6,
                            height: 6,
                            decoration: BoxDecoration(
                              color: active ? cs.primary : cs.outlineVariant,
                              borderRadius: AppRadius.badge,
                            ),
                          ),
                        ),
                      ),
                    ),
                  );
                }),
                if (_offers.length > 1)
                  IconButton(
                    key: const Key('offers-pause'),
                    iconSize: 18,
                    tooltip: _stopped ? 'Play offers' : 'Pause offers',
                    icon: Icon(_stopped ? Icons.play_arrow : Icons.pause),
                    onPressed: () => setState(() => _stopped = !_stopped),
                  ),
              ],
            ),
          ],
        );
      },
    );
  }
}

/// Horizontal filter row: category chips + "In stock only" toggle at the end.
class _FilterRow extends ConsumerWidget {
  final bool inStockOnly;
  const _FilterRow({required this.inStockOnly});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final categoriesAsync = ref.watch(storefrontCategoriesProvider);
    final selected = ref.watch(selectedStorefrontCategoryProvider);
    final cs = Theme.of(context).colorScheme;

    // As tall as its chips: a fixed height cut them off top and bottom at
    // 200% text. Every chip is built, so the in-stock one at the end is there
    // to reach, however long the category list.
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      // A little room above and below, so a desktop's compact chips (about
      // 30px, no 48px tap target) still sit clear of the search bar.
      padding: EdgeInsetsDirectional.symmetric(
          horizontal: context.pageGutter, vertical: AppSpacing.xs),
      child: IntrinsicHeight(
        child: Row(
          children: [
            // Category chips
            ...categoriesAsync.when(
              loading: () => const [],
              error: (_, _) => const [],
              data: (categories) => [
                _categoryChip(
                  context,
                  ref,
                  label: 'All',
                  value: null,
                  selected: selected == null,
                ),
                for (final c in categories)
                  _categoryChip(
                    context,
                    ref,
                    label: c.name,
                    value: c.id,
                    selected: selected == c.id,
                  ),
              ],
            ),
            // Divider spacer, a little shorter than the row: it follows the
            // chips' height on touch (48px targets) and on desktop alike.
            Padding(
              padding: const EdgeInsetsDirectional.symmetric(
                  horizontal: AppSpacing.xs),
              child: VerticalDivider(
                width: 1,
                indent: AppSpacing.xs,
                endIndent: AppSpacing.xs,
                color: cs.outlineVariant,
              ),
            ),
            // In-stock toggle
            Padding(
              padding: const EdgeInsetsDirectional.only(end: AppSpacing.sm),
              child: FilterChip(
                avatar: Icon(
                  Icons.inventory_2_outlined,
                  size: 16,
                  color: inStockOnly
                      ? cs.onSecondaryContainer
                      : cs.onSurfaceVariant,
                ),
                label: const Text('In stock'),
                selected: inStockOnly,
                onSelected: (v) =>
                    ref.read(storefrontInStockOnlyProvider.notifier).state = v,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _categoryChip(
    BuildContext context,
    WidgetRef ref, {
    required String label,
    required String? value,
    required bool selected,
  }) {
    return Padding(
      padding: const EdgeInsetsDirectional.only(end: AppSpacing.sm),
      child: ChoiceChip(
        label: Text(label),
        selected: selected,
        onSelected: (_) =>
            ref.read(selectedStorefrontCategoryProvider.notifier).state = value,
      ),
    );
  }
}

class _EmptyProducts extends ConsumerWidget {
  const _EmptyProducts();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final filtered = ref.watch(selectedStorefrontCategoryProvider) != null;
    return EmptyState(
      icon: Icons.storefront_outlined,
      title: filtered
          ? 'No products in this category'
          : 'No products available yet',
    );
  }
}

/// Compact horizontal-thumbnail card used for the single-column phone list, so
/// stacked products read as a tidy vertical list instead of giant full-width tiles.
class _ProductRow extends ConsumerWidget {
  final StoreProduct product;
  const _ProductRow({super.key, required this.product});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Semantics(
      button: true,
      hint: 'Opens the product details',
      child: Card(
        clipBehavior: Clip.antiAlias,
        margin: EdgeInsets.zero,
        child: InkWell(
          onTap: () => context.go('/store/products/${product.id}'),
          child: Padding(
            padding: const EdgeInsets.all(10),
            child: Row(
              children: [
                SizedBox(
                  width: 72,
                  height: 72,
                  child: ProductImageThumb(
                    productId: product.id,
                    label: product.name,
                    fontSize: 24,
                    borderRadius: AppRadius.chip,
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        product.name,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontWeight: FontWeight.w600,
                          fontSize: 15,
                        ),
                      ),
                      const SizedBox(height: 4),
                      OfferPriceAdd(product: product),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// A [_ProductRow] while the shop loads: the thumbnail, the name and the
/// price, in grey.
class _ProductRowSkeleton extends StatelessWidget {
  const _ProductRowSkeleton();

  @override
  Widget build(BuildContext context) => const Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: EdgeInsets.all(10),
          child: Row(
            children: [
              SkeletonBlock(width: 72, height: 72, borderRadius: AppRadius.chip),
              SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SkeletonLine(widthFactor: 0.7, fontSize: 15),
                    SizedBox(height: AppSpacing.sm),
                    SkeletonLine(widthFactor: 0.3, fontSize: 16),
                    SizedBox(height: AppSpacing.sm),
                    SkeletonLine(widthFactor: 0.4, fontSize: 12),
                  ],
                ),
              ),
            ],
          ),
        ),
      );
}

// ── Sponsored ad widgets ──────────────────────────────────────────────────────

const _kAdUrl = 'https://featurefragment.com/';
const _kAdBrand = 'FeatureFragment';

Future<void> _openAd() =>
    launchUrl(Uri.parse(_kAdUrl), mode: LaunchMode.externalApplication);

/// A sponsored tile is read as one link — the brand, what it offers, and that it is sponsored and
/// opens elsewhere (12.11). Merged, because the name would otherwise sit on a child node the link
/// does not carry; the tile holds no control of its own to swallow.
Widget _adLink(Widget tile) => MergeSemantics(
      child: Semantics(
        link: true,
        hint: 'Sponsored. Opens in a new window',
        child: tile,
      ),
    );

/// Grid-card variant of the sponsored ad tile.
class _AdCard extends StatelessWidget {
  const _AdCard();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return _adLink(
      Card(
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: _openAd,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Expanded(
                child: Stack(
                  children: [
                    // A flat theme fill, not a bright gradient, so the tile sits
                    // among the product pictures in dark mode too.
                    Container(
                      color: cs.tertiaryContainer,
                      alignment: Alignment.center,
                      child: Icon(
                        Icons.inventory_2_outlined,
                        size: 48,
                        color: cs.onTertiaryContainer,
                      ),
                    ),
                    PositionedDirectional(
                      top: 8,
                      end: 8,
                      child: _AdBadge(cs: cs),
                    ),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(12, 10, 6, 6),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      _kAdBrand,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontWeight: FontWeight.w600),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Smart stock & storefront platform',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 11, color: cs.outline),
                    ),
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

/// List-row variant of the sponsored ad tile.
class _AdRow extends StatelessWidget {
  const _AdRow();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return _adLink(
      Card(
        clipBehavior: Clip.antiAlias,
        margin: EdgeInsets.zero,
        child: InkWell(
          onTap: _openAd,
          child: Padding(
            padding: const EdgeInsets.all(10),
            child: Row(
              children: [
                SizedBox(
                  width: 72,
                  height: 72,
                  child: Container(
                    decoration: BoxDecoration(
                      color: cs.tertiaryContainer,
                      borderRadius: AppRadius.chip,
                    ),
                    alignment: Alignment.center,
                    child: Icon(
                      Icons.inventory_2_outlined,
                      size: 32,
                      color: cs.onTertiaryContainer,
                    ),
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Expanded(
                            child: Text(
                              _kAdBrand,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontWeight: FontWeight.w600,
                                fontSize: 15,
                              ),
                            ),
                          ),
                          _AdBadge(cs: cs),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Smart stock & storefront platform',
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(fontSize: 12, color: cs.outline),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _AdBadge extends StatelessWidget {
  const _AdBadge({required this.cs});
  final ColorScheme cs;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: cs.secondaryContainer,
        borderRadius: AppRadius.badge,
      ),
      child: Text(
        'AD',
        style: TextStyle(
          color: cs.onSecondaryContainer,
          fontSize: 9,
          fontWeight: FontWeight.bold,
          letterSpacing: 0.5,
        ),
      ),
    );
  }
}

class _NoStorefront extends StatelessWidget {
  const _NoStorefront();

  @override
  Widget build(BuildContext context) => const EmptyState(
        icon: Icons.store_mall_directory_outlined,
        title: 'No store selected',
        message: 'Open this storefront with a tenant in the URL, e.g.\n'
            '/?tenant=<tenantId>#/store/products',
      );
}
