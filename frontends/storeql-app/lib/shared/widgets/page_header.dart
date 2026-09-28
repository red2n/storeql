import 'package:flutter/material.dart';

import '../../core/spacing.dart';

/// The heading at the top of a page: its title, an optional line under it, and
/// the page's own actions (*Add customer*, *Export CSV*, refresh).
///
/// Wide enough (480px and up) it is one row — text on the start side, actions
/// at the end. On a phone the text takes the whole width and the actions wrap
/// onto the line below it, so a long title never pushes a button off the
/// screen and a button never squeezes the title to a word per line. Text at
/// 130% and up stacks it below laptop width too.
///
/// It is inset by the page gutter (16 on phones, 24 from tablets up — the
/// window's, like `context.pageGutter`) and ends 16 above the content. Inset
/// the list or table below by the same `context.pageGutter`, so their edges
/// line up.
class PageHeader extends StatelessWidget {
  /// Narrower than this, the actions go under the title (a phone is 358 wide
  /// inside its gutters; a 640 form column is 592).
  static const double defaultStackBelow = 480;

  final String title;
  final String? subtitle;
  final List<Widget> actions;

  /// Overrides the default gutter-based padding.
  final EdgeInsetsGeometry? padding;

  /// The header width below which the actions go under the title. A page
  /// whose long subtitle would break mid-word beside its actions asks for
  /// more, e.g. [AppBreakpoints.medium].
  final double stackBelow;

  const PageHeader({
    super.key,
    required this.title,
    this.subtitle,
    this.actions = const [],
    this.padding,
    this.stackBelow = defaultStackBelow,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final gutter = context.pageGutter;
    return Padding(
      padding: padding ??
          EdgeInsetsDirectional.fromSTEB(gutter, gutter, gutter, AppSpacing.lg),
      child: LayoutBuilder(builder: (context, constraints) {
        // Stacked when the header itself is narrow — a phone, or a pane — and,
        // with text at 130% and up, below laptop width too, or the actions
        // squeeze the title. A header in a 640px form column stays one row.
        final largeText = MediaQuery.textScalerOf(context).scale(16) > 16 * 1.3;
        final stacked = constraints.maxWidth < stackBelow ||
            (largeText && constraints.maxWidth < AppBreakpoints.expanded);
        final text = Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              title,
              // The type scale follows the window, like the gutter.
              style: context.isCompact
                  ? theme.textTheme.headlineSmall
                  : theme.textTheme.headlineMedium,
            ),
            if (subtitle != null) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(
                subtitle!,
                style: theme.textTheme.bodyMedium
                    ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
              ),
            ],
          ],
        );
        if (actions.isEmpty) return text;
        if (stacked) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              text,
              const SizedBox(height: AppSpacing.md),
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.sm,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: actions,
              ),
            ],
          );
        }
        return Row(
          children: [
            Expanded(child: text),
            const SizedBox(width: AppSpacing.lg),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (var i = 0; i < actions.length; i++) ...[
                  if (i > 0) const SizedBox(width: AppSpacing.sm),
                  actions[i],
                ],
              ],
            ),
          ],
        );
      }),
    );
  }
}
