import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/format.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'accounting_section.dart';
import 'api_keys_api.dart';
import 'providers/admin_providers.dart';
import 'sandbox_api.dart';
import 'webhooks_api.dart';
import '../../core/theme.dart';
import '../../shared/widgets/empty_state.dart';

// ---------------------------------------------------------------------------
// Integrations (22.7): the keys a business's own systems present instead of a
// person's sign-in. An owner mints one — a name, a staff tier, the stores it
// may work in, a day it stops — and is shown the key once, with a copy button
// and a warning that it will not be shown again; the list says what each key
// is, when it was last used and whether it still works; an owner revokes one
// and it stops within seconds. A manager reads the list and changes nothing.
// ---------------------------------------------------------------------------

class IntegrationsScreen extends ConsumerWidget {
  const IntegrationsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final owner = auth is AuthAuthenticated && auth.roles.contains('OWNER');
    final inSandbox = auth is AuthAuthenticated && auth.sandbox;
    final keys = ref.watch(apiKeysProvider);

    // The page's gutter, and a measure its paragraphs can be read at on a
    // desktop (they ran edge to edge on a wide window).
    return Scaffold(
      body: ListView(
        padding: context.pagePadding,
        children: [
          ContentBounds(
            maxWidth: AppBreakpoints.expanded,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
          const PageHeader(
            title: 'Integrations',
            subtitle: 'API keys let your own systems — an ERP, an accounting package, an integrator — '
                'use the platform without a person signing in. A key acts as staff, never as an owner.',
            padding: EdgeInsets.zero,
          ),
          const SizedBox(height: 24),
          _SandboxSection(owner: owner, inSandbox: inSandbox),
          const SizedBox(height: 32),
          SectionHeading(
            title: 'API keys',
            actions: [
              if (owner)
                FilledButton.icon(
                  key: const Key('mint-key'),
                  onPressed: () => _mint(context, ref),
                  icon: const Icon(Icons.vpn_key_outlined),
                  label: const Text('Mint a key'),
                ),
            ],
          ),
          const SizedBox(height: 12),
          keys.when(
            loading: () => const LoadingView(label: 'Loading keys…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load the keys.'),
              onRetry: () => ref.invalidate(apiKeysProvider),
            ),
            data: (list) => list.isEmpty
                ? const Padding(
                    key: Key('keys-none'),
                    padding: EdgeInsets.symmetric(vertical: 24),
                    child: Text('No keys yet. Mint one for each system that needs its own way in.'),
                  )
                : Column(
                    children: [
                      for (final k in list) _KeyTile(k: k, owner: owner, onRevoke: () => _revoke(context, ref, k)),
                    ],
                  ),
          ),
          const SizedBox(height: 32),
          _WebhooksSection(owner: owner),
          const SizedBox(height: 32),
          AccountingSection(owner: owner),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _mint(BuildContext context, WidgetRef ref) async {
    final minted = await showDialog<MintedApiKey>(
      context: context,
      builder: (_) => const MintKeyDialog(),
    );
    if (minted == null || !context.mounted) return;
    ref.invalidate(apiKeysProvider);
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => SecretShownOnceDialog(
        title: '${minted.key.name} is ready',
        intro: 'Copy the key into the system that will use it. It is shown once and cannot be '
            'shown again; if it is lost, revoke it and mint another.',
        secret: minted.secret,
        hint: 'Send it as  Authorization: Bearer ${minted.key.prefix}…',
      ),
    );
  }

  Future<void> _revoke(BuildContext context, WidgetRef ref, ApiKey k) async {
    final sure = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Revoke ${k.name}?'),
        content: const Text('The system using this key stops within seconds. This cannot be undone; mint a new key if it should carry on.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Keep it')),
          FilledButton(key: const Key('revoke-confirm'), onPressed: () => Navigator.pop(ctx, true), child: const Text('Revoke')),
        ],
      ),
    );
    if (sure != true || !context.mounted) return;
    try {
      await ref.read(apiKeysApiProvider).revoke(k.id);
      ref.invalidate(apiKeysProvider);
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${k.name} revoked')));
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(friendlyError(e, fallback: 'The key could not be revoked.'))),
        );
      }
    }
  }
}

/// A camel-case name from the wire in words: `OrderPlaced` → *Order placed*,
/// `StockBelowThreshold` → *Stock below threshold*, `tenantId` → *Tenant ID*.
/// The fallback for a name no screen map knows yet.
String codeInWords(String code) =>
    humanizeCode(code.replaceAllMapped(RegExp(r'([a-z0-9])([A-Z])'), (m) => '${m[1]}_${m[2]}'));

/// Whether a card's content goes under its icon and its actions under the
/// text or into one ⋮: below 600px, or below 840px with large text.
bool _narrow(BuildContext context, double width) =>
    width < AppBreakpoints.medium ||
    (MediaQuery.textScalerOf(context).scale(16) > 16 * 1.3 && width < AppBreakpoints.expanded);

class _KeyTile extends StatelessWidget {
  final ApiKey k;
  final bool owner;
  final VoidCallback onRevoke;
  const _KeyTile({required this.k, required this.owner, required this.onRevoke});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final active = k.status == 'Active';
    final where = k.storeIds.isEmpty ? 'every store' : '${k.storeIds.length} ${k.storeIds.length == 1 ? 'store' : 'stores'}';
    final used = k.lastUsedAt == null ? 'never used' : 'last used ${AppFormat.date(k.lastUsedAt)}';
    final until = k.expiresAt == null ? '' : ' · until ${AppFormat.date(k.expiresAt)}';
    return Card(
      key: Key('key-${k.id}'),
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: Icon(k.sandbox ? Icons.science_outlined : Icons.vpn_key_outlined, color: active ? cs.primary : cs.outline),
        title: Text(k.name),
        // Its state as badges under the details, and one action at the end,
        // so the key's name keeps the width on a phone.
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('${k.prefix}… · ${humanizeCode(k.role)} · $where · $used$until'),
            const SizedBox(height: AppSpacing.xs),
            Wrap(
              spacing: AppSpacing.sm,
              runSpacing: AppSpacing.xs,
              children: [
                StatusBadge(
                  humanizeCode(k.status),
                  tone: active ? StatusTone.success : StatusTone.neutral,
                ),
                if (k.sandbox)
                  StatusBadge(
                    'Sandbox',
                    key: Key('sandbox-key-${k.id}'),
                    tone: StatusTone.accent,
                  ),
              ],
            ),
          ],
        ),
        trailing: owner && active
            ? IconButton(
                key: Key('revoke-${k.id}'),
                tooltip: 'Revoke',
                icon: const Icon(Icons.delete_outline),
                onPressed: onRevoke,
              )
            : null,
      ),
    );
  }
}

/// A section's heading on the Integrations page, with its actions: one row
/// where there is room, and — on a phone, or with large text — the actions
/// wrap under the title rather than running off the screen.
class SectionHeading extends StatelessWidget {
  const SectionHeading({super.key, required this.title, this.actions = const []});

  final String title;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold);
    return LayoutBuilder(builder: (context, constraints) {
      final largeText = MediaQuery.textScalerOf(context).scale(16) > 16 * 1.3;
      final stacked = constraints.maxWidth < PageHeader.defaultStackBelow ||
          (largeText && constraints.maxWidth < AppBreakpoints.expanded);
      final heading = Text(title, style: style);
      if (actions.isEmpty) return heading;
      if (stacked) {
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            heading,
            const SizedBox(height: AppSpacing.sm),
            Wrap(spacing: AppSpacing.sm, runSpacing: AppSpacing.sm, children: actions),
          ],
        );
      }
      return Row(
        children: [
          Expanded(child: heading),
          const SizedBox(width: AppSpacing.md),
          Wrap(spacing: AppSpacing.sm, runSpacing: AppSpacing.sm, children: actions),
        ],
      );
    });
  }
}

/// The form that mints a key: a name, a tier, the stores, an optional last day.
class MintKeyDialog extends ConsumerStatefulWidget {
  const MintKeyDialog({super.key});

  @override
  ConsumerState<MintKeyDialog> createState() => _MintKeyDialogState();
}

class _MintKeyDialogState extends ConsumerState<MintKeyDialog> {
  final _formKey = GlobalKey<FormState>();
  final _name = TextEditingController();
  String _role = 'STOREKEEPER';
  final Set<String> _stores = {};
  DateTime? _expiresAt;
  bool _sandbox = false;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final minted = await ref.read(apiKeysApiProvider).mint(
            name: _name.text.trim(),
            role: _role,
            storeIds: _stores.toList(),
            expiresAt: _expiresAt,
            sandbox: _sandbox,
          );
      if (mounted) Navigator.pop(context, minted);
    } catch (e) {
      setState(() {
        _busy = false;
        _error = friendlyError(e, fallback: 'The key could not be minted.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final stores = ref.watch(storesProvider);
    final auth = ref.watch(authNotifierProvider).value;
    final inSandbox = auth is AuthAuthenticated && auth.sandbox;
    final sandbox = inSandbox ? null : ref.watch(sandboxProvider).value;
    return AlertDialog(
      title: const Text('Mint an API key'),
      content: SizedBox(
        width: 460,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TextFormField(
                  key: const Key('key-name'),
                  controller: _name,
                  decoration: const InputDecoration(labelText: 'Name *', hintText: 'e.g. Warehouse ERP'),
                  maxLength: 80,
                  validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
                ),
                const SizedBox(height: 8),
                DropdownButtonFormField<String>(
                  key: const Key('key-role'),
                  initialValue: _role,
                  decoration: const InputDecoration(labelText: 'Acts as *', helperText: 'A key is never an owner.'),
                  items: const [
                    DropdownMenuItem(value: 'MANAGER', child: Text('Manager')),
                    DropdownMenuItem(value: 'STOREKEEPER', child: Text('Storekeeper')),
                    DropdownMenuItem(value: 'CASHIER', child: Text('Cashier')),
                  ],
                  onChanged: (v) => setState(() => _role = v ?? _role),
                ),
                const SizedBox(height: 16),
                Text('Stores', style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: 4),
                stores.when(
                  loading: () => const Text('Loading stores…'),
                  error: (_, _) => const Text('Stores could not be loaded; the key works in every store.'),
                  data: (list) => Wrap(
                    spacing: 8,
                    runSpacing: 4,
                    children: [
                      FilterChip(
                        key: const Key('store-all'),
                        label: const Text('Every store'),
                        selected: _stores.isEmpty,
                        onSelected: (_) => setState(_stores.clear),
                      ),
                      for (final s in list)
                        FilterChip(
                          key: Key('store-${s.id}'),
                          label: Text(s.name),
                          selected: _stores.contains(s.id),
                          onSelected: (on) => setState(() => on ? _stores.add(s.id) : _stores.remove(s.id)),
                        ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        _expiresAt == null ? 'Works until revoked' : 'Stops on ${AppFormat.date(_expiresAt!.toIso8601String())}',
                      ),
                    ),
                    TextButton(
                      key: const Key('key-expiry'),
                      onPressed: () async {
                        final now = DateTime.now();
                        final picked = await showDatePicker(
                          context: context,
                          initialDate: now.add(const Duration(days: 90)),
                          firstDate: now.add(const Duration(days: 1)),
                          lastDate: now.add(const Duration(days: 365 * 5)),
                        );
                        if (picked != null) setState(() => _expiresAt = DateTime(picked.year, picked.month, picked.day, 23, 59, 59));
                      },
                      child: Text(_expiresAt == null ? 'Set a last day' : 'Change'),
                    ),
                    if (_expiresAt != null)
                      IconButton(
                        tooltip: 'No last day',
                        icon: const Icon(Icons.close),
                        onPressed: () => setState(() => _expiresAt = null),
                      ),
                  ],
                ),
                if (inSandbox) ...[
                  const SizedBox(height: 12),
                  Text(
                    'You are in the sandbox: this key acts there and reaches nothing real.',
                    key: const Key('key-sandbox-note'),
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ] else if (sandbox != null && sandbox.active) ...[
                  const SizedBox(height: 8),
                  SwitchListTile.adaptive(
                    key: const Key('key-sandbox'),
                    contentPadding: EdgeInsets.zero,
                    title: const Text('Sandbox key'),
                    subtitle: const Text('Acts in the sandbox only, never on live data; starts sqk_test_.'),
                    value: _sandbox,
                    onChanged: (v) => setState(() => _sandbox = v),
                  ),
                ],
                if (_error != null) ...[
                  const SizedBox(height: 8),
                  Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ],
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('mint-submit'),
          onPressed: _busy ? null : _submit,
          child: _busy
              ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Mint'),
        ),
      ],
    );
  }
}

/// The business's sandbox (22.8): whether there is one, and — for the owner —
/// making it, opening it and removing it. Inside the sandbox, the way back.
class _SandboxSection extends ConsumerWidget {
  final bool owner;
  final bool inSandbox;
  const _SandboxSection({required this.owner, required this.inSandbox});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    if (inSandbox) {
      // Beside the icon where there is room; on a phone, or with large text,
      // the sentence goes under the icon at the card's width and the way back
      // under the sentence, rather than a column a third of the card wide.
      return Card(
        key: const Key('sandbox-inside'),
        color: cs.tertiaryContainer,
        child: Padding(
          padding: const EdgeInsetsDirectional.all(AppSpacing.lg),
          child: LayoutBuilder(builder: (context, constraints) {
            final icon = Icon(Icons.science_outlined, color: cs.onTertiaryContainer);
            final notice = Text(
              'You are in the sandbox. Everything here — products, stock, orders, keys, webhooks — is a rehearsal: '
              'no message leaves it, no money moves, nothing is billed. Keys minted here start sqk_test_.',
              style: TextStyle(color: cs.onTertiaryContainer),
            );
            final back = FilledButton.tonal(
              key: const Key('sandbox-leave'),
              onPressed: () => ref.read(authNotifierProvider.notifier).leaveSandbox(),
              child: const Text('Back to live'),
            );
            if (_narrow(context, constraints.maxWidth)) {
              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  icon,
                  const SizedBox(height: AppSpacing.sm),
                  notice,
                  const SizedBox(height: AppSpacing.md),
                  back,
                ],
              );
            }
            return Row(
              children: [
                icon,
                const SizedBox(width: AppSpacing.md),
                Expanded(child: notice),
                const SizedBox(width: AppSpacing.md),
                back,
              ],
            );
          }),
        ),
      );
    }
    final sandbox = ref.watch(sandboxProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SectionHeading(
          title: 'Sandbox',
          actions: [
            if (owner)
              sandbox.when(
                loading: () => const SizedBox.shrink(),
                error: (_, _) => const SizedBox.shrink(),
                data: (s) => s == null
                    ? FilledButton.icon(
                        key: const Key('sandbox-create'),
                        onPressed: () => _create(context, ref),
                        icon: const Icon(Icons.science_outlined),
                        label: const Text('Create a sandbox'),
                      )
                    : Wrap(
                        spacing: AppSpacing.sm,
                        runSpacing: AppSpacing.sm,
                        children: [
                          TextButton(
                            key: const Key('sandbox-delete'),
                            onPressed: () => _remove(context, ref, s),
                            child: const Text('Remove the sandbox'),
                          ),
                          FilledButton.icon(
                            key: const Key('sandbox-enter'),
                            onPressed: () => _enter(context, ref),
                            icon: const Icon(Icons.login),
                            label: const Text('Open the sandbox'),
                          ),
                        ],
                      ),
              ),
          ],
        ),
        const SizedBox(height: 4),
        Text(
          'A copy of the business where your systems can be tried against nothing real: '
          'no message leaves it, no money moves, nothing is billed. One at a time; remove it and make another to start clean.',
          style: theme.textTheme.bodyMedium,
        ),
        const SizedBox(height: 12),
        sandbox.when(
          loading: () => const LoadingView(label: 'Loading the sandbox…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the sandbox.'),
            onRetry: () => ref.invalidate(sandboxProvider),
          ),
          data: (s) => s == null
              ? const Padding(
                  key: Key('sandbox-none'),
                  padding: EdgeInsets.symmetric(vertical: 12),
                  child: Text('No sandbox yet. Create one before pointing a new integration at the live business.'),
                )
              : Card(
                  key: const Key('sandbox-card'),
                  child: ListTile(
                    leading: Icon(Icons.science_outlined, color: cs.primary),
                    title: Text(s.name),
                    subtitle: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('Sandbox plan · made ${AppFormat.date(s.createdAt)}'),
                        const SizedBox(height: AppSpacing.xs),
                        StatusBadge(
                          s.active ? 'Active' : humanizeCode(s.status),
                          tone: s.active ? StatusTone.success : StatusTone.neutral,
                        ),
                      ],
                    ),
                  ),
                ),
        ),
      ],
    );
  }

  Future<void> _create(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      final made = await ref.read(sandboxApiProvider).create();
      ref.invalidate(sandboxProvider);
      messenger.showSnackBar(SnackBar(content: Text('${made.name} is ready')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: 'The sandbox could not be made.'))));
    }
  }

  Future<void> _enter(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(authNotifierProvider.notifier).enterSandbox();
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: 'The sandbox could not be opened.'))));
    }
  }

  Future<void> _remove(BuildContext context, WidgetRef ref, Sandbox s) async {
    final sure = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Remove ${s.name}?'),
        content: const Text(
            'Everything in it is erased and its keys stop within seconds. Make a new one whenever you need a clean start.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Keep it')),
          FilledButton(key: const Key('sandbox-delete-confirm'), onPressed: () => Navigator.pop(ctx, true), child: const Text('Remove')),
        ],
      ),
    );
    if (sure != true || !context.mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(sandboxApiProvider).remove();
      ref.invalidate(sandboxProvider);
      ref.invalidate(apiKeysProvider);
      messenger.showSnackBar(const SnackBar(content: Text('Sandbox removed')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: 'The sandbox could not be removed.'))));
    }
  }
}

/// A secret, the one time it is seen: an API key, or a webhook's signing secret.
class SecretShownOnceDialog extends StatelessWidget {
  final String title;
  final String intro;
  final String secret;
  final String? hint;
  const SecretShownOnceDialog({
    super.key,
    required this.title,
    required this.intro,
    required this.secret,
    this.hint,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text(title),
      content: SizedBox(
        width: 460,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(intro),
            const SizedBox(height: 12),
            Container(
              key: const Key('key-secret'),
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(color: cs.surfaceContainerHighest, borderRadius: AppRadius.chip),
              child: SelectableText(secret, style: const TextStyle(fontFamily: 'monospace')),
            ),
            if (hint != null) ...[
              const SizedBox(height: 8),
              Text(hint!, style: Theme.of(context).textTheme.bodySmall),
            ],
          ],
        ),
      ),
      actions: [
        TextButton.icon(
          key: const Key('key-copy'),
          onPressed: () async {
            await Clipboard.setData(ClipboardData(text: secret));
            if (context.mounted) {
              ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Copied')));
            }
          },
          icon: const Icon(Icons.copy),
          label: const Text('Copy'),
        ),
        FilledButton(key: const Key('key-done'), onPressed: () => Navigator.pop(context), child: const Text('I have copied it')),
      ],
    );
  }
}

// ── Webhooks (22.6) ────────────────────────────────────────────────────────────

/// The endpoints a business's own systems are told at: registered by the
/// owner for the events it wants, each with a secret shown once; pinged,
/// switched off and on, rotated, removed; and the log of what was sent.
class _WebhooksSection extends ConsumerWidget {
  final bool owner;
  const _WebhooksSection({required this.owner});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final endpoints = ref.watch(webhookEndpointsProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SectionHeading(
          title: 'Webhooks',
          actions: [
            if (owner)
              FilledButton.icon(
                key: const Key('add-webhook'),
                onPressed: () => _add(context, ref),
                icon: const Icon(Icons.webhook_outlined),
                label: const Text('Add endpoint'),
              ),
          ],
        ),
        const SizedBox(height: 4),
        Text(
          'Your systems are told the moment something happens — an order placed, stock booked in, a price changed — '
          'by a signed request to an address you give. HTTPS on a public address, answering 2xx within ten seconds.',
          style: theme.textTheme.bodyMedium,
        ),
        const SizedBox(height: 12),
        endpoints.when(
          loading: () => const LoadingView(label: 'Loading endpoints…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the endpoints.'),
            onRetry: () => ref.invalidate(webhookEndpointsProvider),
          ),
          data: (list) => list.isEmpty
              ? const Padding(
                  key: Key('webhooks-none'),
                  padding: EdgeInsets.symmetric(vertical: 24),
                  child: Text('No endpoints yet. Add one for each system that should hear from the shop.'),
                )
              : Column(children: [for (final e in list) _EndpointTile(e: e, owner: owner)]),
        ),
      ],
    );
  }

  Future<void> _add(BuildContext context, WidgetRef ref) async {
    final made = await showDialog<RegisteredWebhook>(
      context: context,
      builder: (_) => const AddWebhookDialog(),
    );
    if (made == null || !context.mounted) return;
    ref.invalidate(webhookEndpointsProvider);
    await showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (_) => SecretShownOnceDialog(
        title: '${made.endpoint.description} is registered',
        intro: 'Put this signing secret in the system that receives the requests. It is shown once and '
            'cannot be shown again; if it is lost, rotate it.',
        secret: made.secret,
        hint: 'Signed to the Standard Webhooks spec: webhook-id, webhook-timestamp and webhook-signature (v1, HMAC-SHA256 over "id.timestamp.body").',
      ),
    );
  }
}

class _EndpointTile extends ConsumerWidget {
  final WebhookEndpoint e;
  final bool owner;
  const _EndpointTile({required this.e, required this.owner});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final api = ref.read(webhooksApiProvider);
    final last = e.lastDeliveredAt == null ? 'never delivered' : 'last delivered ${AppFormat.date(e.lastDeliveredAt)}';
    // What the owner may do, and what anyone may: the same five whether they
    // show as buttons or in the phone's one menu.
    final actions = <({Key key, String label, IconData icon, VoidCallback onPressed})>[
      (
        key: Key('ping-${e.id}'),
        label: 'Ping',
        icon: Icons.send_outlined,
        onPressed: () => _run(context, ref, () async {
              await api.ping(e.id);
              return 'Test delivery queued';
            }),
      ),
      (
        key: Key('deliveries-${e.id}'),
        label: 'Deliveries',
        icon: Icons.receipt_long_outlined,
        onPressed: () => showDialog<void>(context: context, builder: (_) => _DeliveriesDialog(endpoint: e)),
      ),
      if (owner) ...[
        (
          key: Key('toggle-${e.id}'),
          label: e.enabled ? 'Switch off' : 'Switch on',
          icon: e.enabled ? Icons.pause_circle_outline : Icons.play_circle_outline,
          onPressed: () => _run(context, ref, () async {
                await api.setEnabled(e.id, !e.enabled);
                return e.enabled ? 'Switched off' : 'Switched on';
              }),
        ),
        (key: Key('rotate-${e.id}'), label: 'Rotate secret', icon: Icons.key_outlined, onPressed: () => _rotate(context, ref)),
        (key: Key('remove-${e.id}'), label: 'Remove', icon: Icons.delete_outline, onPressed: () => _remove(context, ref)),
      ],
    ];
    final icon = Icon(Icons.webhook_outlined, color: e.enabled ? cs.primary : cs.outline);
    final title = Text(e.description, style: theme.textTheme.titleMedium);
    // The address, the events in words, why the service stopped it, and its
    // state as a badge under the details, as the keys and the pushes have.
    final details = <Widget>[
      Text(e.url, style: theme.textTheme.bodySmall, maxLines: 1, overflow: TextOverflow.ellipsis),
      Text('${e.events.map(_eventName).join(', ')} · $last', style: theme.textTheme.bodySmall),
      if (!e.enabled && e.disabledReason != null)
        Text('Switched off: ${e.disabledReason}', style: theme.textTheme.bodySmall?.copyWith(color: cs.error)),
      const SizedBox(height: AppSpacing.xs),
      StatusBadge(
        e.enabled ? 'Active' : 'Off',
        key: Key('webhook-status-${e.id}'),
        // Stopped by the service after failing again and again, or by a person.
        tone: e.enabled ? StatusTone.success : (e.disabledReason != null ? StatusTone.error : StatusTone.neutral),
      ),
    ];
    return Card(
      key: Key('webhook-${e.id}'),
      margin: const EdgeInsetsDirectional.only(bottom: AppSpacing.sm),
      child: LayoutBuilder(builder: (context, constraints) {
        if (_narrow(context, constraints.maxWidth)) {
          // On a phone the details go under the icon at the card's width and
          // the actions into one ⋮ at the end of the name's row.
          return Padding(
            padding: const EdgeInsetsDirectional.fromSTEB(AppSpacing.lg, AppSpacing.xs, AppSpacing.xs, AppSpacing.md),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    icon,
                    const SizedBox(width: AppSpacing.md),
                    Expanded(child: title),
                    PopupMenuButton<int>(
                      key: Key('webhook-actions-${e.id}'),
                      tooltip: 'Actions for ${e.description}',
                      icon: const Icon(Icons.more_vert),
                      onSelected: (i) => actions[i].onPressed(),
                      itemBuilder: (_) => [
                        for (var i = 0; i < actions.length; i++)
                          PopupMenuItem<int>(
                            key: actions[i].key,
                            value: i,
                            child: Row(
                              children: [
                                Icon(actions[i].icon, size: 20),
                                const SizedBox(width: AppSpacing.md),
                                Flexible(child: Text(actions[i].label)),
                              ],
                            ),
                          ),
                      ],
                    ),
                  ],
                ),
                Padding(
                  padding: const EdgeInsetsDirectional.only(end: AppSpacing.md),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: details),
                ),
              ],
            ),
          );
        }
        return Padding(
          padding: const EdgeInsetsDirectional.fromSTEB(AppSpacing.lg, AppSpacing.md, AppSpacing.sm, AppSpacing.sm),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  icon,
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [title, ...details]),
                  ),
                ],
              ),
              const SizedBox(height: AppSpacing.xs),
              Wrap(
                spacing: AppSpacing.xs,
                children: [
                  for (final a in actions)
                    TextButton.icon(
                      key: a.key,
                      onPressed: a.onPressed,
                      icon: Icon(a.icon, size: 18),
                      label: Text(a.label),
                    ),
                ],
              ),
            ],
          ),
        );
      }),
    );
  }

  Future<void> _run(BuildContext context, WidgetRef ref, Future<String> Function() action) async {
    try {
      final said = await action();
      ref.invalidate(webhookEndpointsProvider);
      if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(said)));
    } catch (err) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(friendlyError(err, fallback: 'That did not work.'))));
      }
    }
  }

  Future<void> _rotate(BuildContext context, WidgetRef ref) async {
    final sure = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Rotate the secret for ${e.description}?'),
        content: const Text('The old secret stops signing at once; the receiving system must be given the new one.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Keep it')),
          FilledButton(key: const Key('rotate-confirm'), onPressed: () => Navigator.pop(ctx, true), child: const Text('Rotate')),
        ],
      ),
    );
    if (sure != true || !context.mounted) return;
    try {
      final secret = await ref.read(webhooksApiProvider).rotateSecret(e.id);
      if (!context.mounted) return;
      await showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (_) => SecretShownOnceDialog(
          title: 'New secret for ${e.description}',
          intro: 'Put this signing secret in the receiving system. It is shown once.',
          secret: secret,
        ),
      );
    } catch (err) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(friendlyError(err, fallback: 'The secret could not be rotated.'))));
      }
    }
  }

  Future<void> _remove(BuildContext context, WidgetRef ref) async {
    final sure = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Remove ${e.description}?'),
        content: const Text('Nothing more is sent to it, and its delivery log goes with it.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Keep it')),
          FilledButton(key: const Key('remove-confirm'), onPressed: () => Navigator.pop(ctx, true), child: const Text('Remove')),
        ],
      ),
    );
    if (sure != true || !context.mounted) return;
    await _run(context, ref, () async {
      await ref.read(webhooksApiProvider).remove(e.id);
      return '${e.description} removed';
    });
  }
}

/// An event by its name in words: *Order placed*, and the hand-made one a
/// ping sends, *Test delivery*.
String _eventName(String type) => type == 'Ping' ? 'Test delivery' : codeInWords(type);

/// A delivery's status as a badge in words: waiting for its next try,
/// delivered, or given up on after every try failed.
Widget _deliveryBadge(String status) {
  final (label, tone) = switch (status) {
    'PENDING' => ('Waiting', StatusTone.info),
    'DELIVERED' => ('Delivered', StatusTone.success),
    'DEAD' => ('Failed', StatusTone.error),
    _ => (humanizeCode(status), StatusTone.neutral),
  };
  return StatusBadge(label, tone: tone);
}

/// The form that registers an endpoint: an address, what it is, the events it wants.
class AddWebhookDialog extends ConsumerStatefulWidget {
  const AddWebhookDialog({super.key});

  @override
  ConsumerState<AddWebhookDialog> createState() => _AddWebhookDialogState();
}

class _AddWebhookDialogState extends ConsumerState<AddWebhookDialog> {
  final _formKey = GlobalKey<FormState>();
  final _url = TextEditingController();
  final _description = TextEditingController();
  final Set<String> _events = {};
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _url.dispose();
    _description.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    if (_events.isEmpty) {
      setState(() => _error = 'Choose at least one event.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final made = await ref.read(webhooksApiProvider).register(
            url: _url.text.trim(),
            description: _description.text.trim(),
            events: _events.toList(),
          );
      if (mounted) Navigator.pop(context, made);
    } catch (e) {
      setState(() {
        _busy = false;
        _error = friendlyError(e, fallback: 'The endpoint could not be registered.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final events = ref.watch(webhookEventsProvider);
    return AlertDialog(
      title: const Text('Add a webhook endpoint'),
      content: SizedBox(
        width: 480,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                TextFormField(
                  key: const Key('webhook-url'),
                  controller: _url,
                  decoration: const InputDecoration(labelText: 'Address *', hintText: 'https://erp.example.com/storeql'),
                  validator: (v) => v == null || !v.trim().startsWith('https://') ? 'An https:// address' : null,
                ),
                const SizedBox(height: 8),
                TextFormField(
                  key: const Key('webhook-description'),
                  controller: _description,
                  decoration: const InputDecoration(labelText: 'What it is *', hintText: 'e.g. Warehouse ERP'),
                  maxLength: 120,
                  validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
                ),
                const SizedBox(height: 8),
                Text('Tell it about', style: Theme.of(context).textTheme.labelLarge),
                events.when(
                  loading: () => const Text('Loading events…'),
                  error: (_, _) => const Text('The event list could not be loaded.'),
                  data: (list) => Column(
                    children: [
                      for (final t in list)
                        CheckboxListTile(
                          key: Key('event-${t.type}'),
                          dense: true,
                          controlAffinity: ListTileControlAffinity.leading,
                          title: Text(_eventName(t.type)),
                          subtitle: Text(t.description),
                          value: _events.contains(t.type),
                          onChanged: (on) => setState(() => on == true ? _events.add(t.type) : _events.remove(t.type)),
                        ),
                    ],
                  ),
                ),
                if (_error != null) ...[
                  const SizedBox(height: 8),
                  Text(_error!, key: const Key('webhook-error'), style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ],
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('webhook-submit'),
          onPressed: _busy ? null : _submit,
          child: _busy
              ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Register'),
        ),
      ],
    );
  }
}

/// The recent deliveries to one endpoint, each with how it went, and a way to send one again.
class _DeliveriesDialog extends ConsumerStatefulWidget {
  final WebhookEndpoint endpoint;
  const _DeliveriesDialog({required this.endpoint});

  @override
  ConsumerState<_DeliveriesDialog> createState() => _DeliveriesDialogState();
}

class _DeliveriesDialogState extends ConsumerState<_DeliveriesDialog> {
  late Future<List<WebhookDelivery>> _rows = ref.read(webhooksApiProvider).deliveries(widget.endpoint.id);

  Future<void> _resend(String id) async {
    // The messenger is found before the wait: the rows rebuild once the call returns, and a
    // context taken from inside them would be gone by then.
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(webhooksApiProvider).redeliver(id);
      if (!mounted) return;
      setState(() {
        _rows = ref.read(webhooksApiProvider).deliveries(widget.endpoint.id);
      });
      messenger.showSnackBar(const SnackBar(content: Text('Queued to send again')));
    } catch (err) {
      messenger.showSnackBar(SnackBar(content: Text(friendlyError(err, fallback: 'Could not resend.'))));
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text('Deliveries to ${widget.endpoint.description}'),
      content: SizedBox(
        width: 560,
        height: 400,
        child: FutureBuilder<List<WebhookDelivery>>(
          future: _rows,
          builder: (context, snap) {
            if (snap.connectionState != ConnectionState.done) return const LoadingView(label: 'Loading deliveries…');
            if (snap.hasError) return Text(friendlyError(snap.error!, fallback: 'Could not load the deliveries.'));
            final rows = snap.data ?? const [];
            if (rows.isEmpty) return const EmptyState(title: 'Nothing sent yet. Ping the endpoint to try it.');
            return ListView(
              children: [
                for (final d in rows)
                  ListTile(
                    key: Key('delivery-${d.id}'),
                    dense: true,
                    leading: Icon(
                      d.status == 'DELIVERED' ? Icons.check_circle_outline : (d.status == 'DEAD' ? Icons.error_outline : Icons.schedule),
                      color: d.status == 'DELIVERED' ? cs.primary : (d.status == 'DEAD' ? cs.error : cs.outline),
                    ),
                    title: Text(_eventName(d.eventType)),
                    // How it went as a badge under the details, as the pushes have.
                    subtitle: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '${d.attempts} ${d.attempts == 1 ? 'try' : 'tries'}'
                          '${d.lastStatus != null ? ' · last answer ${d.lastStatus}' : ''}'
                          '${d.lastError != null ? ' · ${d.lastError}' : ''}'
                          ' · ${AppFormat.date(d.createdAt)}',
                        ),
                        const SizedBox(height: AppSpacing.xs),
                        _deliveryBadge(d.status),
                      ],
                    ),
                    trailing: TextButton(
                      key: Key('redeliver-${d.id}'),
                      onPressed: () => _resend(d.id),
                      child: const Text('Send again'),
                    ),
                  ),
              ],
            );
          },
        ),
      ),
      actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Close'))],
    );
  }
}
