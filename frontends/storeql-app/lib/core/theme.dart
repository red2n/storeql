import 'package:flutter/foundation.dart' show defaultTargetPlatform, TargetPlatform;
import 'package:flutter/material.dart';

import 'spacing.dart';

// ─────────────────────────────────────────────────────────────────────────────
// StoreQL theme — "warm & quiet".
//
// Every Material 3 colour role is written out explicitly for both brightnesses,
// so nothing is left to ColorScheme.fromSeed's algorithm and the design system
// (tokens.json) can mirror this file one-to-one.
//
// Eye comfort rules this palette follows:
//  • No pure white page and no pure black page. Light sits on warm off-white
//    (#FAF8F3), dark on warm charcoal (#161513). Body text is ~13.5:1 (light) and
//    ~15:1 (dark) — well past WCAG AA, short of the 21:1 glare of black-on-white.
//  • Dark mode desaturates every hue (amber, sage, sky) so nothing vibrates.
//  • Every text role meets 4.5:1 on every surface container it can land on, in
//    both themes. That includes `outline`: the app uses it for secondary text in
//    about 200 places, so it is text-safe too (and well past 3:1 as a boundary).
//  • Status is never hue alone — pair it with a word or an icon.
// ─────────────────────────────────────────────────────────────────────────────

/// Corner radii. Use these instead of `BorderRadius.circular(<magic number>)`.
class AppRadius {
  AppRadius._();

  static const double xs = 4; // badges, tags
  static const double sm = 8; // chips, small tiles
  static const double md = 12; // inputs, list tiles, thumbnails
  static const double lg = 16; // cards
  static const double xl = 24; // sheets, dialogs
  static const double full = 999; // pills, stadium buttons

  static const BorderRadius card = BorderRadius.all(Radius.circular(lg));
  static const BorderRadius input = BorderRadius.all(Radius.circular(md));
  static const BorderRadius chip = BorderRadius.all(Radius.circular(sm));
  static const BorderRadius badge = BorderRadius.all(Radius.circular(xs));
  static const BorderRadius pill = BorderRadius.all(Radius.circular(full));
  static const BorderRadius sheet = BorderRadius.vertical(top: Radius.circular(xl));
}

/// The design system's shadows (`shadow-1…3`). StoreQL is flat: cards use a
/// hairline, not a shadow; a shadow is only for something that floats.
class AppShadow {
  AppShadow._();

  /// `shadow-1`: an elevated button.
  static const List<BoxShadow> level1 = [
    BoxShadow(offset: Offset(0, 1), blurRadius: 2, color: Color(0x1F000000)),
    BoxShadow(offset: Offset(0, 1), blurRadius: 3, spreadRadius: 1, color: Color(0x0F000000)),
  ];

  /// `shadow-2`: a FAB, the sticky checkout bar.
  static const List<BoxShadow> level2 = [
    BoxShadow(offset: Offset(0, 2), blurRadius: 6, spreadRadius: 2, color: Color(0x14000000)),
    BoxShadow(offset: Offset(0, 1), blurRadius: 2, color: Color(0x1F000000)),
  ];

  /// `shadow-3`: snackbars, menus.
  static const List<BoxShadow> level3 = [
    BoxShadow(offset: Offset(0, 4), blurRadius: 8, spreadRadius: 3, color: Color(0x14000000)),
    BoxShadow(offset: Offset(0, 1), blurRadius: 3, color: Color(0x24000000)),
  ];
}

/// Named colour palette. Private to this file; widgets read colours from the
/// theme (`Theme.of(context).colorScheme`, `context.status`,
/// `context.channelAccent`), never from here.
class _Light {
  static const primary = Color(0xFF3A3833); // warm charcoal
  static const onPrimary = Color(0xFFFFFFFF);
  static const primaryContainer = Color(0xFFFCE7A6); // soft amber
  static const onPrimaryContainer = Color(0xFF3F2D00);
  static const secondary = Color(0xFF276A3F); // forest
  static const onSecondary = Color(0xFFFFFFFF);
  static const secondaryContainer = Color(0xFFD5EBD9); // sage
  static const onSecondaryContainer = Color(0xFF123D22);
  static const tertiary = Color(0xFF3D5A80); // slate blue
  static const onTertiary = Color(0xFFFFFFFF);
  static const tertiaryContainer = Color(0xFFD6E3F5);
  static const onTertiaryContainer = Color(0xFF1E3552);
  static const error = Color(0xFFB3261E);
  static const onError = Color(0xFFFFFFFF);
  static const errorContainer = Color(0xFFF9DEDC);
  static const onErrorContainer = Color(0xFF601410);
  static const surface = Color(0xFFFAF8F3); // warm off-white page
  static const onSurface = Color(0xFF2B2A27);
  static const onSurfaceVariant = Color(0xFF5F5C55);
  static const surfaceContainerLowest = Color(0xFFFFFFFF); // cards
  static const surfaceContainerLow = Color(0xFFF5F3ED); // inputs, search
  static const surfaceContainer = Color(0xFFF0EDE6); // nav bar
  static const surfaceContainerHigh = Color(0xFFEAE7DF); // menus
  static const surfaceContainerHighest = Color(0xFFE4E1D8); // skeletons
  static const surfaceDim = Color(0xFFDEDBD2);
  static const surfaceBright = Color(0xFFFAF8F3);
  static const outline = Color(0xFF6A665E); // doubles as secondary text: 4.6–5.7:1 on every text surface
  static const outlineVariant = Color(0xFFD7D3C9);
  static const inverseSurface = Color(0xFF32302C);
  static const onInverseSurface = Color(0xFFF4F1EA);
  static const inversePrimary = Color(0xFFF2D27A);
}

class _Dark {
  static const primary = Color(0xFFF2D27A); // muted amber
  static const onPrimary = Color(0xFF3A2F00);
  static const primaryContainer = Color(0xFF54460F);
  static const onPrimaryContainer = Color(0xFFFCE7A6);
  static const secondary = Color(0xFF8FCFA3);
  static const onSecondary = Color(0xFF0B3A1E);
  static const secondaryContainer = Color(0xFF1F4D30);
  static const onSecondaryContainer = Color(0xFFC7EBD1);
  static const tertiary = Color(0xFFA9C5E8);
  static const onTertiary = Color(0xFF0E2A48);
  static const tertiaryContainer = Color(0xFF2A4566);
  static const onTertiaryContainer = Color(0xFFD3E4F8);
  static const error = Color(0xFFF2B8B5);
  static const onError = Color(0xFF601410);
  static const errorContainer = Color(0xFF8C1D18);
  static const onErrorContainer = Color(0xFFF9DEDC);
  static const surface = Color(0xFF161513); // warm charcoal page, not black
  static const onSurface = Color(0xFFECE9E2); // warm off-white text, not #FFF
  static const onSurfaceVariant = Color(0xFFBDB8AD);
  static const surfaceContainerLowest = Color(0xFF100F0E);
  static const surfaceContainerLow = Color(0xFF1C1B18); // cards
  static const surfaceContainer = Color(0xFF211F1C); // nav bar
  static const surfaceContainerHigh = Color(0xFF2B2A26); // inputs, menus
  static const surfaceContainerHighest = Color(0xFF363430); // skeletons
  static const surfaceDim = Color(0xFF161513);
  static const surfaceBright = Color(0xFF3C3A35);
  static const outline = Color(0xFFA39E94); // doubles as secondary text: 4.7–7.2:1 on every surface
  static const outlineVariant = Color(0xFF45423C);
  static const inverseSurface = Color(0xFFECE9E2);
  static const onInverseSurface = Color(0xFF32302C);
  static const inversePrimary = Color(0xFF6F5A12);
}

/// Semantic status colours as a [ThemeExtension], so widgets read them from the
/// active theme (`context.status.success`) and they adapt to light/dark
/// automatically. Each status has a strong tone (text/icons on surfaces) and a
/// container pair (tinted banners, badges, chips). Always pair the colour with
/// text or an icon — never convey status by hue alone (colour-blind users).
@immutable
class StatusColors extends ThemeExtension<StatusColors> {
  final Color success;
  final Color onSuccess;
  final Color successContainer;
  final Color onSuccessContainer;
  final Color warning;
  final Color onWarning;
  final Color warningContainer;
  final Color onWarningContainer;
  final Color info;
  final Color onInfo;
  final Color infoContainer;
  final Color onInfoContainer;

  const StatusColors({
    required this.success,
    required this.onSuccess,
    required this.successContainer,
    required this.onSuccessContainer,
    required this.warning,
    required this.onWarning,
    required this.warningContainer,
    required this.onWarningContainer,
    required this.info,
    required this.onInfo,
    required this.infoContainer,
    required this.onInfoContainer,
  });

  static const light = StatusColors(
    success: Color(0xFF276A3F),
    onSuccess: Color(0xFFFFFFFF),
    successContainer: Color(0xFFD5EBD9),
    onSuccessContainer: Color(0xFF123D22),
    warning: Color(0xFF7A5600),
    onWarning: Color(0xFFFFFFFF),
    warningContainer: Color(0xFFFCEBC0),
    onWarningContainer: Color(0xFF3F2D00),
    info: Color(0xFF3D5A80),
    onInfo: Color(0xFFFFFFFF),
    infoContainer: Color(0xFFD6E3F5),
    onInfoContainer: Color(0xFF1E3552),
  );
  static const dark = StatusColors(
    success: Color(0xFF8FCFA3),
    onSuccess: Color(0xFF0B3A1E),
    successContainer: Color(0xFF1F4D30),
    onSuccessContainer: Color(0xFFC7EBD1),
    warning: Color(0xFFF2C45C),
    onWarning: Color(0xFF3F2D00),
    warningContainer: Color(0xFF4F3B00),
    onWarningContainer: Color(0xFFFCE7A6),
    info: Color(0xFFA9C5E8),
    onInfo: Color(0xFF0E2A48),
    infoContainer: Color(0xFF2A4566),
    onInfoContainer: Color(0xFFD3E4F8),
  );

  @override
  StatusColors copyWith({
    Color? success,
    Color? onSuccess,
    Color? successContainer,
    Color? onSuccessContainer,
    Color? warning,
    Color? onWarning,
    Color? warningContainer,
    Color? onWarningContainer,
    Color? info,
    Color? onInfo,
    Color? infoContainer,
    Color? onInfoContainer,
  }) =>
      StatusColors(
        success: success ?? this.success,
        onSuccess: onSuccess ?? this.onSuccess,
        successContainer: successContainer ?? this.successContainer,
        onSuccessContainer: onSuccessContainer ?? this.onSuccessContainer,
        warning: warning ?? this.warning,
        onWarning: onWarning ?? this.onWarning,
        warningContainer: warningContainer ?? this.warningContainer,
        onWarningContainer: onWarningContainer ?? this.onWarningContainer,
        info: info ?? this.info,
        onInfo: onInfo ?? this.onInfo,
        infoContainer: infoContainer ?? this.infoContainer,
        onInfoContainer: onInfoContainer ?? this.onInfoContainer,
      );

  @override
  StatusColors lerp(ThemeExtension<StatusColors>? other, double t) {
    if (other is! StatusColors) return this;
    return StatusColors(
      success: Color.lerp(success, other.success, t)!,
      onSuccess: Color.lerp(onSuccess, other.onSuccess, t)!,
      successContainer: Color.lerp(successContainer, other.successContainer, t)!,
      onSuccessContainer: Color.lerp(onSuccessContainer, other.onSuccessContainer, t)!,
      warning: Color.lerp(warning, other.warning, t)!,
      onWarning: Color.lerp(onWarning, other.onWarning, t)!,
      warningContainer: Color.lerp(warningContainer, other.warningContainer, t)!,
      onWarningContainer: Color.lerp(onWarningContainer, other.onWarningContainer, t)!,
      info: Color.lerp(info, other.info, t)!,
      onInfo: Color.lerp(onInfo, other.onInfo, t)!,
      infoContainer: Color.lerp(infoContainer, other.infoContainer, t)!,
      onInfoContainer: Color.lerp(onInfoContainer, other.onInfoContainer, t)!,
    );
  }
}

extension StatusColorsX on BuildContext {
  StatusColors get status =>
      Theme.of(this).extension<StatusColors>() ?? StatusColors.light;
}

/// The POS channel's accent (amber), expressed through the theme system instead
/// of a raw [Color] constant, so anything themed off it — app bar, buttons, the
/// bottom nav indicator — stays in sync and carries a correct contrasting
/// foreground. [PosShell] installs this via [AppTheme.applyPosAccent]; screens
/// read it with `context.channelAccent`.
@immutable
class ChannelAccent extends ThemeExtension<ChannelAccent> {
  final Color color;
  final Color onColor;

  const ChannelAccent({required this.color, required this.onColor});

  /// Soft amber in light; a deep amber container in dark, so a till left on all
  /// day in a dim shop doesn't glare.
  static const light = ChannelAccent(color: Color(0xFFFCE7A6), onColor: Color(0xFF3A3833));
  static const dark = ChannelAccent(color: Color(0xFF54460F), onColor: Color(0xFFFCE7A6));

  @override
  ChannelAccent copyWith({Color? color, Color? onColor}) => ChannelAccent(
        color: color ?? this.color,
        onColor: onColor ?? this.onColor,
      );

  @override
  ChannelAccent lerp(ThemeExtension<ChannelAccent>? other, double t) {
    if (other is! ChannelAccent) return this;
    return ChannelAccent(
      color: Color.lerp(color, other.color, t)!,
      onColor: Color.lerp(onColor, other.onColor, t)!,
    );
  }
}

extension ChannelAccentX on BuildContext {
  /// Falls back to the ambient primary/onPrimary outside a channel-themed shell.
  ChannelAccent get channelAccent =>
      Theme.of(this).extension<ChannelAccent>() ??
      ChannelAccent(
        color: Theme.of(this).colorScheme.primary,
        onColor: Theme.of(this).colorScheme.onPrimary,
      );
}

class AppTheme {
  AppTheme._();

  // Kept for existing callers; prefer `context.channelAccent`, which adapts to dark mode.
  static const Color posAccent = Color(0xFFFCE7A6);
  static const Color posAccentForeground = Color(0xFF3A3833);

  // ── Platform ──────────────────────────────────────────────────────────────
  // defaultTargetPlatform is the OS on native builds and the browser's OS on the
  // web, so "desktop" below also covers a desktop browser (mouse + keyboard),
  // while a phone browser gets the touch values.

  static bool get _isApple =>
      defaultTargetPlatform == TargetPlatform.iOS || defaultTargetPlatform == TargetPlatform.macOS;

  static bool get _isDesktop =>
      defaultTargetPlatform == TargetPlatform.macOS ||
      defaultTargetPlatform == TargetPlatform.windows ||
      defaultTargetPlatform == TargetPlatform.linux;

  static const ColorScheme lightScheme = ColorScheme(
    brightness: Brightness.light,
    primary: _Light.primary,
    onPrimary: _Light.onPrimary,
    primaryContainer: _Light.primaryContainer,
    onPrimaryContainer: _Light.onPrimaryContainer,
    secondary: _Light.secondary,
    onSecondary: _Light.onSecondary,
    secondaryContainer: _Light.secondaryContainer,
    onSecondaryContainer: _Light.onSecondaryContainer,
    tertiary: _Light.tertiary,
    onTertiary: _Light.onTertiary,
    tertiaryContainer: _Light.tertiaryContainer,
    onTertiaryContainer: _Light.onTertiaryContainer,
    error: _Light.error,
    onError: _Light.onError,
    errorContainer: _Light.errorContainer,
    onErrorContainer: _Light.onErrorContainer,
    surface: _Light.surface,
    onSurface: _Light.onSurface,
    onSurfaceVariant: _Light.onSurfaceVariant,
    surfaceContainerLowest: _Light.surfaceContainerLowest,
    surfaceContainerLow: _Light.surfaceContainerLow,
    surfaceContainer: _Light.surfaceContainer,
    surfaceContainerHigh: _Light.surfaceContainerHigh,
    surfaceContainerHighest: _Light.surfaceContainerHighest,
    surfaceDim: _Light.surfaceDim,
    surfaceBright: _Light.surfaceBright,
    outline: _Light.outline,
    outlineVariant: _Light.outlineVariant,
    inverseSurface: _Light.inverseSurface,
    onInverseSurface: _Light.onInverseSurface,
    inversePrimary: _Light.inversePrimary,
    shadow: Color(0xFF000000),
    scrim: Color(0xFF000000),
    surfaceTint: _Light.primary,
  );

  static const ColorScheme darkScheme = ColorScheme(
    brightness: Brightness.dark,
    primary: _Dark.primary,
    onPrimary: _Dark.onPrimary,
    primaryContainer: _Dark.primaryContainer,
    onPrimaryContainer: _Dark.onPrimaryContainer,
    secondary: _Dark.secondary,
    onSecondary: _Dark.onSecondary,
    secondaryContainer: _Dark.secondaryContainer,
    onSecondaryContainer: _Dark.onSecondaryContainer,
    tertiary: _Dark.tertiary,
    onTertiary: _Dark.onTertiary,
    tertiaryContainer: _Dark.tertiaryContainer,
    onTertiaryContainer: _Dark.onTertiaryContainer,
    error: _Dark.error,
    onError: _Dark.onError,
    errorContainer: _Dark.errorContainer,
    onErrorContainer: _Dark.onErrorContainer,
    surface: _Dark.surface,
    onSurface: _Dark.onSurface,
    onSurfaceVariant: _Dark.onSurfaceVariant,
    surfaceContainerLowest: _Dark.surfaceContainerLowest,
    surfaceContainerLow: _Dark.surfaceContainerLow,
    surfaceContainer: _Dark.surfaceContainer,
    surfaceContainerHigh: _Dark.surfaceContainerHigh,
    surfaceContainerHighest: _Dark.surfaceContainerHighest,
    surfaceDim: _Dark.surfaceDim,
    surfaceBright: _Dark.surfaceBright,
    outline: _Dark.outline,
    outlineVariant: _Dark.outlineVariant,
    inverseSurface: _Dark.inverseSurface,
    onInverseSurface: _Dark.onInverseSurface,
    inversePrimary: _Dark.inversePrimary,
    shadow: Color(0xFF000000),
    scrim: Color(0xFF000000),
    surfaceTint: _Dark.primary,
  );

  static ThemeData get light => _build(lightScheme, StatusColors.light,
      cardColor: _Light.surfaceContainerLowest, fieldFill: _Light.surfaceContainerLow);

  static ThemeData get dark => _build(darkScheme, StatusColors.dark,
      cardColor: _Dark.surfaceContainerLow, fieldFill: _Dark.surfaceContainerHigh);

  /// Material 3 type scale on the platform font (Roboto on Android/web, SF on
  /// iOS — kept deliberately: the app ships Urdu, Arabic, Bengali, Gujarati and
  /// Punjabi, which the platform fonts cover and a bundled Latin face would not).
  /// Headings get a heavier weight and slightly tighter tracking for a crisper,
  /// modern read; body sizes stay at M3 defaults for legibility.
  static TextTheme _textTheme(TextTheme base) => base.copyWith(
        displayLarge: base.displayLarge?.copyWith(fontWeight: FontWeight.w600, letterSpacing: -0.5),
        displayMedium: base.displayMedium?.copyWith(fontWeight: FontWeight.w600, letterSpacing: -0.5),
        displaySmall: base.displaySmall?.copyWith(fontWeight: FontWeight.w600, letterSpacing: -0.25),
        headlineLarge: base.headlineLarge?.copyWith(fontWeight: FontWeight.w600, letterSpacing: -0.25),
        headlineMedium: base.headlineMedium?.copyWith(fontWeight: FontWeight.w600, letterSpacing: -0.25),
        headlineSmall: base.headlineSmall?.copyWith(fontWeight: FontWeight.w600),
        titleLarge: base.titleLarge?.copyWith(fontWeight: FontWeight.w600),
        titleMedium: base.titleMedium?.copyWith(fontWeight: FontWeight.w600),
        labelLarge: base.labelLarge?.copyWith(fontWeight: FontWeight.w600),
      );

  static ThemeData _build(
    ColorScheme cs,
    StatusColors status, {
    required Color cardColor,
    required Color fieldFill,
  }) {
    final base = ThemeData(useMaterial3: true, colorScheme: cs);
    final text = _textTheme(base.textTheme);
    const stadium = StadiumBorder();
    const buttonPadding = EdgeInsets.symmetric(horizontal: AppSpacing.xl, vertical: AppSpacing.md);
    // 48dp everywhere. On desktop (mouse) the compact visual density takes 8dp off,
    // so buttons land at 40dp there — never below it.
    const buttonMinSize = Size(64, 48);

    return base.copyWith(
      scaffoldBackgroundColor: cs.surface,
      canvasColor: cs.surface,
      textTheme: text,
      // Standard on phones and tablets; compact with a mouse (desktop web, admin):
      // buttons 48 → 40dp, list rows and fields 8dp shorter.
      visualDensity: _isDesktop ? VisualDensity.compact : VisualDensity.standard,
      // Visible keyboard focus on the web and desktop.
      focusColor: cs.primary.withValues(alpha: 0.12),
      hoverColor: cs.onSurface.withValues(alpha: 0.06),
      textSelectionTheme: TextSelectionThemeData(
        cursorColor: cs.primary,
        selectionColor: cs.primary.withValues(alpha: 0.24),
        selectionHandleColor: cs.primary,
      ),
      // Desktop browsers get a slim, warm scrollbar instead of the grey default.
      scrollbarTheme: ScrollbarThemeData(
        thickness: const WidgetStatePropertyAll(8),
        radius: const Radius.circular(AppRadius.full),
        thumbColor: WidgetStatePropertyAll(cs.outline.withValues(alpha: 0.5)),
      ),

      // Surface-coloured app bar — calm, content-first. It picks up a faint tint
      // once content scrolls under it. The POS shell overrides it with the amber
      // channel accent (see applyPosAccent).
      appBarTheme: AppBarTheme(
        // Centred on iOS/macOS (native convention), start-aligned on Android and Windows/Linux.
        centerTitle: _isApple,
        elevation: 0,
        scrolledUnderElevation: 2,
        backgroundColor: cs.surface,
        foregroundColor: cs.onSurface,
        surfaceTintColor: cs.surfaceTint,
        titleTextStyle: text.titleLarge?.copyWith(color: cs.onSurface),
      ),

      // Flat cards lifted off the page by fill and a hairline border, not shadow.
      cardTheme: CardThemeData(
        elevation: 0,
        margin: EdgeInsets.zero,
        color: cardColor,
        surfaceTintColor: Colors.transparent,
        clipBehavior: Clip.antiAlias,
        shape: RoundedRectangleBorder(
          borderRadius: AppRadius.card,
          side: BorderSide(color: cs.outlineVariant),
        ),
      ),

      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          shape: stadium,
          padding: buttonPadding,
          minimumSize: buttonMinSize,
          textStyle: text.labelLarge,
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          shape: stadium,
          padding: buttonPadding,
          minimumSize: buttonMinSize,
          textStyle: text.labelLarge,
          elevation: 1,
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          shape: stadium,
          padding: buttonPadding,
          minimumSize: buttonMinSize,
          textStyle: text.labelLarge,
          side: BorderSide(color: cs.outline),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          shape: stadium,
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg, vertical: AppSpacing.md),
          minimumSize: const Size(48, 48),
          textStyle: text.labelLarge,
        ),
      ),
      // Icon buttons keep standard density, so they stay 40dp with a mouse too.
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(visualDensity: VisualDensity.standard),
      ),
      segmentedButtonTheme: SegmentedButtonThemeData(
        style: SegmentedButton.styleFrom(
          minimumSize: const Size(48, 48),
          textStyle: text.labelLarge,
        ),
      ),
      floatingActionButtonTheme: FloatingActionButtonThemeData(
        elevation: 2,
        highlightElevation: 4,
        backgroundColor: cs.primaryContainer,
        foregroundColor: cs.onPrimaryContainer,
        shape: const RoundedRectangleBorder(borderRadius: AppRadius.card),
      ),

      // Filled fields with a 1px outline at rest — a field's only visible edge, so it must
      // reach 3:1 (WCAG 1.4.11): 5.1:1 (light) / 5.4:1 (dark) against the fill — and 2px primary on focus.
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: fieldFill,
        contentPadding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg, vertical: 14),
        hintStyle: text.bodyLarge?.copyWith(color: cs.onSurfaceVariant),
        helperStyle: text.bodySmall?.copyWith(color: cs.onSurfaceVariant),
        prefixIconColor: cs.onSurfaceVariant,
        suffixIconColor: cs.onSurfaceVariant,
        border: const OutlineInputBorder(borderRadius: AppRadius.input, borderSide: BorderSide.none),
        enabledBorder: OutlineInputBorder(
          borderRadius: AppRadius.input,
          borderSide: BorderSide(color: cs.outline),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: AppRadius.input,
          borderSide: BorderSide(color: cs.primary, width: 2),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: AppRadius.input,
          borderSide: BorderSide(color: cs.error),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: AppRadius.input,
          borderSide: BorderSide(color: cs.error, width: 2),
        ),
        disabledBorder: OutlineInputBorder(
          borderRadius: AppRadius.input,
          borderSide: BorderSide(color: cs.outlineVariant.withValues(alpha: 0.5)),
        ),
      ),

      searchBarTheme: SearchBarThemeData(
        elevation: const WidgetStatePropertyAll(0),
        backgroundColor: WidgetStatePropertyAll(fieldFill),
        surfaceTintColor: const WidgetStatePropertyAll(Colors.transparent),
        side: WidgetStatePropertyAll(BorderSide(color: cs.outlineVariant)),
        constraints: const BoxConstraints(minHeight: 48),
        padding: const WidgetStatePropertyAll(EdgeInsets.symmetric(horizontal: AppSpacing.lg)),
        hintStyle: WidgetStatePropertyAll(text.bodyLarge?.copyWith(color: cs.onSurfaceVariant)),
      ),

      chipTheme: ChipThemeData(
        shape: RoundedRectangleBorder(
          borderRadius: AppRadius.chip,
          side: BorderSide(color: cs.outlineVariant),
        ),
        side: BorderSide(color: cs.outlineVariant),
        backgroundColor: cs.surface,
        selectedColor: cs.secondaryContainer,
        checkmarkColor: cs.onSecondaryContainer,
        labelStyle: text.labelLarge?.copyWith(color: cs.onSurface),
        secondaryLabelStyle: text.labelLarge?.copyWith(color: cs.onSecondaryContainer),
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.sm, vertical: AppSpacing.xs),
      ),

      badgeTheme: BadgeThemeData(
        backgroundColor: cs.error,
        textColor: cs.onError,
      ),

      navigationBarTheme: NavigationBarThemeData(
        elevation: 0,
        height: 72,
        backgroundColor: cs.surfaceContainer,
        surfaceTintColor: Colors.transparent,
        indicatorColor: cs.secondaryContainer,
        labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
        labelTextStyle: WidgetStateProperty.resolveWith((states) => text.labelMedium?.copyWith(
              color: states.contains(WidgetState.selected) ? cs.onSurface : cs.onSurfaceVariant,
              fontWeight: states.contains(WidgetState.selected) ? FontWeight.w600 : FontWeight.w500,
            )),
        iconTheme: WidgetStateProperty.resolveWith((states) => IconThemeData(
              color: states.contains(WidgetState.selected) ? cs.onSecondaryContainer : cs.onSurfaceVariant,
            )),
      ),
      navigationRailTheme: NavigationRailThemeData(
        backgroundColor: cs.surface,
        indicatorColor: cs.secondaryContainer,
        selectedIconTheme: IconThemeData(color: cs.onSecondaryContainer),
        unselectedIconTheme: IconThemeData(color: cs.onSurfaceVariant),
        selectedLabelTextStyle: text.labelMedium?.copyWith(color: cs.onSurface, fontWeight: FontWeight.w600),
        unselectedLabelTextStyle: text.labelMedium?.copyWith(color: cs.onSurfaceVariant),
      ),
      navigationDrawerTheme: NavigationDrawerThemeData(
        backgroundColor: cs.surfaceContainerLow,
        surfaceTintColor: Colors.transparent,
        indicatorColor: cs.secondaryContainer,
      ),

      listTileTheme: ListTileThemeData(
        shape: const RoundedRectangleBorder(borderRadius: AppRadius.input),
        contentPadding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
        iconColor: cs.onSurfaceVariant,
        titleTextStyle: text.bodyLarge?.copyWith(color: cs.onSurface),
        subtitleTextStyle: text.bodyMedium?.copyWith(color: cs.onSurfaceVariant),
      ),

      // Scrim behind dialogs, sheets and the drawer: the `scrim` role at 32%
      // (Material 3), not Flutter's default 54% black, which reads as a
      // blackout in light mode.
      dialogTheme: DialogThemeData(
        backgroundColor: cs.surfaceContainerHigh,
        surfaceTintColor: Colors.transparent,
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(AppRadius.xl))),
        titleTextStyle: text.headlineSmall?.copyWith(color: cs.onSurface),
        barrierColor: cs.scrim.withValues(alpha: 0.32),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: cs.surfaceContainerLow,
        surfaceTintColor: Colors.transparent,
        showDragHandle: true,
        dragHandleColor: cs.outline,
        modalBarrierColor: cs.scrim.withValues(alpha: 0.32),
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadius.xl)),
        ),
      ),
      drawerTheme: DrawerThemeData(scrimColor: cs.scrim.withValues(alpha: 0.32)),
      popupMenuTheme: PopupMenuThemeData(
        color: cs.surfaceContainerHigh,
        surfaceTintColor: Colors.transparent,
        shape: const RoundedRectangleBorder(borderRadius: AppRadius.input),
      ),
      menuTheme: MenuThemeData(
        style: MenuStyle(
          backgroundColor: WidgetStatePropertyAll(cs.surfaceContainerHigh),
          surfaceTintColor: const WidgetStatePropertyAll(Colors.transparent),
          shape: const WidgetStatePropertyAll(RoundedRectangleBorder(borderRadius: AppRadius.input)),
        ),
      ),

      // Floating, rounded snackbars on the inverse surface.
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: cs.inverseSurface,
        contentTextStyle: text.bodyMedium?.copyWith(color: cs.onInverseSurface),
        actionTextColor: cs.inversePrimary,
        shape: const RoundedRectangleBorder(borderRadius: AppRadius.input),
        // Phones: full width minus a 16dp inset. Desktop browsers: a 440dp toast,
        // not a bar stretched across a 1920px window.
        insetPadding: _isDesktop ? null : const EdgeInsets.all(AppSpacing.lg),
        width: _isDesktop ? 440 : null,
      ),
      bannerTheme: MaterialBannerThemeData(
        backgroundColor: cs.surfaceContainerLow,
        contentTextStyle: text.bodyMedium?.copyWith(color: cs.onSurface),
        dividerColor: cs.outlineVariant,
      ),
      tooltipTheme: TooltipThemeData(
        decoration: BoxDecoration(
          color: cs.inverseSurface,
          borderRadius: const BorderRadius.all(Radius.circular(AppRadius.sm)),
        ),
        textStyle: text.bodySmall?.copyWith(color: cs.onInverseSurface),
        waitDuration: const Duration(milliseconds: 400),
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(
        color: cs.primary,
        linearTrackColor: cs.surfaceContainerHighest,
        circularTrackColor: Colors.transparent,
      ),
      dividerTheme: DividerThemeData(color: cs.outlineVariant, thickness: 1, space: 1),
      tabBarTheme: TabBarThemeData(
        labelColor: cs.onSurface,
        unselectedLabelColor: cs.onSurfaceVariant,
        indicatorColor: cs.primary,
        dividerColor: cs.outlineVariant,
        labelStyle: text.titleSmall?.copyWith(fontWeight: FontWeight.w600),
        unselectedLabelStyle: text.titleSmall,
      ),
      extensions: [
        status,
        // No ChannelAccent here on purpose: outside POS, context.channelAccent
        // falls back to primary/onPrimary.
      ],
    );
  }

  /// Applied by [PosShell] to scope the amber channel accent (app bar, primary
  /// action buttons, bottom-nav indicator) to the POS subtree only — without
  /// touching `colorScheme.primary`, which POS screens still read for
  /// unrelated meanings (e.g. a settled-payment checkmark). Brightness-aware:
  /// a deep amber container in dark mode instead of a bright amber bar.
  static ThemeData applyPosAccent(ThemeData base) {
    final accent = base.brightness == Brightness.dark ? ChannelAccent.dark : ChannelAccent.light;
    return base.copyWith(
      appBarTheme: base.appBarTheme.copyWith(
        backgroundColor: accent.color,
        foregroundColor: accent.onColor,
        titleTextStyle: base.appBarTheme.titleTextStyle?.copyWith(color: accent.onColor),
        iconTheme: IconThemeData(color: accent.onColor),
        actionsIconTheme: IconThemeData(color: accent.onColor),
      ),
      navigationBarTheme: base.navigationBarTheme.copyWith(
        indicatorColor: accent.color,
        iconTheme: WidgetStateProperty.resolveWith((states) => IconThemeData(
              color: states.contains(WidgetState.selected)
                  ? accent.onColor
                  : base.colorScheme.onSurfaceVariant,
            )),
      ),
      navigationRailTheme: base.navigationRailTheme.copyWith(
        indicatorColor: accent.color,
        selectedIconTheme: IconThemeData(color: accent.onColor),
      ),
      extensions: [
        ...base.extensions.values,
        accent,
      ],
    );
  }
}
