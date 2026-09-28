import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../admin/providers/admin_providers.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/reference_fields.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/scrollable_table.dart';
import '../../shared/widgets/status_badge.dart';
import 'tenant_onboarding_notifier.dart';

class TenantsScreen extends ConsumerWidget {
  const TenantsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tenantsAsync = ref.watch(allTenantsProvider);
    // Kept through a refresh, hidden while the list itself shows an error.
    final count = tenantsAsync.hasError ? null : tenantsAsync.value?.length;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        PageHeader(
          title: 'Tenants',
          subtitle: count == null ? null : _tenantCount(count),
          actions: [
            FilledButton.icon(
              onPressed: () => _showOnboardingDialog(context, ref),
              icon: const Icon(Icons.add_business),
              label: const Text('Onboard new tenant'),
            ),
            OutlinedButton.icon(
              onPressed: () => ref.invalidate(allTenantsProvider),
              icon: const Icon(Icons.refresh),
              label: const Text('Refresh'),
            ),
          ],
        ),

        // List
        Expanded(
          child: tenantsAsync.when(
            loading: () => const LoadingView(label: 'Loading tenants…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load tenants.'),
              onRetry: () => ref.invalidate(allTenantsProvider),
            ),
            data: (tenants) => RefreshIndicator.adaptive(
              onRefresh: () => _pullToRefresh(ref),
              // Laid out for the width the page has, not the window's: from
              // tablet width the rail takes some of it.
              child: LayoutBuilder(builder: (context, bc) {
                if (tenants.isEmpty) {
                  return SingleChildScrollView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    child: ConstrainedBox(
                      constraints: BoxConstraints(minHeight: bc.maxHeight),
                      child: EmptyState(
                        icon: Icons.business_outlined,
                        title: 'No tenants yet',
                        message:
                            'Use "Onboard new tenant" to add the first business.',
                        action: OutlinedButton.icon(
                          onPressed: () => _showOnboardingDialog(context, ref),
                          icon: const Icon(Icons.add_business),
                          label: const Text('Onboard new tenant'),
                        ),
                      ),
                    ),
                  );
                }
                final width = AppBreakpoints.classOf(bc.maxWidth);
                if (width >= WindowClass.expanded) {
                  return _TenantTable(
                    tenants: tenants,
                    onToggleStatus: (t) => _toggleStatus(context, ref, t),
                  );
                }
                return _TenantList(
                  tenants: tenants,
                  compact: width == WindowClass.compact,
                  onToggleStatus: (t) => _toggleStatus(context, ref, t),
                );
              }),
            ),
          ),
        ),
      ],
    );
  }

  /// Pull to refresh: the spinner stays until the list has been read again.
  Future<void> _pullToRefresh(WidgetRef ref) async {
    ref.invalidate(allTenantsProvider);
    try {
      await ref.read(allTenantsProvider.future);
    } on Object {
      // A failed read replaces the list with the error view and its retry.
    }
  }

  Future<void> _toggleStatus(
      BuildContext context, WidgetRef ref, PlatformTenant tenant) async {
    final activate = tenant.status.toUpperCase() != 'ACTIVE';
    final newStatus = activate ? 'ACTIVE' : 'INACTIVE';
    final label = activate ? 'Activate' : 'Deactivate';

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('$label "${tenant.name}"?'),
        content: Text(activate
            ? 'The tenant will regain access to the platform.'
            : 'The tenant and all their users will lose access to the platform.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            style: activate
                ? null
                : FilledButton.styleFrom(
                    backgroundColor: Theme.of(ctx).colorScheme.error,
                    foregroundColor: Theme.of(ctx).colorScheme.onError,
                  ),
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(label),
          ),
        ],
      ),
    );

    if (confirmed != true || !context.mounted) return;

    try {
      await ref.read(apiClientProvider).dio.patch(
        '/${ApiConstants.tenant}/platform/tenants/${tenant.id}/status',
        data: {'status': newStatus},
      );
      ref.invalidate(allTenantsProvider);
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(friendlyError(e,
                fallback: 'Failed to ${label.toLowerCase()} tenant.')),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    }
  }

  void _showOnboardingDialog(BuildContext context, WidgetRef ref) {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (_) => _OnboardingDialog(onDone: () => ref.invalidate(allTenantsProvider)),
    );
  }
}

/// *1 tenant*, *4 tenants*.
String _tenantCount(int n) => n == 1 ? '1 tenant' : '$n tenants';

// ── Wide table (expanded and up) ──────────────────────────────────────────────

class _TenantTable extends StatelessWidget {
  final List<PlatformTenant> tenants;
  final void Function(PlatformTenant) onToggleStatus;
  const _TenantTable({required this.tenants, required this.onToggleStatus});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final gutter = context.pageGutter;
    // The page scrolls up and down, so it can be pulled to refresh however
    // short the table is; the table scrolls sideways on its own when its
    // columns need more than the width.
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: EdgeInsetsDirectional.fromSTEB(gutter, 0, gutter, gutter),
      children: [
        Card(
          clipBehavior: Clip.antiAlias,
          child: ScrollableTable(
            child: DataTable(
              headingRowColor:
                  WidgetStatePropertyAll(cs.surfaceContainerHigh),
              columnSpacing: AppSpacing.xl,
              // A row grows with its text (a legal name, large type) rather
              // than clipping it at the default 48.
              dataRowMaxHeight: double.infinity,
              columns: const [
                DataColumn(label: Text('Business')),
                DataColumn(label: Text('Country')),
                DataColumn(label: Text('Currency')),
                DataColumn(label: Text('Status')),
                DataColumn(label: Text('Created')),
                DataColumn(label: Text('')),
              ],
              rows: [
                for (final t in tenants)
                  DataRow(cells: [
                    DataCell(Padding(
                      padding:
                          const EdgeInsets.symmetric(vertical: AppSpacing.sm),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(t.name,
                              style: const TextStyle(
                                  fontWeight: FontWeight.bold)),
                          if ((t.legalName ?? '').isNotEmpty)
                            Text(t.legalName!,
                                style: text.bodySmall
                                    ?.copyWith(color: cs.onSurfaceVariant)),
                        ],
                      ),
                    )),
                    DataCell(Text(t.country)),
                    DataCell(Text(t.currency)),
                    DataCell(_TenantStatus(t)),
                    DataCell(Text(AppFormat.date(t.createdAt))),
                    DataCell(_StatusAction(
                      tenant: t,
                      onPressed: () => onToggleStatus(t),
                    )),
                  ]),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

// ── List (phones and tablets in portrait) ─────────────────────────────────────

class _TenantList extends StatelessWidget {
  final List<PlatformTenant> tenants;

  /// A phone: the status and the action go on a line under the text.
  final bool compact;
  final void Function(PlatformTenant) onToggleStatus;
  const _TenantList({
    required this.tenants,
    required this.compact,
    required this.onToggleStatus,
  });

  @override
  Widget build(BuildContext context) {
    final gutter = context.pageGutter;
    return ListView.separated(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: EdgeInsetsDirectional.fromSTEB(gutter, 0, gutter, gutter),
      itemCount: tenants.length,
      separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.sm),
      itemBuilder: (context, i) {
        final t = tenants[i];
        return _TenantCard(
          tenant: t,
          compact: compact,
          onToggleStatus: () => onToggleStatus(t),
        );
      },
    );
  }
}

class _TenantCard extends StatelessWidget {
  final PlatformTenant tenant;
  final bool compact;
  final VoidCallback onToggleStatus;
  const _TenantCard({
    required this.tenant,
    required this.compact,
    required this.onToggleStatus,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final t = tenant;
    final secondary = text.bodySmall?.copyWith(color: cs.onSurfaceVariant);
    final legal = t.legalName ?? '';
    final created = AppFormat.date(t.createdAt);

    final avatar = CircleAvatar(
      backgroundColor: cs.primaryContainer,
      child: Text(
        t.name.isNotEmpty ? t.name[0].toUpperCase() : '?',
        style: TextStyle(
            fontWeight: FontWeight.bold, color: cs.onPrimaryContainer),
      ),
    );
    final details = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(t.name, style: text.titleMedium),
        if (legal.isNotEmpty) Text(legal, style: secondary),
        Text(
          [t.country, t.currency, if (created.isNotEmpty) 'Created $created']
              .join(' · '),
          style: secondary,
        ),
      ],
    );
    final status = _TenantStatus(t);
    final action = _StatusAction(tenant: t, onPressed: onToggleStatus);

    return Card(
      child: Padding(
        padding: const EdgeInsetsDirectional.fromSTEB(
            AppSpacing.lg, AppSpacing.md, AppSpacing.sm, AppSpacing.sm),
        child: compact
            // The status and the action on a line of their own, lined up
            // under the text, so the name keeps the width of the card. They
            // wrap rather than overflow when the type is large.
            ? Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      avatar,
                      const SizedBox(width: AppSpacing.md),
                      Expanded(child: details),
                    ],
                  ),
                  const SizedBox(height: AppSpacing.xs),
                  Padding(
                    padding: const EdgeInsetsDirectional.only(
                        start: 40 + AppSpacing.md),
                    child: Wrap(
                      alignment: WrapAlignment.spaceBetween,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      spacing: AppSpacing.sm,
                      children: [status, action],
                    ),
                  ),
                ],
              )
            : Row(
                children: [
                  avatar,
                  const SizedBox(width: AppSpacing.md),
                  Expanded(child: details),
                  const SizedBox(width: AppSpacing.md),
                  status,
                  const SizedBox(width: AppSpacing.sm),
                  action,
                ],
              ),
      ),
    );
  }
}

/// A tenant's status in words, in its tone: *Active* success, *Pending* info,
/// *Suspended* warning, *Deactivated* and *Closed* neutral, anything failed
/// error. The server sends ACTIVE, INACTIVE or PENDING; a state this map does
/// not know still reads as words, never as the code.
class _TenantStatusBadge extends StatelessWidget {
  final String status;
  const _TenantStatusBadge(this.status);

  @override
  Widget build(BuildContext context) {
    final code = status.toUpperCase();
    final (label, tone) = switch (code) {
      'ACTIVE' => ('Active', StatusTone.success),
      'PENDING' => ('Pending', StatusTone.info),
      'SUSPENDED' => ('Suspended', StatusTone.warning),
      'INACTIVE' || 'DEACTIVATED' => ('Deactivated', StatusTone.neutral),
      'CLOSED' => ('Closed', StatusTone.neutral),
      _ when code.contains('FAIL') => (humanizeCode(status), StatusTone.error),
      _ => (humanizeCode(status), StatusTone.neutral),
    };
    return StatusBadge(label, tone: tone);
  }
}

/// A tenant's status and, for a business's sandbox (22.8) — a tenant of its
/// own — a *Sandbox* badge beside it, so the platform never mistakes it for a
/// customer. The two wrap rather than overflow when the type is large.
class _TenantStatus extends StatelessWidget {
  final PlatformTenant tenant;
  const _TenantStatus(this.tenant);

  @override
  Widget build(BuildContext context) {
    final status = _TenantStatusBadge(tenant.status);
    if (!tenant.sandbox) return status;
    return Wrap(
      spacing: AppSpacing.xs,
      runSpacing: AppSpacing.xs,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        status,
        const StatusBadge(
          'Sandbox',
          key: Key('sandbox-chip'),
          tone: StatusTone.accent,
          icon: Icons.science_outlined,
        ),
      ],
    );
  }
}

/// A row's one action — switch the business off, or back on — in words rather
/// than behind a menu of one. The confirmation dialog still comes first.
class _StatusAction extends StatelessWidget {
  final PlatformTenant tenant;
  final VoidCallback onPressed;
  const _StatusAction({required this.tenant, required this.onPressed});

  @override
  Widget build(BuildContext context) {
    final active = tenant.status.toUpperCase() == 'ACTIVE';
    return TextButton(
      style: active
          ? TextButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error)
          : null,
      onPressed: onPressed,
      child: Text(active ? 'Deactivate' : 'Activate'),
    );
  }
}

// ── 2-step onboarding dialog ──────────────────────────────────────────────────

class _OnboardingDialog extends ConsumerStatefulWidget {
  final VoidCallback onDone;
  const _OnboardingDialog({required this.onDone});

  @override
  ConsumerState<_OnboardingDialog> createState() => _OnboardingDialogState();
}

class _OnboardingDialogState extends ConsumerState<_OnboardingDialog> {
  final _emailCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  bool _obscure = true;
  final _step0Key = GlobalKey<FormState>();

  final _bizNameCtrl = TextEditingController();
  final _storeNameCtrl = TextEditingController();
  final _storeCodeCtrl = TextEditingController();
  final _cityCtrl = TextEditingController();
  // Nothing preselected: a new business's country, currency and zone are its
  // own to choose (SJ-D53). Choosing a country suggests its currency.
  String? _country;
  String? _currency;
  String _storeType = 'STORE';
  String? _timezone;
  final _step1Key = GlobalKey<FormState>();

  @override
  void dispose() {
    _emailCtrl.dispose();
    _passwordCtrl.dispose();
    _bizNameCtrl.dispose();
    _storeNameCtrl.dispose();
    _storeCodeCtrl.dispose();
    _cityCtrl.dispose();
    super.dispose();
  }

  void _close() {
    ref.read(tenantOnboardingProvider.notifier).reset();
    Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final ob = ref.watch(tenantOnboardingProvider);
    final cs = Theme.of(context).colorScheme;

    // Refresh list when done
    ref.listen<TenantOnboardingState>(tenantOnboardingProvider, (_, next) {
      if (next.isDone) widget.onDone();
    });

    return Dialog(
      shape: const RoundedRectangleBorder(borderRadius: AppRadius.card),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: ob.isDone
              ? _DoneView(
                  tenantName: ob.tenantName ?? '',
                  ownerEmail: ob.ownerEmail ?? '',
                  onClose: _close,
                )
              : Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        Icon(Icons.add_business, color: cs.primary),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            ob.step == 0
                                ? 'Step 1 of 2 — Owner account'
                                : 'Step 2 of 2 — Business & first store',
                            style: Theme.of(context)
                                .textTheme
                                .titleLarge
                                ?.copyWith(fontWeight: FontWeight.bold),
                          ),
                        ),
                        IconButton(
                          icon: const Icon(Icons.close),
                          tooltip: 'Close',
                          onPressed: ob.loading ? null : _close,
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    LinearProgressIndicator(
                      value: ob.step == 0 ? 0.5 : 1.0,
                      borderRadius: AppRadius.badge,
                    ),
                    const SizedBox(height: 16),
                    if (ob.error != null) ...[
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: cs.errorContainer,
                          borderRadius: AppRadius.chip,
                        ),
                        child: Text(ob.error!,
                            style: TextStyle(color: cs.onErrorContainer)),
                      ),
                      const SizedBox(height: 12),
                    ],
                    if (ob.step == 0)
                      _AccountForm(
                        formKey: _step0Key,
                        emailCtrl: _emailCtrl,
                        passwordCtrl: _passwordCtrl,
                        obscure: _obscure,
                        onToggleObscure: () =>
                            setState(() => _obscure = !_obscure),
                        loading: ob.loading,
                        onNext: () {
                          if (!_step0Key.currentState!.validate()) return;
                          ref
                              .read(tenantOnboardingProvider.notifier)
                              .registerOwner(
                                email: _emailCtrl.text.trim(),
                                password: _passwordCtrl.text,
                              );
                        },
                      )
                    else
                      _BusinessStoreForm(
                        formKey: _step1Key,
                        bizNameCtrl: _bizNameCtrl,
                        storeNameCtrl: _storeNameCtrl,
                        storeCodeCtrl: _storeCodeCtrl,
                        cityCtrl: _cityCtrl,
                        country: _country,
                        currency: _currency,
                        storeType: _storeType,
                        timezone: _timezone,
                        onCountryChanged: (v) => setState(() {
                          _country = v;
                          _currency = currencyOfCountry(v) ?? _currency;
                        }),
                        onCurrencyChanged: (v) =>
                            setState(() => _currency = v!),
                        onTypeChanged: (v) =>
                            setState(() => _storeType = v!),
                        onTimezoneChanged: (v) =>
                            setState(() => _timezone = v!),
                        loading: ob.loading,
                        onSubmit: () {
                          if (!_step1Key.currentState!.validate()) return;
                          ref
                              .read(tenantOnboardingProvider.notifier)
                              .onboard(
                                businessName: _bizNameCtrl.text.trim(),
                                country: _country!,
                                currency: _currency!,
                                storeName: _storeNameCtrl.text.trim(),
                                storeCode: _storeCodeCtrl.text
                                    .trim()
                                    .toUpperCase(),
                                storeType: _storeType,
                                storeCity: _cityCtrl.text.trim(),
                                storeCountry: _country,
                                storeTimezone: _timezone!,
                              );
                        },
                      ),
                  ],
                ),
        ),
      ),
    );
  }
}

// ── Form widgets (reused from previous version) ───────────────────────────────

class _AccountForm extends StatelessWidget {
  final GlobalKey<FormState> formKey;
  final TextEditingController emailCtrl;
  final TextEditingController passwordCtrl;
  final bool obscure;
  final VoidCallback onToggleObscure;
  final bool loading;
  final VoidCallback onNext;

  const _AccountForm({
    required this.formKey,
    required this.emailCtrl,
    required this.passwordCtrl,
    required this.obscure,
    required this.onToggleObscure,
    required this.loading,
    required this.onNext,
  });

  @override
  Widget build(BuildContext context) {
    return Form(
      key: formKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Create the owner login credentials.',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: Theme.of(context).colorScheme.outline)),
          const SizedBox(height: 16),
          TextFormField(
            controller: emailCtrl,
            keyboardType: TextInputType.emailAddress,
            textInputAction: TextInputAction.next,
            decoration: const InputDecoration(
              labelText: 'Owner email *',
              prefixIcon: Icon(Icons.email_outlined),
            ),
            validator: (v) =>
                v == null || !v.contains('@') ? 'Enter a valid email' : null,
          ),
          const SizedBox(height: 12),
          TextFormField(
            controller: passwordCtrl,
            obscureText: obscure,
            textInputAction: TextInputAction.done,
            decoration: InputDecoration(
              labelText: 'Password *',
              prefixIcon: const Icon(Icons.lock_outline),
              suffixIcon: IconButton(
                icon:
                    Icon(obscure ? Icons.visibility_off : Icons.visibility),
                tooltip: obscure ? 'Show password' : 'Hide password',
                onPressed: onToggleObscure,
              ),
            ),
            validator: (v) =>
                v == null || v.length < 15 ? 'At least 15 characters — a phrase of a few words is easiest' : null,
          ),
          const SizedBox(height: 20),
          FilledButton(
            onPressed: loading ? null : onNext,
            child: loading
                ?  SizedBox(
                    height: 18,
                    width: 18,
                    child: CircularProgressIndicator(
                        strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                : const Text('Next  →'),
          ),
        ],
      ),
    );
  }
}

class _BusinessStoreForm extends StatelessWidget {
  final GlobalKey<FormState> formKey;
  final TextEditingController bizNameCtrl;
  final TextEditingController storeNameCtrl;
  final TextEditingController storeCodeCtrl;
  final TextEditingController cityCtrl;
  final String? country;
  final String? currency;
  final String storeType;
  final String? timezone;
  final ValueChanged<String?> onCountryChanged;
  final ValueChanged<String?> onCurrencyChanged;
  final ValueChanged<String?> onTypeChanged;
  final ValueChanged<String?> onTimezoneChanged;
  final bool loading;
  final VoidCallback onSubmit;

  const _BusinessStoreForm({
    required this.formKey,
    required this.bizNameCtrl,
    required this.storeNameCtrl,
    required this.storeCodeCtrl,
    required this.cityCtrl,
    required this.country,
    required this.currency,
    required this.storeType,
    required this.timezone,
    required this.onCountryChanged,
    required this.onCurrencyChanged,
    required this.onTypeChanged,
    required this.onTimezoneChanged,
    required this.loading,
    required this.onSubmit,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Form(
      key: formKey,
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const _SectionLabel(label: 'Business', icon: Icons.business_outlined),
            const SizedBox(height: 10),
            TextFormField(
              controller: bizNameCtrl,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(
                labelText: 'Business name *',
                hintText: 'e.g. Green Valley Supermarket',
                prefixIcon: Icon(Icons.business),
              ),
              validator: (v) =>
                  v == null || v.trim().isEmpty ? 'Required' : null,
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: CountryField(
                    label: 'Country *',
                    value: country,
                    onChanged: onCountryChanged,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: CurrencyField(
                    label: 'Currency *',
                    value: currency,
                    onChanged: onCurrencyChanged,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            Divider(color: cs.outlineVariant),
            const SizedBox(height: 8),
            const _SectionLabel(label: 'First Store', icon: Icons.store_outlined),
            const SizedBox(height: 10),
            TextFormField(
              controller: storeNameCtrl,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(
                labelText: 'Store name *',
                hintText: 'e.g. Main Street Branch',
                prefixIcon: Icon(Icons.store),
              ),
              validator: (v) =>
                  v == null || v.trim().isEmpty ? 'Required' : null,
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextFormField(
                    controller: storeCodeCtrl,
                    textCapitalization: TextCapitalization.characters,
                    textInputAction: TextInputAction.next,
                    decoration: const InputDecoration(
                      labelText: 'Store code *',
                      hintText: 'STR-001',
                      prefixIcon: Icon(Icons.tag),
                    ),
                    validator: (v) =>
                        v == null || v.trim().isEmpty ? 'Required' : null,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: DropdownButtonFormField<String>(
                    initialValue: storeType,
                    decoration: const InputDecoration(labelText: 'Type'),
                    items: const [
                      DropdownMenuItem(
                          value: 'STORE', child: Text('Retail Store')),
                      DropdownMenuItem(
                          value: 'WAREHOUSE', child: Text('Warehouse')),
                    ],
                    onChanged: onTypeChanged,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextFormField(
                    controller: cityCtrl,
                    textInputAction: TextInputAction.next,
                    decoration: const InputDecoration(labelText: 'City'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TimezoneField(
                    label: 'Timezone *',
                    value: timezone,
                    onChanged: onTimezoneChanged,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            FilledButton(
              onPressed: loading ? null : onSubmit,
              child: loading
                  ?  SizedBox(
                      height: 18,
                      width: 18,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                  : const Text('Create tenant & store'),
            ),
          ],
        ),
      ),
    );
  }
}

class _SectionLabel extends StatelessWidget {
  final String label;
  final IconData icon;
  const _SectionLabel({required this.label, required this.icon});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Row(
      children: [
        Icon(icon, size: 16, color: cs.primary),
        const SizedBox(width: 6),
        Text(label,
            style: TextStyle(
                fontWeight: FontWeight.bold,
                color: cs.primary,
                fontSize: 13)),
      ],
    );
  }
}

class _DoneView extends StatelessWidget {
  final String tenantName;
  final String ownerEmail;
  final VoidCallback onClose;

  const _DoneView({
    required this.tenantName,
    required this.ownerEmail,
    required this.onClose,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const SizedBox(height: 8),
        Container(
          width: 64,
          height: 64,
          decoration: BoxDecoration(
              color: cs.primaryContainer, shape: BoxShape.circle),
          child: Icon(Icons.check_circle_outline,
              size: 36, color: cs.onPrimaryContainer),
        ),
        const SizedBox(height: 16),
        Text('"$tenantName" is live!',
            style: Theme.of(context)
                .textTheme
                .titleLarge
                ?.copyWith(fontWeight: FontWeight.bold),
            textAlign: TextAlign.center),
        const SizedBox(height: 8),
        Text('Tenant and first store created. The list has been refreshed.',
            style: Theme.of(context)
                .textTheme
                .bodyMedium
                ?.copyWith(color: cs.outline),
            textAlign: TextAlign.center),
        const SizedBox(height: 20),
        Card(
          color: cs.surfaceContainerHigh,
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Share with the owner:',
                    style: TextStyle(fontWeight: FontWeight.bold)),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Icon(Icons.email_outlined, size: 16, color: cs.outline),
                    const SizedBox(width: 6),
                    Text(ownerEmail,
                        style: const TextStyle(fontFamily: 'monospace')),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  'They can log in at the app login screen with these credentials.',
                  style: TextStyle(fontSize: 12, color: cs.outline),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 20),
        FilledButton(onPressed: onClose, child: const Text('Done')),
      ],
    );
  }
}
