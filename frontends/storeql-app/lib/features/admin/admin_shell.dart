import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/auth/password_policy.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/adaptive_nav_shell.dart';
import 'providers/admin_providers.dart';
import '../../core/theme.dart';
import '../../core/spacing.dart';

class _AdminNavItem {
  final AdaptiveNavDestination destination;
  final String route;
  /// When true, shown to storekeeper-only users (warehouse-focused shell).
  final bool storekeeperVisible;

  /// When true, shown to the owner only: the page is the owner's alone, and a
  /// manager sent there would find nothing but a line saying so.
  final bool ownerOnly;

  const _AdminNavItem({
    required this.destination,
    required this.route,
    this.storekeeperVisible = false,
    this.ownerOnly = false,
  });
}

/// The pages a storekeeper-only login is offered: each must be one the router
/// lets them open (`storekeeperAdminAllowed`), which is one whose reads the
/// services open to any member of staff.
List<String> get storekeeperAdminRoutes =>
    [for (final i in _navItems) if (i.storekeeperVisible) i.route];

/// Grouped by what a manager is doing — selling, looking after stock, keeping
/// the shop legal, the money, the business itself, its data — and listed under
/// those headings (AdaptiveNavShell's sectioned layout), not as one flat run of
/// 31. Keep each section's items together; give every item its own icon.
const _navItems = [
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Dashboard',
      icon: Icons.dashboard_outlined,
      selectedIcon: Icons.dashboard,
    ),
    route: '/admin/dashboard',
  ),
  // ── Sell ──
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Orders',
      icon: Icons.receipt_long_outlined,
      selectedIcon: Icons.receipt_long,
      section: 'Sell',
    ),
    route: '/admin/orders',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Fulfilment',
      icon: Icons.outbox_outlined,
      selectedIcon: Icons.outbox,
      section: 'Sell',
    ),
    route: '/admin/fulfilment',
    // Picking, packing and the handover are the storekeeper's work too.
    storekeeperVisible: true,
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Customers',
      icon: Icons.groups_outlined,
      selectedIcon: Icons.groups,
      section: 'Sell',
    ),
    route: '/admin/customers',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Sales',
      icon: Icons.card_giftcard_outlined,
      selectedIcon: Icons.card_giftcard,
      section: 'Sell',
    ),
    route: '/admin/sales',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Messages',
      icon: Icons.mail_outline,
      selectedIcon: Icons.mail,
      section: 'Sell',
    ),
    route: '/admin/messages',
  ),
  // ── Products & stock ──
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Catalog',
      icon: Icons.inventory_2_outlined,
      selectedIcon: Icons.inventory_2,
      section: 'Products & stock',
    ),
    route: '/admin/catalog',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Inventory',
      icon: Icons.warehouse_outlined,
      selectedIcon: Icons.warehouse,
      section: 'Products & stock',
    ),
    route: '/admin/inventory',
    storekeeperVisible: true,
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Pricing',
      icon: Icons.sell_outlined,
      selectedIcon: Icons.sell,
      section: 'Products & stock',
    ),
    route: '/admin/pricing',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Procurement',
      icon: Icons.local_shipping_outlined,
      selectedIcon: Icons.local_shipping,
      section: 'Products & stock',
    ),
    route: '/admin/procurement',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Shelf space',
      icon: Icons.shelves,
      selectedIcon: Icons.shelves,
      section: 'Products & stock',
    ),
    route: '/admin/shelf-space',
    // The shelf-gap report is now a staff read: a
    // storekeeper sees the Gaps tab only, at their own stores; Shelving and
    // Range stay management-only inside the screen itself.
    storekeeperVisible: true,
  ),
  // ── Safety & compliance ──
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Food safety',
      icon: Icons.health_and_safety_outlined,
      selectedIcon: Icons.health_and_safety,
      section: 'Safety & compliance',
    ),
    route: '/admin/food-safety',
    storekeeperVisible: true,
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Recalls',
      icon: Icons.report_outlined,
      selectedIcon: Icons.report,
      section: 'Safety & compliance',
    ),
    route: '/admin/recalls',
    storekeeperVisible: true,
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Age checks',
      icon: Icons.badge_outlined,
      selectedIcon: Icons.badge,
      section: 'Safety & compliance',
    ),
    route: '/admin/age-checks',
    // Management-only: the register reads /admin/pos/age-checks.
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Legal obligations',
      icon: Icons.gavel_outlined,
      selectedIcon: Icons.gavel,
      section: 'Safety & compliance',
    ),
    route: '/admin/obligations',
    storekeeperVisible: true,
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Statutory returns',
      icon: Icons.event_note_outlined,
      selectedIcon: Icons.event_note,
      section: 'Safety & compliance',
    ),
    route: '/admin/statutory-returns',
  ),
  // ── Money ──
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Reports',
      icon: Icons.bar_chart_outlined,
      selectedIcon: Icons.bar_chart,
      section: 'Money',
    ),
    route: '/admin/reports',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Card machines',
      icon: Icons.point_of_sale_outlined,
      selectedIcon: Icons.point_of_sale,
      section: 'Money',
    ),
    route: '/admin/terminals',
    storekeeperVisible: false,
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Card settlements',
      icon: Icons.account_balance_outlined,
      selectedIcon: Icons.account_balance,
      section: 'Money',
    ),
    route: '/admin/settlements',
    storekeeperVisible: false,
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Chargebacks',
      icon: Icons.credit_card_off_outlined,
      selectedIcon: Icons.credit_card_off,
      section: 'Money',
    ),
    route: '/admin/disputes',
    storekeeperVisible: false,
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Container deposits',
      icon: Icons.recycling_outlined,
      selectedIcon: Icons.recycling,
      section: 'Money',
    ),
    route: '/admin/deposits',
    storekeeperVisible: false,
  ),
  // ── Business ──
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Stores',
      icon: Icons.store_outlined,
      selectedIcon: Icons.store,
      section: 'Business',
    ),
    route: '/admin/stores',
    storekeeperVisible: true,
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Staff',
      icon: Icons.people_outline,
      selectedIcon: Icons.people,
      section: 'Business',
    ),
    route: '/admin/staff',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Plan',
      icon: Icons.workspace_premium_outlined,
      selectedIcon: Icons.workspace_premium,
      section: 'Business',
    ),
    route: '/admin/plan',
    storekeeperVisible: false,
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Integrations',
      icon: Icons.vpn_key_outlined,
      selectedIcon: Icons.vpn_key,
      section: 'Business',
    ),
    route: '/admin/integrations',
    storekeeperVisible: false,
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Billing',
      icon: Icons.request_quote_outlined,
      selectedIcon: Icons.request_quote,
      section: 'Business',
    ),
    route: '/admin/billing',
    storekeeperVisible: false,
  ),
  // ── Data & security ──
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Audit',
      icon: Icons.manage_search_outlined,
      selectedIcon: Icons.manage_search,
      section: 'Data & security',
    ),
    route: '/admin/audit',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Privacy',
      icon: Icons.privacy_tip_outlined,
      selectedIcon: Icons.privacy_tip,
      section: 'Data & security',
    ),
    route: '/admin/privacy',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Data retention',
      icon: Icons.auto_delete_outlined,
      selectedIcon: Icons.auto_delete,
      section: 'Data & security',
    ),
    route: '/admin/retention',
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Data export',
      icon: Icons.move_up_outlined,
      selectedIcon: Icons.move_up,
      section: 'Data & security',
    ),
    route: '/admin/tenant-data',
    // Taking the data out, bringing it in and giving notice are the owner's.
    ownerOnly: true,
  ),
  _AdminNavItem(
    destination: AdaptiveNavDestination(
      label: 'Security notices',
      icon: Icons.shield_outlined,
      selectedIcon: Icons.shield,
      section: 'Data & security',
    ),
    route: '/admin/security-notices',
  ),
];

class AdminShell extends ConsumerWidget {
  final String currentLocation;
  final Widget child;

  const AdminShell({
    super.key,
    required this.currentLocation,
    required this.child,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenantAsync = ref.watch(tenantInfoProvider);
    final tenantName = tenantAsync.value?.name ?? '';
    final auth = ref.watch(authNotifierProvider).value;
    final storekeeperOnly = auth is AuthAuthenticated &&
        auth.isStorekeeper &&
        !auth.isManager;
    final inSandbox = auth is AuthAuthenticated && auth.sandbox;
    final owner =
        auth is AuthAuthenticated && auth.roles.contains(UserRoles.owner);

    final items = _navItems
        .where((i) => !storekeeperOnly || i.storekeeperVisible)
        .where((i) => owner || !i.ownerOnly)
        .toList();
    final routes = items.map((i) => i.route).toList();
    final destinations = items.map((i) => i.destination).toList();

    var selectedIndex =
        routes.indexWhere((r) => currentLocation.startsWith(r));
    if (selectedIndex < 0) selectedIndex = 0;

    return AdaptiveNavShell(
      title: tenantName,
      destinations: destinations,
      selectedIndex: selectedIndex,
      onDestinationSelected: (i) => context.go(routes[i]),
      actions: [
        PopupMenuButton<String>(
          icon: const Icon(Icons.account_circle_outlined),
          tooltip: 'Account',
          onSelected: (v) {
            if (v == 'password') {
              showDialog(
                  context: context, builder: (_) => const ChangePasswordDialog());
            } else if (v == 'security') {
              context.push('/account/security');
            } else if (v == 'logout') {
              ref.read(authNotifierProvider.notifier).logout();
            }
          },
          itemBuilder: (_) => const [
            PopupMenuItem(
                value: 'password',
                child: Row(children: [
                  Icon(Icons.lock_outline, size: 18),
                  SizedBox(width: 8),
                  Text('Change password'),
                ])),
            PopupMenuItem(
                value: 'security',
                child: Row(children: [
                  Icon(Icons.verified_user_outlined, size: 18),
                  SizedBox(width: 8),
                  Text('Sign-in security'),
                ])),
            PopupMenuItem(
                value: 'logout',
                child: Row(children: [
                  Icon(Icons.logout, size: 18),
                  SizedBox(width: 8),
                  Text('Sign out'),
                ])),
          ],
        ),
      ],
      child: inSandbox
          ? Column(children: [const _SandboxBanner(), Expanded(child: child)])
          : child,
    );
  }
}

/// Where the owner is (22.8): in the sandbox, where nothing is real, with the
/// way back to the live business one tap away.
class _SandboxBanner extends ConsumerWidget {
  const _SandboxBanner();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    // A persistent message with one action: a MaterialBanner (§7.1), in the
    // tertiary container so it reads apart from alerts and errors.
    return MaterialBanner(
      key: const Key('sandbox-banner'),
      backgroundColor: cs.tertiaryContainer,
      padding: const EdgeInsetsDirectional.fromSTEB(
          AppSpacing.lg, AppSpacing.sm, AppSpacing.sm, AppSpacing.sm),
      leadingPadding: const EdgeInsetsDirectional.only(end: AppSpacing.md),
      leading: Icon(Icons.science_outlined, color: cs.onTertiaryContainer),
      content: Text(
        'Sandbox — nothing here is real: no message leaves it, no money moves, nothing is billed.',
        style: TextStyle(color: cs.onTertiaryContainer),
      ),
      actions: [
        TextButton(
          key: const Key('sandbox-leave'),
          style: TextButton.styleFrom(foregroundColor: cs.onTertiaryContainer),
          onPressed: () =>
              ref.read(authNotifierProvider.notifier).leaveSandbox(),
          child: const Text('Back to live'),
        ),
      ],
    );
  }
}

/// Lets the signed-in user change their own password (iam-svc).
class ChangePasswordDialog extends ConsumerStatefulWidget {
  const ChangePasswordDialog({super.key});

  @override
  ConsumerState<ChangePasswordDialog> createState() =>
      _ChangePasswordDialogState();
}

class _ChangePasswordDialogState extends ConsumerState<ChangePasswordDialog> {
  final _formKey = GlobalKey<FormState>();
  final _currentCtrl = TextEditingController();
  final _newCtrl = TextEditingController();
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _currentCtrl.dispose();
    _newCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.iam}/auth/change-password',
        data: {
          'currentPassword': _currentCtrl.text,
          'newPassword': _newCtrl.text,
        },
      );
      if (!mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Password changed.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        final status = e is DioException ? e.response?.statusCode : null;
        // iam-svc answers 401 for a wrong current password, 400 for a policy
        // refusal of the new one (PASSWORD_TOO_SHORT/TOO_LONG/IS_IDENTITY/
        // BREACHED) — the same words the field's own rule already used, not
        // "Current password is incorrect." for both.
        final policy = ref.read(passwordPolicyProvider).value ?? PasswordPolicy.fallback;
        _error = status == 401
            ? 'Current password is incorrect.'
            : passwordPolicyRefusal(apiErrorCode(e), policy) ??
                friendlyError(e, fallback: 'Could not change password.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final policy = watchPasswordPolicy(ref);
    return AlertDialog(
      title: const Text('Change password'),
      content: SizedBox(
        width: 360,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                      color: cs.errorContainer,
                      borderRadius: AppRadius.chip),
                  child: Text(_error!,
                      style: TextStyle(color: cs.onErrorContainer)),
                ),
                const SizedBox(height: 12),
              ],
              TextFormField(
                controller: _currentCtrl,
                obscureText: true,
                decoration:
                    const InputDecoration(labelText: 'Current password'),
                validator: (v) =>
                    v == null || v.isEmpty ? 'Required' : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                key: const Key('change-password-new'),
                controller: _newCtrl,
                obscureText: true,
                decoration: InputDecoration(
                  labelText: 'New password',
                  // The published policy's rule, before it is typed — never
                  // learned only from a refusal.
                  helperText: passwordRuleText(policy),
                  helperMaxLines: 2,
                  errorMaxLines: 2,
                ),
                validator: (v) => passwordLengthProblemPlain(v, policy),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading ? null : _submit,
          child: _loading
              ?  SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
              : const Text('Change'),
        ),
      ],
    );
  }
}
