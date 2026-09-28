import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/auth/password_policy.dart';
import '../../core/constants.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../l10n/gen/app_localizations.dart';

// ---------------------------------------------------------------------------
// Forgot password — the request half (intent/password-reset.md, CONFIRMED).
//
// No sign-in, no session: this page asks for an email address and always
// reads the same answer, whatever the address — a known one, an unknown one,
// a suspended login, one throttled for asking too often. iam-svc does the same
// work either way (a token is minted and hashed either way), so there is
// nothing here for the wire to leak either. Only a request that never reached
// the server at all — no answer to read — says something different, so a
// person knows to try again rather than wait on an email that was never asked
// for.
// ---------------------------------------------------------------------------

class ForgotPasswordScreen extends ConsumerStatefulWidget {
  /// Who sent the shopper here — `storefront` from the storefront's own
  /// sign-in dialog (`?from=storefront`), so *Cancel* and *Sign in* lead back
  /// to the shop rather than to staff sign-in. Null for staff (the sign-in
  /// page's own link), which is also what a bare deep link or a refresh reads.
  final String? from;

  const ForgotPasswordScreen({super.key, this.from});

  @override
  ConsumerState<ForgotPasswordScreen> createState() =>
      _ForgotPasswordScreenState();
}

class _ForgotPasswordScreenState extends ConsumerState<ForgotPasswordScreen> {
  final _formKey = GlobalKey<FormState>();
  final _emailCtrl = TextEditingController();
  bool _sending = false;
  bool _sent = false;
  String? _networkError;

  @override
  void dispose() {
    _emailCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _sending = true;
      _networkError = null;
    });
    final language = Localizations.localeOf(context).languageCode;
    try {
      await ref
          .read(publicAuthDioProvider)
          .post(
            '/${ApiConstants.iam}/auth/password/forgot',
            data: {'email': _emailCtrl.text.trim(), 'language': language},
          );
      if (mounted) setState(() => _sent = true);
    } on DioException catch (e) {
      // The server answered — 202, or even a 400 the person's own retry can't
      // fix — so nothing more is learnable by looking any different: the same
      // reassuring words either way. Only a genuinely unreachable server (no
      // answer at all) says something else, so a retry looks worth trying.
      if (e.response != null) {
        if (mounted) setState(() => _sent = true);
      } else if (mounted) {
        final l = AppLocalizations.of(context);
        setState(() => _networkError = l.errNetwork);
      }
    } catch (_) {
      if (mounted) {
        final l = AppLocalizations.of(context);
        setState(() => _networkError = l.errNetwork);
      }
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    // A shopper who came from the storefront's own sign-in dialog leads back
    // there, never to staff sign-in they never asked for.
    final backRoute = widget.from == 'storefront' ? '/store' : '/login';
    return Scaffold(
      body: Center(
        child: SingleChildScrollView(
          padding: context.pagePadding,
          // The sign-in card's own width (login_screen.dart), not the wider
          // ContentBounds.form (640) this page came from — a person who
          // arrives here from a 420px sign-in card should not meet a page
          // that suddenly grows under them.
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Card(
              margin: EdgeInsets.zero,
              child: Padding(
                padding: const EdgeInsets.all(AppSpacing.xxl),
                child: _sent
                    ? Column(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          Icon(
                            Icons.mark_email_read_outlined,
                            size: 48,
                            color: cs.primary,
                          ),
                          const SizedBox(height: AppSpacing.md),
                          Text(
                            l.forgotPasswordSent,
                            key: const Key('forgot-result'),
                            style: text.bodyLarge,
                            textAlign: TextAlign.center,
                          ),
                          const SizedBox(height: AppSpacing.xl),
                          TextButton(
                            key: const Key('forgot-back-to-sign-in'),
                            onPressed: () => context.go(backRoute),
                            child: Text(l.actionSignIn),
                          ),
                        ],
                      )
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
                              l.forgotPasswordTitle,
                              style: text.headlineSmall,
                              textAlign: TextAlign.center,
                            ),
                            const SizedBox(height: AppSpacing.xs),
                            Text(
                              l.forgotPasswordIntro,
                              style: text.bodyMedium?.copyWith(
                                color: cs.outline,
                              ),
                              textAlign: TextAlign.center,
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            if (_networkError != null) ...[
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
                                  _networkError!,
                                  key: const Key('forgot-network-error'),
                                  style: TextStyle(color: cs.onErrorContainer),
                                ),
                              ),
                              const SizedBox(height: AppSpacing.lg),
                            ],
                            TextFormField(
                              key: const Key('forgot-email'),
                              controller: _emailCtrl,
                              autofocus: true,
                              keyboardType: TextInputType.emailAddress,
                              textInputAction: TextInputAction.done,
                              onFieldSubmitted: (_) => _submit(),
                              decoration: InputDecoration(
                                labelText: l.fieldEmail,
                                prefixIcon: const Icon(Icons.email_outlined),
                              ),
                              validator: (v) => v == null || !v.contains('@')
                                  ? l.fieldEmailInvalid
                                  : null,
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            FilledButton(
                              key: const Key('forgot-submit'),
                              onPressed: _sending ? null : _submit,
                              child: _sending
                                  ? const SizedBox(
                                      height: 20,
                                      width: 20,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                      ),
                                    )
                                  : Text(l.forgotPasswordSubmit),
                            ),
                            const SizedBox(height: AppSpacing.sm),
                            TextButton(
                              key: const Key('forgot-cancel'),
                              onPressed: _sending
                                  ? null
                                  : () => context.go(backRoute),
                              child: Text(l.actionCancel),
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
