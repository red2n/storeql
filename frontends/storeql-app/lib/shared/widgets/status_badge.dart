import 'package:flutter/material.dart';

import '../../core/theme.dart';
import '../util/status_labels.dart';

export '../util/status_labels.dart'
    show
        StatusTone,
        humanizeCode,
        orderStatusLabel,
        orderStatusTone,
        channelLabel,
        batchMaterialStatuses,
        materialStatusLabel,
        materialStatusTone,
        tillPhoneChoices,
        tillPhoneLabel;

/// A status in words on its tone's container colour: *Pending*, *Part
/// fulfilled*, *Overdue*. The one badge for every list, card and table, so a
/// state looks the same wherever it appears.
///
/// Pass words, never a raw code — `orderStatusLabel`, `humanizeCode` or the
/// screen's own map. The label never relies on colour alone: the words carry
/// the meaning, the tone only helps scanning. 24px tall (taller with large
/// text), 12/600, 4px corners; it ellipsizes rather than overflowing a narrow
/// row.
class StatusBadge extends StatelessWidget {
  final String label;
  final StatusTone tone;
  final IconData? icon;

  const StatusBadge(
    this.label, {
    super.key,
    this.tone = StatusTone.neutral,
    this.icon,
  });

  /// An order's status, in the words and tone used everywhere.
  factory StatusBadge.order(String? status, {Key? key}) => StatusBadge(
        orderStatusLabel(status),
        key: key,
        tone: orderStatusTone(status),
      );

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = toneColors(context, tone);
    return Container(
      // At least 24 tall; it grows with large text rather than clipping it.
      constraints: const BoxConstraints(minHeight: 24),
      padding: EdgeInsetsDirectional.fromSTEB(icon == null ? 8 : 6, 2, 8, 2),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: const BorderRadius.all(Radius.circular(AppRadius.xs)),
      ),
      // One Text (the icon rides in a WidgetSpan), so the badge shrink-wraps in
      // an unbounded Row and ellipsizes in a bounded one — it never throws a
      // flex error or an overflow stripe, wherever a screen puts it.
      child: Center(
        widthFactor: 1,
        heightFactor: 1,
        child: Text.rich(
          TextSpan(children: [
            if (icon != null)
              WidgetSpan(
                alignment: PlaceholderAlignment.middle,
                child: Padding(
                  padding: const EdgeInsetsDirectional.only(end: 4),
                  child: Icon(icon, size: 14, color: fg),
                ),
              ),
            TextSpan(text: label),
          ]),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            fontSize: 12,
            height: 16 / 12,
            fontWeight: FontWeight.w600,
            letterSpacing: 0.3,
            color: fg,
          ),
        ),
      ),
    );
  }

  /// The container colour and the text colour on it for [tone].
  static (Color, Color) toneColors(BuildContext context, StatusTone tone) {
    final cs = Theme.of(context).colorScheme;
    final s = context.status;
    return switch (tone) {
      StatusTone.neutral => (cs.surfaceContainerHigh, cs.onSurfaceVariant),
      StatusTone.info => (s.infoContainer, s.onInfoContainer),
      StatusTone.success => (s.successContainer, s.onSuccessContainer),
      StatusTone.warning => (s.warningContainer, s.onWarningContainer),
      StatusTone.error => (cs.errorContainer, cs.onErrorContainer),
      StatusTone.accent => (cs.primaryContainer, cs.onPrimaryContainer),
    };
  }
}
