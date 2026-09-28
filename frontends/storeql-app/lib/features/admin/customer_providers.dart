import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';

// ── Models ───────────────────────────────────────────────────────────────────

class Customer {
  final String id;
  final String email;
  final String? phone;
  final String firstName;
  final String lastName;
  final String status;
  final String? dob;
  final String? gender;

  const Customer({
    required this.id,
    required this.email,
    this.phone,
    required this.firstName,
    required this.lastName,
    required this.status,
    this.dob,
    this.gender,
  });

  String get fullName => '$firstName $lastName'.trim();

  factory Customer.fromJson(Map<String, dynamic> j) => Customer(
        id: j['id'] as String? ?? '',
        email: j['email'] as String? ?? '',
        phone: j['phone'] as String?,
        firstName: j['firstName'] as String? ?? '',
        lastName: j['lastName'] as String? ?? '',
        status: j['status'] as String? ?? '',
        dob: j['dob'] as String?,
        gender: j['gender'] as String?,
      );
}

class CustomerAddress {
  final String id;
  final String type;
  final String line1;
  final String? line2;
  final String? city;
  final String? state;
  final String country;
  final String? pincode;
  final bool isDefault;

  const CustomerAddress({
    required this.id,
    required this.type,
    required this.line1,
    this.line2,
    this.city,
    this.state,
    required this.country,
    this.pincode,
    required this.isDefault,
  });

  String get oneLine => [line1, line2, city, state, pincode, country]
      .where((e) => e != null && e.isNotEmpty)
      .join(', ');

  factory CustomerAddress.fromJson(Map<String, dynamic> j) => CustomerAddress(
        id: j['id'] as String? ?? '',
        type: j['type'] as String? ?? 'HOME',
        line1: j['line1'] as String? ?? '',
        line2: j['line2'] as String?,
        city: j['city'] as String?,
        state: j['state'] as String?,
        country: j['country'] as String? ?? '',
        pincode: j['pincode'] as String?,
        isDefault: j['isDefault'] as bool? ?? false,
      );
}

/// The next rung of the ladder and how far it is.
class NextTier {
  final String name;
  final double threshold;
  final double pointsToGo;
  const NextTier({required this.name, required this.threshold, required this.pointsToGo});

  factory NextTier.fromJson(Map<String, dynamic> j) => NextTier(
        name: j['name'] as String? ?? '',
        threshold: (j['threshold'] as num?)?.toDouble() ?? 0,
        pointsToGo: (j['pointsToGo'] as num?)?.toDouble() ?? 0,
      );
}

/// Points that die within thirty days, and the first day any do.
class ExpiringSoon {
  final double points;
  final String on;
  const ExpiringSoon({required this.points, required this.on});

  factory ExpiringSoon.fromJson(Map<String, dynamic> j) => ExpiringSoon(
        points: (j['points'] as num?)?.toDouble() ?? 0,
        on: j['on'] as String? ?? '',
      );

  /// The day, for a sentence: `2026-10-23`.
  String get day => on.length >= 10 ? on.substring(0, 10) : on;
}

/// A customer's points under the business's programme (13.x).
class LoyaltyAccount {
  final double pointsBalance;
  final double lifetimePoints;
  final String? tier;
  final String? tierSince;
  final double qualifyingPoints;
  final double multiplier;
  final NextTier? nextTier;
  final ExpiringSoon? expiringSoon;
  final int? expiryMonths;

  const LoyaltyAccount({
    required this.pointsBalance,
    required this.lifetimePoints,
    this.tier,
    this.tierSince,
    this.qualifyingPoints = 0,
    this.multiplier = 1,
    this.nextTier,
    this.expiringSoon,
    this.expiryMonths,
  });

  factory LoyaltyAccount.fromJson(Map<String, dynamic> j) => LoyaltyAccount(
        pointsBalance: (j['pointsBalance'] as num?)?.toDouble() ?? 0,
        lifetimePoints: (j['lifetimePoints'] as num?)?.toDouble() ?? 0,
        tier: j['tier'] as String?,
        tierSince: j['tierSince'] as String?,
        qualifyingPoints: (j['qualifyingPoints'] as num?)?.toDouble() ?? 0,
        multiplier: (j['multiplier'] as num?)?.toDouble() ?? 1,
        nextTier: j['nextTier'] is Map<String, dynamic>
            ? NextTier.fromJson(j['nextTier'] as Map<String, dynamic>)
            : null,
        expiringSoon: j['expiringSoon'] is Map<String, dynamic>
            ? ExpiringSoon.fromJson(j['expiringSoon'] as Map<String, dynamic>)
            : null,
        expiryMonths: (j['expiryMonths'] as num?)?.toInt(),
      );

  /// `SILVER · GOLD in 38 pts · ×1.5` — the tier, the way up, the benefit.
  String get tierLine {
    final parts = <String>[
      if (tier != null && tier!.isNotEmpty) tier!,
      if (nextTier != null) '${nextTier!.name} in ${_pts(nextTier!.pointsToGo)}',
      if (multiplier != 1) '×${_trim(multiplier)}',
    ];
    return parts.join(' · ');
  }

  /// `20 pts expire 23 Oct 2026`, or empty when nothing is about to.
  String get expiringLine => expiringSoon == null
      ? ''
      : '${_pts(expiringSoon!.points)} expire ${AppFormat.date(expiringSoon!.on)}';

  static String _pts(double v) => '${_trim(v)} pts';
  static String _trim(double v) => v == v.roundToDouble()
      ? v.toStringAsFixed(0)
      : v.toStringAsFixed(2).replaceFirst(RegExp(r'0+$'), '');
}

/// One rung of the business's ladder.
class LoyaltyTier {
  final String name;
  final double threshold;
  final double multiplier;
  const LoyaltyTier({required this.name, required this.threshold, required this.multiplier});

  factory LoyaltyTier.fromJson(Map<String, dynamic> j) => LoyaltyTier(
        name: j['name'] as String? ?? '',
        threshold: (j['threshold'] as num?)?.toDouble() ?? 0,
        multiplier: (j['multiplier'] as num?)?.toDouble() ?? 1,
      );

  Map<String, dynamic> toJson() => {'name': name, 'threshold': threshold, 'multiplier': multiplier};
}

/// The business's loyalty programme (13.x), or the platform's default.
class LoyaltyProgramme {
  final int? expiryMonths;
  final int? qualifyingMonths;
  final List<LoyaltyTier> tiers;
  final String? reason;
  final String? setAt;
  final bool isDefault;

  const LoyaltyProgramme({
    this.expiryMonths,
    this.qualifyingMonths,
    required this.tiers,
    this.reason,
    this.setAt,
    this.isDefault = true,
  });

  factory LoyaltyProgramme.fromJson(Map<String, dynamic> j) => LoyaltyProgramme(
        expiryMonths: (j['expiryMonths'] as num?)?.toInt(),
        qualifyingMonths: (j['qualifyingMonths'] as num?)?.toInt(),
        tiers: ((j['tiers'] as List?) ?? [])
            .map((e) => LoyaltyTier.fromJson(e as Map<String, dynamic>))
            .toList(),
        reason: j['reason'] as String?,
        setAt: j['setAt'] as String?,
        isDefault: j['isDefault'] as bool? ?? true,
      );
}

/// What a loyalty sweep did.
class LoyaltySweepResult {
  final int customers;
  final double points;
  final int retiered;
  const LoyaltySweepResult({required this.customers, required this.points, required this.retiered});

  factory LoyaltySweepResult.fromJson(Map<String, dynamic> j) => LoyaltySweepResult(
        customers: (j['customers'] as num?)?.toInt() ?? 0,
        points: (j['points'] as num?)?.toDouble() ?? 0,
        retiered: (j['retiered'] as num?)?.toInt() ?? 0,
      );
}

/// The programme in force; management reads it.
final loyaltyProgrammeProvider = FutureProvider.autoDispose<LoyaltyProgramme>((ref) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.customer}/admin/loyalty/programme');
  return LoyaltyProgramme.fromJson(resp.data['data'] as Map<String, dynamic>);
});

class LoyaltyLedgerEntry {
  final String type;
  final double points;
  final double balanceAfter;
  final String? reason;
  final String createdAt;

  const LoyaltyLedgerEntry({
    required this.type,
    required this.points,
    required this.balanceAfter,
    this.reason,
    required this.createdAt,
  });

  factory LoyaltyLedgerEntry.fromJson(Map<String, dynamic> j) => LoyaltyLedgerEntry(
        type: j['type'] as String? ?? '',
        points: (j['points'] as num?)?.toDouble() ?? 0,
        balanceAfter: (j['balanceAfter'] as num?)?.toDouble() ?? 0,
        reason: j['reason'] as String?,
        createdAt: j['createdAt'] as String? ?? '',
      );
}

class StoreCreditAccount {
  final double balance;
  final String currency;

  const StoreCreditAccount({required this.balance, required this.currency});

  factory StoreCreditAccount.fromJson(Map<String, dynamic> j) => StoreCreditAccount(
        balance: (j['balance'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
      );
}

// ── Providers ────────────────────────────────────────────────────────────────

final customersProvider = FutureProvider.autoDispose<List<Customer>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.customer}/customers',
      queryParameters: {'limit': 100});
  final items = (resp.data['data']?['items'] as List?) ?? [];
  return items.map((e) => Customer.fromJson(e as Map<String, dynamic>)).toList();
});

/// A single customer's full record (refreshes the detail view after an edit).
final customerDetailProvider =
    FutureProvider.autoDispose.family<Customer, String>((ref, id) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.customer}/customers/$id');
  return Customer.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// A customer's saved addresses (ship-to / billing).
final customerAddressesProvider = FutureProvider.autoDispose
    .family<List<CustomerAddress>, String>((ref, customerId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.customer}/customers/$customerId/addresses');
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => CustomerAddress.fromJson(e as Map<String, dynamic>))
      .toList();
});

final customerLoyaltyProvider =
    FutureProvider.autoDispose.family<LoyaltyAccount, String>((ref, customerId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.customer}/customers/$customerId/loyalty');
  return LoyaltyAccount.fromJson(resp.data['data'] as Map<String, dynamic>);
});

final customerLoyaltyLedgerProvider = FutureProvider.autoDispose
    .family<List<LoyaltyLedgerEntry>, String>((ref, customerId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.customer}/customers/$customerId/loyalty/ledger');
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => LoyaltyLedgerEntry.fromJson(e as Map<String, dynamic>))
      .toList();
});

final customerStoreCreditProvider = FutureProvider.autoDispose
    .family<StoreCreditAccount, String>((ref, customerId) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.customer}/customers/$customerId/store-credit');
  return StoreCreditAccount.fromJson(resp.data['data'] as Map<String, dynamic>);
});
