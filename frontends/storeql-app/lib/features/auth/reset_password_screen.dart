import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/auth/password_policy.dart';
import '../../core/constants.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../l10n/gen/app_localizations.dart';

// ---------------------------------------------------------------------------
// Forgot password — the reset half (intent/password-reset.md, CONFIRMED).
//
// The link from the email, good for thirty minutes and once. No sign-in: the
// link is the proof. Shows the published policy's rule before anything is
// typed (fetched, never learned from a refusal), and a refused password
// leaves the token unspent — the person tries again with the same link.
// ---------------------------------------------------------------------------

class ResetPasswordScreen extends ConsumerStatefulWidget {
  final String token;
  const ResetPasswordScreen({super.key, required this.token});

  @override
  ConsumerState<ResetPasswordScreen> createState() =>
      _ResetPasswordScreenState();
}

class _ResetPasswordScreenState extends ConsumerState<ResetPasswordScreen> {
  final _formKey = GlobalKey<FormState>();
  final _newCtrl = TextEditingController();
  final _confirmCtrl = TextEditingController();
  bool _obscure = true;
  bool _busy = false;
  bool _done = false;
  bool _tokenInvalid = false;
  String? _error;

  @override
  void dispose() {
    _newCtrl.dispose();
    _confirmCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit(PasswordPolicy policy) async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref
          .read(publicAuthDioProvider)
          .post(
            '/${ApiConstants.iam}/auth/password/reset',
            data: {'token': widget.token, 'newPassword': _newCtrl.text},
          );
      if (mounted) setState(() => _done = true);
    } catch (e) {
      if (!mounted) return;
      final l = AppLocalizations.of(context);
      final code = apiErrorCode(e);
      setState(() {
        if (code == 'PASSWORD_RESET_TOKEN_INVALID') {
          _tokenInvalid = true;
          return;
        }
        _error = switch (code) {
          'PASSWORD_TOO_SHORT' => l.fieldPasswordTooShort(policy.minLength),
          'PASSWORD_TOO_LONG' => l.fieldPasswordTooLong(policy.maxLength),
          'PASSWORD_IS_IDENTITY' => l.errPasswordIsIdentity,
          'PASSWORD_BREACHED' => l.errPasswordBreached,
          _ => friendlyError(e),
        };
      });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final policy = watchPasswordPolicy(ref);

    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: context.pagePadding,
          // The sign-in card's own width (login_screen.dart), not the wider
          // ContentBounds.form (640) this page came from — the link from the
          // email should not open onto a page wider than the sign-in card
          // it leads back to.
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Card(
              margin: EdgeInsets.zero,
              child: Padding(
                padding: const EdgeInsets.all(AppSpacing.xxl),
                child: _done
                    ? _ResetDone(l: l, text: text, cs: cs)
                    : _tokenInvalid
                    ? _TokenInvalid(l: l, text: text, cs: cs)
                    : Form(
                        key: _formKey,
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Icon(
                              Icons.lock_reset_outlined,
                              size: 48,
                              color: cs.primary,
                            ),
                            const SizedBox(height: AppSpacing.sm),
                            Text(
                              l.resetPasswordTitle,
                              style: text.headlineSmall,
                              textAlign: TextAlign.center,
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            if (_error != null) ...[
                              Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: AppSpacing.md,
                                  vertical: AppSpacing.sm,
                                ),
                                decoration: BoxDecoration(
                                  color: cs.errorContainer,
                                  borderRadius: AppRadius.chip,
                                ),
                                child: Text(
                                  _error!,
                                  key: const Key('reset-error'),
                                  style: TextStyle(color: cs.onErrorContainer),
                                ),
                              ),
                              const SizedBox(height: AppSpacing.lg),
                            ],
                            TextFormField(
                              key: const Key('reset-new-password'),
                              controller: _newCtrl,
                              obscureText: _obscure,
                              autofocus: true,
                              textInputAction: TextInputAction.next,
                              decoration: InputDecoration(
                                labelText: l.resetPasswordNewLabel,
                                prefixIcon: const Icon(Icons.lock_outline),
                                // The published policy's rule, before it is typed.
                                helperText: l.fieldPasswordTooShort(
                                  policy.minLength,
                                ),
                                helperMaxLines: 3,
                                errorMaxLines: 3,
                                suffixIcon: IconButton(
                                  icon: Icon(
                                    _obscure
                                        ? Icons.visibility_off
                                        : Icons.visibility,
                                  ),
                                  tooltip: _obscure
                                      ? l.showPassword
                                      : l.hidePassword,
                                  onPressed: () =>
                                      setState(() => _obscure = !_obscure),
                                ),
                              ),
                              validator: (v) =>
                                  passwordLengthProblem(l, v, policy),
                            ),
                            const SizedBox(height: AppSpacing.lg),
                            TextFormField(
                              key: const Key('reset-confirm-password'),
                              controller: _confirmCtrl,
                              obscureText: _obscure,
                              textInputAction: TextInputAction.done,
                              onFieldSubmitted: (_) => _submit(policy),
                              decoration: InputDecoration(
                                labelText: l.resetPasswordConfirmLabel,
                                prefixIcon: const Icon(Icons.lock_outline),
                              ),
                              validator: (v) => v != _newCtrl.text
                                  ? l.resetPasswordMismatch
                                  : null,
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            FilledButton(
                              key: const Key('reset-submit'),
                              onPressed: _busy ? null : () => _submit(policy),
                              child: _busy
                                  ? const SizedBox(
                                      height: 20,
                                      width: 20,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                      ),
                                    )
                                  : Text(l.resetPasswordSubmit),
                            ),
                          ],
                        ),
                      ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// The reset's success: no tokens are issued (20.12's second factor, if any,
/// is still asked at sign-in), so the way on is a plain link to it — the
/// storefront's own sign-in lives in the shop, not here.
class _ResetDone extends StatelessWidget {
  final AppLocalizations l;
  final TextTheme text;
  final ColorScheme cs;
  const _ResetDone({required this.l, required this.text, required this.cs});

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Icon(Icons.check_circle_outline, size: 48, color: cs.primary),
        const SizedBox(height: AppSpacing.md),
        Text(
          l.resetPasswordDone,
          key: const Key('reset-done'),
          style: text.titleMedium,
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: AppSpacing.xs),
        Text(
          l.resetPasswordDoneStorefront,
          style: text.bodyMedium?.copyWith(color: cs.outline),
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: AppSpacing.xl),
        FilledButton(
          key: const Key('reset-go-sign-in'),
          onPressed: () => context.go('/login'),
          child: Text(l.actionSignIn),
        ),
      ],
    );
  }
}

/// A used, expired or replaced link, and a made-up one, are refused alike —
/// one code, so the page cannot tell which, and says only that it no longer
/// works.
class _TokenInvalid extends StatelessWidget {
  final AppLocalizations l;
  final TextTheme text;
  final ColorScheme cs;
  const _TokenInvalid({required this.l, required this.text, required this.cs});

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Icon(Icons.error_outline, size: 48, color: cs.error),
        const SizedBox(height: AppSpacing.md),
        Text(
          l.resetPasswordTokenInvalid,
          key: const Key('reset-token-invalid'),
          style: text.titleMedium,
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: AppSpacing.xl),
        FilledButton(
          key: const Key('reset-request-new'),
          onPressed: () => context.go('/forgot-password'),
          child: Text(l.resetPasswordRequestNew),
        ),
      ],
    );
  }
}
