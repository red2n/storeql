import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/auth/passkeys.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import 'mfa_widgets.dart';

/// The second step of a sign-in (20.12): the password was right, and the login
/// holds a second factor — a code from its authenticator app, a passkey on this
/// device, or one of its recovery codes.
class SecondFactorScreen extends ConsumerStatefulWidget {
  const SecondFactorScreen({super.key});

  @override
  ConsumerState<SecondFactorScreen> createState() => _SecondFactorScreenState();
}

class _SecondFactorScreenState extends ConsumerState<SecondFactorScreen> {
  final _code = TextEditingController();
  bool _recovery = false;
  bool _busy = false;

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  Future<void> _run(Future<void> Function(AuthNotifier) answer) async {
    setState(() => _busy = true);
    await answer(ref.read(authNotifierProvider.notifier));
    if (mounted) {
      setState(() => _busy = false);
      _code.clear();
    }
  }

  @override
  Widget build(BuildContext context) {
    final owed = ref.watch(authNotifierProvider).value;
    if (owed is! AuthSecondFactorOwed) return const SizedBox.shrink();
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final hasTotp = owed.methods.contains('TOTP');
    final hasRecovery = owed.methods.contains('RECOVERY_CODE');
    final hasPasskey = owed.methods.contains('PASSKEY') && passkeys.supported;
    // A login with only a passkey, on a device that cannot use one, is left the recovery code.
    final recovery = _recovery || (!hasTotp && hasRecovery);

    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: context.pagePadding,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(AppSpacing.xxl),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Icon(Icons.verified_user_outlined, size: 48, color: cs.primary),
                    const SizedBox(height: AppSpacing.md),
                    Text('One more step', style: text.headlineSmall, textAlign: TextAlign.center),
                    const SizedBox(height: AppSpacing.xs),
                    Text(
                      recovery
                          ? 'Enter one of your recovery codes. Each works once.'
                          : 'Enter the code your authenticator app shows for this account.',
                      style: text.bodyMedium?.copyWith(color: cs.outline),
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: AppSpacing.xl),
                    if (owed.error != null) ...[
                      Container(
                        key: const Key('mfa-error'),
                        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.sm),
                        decoration: BoxDecoration(color: cs.errorContainer, borderRadius: AppRadius.chip),
                        child: Text(owed.error!, style: TextStyle(color: cs.onErrorContainer)),
                      ),
                      const SizedBox(height: AppSpacing.lg),
                    ],
                    // One filled button per card: *Sign in* is it, so the
                    // passkey — offered first — is tonal.
                    if (hasPasskey) ...[
                      FilledButton.tonalIcon(
                        key: const Key('mfa-passkey'),
                        icon: const Icon(Icons.fingerprint),
                        label: const Text('Use a passkey'),
                        onPressed: _busy ? null : () => _run((n) => n.answerWithPasskey()),
                      ),
                      if (hasTotp || hasRecovery) ...[
                        const SizedBox(height: AppSpacing.lg),
                        Row(children: [
                          const Expanded(child: Divider()),
                          Padding(
                            padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md),
                            child: Text('or', style: text.bodySmall),
                          ),
                          const Expanded(child: Divider()),
                        ]),
                        const SizedBox(height: AppSpacing.lg),
                      ],
                    ],
                    if (hasTotp || hasRecovery) ...[
                      CodeField(
                        controller: _code,
                        recovery: recovery,
                        onSubmitted: _busy ? null : () => _submit(recovery),
                      ),
                      const SizedBox(height: AppSpacing.lg),
                      FilledButton(
                        key: const Key('mfa-submit'),
                        onPressed: _busy ? null : () => _submit(recovery),
                        child: Text(_busy ? 'Checking…' : 'Sign in'),
                      ),
                      if (hasTotp && hasRecovery)
                        TextButton(
                          key: const Key('mfa-switch'),
                          onPressed: _busy ? null : () => setState(() => _recovery = !_recovery),
                          child: Text(recovery ? 'Use the authenticator app instead' : 'Lost your phone? Use a recovery code'),
                        ),
                    ],
                    TextButton(
                      key: const Key('mfa-cancel'),
                      onPressed: _busy ? null : () => ref.read(authNotifierProvider.notifier).cancelSecondFactor(),
                      child: const Text('Back to sign in'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  void _submit(bool recovery) {
    if (_code.text.trim().isEmpty) return;
    final code = _code.text;
    _run((n) => n.answerSecondFactor(recovery ? 'RECOVERY_CODE' : 'TOTP', code));
  }
}
