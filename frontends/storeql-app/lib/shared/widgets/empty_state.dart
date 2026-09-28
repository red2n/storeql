import 'package:flutter/material.dart';

import '../../core/spacing.dart';

/// What a list shows when there is nothing in it: an icon, what that means in
/// a few words, and — when there is one — the next step as a button.
///
/// Name the situation (*No purchase orders yet*, *No customers match
/// “Leeds”*), not the mechanism (*Empty list*, *0 results*). Never a blank
/// area or a bare "No data".
///
/// [icon] defaults to an outlined inbox so a plain `EmptyState(title: …)`
/// still reads as a state. [detail] is the earlier name for [message] and is
/// accepted as a synonym; pass one or the other, not both.
class EmptyState extends StatelessWidget {
  final IconData icon;
  final String title;
  final String? message;
  final Widget? action;

  const EmptyState({
    super.key,
    this.icon = Icons.inbox_outlined,
    required this.title,
    String? message,
    String? detail,
    this.action,
  })  : assert(message == null || detail == null,
            'Pass message or detail, not both.'),
        message = message ?? detail;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.xl),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 72,
                height: 72,
                decoration: BoxDecoration(
                  color: cs.surfaceContainerHigh,
                  shape: BoxShape.circle,
                ),
                child: Icon(icon, size: 32, color: cs.onSurfaceVariant),
              ),
              const SizedBox(height: AppSpacing.lg),
              Text(
                title,
                textAlign: TextAlign.center,
                style: theme.textTheme.titleMedium,
              ),
              if (message != null) ...[
                const SizedBox(height: AppSpacing.xs),
                Text(
                  message!,
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodyMedium
                      ?.copyWith(color: cs.onSurfaceVariant),
                ),
              ],
              if (action != null) ...[
                const SizedBox(height: AppSpacing.lg),
                action!,
              ],
            ],
          ),
        ),
      ),
    );
  }
}
