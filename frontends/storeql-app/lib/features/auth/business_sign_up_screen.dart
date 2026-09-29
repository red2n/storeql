import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/auth/auth_notifier.dart';
import '../../core/auth/password_policy.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../l10n/gen/app_localizations.dart';

// ---------------------------------------------------------------------------
// "Start a business" (/start-business): the sign-up for someone who is about
// to set a business up, beside the shopper's "Create account" on the sign-in
// card. iam-svc's POST /auth/register/business makes a staff login with no
// business and no role and signs it in; a login like that is exactly what
// the router sends to the setup wizard (AuthAuthenticated.needsOnboarding),
// which creates the business and makes this login its owner.
//
// The storefront never links here: a shopper signs up from the shop's own
// dialog, and the sign-in card's "Create account" still makes a shopper.
// ---------------------------------------------------------------------------

class BusinessSignUpScreen extends ConsumerStatefulWidget {
  const BusinessSignUpScreen({super.key});

  @override
  ConsumerState<BusinessSignUpScreen> createState() =>
      _BusinessSignUpScreenState();
}

class _BusinessSignUpScreenState extends ConsumerState<BusinessSignUpScreen> {
  final _formKey = GlobalKey<FormState>();
  final _emailCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  final _confirmCtrl = TextEditingController();
  bool _obscure = true;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _emailCtrl.dispose();
    _passwordCtrl.dispose();
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
          .read(authNotifierProvider.notifier)
          .registerBusiness(_emailCtrl.text.trim(), _passwordCtrl.text);
      // Signed in with no business yet: the router's redirect takes it from
      // here to the setup wizard.
    } catch (e) {
      if (!mounted) return;
      final l = AppLocalizations.of(context);
      setState(() => _error = _refusal(l, e, policy));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// A refusal in words: the password policy's codes in the rule's own terms
  /// (the published minimum, never a number guessed from the message), a
  /// taken address, no connection; anything else the server's own message.
  String _refusal(AppLocalizations l, Object e, PasswordPolicy policy) {
    switch (apiErrorCode(e)) {
      case 'PASSWORD_TOO_SHORT':
        return l.fieldPasswordTooShort(policy.minLength);
      case 'PASSWORD_TOO_LONG':
        return l.fieldPasswordTooLong(policy.maxLength);
      case 'PASSWORD_IS_IDENTITY':
        return l.errPasswordIsIdentity;
      case 'PASSWORD_BREACHED':
        return l.errPasswordBreached;
      case 'USER_ALREADY_EXISTS':
        return l.errEmailExists;
    }
    if (e is DioException && e.response == null) return l.errNetwork;
    return friendlyError(e, fallback: l.errGeneric);
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    // The published rule, read before anything is typed (never learned only
    // from a refusal); the platform's own default until it has loaded.
    final policy = watchPasswordPolicy(ref);

    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: context.pagePadding,
          // The sign-in card's own width (login_screen.dart), so going from
          // one to the other does not change the size of the card.
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Card(
              margin: EdgeInsets.zero,
              child: Padding(
                padding: const EdgeInsets.all(AppSpacing.xxl),
                child: Form(
                  key: _formKey,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Icon(
                        Icons.add_business_outlined,
                        size: 48,
                        color: cs.primary,
                      ),
                      const SizedBox(height: AppSpacing.sm),
                      Text(
                        l.startBusinessTitle,
                        style: text.headlineSmall,
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: AppSpacing.xs),
                      Text(
                        l.startBusinessIntro,
                        style: text.bodyMedium?.copyWith(
                          color: cs.onSurfaceVariant,
                        ),
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
                            key: const Key('business-signup-error'),
                            style: TextStyle(color: cs.onErrorContainer),
                          ),
                        ),
                        const SizedBox(height: AppSpacing.lg),
                      ],
                      TextFormField(
                        key: const Key('business-signup-email'),
                        controller: _emailCtrl,
                        keyboardType: TextInputType.emailAddress,
                        autofillHints: const [AutofillHints.email],
                        textInputAction: TextInputAction.next,
                        decoration: InputDecoration(
                          labelText: l.fieldEmail,
                          prefixIcon: const Icon(Icons.email_outlined),
                        ),
                        validator: (v) => v == null || !v.contains('@')
                            ? l.fieldEmailInvalid
                            : null,
                      ),
                      const SizedBox(height: AppSpacing.lg),
                      TextFormField(
                        key: const Key('business-signup-password'),
                        controller: _passwordCtrl,
                        obscureText: _obscure,
                        autofillHints: const [AutofillHints.newPassword],
                        textInputAction: TextInputAction.next,
                        decoration: InputDecoration(
                          labelText: l.fieldPassword,
                          prefixIcon: const Icon(Icons.lock_outline),
                          // The published policy's rule, before it is typed.
                          helperText: l.fieldPasswordTooShort(policy.minLength),
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
                        validator: (v) => passwordLengthProblem(l, v, policy),
                      ),
                      const SizedBox(height: AppSpacing.lg),
                      TextFormField(
                        key: const Key('business-signup-confirm'),
                        controller: _confirmCtrl,
                        obscureText: _obscure,
                        textInputAction: TextInputAction.done,
                        onFieldSubmitted: (_) {
                          if (!_busy) _submit(policy);
                        },
                        decoration: InputDecoration(
                          labelText: l.fieldPasswordConfirm,
                          prefixIcon: const Icon(Icons.lock_outline),
                          errorMaxLines: 3,
                        ),
                        validator: (v) => v != _passwordCtrl.text
                            ? l.fieldPasswordMismatch
                            : null,
                      ),
                      const SizedBox(height: AppSpacing.xl),
                      FilledButton(
                        key: const Key('business-signup-submit'),
                        onPressed: _busy ? null : () => _submit(policy),
                        child: _busy
                            ? const SizedBox(
                                height: 20,
                                width: 20,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : Text(l.actionCreateAccount),
                      ),
                      const SizedBox(height: AppSpacing.sm),
                      TextButton(
                        key: const Key('business-signup-sign-in'),
                        onPressed: _busy ? null : () => context.go('/login'),
                        child: Text(l.toggleHaveAccount),
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
