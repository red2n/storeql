import 'package:flutter/material.dart';

import '../../core/spacing.dart';
import '../../core/theme.dart';

/// The bar that holds a screen's main action at the foot of a phone screen —
/// *Checkout*, *Charge*, *Complete sale*, *Add to basket* — where the thumb is.
///
/// Full width, on the page colour with a hairline above so it reads as fixed,
/// and clear of the Android gesture bar and the iPhone home indicator. Put it
/// under the scrolling content (`Column([Expanded(scroll), BottomActionBar])`
/// or `Scaffold.bottomNavigationBar`). From tablet width up, place the action
/// at the end of the content instead: a bar across a desktop window is a long
/// way from everything else.
///
/// [raised] lifts it on `shadow-2` instead of the hairline, for a bar that
/// floats over content scrolling under it — the cart's sticky checkout.
class BottomActionBar extends StatelessWidget {
  final Widget child;

  /// Floats on `shadow-2` ([AppShadow.level2]) rather than sitting on a
  /// hairline.
  final bool raised;

  const BottomActionBar({super.key, required this.child, this.raised = false});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: cs.surface,
        border: raised ? null : Border(top: BorderSide(color: cs.outlineVariant)),
        boxShadow: raised ? AppShadow.level2 : null,
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsetsDirectional.fromSTEB(
              AppSpacing.lg, AppSpacing.md, AppSpacing.lg, AppSpacing.md),
          child: child,
        ),
      ),
    );
  }
}
