import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/auth/passkeys.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import 'mfa_api.dart';
import 'mfa_widgets.dart';

/// A sign-in that must have a second factor and has none (20.12): the business,
/// or the platform, requires one of this login. The factor is set up here, with
/// a token good for nothing else, and the session begins once the recovery codes
/// have been kept.
class SecondFactorSetupScreen extends ConsumerStatefulWidget {
  const SecondFactorSetupScreen({super.key});

  @override
  ConsumerState<SecondFactorSetupScreen> createState() => _SecondFactorSetupScreenState();
}

class _SecondFactorSetupScreenState extends ConsumerState<SecondFactorSetupScreen> {
  FactorEnrolled? _enrolled;
  bool _usePasskey = false;
  String? _error;
  bool _busy = false;

  Future<void> _addPasskey(MfaApi api) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final enrolled = await api.addPasskey('This device');
      if (mounted) setState(() => _enrolled = enrolled);
    } on PasskeyCancelled {
      // Closed or timed out: nothing to say, the button is still there.
    } catch (e) {
      if (mounted) setState(() => _error = friendlyError(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final owed = ref.watch(authNotifierProvider).value;
    if (owed is! AuthEnrolmentOwed) return const SizedBox.shrink();
    final api = MfaApi(ref.watch(apiClientProvider).dio, token: owed.enrolmentToken);
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final enrolled = _enrolled;

    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: context.pagePadding,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 460),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(AppSpacing.xxl),
                child: enrolled != null
                    ? RecoveryCodesPanel(
                        codes: enrolled.recoveryCodes,
                        doneLabel: 'Finish signing in',
                        onDone: () => ref.read(authNotifierProvider.notifier).completeEnrolment(enrolled.tokens!),
                      )
                    : Column(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          // The only way back sits above the steps, so a short
                          // window (a laptop's 800) never leaves it below the fold.
                          Align(
                            alignment: AlignmentDirectional.centerStart,
                            child: TextButton.icon(
                              key: const Key('setup-cancel'),
                              icon: const Icon(Icons.arrow_back),
                              label: const Text('Back to sign in'),
                              onPressed: _busy ? null : () => ref.read(authNotifierProvider.notifier).cancelSecondFactor(),
                            ),
                          ),
                          const SizedBox(height: AppSpacing.sm),
                          Icon(Icons.shield_outlined, size: 48, color: cs.primary),
                          const SizedBox(height: AppSpacing.md),
                          Text('Set up a second step', style: text.headlineSmall, textAlign: TextAlign.center),
                          const SizedBox(height: AppSpacing.xs),
                          Text(
                            owed.platform
                                ? 'A platform administrator signs in with a second step as well as a password.'
                                : 'Your business asks for a second step when you sign in, as well as your password.',
                            style: text.bodyMedium?.copyWith(color: cs.outline),
                            textAlign: TextAlign.center,
                          ),
                          const SizedBox(height: AppSpacing.xl),
                          if (_error != null) ...[
                            Text(_error!, style: TextStyle(color: cs.error)),
                            const SizedBox(height: AppSpacing.md),
                          ],
                          if (_usePasskey)
                            FilledButton.icon(
                              key: const Key('setup-passkey'),
                              icon: const Icon(Icons.fingerprint),
                              label: Text(_busy ? 'Waiting for your device…' : 'Create a passkey on this device'),
                              onPressed: _busy ? null : () => _addPasskey(api),
                            )
                          else
                            TotpSetup(api: api, onEnrolled: (e) => setState(() => _enrolled = e)),
                          if (passkeys.supported)
                            TextButton(
                              key: const Key('setup-switch'),
                              onPressed: _busy ? null : () => setState(() => _usePasskey = !_usePasskey),
                              child: Text(_usePasskey ? 'Use an authenticator app instead' : 'Use a passkey instead'),
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
}
