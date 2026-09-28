import 'package:flutter/widgets.dart';

/// A 4-pt spacing scale. Use these tokens instead of magic numbers in
/// `EdgeInsets` / `SizedBox`, so spacing stays consistent and tunable in one
/// place (e.g. `EdgeInsets.all(AppSpacing.lg)`, `SizedBox(height: AppSpacing.md)`).
class AppSpacing {
  AppSpacing._();

  static const double xs = 4;
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 24;
  static const double xxl = 32;

  // Common ready-made insets.
  static const EdgeInsets pagePadding = EdgeInsets.all(xl);
  static const EdgeInsets cardPadding = EdgeInsets.all(lg);

  /// Bottom padding for a scrolling list under a FloatingActionButton, so the
  /// last row's text and trailing controls stay clear of the button.
  static const double fabClearance = 88;
}

/// A square or directional gap, e.g. `Gap(AppSpacing.md)`.
class Gap extends StatelessWidget {
  final double size;
  const Gap(this.size, {super.key});

  @override
  Widget build(BuildContext context) => SizedBox(width: size, height: size);
}

/// Material 3 window-size classes, plus the width where [AdaptiveNavShell]
/// switches to a rail. One set of numbers for phones, tablets, and the web.
class AppBreakpoints {
  AppBreakpoints._();

  /// Below this: phones (portrait). Single column, bottom bar or drawer.
  static const double medium = 600;

  /// [AdaptiveNavShell] shows a persistent NavigationRail at or above this.
  static const double rail = 800;

  /// At or above: tablets landscape, small laptops.
  static const double expanded = 840;

  /// At or above: desktop browsers. The rail starts extended (labelled).
  static const double large = 1200;

  /// Reading/form content never grows wider than this on big screens
  /// (checkout, product detail, account, settings) — see [ContentBounds].
  static const double contentMaxWidth = 1200;
  static const double formMaxWidth = 640;

  /// The widest a line of 14px body text runs on a page of running text (a
  /// statement, a notice): about 80 characters, the most WCAG 1.4.8 allows.
  /// The column is this plus the page gutters — see [ContentBounds.reading].
  static const double readingMeasure = 552;

  /// The class of a width. Content should pass the width it actually has — a
  /// LayoutBuilder's `maxWidth`, which is narrower than the window when a rail
  /// or a side panel is showing — rather than the window's.
  static WindowClass classOf(double width) {
    if (width >= large) return WindowClass.large;
    if (width >= expanded) return WindowClass.expanded;
    if (width >= medium) return WindowClass.medium;
    return WindowClass.compact;
  }
}

/// compact: phones (portrait) · medium: tablets portrait, narrow browser
/// windows · expanded: tablets landscape, laptops · large: desktop browsers.
enum WindowClass { compact, medium, expanded, large }

extension WindowClassOrder on WindowClass {
  /// `context.windowClass >= WindowClass.expanded` — this class or a wider one.
  bool operator >=(WindowClass other) => index >= other.index;
}

extension WindowClassX on BuildContext {
  /// The window's class — for app chrome (navigation, app-bar actions). For
  /// what goes inside the page, use [AppBreakpoints.classOf] on the width a
  /// LayoutBuilder gives you.
  WindowClass get windowClass =>
      AppBreakpoints.classOf(MediaQuery.sizeOf(this).width);

  /// A phone-sized window.
  bool get isCompact => windowClass == WindowClass.compact;

  /// The page's side inset: 16 on phones, 24 from tablets up. Lists and tables
  /// use it too, so they line up under the page title.
  double get pageGutter => isCompact ? AppSpacing.lg : AppSpacing.xl;

  /// Page padding that grows with the window: 16 on phones, 24 from tablets up.
  EdgeInsets get pagePadding => EdgeInsets.all(pageGutter);
}

/// Centres [child] and caps its width, so a form or a detail page doesn't
/// stretch edge to edge in a desktop browser. A no-op on phones.
class ContentBounds extends StatelessWidget {
  final Widget child;
  final double maxWidth;

  /// Whether the page's gutters are added to [maxWidth] on each side, so the
  /// content inside them is [maxWidth] wide.
  final bool _plusGutters;

  const ContentBounds({
    super.key,
    required this.child,
    this.maxWidth = AppBreakpoints.contentMaxWidth,
  }) : _plusGutters = false;

  /// For single-column forms (sign-in, address, checkout details).
  const ContentBounds.form({super.key, required this.child})
      : maxWidth = AppBreakpoints.formMaxWidth,
        _plusGutters = false;

  /// For a page of running text (an accessibility statement, a privacy
  /// notice): [AppBreakpoints.readingMeasure] of text, about 80 characters a
  /// line, plus the page gutters. Give the child `context.pagePadding`.
  const ContentBounds.reading({super.key, required this.child})
      : maxWidth = AppBreakpoints.readingMeasure,
        _plusGutters = true;

  @override
  Widget build(BuildContext context) => Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxWidth:
                _plusGutters ? maxWidth + 2 * context.pageGutter : maxWidth,
          ),
          child: child,
        ),
      );
}
