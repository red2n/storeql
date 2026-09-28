import 'package:flutter/material.dart';

import '../../core/spacing.dart';

/// A list's filters (search, dropdowns, date range, chips) laid out for the
/// space there is.
///
/// From tablet width up the controls sit inline in a wrapping row. On a phone
/// they fold behind one *Filters* button — with the number in use — that opens
/// them in place, stacked full width, so the list itself starts on the first
/// screen instead of below five stacked dropdowns.
///
/// The controls stay in the page's own tree (no sheet or route), so their
/// state and `setState` work exactly as they do inline.
class AdaptiveFilters extends StatefulWidget {
  final List<Widget> children;

  /// How many filters differ from their default; shown on the phone button.
  final int activeCount;

  /// Resets every filter. Offered once any is in use.
  final VoidCallback? onClear;

  const AdaptiveFilters({
    super.key,
    required this.children,
    this.activeCount = 0,
    this.onClear,
  });

  @override
  State<AdaptiveFilters> createState() => _AdaptiveFiltersState();
}

class _AdaptiveFiltersState extends State<AdaptiveFilters> {
  bool _open = false;

  @override
  Widget build(BuildContext context) {
    final clear = widget.onClear != null && widget.activeCount > 0
        ? TextButton(onPressed: widget.onClear, child: const Text('Clear filters'))
        : null;

    return LayoutBuilder(builder: (context, constraints) {
      if (AppBreakpoints.classOf(constraints.maxWidth) != WindowClass.compact) {
        return Wrap(
          spacing: AppSpacing.md,
          runSpacing: AppSpacing.md,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [...widget.children, ?clear],
        );
      }
      final count = widget.activeCount;
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          // The button at the start and Clear filters at the end; Clear moves
          // under the button when the two do not fit one line (a narrow phone,
          // large text) rather than overflowing it.
          Wrap(
            alignment: WrapAlignment.spaceBetween,
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.xs,
            children: [
              OutlinedButton.icon(
                onPressed: () => setState(() => _open = !_open),
                icon: Icon(_open ? Icons.expand_less : Icons.tune, size: 18),
                label: Text(count > 0 ? 'Filters · $count' : 'Filters'),
              ),
              ?clear,
            ],
          ),
          AnimatedSize(
            duration: MediaQuery.disableAnimationsOf(context)
                ? Duration.zero
                : const Duration(milliseconds: 200),
            curve: Curves.easeOutCubic,
            alignment: AlignmentDirectional.topCenter,
            child: _open
                ? Padding(
                    padding: const EdgeInsetsDirectional.only(top: AppSpacing.md),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        for (var i = 0; i < widget.children.length; i++) ...[
                          if (i > 0) const SizedBox(height: AppSpacing.md),
                          widget.children[i],
                        ],
                      ],
                    ),
                  )
                : const SizedBox(width: double.infinity),
          ),
        ],
      );
    });
  }
}
