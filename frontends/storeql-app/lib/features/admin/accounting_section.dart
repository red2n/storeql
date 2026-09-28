import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/format.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/widgets/status_badge.dart';
import 'integrations_screen.dart' show SectionHeading, codeInWords;
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'accounting_api.dart';
import 'providers/admin_providers.dart' show tenantInfoProvider;

// ---------------------------------------------------------------------------
// Accounting (17.9), on the Integrations screen: the package the business keeps
// its books in. The owner connects one — which, its identifiers, the tokens it
// issued, the day to push from — maps the business's nominal codes onto the
// package's accounts, pushes now or lets the clock, and reads every journal's
// journey: delivered with the package's own id, waiting with the reason, or
// needing a person, who tries again or leaves it out with a reason.
// ---------------------------------------------------------------------------
class AccountingSection extends ConsumerWidget {
  final bool owner;
  const AccountingSection({super.key, required this.owner});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final connection = ref.watch(accountingConnectionProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SectionHeading(
          title: 'Accounting',
          actions: [
            if (owner)
              connection.when(
                loading: () => const SizedBox.shrink(),
                error: (_, _) => const SizedBox.shrink(),
                data: (c) => c == null
                    ? FilledButton.icon(
                        key: const Key('accounting-connect'),
                        onPressed: () => _connect(context, ref),
                        icon: const Icon(Icons.account_balance_outlined),
                        label: const Text('Connect a package'),
                      )
                    : Wrap(
                        spacing: AppSpacing.sm,
                        runSpacing: AppSpacing.sm,
                        children: [
                          TextButton(
                            key: const Key('accounting-disconnect'),
                            onPressed: () => _disconnect(context, ref, c),
                            child: const Text('Disconnect'),
                          ),
                          OutlinedButton(
                            key: const Key('accounting-toggle'),
                            onPressed: () => _toggle(context, ref, c),
                            child: Text(c.active ? 'Switch off' : 'Switch on'),
                          ),
                        ],
                      ),
              ),
          ],
        ),
        const SizedBox(height: 4),
        Text(
          'Every journal the ledger posts is pushed to the package you keep your books in — Xero, QuickBooks Online or '
          'Sage — once, as that package\'s journal. Map your nominal codes onto its accounts first.',
          style: theme.textTheme.bodyMedium,
        ),
        const SizedBox(height: 12),
        connection.when(
          loading: () => const LoadingView(label: 'Loading the accounting connection…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the accounting connection.'),
            onRetry: () => ref.invalidate(accountingConnectionProvider),
          ),
          data: (c) => c == null
              ? const Padding(
                  key: Key('accounting-none'),
                  padding: EdgeInsets.symmetric(vertical: 12),
                  child: Text('No package connected. Journals stay in the ledger here until one is.'),
                )
              : _ConnectionCard(c: c),
        ),
        connection.maybeWhen(
          data: (c) => c == null ? const SizedBox.shrink() : const _SyncList(),
          orElse: () => const SizedBox.shrink(),
        ),
      ],
    );
  }

  Future<void> _connect(BuildContext context, WidgetRef ref) async {
    final made = await showDialog<AccountingConnection>(
      context: context,
      builder: (_) => const ConnectAccountingDialog(),
    );
    if (made == null || !context.mounted) return;
    ref.invalidate(accountingConnectionProvider);
    ref.invalidate(accountingSyncsProvider);
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${_providerName(made.provider)} connected')));
  }

  Future<void> _toggle(BuildContext context, WidgetRef ref, AccountingConnection c) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(accountingApiProvider).setEnabled(!c.active);
      ref.invalidate(accountingConnectionProvider);
      messenger.showSnackBar(SnackBar(content: Text(c.active ? 'Pushing switched off' : 'Pushing switched on')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: 'The connection could not be changed.'))));
    }
  }

  Future<void> _disconnect(BuildContext context, WidgetRef ref, AccountingConnection c) async {
    final sure = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Disconnect ${_providerName(c.provider)}?'),
        content: const Text('The tokens, the account mapping and the log of pushes go with it. Journals already in the package stay there.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Keep it')),
          FilledButton(key: const Key('accounting-disconnect-confirm'), onPressed: () => Navigator.pop(ctx, true), child: const Text('Disconnect')),
        ],
      ),
    );
    if (sure != true || !context.mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(accountingApiProvider).disconnect();
      ref.invalidate(accountingConnectionProvider);
      ref.invalidate(accountingSyncsProvider);
      messenger.showSnackBar(const SnackBar(content: Text('Package disconnected')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: 'The package could not be disconnected.'))));
    }
  }
}

String _providerName(String code) => switch (code) {
      'XERO' => 'Xero',
      'QUICKBOOKS' => 'QuickBooks Online',
      'SAGE' => 'Sage Business Cloud Accounting',
      'SIMULATED' => 'Simulated package',
      _ => humanizeCode(code),
    };

/// What each of a package's settings is, in words (`Accounting.CATALOGUE` in
/// purchase-svc names them by key): the organisation, company or business the
/// journals go to, QuickBooks' environment, the stand-in's refused account.
String _settingLabel(String key) => switch (key) {
      'tenantId' => 'Xero organisation',
      'realmId' => 'QuickBooks company',
      'businessId' => 'Sage business',
      'environment' => 'Environment',
      'refuse' => 'Account it refuses',
      _ => codeInWords(key),
    };

/// Where to find a setting's value, under its field.
String? _settingHelp(String key) => switch (key) {
      'tenantId' => 'The organisation\'s tenant id, from the Xero connection',
      'realmId' => 'The company\'s realm id, from the Intuit app',
      'businessId' => 'The business id, from the Sage developer app',
      'environment' => 'Production, unless the company is a QuickBooks sandbox',
      'refuse' => 'A nominal code the stand-in turns away, to rehearse a refusal',
      _ => null,
    };

/// A setting on the connection's card: the package's own id by a short ref
/// (the end of it, which is what differs), the environment in words.
String _settingPhrase(String key, String value) => switch (key) {
      'environment' => '${humanizeCode(value)} environment',
      'refuse' => 'refuses account $value',
      _ => '${_settingLabel(key)} ${_ref(value)}',
    };

/// A package's id as a short ref; one short enough to read stays whole.
String _ref(String id) => id.length <= 8 ? id : '…${shortRef(id)}';

class _ConnectionCard extends ConsumerWidget {
  final AccountingConnection c;
  const _ConnectionCard({required this.c});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final n = c.counts;
    return Card(
      key: const Key('accounting-card'),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.account_balance_outlined, color: c.active ? cs.primary : cs.outline),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(_providerName(c.provider), style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              [
                'Journals from ${AppFormat.date(c.syncFrom)}',
                c.lastSyncAt == null ? 'never pushed yet' : 'last push ${AppFormat.dateTime(c.lastSyncAt)}',
                for (final e in c.settings.entries) _settingPhrase(e.key, e.value),
              ].join(' · '),
              style: Theme.of(context).textTheme.bodySmall,
            ),
            if (c.lastError != null) ...[
              const SizedBox(height: 6),
              Text(c.lastError!, key: const Key('accounting-last-error'), style: TextStyle(color: cs.error)),
            ],
            const SizedBox(height: AppSpacing.sm),
            // Whether it pushes, as a badge under the details.
            StatusBadge(
              c.active ? 'Pushing' : 'Switched off',
              key: const Key('accounting-status'),
              tone: c.active ? StatusTone.success : StatusTone.neutral,
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 4,
              children: [
                _count('Waiting', n.pending, cs.surfaceContainerHighest),
                _count('Delivered', n.delivered, cs.primaryContainer),
                _count('Failed', n.failed, n.failed > 0 ? cs.errorContainer : cs.surfaceContainerHighest),
                _count('Uncertain', n.uncertain, n.uncertain > 0 ? cs.tertiaryContainer : cs.surfaceContainerHighest),
                _count('Skipped', n.skipped, cs.surfaceContainerHighest),
              ],
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                FilledButton.tonalIcon(
                  key: const Key('accounting-sync'),
                  onPressed: c.active ? () => _syncNow(context, ref) : null,
                  icon: const Icon(Icons.sync),
                  label: const Text('Push now'),
                ),
                OutlinedButton.icon(
                  key: const Key('accounting-map'),
                  onPressed: () => showDialog<void>(context: context, builder: (_) => const AccountMappingsDialog()),
                  icon: const Icon(Icons.compare_arrows),
                  label: const Text('Map accounts'),
                ),
                OutlinedButton.icon(
                  key: const Key('accounting-check'),
                  onPressed: () => _check(context, ref),
                  icon: const Icon(Icons.fact_check_outlined),
                  label: const Text('Check the connection'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _count(String label, int n, Color color) => Chip(label: Text('$label $n'), backgroundColor: color);

  Future<void> _syncNow(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      final run = await ref.read(accountingApiProvider).syncNow();
      ref.invalidate(accountingConnectionProvider);
      ref.invalidate(accountingSyncsProvider);
      messenger.showSnackBar(SnackBar(
        content: Text('${run.queued} queued, ${run.delivered} pushed'
            '${run.failed > 0 ? ', ${run.failed} refused' : ''}${run.uncertain > 0 ? ', ${run.uncertain} uncertain' : ''}'),
      ));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: 'The push could not be started.'))));
    }
  }

  Future<void> _check(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      final accounts = await ref.read(accountingApiProvider).accounts();
      messenger.showSnackBar(SnackBar(content: Text('Connected: the package lists ${accounts.length} accounts')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: 'The package could not be reached.'))));
    }
  }
}

class _SyncList extends ConsumerWidget {
  const _SyncList();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final syncs = ref.watch(accountingSyncsProvider);
    // The ledger is kept in the business's own currency, and a push carries
    // none: its total is money in that currency.
    final currency = ref.watch(tenantInfoProvider).value?.currency;
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Recent pushes', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
          const SizedBox(height: 6),
          syncs.when(
            loading: () => const LoadingView(label: 'Loading pushes…'),
            error: (e, _) => ErrorView(
              message: friendlyError(e, fallback: 'Could not load the pushes.'),
              onRetry: () => ref.invalidate(accountingSyncsProvider),
            ),
            data: (list) => list.isEmpty
                ? const Padding(
                    key: Key('accounting-syncs-none'),
                    padding: EdgeInsets.symmetric(vertical: 8),
                    child: Text('Nothing pushed yet. Post a journal, or press Push now.'),
                  )
                : Column(children: [for (final s in list) _SyncTile(s: s, currency: currency)]),
          ),
        ],
      ),
    );
  }
}

class _SyncTile extends ConsumerWidget {
  final AccountingSync s;

  /// The business's home currency, or null while it is unknown.
  final String? currency;
  const _SyncTile({required this.s, required this.currency});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tone = switch (s.status) {
      'DELIVERED' => StatusTone.success,
      'FAILED' => StatusTone.error,
      'UNCERTAIN' => StatusTone.warning,
      // Waiting is the info tone everywhere (UI-GUIDE §7.2), as a webhook
      // delivery that waits is on the same page.
      'PENDING' => StatusTone.info,
      _ => StatusTone.neutral,
    };
    final total = s.total == null ? null : num.tryParse(s.total!);
    final detail = [
      if (s.entryDate != null) AppFormat.date(s.entryDate),
      if (total != null) AppFormat.money(total, currencyCode: currency),
      if (s.externalId != null) 'in the package as ${_ref(s.externalId!)}',
      if (s.lastError != null && !s.delivered) s.lastError!,
      if (s.status == 'PENDING' && s.nextAttemptAt != null && s.attempts > 0) 'next try ${AppFormat.dateTime(s.nextAttemptAt)}',
    ].join(' · ');
    return Card(
      key: Key('sync-${s.id}'),
      margin: const EdgeInsetsDirectional.only(bottom: AppSpacing.sm),
      child: ListTile(
        title: Text(s.description ?? s.journalId),
        // The state as a badge under the details; the two actions at the end
        // where there is room, one menu on a phone, so the journal's name
        // keeps the width.
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (detail.isNotEmpty) Text(detail),
            const SizedBox(height: AppSpacing.xs),
            StatusBadge(_statusLabel(s.status), tone: tone),
          ],
        ),
        trailing: !s.retryable
            ? null
            : context.isCompact
                ? PopupMenuButton<String>(
                    key: Key('sync-actions-${s.id}'),
                    tooltip: 'Try again or leave out',
                    onSelected: (a) => a == 'retry' ? _retry(context, ref) : _skip(context, ref),
                    itemBuilder: (_) => const [
                      PopupMenuItem(value: 'retry', child: Text('Try again now')),
                      PopupMenuItem(value: 'skip', child: Text('Leave it out of the package')),
                    ],
                  )
                : Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      IconButton(
                        key: Key('sync-retry-${s.id}'),
                        tooltip: 'Try again now',
                        icon: const Icon(Icons.replay),
                        onPressed: () => _retry(context, ref),
                      ),
                      IconButton(
                        key: Key('sync-skip-${s.id}'),
                        tooltip: 'Leave it out of the package',
                        icon: const Icon(Icons.block),
                        onPressed: () => _skip(context, ref),
                      ),
                    ],
                  ),
      ),
    );
  }

  static String _statusLabel(String status) => switch (status) {
        'PENDING' => 'Waiting',
        'DELIVERED' => 'Delivered',
        'FAILED' => 'Failed',
        'UNCERTAIN' => 'Uncertain',
        'SKIPPED' => 'Skipped',
        _ => humanizeCode(status),
      };

  Future<void> _retry(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(accountingApiProvider).retry(s.id);
      ref.invalidate(accountingSyncsProvider);
      ref.invalidate(accountingConnectionProvider);
      messenger.showSnackBar(const SnackBar(content: Text('Queued to go on the next push')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: 'It could not be queued again.'))));
    }
  }

  Future<void> _skip(BuildContext context, WidgetRef ref) async {
    final reason = await showDialog<String>(context: context, builder: (_) => const _SkipReasonDialog());
    if (reason == null || reason.isEmpty || !context.mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(accountingApiProvider).skip(s.id, reason);
      ref.invalidate(accountingSyncsProvider);
      ref.invalidate(accountingConnectionProvider);
      messenger.showSnackBar(const SnackBar(content: Text('Left out of the package')));
    } catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(friendlyError(e, fallback: 'It could not be left out.'))));
    }
  }
}

/// Why a journal is left out of the package; the dialog owns its own text.
class _SkipReasonDialog extends StatefulWidget {
  const _SkipReasonDialog();

  @override
  State<_SkipReasonDialog> createState() => _SkipReasonDialogState();
}

class _SkipReasonDialogState extends State<_SkipReasonDialog> {
  final _reason = TextEditingController();

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Leave this journal out of the package?'),
      content: TextField(
        key: const Key('sync-skip-reason'),
        controller: _reason,
        decoration: const InputDecoration(labelText: 'Why', hintText: 'e.g. entered in the package by hand'),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('sync-skip-confirm'),
          onPressed: () => Navigator.pop(context, _reason.text.trim()),
          child: const Text('Leave it out'),
        ),
      ],
    );
  }
}

/// The form that connects a package: which, its identifiers, its tokens, the day to push from.
class ConnectAccountingDialog extends ConsumerStatefulWidget {
  const ConnectAccountingDialog({super.key});

  @override
  ConsumerState<ConnectAccountingDialog> createState() => _ConnectAccountingDialogState();
}

class _ConnectAccountingDialogState extends ConsumerState<ConnectAccountingDialog> {
  final _formKey = GlobalKey<FormState>();
  String? _provider;
  final Map<String, TextEditingController> _settings = {};
  final _access = TextEditingController();
  final _refresh = TextEditingController();
  final _clientId = TextEditingController();
  final _clientSecret = TextEditingController();

  /// Journals dated from this day are pushed; the first of this month unless
  /// another day is chosen on the calendar.
  DateTime _syncFrom = DateTime(DateTime.now().year, DateTime.now().month);
  bool _busy = false;
  String? _error;

  /// The day as the server reads it: yyyy-MM-dd.
  String get _syncFromIso =>
      '${_syncFrom.year.toString().padLeft(4, '0')}-${_syncFrom.month.toString().padLeft(2, '0')}-${_syncFrom.day.toString().padLeft(2, '0')}';

  Future<void> _pickSyncFrom() async {
    final today = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      helpText: 'Push journals from',
      initialDate: _syncFrom,
      firstDate: DateTime(2000),
      lastDate: DateTime(today.year + 1, 12, 31),
    );
    if (picked != null && mounted) setState(() => _syncFrom = DateTime(picked.year, picked.month, picked.day));
  }

  @override
  void dispose() {
    for (final c in _settings.values) {
      c.dispose();
    }
    _access.dispose();
    _refresh.dispose();
    _clientId.dispose();
    _clientSecret.dispose();
    super.dispose();
  }

  TextEditingController _setting(String name) => _settings.putIfAbsent(name, TextEditingController.new);

  Future<void> _submit(AccountingProvider chosen) async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final settings = <String, String>{
        for (final name in [...chosen.settings, ...chosen.optional])
          if (_setting(name).text.trim().isNotEmpty) name: _setting(name).text.trim(),
      };
      final made = await ref.read(accountingApiProvider).connect(
            provider: chosen.code,
            settings: settings,
            credentials: chosen.needsCredentials
                ? {
                    'accessToken': _access.text.trim(),
                    if (_refresh.text.trim().isNotEmpty) 'refreshToken': _refresh.text.trim(),
                    if (_clientId.text.trim().isNotEmpty) 'clientId': _clientId.text.trim(),
                    if (_clientSecret.text.trim().isNotEmpty) 'clientSecret': _clientSecret.text.trim(),
                  }
                : null,
            syncFrom: _syncFromIso,
          );
      if (mounted) Navigator.pop(context, made);
    } catch (e) {
      setState(() {
        _busy = false;
        _error = friendlyError(e, fallback: 'The package could not be connected.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final providers = ref.watch(accountingProvidersProvider);
    return AlertDialog(
      title: const Text('Connect an accounting package'),
      content: SizedBox(
        width: 480,
        child: providers.when(
          loading: () => const LoadingView(label: 'Loading packages…'),
          error: (e, _) => Text(friendlyError(e, fallback: 'The packages could not be listed.')),
          data: (list) {
            final chosen = list.firstWhere((p) => p.code == _provider, orElse: () => list.first);
            _provider ??= chosen.code;
            return Form(
              key: _formKey,
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    DropdownButtonFormField<String>(
                      key: const Key('acct-provider'),
                      initialValue: chosen.code,
                      decoration: const InputDecoration(labelText: 'Package *'),
                      items: [for (final p in list) DropdownMenuItem(value: p.code, child: Text(p.name))],
                      onChanged: (v) => setState(() => _provider = v),
                    ),
                    const SizedBox(height: 8),
                    Text(chosen.tokens, style: Theme.of(context).textTheme.bodySmall),
                    // Each setting by what it is — the Xero organisation, the
                    // QuickBooks company — with where to find it underneath.
                    for (final name in chosen.settings) ...[
                      const SizedBox(height: 8),
                      TextFormField(
                        key: Key('acct-setting-$name'),
                        controller: _setting(name),
                        decoration: InputDecoration(labelText: '${_settingLabel(name)} *', helperText: _settingHelp(name)),
                        validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
                      ),
                    ],
                    for (final name in chosen.optional) ...[
                      const SizedBox(height: 8),
                      if (name == 'environment')
                        DropdownButtonFormField<String>(
                          key: Key('acct-setting-$name'),
                          initialValue: _setting(name).text.isEmpty ? null : _setting(name).text,
                          decoration: InputDecoration(labelText: _settingLabel(name), helperText: _settingHelp(name)),
                          items: const [
                            DropdownMenuItem(value: 'PRODUCTION', child: Text('Production')),
                            DropdownMenuItem(value: 'SANDBOX', child: Text('Sandbox')),
                          ],
                          onChanged: (v) => _setting(name).text = v ?? '',
                        )
                      else
                        TextFormField(
                          key: Key('acct-setting-$name'),
                          controller: _setting(name),
                          decoration: InputDecoration(labelText: _settingLabel(name), helperText: _settingHelp(name)),
                        ),
                    ],
                    if (chosen.needsCredentials) ...[
                      const SizedBox(height: 8),
                      TextFormField(
                        key: const Key('acct-access'),
                        controller: _access,
                        obscureText: true,
                        decoration: const InputDecoration(labelText: 'Access token *'),
                        validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
                      ),
                      const SizedBox(height: 8),
                      TextFormField(
                        key: const Key('acct-refresh'),
                        controller: _refresh,
                        obscureText: true,
                        decoration: const InputDecoration(labelText: 'Refresh token', helperText: 'With the client id and secret, the token renews itself.'),
                      ),
                      const SizedBox(height: 8),
                      TextFormField(key: const Key('acct-client-id'), controller: _clientId, decoration: const InputDecoration(labelText: 'Client id')),
                      const SizedBox(height: 8),
                      TextFormField(
                        key: const Key('acct-client-secret'),
                        controller: _clientSecret,
                        obscureText: true,
                        decoration: const InputDecoration(labelText: 'Client secret'),
                      ),
                    ],
                    const SizedBox(height: 8),
                    // The day on a calendar, never typed; sent as yyyy-MM-dd.
                    InkWell(
                      key: const Key('acct-sync-from'),
                      borderRadius: AppRadius.input,
                      onTap: _busy ? null : _pickSyncFrom,
                      child: InputDecorator(
                        decoration: const InputDecoration(
                          labelText: 'Push journals from *',
                          helperText: 'Journals dated from this day on go to the package.',
                          suffixIcon: Icon(Icons.calendar_today_outlined),
                        ),
                        child: Text(AppFormat.dateOf(_syncFrom)),
                      ),
                    ),
                    if (_error != null) ...[
                      const SizedBox(height: 8),
                      Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                    ],
                  ],
                ),
              ),
            );
          },
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('acct-submit'),
          onPressed: _busy
              ? null
              : () {
                  final list = ref.read(accountingProvidersProvider).value;
                  if (list == null || list.isEmpty) return;
                  _submit(list.firstWhere((p) => p.code == _provider, orElse: () => list.first));
                },
          child: _busy ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Text('Connect'),
        ),
      ],
    );
  }
}

/// The business's nominal codes onto the package's accounts; the whole mapping, saved at once.
class AccountMappingsDialog extends ConsumerStatefulWidget {
  const AccountMappingsDialog({super.key});

  @override
  ConsumerState<AccountMappingsDialog> createState() => _AccountMappingsDialogState();
}

class _MappingRow {
  final TextEditingController code;
  final TextEditingController account;
  _MappingRow(String c, String a) : code = TextEditingController(text: c), account = TextEditingController(text: a);
  void dispose() {
    code.dispose();
    account.dispose();
  }
}

class _AccountMappingsDialogState extends ConsumerState<AccountMappingsDialog> {
  List<_MappingRow>? _rows;
  List<ExternalAccount> _chart = const [];
  String? _chartError;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final api = ref.read(accountingApiProvider);
    try {
      final mappings = await api.mappings();
      if (!mounted) return;
      setState(() => _rows = [for (final m in mappings) _MappingRow(m.nominalCode, m.externalAccount)]);
    } catch (e) {
      if (mounted) setState(() => _error = friendlyError(e, fallback: 'The mapping could not be loaded.'));
    }
    try {
      final chart = await api.accounts();
      if (mounted) setState(() => _chart = chart);
    } catch (e) {
      if (mounted) setState(() => _chartError = friendlyError(e, fallback: 'The package\'s accounts could not be read.'));
    }
  }

  @override
  void dispose() {
    for (final r in _rows ?? const <_MappingRow>[]) {
      r.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final mappings = [
        for (final r in _rows ?? const <_MappingRow>[])
          if (r.code.text.trim().isNotEmpty)
            AccountMapping(nominalCode: r.code.text.trim(), externalAccount: r.account.text.trim()),
      ];
      await ref.read(accountingApiProvider).replaceMappings(mappings);
      if (mounted) Navigator.pop(context);
    } catch (e) {
      setState(() {
        _busy = false;
        _error = friendlyError(e, fallback: 'The mapping could not be saved.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final rows = _rows;
    return AlertDialog(
      title: const Text('Map accounts'),
      content: SizedBox(
        width: 560,
        child: rows == null
            ? (_error != null ? Text(_error!) : const LoadingView(label: 'Loading the mapping…'))
            : SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      'Your nominal code on the left, the package\'s account on the right. A code left unmapped is sent as itself.'
                      '${_chartError == null ? '' : ' $_chartError'}',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    const SizedBox(height: 8),
                    for (var i = 0; i < rows.length; i++)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 6),
                        child: Row(
                          children: [
                            SizedBox(
                              width: 120,
                              child: TextField(
                                key: Key('map-code-$i'),
                                controller: rows[i].code,
                                decoration: const InputDecoration(labelText: 'Nominal code'),
                              ),
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: _chart.isEmpty
                                  ? TextField(
                                      key: Key('map-account-$i'),
                                      controller: rows[i].account,
                                      decoration: const InputDecoration(labelText: 'Package account'),
                                    )
                                  : DropdownButtonFormField<String>(
                                      key: Key('map-account-$i'),
                                      initialValue: _chart.any((a) => a.id == rows[i].account.text) ? rows[i].account.text : null,
                                      decoration: const InputDecoration(labelText: 'Package account'),
                                      items: [for (final a in _chart) DropdownMenuItem(value: a.id, child: Text(a.label, overflow: TextOverflow.ellipsis))],
                                      onChanged: (v) => setState(() => rows[i].account.text = v ?? ''),
                                    ),
                            ),
                            IconButton(
                              key: Key('map-remove-$i'),
                              tooltip: 'Remove',
                              icon: const Icon(Icons.close),
                              onPressed: () {
                                final removed = rows.removeAt(i);
                                // Disposed once the fields that used it are gone from the tree.
                                WidgetsBinding.instance.addPostFrameCallback((_) => removed.dispose());
                                setState(() {});
                              },
                            ),
                          ],
                        ),
                      ),
                    TextButton.icon(
                      key: const Key('map-add'),
                      onPressed: () => setState(() => rows.add(_MappingRow('', ''))),
                      icon: const Icon(Icons.add),
                      label: const Text('Add a mapping'),
                    ),
                    if (_error != null) ...[
                      const SizedBox(height: 8),
                      Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                    ],
                  ],
                ),
              ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          key: const Key('map-save'),
          onPressed: _busy || rows == null ? null : _save,
          child: _busy ? const SizedBox(height: 18, width: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Text('Save'),
        ),
      ],
    );
  }
}
