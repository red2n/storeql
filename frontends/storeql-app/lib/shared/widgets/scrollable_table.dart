import 'package:flutter/material.dart';

/// Holds a wide table — usually a [DataTable] — so every column and every row
/// can always be reached.
///
/// It scrolls sideways when the columns need more room than there is (a
/// tablet between 600 and 900px, a narrow browser window) and, inside a
/// bounded parent such as an `Expanded`, up and down too; both scrollbars sit
/// on the edges of the visible area, not at the far end of the table. A table
/// narrower than the space is stretched to fill it, so it lines up with the
/// page. Inside a parent that already scrolls vertically, only the sideways
/// scroll is added.
class ScrollableTable extends StatefulWidget {
  final Widget child;

  const ScrollableTable({super.key, required this.child});

  @override
  State<ScrollableTable> createState() => _ScrollableTableState();
}

class _ScrollableTableState extends State<ScrollableTable> {
  final _horizontal = ScrollController();
  final _vertical = ScrollController();

  @override
  void dispose() {
    _horizontal.dispose();
    _vertical.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // The scrollbars are drawn here, once each, at the visible edges. Turn off
    // the ones desktop platforms add to every scroll view automatically, or
    // the inner vertical view gets a second thumb at the table's far end.
    final noAutoScrollbars =
        ScrollConfiguration.of(context).copyWith(scrollbars: false);
    return LayoutBuilder(builder: (context, constraints) {
      final width = constraints.maxWidth;
      if (!constraints.hasBoundedHeight) {
        return Scrollbar(
          controller: _horizontal,
          child: ScrollConfiguration(
            behavior: noAutoScrollbars,
            child: SingleChildScrollView(
              controller: _horizontal,
              scrollDirection: Axis.horizontal,
              primary: false,
              child: ConstrainedBox(
                constraints: BoxConstraints(minWidth: width),
                child: widget.child,
              ),
            ),
          ),
        );
      }
      // The vertical scrollbar listens one level down (depth 1) so it can sit
      // at the right edge of the visible area while the vertical scroll view
      // lives inside the horizontal one.
      return Scrollbar(
        controller: _vertical,
        notificationPredicate: (n) => n.depth == 1,
        child: Scrollbar(
          controller: _horizontal,
          child: ScrollConfiguration(
            behavior: noAutoScrollbars,
            child: SingleChildScrollView(
              controller: _horizontal,
              scrollDirection: Axis.horizontal,
              primary: false,
              child: ConstrainedBox(
                constraints: BoxConstraints(minWidth: width),
                child: SizedBox(
                  height: constraints.maxHeight,
                  child: SingleChildScrollView(
                    controller: _vertical,
                    primary: false,
                    child: widget.child,
                  ),
                ),
              ),
            ),
          ),
        ),
      );
    });
  }
}
