import 'package:flutter/foundation.dart';

/// How people mostly drive the app on this platform. On the web this is the
/// browser's OS, so a phone browser counts as touch and a desktop browser as
/// mouse and keyboard — the same rule `AppTheme` uses for density.
///
/// Mouse-and-keyboard platforms get keyboard shortcuts and their hints, hover
/// states and visible scrollbars; touch platforms get pull-to-refresh, bottom
/// sheets and haptics. Never use this to change colours or type: those are the
/// same everywhere.
bool get pointerFirst => switch (defaultTargetPlatform) {
      TargetPlatform.macOS ||
      TargetPlatform.windows ||
      TargetPlatform.linux =>
        true,
      _ => false,
    };

/// iOS and macOS, where shortcuts use ⌘ rather than Ctrl.
bool get applePlatform =>
    defaultTargetPlatform == TargetPlatform.iOS ||
    defaultTargetPlatform == TargetPlatform.macOS;

/// How a shortcut with the platform's command key reads here: `⌘K` on Apple
/// platforms, `Ctrl+K` elsewhere.
String shortcutLabel(String key) => applePlatform ? '⌘$key' : 'Ctrl+$key';
