import 'package:flutter/material.dart';

import '../../core/theme.dart';

/// Grey placeholders shaped like the content that is loading: a product grid,
/// an order list, a dashboard's cards.
///
/// Use it for a first load whose layout is known, so the page doesn't jump
/// from a centred spinner to a full screen. Keep `LoadingView` for waits with
/// no known shape.
///
/// Build the shape from [SkeletonBlock]s and [SkeletonLine]s laid out like the
/// real content, and wrap the whole of it in one [Skeleton]: it draws a soft
/// highlight across every block at once, and holds still when the device asks
/// for less motion. It reads to a screen reader as [label], never as the
/// blocks inside it.
///
/// A skeleton is a first-load state only. The fetch behind it decides when it
/// gives way — to the content, or to an `ErrorView` once the request fails or
/// times out — and a skeleton should rarely be on screen for more than a
/// second or two.
class Skeleton extends StatefulWidget {
  /// The loading shape, built from [SkeletonBlock]s and [SkeletonLine]s.
  final Widget child;

  /// What a screen reader says in its place, e.g. *Loading products*.
  final String label;

  const Skeleton({super.key, required this.child, this.label = 'Loading'});

  /// One sweep of the highlight across the blocks.
  static const Duration period = Duration(milliseconds: 1500);

  @override
  State<Skeleton> createState() => _SkeletonState();
}

class _SkeletonState extends State<Skeleton>
    with SingleTickerProviderStateMixin {
  late final AnimationController _shimmer =
      AnimationController(vsync: this, duration: Skeleton.period);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Still for a shopper who asked for less motion (WCAG 2.3.3); moving
    // otherwise, and picked up again if the setting changes while it shows.
    if (MediaQuery.disableAnimationsOf(context)) {
      _shimmer.stop();
    } else if (!_shimmer.isAnimating) {
      _shimmer.repeat();
    }
  }

  @override
  void dispose() {
    _shimmer.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final still = MediaQuery.disableAnimationsOf(context);
    final blocks = ExcludeSemantics(child: widget.child);
    return Semantics(
      container: true,
      label: widget.label,
      child: still
          ? blocks
          : RepaintBoundary(
              child: AnimatedBuilder(
                animation: _shimmer,
                child: blocks,
                builder: (context, child) => ShaderMask(
                  // Paints only where a block is (srcATop): the gap between
                  // blocks stays the page.
                  blendMode: BlendMode.srcATop,
                  shaderCallback: (bounds) => LinearGradient(
                    colors: [
                      cs.surfaceContainerHighest,
                      cs.surfaceBright,
                      cs.surfaceContainerHighest,
                    ],
                    stops: const [0.35, 0.5, 0.65],
                    transform: _Sweep(_shimmer.value),
                  ).createShader(bounds),
                  child: child,
                ),
              ),
            ),
    );
  }
}

/// Moves the highlight from beyond the start edge to beyond the end edge as
/// [progress] runs from 0 to 1, whichever way the text reads.
class _Sweep extends GradientTransform {
  final double progress;
  const _Sweep(this.progress);

  @override
  Matrix4? transform(Rect bounds, {TextDirection? textDirection}) {
    final travel = bounds.width * (progress * 2 - 1);
    final dx = textDirection == TextDirection.rtl ? -travel : travel;
    return Matrix4.translationValues(dx, 0, 0);
  }
}

/// A placeholder block in `surface-container-highest`: a picture, a badge, a
/// button. Give it the size of what it stands in for.
class SkeletonBlock extends StatelessWidget {
  final double? width;
  final double? height;
  final BorderRadius borderRadius;

  const SkeletonBlock({
    super.key,
    this.width,
    this.height,
    this.borderRadius = AppRadius.badge,
  });

  @override
  Widget build(BuildContext context) => Container(
        width: width,
        height: height,
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: borderRadius,
        ),
      );
}

/// A placeholder for one line of text: as tall as the text it stands in for
/// at the shopper's text size, and [widthFactor] of the width it is given.
class SkeletonLine extends StatelessWidget {
  final double widthFactor;

  /// The size of the text it stands in for; it grows with the text scale.
  final double fontSize;

  const SkeletonLine({super.key, this.widthFactor = 1, this.fontSize = 14});

  @override
  Widget build(BuildContext context) => FractionallySizedBox(
        alignment: AlignmentDirectional.centerStart,
        widthFactor: widthFactor,
        child: SkeletonBlock(
          height: MediaQuery.textScalerOf(context).scale(fontSize),
        ),
      );
}
