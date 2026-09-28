import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/password_policy.dart';
import '../../core/auth/sso.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../l10n/gen/app_localizations.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _emailCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  final _phoneCtrl = TextEditingController();
  bool _obscure = true;
  bool _isRegister = false;

  @override
  void dispose() {
    _emailCtrl.dispose();
    _passwordCtrl.dispose();
    _phoneCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    final notifier = ref.read(authNotifierProvider.notifier);
    if (_isRegister) {
      final phone = _phoneCtrl.text.trim();
      await notifier.register(
        _emailCtrl.text.trim(),
        _passwordCtrl.text,
        phone.isEmpty ? null : phone,
      );
    } else {
      await notifier.login(_emailCtrl.text.trim(), _passwordCtrl.text);
    }
    // routing handled by go_router redirect on auth state change
  }

  /// Signing in through the business's own identity provider (20.x): asks for
  /// the business's sign-in name, unless the server has just named it.
  Future<void> _signInWithBusiness({String? slug}) async {
    final name = slug ?? await showDialog<String>(context: context, builder: (_) => const _BusinessNameDialog());
    if (name == null || name.trim().isEmpty) return;
    await ref.read(authNotifierProvider.notifier).startSso(name);
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final authAsync = ref.watch(authNotifierProvider);
    final isLoading = authAsync.isLoading;
    // Only in sign-up mode: a plain sign-in never touches the policy
    // endpoint, so a test (or a person) that never opens sign-up never makes
    // that network call at all.
    final policy = _isRegister ? watchPasswordPolicy(ref) : PasswordPolicy.fallback;
    final error = authAsync.hasError ? _friendlyError(context, authAsync.error!, policy) : null;
    // A password refused because the business signs its staff in through its
    // provider: the server names the business, so one press continues there.
    final requiredSlug = authAsync.hasError && apiErrorCode(authAsync.error!) == 'SSO_REQUIRED'
        ? apiErrorOf(authAsync.error!)?.detail('slug')
        : null;
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: context.pagePadding,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(AppSpacing.xxl),
                child: Form(
                  key: _formKey,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Icon(Icons.storefront_rounded, size: 52, color: cs.primary),
                      const SizedBox(height: AppSpacing.sm),
                      Text(
                        'storeql.com',
                        style: Theme.of(context)
                            .textTheme
                            .headlineMedium
                            ?.copyWith(fontWeight: FontWeight.bold, color: cs.primary),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: AppSpacing.xs),
                      Text(
                        _isRegister ? l.createYourAccount : l.signInToContinue,
                        style: Theme.of(context)
                            .textTheme
                            .bodyMedium
                            ?.copyWith(color: cs.outline),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: AppSpacing.xl),
                      if (error != null) ...[
                        Container(
                          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md, vertical: AppSpacing.sm),
                          decoration: BoxDecoration(
                            color: cs.errorContainer,
                            borderRadius: AppRadius.chip,
                          ),
                          child: Text(error, style: TextStyle(color: cs.onErrorContainer)),
                        ),
                        if (requiredSlug != null) ...[
                          const SizedBox(height: AppSpacing.sm),
                          FilledButton.tonalIcon(
                            key: const Key('sso-continue'),
                            onPressed: isLoading ? null : () => _signInWithBusiness(slug: requiredSlug),
                            icon: const Icon(Icons.business_outlined),
                            label: Text(l.continueWithBusiness(requiredSlug)),
                          ),
                        ],
                        const SizedBox(height: AppSpacing.lg),
                      ],
                      TextFormField(
                        controller: _emailCtrl,
                        keyboardType: TextInputType.emailAddress,
                        textInputAction: TextInputAction.next,
                        decoration: InputDecoration(
                          labelText: l.fieldEmail,
                          prefixIcon: const Icon(Icons.email_outlined),
                        ),
                        validator: (v) =>
                            v == null || !v.contains('@') ? l.fieldEmailInvalid : null,
                      ),
                      if (_isRegister) ...[
                        const SizedBox(height: AppSpacing.lg),
                        TextFormField(
                          controller: _phoneCtrl,
                          keyboardType: TextInputType.phone,
                          textInputAction: TextInputAction.next,
                          decoration: InputDecoration(
                            labelText: l.fieldPhoneOptional,
                            prefixIcon: const Icon(Icons.phone_outlined),
                          ),
                        ),
                      ],
                      const SizedBox(height: AppSpacing.lg),
                      TextFormField(
                        // A fresh field per mode, so the other mode's refusal
                        // does not linger; the controller keeps what was typed.
                        key: ValueKey('password-$_isRegister'),
                        controller: _passwordCtrl,
                        obscureText: _obscure,
                        textInputAction: TextInputAction.done,
                        onFieldSubmitted: (_) => _submit(),
                        decoration: InputDecoration(
                          labelText: l.fieldPassword,
                          prefixIcon: const Icon(Icons.lock_outline),
                          // The published policy's rule, before it is typed —
                          // never learned only from a refusal.
                          helperText: _isRegister ? l.fieldPasswordTooShort(policy.minLength) : null,
                          helperMaxLines: 3,
                          errorMaxLines: 3,
                          suffixIcon: IconButton(
                            icon: Icon(_obscure ? Icons.visibility_off : Icons.visibility),
                            tooltip: _obscure ? l.showPassword : l.hidePassword,
                            onPressed: () => setState(() => _obscure = !_obscure),
                          ),
                        ),
                        // Signing in asks only for a password: the policy is for new ones.
                        validator: (v) => _isRegister
                            ? passwordLengthProblem(l, v, policy)
                            : (v == null || v.isEmpty ? l.fieldPasswordRequired : null),
                      ),
                      if (!_isRegister) ...[
                        Align(
                          alignment: AlignmentDirectional.centerEnd,
                          child: TextButton(
                            key: const Key('forgot-password'),
                            onPressed: isLoading ? null : () => context.go('/forgot-password'),
                            child: Text(l.forgotPassword),
                          ),
                        ),
                      ],
                      const SizedBox(height: AppSpacing.xl),
                      FilledButton(
                        onPressed: isLoading ? null : _submit,
                        child: isLoading
                            ? const SizedBox(
                                height: 20,
                                width: 20,
                                child: CircularProgressIndicator(strokeWidth: 2),
                              )
                            : Text(_isRegister ? l.actionCreateAccount : l.actionSignIn),
                      ),
                      if (!_isRegister && ssoBrowser.supported) ...[
                        const SizedBox(height: AppSpacing.md),
                        OutlinedButton.icon(
                          key: const Key('sso-start'),
                          onPressed: isLoading ? null : _signInWithBusiness,
                          icon: const Icon(Icons.business_outlined),
                          label: Text(l.signInWithBusiness),
                        ),
                      ],
                      const SizedBox(height: AppSpacing.sm),
                      TextButton(
                        onPressed: isLoading
                            ? null
                            : () => setState(() => _isRegister = !_isRegister),
                        child: Text(
                            _isRegister ? l.toggleHaveAccount : l.toggleNewHere),
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

  String _friendlyError(BuildContext context, Object e, PasswordPolicy policy) {
    final l = AppLocalizations.of(context);
    if (e is SsoError) return ssoMessage(e.code, l);
    final code = apiErrorCode(e);
    if (code != null && (code.startsWith('SSO_') || code == 'TENANT_INACTIVE')) {
      return ssoMessage(code, l);
    }
    // iam-svc's PasswordPolicy refusals, each in its own words. The length is
    // the published policy's own — fetched before the form was ever
    // submitted, never guessed from this one refusal's free text.
    switch (code) {
      case 'PASSWORD_TOO_SHORT':
        return l.fieldPasswordTooShort(policy.minLength);
      case 'PASSWORD_TOO_LONG':
        return l.fieldPasswordTooLong(policy.maxLength);
      case 'PASSWORD_IS_IDENTITY':
        return l.errPasswordIsIdentity;
      case 'PASSWORD_BREACHED':
        return l.errPasswordBreached;
      case 'INVALID_CREDENTIALS':
        return l.errInvalidCredentials;
    }
    final raw = e.toString();
    if (raw.contains('401') || raw.contains('INVALID_CREDENTIALS')) {
      return l.errInvalidCredentials;
    }
    if (raw.contains('409') || raw.contains('EMAIL_ALREADY_EXISTS')) {
      return l.errEmailExists;
    }
    if (raw.contains('SocketException') || raw.contains('Failed host lookup')) {
      return l.errNetwork;
    }
    return l.errGeneric;
  }
}

/// The business's sign-in name: what its owner chose when connecting its
/// identity provider, and told its staff.
class _BusinessNameDialog extends StatefulWidget {
  const _BusinessNameDialog();

  @override
  State<_BusinessNameDialog> createState() => _BusinessNameDialogState();
}

class _BusinessNameDialogState extends State<_BusinessNameDialog> {
  final _ctrl = TextEditingController();

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  void _go() => Navigator.of(context).pop(_ctrl.text.trim());

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return AlertDialog(
      title: Text(l.signInWithBusiness),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l.businessSignInNameHelp),
            const SizedBox(height: AppSpacing.md),
            TextField(
              key: const Key('sso-slug'),
              controller: _ctrl,
              autofocus: true,
              autocorrect: false,
              textInputAction: TextInputAction.go,
              onSubmitted: (_) => _go(),
              decoration: InputDecoration(
                labelText: l.fieldBusinessSignInName,
                hintText: l.fieldBusinessSignInNameHint,
                prefixIcon: const Icon(Icons.business_outlined),
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: Text(l.actionCancel)),
        FilledButton(key: const Key('sso-go'), onPressed: _go, child: Text(l.actionContinue)),
      ],
    );
  }
}
