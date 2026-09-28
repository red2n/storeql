class ApiConstants {
  /// Gateway base URL. Overridable at build time for a dockerized/remote deploy:
  ///   flutter build web --dart-define=STOREQL_API_BASE=https://api.example.com/api
  /// Defaults to the local docker gateway (host port 8090).
  static const String baseUrl = String.fromEnvironment(
    'STOREQL_API_BASE',
    defaultValue: 'http://localhost:8090/api',
  );

  // service-name segments (must match gateway's routable-services list)
  static const String iam = 'iam-svc';
  static const String tenant = 'tenant-svc';
  static const String product = 'product-svc';
  static const String inventory = 'inventory-svc';
  static const String pricing = 'pricing-svc';
  static const String order = 'order-svc';
  static const String payment = 'payment-svc';
  static const String purchase = 'purchase-svc';
  static const String customer = 'customer-svc';
  static const String reporting = 'reporting-svc';
  static const String notification = 'notification-svc';
  static const String cart = 'cart-svc';

  /// MQTT-over-WebSocket broker for the live admin push channel (see
  /// lib/features/admin/providers/live_alerts_provider.dart). Not behind the gateway — a
  /// persistent pub/sub socket needs a WS-capable path, so this points straight at the broker.
  /// Overridable at build time for a dockerized/remote deploy, same pattern as [baseUrl]:
  ///   flutter build web --dart-define=STOREQL_MQTT_WS_URL=wss://mqtt.example.com/mqtt
  static const String mqttWsUrl = String.fromEnvironment(
    'STOREQL_MQTT_WS_URL',
    defaultValue: 'ws://localhost:8083/mqtt',
  );
}

class StorageKeys {
  static const String accessToken = 'access_token';
  static const String refreshToken = 'refresh_token';

  /// The live business's tokens, kept aside while the owner is in the sandbox
  /// (22.8), so "Back to live" needs no sign-in.
  static const String liveAccessToken = 'live_access_token';
  static const String liveRefreshToken = 'live_refresh_token';

  /// Device-local history of orders placed from the (guest) storefront.
  static const String storefrontOrders = 'sf_orders';

  // Customer data-collection keys
  static const String sfGender = 'sf_gender';
  static const String sfGenderAsked = 'sf_gender_asked';
  static const String sfPrefs = 'sf_cust_prefs';
  static const String sfPrefsAsked = 'sf_prefs_asked';
  static const String sfSurveys = 'sf_surveys';
  static const String sfSurveyLastDate = 'sf_survey_last_date';
  static const String sfFeedback = 'sf_feedback';

  /// Last delivery address used at checkout — prefills the form on the next order.
  static const String sfSavedAddress = 'sf_saved_address';

  /// POS sales taken while the server was unreachable, waiting to be replayed.
  /// Device-local and never cleared on sign-out: this is money the server has not
  /// been told about yet, and it must outlive the cashier's shift.
  static const String posOfflineSales = 'pos_offline_sales';

  /// Where an unreadable offline queue is set aside so it is not overwritten.
  ///
  /// [posOfflineSales] is rewritten in full on the next sale, so "left on disk
  /// for a developer to recover" was only true until the cashier rang up one
  /// more item. The unparseable payload is moved here first, and this key is
  /// never written by the normal path.
  static const String posOfflineSalesCorrupt = 'pos_offline_sales_corrupt';

  /// Sales that were taken offline and have since reached the server, kept so a
  /// cashier holding an offline receipt — printed without its legal number —
  /// can find the number the server issued on replay. Device-local, capped.
  static const String posOfflineSynced = 'pos_offline_synced';

  /// This till's receipt printer (09.12): how receipts are produced here, the
  /// printer's address, the paper width. Device-local, like the printer is.
  static const String posPrinter = 'pos_printer';
}

/// Backend role codes (iam-svc seed). Do not invent client-only roles.
class UserRoles {
  static const String platformAdmin = 'PLATFORM_ADMIN';
  static const String owner = 'OWNER';
  static const String manager = 'MANAGER';
  static const String storekeeper = 'STOREKEEPER';
  static const String cashier = 'CASHIER';
  static const String customer = 'CUSTOMER';
}
