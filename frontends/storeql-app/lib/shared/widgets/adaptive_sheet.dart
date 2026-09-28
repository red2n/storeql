import 'package:flutter/material.dart';

import '../../core/spacing.dart';

/// Opens a panel of content the way the window suits: a bottom sheet on a
/// phone (drag handle, clear of the notch and home indicator, lifts with the
/// keyboard, the thumb can reach it), a centred dialog up to [maxWidth] from
/// tablet width up, where a sheet stretched across a desktop screen reads
/// badly and sits far from the pointer.
///
/// [builder] returns the panel's content, without a Scaffold. It is scrolled
/// for you. Close it with `Navigator.pop(context, result)`.
Future<T?> showAdaptiveSheet<T>({
  required BuildContext context,
  required WidgetBuilder builder,
  String? title,
  double maxWidth = 560,
}) {
  Widget body(BuildContext ctx, EdgeInsetsGeometry padding) =>
      SingleChildScrollView(
        padding: padding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            if (title != null) ...[
              Text(title, style: Theme.of(ctx).textTheme.titleLarge),
              const SizedBox(height: AppSpacing.lg),
            ],
            builder(ctx),
          ],
        ),
      );

  if (context.isCompact) {
    return showModalBottomSheet<T>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      // useSafeArea keeps the top clear; the bottom is ours: lift with the
      // keyboard, and keep the last control above the home indicator.
      builder: (ctx) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(ctx).bottom),
        child: body(
          ctx,
          EdgeInsets.fromLTRB(AppSpacing.lg, 0, AppSpacing.lg,
              AppSpacing.xl + MediaQuery.paddingOf(ctx).bottom),
        ),
      ),
    );
  }
  return showDialog<T>(
    context: context,
    builder: (ctx) => Dialog(
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: maxWidth,
          maxHeight: MediaQuery.sizeOf(ctx).height * 0.85,
        ),
        child: body(ctx, const EdgeInsets.all(AppSpacing.xl)),
      ),
    ),
  );
}
