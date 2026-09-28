import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/format.dart';
import '../../core/input_mode.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/util/slot_label.dart';
import '../../shared/widgets/skeleton.dart';
import '../../shared/widgets/status_badge.dart';
import 'recall_notice_card.dart';
import 'storefront_providers.dart';
import 'storefront_shell.dart' show StorefrontAuthDialog;
import '../../shared/util/short_ref.dart';

class StorefrontOrdersScreen extends ConsumerWidget {
  const StorefrontOrdersScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(storefrontAuthProvider);
    final showPrices = ref.watch(storefrontShowPricesProvider);
    final gutter = context.pageGutter;

    // Signed-in customers get their real, cross-device order history from the
    // server. Guests see only orders placed on this device.
    if (auth.isSignedIn) {
      final async = ref.watch(serverOrdersProvider);
      final storeNames = {
        for (final s in ref.watch(storefrontStoresProvider).value ?? const <StoreSummary>[])
          s.id: s.name,
      };
      // The orders and the recalls above them, read again. The list keeps
      // showing while the new one loads; a failure shows in its place.
      Future<void> refresh() async {
        ref
          ..invalidate(myRecallNoticesProvider)
          ..invalidate(serverOrdersProvider);
        try {
          await ref.read(serverOrdersProvider.future);
        } catch (_) {}
      }

      final header = _OrdersHeader(
        // Touch screens pull to refresh; a mouse can't, so it gets a button —
        // off while a load is in flight, on again once one has failed, like
        // the error's own Retry.
        onRefresh: async.isLoading && !async.hasError ? null : refresh,
      );
      return RefreshIndicator.adaptive(
        onRefresh: refresh,
        child: async.when(
          // A reload keeps the list up; a failed one shows its error at once.
          skipLoadingOnReload: true,
          // The first load in grey, shaped like the order cards to come.
          loading: () => ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.only(bottom: AppSpacing.lg),
            children: [
              header,
              Padding(
                padding: EdgeInsets.symmetric(horizontal: gutter),
                child: const ContentBounds.form(
                  child: Skeleton(
                    label: 'Loading your orders',
                    child: Column(
                      children: [
                        _OrderCardSkeleton(),
                        SizedBox(height: AppSpacing.sm),
                        _OrderCardSkeleton(),
                        SizedBox(height: AppSpacing.sm),
                        _OrderCardSkeleton(),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
          // Still under the header, and still pulled or clicked to try again.
          error: (e, _) => CustomScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            slivers: [
              SliverToBoxAdapter(child: header),
              SliverFillRemaining(
                hasScrollBody: false,
                child: ErrorView(
                  message:
                      friendlyError(e, fallback: 'Could not load your orders.'),
                  onRetry: refresh,
                ),
              ),
            ],
          ),
          data: (orders) {
            final list = orders ?? const [];
            // A product safety recall on something they bought comes before
            // the orders themselves (05.10). Each row keeps to a reading
            // width on a wide screen.
            return ListView.separated(
              // A short history can still be pulled down to refresh.
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.only(bottom: AppSpacing.lg),
              itemCount: list.length + 3,
              separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.sm),
              itemBuilder: (_, i) {
                if (i == 0) return header;
                if (i == 1) return const RecallNoticesSection();
                if (i == 2) {
                  return list.isEmpty
                      ? const _NoOrders()
                      : const SizedBox(height: AppSpacing.md);
                }
                final order = list[i - 3];
                return Padding(
                  padding: EdgeInsets.symmetric(horizontal: gutter),
                  child: ContentBounds.form(
                    child: _ServerOrderTile(
                      order: order,
                      // A person reads the store's name; the end of its id
                      // only stands in until the stores have loaded.
                      storeName:
                          storeNames[order.storeId] ?? shortRef(order.storeId),
                      showPrices: showPrices,
                      // The parts of one split checkout (order orchestration)
                      // each name the delivery they belong to.
                      parts: order.groupId == null
                          ? 1
                          : list.where((o) => o.groupId == order.groupId).length,
                    ),
                  ),
                );
              },
            );
          },
        ),
      );
    }

    // Guest: device-local fallback + a nudge to sign in for synced history.
    // The list is kept as each order is placed here, so there is nothing to
    // pull or click to read again.
    final orders = ref.watch(storefrontOrdersProvider);
    return Column(
      children: [
        const _SignInBanner(),
        const _OrdersHeader.deviceOnly(),
        Expanded(
          child: orders.isEmpty
              ? const _NoOrders()
              : ListView.separated(
                  padding: EdgeInsetsDirectional.fromSTEB(
                      gutter, 0, gutter, AppSpacing.lg),
                  itemCount: orders.length,
                  separatorBuilder: (_, _) =>
                      const SizedBox(height: AppSpacing.sm),
                  itemBuilder: (_, i) => ContentBounds.form(
                    child: _LocalOrderTile(
                        order: orders[i], showPrices: showPrices),
                  ),
                ),
        ),
      ],
    );
  }
}

/// The page's title, starting at the orders' edge, and — on the signed-in
/// history, with a mouse — a button that reads them again.
class _OrdersHeader extends StatelessWidget {
  /// Whether there is anything to read again: the signed-in history, which
  /// the server holds. A guest's is this device's own list, written as each
  /// order is placed, so it is already current and gets no button.
  final bool _reloadable;

  /// Reads the orders again; null while they are loading. Offered as a button
  /// only where people mostly use a mouse: touch screens pull to refresh.
  final Future<void> Function()? onRefresh;

  /// The signed-in history's heading.
  const _OrdersHeader({required this.onRefresh}) : _reloadable = true;

  /// A guest's heading: the device's own orders, nothing to reload.
  const _OrdersHeader.deviceOnly()
      : onRefresh = null,
        _reloadable = false;

  @override
  Widget build(BuildContext context) {
    final refresh = onRefresh;
    return ContentBounds(
      // The form column the cards keep to, plus the gutters either side, so
      // the title lines up with the cards' edges.
      maxWidth: AppBreakpoints.formMaxWidth + 2 * context.pageGutter,
      // The column's whole width, not the title's: with no button beside it
      // (a touch screen, a guest) or with the button wrapped under it (a
      // narrow window) the heading is only as wide as what it holds, and
      // would otherwise shrink to the title and sit centred over the cards.
      child: SizedBox(
        width: double.infinity,
        child: PageHeader(
          title: 'My orders',
          actions: [
            if (_reloadable && pointerFirst)
              IconButton(
                key: const Key('orders-refresh'),
                icon: const Icon(Icons.refresh),
                tooltip: 'Refresh',
                onPressed: refresh == null ? null : () => refresh(),
              ),
          ],
        ),
      ),
    );
  }
}

/// An order card while the history loads: the icon, the number, where and
/// when, and the status and total, in grey.
class _OrderCardSkeleton extends StatelessWidget {
  const _OrderCardSkeleton();

  @override
  Widget build(BuildContext context) => const Card(
        child: Padding(
          padding: EdgeInsets.all(AppSpacing.lg),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SkeletonBlock(width: 40, height: 40, borderRadius: AppRadius.pill),
              SizedBox(width: AppSpacing.lg),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SkeletonLine(widthFactor: 0.45, fontSize: 16),
                    SizedBox(height: AppSpacing.sm),
                    SkeletonLine(widthFactor: 0.7),
                    SizedBox(height: AppSpacing.xs),
                    SkeletonLine(widthFactor: 0.5),
                    SizedBox(height: AppSpacing.sm),
                    Row(
                      children: [
                        SkeletonBlock(width: 72, height: 24),
                        Spacer(),
                        SizedBox(width: 56, child: SkeletonLine(fontSize: 16)),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      );
}

class _ServerOrderTile extends StatelessWidget {
  final ServerOrderSummary order;
  final String storeName;
  final bool showPrices;

  /// How many parts the checkout came in (order orchestration); 1 for an order never split.
  final int parts;
  const _ServerOrderTile(
      {required this.order,
      required this.storeName,
      required this.showPrices,
      this.parts = 1});

  @override
  Widget build(BuildContext context) {
    final delivery = order.fulfilmentType == 'DELIVERY';
    // Where a picked order is (ship-from-store); the status itself otherwise.
    final stage = order.stageLabel;
    final slot = order.slot;
    final placed = AppFormat.dateTime(order.placedAt.toIso8601String());
    return _OrderCard(
      orderId: order.id,
      detailKey: Key('order-subtitle-${order.id}'),
      where: delivery
          ? (parts > 1
              ? 'Part of a delivery in $parts parts · from $storeName'
              : 'Deliver to home')
          : 'Collect from $storeName',
      // The window this order holds (delivery-and-collection-slots), worded
      // with which kind it is, in the store's own local date and clock —
      // never converted on the device.
      detail: slot == null
          ? placed
          : '$placed\n${slotWindowLabel(fulfilmentType: order.fulfilmentType, date: slot.date, startTime: slot.startTime, endTime: slot.endTime)}',
      amount: showPrices
          ? AppFormat.money(order.total, currencyCode: order.currency)
          : (delivery ? 'Price on delivery' : 'Price in store'),
      priced: showPrices,
      status: order.status,
      stage: stage == order.status ? null : stage,
    );
  }
}

class _LocalOrderTile extends StatelessWidget {
  final StorefrontOrderRecord order;
  final bool showPrices;
  const _LocalOrderTile({required this.order, required this.showPrices});

  @override
  Widget build(BuildContext context) {
    final delivery = order.fulfilmentType == 'DELIVERY';
    final hasKnownPrice = showPrices && order.currency.isNotEmpty;
    final placed = AppFormat.dateTime(order.placedAt.toIso8601String());
    final slot = order.slot;
    final itemsLine = '${order.itemCount} item${order.itemCount == 1 ? '' : 's'} · $placed';
    return _OrderCard(
      orderId: order.orderId,
      where: delivery ? 'Deliver to home' : 'Collect from ${order.storeName}',
      // The window this order holds (delivery-and-collection-slots), worded
      // with which kind it is.
      detail: slot == null
          ? itemsLine
          : '$itemsLine\n${slotWindowLabel(fulfilmentType: order.fulfilmentType, date: slot.date, startTime: slot.startTime, endTime: slot.endTime)}',
      amount: hasKnownPrice
          ? AppFormat.money(order.total, currencyCode: order.currency)
          : (delivery ? 'Price on delivery' : 'Price in store'),
      priced: hasKnownPrice,
    );
  }
}

/// One order in the history: its number, how it reaches the shopper and when
/// it was placed, with the total and the status beside that from 600 wide and
/// on a line under it on a phone, so the text keeps the row.
class _OrderCard extends StatelessWidget {
  final String orderId;
  final String where;
  final String detail;

  /// Keys the where-and-when line, so a test can read one order's.
  final Key? detailKey;

  /// The total, or what stands in for it in a shop that hides prices.
  final String amount;
  final bool priced;

  /// The status code, shown in words; null for an order known only on this
  /// device.
  final String? status;

  /// What the badge says in place of the status's own words once a picked
  /// order is on its way to the shopper (ship-from-store): *Ready to
  /// collect*, *Packed*, *On its way · DPD 1Z…*, *Collected*. It keeps the
  /// status's tone.
  final String? stage;

  const _OrderCard({
    required this.orderId,
    required this.where,
    required this.detail,
    required this.amount,
    required this.priced,
    this.detailKey,
    this.status,
    this.stage,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final code = status;
    final label = stage;
    final badge = code == null
        ? null
        : label == null
            ? StatusBadge.order(code)
            : StatusBadge(label, tone: orderStatusTone(code));
    // A figure the shopper reads: its own size and the page's ink.
    final amountText = Text(
      amount,
      textAlign: TextAlign.end,
      style: priced
          ? theme.textTheme.titleSmall
              ?.copyWith(color: cs.onSurface, fontWeight: FontWeight.w700)
          : TextStyle(color: cs.outline, fontSize: 12),
    );
    final text = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('Order #${shortRef(orderId)}',
            style: theme.textTheme.bodyLarge
                ?.copyWith(fontWeight: FontWeight.bold)),
        const SizedBox(height: 2),
        Text('$where\n$detail',
            key: detailKey,
            style: theme.textTheme.bodyMedium
                ?.copyWith(color: cs.onSurfaceVariant)),
      ],
    );
    return LayoutBuilder(
      builder: (context, constraints) {
        final compact = AppBreakpoints.classOf(constraints.maxWidth) ==
            WindowClass.compact;
        return Card(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.lg),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                CircleAvatar(
                  backgroundColor: cs.primaryContainer,
                  child: Icon(Icons.receipt_long_outlined,
                      color: cs.onPrimaryContainer),
                ),
                const SizedBox(width: AppSpacing.lg),
                Expanded(
                  child: compact
                      ? Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            text,
                            const SizedBox(height: AppSpacing.sm),
                            Row(
                              children: [
                                if (badge != null) Flexible(child: badge),
                                const SizedBox(width: AppSpacing.md),
                                Expanded(child: amountText),
                              ],
                            ),
                          ],
                        )
                      : text,
                ),
                if (!compact) ...[
                  const SizedBox(width: AppSpacing.lg),
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      amountText,
                      if (badge != null) ...[
                        const SizedBox(height: AppSpacing.xs),
                        badge,
                      ],
                    ],
                  ),
                ],
              ],
            ),
          ),
        );
      },
    );
  }
}

/// Shown to guests: their history is device-only until they sign in.
class _SignInBanner extends StatefulWidget {
  const _SignInBanner();

  @override
  State<_SignInBanner> createState() => _SignInBannerState();
}

class _SignInBannerState extends State<_SignInBanner> {
  bool _dismissed = false;

  @override
  Widget build(BuildContext context) {
    if (_dismissed) return const SizedBox.shrink();
    final cs = Theme.of(context).colorScheme;
    return MaterialBanner(
      backgroundColor: cs.secondaryContainer,
      leading: Icon(Icons.info_outline, size: 18, color: cs.onSecondaryContainer),
      content: Text(
        'Showing orders from this device. Sign in to see your full order history.',
        style: TextStyle(color: cs.onSecondaryContainer, fontSize: 13),
      ),
      actions: [
        TextButton(
          onPressed: () => setState(() => _dismissed = true),
          child: Text('Not now', style: TextStyle(color: cs.onSecondaryContainer)),
        ),
        TextButton(
          onPressed: () => showDialog(
              context: context, builder: (_) => const StorefrontAuthDialog()),
          child: Text('Sign in',
              style: TextStyle(
                  color: cs.onSecondaryContainer, fontWeight: FontWeight.bold)),
        ),
      ],
    );
  }
}

/// No orders to show: what that means, and the way to the shop.
class _NoOrders extends StatelessWidget {
  const _NoOrders();

  @override
  Widget build(BuildContext context) => EmptyState(
        icon: Icons.list_alt_outlined,
        title: 'No orders yet',
        message: 'Orders you place will appear here.',
        action: OutlinedButton.icon(
          onPressed: () => context.go('/store/products'),
          icon: const Icon(Icons.storefront),
          label: const Text('Browse products'),
        ),
      );
}
