/// Barrel for the POS shell's screens, imported as a single `deferred as`
/// library from `core/router.dart` so a storefront/admin/platform visit never
/// downloads the point-of-sale code.
library;

export 'cart_screen.dart';
export 'tender_screen.dart';
export 'cash_screen.dart';
export 'offline_queue_screen.dart';
export 'customer_display.dart';
export 'returns_screen.dart';
