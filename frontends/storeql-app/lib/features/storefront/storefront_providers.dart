import 'unit_price.dart';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/storage/app_storage.dart';
import '../../shared/util/image_byte_cache.dart';

/// Dev seam for "subdomain → tenant". In production the gateway derives the
/// tenant from the storefront's domain; here we read `?tenant=<id>` from the URL
/// (put it BEFORE the hash, e.g. `/?tenant=<id>#/store/products`) and send it as
/// the `X-Storefront-Tenant` header. Overridable at runtime via the picker.
final storefrontTenantProvider = StateProvider<String?>((ref) {
  final t = Uri.base.queryParameters['tenant'];
  return (t != null && t.isNotEmpty) ? t : null;
});

/// Store used for online order fulfilment. `?store=<id>` override; otherwise
/// resolved from the first store returned by the tenant's store list (set by
/// ProductListScreen.initState after the stores API responds).
final storefrontStoreProvider = StateProvider<String>((ref) {
  final s = Uri.base.queryParameters['store'];
  return (s != null && s.isNotEmpty) ? s : '';
});

/// Whether to show only in-stock products on the storefront product list.
final storefrontInStockOnlyProvider = StateProvider<bool>((ref) => false);

/// A tokenless Dio that stamps the storefront tenant header on every request.
final storefrontDioProvider = Provider<Dio>((ref) {
  final dio = Dio(BaseOptions(
    baseUrl: ApiConstants.baseUrl,
    connectTimeout: const Duration(seconds: 8),
    receiveTimeout: const Duration(seconds: 15),
    headers: const {'Content-Type': 'application/json'},
  ));
  dio.interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
    final tenant = ref.read(storefrontTenantProvider);
    if (tenant != null && tenant.isNotEmpty) {
      options.headers['X-Storefront-Tenant'] = tenant;
    }
    // Attach the signed-in customer's bearer token when present.
    final token = ref.read(storefrontAuthProvider).accessToken;
    if (token != null && token.isNotEmpty) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }));
  return dio;
});

// ── Customer authentication (storefront self-service) ────────────────────────

class StorefrontAuthState {
  final String? accessToken;
  final String? refreshToken;
  final String? email;

  const StorefrontAuthState({this.accessToken, this.refreshToken, this.email});

  bool get isSignedIn => accessToken != null && accessToken!.isNotEmpty;
}

class StorefrontAuthNotifier extends StateNotifier<StorefrontAuthState> {
  StorefrontAuthNotifier() : super(const StorefrontAuthState()) {
    _load();
  }

  static const _storage = AppStorage();
  static const _kAccess = 'sf_cust_access';
  static const _kRefresh = 'sf_cust_refresh';
  static const _kEmail = 'sf_cust_email';

  Future<void> _load() async {
    final access = await _storage.read(key: _kAccess);
    if (access == null || access.isEmpty) return;
    state = StorefrontAuthState(
      accessToken: access,
      refreshToken: await _storage.read(key: _kRefresh),
      email: await _storage.read(key: _kEmail),
    );
  }

  Future<void> register(String email, String password, String? phone) =>
      _auth('/${ApiConstants.iam}/auth/register', {
        'email': email,
        'password': password,
        if (phone != null && phone.isNotEmpty) 'phone': phone,
      }, email);

  Future<void> login(String email, String password) => _auth(
      '/${ApiConstants.iam}/auth/login',
      {'email': email, 'password': password},
      email);

  Future<void> _auth(String path, Map<String, dynamic> body, String email) async {
    // Use a clean Dio (no stale Authorization header) for the auth call.
    final dio = Dio(BaseOptions(baseUrl: ApiConstants.baseUrl));
    final resp = await dio.post(path, data: body);
    final data = resp.data['data'] as Map<String, dynamic>;
    final access = data['accessToken'] as String?;
    final refresh = data['refreshToken'] as String?;

    // Reject staff / admin accounts on the customer storefront. Decode the JWT
    // payload (base64url) and check the `type` claim — only CUSTOMER tokens are
    // allowed here. This is a UX guard; the server enforces authorisation anyway.
    if (access != null) {
      final type = _jwtType(access);
      if (type != null && type != 'CUSTOMER') {
        throw Exception(
            'Staff accounts cannot sign in here. Please use the manager portal.');
      }
    }

    await _storage.write(key: _kAccess, value: access);
    await _storage.write(key: _kRefresh, value: refresh);
    await _storage.write(key: _kEmail, value: email);
    state = StorefrontAuthState(
        accessToken: access, refreshToken: refresh, email: email);
  }

  /// Decodes the `type` claim from a JWT payload without verifying the signature
  /// (verification happens server-side). Returns null on any parse failure.
  static String? _jwtType(String token) {
    try {
      final parts = token.split('.');
      if (parts.length != 3) return null;
      final payload = utf8.decode(
          base64Url.decode(base64Url.normalize(parts[1])));
      final claims = json.decode(payload) as Map<String, dynamic>;
      return claims['type'] as String?;
    } catch (_) {
      return null;
    }
  }

  Future<void> logout() async {
    await _storage.delete(key: _kAccess);
    await _storage.delete(key: _kRefresh);
    await _storage.delete(key: _kEmail);
    state = const StorefrontAuthState();
  }

  /// SJ-D43: the account holder deletes their own login. The password is asked
  /// for again server-side (a session left open on this device must not be
  /// enough on its own), so a wrong password surfaces as the 401 iam-svc
  /// returns; the caller shows that. On success the login is gone, so this
  /// behaves like [logout] on top of the server-side deletion.
  Future<void> deleteAccount(String password) async {
    final token = state.accessToken;
    if (token == null || token.isEmpty) {
      throw Exception('Not signed in.');
    }
    final dio = Dio(BaseOptions(
      baseUrl: ApiConstants.baseUrl,
      headers: {'Authorization': 'Bearer $token'},
    ));
    await dio.post('/${ApiConstants.iam}/auth/delete-account',
        data: {'password': password});
    await logout();
  }
}

final storefrontAuthProvider =
    StateNotifierProvider<StorefrontAuthNotifier, StorefrontAuthState>(
        (ref) => StorefrontAuthNotifier());

// ── Models ───────────────────────────────────────────────────────────────────

class StoreProduct {
  final String id;
  final String name;
  final String? description;
  final String? categoryId;

  const StoreProduct({
    required this.id,
    required this.name,
    this.description,
    this.categoryId,
  });

  factory StoreProduct.fromJson(Map<String, dynamic> j) => StoreProduct(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        description: j['description'] as String?,
        categoryId: j['categoryId'] as String?,
      );
}

class StoreVariant {
  final String id;
  final String sku;
  final String? barcode;
  final String? unit;

  const StoreVariant({
    required this.id,
    required this.sku,
    this.barcode,
    this.unit,
  });

  factory StoreVariant.fromJson(Map<String, dynamic> j) => StoreVariant(
        id: j['id'] as String? ?? '',
        sku: j['sku'] as String? ?? '-',
        barcode: j['barcode'] as String?,
        unit: j['unit'] as String?,
      );
}

class ResolvedPrice {
  final double unitPrice;
  final double totalWithVat;
  final String currency;

  /// The price per kg, litre, metre, m² or item of what the shopper pays
  /// (03.13); null when the item has no declared measure.
  final UnitPriceInfo? unitPricing;

  /// The promotion that reduced the price, if one did.
  final String? promotionApplied;

  /// The lowest price of the 30 days before the reduction (03.12, Directive
  /// 98/6/EC art.6a); null without a promotion.
  final double? priorPrice;

  /// Whether this may be shown as a reduction at all: pricing-svc says no
  /// where the law needs a prior price it cannot prove.
  final bool reductionAnnounceable;

  /// The same price in the currency the shopper chose to see (03.x), at the
  /// shop's own rate; null when they see the shop's currency. Shown, never charged.
  final DisplayPrice? display;

  const ResolvedPrice({
    required this.unitPrice,
    required this.totalWithVat,
    required this.currency,
    this.unitPricing,
    this.promotionApplied,
    this.priorPrice,
    this.reductionAnnounceable = false,
    this.display,
  });

  /// `≈ US$15.00` when the shopper sees another currency; empty otherwise.
  String get shownLine {
    final d = display;
    if (d == null || d.currency == currency) return '';
    return '≈ ${AppFormat.money(d.totalWithVat, currencyCode: d.currency)}';
  }

  factory ResolvedPrice.fromJson(Map<String, dynamic> j) => ResolvedPrice(
        unitPrice: (j['unitPrice'] as num?)?.toDouble() ?? 0,
        totalWithVat: (j['totalWithVat'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
        unitPricing: UnitPriceInfo.fromJson(j['unitPricing']),
        promotionApplied: j['promotionApplied'] as String?,
        priorPrice: (j['priorPrice'] as num?)?.toDouble(),
        reductionAnnounceable: j['reductionAnnounceable'] as bool? ?? false,
        display: j['display'] is Map<String, dynamic>
            ? DisplayPrice.fromJson(j['display'] as Map<String, dynamic>)
            : null,
      );
}

/// A price in another currency at the shop's own rate (03.x): what a shopper
/// sees beside the price they pay.
class DisplayPrice {
  final String currency;
  final double rate;
  final double unitPrice;
  final double totalWithVat;
  const DisplayPrice({
    required this.currency,
    required this.rate,
    required this.unitPrice,
    required this.totalWithVat,
  });

  factory DisplayPrice.fromJson(Map<String, dynamic> j) => DisplayPrice(
        currency: j['currency'] as String? ?? '',
        rate: (j['rate'] as num?)?.toDouble() ?? 1,
        unitPrice: (j['unitPrice'] as num?)?.toDouble() ?? 0,
        totalWithVat: (j['totalWithVat'] as num?)?.toDouble() ?? 0,
      );
}

/// The currencies this shop can show prices in (03.x): its own first, then
/// those it keeps a rate for, with the rates (home units per one unit).
class ShopCurrencies {
  final String home;
  final List<String> currencies;
  final Map<String, double> rates;
  const ShopCurrencies({required this.home, required this.currencies, required this.rates});

  factory ShopCurrencies.fromJson(Map<String, dynamic> j) => ShopCurrencies(
        home: j['home'] as String? ?? '',
        currencies: ((j['currencies'] as List?) ?? []).map((e) => e.toString()).toList(),
        rates: {
          for (final r in ((j['rates'] as List?) ?? []))
            (r as Map<String, dynamic>)['currency'] as String: ((r['rate'] as num?)?.toDouble() ?? 1),
        },
      );

  /// A home-currency amount shown in [currency]; null without a rate.
  double? shown(double homeAmount, String currency) {
    if (currency == home) return homeAmount;
    final rate = rates[currency];
    return rate == null || rate <= 0 ? null : homeAmount / rate;
  }
}

/// The currencies the shop offers; empty when it could not be asked.
final storefrontCurrenciesProvider = FutureProvider<ShopCurrencies?>((ref) async {
  final dio = ref.watch(storefrontDioProvider);
  try {
    final resp = await dio.get('/${ApiConstants.pricing}/prices/currencies');
    final data = resp.data is Map ? resp.data['data'] : null;
    return data is Map<String, dynamic> ? ShopCurrencies.fromJson(data) : null;
  } on DioException {
    return null;
  }
});

/// The currency the shopper chose to see prices in; null for the shop's own.
final displayCurrencyProvider = StateProvider<String?>((ref) => null);

class StoreCategory {
  final String id;
  final String name;

  const StoreCategory({required this.id, required this.name});

  factory StoreCategory.fromJson(Map<String, dynamic> j) => StoreCategory(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '-',
      );
}

// ── Store config & availability ──────────────────────────────────────────────

/// Per-store storefront display rules, fetched from tenant-svc.
class StorefrontConfig {
  final bool showPrices;
  final String storeName;

  /// Tenders the store owner enabled (subset of CASH, CARD, UPI, WALLET).
  /// Drives which payment options checkout offers.
  final List<String> enabledPaymentMethods;

  /// The deposit return scheme in force where this store trades (09.16), or
  /// null. The deposit is put on the order by the server as its own line.
  final DepositScheme? depositScheme;

  /// Whether a shopper may collect an order here; false at a dark store, which
  /// fills online orders for delivery only.
  final bool pickupOffered;

  const StorefrontConfig({
    required this.showPrices,
    this.storeName = '-',
    this.enabledPaymentMethods = const ['CASH', 'CARD'],
    this.depositScheme,
    this.pickupOffered = true,
  });
}

/// A deposit return scheme (09.16): the deposit on each drinks container of
/// the named materials within the volume band, paid back on the empty.
class DepositScheme {
  final String scope;
  final String currency;
  final double depositEach;
  final List<String> materials;
  final int minVolumeMl;
  final int maxVolumeMl;
  final String vatTreatment;
  final String citation;
  final String summary;
  const DepositScheme({
    required this.scope,
    required this.currency,
    required this.depositEach,
    required this.materials,
    required this.minVolumeMl,
    required this.maxVolumeMl,
    required this.vatTreatment,
    required this.citation,
    required this.summary,
  });

  /// Whether the scheme takes this container back.
  bool covers(String? material, int? volumeMl) =>
      material != null &&
      volumeMl != null &&
      materials.contains(material) &&
      volumeMl >= minVolumeMl &&
      volumeMl <= maxVolumeMl;

  /// The materials and band in words: "PET, aluminium or steel, 150 ml to 3 l".
  String get inWords {
    final names = materials.map(_materialName).toList();
    final list = names.length <= 1
        ? names.join()
        : '${names.sublist(0, names.length - 1).join(', ')} or ${names.last}';
    return '$list, $minVolumeMl ml to ${_litres(maxVolumeMl)}';
  }

  static String _litres(int ml) =>
      ml % 1000 == 0 ? '${ml ~/ 1000} l' : '$ml ml';
  static String _materialName(String code) => switch (code) {
        'PET' => 'PET',
        'ALUMINIUM' => 'aluminium',
        'STEEL' => 'steel',
        'GLASS' => 'glass',
        _ => code.toLowerCase(),
      };

  factory DepositScheme.fromJson(Map<String, dynamic> j) => DepositScheme(
        scope: j['scope'] as String? ?? '',
        currency: j['currency'] as String? ?? '',
        depositEach: (j['depositEach'] as num?)?.toDouble() ?? 0,
        materials: [
          for (final m in (j['materials'] as List?) ?? const []) m.toString()
        ],
        minVolumeMl: (j['minVolumeMl'] as num?)?.toInt() ?? 0,
        maxVolumeMl: (j['maxVolumeMl'] as num?)?.toInt() ?? 0,
        vatTreatment: j['vatTreatment'] as String? ?? '',
        citation: j['citation'] as String? ?? '',
        summary: j['summary'] as String? ?? '',
      );
}

/// A tenant store, for the storefront's store switcher.
class StoreSummary {
  final String id;
  final String name;
  final bool showPrices;

  /// STORE, WAREHOUSE or DARK_STORE.
  final String type;

  /// Whether a shopper may collect here. A dark store — a shop with no shop
  /// floor — sells delivery-only (ship-from-store and dark-store picking).
  final bool pickupOffered;

  /// The business the store belongs to (the tenant's legal name, else its
  /// name), when tenant-svc sends it; null otherwise. The accessibility
  /// statement speaks for the business, never for one of its stores.
  final String? businessName;

  const StoreSummary(
      {required this.id,
      required this.name,
      required this.showPrices,
      this.type = 'STORE',
      this.pickupOffered = true,
      this.businessName});

  factory StoreSummary.fromJson(Map<String, dynamic> j) => StoreSummary(
        id: j['storeId'] as String? ?? '',
        name: j['storeName'] as String? ?? '-',
        showPrices: j['showPrices'] as bool? ?? true,
        type: j['type'] as String? ?? 'STORE',
        pickupOffered: j['pickupOffered'] as bool? ?? true,
        businessName: _knownName(j['businessName']),
      );
}

/// A name as sent, trimmed; null when missing, blank, or the '-' the
/// storefront sends for a name it does not have.
String? _knownName(Object? raw) {
  if (raw is! String) return null;
  final trimmed = raw.trim();
  return trimmed.isEmpty || trimmed == '-' ? null : trimmed;
}

/// True when the storefront's tenant has been deactivated (gateway returns 403
/// TENANT_INACTIVE for every storefront path). Fails open to "available" on any
/// other error so a transient blip doesn't hide a working shop.
final storefrontSuspendedProvider =
    FutureProvider.autoDispose<bool>((ref) async {
  final dio = ref.watch(storefrontDioProvider);
  try {
    await dio.get('/${ApiConstants.tenant}/storefront/stores');
    return false;
  } on DioException catch (e) {
    return e.response?.statusCode == 403;
  } catch (_) {
    return false;
  }
});

/// Active stores for the tenant — powers the store switcher.
final storefrontStoresProvider =
    FutureProvider.autoDispose<List<StoreSummary>>((ref) async {
  final dio = ref.watch(storefrontDioProvider);
  final resp = await dio.get('/${ApiConstants.tenant}/storefront/stores');
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => StoreSummary.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// Whether the current store shows prices (priced shop) or hides them and shows
/// stock availability instead (catalog mode). Fails open to hiding prices (safe
/// default) while loading. Not autoDispose so the config survives navigation
/// between product list → cart → detail without a re-fetch that would briefly
/// flash showPrices=true and let price-resolve calls slip through.
final storefrontConfigProvider =
    FutureProvider<StorefrontConfig>((ref) async {
  final dio = ref.watch(storefrontDioProvider);
  final store = ref.watch(storefrontStoreProvider);
  try {
    final resp = await dio.get('/${ApiConstants.tenant}/storefront/config',
        queryParameters: {'store': store});
    final d = resp.data['data'] as Map<String, dynamic>;
    final scheme = d['depositScheme'];
    return StorefrontConfig(
      showPrices: d['showPrices'] as bool? ?? true,
      storeName: d['storeName'] as String? ?? '-',
      depositScheme: scheme is Map<String, dynamic>
          ? DepositScheme.fromJson(scheme)
          : null,
      enabledPaymentMethods: (d['enabledPaymentMethods'] as List?)
              ?.map((e) => e.toString().toUpperCase())
              .toList() ??
          const ['CASH', 'CARD'],
      pickupOffered: d['pickupOffered'] as bool? ?? true,
    );
  } catch (_) {
    return const StorefrontConfig(showPrices: true);
  }
});

/// The tenders the current store accepts. Falls back to CASH+CARD while loading
/// so checkout is never left with zero options on a slow config fetch.
final storefrontPaymentMethodsProvider = Provider<List<String>>((ref) =>
    ref.watch(storefrontConfigProvider).value?.enabledPaymentMethods ??
    const ['CASH', 'CARD']);

/// A variant's stock at the current store: whether it can be sold at all, and
/// — only when the business set a storefront stock-signal threshold and this
/// variant is at or under it — the whole units left. Never a count above the
/// threshold, and never one at all with no threshold, out of stock, or a
/// weighed good.
class StockInfo {
  final bool inStock;
  final int? onlyLeft;

  /// True when the supplier ships it per order: available with none on the
  /// shelf (inventory-svc's own words for it). A variant sourced this way is
  /// never held back from Add for being "out of stock" — there is no shelf
  /// to be out of.
  final bool dropship;

  const StockInfo({required this.inStock, this.onlyLeft, this.dropship = false});

  factory StockInfo.fromJson(Map<String, dynamic> j) => StockInfo(
        inStock: j['inStock'] as bool? ?? false,
        onlyLeft: (j['onlyLeft'] as num?)?.toInt(),
        dropship: j['dropship'] as bool? ?? false,
      );
}

/// variantId → stock at the current store (real inventory). Empty/failed = treat as available.
final storefrontAvailabilityProvider =
    FutureProvider.autoDispose<Map<String, StockInfo>>((ref) async {
  final dio = ref.watch(storefrontDioProvider);
  final store = ref.watch(storefrontStoreProvider);
  if (store.isEmpty) return {};
  final resp = await dio.get('/${ApiConstants.inventory}/inventory/availability',
      queryParameters: {'store': store});
  final data = (resp.data['data'] as List?) ?? [];
  return {
    for (final e in data)
      (e['variantId'] as String): StockInfo.fromJson(e as Map<String, dynamic>)
  };
});

// ── Delivery and collection slots ────────────────────────────────────────────
//
// A store's next seven days of delivery/collection windows, in the store's own
// time (order-svc computes date/startTime/endTime server-side; the app never
// converts them — a device in one time zone must show a store in another its
// own hours, not the device's).

/// The store's own bare calendar date ("2026-09-27"), read as its year/month/day
/// only — never run through a timezone conversion, so a store on the other
/// side of the world keeps its own day. Null when [ymd] is not that shape.
DateTime? localYmd(String ymd) {
  final parts = ymd.split('-');
  if (parts.length != 3) return null;
  final y = int.tryParse(parts[0]);
  final m = int.tryParse(parts[1]);
  final d = int.tryParse(parts[2]);
  if (y == null || m == null || d == null) return null;
  return DateTime(y, m, d);
}

/// One occurrence of a window a shopper may choose at checkout.
class SlotOption {
  final String windowId;

  /// The store's own calendar day this occurrence falls on ("2026-09-27"),
  /// from the day the server listed it under.
  final String date;
  final DateTime startsAt;
  final DateTime endsAt;

  /// The store's own local clock, exactly as the server sent it — never
  /// converted on the device.
  final String startTime;
  final String endTime;
  final int left;
  final bool full;

  const SlotOption({
    required this.windowId,
    this.date = '',
    required this.startsAt,
    required this.endsAt,
    required this.startTime,
    required this.endTime,
    required this.left,
    required this.full,
  });

  /// "17:00–19:00", the store's own local clock.
  String get timeRange => '$startTime–$endTime';

  factory SlotOption.fromJson(Map<String, dynamic> j, {String date = ''}) => SlotOption(
        windowId: j['windowId'] as String? ?? '',
        date: date,
        startsAt: DateTime.tryParse(j['startsAt'] as String? ?? '') ?? DateTime.now(),
        endsAt: DateTime.tryParse(j['endsAt'] as String? ?? '') ?? DateTime.now(),
        startTime: j['startTime'] as String? ?? '',
        endTime: j['endTime'] as String? ?? '',
        left: (j['left'] as num?)?.toInt() ?? 0,
        full: j['full'] as bool? ?? false,
      );
}

/// One of the next seven days, and what it offers.
class SlotDay {
  final String date;
  final List<SlotOption> slots;

  const SlotDay({required this.date, required this.slots});

  DateTime? get localDate => localYmd(date);

  factory SlotDay.fromJson(Map<String, dynamic> j) {
    final date = j['date'] as String? ?? '';
    return SlotDay(
      date: date,
      slots: [
        for (final s in (j['slots'] as List?) ?? const [])
          if (s is Map<String, dynamic>) SlotOption.fromJson(s, date: date),
      ],
    );
  }
}

/// A store's answer to "what windows can a shopper choose, and what is left".
class FulfilmentSlots {
  final String storeId;
  final String fulfilmentType;
  final String timeZone;

  /// Whether the store offers windows of this type at all; false ⇒ no picker,
  /// checkout exactly as before.
  final bool offered;
  final List<SlotDay> days;

  const FulfilmentSlots({
    required this.storeId,
    required this.fulfilmentType,
    required this.timeZone,
    required this.offered,
    required this.days,
  });

  factory FulfilmentSlots.fromJson(Map<String, dynamic> j) => FulfilmentSlots(
        storeId: j['storeId'] as String? ?? '',
        fulfilmentType: j['fulfilmentType'] as String? ?? '',
        timeZone: j['timeZone'] as String? ?? '',
        offered: j['offered'] as bool? ?? false,
        days: [
          for (final d in (j['days'] as List?) ?? const [])
            if (d is Map<String, dynamic>) SlotDay.fromJson(d),
        ],
      );
}

/// Which store's windows, of which fulfilment type.
typedef SlotsQuery = ({String store, String type});

/// The next seven days of [q.store]'s windows for [q.type] (DELIVERY | PICKUP),
/// with what each has left. Public storefront read; autoDispose.family — this
/// is scoped to the checkout screen, unlike [storefrontConfigProvider].
final fulfilmentSlotsProvider =
    FutureProvider.autoDispose.family<FulfilmentSlots, SlotsQuery>((ref, q) async {
  final dio = ref.watch(storefrontDioProvider);
  final resp = await dio.get('/${ApiConstants.order}/storefront/fulfilment-slots',
      queryParameters: {'store': q.store, 'type': q.type});
  return FulfilmentSlots.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// The window an order was placed for (null when it has none): the store's own
/// local date and clock, computed server-side — the app never converts a time.
class OrderSlot {
  final DateTime startsAt;
  final DateTime endsAt;
  final String timeZone;
  final String date;
  final String startTime;
  final String endTime;

  const OrderSlot({
    required this.startsAt,
    required this.endsAt,
    required this.timeZone,
    required this.date,
    required this.startTime,
    required this.endTime,
  });

  factory OrderSlot.fromJson(Map<String, dynamic> j) => OrderSlot(
        startsAt: DateTime.tryParse(j['startsAt'] as String? ?? '') ?? DateTime.now(),
        endsAt: DateTime.tryParse(j['endsAt'] as String? ?? '') ?? DateTime.now(),
        timeZone: j['timeZone'] as String? ?? '',
        date: j['date'] as String? ?? '',
        startTime: j['startTime'] as String? ?? '',
        endTime: j['endTime'] as String? ?? '',
      );

  Map<String, dynamic> toJson() => {
        'startsAt': startsAt.toIso8601String(),
        'endsAt': endsAt.toIso8601String(),
        'timeZone': timeZone,
        'date': date,
        'startTime': startTime,
        'endTime': endTime,
      };

  /// [raw] read as an [OrderSlot] when it is a map; null otherwise (no window).
  static OrderSlot? maybe(Object? raw) =>
      raw is Map<String, dynamic> ? OrderSlot.fromJson(raw) : null;
}

// ── Promotions (storefront offers banner) ────────────────────────────────────

/// An active, advertised promotion for the offers carousel.
class StorePromotion {
  final String name;
  final String type; // PERCENT | FLAT
  final double value;
  final double? minOrderAmount;

  /// Whether pricing-svc lets the storefront advertise it as a reduction (03.12).
  final bool reductionAnnounceable;

  const StorePromotion({
    required this.name,
    required this.type,
    required this.value,
    this.minOrderAmount,
    this.reductionAnnounceable = false,
  });

  factory StorePromotion.fromJson(Map<String, dynamic> j) => StorePromotion(
        name: j['name'] as String? ?? 'Offer',
        type: (j['type'] as String? ?? 'PERCENT').toUpperCase(),
        value: (j['value'] as num?)?.toDouble() ?? 0,
        minOrderAmount: (j['minOrderAmount'] as num?)?.toDouble(),
        reductionAnnounceable: j['reductionAnnounceable'] == true,
      );

  /// Short headline, e.g. "20% off" or "£5.00 off", in the shop's
  /// [currency] (the amount alone, grouped, while it is not known).
  String headlineIn(String? currency) => type == 'PERCENT'
      ? '${AppFormat.count(value)}% off'
      : '${AppFormat.money(value, currencyCode: currency)} off';

  /// [headlineIn] with the currency not known.
  String get headline => headlineIn(null);
}

/// The promotions the banner may show (03.12): only those pricing-svc says may be
/// advertised — never on a missing or garbled answer. Directive 98/6/EC art.6a
/// lets a reduction be announced only against a proven prior price.
List<StorePromotion> advertisedPromotions(List<StorePromotion> all) =>
    all.where((p) => p.reductionAnnounceable).toList();

/// Active promotions for the current tenant (advertised offers). Fails soft to an
/// empty list so the banner can fall back to evergreen content.
final storefrontPromotionsProvider =
    FutureProvider.autoDispose<List<StorePromotion>>((ref) async {
  final dio = ref.watch(storefrontDioProvider);
  try {
    final resp = await dio.get('/${ApiConstants.pricing}/promotions');
    final data = (resp.data['data'] as List?) ?? [];
    return data
        .map((e) => StorePromotion.fromJson(e as Map<String, dynamic>))
        .toList();
  } catch (_) {
    return const [];
  }
});

// ── Catalog providers ────────────────────────────────────────────────────────

/// Active categories for the storefront "browse by category" chips.
final storefrontCategoriesProvider =
    FutureProvider.autoDispose<List<StoreCategory>>((ref) async {
  final dio = ref.watch(storefrontDioProvider);
  final resp = await dio.get('/${ApiConstants.product}/catalog/categories');
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => StoreCategory.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// Currently selected category id for browsing; null = "All".
final selectedStorefrontCategoryProvider = StateProvider<String?>((ref) => null);

/// Filter key for the storefront product list: free-text query + optional category.
typedef ProductFilter = ({String query, String? categoryId});

/// Storefront product list, filtered by search query and/or category.
final storefrontProductsProvider =
    FutureProvider.autoDispose.family<List<StoreProduct>, ProductFilter>((ref, f) async {
  final dio = ref.watch(storefrontDioProvider);
  // Watching the store id re-runs this when the shopper switches store, so the
  // catalog reflects that store's assortment.
  final store = ref.watch(storefrontStoreProvider);
  final q = f.query.trim();
  final params = <String, dynamic>{'limit': 50, 'store': store};
  // The catalog endpoint routes to text-search when q is present (ignoring
  // category) or to a category-filtered list otherwise.
  if (q.isNotEmpty) {
    params['q'] = q;
  } else if (f.categoryId != null) {
    params['category'] = f.categoryId;
  }
  final resp =
      await dio.get('/${ApiConstants.product}/catalog/products', queryParameters: params);
  final data = (resp.data['data'] as List?) ?? [];
  var list = data.map((e) => StoreProduct.fromJson(e as Map<String, dynamic>)).toList();
  // When both a search term and a category are active, the server honoured only
  // the search — narrow to the category here.
  if (q.isNotEmpty && f.categoryId != null) {
    list = list.where((p) => p.categoryId == f.categoryId).toList();
  }
  return list;
});

/// Bounded LRU holding fetched product image bytes, shared by every [productImageProvider].
///
/// Deliberately not autoDispose: this is the thing that must outlive the per-product
/// providers watching it.
final productImageCacheProvider = Provider<ImageByteCache>((ref) => ImageByteCache());

/// The product's uploaded image bytes, or null when it has none (the UI then renders the
/// deterministic colour tile). Fetched through Dio (not Image.network) so the storefront tenant
/// header rides along — a plain browser <img> request can't carry it on web.
///
/// autoDispose, with the bytes held in [productImageCacheProvider] instead. Keeping the
/// providers themselves alive was what cached images for the session, but that cache had no
/// ceiling: it grew with every product the shopper ever scrolled past and was never released.
/// The LRU keeps the same "scrolling doesn't refetch" behaviour — which is what stops the
/// catalog tripping the gateway's per-IP rate limit — against a bounded ceiling.
///
/// Returns synchronously on a cache hit (hence FutureOr, not async) so scrolling back over a
/// seen product paints the image immediately rather than flashing the colour tile for a frame.
final productImageProvider =
    FutureProvider.autoDispose.family<Uint8List?, String>((ref, productId) {
  final cache = ref.watch(productImageCacheProvider);
  final hit = cache.lookup(productId);
  if (hit != null) return hit.bytes;
  return _fetchProductImage(ref.watch(storefrontDioProvider), cache, productId);
});

Future<Uint8List?> _fetchProductImage(
    Dio dio, ImageByteCache cache, String productId) async {
  try {
    final resp = await dio.get(
      '/${ApiConstants.product}/catalog/products/$productId/image',
      options: Options(responseType: ResponseType.bytes),
    );
    final data = resp.data;
    final bytes =
        data is List<int> && data.isNotEmpty ? Uint8List.fromList(data) : null;
    cache.store(productId, bytes);
    return bytes;
  } on DioException catch (e) {
    // A 404 is a real answer — this product has no image — so cache it and stop asking.
    // Anything else is transient: leave it uncached so a later rebuild can retry, and
    // fall back to the colour tile meanwhile.
    if (e.response?.statusCode == 404) cache.store(productId, null);
    return null;
  } catch (_) {
    return null;
  }
}

final storefrontProductProvider =
    FutureProvider.autoDispose.family<StoreProduct, String>((ref, id) async {
  final dio = ref.watch(storefrontDioProvider);
  final resp = await dio.get('/${ApiConstants.product}/catalog/products/$id');
  return StoreProduct.fromJson(resp.data['data'] as Map<String, dynamic>);
});

// Not autoDispose: cached for the session so scrolling the catalog (cards
// entering/leaving the viewport) doesn't re-fetch variants and trip the
// gateway's per-IP rate limit.
final storefrontVariantsProvider =
    FutureProvider.family<List<StoreVariant>, String>((ref, productId) async {
  final dio = ref.watch(storefrontDioProvider);
  final resp =
      await dio.get('/${ApiConstants.product}/catalog/products/$productId/variants');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => StoreVariant.fromJson(e as Map<String, dynamic>)).toList();
});

/// Whether the current store shows prices. When false (catalog mode) the whole
/// storefront hides prices AND skips price-resolve calls; checkout is order-only.
/// Defaults to false while config is loading — safer than defaulting to true,
/// which would let prices flash and let price-resolve calls fire prematurely.
/// Not autoDispose: must survive navigation alongside storefrontConfigProvider.
final storefrontShowPricesProvider = Provider<bool>(
    (ref) => ref.watch(storefrontConfigProvider).value?.showPrices ?? false);

/// First sellable variant of a product (no price) — used to add to cart in
/// catalog mode without ever resolving a price.
// Not autoDispose: same reasoning as storefrontVariantsProvider above.
final productFirstVariantProvider =
    FutureProvider.family<StoreVariant?, String>((ref, productId) async {
  final variants = await ref.watch(storefrontVariantsProvider(productId).future);
  return variants.isEmpty ? null : variants.first;
});

/// Resolved ONLINE price for one variant. Not autoDispose: same reasoning as
/// storefrontVariantsProvider above.
final variantPriceProvider =
    FutureProvider.family<ResolvedPrice, String>((ref, variantId) async {
  final dio = ref.watch(storefrontDioProvider);
  final shownIn = ref.watch(displayCurrencyProvider);
  final resp = await dio.post(
    '/${ApiConstants.pricing}/prices/resolve',
    data: {
      'variantId': variantId,
      'channel': 'ONLINE',
      'qty': 1,
      'displayCurrency': ?shownIn,
    },
  );
  return ResolvedPrice.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// Price to show on a product card = price of its first variant (or null).
final productCardPriceProvider =
    FutureProvider.autoDispose.family<ResolvedPrice?, String>((ref, productId) async {
  final variants = await ref.watch(storefrontVariantsProvider(productId).future);
  if (variants.isEmpty) return null;
  return ref.watch(variantPriceProvider(variants.first.id).future);
});

/// A product's first sellable variant + its resolved price, bundled so a listing
/// card can both show a price and add straight to cart in one tap.
class CardOffer {
  final StoreVariant variant;
  final ResolvedPrice price;
  const CardOffer(this.variant, this.price);
}

// Not autoDispose: same reasoning as storefrontVariantsProvider above.
final productCardOfferProvider =
    FutureProvider.family<CardOffer?, String>((ref, productId) async {
  final variants = await ref.watch(storefrontVariantsProvider(productId).future);
  if (variants.isEmpty) return null;
  final v = variants.first;
  final price = await ref.watch(variantPriceProvider(v.id).future);
  return CardOffer(v, price);
});

// ── Cart (client-side; there is no cart-svc) ─────────────────────────────────

class CartLine {
  final String variantId;

  /// The product the variant belongs to, so the cart shows the same picture
  /// (and placeholder colour) as the shop. Null for a line added before lines
  /// knew their product.
  final String? productId;
  final String productName;
  final String sku;
  final double unitPrice;
  final String currency;
  int qty;

  CartLine({
    required this.variantId,
    this.productId,
    required this.productName,
    required this.sku,
    required this.unitPrice,
    required this.currency,
    this.qty = 1,
  });

  double get lineTotal => unitPrice * qty;
}

class CartNotifier extends StateNotifier<List<CartLine>> {
  CartNotifier() : super([]);

  void add(CartLine line) {
    final idx = state.indexWhere((l) => l.variantId == line.variantId);
    if (idx >= 0) {
      final copy = [...state];
      copy[idx].qty += line.qty;
      state = copy;
    } else {
      state = [...state, line];
    }
  }

  void setQty(String variantId, int qty) {
    if (qty <= 0) {
      remove(variantId);
      return;
    }
    state = [
      for (final l in state)
        if (l.variantId == variantId)
          (CartLine(
            variantId: l.variantId,
            productId: l.productId,
            productName: l.productName,
            sku: l.sku,
            unitPrice: l.unitPrice,
            currency: l.currency,
            qty: qty,
          ))
        else
          l
    ];
  }

  void remove(String variantId) =>
      state = state.where((l) => l.variantId != variantId).toList();

  /// Puts [line] back at [index] (the end, past it) — *Undo* after a removal —
  /// unless its variant is in the cart again already.
  void insert(int index, CartLine line) {
    if (state.any((l) => l.variantId == line.variantId)) return;
    state = [...state]..insert(index.clamp(0, state.length), line);
  }

  void clear() => state = [];

  double get total => state.fold(0.0, (s, l) => s + l.lineTotal);
  int get count => state.fold(0, (s, l) => s + l.qty);
}

final cartProvider =
    StateNotifierProvider<CartNotifier, List<CartLine>>((ref) => CartNotifier());

// ── Order history (device-local) ─────────────────────────────────────────────
//
// On-device cache of orders placed by the current customer. Signed-in customers
// also get a server-backed list via serverOrdersProvider; this local copy acts
// as a fast, offline-safe supplement and is kept in sync on every checkout.

class StorefrontOrderRecord {
  final String orderId;
  final double total;
  final String currency;
  final int itemCount;
  final DateTime placedAt;
  final String storeName;
  final String fulfilmentType;

  /// The window this order was placed for (delivery-and-collection-slots); null
  /// for an order with none.
  final OrderSlot? slot;

  const StorefrontOrderRecord({
    required this.orderId,
    required this.total,
    required this.currency,
    required this.itemCount,
    required this.placedAt,
    this.storeName = '-',
    this.fulfilmentType = 'PICKUP',
    this.slot,
  });

  Map<String, dynamic> toJson() => {
        'orderId': orderId,
        'total': total,
        'currency': currency,
        'itemCount': itemCount,
        'placedAt': placedAt.toIso8601String(),
        'storeName': storeName,
        'fulfilmentType': fulfilmentType,
        if (slot != null) 'slot': slot!.toJson(),
      };

  factory StorefrontOrderRecord.fromJson(Map<String, dynamic> j) =>
      StorefrontOrderRecord(
        orderId: j['orderId'] as String? ?? '',
        total: (j['total'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
        itemCount: (j['itemCount'] as num?)?.toInt() ?? 0,
        placedAt:
            DateTime.tryParse(j['placedAt'] as String? ?? '') ?? DateTime.now(),
        storeName: j['storeName'] as String? ?? '-',
        fulfilmentType: j['fulfilmentType'] as String? ?? 'PICKUP',
        slot: OrderSlot.maybe(j['slot']),
      );
}

class StorefrontOrdersNotifier
    extends StateNotifier<List<StorefrontOrderRecord>> {
  StorefrontOrdersNotifier() : super(const []) {
    _load();
  }

  static const _storage = AppStorage();

  Future<void> _load() async {
    try {
      final raw = await _storage.read(key: StorageKeys.storefrontOrders);
      if (raw == null || raw.isEmpty) return;
      final list = (jsonDecode(raw) as List)
          .map((e) => StorefrontOrderRecord.fromJson(e as Map<String, dynamic>))
          .toList();
      state = list;
    } catch (_) {
      // Corrupt/unreadable history is non-fatal — start empty.
    }
  }

  Future<void> add(StorefrontOrderRecord record) async {
    state = [record, ...state];
    await _persist();
  }

  Future<void> _persist() async {
    await _storage.write(
      key: StorageKeys.storefrontOrders,
      value: jsonEncode(state.map((e) => e.toJson()).toList()),
    );
  }
}

final storefrontOrdersProvider = StateNotifierProvider<StorefrontOrdersNotifier,
    List<StorefrontOrderRecord>>((ref) => StorefrontOrdersNotifier());

// ── Order history (server-backed, signed-in customers) ───────────────────────
//
// When the shopper is signed in, their real order history comes from order-svc
// `GET /orders/mine`: the gateway stamps the tenant from the storefront header
// and the customer identity from the bearer token, and the service filters to
// orders whose customer_id is the authenticated customer. Guests (no token) fall
// back to the device-local history above.

class ServerOrderSummary {
  final String id;
  final String storeId;
  final String fulfilmentType;
  final String status;
  final double total;
  final String currency;
  final DateTime placedAt;

  /// The checkout this order is a part of, when a delivery came from several shops (order
  /// orchestration); null for an order never split.
  final String? groupId;

  /// How a picked order was handed over (ship-from-store): DISPATCHED to a carrier, or
  /// COLLECTED at the counter; null until it is.
  final String? handoverKind;
  final String? handoverCarrier;
  final String? handoverReference;

  /// The window this order was placed for (delivery-and-collection-slots); null
  /// for an order with none.
  final OrderSlot? slot;

  const ServerOrderSummary({
    required this.id,
    required this.storeId,
    required this.fulfilmentType,
    required this.status,
    required this.total,
    required this.currency,
    required this.placedAt,
    this.groupId,
    this.handoverKind,
    this.handoverCarrier,
    this.handoverReference,
    this.slot,
  });

  /// Where the order is, in the shopper's words: a picked pickup is *Ready to collect*, a picked
  /// delivery *Packed* until it is *On its way · DPD 1Z…*, a collected one *Collected*; anything
  /// else reads as its status.
  String get stageLabel {
    if (handoverKind == 'COLLECTED') return 'Collected';
    if (handoverKind == 'DISPATCHED') {
      final ref = [handoverCarrier, handoverReference]
          .where((e) => e != null && e.isNotEmpty)
          .join(' ');
      return ref.isEmpty ? 'On its way' : 'On its way · $ref';
    }
    if (status.toUpperCase() == 'FULFILLED') {
      return fulfilmentType.toUpperCase() == 'PICKUP' ? 'Ready to collect' : 'Packed';
    }
    return status;
  }

  factory ServerOrderSummary.fromJson(Map<String, dynamic> j) =>
      ServerOrderSummary(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        fulfilmentType: j['fulfilmentType'] as String? ?? 'PICKUP',
        status: j['status'] as String? ?? '-',
        total: (j['total'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
        placedAt: DateTime.tryParse(j['createdAt'] as String? ?? '')?.toLocal() ??
            DateTime.now(),
        groupId: j['groupId'] as String?,
        handoverKind: (j['handover'] as Map<String, dynamic>?)?['kind'] as String?,
        handoverCarrier: (j['handover'] as Map<String, dynamic>?)?['carrier'] as String?,
        handoverReference: (j['handover'] as Map<String, dynamic>?)?['reference'] as String?,
        slot: OrderSlot.maybe(j['slot']),
      );
}

/// One part of a delivery split across shops (order orchestration), as the answer to placing it
/// names it: its own order at its own shop, with its total and how many items it carries.
class CheckoutPart {
  final String orderId;
  final String storeId;
  final double total;
  final int units;

  const CheckoutPart({
    required this.orderId,
    required this.storeId,
    required this.total,
    required this.units,
  });

  factory CheckoutPart.fromJson(Map<String, dynamic> j) => CheckoutPart(
        orderId: j['orderId'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        total: (j['total'] as num?)?.toDouble() ?? 0,
        units: (j['units'] as num?)?.toInt() ?? 0,
      );
}

/// "Arrives in 2 parts: 3 items from Leeds, 1 from York" — a split delivery in the shopper's
/// words; a shop the list does not name is called by the end of its id.
String splitSummary(List<CheckoutPart> parts, Map<String, String> storeNames) {
  final bits = <String>[];
  for (var i = 0; i < parts.length; i++) {
    final p = parts[i];
    final name = storeNames[p.storeId] ?? 'shop ${p.storeId.length > 4 ? p.storeId.substring(p.storeId.length - 4) : p.storeId}';
    bits.add(i == 0
        ? '${p.units} item${p.units == 1 ? '' : 's'} from $name'
        : '${p.units} from $name');
  }
  return 'Arrives in ${parts.length} parts: ${bits.join(', ')}';
}

/// The signed-in customer's real order history. Returns null when not signed in
/// (the UI then shows the device-local list / a sign-in prompt).
///
/// Deliberately not `autoDispose`: checkout's pending-order guard does a `ref.read(...future)`
/// on every checkout attempt purely to look for one pending order, and with `autoDispose` nothing
/// else is necessarily watching this in between checkouts, so every attempt forced a fresh
/// network fetch of the customer's whole order history. Staying alive lets that reuse the
/// already-fetched list; `ref.watch(storefrontAuthProvider)` below still recomputes it on
/// sign-in/sign-out, and call sites already `ref.invalidate` it after placing or refreshing.
///
/// Never retried behind the page: a refused read shows its error at once, with
/// Retry and Refresh, instead of grey cards through half a minute of retries.
final serverOrdersProvider = FutureProvider<List<ServerOrderSummary>?>((ref) async {
  final auth = ref.watch(storefrontAuthProvider);
  if (!auth.isSignedIn) return null;
  final dio = ref.watch(storefrontDioProvider);
  final resp = await dio.get('/${ApiConstants.order}/orders/mine',
      queryParameters: {'limit': 50});
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => ServerOrderSummary.fromJson(e as Map<String, dynamic>))
      .toList();
}, retry: (_, _) => null);

// ── Product safety recalls (05.10) ───────────────────────────────────────────

/// One line of an order a recall reached.
class RecallNoticeLine {
  final String variantId;
  final String? productName;
  final String? batchNo;
  final String? expiryDate;
  final double qty;

  const RecallNoticeLine({
    required this.variantId,
    this.productName,
    this.batchNo,
    this.expiryDate,
    required this.qty,
  });

  factory RecallNoticeLine.fromJson(Map<String, dynamic> j) => RecallNoticeLine(
        variantId: j['variantId'] as String? ?? '',
        productName: j['productName'] as String?,
        batchNo: j['batchNo'] as String?,
        expiryDate: j['expiryDate'] as String?,
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
      );

  String describe() => [
        productName ?? 'the product',
        if (batchNo != null) 'lot $batchNo',
        if (expiryDate != null) 'best before $expiryDate',
      ].join(', ');
}

/// A recall's notice to this shopper about one of their orders: the notice
/// as the shop wrote it, the remedies it offers, and where they got to.
class MyRecallNotice {
  final String id;
  final String reference;
  final String hazard;
  final String reason;
  final String customerNotice;
  final List<String> remedies;
  final String? singleRemedyReason;
  final String? contactPhone;
  final String? contactUrl;
  final String orderId;
  final DateTime? soldAt;
  final String status;
  final String? remedy;
  final String? resolution;
  final List<RecallNoticeLine> lines;

  const MyRecallNotice({
    required this.id,
    required this.reference,
    required this.hazard,
    required this.reason,
    required this.customerNotice,
    this.remedies = const [],
    this.singleRemedyReason,
    this.contactPhone,
    this.contactUrl,
    required this.orderId,
    this.soldAt,
    required this.status,
    this.remedy,
    this.resolution,
    this.lines = const [],
  });

  bool get isResolved => status == 'RESOLVED';
  bool get canChoose => !isResolved && remedy == null;

  factory MyRecallNotice.fromJson(Map<String, dynamic> j) => MyRecallNotice(
        id: j['id'] as String? ?? '',
        reference: j['reference'] as String? ?? '-',
        hazard: j['hazard'] as String? ?? 'OTHER',
        reason: j['reason'] as String? ?? '',
        customerNotice: j['customerNotice'] as String? ?? '',
        remedies: [for (final r in (j['remedies'] as List?) ?? const []) '$r'],
        singleRemedyReason: j['singleRemedyReason'] as String?,
        contactPhone: j['contactPhone'] as String?,
        contactUrl: j['contactUrl'] as String?,
        orderId: j['orderId'] as String? ?? '',
        soldAt: DateTime.tryParse(j['soldAt'] as String? ?? '')?.toLocal(),
        status: j['status'] as String? ?? 'ISSUED',
        remedy: j['remedy'] as String?,
        resolution: j['resolution'] as String?,
        lines: [
          for (final l in (j['lines'] as List?) ?? const [])
            if (l is Map) RecallNoticeLine.fromJson(l.cast<String, dynamic>()),
        ],
      );
}

/// The signed-in shopper's recall notices at this shop; null when signed out.
final myRecallNoticesProvider =
    FutureProvider<List<MyRecallNotice>?>((ref) async {
  final auth = ref.watch(storefrontAuthProvider);
  if (!auth.isSignedIn) return null;
  final dio = ref.watch(storefrontDioProvider);
  final resp = await dio.get('/${ApiConstants.order}/orders/recall-notices/mine');
  final data = (resp.data['data'] as List?) ?? [];
  return [
    for (final e in data)
      if (e is Map) MyRecallNotice.fromJson(e.cast<String, dynamic>()),
  ];
}, retry: (_, _) => null);

/// The shopper chooses their remedy, once.
Future<MyRecallNotice> chooseMyRecallRemedy(
  Dio dio, {
  required String noticeId,
  required String remedy,
}) async {
  final resp = await dio.post(
    '/${ApiConstants.order}/orders/recall-notices/$noticeId/remedy',
    data: {'remedy': remedy},
  );
  return MyRecallNotice.fromJson(
      (resp.data['data'] as Map).cast<String, dynamic>());
}

// ── Customer preferences & data collection ───────────────────────────────────

enum CustomerGender { male, female, other, preferNotToSay }

class CustomerPrefs {
  final List<String> shoppingFor;
  final String notifications;

  const CustomerPrefs({required this.shoppingFor, required this.notifications});

  Map<String, dynamic> toJson() => {
        'shoppingFor': shoppingFor,
        'notifications': notifications,
      };

  factory CustomerPrefs.fromJson(Map<String, dynamic> j) => CustomerPrefs(
        shoppingFor: List<String>.from(j['shoppingFor'] as List? ?? []),
        notifications: j['notifications'] as String? ?? 'None',
      );
}

class SurveyResponse {
  final String orderId;
  final int experienceRating;
  final int nps;
  final String? comment;
  final String platform;
  final String formFactor;
  final DateTime submittedAt;

  const SurveyResponse({
    required this.orderId,
    required this.experienceRating,
    required this.nps,
    this.comment,
    required this.platform,
    required this.formFactor,
    required this.submittedAt,
  });

  Map<String, dynamic> toJson() => {
        'orderId': orderId,
        'experienceRating': experienceRating,
        'nps': nps,
        if (comment != null && comment!.isNotEmpty) 'comment': comment,
        'platform': platform,
        'formFactor': formFactor,
        'submittedAt': submittedAt.toIso8601String(),
      };

  factory SurveyResponse.fromJson(Map<String, dynamic> j) => SurveyResponse(
        orderId: j['orderId'] as String? ?? '',
        experienceRating: (j['experienceRating'] as num?)?.toInt() ?? 3,
        nps: (j['nps'] as num?)?.toInt() ?? 5,
        comment: j['comment'] as String?,
        platform: j['platform'] as String? ?? 'unknown',
        formFactor: j['formFactor'] as String? ?? 'phone',
        submittedAt:
            DateTime.tryParse(j['submittedAt'] as String? ?? '') ?? DateTime.now(),
      );
}

class AppFeedbackEntry {
  final String category;
  final String text;
  final int? starRating;
  final String platform;
  final String formFactor;
  final DateTime submittedAt;

  const AppFeedbackEntry({
    required this.category,
    required this.text,
    this.starRating,
    required this.platform,
    required this.formFactor,
    required this.submittedAt,
  });

  Map<String, dynamic> toJson() => {
        'category': category,
        'text': text,
        if (starRating != null) 'starRating': starRating,
        'platform': platform,
        'formFactor': formFactor,
        'submittedAt': submittedAt.toIso8601String(),
      };

  factory AppFeedbackEntry.fromJson(Map<String, dynamic> j) => AppFeedbackEntry(
        category: j['category'] as String? ?? 'Other',
        text: j['text'] as String? ?? '',
        starRating: (j['starRating'] as num?)?.toInt(),
        platform: j['platform'] as String? ?? 'unknown',
        formFactor: j['formFactor'] as String? ?? 'phone',
        submittedAt:
            DateTime.tryParse(j['submittedAt'] as String? ?? '') ?? DateTime.now(),
      );
}

class CustomerPreferencesState {
  final bool genderAsked;
  final CustomerGender? gender;
  final bool prefsAsked;
  final CustomerPrefs? prefs;
  final List<SurveyResponse> surveys;
  final List<AppFeedbackEntry> feedback;

  const CustomerPreferencesState({
    this.genderAsked = false,
    this.gender,
    this.prefsAsked = false,
    this.prefs,
    this.surveys = const [],
    this.feedback = const [],
  });

  CustomerPreferencesState copyWith({
    bool? genderAsked,
    CustomerGender? gender,
    bool? prefsAsked,
    CustomerPrefs? prefs,
    List<SurveyResponse>? surveys,
    List<AppFeedbackEntry>? feedback,
  }) =>
      CustomerPreferencesState(
        genderAsked: genderAsked ?? this.genderAsked,
        gender: gender ?? this.gender,
        prefsAsked: prefsAsked ?? this.prefsAsked,
        prefs: prefs ?? this.prefs,
        surveys: surveys ?? this.surveys,
        feedback: feedback ?? this.feedback,
      );
}

class CustomerPreferencesNotifier
    extends StateNotifier<CustomerPreferencesState> {
  CustomerPreferencesNotifier() : super(const CustomerPreferencesState()) {
    ready = _load();
  }

  /// Completes once the stored answers are read. Until then every flag reads as "not asked", so
  /// whatever decides whether to ask must wait for it: reading straight after the provider was
  /// created asked a shopper who had already answered, on every visit (SJ-D62).
  late final Future<void> ready;

  static const _storage = AppStorage();

  Future<void> _load() async {
    final genderAsked =
        (await _storage.read(key: StorageKeys.sfGenderAsked)) == 'true';
    final genderRaw = await _storage.read(key: StorageKeys.sfGender);
    final CustomerGender? gender = genderRaw != null
        ? CustomerGender.values.where((g) => g.name == genderRaw).firstOrNull
        : null;

    final prefsAsked =
        (await _storage.read(key: StorageKeys.sfPrefsAsked)) == 'true';
    CustomerPrefs? prefs;
    final prefsRaw = await _storage.read(key: StorageKeys.sfPrefs);
    if (prefsRaw != null) {
      try {
        prefs = CustomerPrefs.fromJson(
            jsonDecode(prefsRaw) as Map<String, dynamic>);
      } catch (_) {}
    }

    List<SurveyResponse> surveys = const [];
    final surveysRaw = await _storage.read(key: StorageKeys.sfSurveys);
    if (surveysRaw != null) {
      try {
        surveys = (jsonDecode(surveysRaw) as List)
            .map((e) => SurveyResponse.fromJson(e as Map<String, dynamic>))
            .toList();
      } catch (_) {}
    }

    List<AppFeedbackEntry> feedback = const [];
    final feedbackRaw = await _storage.read(key: StorageKeys.sfFeedback);
    if (feedbackRaw != null) {
      try {
        feedback = (jsonDecode(feedbackRaw) as List)
            .map((e) => AppFeedbackEntry.fromJson(e as Map<String, dynamic>))
            .toList();
      } catch (_) {}
    }

    state = CustomerPreferencesState(
      genderAsked: genderAsked,
      gender: gender,
      prefsAsked: prefsAsked,
      prefs: prefs,
      surveys: surveys,
      feedback: feedback,
    );
  }

  Future<void> setGender(CustomerGender gender) async {
    await _storage.write(key: StorageKeys.sfGender, value: gender.name);
    await _storage.write(key: StorageKeys.sfGenderAsked, value: 'true');
    state = state.copyWith(gender: gender, genderAsked: true);
  }

  Future<void> skipGender() async {
    await _storage.write(key: StorageKeys.sfGenderAsked, value: 'true');
    state = state.copyWith(genderAsked: true);
  }

  Future<void> setPrefs(CustomerPrefs prefs) async {
    await _storage.write(
        key: StorageKeys.sfPrefs, value: jsonEncode(prefs.toJson()));
    await _storage.write(key: StorageKeys.sfPrefsAsked, value: 'true');
    state = state.copyWith(prefs: prefs, prefsAsked: true);
  }

  Future<void> skipPrefs() async {
    await _storage.write(key: StorageKeys.sfPrefsAsked, value: 'true');
    state = state.copyWith(prefsAsked: true);
  }

  Future<void> addSurvey(SurveyResponse response) async {
    final updated = [response, ...state.surveys];
    await _storage.write(
      key: StorageKeys.sfSurveys,
      value: jsonEncode(updated.map((e) => e.toJson()).toList()),
    );
    await _storage.write(
      key: StorageKeys.sfSurveyLastDate,
      value: _todayString(),
    );
    state = state.copyWith(surveys: updated);
  }

  Future<void> addFeedback(AppFeedbackEntry entry) async {
    final updated = [entry, ...state.feedback];
    await _storage.write(
      key: StorageKeys.sfFeedback,
      value: jsonEncode(updated.map((e) => e.toJson()).toList()),
    );
    state = state.copyWith(feedback: updated);
  }

  Future<bool> wasSurveyShownToday() async {
    final last = await _storage.read(key: StorageKeys.sfSurveyLastDate);
    return last == _todayString();
  }

  String _todayString() {
    final now = DateTime.now();
    return '${now.year}-'
        '${now.month.toString().padLeft(2, '0')}-'
        '${now.day.toString().padLeft(2, '0')}';
  }
}

final customerPrefsProvider =
    StateNotifierProvider<CustomerPreferencesNotifier, CustomerPreferencesState>(
        (ref) => CustomerPreferencesNotifier());

/// Ephemeral flag set to true immediately after a successful storefront login or
/// register. The shell listens to this and shows the preferences sheet once if
/// the customer hasn't been asked yet. Reset to false immediately after reading.
final storefrontJustAuthenticatedProvider =
    StateProvider<bool>((ref) => false);
