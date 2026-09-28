import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/format.dart';
import '../../shared/util/status_labels.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/paged.dart';

// ── Models ───────────────────────────────────────────────────────────────────

class PriceList {
  final String id;
  final String name;
  final String? channel;
  final String? currency;
  final String? effectiveFrom;
  final String? effectiveTo;
  final bool active;

  const PriceList({
    required this.id,
    required this.name,
    this.channel,
    this.currency,
    this.effectiveFrom,
    this.effectiveTo,
    required this.active,
  });

  factory PriceList.fromJson(Map<String, dynamic> j) => PriceList(
    id: j['id'] as String? ?? '',
    name: j['name'] as String? ?? '-',
    channel: j['channel'] as String?,
    currency: j['currency'] as String?,
    effectiveFrom: j['effectiveFrom'] as String?,
    effectiveTo: j['effectiveTo'] as String?,
    active: j['active'] as bool? ?? false,
  );
}

class PriceListItem {
  final String id;
  final String variantId;
  final double price;
  final double minQty;

  const PriceListItem({
    required this.id,
    required this.variantId,
    required this.price,
    required this.minQty,
  });

  factory PriceListItem.fromJson(Map<String, dynamic> j) => PriceListItem(
    id: j['id'] as String? ?? '',
    variantId: j['variantId'] as String? ?? '',
    price: (j['price'] as num?)?.toDouble() ?? 0,
    minQty: (j['minQty'] as num?)?.toDouble() ?? 1,
  );
}

class Promotion {
  final String id;
  final String name;
  final String type;
  final double value;
  final double? minOrderAmount;
  final String? channel;
  final bool active;
  final String? startsAt;
  final String? endsAt;

  /// Application order, ascending — lower runs first. Which of two overlapping
  /// offers wins used to be an accident of a SQL sort that compared a
  /// percentage against a sum of money.
  final int priority;

  /// True when this promotion stops every promotion after it.
  final bool exclusive;

  /// Code the customer must present, or null when it applies on its own.
  final String? couponCode;
  final int? maxRedemptions;
  final int? maxPerCustomer;

  /// BOGO only: buy [buyQty], get [getQty] at [getDiscountPct] off.
  final double? buyQty;
  final double? getQty;
  final double? getDiscountPct;

  const Promotion({
    required this.id,
    required this.name,
    required this.type,
    required this.value,
    this.minOrderAmount,
    this.channel,
    required this.active,
    this.startsAt,
    this.endsAt,
    this.priority = 100,
    this.exclusive = false,
    this.couponCode,
    this.maxRedemptions,
    this.maxPerCustomer,
    this.buyQty,
    this.getQty,
    this.getDiscountPct,
  });

  /// How this promotion reads on one line of the list, its amounts as money
  /// in the business's [currency] (grouped, with no symbol, while it is not
  /// known).
  String summaryIn(String? currency) {
    String money(num v) => AppFormat.money(v, currencyCode: currency);
    switch (type) {
      case 'PERCENT':
        return '${AppFormat.count(value)}% off each item';
      case 'FLAT':
        return '${money(value)} off each item';
      case 'BASKET_PERCENT':
        return '${AppFormat.count(value)}% off the basket';
      case 'BASKET_FLAT':
        return '${money(value)} off the basket';
      case 'SPEND_THRESHOLD':
        return '${money(value)} off over ${money(minOrderAmount ?? 0)}';
      case 'BOGO':
        final free = (getDiscountPct ?? 0) >= 100;
        return 'Buy ${AppFormat.count(buyQty ?? 0)}, '
            'get ${AppFormat.count(getQty ?? 0)} '
            '${free ? 'free' : '${AppFormat.count(getDiscountPct ?? 0)}% off'}';
      default:
        return humanizeCode(type);
    }
  }

  /// [summaryIn] with the currency not known.
  String get summary => summaryIn(null);

  factory Promotion.fromJson(Map<String, dynamic> j) => Promotion(
    id: j['id'] as String? ?? '',
    name: j['name'] as String? ?? '-',
    type: j['type'] as String? ?? 'PERCENT',
    value: (j['value'] as num?)?.toDouble() ?? 0,
    minOrderAmount: (j['minOrderAmount'] as num?)?.toDouble(),
    channel: j['channel'] as String?,
    active: j['active'] as bool? ?? false,
    priority: (j['priority'] as num?)?.toInt() ?? 100,
    exclusive: j['exclusive'] as bool? ?? false,
    couponCode: j['couponCode'] as String?,
    maxRedemptions: (j['maxRedemptions'] as num?)?.toInt(),
    maxPerCustomer: (j['maxPerCustomer'] as num?)?.toInt(),
    buyQty: (j['buyQty'] as num?)?.toDouble(),
    getQty: (j['getQty'] as num?)?.toDouble(),
    getDiscountPct: (j['getDiscountPct'] as num?)?.toDouble(),
    startsAt: j['startsAt'] as String?,
    endsAt: j['endsAt'] as String?,
  );
}

class VatRate {
  final String code;
  final String name;
  final double rate;
  final bool exempt;
  final String? description;
  final String? effectiveFrom;

  const VatRate({
    required this.code,
    required this.name,
    required this.rate,
    required this.exempt,
    this.description,
    this.effectiveFrom,
  });

  factory VatRate.fromJson(Map<String, dynamic> j) => VatRate(
    code: j['code'] as String? ?? '',
    name: j['name'] as String? ?? '-',
    rate: (j['rate'] as num?)?.toDouble() ?? 0,
    exempt: j['exempt'] as bool? ?? false,
    description: j['description'] as String?,
    effectiveFrom: j['effectiveFrom'] as String?,
  );
}

// ── Providers ────────────────────────────────────────────────────────────────

final priceListsProvider = FutureProvider.autoDispose<List<PriceList>>((
  ref,
) async {
  final data = await fetchAllPages(
    ref.read(apiClientProvider).dio,
    '/${ApiConstants.pricing}/price-lists',
  );
  return data
      .map((e) => PriceList.fromJson(e as Map<String, dynamic>))
      .toList();
});

final priceListItemsProvider = FutureProvider.autoDispose
    .family<List<PriceListItem>, String>((ref, priceListId) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get('/${ApiConstants.pricing}/price-lists/$priceListId/items');
      final data = (resp.data['data'] as List?) ?? [];
      return data
          .map((e) => PriceListItem.fromJson(e as Map<String, dynamic>))
          .toList();
    });

final promotionsProvider = FutureProvider.autoDispose<List<Promotion>>((
  ref,
) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.pricing}/promotions');
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => Promotion.fromJson(e as Map<String, dynamic>))
      .toList();
});

final vatRatesProvider = FutureProvider.autoDispose<List<VatRate>>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.pricing}/vat-rates');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => VatRate.fromJson(e as Map<String, dynamic>)).toList();
});

/// HMRC MTD VAT return boxes 1–9 for a period (ISO-8601 timestamps).
class VatReturn {
  final double box1;
  final double box2;
  final double box3;
  final double box4;
  final double box5;
  final double box6;
  final double box7;
  final double box8;
  final double box9;
  final String? periodFrom;
  final String? periodTo;

  /// The boxes the server could not compute (SJ-D39) — shown as such, never
  /// as a figure someone might file.
  final List<int> notComputedBoxes;
  final bool fitToFile;
  final String? caveat;

  const VatReturn({
    required this.box1,
    required this.box2,
    required this.box3,
    required this.box4,
    required this.box5,
    required this.box6,
    required this.box7,
    required this.box8,
    required this.box9,
    this.periodFrom,
    this.periodTo,
    this.notComputedBoxes = const [],
    this.fitToFile = false,
    this.caveat,
  });

  factory VatReturn.fromJson(Map<String, dynamic> j) => VatReturn(
    box1: (j['box1'] as num?)?.toDouble() ?? 0,
    box2: (j['box2'] as num?)?.toDouble() ?? 0,
    box3: (j['box3'] as num?)?.toDouble() ?? 0,
    box4: (j['box4'] as num?)?.toDouble() ?? 0,
    box5: (j['box5'] as num?)?.toDouble() ?? 0,
    box6: (j['box6'] as num?)?.toDouble() ?? 0,
    box7: (j['box7'] as num?)?.toDouble() ?? 0,
    box8: (j['box8'] as num?)?.toDouble() ?? 0,
    box9: (j['box9'] as num?)?.toDouble() ?? 0,
    periodFrom: j['periodFrom'] as String?,
    periodTo: j['periodTo'] as String?,
    notComputedBoxes: [
      for (final b in (j['notComputedBoxes'] as List?) ?? const [])
        (b as num).toInt(),
    ],
    fitToFile: j['fitToFile'] as bool? ?? false,
    caveat: j['caveat'] as String?,
  );
}

/// ISO timestamp range for the VAT return query.
class VatReturnRange {
  final String from;
  final String to;
  const VatReturnRange({required this.from, required this.to});

  @override
  bool operator ==(Object other) =>
      other is VatReturnRange && other.from == from && other.to == to;

  @override
  int get hashCode => Object.hash(from, to);
}

/// Default: current UK VAT quarter (calendar quarter) in UTC.
VatReturnRange defaultVatReturnRange() {
  final now = DateTime.now().toUtc();
  final qMonth = ((now.month - 1) ~/ 3) * 3 + 1;
  final from = DateTime.utc(now.year, qMonth, 1);
  final to = DateTime.utc(
    qMonth == 10 ? now.year + 1 : now.year,
    qMonth == 10 ? 1 : qMonth + 3,
    1,
  );
  return VatReturnRange(from: from.toIso8601String(), to: to.toIso8601String());
}

final vatReturnProvider = FutureProvider.autoDispose
    .family<VatReturn, VatReturnRange>((ref, range) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(
            '/${ApiConstants.pricing}/vat-return',
            queryParameters: {'from': range.from, 'to': range.to},
          );
      return VatReturn.fromJson(resp.data['data'] as Map<String, dynamic>);
    });

// ── Making Tax Digital (18.5) ────────────────────────────────────────────────

/// The VAT number the business files under, how, and what the deployment offers.
class VatRegistration {
  final bool registered;
  final String? vrn;
  final String? provider;
  final bool connected;
  final String? connectedAt;
  final List<String> providers;
  final bool hmrcConfigured;
  const VatRegistration({
    required this.registered,
    this.vrn,
    this.provider,
    this.connected = false,
    this.connectedAt,
    this.providers = const [],
    this.hmrcConfigured = false,
  });
  factory VatRegistration.fromJson(Map<String, dynamic> j) => VatRegistration(
    registered: j['registered'] as bool? ?? false,
    vrn: j['vrn'] as String?,
    provider: j['provider'] as String?,
    connected: j['connected'] as bool? ?? false,
    connectedAt: j['connectedAt'] as String?,
    providers: [
      for (final p in (j['providers'] as List?) ?? const []) p as String,
    ],
    hmrcConfigured: j['hmrcConfigured'] as bool? ?? false,
  );
}

/// One of HMRC's obligations: a period to file for, open or fulfilled.
class VatObligation {
  final String periodKey;
  final String start;
  final String end;
  final String? due;
  final String status;
  const VatObligation({
    required this.periodKey,
    required this.start,
    required this.end,
    this.due,
    required this.status,
  });
  bool get open => status == 'O';
  factory VatObligation.fromJson(Map<String, dynamic> j) => VatObligation(
    periodKey: j['periodKey'] as String? ?? '',
    start: j['start'] as String? ?? '',
    end: j['end'] as String? ?? '',
    due: j['due'] as String?,
    status: j['status'] as String? ?? 'O',
  );
}

/// A return as filed, and what HMRC answered.
class VatSubmission {
  final String id;
  final String periodKey;
  final String status;
  final String submittedAt;
  final double box1;
  final double box5;
  final double box6;
  final String? formBundleNumber;
  final String? chargeRefNumber;
  final String? errorCode;
  final String? errorMessage;
  const VatSubmission({
    required this.id,
    required this.periodKey,
    required this.status,
    required this.submittedAt,
    required this.box1,
    required this.box5,
    required this.box6,
    this.formBundleNumber,
    this.chargeRefNumber,
    this.errorCode,
    this.errorMessage,
  });
  factory VatSubmission.fromJson(Map<String, dynamic> j) => VatSubmission(
    id: j['id'] as String? ?? '',
    periodKey: j['periodKey'] as String? ?? '',
    status: j['status'] as String? ?? '',
    submittedAt: j['submittedAt'] as String? ?? '',
    box1: (j['box1'] as num?)?.toDouble() ?? 0,
    box5: (j['box5'] as num?)?.toDouble() ?? 0,
    box6: (j['box6'] as num?)?.toDouble() ?? 0,
    formBundleNumber: j['formBundleNumber'] as String?,
    chargeRefNumber: j['chargeRefNumber'] as String?,
    errorCode: j['errorCode'] as String?,
    errorMessage: j['errorMessage'] as String?,
  );
}

final vatRegistrationProvider = FutureProvider.autoDispose<VatRegistration>((
  ref,
) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.pricing}/vat-return/mtd/registration');
  return VatRegistration.fromJson(resp.data['data'] as Map<String, dynamic>);
});

final vatObligationsProvider = FutureProvider.autoDispose
    .family<List<VatObligation>, VatReturnRange>((ref, range) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(
            '/${ApiConstants.pricing}/vat-return/mtd/obligations',
            queryParameters: {'from': range.from, 'to': range.to},
          );
      return ((resp.data['data'] as List?) ?? const [])
          .map((e) => VatObligation.fromJson(e as Map<String, dynamic>))
          .toList();
    });

final vatSubmissionsProvider = FutureProvider.autoDispose<List<VatSubmission>>((
  ref,
) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.pricing}/vat-return/mtd/submissions');
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => VatSubmission.fromJson(e as Map<String, dynamic>))
      .toList();
});
