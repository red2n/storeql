import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

// dart:io is unavailable on web; all Platform.* calls are guarded by !kIsWeb
import 'dart:io' show Platform;
import 'spacing.dart';

typedef DeviceInfo = ({String platform, String formFactor});

class DeviceInfoCapture {
  DeviceInfoCapture._();

  /// Returns the platform name and form-factor inferred from screen width.
  /// [screenSize] should come from `MediaQuery.sizeOf(context)` at the call site
  /// so this class stays free of BuildContext.
  static DeviceInfo capture(Size screenSize) {
    final String platform;
    if (kIsWeb) {
      platform = 'web';
    } else if (Platform.isAndroid) {
      platform = 'android';
    } else if (Platform.isIOS) {
      platform = 'ios';
    } else if (Platform.isWindows) {
      platform = 'windows';
    } else if (Platform.isMacOS) {
      platform = 'macos';
    } else {
      platform = 'linux';
    }

    final String formFactor;
    final isNativeDesktop = !kIsWeb &&
        (Platform.isWindows || Platform.isMacOS || Platform.isLinux);
    if (isNativeDesktop) {
      formFactor = 'desktop';
    } else if (screenSize.width >= AppBreakpoints.medium) {
      formFactor = 'tablet';
    } else {
      formFactor = 'phone';
    }

    return (platform: platform, formFactor: formFactor);
  }
}
