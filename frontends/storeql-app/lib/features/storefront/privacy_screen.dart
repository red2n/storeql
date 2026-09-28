import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import 'privacy_rights.dart';
import 'storefront_providers.dart';
import 'storefront_shell.dart' show StorefrontAuthDialog;

/// Privacy and marketing: what the shop may send, and everything it holds.
///
/// Two rights on one screen because they are two halves of the same question.
/// The switches are PECR reg.22 consent — recorded with the wording shown here,
/// because UK GDPR art.7(1) makes the shop prove what was agreed to. The
/// download is art.20 portability, and it deliberately fails loudly rather than
/// handing over a partial file: half of somebody's data is a wrong answer, not
/// a small one.
class StorefrontPrivacyScreen extends ConsumerStatefulWidget {
  const StorefrontPrivacyScreen({super.key});

  @override
  ConsumerState<StorefrontPrivacyScreen> createState() =>
      _StorefrontPrivacyScreenState();
}

class _StorefrontPrivacyScreenState
    extends ConsumerState<StorefrontPrivacyScreen> {
  String? _exporting;

  Future<void> _download() async {
    setState(() => _exporting = 'working');
    final dio = ref.read(storefrontDioProvider);
    try {
      final resp =
          await dio.get('/${ApiConstants.customer}/customers/me/export');
      final pretty =
          const JsonEncoder.withIndent('  ').convert(resp.data['data']);
      await Clipboard.setData(ClipboardData(text: pretty));
      if (mounted) {
        setState(() => _exporting = null);
        showDialog<void>(
          context: context,
          builder: (_) => _ExportDialog(json: pretty),
        );
      }
    } catch (e) {
      if (mounted) {
        setState(() => _exporting = null);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(friendlyError(e,
                fallback:
                    'Your data could not be assembled just now. Nothing partial '
                    'has been sent — please try again shortly.')),
          ),
        );
      }
    }
  }

  void _refresh() {
    ref.invalidate(privacyNoticeProvider);
    ref.invalidate(myPrivacyProvider);
    ref.invalidate(marketingPreferencesProvider);
    ref.invalidate(myPrivacyRequestsProvider);
  }

  @override
  Widget build(BuildContext context) {
    final auth = ref.watch(storefrontAuthProvider);
    if (!auth.isSignedIn) {
      return _SignInFirst();
    }
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodyMedium
        ?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    // Marketing is asked once. When the consents carry the Marketing purpose its channels are
    // chosen beneath it there; only when they cannot be read do the channels stand on their own
    // here, so an opt-out is never hidden behind a failed load.
    final privacy = ref.watch(myPrivacyProvider);
    final underPurpose = privacy.value?.consents
            .any((c) => c.purpose == marketingPurpose) ??
        false;
    final standalone = !underPurpose && (privacy.hasValue || privacy.hasError);

    return RefreshIndicator(
      onRefresh: () async => _refresh(),
      // About 80 characters a line (WCAG 1.4.8), so the notice does not run 900px wide on the web.
      child: ContentBounds.reading(
        child: ListView(
          padding: context.pagePadding,
          children: [
            const PrivacyNoticeSection(),
            const SizedBox(height: AppSpacing.xl),
            const ConsentsSection(),
            if (standalone) ...[
              const SizedBox(height: AppSpacing.xl),
              Text('Marketing',
                  key: const Key('marketing-section'),
                  style: theme.textTheme.titleLarge),
              const SizedBox(height: AppSpacing.xs),
              Text(
                'You decide what this shop may send you. Nothing is on unless you '
                'turn it on, and you can turn it off again at any time — here, or '
                'from the link in any message we send.',
                style: muted,
              ),
              const SizedBox(height: AppSpacing.md),
              const Card(child: MarketingChannels()),
            ],
            const SizedBox(height: AppSpacing.xl),
            Text('Your data', style: theme.textTheme.titleLarge),
            const SizedBox(height: AppSpacing.xs),
            Text(
              'You can have a copy of everything this shop holds about you: your '
              'details, your addresses, your loyalty and store credit with their '
              'full history, what you have agreed to be sent, and every order you '
              'have placed here.',
              style: muted,
            ),
            const SizedBox(height: AppSpacing.md),
            FilledButton.icon(
              onPressed: _exporting == null ? _download : null,
              icon: _exporting == null
                  ? const Icon(Icons.download_outlined)
                  : const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2)),
              label: Text(_exporting == null
                  ? 'Download my data'
                  : 'Gathering your data…'),
            ),
            const SizedBox(height: AppSpacing.xl),
            const RequestsSection(),
          ],
        ),
      ),
    );
  }
}

/// The assembled export. Copied to the clipboard as soon as it arrives, because
/// a browser download from inside the app frame is not always permitted — and
/// the person asked for their data, not for a file-save dialog to work.
class _ExportDialog extends StatelessWidget {
  const _ExportDialog({required this.json});

  final String json;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Your data'),
      content: SizedBox(
        width: 560,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
                'Copied to your clipboard, and shown below. It is JSON, so any '
                'other service can read it.'),
            const SizedBox(height: 12),
            Flexible(
              child: SingleChildScrollView(
                child: SelectableText(
                  json,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                ),
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Clipboard.setData(ClipboardData(text: json)),
          child: const Text('Copy again'),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Close'),
        ),
      ],
    );
  }
}

class _SignInFirst extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.xl),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.privacy_tip_outlined, size: 48),
            const SizedBox(height: AppSpacing.md),
            const Text(
              'Sign in to see what this shop may send you, and to ask for a '
              'copy of your data.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            FilledButton(
              onPressed: () => showDialog<void>(
                  context: context,
                  builder: (_) => const StorefrontAuthDialog()),
              child: const Text('Sign in'),
            ),
          ],
        ),
      ),
    );
  }
}
