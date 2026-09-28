import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';

/// A plan on sale to the public (21.13): what a prospect reads before signing
/// up, with no login — the price, what it includes, how long the trial is, and
/// which plan the platform starts a business on when it does not choose.
class PublicPlan {
  final String id;
  final String code;
  final String name;
  final String? description;
  final String billingInterval;
  final int trialDays;
  final bool isDefault;
  final List<PublicPlanPrice> prices;
  final List<String> includes;

  const PublicPlan({
    required this.id,
    required this.code,
    required this.name,
    required this.description,
    required this.billingInterval,
    required this.trialDays,
    required this.isDefault,
    required this.prices,
    required this.includes,
  });

  factory PublicPlan.fromJson(Map<String, dynamic> j) => PublicPlan(
        id: j['id'] as String,
        code: j['code'] as String? ?? '',
        name: j['name'] as String? ?? '',
        description: j['description'] as String?,
        billingInterval: j['billingInterval'] as String? ?? 'MONTH',
        trialDays: (j['trialDays'] as num?)?.toInt() ?? 0,
        isDefault: j['isDefault'] == true,
        prices: [
          for (final p in j['prices'] as List<dynamic>? ?? const [])
            PublicPlanPrice.fromJson(p as Map<String, dynamic>),
        ],
        includes: [
          for (final g in j['includes'] as List<dynamic>? ?? const [])
            (g as Map<String, dynamic>)['label'] as String? ?? (g['key'] as String? ?? ''),
        ],
      );

  /// What it costs each period: in [currency] when it is priced in it, else in
  /// the first currency it is priced in; a plan with no price is free.
  String priceLine(String? currency) {
    if (prices.isEmpty) return 'free';
    final price = prices.firstWhere(
      (p) => p.currency == currency,
      orElse: () => prices.first,
    );
    final each = billingInterval == 'YEAR' ? 'year' : 'month';
    return '${AppFormat.money(price.amount, currencyCode: price.currency)} a $each';
  }

  /// The one line a prospect chooses by: `Starter — £49.00 a month · 14-day free trial`.
  String label(String? currency) {
    final trial = trialDays > 0 ? '$trialDays-day free trial' : 'no free trial';
    return '$name — ${priceLine(currency)} · $trial';
  }

  /// The plan a business starts on when it does not choose: the platform's
  /// default, else the first on sale; nothing when nothing is on sale.
  static String? defaultId(List<PublicPlan> plans) {
    if (plans.isEmpty) return null;
    return plans.firstWhere((p) => p.isDefault, orElse: () => plans.first).id;
  }
}

class PublicPlanPrice {
  final String currency;
  final num amount;
  const PublicPlanPrice({required this.currency, required this.amount});

  factory PublicPlanPrice.fromJson(Map<String, dynamic> j) => PublicPlanPrice(
        currency: j['currency'] as String? ?? '',
        amount: j['amount'] as num? ?? 0,
      );
}

/// The price list, read with no login from `GET /tenant-svc/plans`.
///
/// Read once: a list that cannot be read is said so on the form, and the
/// business signs up on the platform's default plan rather than waiting on a
/// retry loop.
final publicPlansProvider = FutureProvider.autoDispose<List<PublicPlan>>(
  retry: (_, _) => null,
  (ref) async {
  final dio = ref.watch(apiClientProvider).dio;
  final resp = await dio.get('/${ApiConstants.tenant}/plans');
  final list = resp.data['data'] as List<dynamic>? ?? const [];
  return [for (final j in list) PublicPlan.fromJson(j as Map<String, dynamic>)];
});
