import 'package:flutter/material.dart';

import '../../core/spacing.dart';

/// The full-area failure state: an icon on an error-container disc, the
/// message as ordinary text, and an optional *Retry*.
///
/// The message is what the person can act on, never a raw code, and it is set
/// in `onSurface` rather than red: red paragraphs are harsh to read, and the
/// disc already says something went wrong.
class ErrorView extends StatelessWidget {
  final String message;
  final VoidCallback? onRetry;

  const ErrorView({super.key, required this.message, this.onRetry});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.xl),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 64,
                height: 64,
                decoration: BoxDecoration(color: cs.errorContainer, shape: BoxShape.circle),
                alignment: Alignment.center,
                child: Icon(Icons.error_outline, size: 32, color: cs.onErrorContainer),
              ),
              const SizedBox(height: AppSpacing.lg),
              Text(
                message,
                textAlign: TextAlign.center,
                style: text.bodyLarge?.copyWith(color: cs.onSurface),
              ),
              if (onRetry != null) ...[
                const SizedBox(height: AppSpacing.lg),
                FilledButton.tonalIcon(
                  onPressed: onRetry,
                  icon: const Icon(Icons.refresh),
                  label: const Text('Retry'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
