import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart' show Intl;
import 'core/auth/sso.dart';
import 'core/l10n/app_locales.dart';
import 'core/router.dart';
import 'core/theme.dart';
import 'l10n/gen/app_localizations.dart';

/// Keeps the accessibility tree alive for the life of the app on the web; see [main].
SemanticsHandle? webSemanticsHandle;

void main() {
  // On the web Flutter builds no accessibility tree until someone finds its hidden "Enable
  // accessibility" button, so a screen reader meets a blank canvas. The storefront is a consumer
  // e-commerce service under the European Accessibility Act (12.11): build it from the first frame.
  if (kIsWeb) {
    WidgetsFlutterBinding.ensureInitialized();
    webSemanticsHandle = SemanticsBinding.instance.ensureSemantics();
  }
  // Back from a business's identity provider (20.x): read the answer out of the
  // address bar before the router takes the fragment for a route.
  ssoReturnAtLaunch = ssoBrowser.takeReturn();
  runApp(const ProviderScope(child: ShelfApp()));
}

class ShelfApp extends ConsumerWidget {
  const ShelfApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(routerProvider);
    return MaterialApp.router(
      title: 'storeql.com',
      theme: AppTheme.light,
      darkTheme: AppTheme.dark,
      themeMode: ThemeMode.system,
      // No country assumed: English resolves to the device's own region when
      // intl has it (AppLocales.englishRegions) and to plain English
      // otherwise; a device already in one of the app's other shipped
      // languages keeps it, whatever its region. Urdu/Arabic resolve to RTL
      // automatically.
      supportedLocales: AppLocales.supported,
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      localeResolutionCallback: (locale, supported) {
        final resolved = AppLocales.resolve(locale, supported);
        // AppFormat writes money, counts and dates in the language the app
        // runs in. Set here, before anything is formatted, so no stray
        // formatter can pin intl to the system's en_US instead.
        Intl.defaultLocale = AppLocales.intlName(resolved);
        return resolved;
      },
      routerConfig: router,
      debugShowCheckedModeBanner: false,
    );
  }
}
