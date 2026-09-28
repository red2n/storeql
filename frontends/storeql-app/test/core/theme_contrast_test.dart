import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/storefront/storefront_widgets.dart';

// ---------------------------------------------------------------------------
// The theme's promises, measured (WCAG 2.1 contrast, 12.11): every "on" role
// reads on the role it sits on at 4.5:1 or better, in both brightnesses; body
// text reads on every surface container it can land on; outlines are at least
// 3:1 as a control boundary; and the status and POS-channel extensions keep the
// same rule. A palette that fails here fails a real reader.
// ---------------------------------------------------------------------------

double _luminance(Color c) {
  double channel(double v) =>
      v <= 0.03928 ? v / 12.92 : math.pow((v + 0.055) / 1.055, 2.4).toDouble();
  return 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b);
}

double contrast(Color a, Color b) {
  final la = _luminance(a), lb = _luminance(b);
  final lighter = math.max(la, lb), darker = math.min(la, lb);
  return (lighter + 0.05) / (darker + 0.05);
}

String _hex(Color c) => '#${c.toARGB32().toRadixString(16).padLeft(8, '0').substring(2)}';

void _atLeast(double min, Color fg, Color bg, String what) {
  final ratio = contrast(fg, bg);
  expect(ratio, greaterThanOrEqualTo(min),
      reason: '$what: ${_hex(fg)} on ${_hex(bg)} is ${ratio.toStringAsFixed(2)}:1, needs $min:1');
}

void _schemeReads(ColorScheme cs, String name) {
  final textPairs = <String, (Color, Color)>{
    'onPrimary/primary': (cs.onPrimary, cs.primary),
    'onPrimaryContainer/primaryContainer': (cs.onPrimaryContainer, cs.primaryContainer),
    'onSecondary/secondary': (cs.onSecondary, cs.secondary),
    'onSecondaryContainer/secondaryContainer': (cs.onSecondaryContainer, cs.secondaryContainer),
    'onTertiary/tertiary': (cs.onTertiary, cs.tertiary),
    'onTertiaryContainer/tertiaryContainer': (cs.onTertiaryContainer, cs.tertiaryContainer),
    'onError/error': (cs.onError, cs.error),
    'onErrorContainer/errorContainer': (cs.onErrorContainer, cs.errorContainer),
    'onInverseSurface/inverseSurface': (cs.onInverseSurface, cs.inverseSurface),
    'inversePrimary/inverseSurface': (cs.inversePrimary, cs.inverseSurface),
    'error/surface': (cs.error, cs.surface),
    'primary/surface': (cs.primary, cs.surface),
    'secondary/surface': (cs.secondary, cs.surface),
    'tertiary/surface': (cs.tertiary, cs.surface),
  };
  textPairs.forEach((what, pair) => _atLeast(4.5, pair.$1, pair.$2, '$name $what'));

  final surfaces = <String, Color>{
    'surface': cs.surface,
    'surfaceBright': cs.surfaceBright,
    'surfaceDim': cs.surfaceDim,
    'surfaceContainerLowest': cs.surfaceContainerLowest,
    'surfaceContainerLow': cs.surfaceContainerLow,
    'surfaceContainer': cs.surfaceContainer,
    'surfaceContainerHigh': cs.surfaceContainerHigh,
    'surfaceContainerHighest': cs.surfaceContainerHighest,
  };
  surfaces.forEach((where, bg) {
    _atLeast(4.5, cs.onSurface, bg, '$name onSurface on $where');
    _atLeast(4.5, cs.onSurfaceVariant, bg, '$name onSurfaceVariant on $where');
    _atLeast(3.0, cs.outline, bg, '$name outline on $where');
  });
}

void _statusReads(StatusColors s, ColorScheme cs, String name) {
  _atLeast(4.5, s.success, cs.surface, '$name success as text on surface');
  _atLeast(4.5, s.warning, cs.surface, '$name warning as text on surface');
  _atLeast(4.5, s.info, cs.surface, '$name info as text on surface');
  _atLeast(4.5, s.success, cs.surfaceContainerLowest, '$name success as text on a card');
  _atLeast(4.5, s.warning, cs.surfaceContainerLowest, '$name warning as text on a card');
  _atLeast(4.5, s.info, cs.surfaceContainerLowest, '$name info as text on a card');
  _atLeast(4.5, s.onSuccess, s.success, '$name onSuccess/success');
  _atLeast(4.5, s.onWarning, s.warning, '$name onWarning/warning');
  _atLeast(4.5, s.onInfo, s.info, '$name onInfo/info');
  _atLeast(4.5, s.onSuccessContainer, s.successContainer, '$name onSuccessContainer/successContainer');
  _atLeast(4.5, s.onWarningContainer, s.warningContainer, '$name onWarningContainer/warningContainer');
  _atLeast(4.5, s.onInfoContainer, s.infoContainer, '$name onInfoContainer/infoContainer');
}

void main() {
  group('light', () {
    test('every text role reads on the role it sits on', () => _schemeReads(AppTheme.lightScheme, 'light'));
    test('status colours read as text and as badges', () => _statusReads(StatusColors.light, AppTheme.lightScheme, 'light'));
    test('the POS channel accent reads', () => _atLeast(4.5, ChannelAccent.light.onColor, ChannelAccent.light.color, 'light POS accent'));
    test('the page is not pure white and body text is not pure black', () {
      expect(AppTheme.lightScheme.surface, isNot(const Color(0xFFFFFFFF)));
      expect(AppTheme.lightScheme.onSurface, isNot(const Color(0xFF000000)));
      expect(contrast(AppTheme.lightScheme.onSurface, AppTheme.lightScheme.surface), lessThan(18),
          reason: 'well past AA, short of the glare of black on white');
    });
  });

  group('dark', () {
    test('every text role reads on the role it sits on', () => _schemeReads(AppTheme.darkScheme, 'dark'));
    test('status colours read as text and as badges', () => _statusReads(StatusColors.dark, AppTheme.darkScheme, 'dark'));
    test('the POS channel accent reads', () => _atLeast(4.5, ChannelAccent.dark.onColor, ChannelAccent.dark.color, 'dark POS accent'));
    test('the page is not pure black', () {
      expect(AppTheme.darkScheme.surface, isNot(const Color(0xFF000000)));
    });
  });

  group('storefront', () {
    for (final (name, cs, brightness) in [
      ('light', AppTheme.lightScheme, Brightness.light),
      ('dark', AppTheme.darkScheme, Brightness.dark),
    ]) {
      // The offer banners (`_toneColors` in product_list_screen) and the sponsored tile and its
      // badge draw a flat container fill with its own on-container ink: every word and icon in it.
      test('$name: every offer banner tone and the sponsored tile read on their fill', () {
        final fills = <String, (Color, Color)>{
          'primary banner': (cs.onPrimaryContainer, cs.primaryContainer),
          'secondary banner': (cs.onSecondaryContainer, cs.secondaryContainer),
          'tertiary banner': (cs.onTertiaryContainer, cs.tertiaryContainer),
          'sponsored tile': (cs.onTertiaryContainer, cs.tertiaryContainer),
          'sponsored badge': (cs.onSecondaryContainer, cs.secondaryContainer),
        };
        fills.forEach((what, pair) => _atLeast(4.5, pair.$1, pair.$2, '$name $what'));
      });
      test('$name: every product placeholder reads its initials', () {
        final tones = ProductThumb.tones(brightness);
        expect(tones.length, 8);
        for (var i = 0; i < tones.length; i++) {
          _atLeast(4.5, tones[i].$2, tones[i].$1, '$name thumb tone $i');
        }
      });
    }
  });

  test('the built themes carry the schemes and the status extension', () {
    expect(AppTheme.light.colorScheme, AppTheme.lightScheme);
    expect(AppTheme.dark.colorScheme, AppTheme.darkScheme);
    expect(AppTheme.light.extension<StatusColors>(), StatusColors.light);
    expect(AppTheme.dark.extension<StatusColors>(), StatusColors.dark);
    expect(AppTheme.light.extension<ChannelAccent>(), isNull, reason: 'the accent is scoped to the POS shell');
    expect(AppTheme.applyPosAccent(AppTheme.dark).extension<ChannelAccent>(), ChannelAccent.dark);
  });
}
