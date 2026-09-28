import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/api_error.dart';
import '../auth/sso_api.dart';

/// The business's own identity provider (20.x): staff sign in through it, by the
/// sign-in name chosen here. Owners change it; a manager can look.
class SsoSettingsDialog extends ConsumerWidget {
  const SsoSettingsDialog({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final current = ref.watch(ssoConnectionProvider);
    return current.when(
      loading: () => const AlertDialog(
        title: Text('Single sign-on'),
        content: SizedBox(height: 120, width: 460, child: Center(child: CircularProgressIndicator())),
      ),
      error: (e, _) => AlertDialog(
        title: const Text('Single sign-on'),
        content: Text(friendlyError(e)),
        actions: [TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Close'))],
      ),
      data: (c) => _SsoForm(current: c),
    );
  }
}

class _SsoForm extends ConsumerStatefulWidget {
  final SsoConnection? current;
  const _SsoForm({required this.current});

  @override
  ConsumerState<_SsoForm> createState() => _SsoFormState();
}

class _SsoFormState extends ConsumerState<_SsoForm> {
  static const _tiers = {'MANAGER': 'Managers', 'STOREKEEPER': 'Storekeepers', 'CASHIER': 'Cashiers'};

  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _slug;
  late final TextEditingController _issuer;
  late final TextEditingController _clientId;
  final _secret = TextEditingController();
  late bool _enabled;
  late bool _verifiedOnly;
  late final Set<String> _required;
  List<SsoCheck>? _checks;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    final c = widget.current;
    _slug = TextEditingController(text: c?.slug ?? '');
    _issuer = TextEditingController(text: c?.issuer ?? '');
    _clientId = TextEditingController(text: c?.clientId ?? '');
    _enabled = c?.enabled ?? true;
    _verifiedOnly = c?.requireVerifiedEmail ?? true;
    _required = {...?c?.requiredTiers};
  }

  @override
  void dispose() {
    _slug.dispose();
    _issuer.dispose();
    _clientId.dispose();
    _secret.dispose();
    super.dispose();
  }

  String _message(Object e) => switch (apiErrorCode(e)) {
        'FORBIDDEN' || 'PERMISSION_DENIED' => 'Only an owner changes this.',
        'SSO_SLUG_TAKEN' => 'Another business already signs in with that name. Choose another.',
        'SSO_ISSUER_INVALID' => 'The issuer is an https:// address, exactly as your provider states it.',
        'SSO_SECRET_REQUIRED' => 'Enter the client secret your provider gave you.',
        _ => friendlyError(e),
      };

  Future<void> _run(Future<void> Function() work) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await work();
    } catch (e) {
      if (mounted) setState(() => _error = _message(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    await _run(() async {
      await ref.read(ssoApiProvider).save(
            slug: _slug.text,
            issuer: _issuer.text,
            clientId: _clientId.text,
            clientSecret: _secret.text,
            enabled: _enabled,
            requiredTiers: _required.toList()..sort(),
            requireVerifiedEmail: _verifiedOnly,
          );
      ref.invalidate(ssoConnectionProvider);
      if (mounted) Navigator.of(context).pop(true);
    });
  }

  Future<void> _check() => _run(() async {
        final checks = await ref.read(ssoApiProvider).readiness();
        if (mounted) setState(() => _checks = checks);
      });

  Future<void> _disconnect() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Disconnect single sign-on'),
        content: const Text(
          'Staff sign in with their passwords again. Everyone linked to the provider is unlinked, '
          'and matched afresh if you connect it again.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
          FilledButton(
            key: const Key('sso-disconnect-confirm'),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Disconnect'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    await _run(() async {
      await ref.read(ssoApiProvider).disconnect();
      ref.invalidate(ssoConnectionProvider);
      if (mounted) Navigator.of(context).pop(true);
    });
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final small = Theme.of(context).textTheme.bodySmall?.copyWith(color: cs.outline);
    final c = widget.current;
    return AlertDialog(
      title: const Text('Single sign-on'),
      content: SizedBox(
        width: 480,
        child: SingleChildScrollView(
          child: Form(
            key: _formKey,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Text(
                  'Staff sign in through your own identity provider — Microsoft Entra, Google Workspace, '
                  'Okta or any OpenID Connect provider — by the sign-in name you choose here. '
                  'They are matched to the staff you have already added, by email the first time.',
                ),
                if (c?.callbackUrl != null) ...[
                  const SizedBox(height: 12),
                  Text('Redirect URI to register with your provider', style: small),
                  Row(
                    children: [
                      Expanded(child: SelectableText(c!.callbackUrl!, key: const Key('sso-callback'))),
                      IconButton(
                        tooltip: 'Copy',
                        icon: const Icon(Icons.copy, size: 18),
                        onPressed: () => Clipboard.setData(ClipboardData(text: c.callbackUrl!)),
                      ),
                    ],
                  ),
                ],
                const SizedBox(height: 12),
                TextFormField(
                  key: const Key('sso-form-slug'),
                  controller: _slug,
                  decoration: const InputDecoration(
                    labelText: 'Sign-in name',
                    helperText: 'What your staff type. Lower-case letters, digits and hyphens.',
                  ),
                  validator: (v) => RegExp(r'^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$').hasMatch((v ?? '').trim())
                      ? null
                      : '3 to 63 lower-case letters, digits and hyphens',
                ),
                const SizedBox(height: 8),
                TextFormField(
                  key: const Key('sso-form-issuer'),
                  controller: _issuer,
                  decoration: const InputDecoration(
                    labelText: 'Issuer',
                    helperText: 'Exactly as your provider states it, e.g. https://login.microsoftonline.com/…/v2.0',
                  ),
                  validator: (v) => (v ?? '').trim().isEmpty ? 'Required' : null,
                ),
                const SizedBox(height: 8),
                TextFormField(
                  key: const Key('sso-form-client'),
                  controller: _clientId,
                  decoration: const InputDecoration(labelText: 'Client ID'),
                  validator: (v) => (v ?? '').trim().isEmpty ? 'Required' : null,
                ),
                const SizedBox(height: 8),
                TextFormField(
                  key: const Key('sso-form-secret'),
                  controller: _secret,
                  obscureText: true,
                  decoration: InputDecoration(
                    labelText: 'Client secret',
                    helperText: c?.clientSecretSet == true ? 'Leave empty to keep the one saved.' : null,
                  ),
                  validator: (v) =>
                      c?.clientSecretSet != true && (v ?? '').trim().isEmpty ? 'Required the first time' : null,
                ),
                const SizedBox(height: 8),
                SwitchListTile.adaptive(
                  key: const Key('sso-form-enabled'),
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Staff can sign in with it'),
                  value: _enabled,
                  onChanged: _busy ? null : (v) => setState(() => _enabled = v),
                ),
                SwitchListTile.adaptive(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Match by email only when the provider verified it'),
                  subtitle: const Text('Turn off only if your provider never says, and you trust its addresses.'),
                  value: _verifiedOnly,
                  onChanged: _busy ? null : (v) => setState(() => _verifiedOnly = v),
                ),
                const SizedBox(height: 8),
                const Text('Only through your provider — their password stops working:'),
                for (final tier in _tiers.entries)
                  CheckboxListTile(
                    key: Key('sso-tier-${tier.key}'),
                    contentPadding: EdgeInsets.zero,
                    controlAffinity: ListTileControlAffinity.leading,
                    title: Text(tier.value),
                    value: _required.contains(tier.key),
                    onChanged: _busy
                        ? null
                        : (v) => setState(() => v == true ? _required.add(tier.key) : _required.remove(tier.key)),
                  ),
                Text(
                  'Owners can always use their password, so a provider that breaks cannot lock your business out.',
                  style: small,
                ),
                if (_checks != null) ...[
                  const Divider(height: 24),
                  for (final check in _checks!)
                    ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(
                        check.satisfied ? Icons.check_circle_outline : Icons.error_outline,
                        color: check.satisfied ? cs.primary : cs.error,
                      ),
                      title: Text(_checkName(check.code)),
                      subtitle: Text(check.detail),
                    ),
                ],
                if (_error != null) ...[
                  const SizedBox(height: 8),
                  Text(_error!, key: const Key('sso-form-error'), style: TextStyle(color: cs.error)),
                ],
              ],
            ),
          ),
        ),
      ),
      actions: [
        if (c != null) ...[
          TextButton(
            key: const Key('sso-disconnect'),
            onPressed: _busy ? null : _disconnect,
            child: Text('Disconnect', style: TextStyle(color: cs.error)),
          ),
          TextButton(key: const Key('sso-check'), onPressed: _busy ? null : _check, child: const Text('Check')),
        ],
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(), child: const Text('Cancel')),
        FilledButton(
          key: const Key('sso-save'),
          onPressed: _busy ? null : _save,
          child: Text(_busy ? 'Working…' : 'Save'),
        ),
      ],
    );
  }

  static String _checkName(String code) => switch (code) {
        'CALLBACK_CONFIGURED' => 'Redirect URI',
        'CONNECTION_SAVED' => 'Settings saved',
        'SECRET_HELD' => 'Client secret',
        'DISCOVERY_READ' => 'Provider found',
        'KEYS_READ' => 'Signing keys',
        'ENABLED' => 'Switched on',
        _ => code,
      };
}
