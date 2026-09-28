import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/format.dart';
import '../../core/network/api_error.dart';
import '../../core/offline/offline_queue.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/adaptive_actions.dart';
import '../../shared/widgets/adaptive_nav_shell.dart';
import 'pos_providers.dart';
import 'pos_session_providers.dart';
import 'pos_printer_settings_dialog.dart';
import 'container_return_dialog.dart';
import 'customer_display.dart';
import 'customer_display_channel.dart';

/// [pending] badges the Pending destination so unsynced sales are visible from
/// anywhere in the terminal, not only once the cashier goes looking.
List<AdaptiveNavDestination> _destinations(int pending) => [
      const AdaptiveNavDestination(
        label: 'Sale',
        icon: Icons.shopping_cart_outlined,
        selectedIcon: Icons.shopping_cart,
      ),
      const AdaptiveNavDestination(
        label: 'Tender',
        icon: Icons.payments_outlined,
        selectedIcon: Icons.payments,
      ),
      const AdaptiveNavDestination(
        label: 'Cash',
        icon: Icons.account_balance_wallet_outlined,
        selectedIcon: Icons.account_balance_wallet,
      ),
      AdaptiveNavDestination(
        label: 'Pending',
        icon: Icons.cloud_off_outlined,
        selectedIcon: Icons.cloud_off,
        badgeCount: pending,
      ),
    ];

const _routes = ['/pos/cart', '/pos/tender', '/pos/cash', '/pos/pending'];

class PosShell extends ConsumerStatefulWidget {
  final String currentLocation;
  final Widget child;

  const PosShell({
    super.key,
    required this.currentLocation,
    required this.child,
  });

  @override
  ConsumerState<PosShell> createState() => _PosShellState();
}

class _PosShellState extends ConsumerState<PosShell> {
  Timer? _heartbeat;

  @override
  void initState() {
    super.initState();
    // Keep the open session off the server's idle sweep while the terminal is up.
    _heartbeat = Timer.periodic(const Duration(minutes: 4), (_) {
      if (ref.read(posSessionProvider) != null) {
        ref.read(posSessionProvider.notifier).touch();
      }
    });
  }

  @override
  void dispose() {
    _heartbeat?.cancel();
    super.dispose();
  }

  bool get _pendingSelected =>
      widget.currentLocation.startsWith('/pos/pending');

  int get _selectedIndex => _pendingSelected
      ? 3
      : widget.currentLocation.startsWith('/pos/cash')
          ? 2
          : widget.currentLocation.startsWith('/pos/tender')
              ? 1
              : 0;

  /// Tells the customer-facing display what the till shows: the sale as it
  /// is rung up, or nothing between customers.
  void _tellDisplay() {
    final channel = ref.read(customerDisplayChannelProvider);
    if (!channel.supported) return;
    final lines = ref.read(posCartProvider);
    final storeId = ref.read(posStoreProvider);
    final stores = ref.read(posStoresProvider).value ?? const [];
    final store = stores.where((s) => s.id == storeId).toList();
    channel.post(customerDisplaySale(
      storeName: store.isEmpty ? '' : store.first.name,
      currency: lines.isEmpty ? '' : lines.first.currency,
      lines: lines,
      discount: ref.read(posDiscountProvider),
    ));
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(posSessionProvider);
    final pending = ref.watch(offlineQueueCountProvider);
    ref.listen(posCartProvider, (_, _) => _tellDisplay());
    ref.listen(posDiscountProvider, (_, _) => _tellDisplay());
    ref.listen(posStoreProvider, (_, _) => _tellDisplay());

    // Scope the amber channel accent (app bar, primary actions, nav indicator)
    // to the whole POS subtree via the theme system, rather than threading the
    // raw AppTheme.posAccent constant into each widget by hand.
    return Theme(
      data: AppTheme.applyPosAccent(Theme.of(context)),
      child: AdaptiveNavShell(
        title: 'POS Terminal',
        leadingIcon: Icons.point_of_sale,
        // Four flat destinations — a bottom bar, per Material's compact-width guidance.
        compactStyle: CompactNavStyle.bottomBar,
        destinations: _destinations(pending),
        selectedIndex: _selectedIndex,
        onDestinationSelected: (i) => context.go(_routes[i]),
        // Six commands: all on the bar from tablet width, but on a phone only
        // Returns stays — the rest go into ⋮ so the title keeps its room.
        actions: [
          AdaptiveActions(actions: [
            if (session != null &&
                ref.read(customerDisplayChannelProvider).supported)
              AdaptiveAction(
                key: const Key('pos-customer-display'),
                label: 'Customer display',
                icon: Icons.desktop_windows_outlined,
                onPressed: () {
                  ref.read(customerDisplayChannelProvider).openWindow();
                  _tellDisplay();
                },
              ),
            if (session != null)
              AdaptiveAction(
                key: const Key('pos-container-return'),
                label: 'Returns',
                icon: Icons.recycling,
                showLabel: true,
                keepOnCompact: true,
                onPressed: () async {
                  final amount = await showContainerReturnDialog(context);
                  if (amount == null || !context.mounted) return;
                  // In the scheme's currency, which the dialog has just read.
                  final storeId = ref.read(posStoreProvider);
                  final currency = storeId == null
                      ? null
                      : ref
                          .read(posDepositSchemeProvider(storeId))
                          .value
                          ?.currency;
                  ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                      content: Text('Deposit refunded: hand back '
                          '${AppFormat.money(amount, currencyCode: currency)}')));
                },
              ),
            if (session != null)
              AdaptiveAction(
                label: 'Clock out',
                icon: Icons.logout,
                showLabel: true,
                onPressed: () async {
                  final ok = await showDialog<bool>(
                    context: context,
                    builder: (ctx) => AlertDialog(
                      title: const Text('Clock out?'),
                      content: Text(pending == 0
                          ? 'This ends your POS session. Any sale in progress is kept.'
                          : 'This ends your POS session. $pending sale'
                              '${pending == 1 ? '' : 's'} still '
                              "haven't reached the server — they stay on this till "
                              'and are sent when the network is back.'),
                      actions: [
                        TextButton(
                            onPressed: () => Navigator.pop(ctx, false),
                            child: const Text('Cancel')),
                        FilledButton(
                            onPressed: () => Navigator.pop(ctx, true),
                            child: const Text('Clock out')),
                      ],
                    ),
                  );
                  if (ok == true) {
                    await ref.read(posSessionProvider.notifier).clockOut();
                  }
                },
              ),
            AdaptiveAction(
              key: const Key('pos-printer'),
              label: 'Receipt printer',
              icon: Icons.print_outlined,
              onPressed: () => showDialog<void>(
                  context: context,
                  builder: (_) => const PrinterSettingsDialog()),
            ),
            AdaptiveAction(
              key: const Key('pos-security'),
              label: 'Sign-in security',
              icon: Icons.verified_user_outlined,
              onPressed: () => context.push('/account/security'),
            ),
            AdaptiveAction(
              label: 'Sign out',
              icon: Icons.exit_to_app,
              onPressed: () => ref.read(authNotifierProvider.notifier).logout(),
            ),
          ]),
        ],
        // Gate the terminal: no selling until a cashier clocks in. Pending is
        // the exception — sales taken offline are money the server has not
        // been told about, and must stay in view (and syncable) clocked out.
        child: session == null && !_pendingSelected
            ? const _ClockInView()
            : widget.child,
      ),
    );
  }
}

/// Clock-in screen — a cashier picks a store and opens a POS session before any
/// sale can be rung up.
class _ClockInView extends ConsumerStatefulWidget {
  const _ClockInView();

  @override
  ConsumerState<_ClockInView> createState() => _ClockInViewState();
}

class _ClockInViewState extends ConsumerState<_ClockInView> {
  String? _storeId;
  bool _busy = false;
  String? _error;

  Future<void> _clockIn() async {
    if (_storeId == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(posSessionProvider.notifier).clockIn(_storeId!);
    } catch (e) {
      // The card can be gone by now: Pending stays reachable while clocked
      // out, and leaving for it unmounts this.
      if (!mounted) return;
      setState(
          () => _error = friendlyError(e, fallback: 'Could not clock in.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(posStoresProvider);
    final pending = ref.watch(offlineQueueCountProvider);

    return Center(
      // Scrolls when a phone on its side or large text leaves it too little
      // height, rather than overflowing.
      child: SingleChildScrollView(
        padding: context.pagePadding,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Card(
            margin: EdgeInsets.zero,
            child: Padding(
              padding: const EdgeInsets.all(AppSpacing.xl),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Icon(Icons.point_of_sale,
                      size: 48, color: Theme.of(context).colorScheme.primary),
                  const SizedBox(height: AppSpacing.md),
                  Text('Clock in',
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.headlineSmall),
                  const SizedBox(height: AppSpacing.xs),
                  Text('Open a POS session to start selling.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: cs.onSurfaceVariant)),
                  const SizedBox(height: AppSpacing.xl),
                  storesAsync.when(
                    loading: () => const Center(
                        child: Padding(
                            padding: EdgeInsets.all(AppSpacing.sm),
                            child: CircularProgressIndicator())),
                    error: (e, _) => Text(
                        friendlyError(e, fallback: 'Could not load stores.'),
                        style: TextStyle(color: cs.error)),
                    data: (stores) {
                      if (stores.isEmpty) {
                        return Text('No stores configured.',
                            style: TextStyle(color: cs.error));
                      }
                      _storeId ??= stores.first.id;
                      return DropdownButtonFormField<String>(
                        initialValue: _storeId,
                        decoration: const InputDecoration(
                          labelText: 'Store / terminal',
                          prefixIcon: Icon(Icons.store_outlined),
                        ),
                        items: [
                          for (final s in stores)
                            DropdownMenuItem(value: s.id, child: Text(s.name)),
                        ],
                        onChanged:
                            _busy ? null : (v) => setState(() => _storeId = v),
                      );
                    },
                  ),
                  if (_error != null) ...[
                    const SizedBox(height: AppSpacing.md),
                    Text(_error!, style: TextStyle(color: cs.error)),
                  ],
                  const SizedBox(height: AppSpacing.xl),
                  FilledButton.icon(
                    style: FilledButton.styleFrom(
                      backgroundColor: context.channelAccent.color,
                      foregroundColor: context.channelAccent.onColor,
                      padding:
                          const EdgeInsets.symmetric(vertical: AppSpacing.md),
                    ),
                    onPressed: _busy ? null : _clockIn,
                    icon: _busy
                        ? SizedBox(
                            height: 18,
                            width: 18,
                            child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: context.channelAccent.onColor))
                        : const Icon(Icons.login),
                    label: Text(_busy ? 'Opening…' : 'Clock in',
                        style: const TextStyle(fontSize: 16)),
                  ),
                  // Sales taken offline stay in view while clocked out.
                  if (pending > 0) ...[
                    const SizedBox(height: AppSpacing.sm),
                    TextButton.icon(
                      key: const Key('pos-clocked-out-pending'),
                      // Not while a clock-in is in flight: it would leave the
                      // card, and come back to a second Clock in.
                      onPressed:
                          _busy ? null : () => context.go('/pos/pending'),
                      icon: const Icon(Icons.cloud_off_outlined),
                      label: Text('$pending sale${pending == 1 ? '' : 's'} '
                          'waiting to sync'),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
