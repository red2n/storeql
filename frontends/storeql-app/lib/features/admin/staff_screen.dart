import 'mfa_policy_dialog.dart';
import 'sso_settings_dialog.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';
import 'providers/admin_providers.dart';
import 'providers/staff_names.dart';
import 'role_dialog.dart';
import '../../shared/util/short_ref.dart';
import '../../core/theme.dart';

const _roles = ['OWNER', 'MANAGER', 'STOREKEEPER', 'CASHIER'];

/// A role by its name — the one the Roles tab shows beside its code — and, while
/// the roles are loading or for a code they do not list, the code in words.
String _roleName(String code, Map<String, String> names) {
  final name = names[code];
  return name != null && name.isNotEmpty ? name : humanizeCode(code);
}

/// Role code → name, from the roles already read (empty while they load).
Map<String, String> _roleNames(List<TenantRole>? roles) =>
    {for (final r in roles ?? const <TenantRole>[]) r.code: r.name};

/// Staff and the roles they hold (20.10). Two tabs: the people assigned to
/// stores, and the roles — the four built-in tiers beside the tenant's own,
/// each of which stands on a tier and holds fewer of that tier's permissions.
class StaffScreen extends StatelessWidget {
  const StaffScreen({super.key});

  @override
  Widget build(BuildContext context) {
    // One inset for the title, the tab labels, the actions and the cards, so
    // their edges line up: 16 on a phone, 24 from tablet width.
    final gutter = context.pageGutter;
    return DefaultTabController(
      length: 2,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PageHeader(title: 'Staff'),
          TabBar(
            isScrollable: true,
            tabAlignment: TabAlignment.start,
            padding: EdgeInsetsDirectional.only(start: gutter - AppSpacing.lg),
            labelPadding:
                const EdgeInsetsDirectional.symmetric(horizontal: AppSpacing.lg),
            tabs: const [Tab(text: 'People'), Tab(text: 'Roles')],
          ),
          const Expanded(
            child: TabBarView(children: [_PeopleTab(), _RolesTab()]),
          ),
        ],
      ),
    );
  }
}

class _PeopleTab extends ConsumerWidget {
  const _PeopleTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final staffAsync = ref.watch(staffProvider);
    final gutter = context.pageGutter;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: EdgeInsetsDirectional.fromSTEB(gutter, AppSpacing.md, gutter, 0),
          // A Wrap, not a Row: four actions do not fit a phone's width on one line.
          child: Wrap(
            alignment: WrapAlignment.end,
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.sm,
            children: [
              OutlinedButton.icon(
                key: const Key('sso-open'),
                onPressed: () => showDialog<bool>(context: context, builder: (_) => const SsoSettingsDialog()),
                icon: const Icon(Icons.business_outlined),
                label: const Text('Single sign-on'),
              ),
              OutlinedButton.icon(
                key: const Key('mfa-policy-open'),
                onPressed: () => showDialog<bool>(context: context, builder: (_) => const MfaPolicyDialog()),
                icon: const Icon(Icons.verified_user_outlined),
                label: const Text('Second step'),
              ),
              FilledButton.icon(
                onPressed: () => _showAssignDialog(context, ref),
                icon: const Icon(Icons.person_add),
                label: const Text('Assign Staff'),
              ),
              IconButton(
                icon: const Icon(Icons.refresh),
                tooltip: 'Refresh staff',
                onPressed: () => ref.invalidate(staffProvider),
              ),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.sm),
        Expanded(
          child: staffAsync.when(
            loading: () => const LoadingView(label: 'Loading staff…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load staff.'),
              onRetry: () => ref.invalidate(staffProvider),
            ),
            data: (staff) {
              if (staff.isEmpty) {
                return EmptyState(
                  icon: Icons.people_outline,
                  title: 'No staff assigned yet',
                  message:
                      'Assign a user to a store with a role to grant them access.',
                  action: OutlinedButton.icon(
                    onPressed: () => _showAssignDialog(context, ref),
                    icon: const Icon(Icons.person_add),
                    label: const Text('Assign Staff'),
                  ),
                );
              }
              // People and places by name: tenant-svc's assignments carry
              // only ids, iam-svc names the logins and the stores are read
              // already. A short reference stands in while a name is unknown.
              final logins = ref
                      .watch(staffLoginsProvider(
                          staffIdsKey(staff.map((m) => m.userId))))
                      .value ??
                  const <String, String>{};
              final storeNames = {
                for (final s in ref.watch(storesProvider).value ??
                    const <StoreInfo>[])
                  s.id: s.name,
              };
              final roleNames = _roleNames(ref.watch(rolesProvider).value);
              return LayoutBuilder(builder: (context, constraints) {
                // On a phone the two actions fold into one menu, so the name
                // keeps most of the row.
                final compact = AppBreakpoints.classOf(constraints.maxWidth) ==
                    WindowClass.compact;
                return ListView.separated(
                  padding: EdgeInsetsDirectional.fromSTEB(
                      gutter, AppSpacing.sm, gutter, AppSpacing.lg),
                  itemCount: staff.length,
                  separatorBuilder: (_, _) =>
                      const SizedBox(height: AppSpacing.xs),
                  itemBuilder: (context, i) {
                    final m = staff[i];
                    return _StaffCard(
                      member: m,
                      name: staffDisplayName(m.userId, logins),
                      store: storeNames[m.storeId] ??
                          'Store ${shortRef(m.storeId)}',
                      role: _roleName(m.role, roleNames),
                      tier: _roleName(m.baseTier, roleNames),
                      compact: compact,
                      onResetSecondStep: (who) =>
                          resetSecondFactor(context, ref, m.userId, who),
                      onRemove: (who, store, role) =>
                          _removeStaff(context, ref, m, who, store, role),
                    );
                  },
                );
              });
            },
          ),
        ),
      ],
    );
  }

  void _showAssignDialog(BuildContext context, WidgetRef ref) {
    showDialog(
      context: context,
      builder: (_) => _AssignStaffDialog(
        onAssigned: () => ref.invalidate(staffProvider),
      ),
    );
  }

  Future<void> _removeStaff(BuildContext context, WidgetRef ref, StaffMember m,
      String who, String store, String role) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Remove staff assignment?'),
        content: Text(
            'Remove $role access for $who at $store. They lose access to it immediately.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel')),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(ctx).colorScheme.error,
              foregroundColor: Theme.of(ctx).colorScheme.onError,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Remove'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) return;
    try {
      await ref.read(apiClientProvider).dio.delete(
        '/${ApiConstants.tenant}/admin/staff/${m.userId}',
        queryParameters: {'store': m.storeId},
      );
      ref.invalidate(staffProvider);
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
                friendlyError(e, fallback: 'Could not remove staff member.')),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    }
  }
}

/// One assignment: who (their login), then their role and the store on the
/// line under it. The name has a line of its own, so neither the role badge nor
/// the actions can cut it short.
class _StaffCard extends StatelessWidget {
  const _StaffCard({
    required this.member,
    required this.name,
    required this.store,
    required this.role,
    required this.tier,
    required this.compact,
    required this.onResetSecondStep,
    required this.onRemove,
  });

  final StaffMember member;
  final String name;
  final String store;
  final String role;
  final String tier;
  final bool compact;
  final void Function(String who) onResetSecondStep;
  final void Function(String who, String store, String role) onRemove;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      child: ListTile(
        key: Key('staff-${member.id}'),
        leading: CircleAvatar(
          backgroundColor: cs.secondaryContainer,
          child: Icon(Icons.person_outline, color: cs.onSecondaryContainer),
        ),
        title: Text(name),
        subtitle: Padding(
          padding: const EdgeInsetsDirectional.only(top: AppSpacing.xs),
          child: Wrap(
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.xs,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              _RoleBadge(role: role),
              if (member.customRole) Text('on $tier'),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.store_outlined, size: 16, color: cs.onSurfaceVariant),
                  const SizedBox(width: AppSpacing.xs),
                  Flexible(child: Text(store)),
                ],
              ),
            ],
          ),
        ),
        trailing: compact
            ? PopupMenuButton<String>(
                key: Key('staff-actions-${member.id}'),
                tooltip: 'Actions for $name',
                onSelected: (v) => v == 'reset'
                    ? onResetSecondStep(name)
                    : onRemove(name, store, role),
                itemBuilder: (_) => [
                  const PopupMenuItem(
                    value: 'reset',
                    child: Row(
                      children: [
                        Icon(Icons.phonelink_erase_outlined, size: 20),
                        SizedBox(width: AppSpacing.md),
                        Flexible(child: Text('Reset second step')),
                      ],
                    ),
                  ),
                  PopupMenuItem(
                    value: 'remove',
                    child: Row(
                      children: [
                        Icon(Icons.delete_outline, size: 20, color: cs.error),
                        const SizedBox(width: AppSpacing.md),
                        const Flexible(child: Text('Remove')),
                      ],
                    ),
                  ),
                ],
              )
            : Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  IconButton(
                    icon: const Icon(Icons.phonelink_erase_outlined),
                    tooltip: 'Reset second step (lost phone)',
                    onPressed: () => onResetSecondStep(name),
                  ),
                  IconButton(
                    icon: Icon(Icons.delete_outline, color: cs.error),
                    tooltip: 'Remove',
                    onPressed: () => onRemove(name, store, role),
                  ),
                ],
              ),
      ),
    );
  }
}

/// A role in the house badge. The People tab passes the role's name; the Roles
/// tab, where roles are defined by code, passes the code beside the name.
class _RoleBadge extends StatelessWidget {
  final String role;
  const _RoleBadge({required this.role});

  @override
  Widget build(BuildContext context) =>
      StatusBadge(role, tone: StatusTone.accent);
}

class _AssignStaffDialog extends ConsumerStatefulWidget {
  final VoidCallback onAssigned;
  const _AssignStaffDialog({required this.onAssigned});

  @override
  ConsumerState<_AssignStaffDialog> createState() => _AssignStaffDialogState();
}

class _AssignStaffDialogState extends ConsumerState<_AssignStaffDialog> {
  final _formKey = GlobalKey<FormState>();
  final _emailCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  String _role = 'CASHIER';
  String? _storeId;
  bool _loading = false;
  String? _error;

  /// The dropdown: the built-in tiers first, then the tenant's own roles, each
  /// named with the tier it stands on. Falls back to the tiers alone while the
  /// list loads or if it cannot be read.
  List<DropdownMenuItem<String>> _roleItems(AsyncValue<List<TenantRole>> roles) {
    // Every role by its name — the tiers as the staff list names them
    // (*Cashier*), a custom role with the tier it stands on (*Shift lead · on
    // Manager*) — never its code.
    final names = _roleNames(roles.value);
    final items = _roles
        .map((r) => DropdownMenuItem(value: r, child: Text(_roleName(r, names))))
        .toList();
    for (final r in (roles.value ?? const <TenantRole>[]).where((r) => r.custom)) {
      items.add(DropdownMenuItem(
        value: r.code,
        child: Text('${r.name} · on ${_roleName(r.baseTier, names)}',
            overflow: TextOverflow.ellipsis),
      ));
    }
    return items;
  }

  @override
  void dispose() {
    _emailCtrl.dispose();
    _passwordCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    if (_storeId == null) {
      setState(() => _error = 'Select a store.');
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    final dio = ref.read(apiClientProvider).dio;
    final pwd = _passwordCtrl.text.trim();
    try {
      // 1. Find-or-create the account by email — backend owns the user UUID.
      final provision = await dio.post(
        '/${ApiConstants.iam}/auth/admin/staff-users',
        data: {
          'email': _emailCtrl.text.trim(),
          if (pwd.isNotEmpty) 'password': pwd,
        },
      );
      final pdata = provision.data['data'] as Map<String, dynamic>;
      final userId = pdata['userId'] as String;
      final created = pdata['created'] as bool? ?? false;
      final tempPassword = pdata['tempPassword'] as String?;

      // 2. Assign that user a role at the chosen store.
      await dio.post(
        '/${ApiConstants.tenant}/admin/staff',
        data: {'userId': userId, 'storeId': _storeId, 'role': _role},
      );
      if (!mounted) return;
      widget.onAssigned();
      Navigator.pop(context);
      _showResult(created, tempPassword);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = _friendly(e);
      });
    }
  }

  void _showResult(bool created, String? tempPassword) {
    final email = _emailCtrl.text.trim();
    if (created && tempPassword != null) {
      // New account — surface the temp password so the admin can pass it on.
      showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          icon: Icon(Icons.check_circle_outline,
              color: Theme.of(ctx).colorScheme.primary, size: 40),
          title: const Text('Staff account created'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('$email can now sign in with this temporary password '
                  '(ask them to change it after first login). It will not be shown again:'),
              const SizedBox(height: 12),
              _TempPasswordReveal(password: tempPassword),
            ],
          ),
          actions: [
            FilledButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('Done')),
          ],
        ),
      );
    } else {
      final role = _roleName(_role, _roleNames(ref.read(rolesProvider).value));
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('$email assigned as $role.')),
      );
    }
  }

  String _friendly(Object e) {
    final code = apiErrorCode(e);
    final status = e is DioException ? e.response?.statusCode : null;
    if (code == 'EMAIL_IN_OTHER_TENANT' || status == 409) {
      return 'That email already belongs to another business, or the user '
          'already has that role at this store.';
    }
    if (status == 400) return 'Check the email is valid.';
    return friendlyError(e, fallback: 'Could not assign staff.');
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);
    final rolesAsync = ref.watch(rolesProvider);
    return AlertDialog(
      title: const Text('Assign Staff'),
      content: SizedBox(
        width: 420,
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                "Enter the staff member's email. If they don't have an account "
                'yet, one is created automatically and a temporary password is '
                'shown for you to share.',
                style: Theme.of(context)
                    .textTheme
                    .bodySmall
                    ?.copyWith(color: cs.outline),
              ),
              const SizedBox(height: 16),
              if (_error != null) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: cs.errorContainer,
                    borderRadius: AppRadius.chip,
                  ),
                  child: Text(_error!, style: TextStyle(color: cs.onErrorContainer)),
                ),
                const SizedBox(height: 12),
              ],
              TextFormField(
                controller: _emailCtrl,
                keyboardType: TextInputType.emailAddress,
                autofillHints: const [AutofillHints.email],
                decoration: const InputDecoration(
                  labelText: 'Staff email *',
                  prefixIcon: Icon(Icons.alternate_email),
                ),
                validator: (v) {
                  final s = v?.trim() ?? '';
                  if (s.isEmpty) return 'Required';
                  if (!s.contains('@') || !s.contains('.')) {
                    return 'Enter a valid email';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _passwordCtrl,
                decoration: const InputDecoration(
                  labelText: 'Temporary password (optional)',
                  helperText: 'Leave blank to auto-generate',
                  prefixIcon: Icon(Icons.password_outlined),
                ),
                validator: (v) {
                  final s = v?.trim() ?? '';
                  if (s.isNotEmpty && s.length < 15) {
                    return 'At least 15 characters — a phrase of a few words is easiest';
                  }
                  return null;
                },
              ),
              const SizedBox(height: 12),
              storesAsync.when(
                loading: () => const LinearProgressIndicator(),
                error: (e, _) => Text(
                    friendlyError(e, fallback: 'Could not load stores.'),
                    style: TextStyle(color: cs.error)),
                data: (stores) => DropdownButtonFormField<String>(
                  initialValue: _storeId,
                  isExpanded: true,
                  decoration: const InputDecoration(
                    labelText: 'Store *',
                    prefixIcon: Icon(Icons.store_outlined),
                  ),
                  items: stores
                      .map((s) => DropdownMenuItem(
                            value: s.id,
                            child: Text('${s.name} (${s.code})',
                                overflow: TextOverflow.ellipsis),
                          ))
                      .toList(),
                  onChanged: (v) => setState(() => _storeId = v),
                  validator: (v) => v == null ? 'Required' : null,
                ),
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                key: const Key('assign-role'),
                initialValue: _role,
                isExpanded: true,
                decoration: const InputDecoration(
                  labelText: 'Role *',
                  prefixIcon: Icon(Icons.shield_outlined),
                ),
                items: _roleItems(rolesAsync),
                onChanged: (v) => setState(() => _role = v!),
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
                  child: CircularProgressIndicator(strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
              : const Text('Assign'),
        ),
      ],
    );
  }
}

/// Shows a one-time temp password masked by default (shoulder-surfing on a shared admin
/// screen is the threat model here, not just the network/log exposure a one-time secret already
/// avoids) with an explicit reveal toggle and a copy button so the admin doesn't need to select
/// the raw text by hand.
class _TempPasswordReveal extends StatefulWidget {
  final String password;
  const _TempPasswordReveal({required this.password});

  @override
  State<_TempPasswordReveal> createState() => _TempPasswordRevealState();
}

class _TempPasswordRevealState extends State<_TempPasswordReveal> {
  bool _revealed = false;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsetsDirectional.fromSTEB(
          AppSpacing.md, AppSpacing.sm, AppSpacing.xs, AppSpacing.sm),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: AppRadius.chip,
      ),
      child: Row(
        children: [
          Expanded(
            child: _revealed
                ? SelectableText(widget.password,
                    style: const TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 16,
                        fontWeight: FontWeight.bold))
                : Text('•' * widget.password.length,
                    style: const TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 2)),
          ),
          IconButton(
            tooltip: _revealed ? 'Hide' : 'Reveal',
            icon: Icon(_revealed ? Icons.visibility_off : Icons.visibility, size: 20),
            onPressed: () => setState(() => _revealed = !_revealed),
          ),
          IconButton(
            tooltip: 'Copy',
            icon: const Icon(Icons.copy, size: 20),
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: widget.password));
              if (!context.mounted) return;
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(
                    content: Text('Password copied. Clear your clipboard once it\'s shared.'),
                    duration: Duration(seconds: 3)),
              );
            },
          ),
        ],
      ),
    );
  }
}

// ── Roles (20.10) ────────────────────────────────────────────────────────────

class _RolesTab extends ConsumerWidget {
  const _RolesTab();

  Future<void> _delete(BuildContext context, WidgetRef ref, TenantRole role) async {
    // By its name, as the list names it; the code only travels in the request.
    final name = _roleName(role.code, {role.code: role.name});
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Delete $name?'),
        content: const Text(
            'Refused while anyone still holds it — remove those assignments first.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(
            key: const Key('role-delete-confirm'),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (ok != true || !context.mounted) return;
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .delete('/${ApiConstants.tenant}/admin/roles/${role.code}');
      ref.invalidate(rolesProvider);
      if (!context.mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('$name deleted.')));
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(friendlyError(e, fallback: 'Could not delete the role.'))));
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final rolesAsync = ref.watch(rolesProvider);
    final gutter = context.pageGutter;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: EdgeInsetsDirectional.fromSTEB(gutter, AppSpacing.md, gutter, 0),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  'A role of your own stands on a built-in tier and holds fewer of its '
                  'permissions — a manager who cannot void a sale, a cashier who cannot '
                  'open the drawer. It can never hold more than its tier.',
                  style: TextStyle(color: cs.outline, fontSize: 13),
                ),
              ),
              const SizedBox(width: 12),
              FilledButton.icon(
                key: const Key('define-role'),
                onPressed: () => showDialog<bool>(
                    context: context, builder: (_) => const RoleDialog()),
                icon: const Icon(Icons.add_moderator_outlined),
                label: const Text('Define role'),
              ),
              const SizedBox(width: 8),
              IconButton(
                icon: const Icon(Icons.refresh),
                tooltip: 'Refresh roles',
                onPressed: () => ref.invalidate(rolesProvider),
              ),
            ],
          ),
        ),
        const SizedBox(height: 8),
        Expanded(
          child: rolesAsync.when(
            loading: () => const LoadingView(label: 'Loading roles…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load roles.'),
              onRetry: () => ref.invalidate(rolesProvider),
            ),
            data: (roles) {
              final names = _roleNames(roles);
              return ListView.separated(
              padding: EdgeInsetsDirectional.fromSTEB(
                  gutter, AppSpacing.sm, gutter, AppSpacing.lg),
              itemCount: roles.length,
              separatorBuilder: (_, _) => const SizedBox(height: 4),
              itemBuilder: (context, i) {
                final r = roles[i];
                return Card(
                  child: ListTile(
                    leading: CircleAvatar(
                      backgroundColor:
                          r.custom ? cs.tertiaryContainer : cs.secondaryContainer,
                      child: Icon(
                        r.custom ? Icons.shield_outlined : Icons.verified_user_outlined,
                        color: r.custom ? cs.onTertiaryContainer : cs.onSecondaryContainer,
                      ),
                    ),
                    // The role by its name; a custom one says so in a badge.
                    title: Row(children: [
                      Flexible(child: Text(r.name, overflow: TextOverflow.ellipsis)),
                      if (r.custom) ...[
                        const SizedBox(width: AppSpacing.sm),
                        const _RoleBadge(role: 'Custom'),
                      ],
                    ]),
                    subtitle: Text(
                      r.custom
                          ? 'On ${_roleName(r.baseTier, names)} · ${r.permissions.isEmpty ? 'holds nothing' : r.permissions.join(', ')}'
                          : r.permissions.isEmpty
                              ? 'Built in · holds no permission'
                              : 'Built in · ${r.permissions.join(', ')}',
                      style: const TextStyle(fontSize: 11, fontFamily: 'monospace'),
                    ),
                    trailing: r.custom
                        ? Row(mainAxisSize: MainAxisSize.min, children: [
                            IconButton(
                              key: Key('edit-role-${r.code}'),
                              icon: const Icon(Icons.edit_outlined),
                              tooltip: 'Redefine',
                              onPressed: () => showDialog<bool>(
                                  context: context,
                                  builder: (_) => RoleDialog(existing: r)),
                            ),
                            IconButton(
                              key: Key('delete-role-${r.code}'),
                              icon: Icon(Icons.delete_outline, color: cs.error),
                              tooltip: 'Delete',
                              onPressed: () => _delete(context, ref, r),
                            ),
                          ])
                        : null,
                  ),
                );
              },
            );
            },
          ),
        ),
      ],
    );
  }
}
