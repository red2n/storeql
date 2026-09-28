import 'package:flutter/material.dart';

import '../../core/spacing.dart';

/// One command for an app bar: what it says, its icon, and what it does.
class AdaptiveAction {
  final String label;
  final IconData icon;

  /// Null disables it (greyed out in place, or in the overflow menu).
  final VoidCallback? onPressed;

  /// Shown as a labelled text button where there is room — keep this for the
  /// one or two commands people look for by name (*Returns*, *Clock out*).
  /// Otherwise an icon button, with [label] as its tooltip.
  final bool showLabel;

  /// Stays on the app bar on phones. Everything else moves into the ⋮ menu
  /// there, so the title keeps its room. Keep this to one, at most two.
  final bool keepOnCompact;

  /// Finds the command in tests, on the bar and in the ⋮ menu alike.
  final Key? key;

  const AdaptiveAction({
    required this.label,
    required this.icon,
    required this.onPressed,
    this.showLabel = false,
    this.keepOnCompact = false,
    this.key,
  });
}

/// App-bar actions that fit the window.
///
/// From tablet width up every action shows, the [AdaptiveAction.showLabel]
/// ones as labelled text buttons. On a phone only the
/// [AdaptiveAction.keepOnCompact] ones stay, as icon buttons, and the rest go
/// into one ⋮ overflow menu — six actions on a 390px bar would push the title
/// out and the last icons off the screen.
///
/// Put it in `AppBar.actions` (or `AdaptiveNavShell.actions`) as the only
/// entry: `actions: [AdaptiveActions(actions: [...])]`.
class AdaptiveActions extends StatelessWidget {
  final List<AdaptiveAction> actions;

  /// Tooltip and accessible name of the ⋮ button.
  final String overflowTooltip;

  const AdaptiveActions({
    super.key,
    required this.actions,
    this.overflowTooltip = 'More actions',
  });

  @override
  Widget build(BuildContext context) {
    final compact = context.isCompact;
    final onBar =
        compact ? actions.where((a) => a.keepOnCompact).toList() : actions;
    final inMenu = compact
        ? actions.where((a) => !a.keepOnCompact).toList()
        : const <AdaptiveAction>[];

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (final a in onBar)
          if (a.showLabel && !compact)
            TextButton.icon(
              key: a.key,
              onPressed: a.onPressed,
              icon: Icon(a.icon, size: 18),
              label: Text(a.label),
            )
          else
            IconButton(
              key: a.key,
              tooltip: a.label,
              icon: Icon(a.icon),
              onPressed: a.onPressed,
            ),
        if (inMenu.isNotEmpty)
          PopupMenuButton<int>(
            tooltip: overflowTooltip,
            icon: const Icon(Icons.more_vert),
            onSelected: (i) => inMenu[i].onPressed?.call(),
            itemBuilder: (_) => [
              for (var i = 0; i < inMenu.length; i++)
                PopupMenuItem<int>(
                  key: inMenu[i].key,
                  value: i,
                  enabled: inMenu[i].onPressed != null,
                  child: Row(
                    children: [
                      Icon(inMenu[i].icon, size: 20),
                      const SizedBox(width: AppSpacing.md),
                      Flexible(child: Text(inMenu[i].label)),
                    ],
                  ),
                ),
            ],
          ),
      ],
    );
  }
}
