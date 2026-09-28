import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/reference/iso_reference.dart';
import '../../core/format.dart';
import '../../core/network/api_error.dart';
import '../admin/providers/admin_providers.dart';
import 'pos_providers.dart';

// ---------------------------------------------------------------------------
// The till's age check.
//
// Selling an age-restricted item to someone under age is an offence in every
// market this platform trades in, and the defence is having taken "all
// reasonable precautions and exercised all due diligence". product-svc can say
// which items are restricted and from what age in which country; without a
// prompt at the till that knowledge protects nobody.
// ---------------------------------------------------------------------------

/// The answer to "may this item go into the sale without a check?".
///
/// Three outcomes, not two. [AgeCheckBlocked] is the one that matters: when the
/// till cannot find out whether an item is restricted — no country for the
/// store, no rule for that country, an answer it cannot read, a failed call —
/// the item is not sold. Treating "could not tell" as "not restricted" is how a
/// till sells alcohol to a child with every screen saying it asked.
sealed class AgeCheckResult {
  const AgeCheckResult();
}

class AgeCheckNotRestricted extends AgeCheckResult {
  const AgeCheckNotRestricted();
}

class AgeCheckRestricted extends AgeCheckResult {
  final String category;
  final int minimumAge;
  final String country;

  /// True when the age is the business's own policy rather than the legal
  /// minimum — product-svc lets a tenant be stricter, never laxer.
  final bool storePolicy;

  /// A date of birth rather than an age: anyone born on or after it is
  /// refused, however old they are — the UK's generational tobacco ban from
  /// 1 Jan 2027, or a business's own earlier policy. Null when there is none.
  final DateTime? bornBefore;

  /// True when [bornBefore] is the business's policy rather than the law.
  final bool bornBeforeStorePolicy;

  const AgeCheckRestricted({
    required this.category,
    required this.minimumAge,
    required this.country,
    required this.storePolicy,
    this.bornBefore,
    this.bornBeforeStorePolicy = false,
  });

  /// [bornBefore] as the API writes it, yyyy-mm-dd.
  String? get bornBeforeIso => bornBefore == null ? null : isoDay(bornBefore!);
}

/// A calendar date as yyyy-mm-dd, the form every age-rule date travels in.
String isoDay(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

final _isoDayPattern = RegExp(r'^\d{4}-\d{2}-\d{2}$');

class AgeCheckBlocked extends AgeCheckResult {
  final String message;
  const AgeCheckBlocked(this.message);
}

/// Asks product-svc whether [variantId] is age-restricted in [country].
Future<AgeCheckResult> checkAgeRestriction(
    Dio dio, String variantId, String? country) async {
  final cc = country?.trim().toUpperCase();
  if (cc == null || cc.length != 2) {
    // Not guessed. The same bottle is 18 in the UK, 20 in Japan and 21 in the
    // US; a default country would be a wrong answer somewhere.
    return const AgeCheckBlocked(
        "This store has no country set, so age-restricted items can't be "
        'checked. Set the country in Admin → Stores.');
  }
  try {
    final resp = await dio.get(
      '/${ApiConstants.product}/catalog/variants/$variantId/age-check',
      queryParameters: {'country': cc},
    );
    final data = (resp.data as Map?)?['data'] as Map?;
    final restricted = data?['restricted'];
    // The explicit flag, never the absence of minimumAge: a null field is left
    // out of the JSON entirely, so "no age given" cannot be told apart from a
    // field that went missing. Anything but a real boolean is not an answer.
    if (restricted == false) return const AgeCheckNotRestricted();
    if (restricted != true) {
      return const AgeCheckBlocked(
          "Couldn't tell whether this item is age-restricted. Scan it again.");
    }
    final age = (data?['minimumAge'] as num?)?.toInt();
    final category = data?['category'] as String?;
    if (age == null || category == null) {
      return const AgeCheckBlocked(
          "This item is age-restricted but its minimum age couldn't be read. "
          'Scan it again.');
    }
    // A cut-off that is present but unreadable is not "no cut-off": the item
    // stays out rather than being sold on the age alone.
    final rawCutoff = data?['bornBefore'];
    DateTime? bornBefore;
    if (rawCutoff != null) {
      final parsed = rawCutoff is String && _isoDayPattern.hasMatch(rawCutoff)
          ? DateTime.tryParse(rawCutoff)
          : null;
      if (parsed == null || isoDay(parsed) != rawCutoff) {
        return const AgeCheckBlocked(
            "This item has a date-of-birth rule that couldn't be read. "
            'Scan it again.');
      }
      bornBefore = parsed;
    }
    return AgeCheckRestricted(
      category: category,
      minimumAge: age,
      country: cc,
      storePolicy: data?['tenantOverride'] == true,
      bornBefore: bornBefore,
      bornBeforeStorePolicy: data?['bornBeforeTenantOverride'] == true,
    );
  } on DioException catch (e) {
    if (apiErrorCode(e) == 'PRODUCT_NO_AGE_RULE') {
      return AgeCheckBlocked(
          'This item is age-restricted, but no minimum age is set for $cc. '
          "It can't be sold until one is.");
    }
    return const AgeCheckBlocked(
        "Couldn't check the age restriction. Scan it again.");
  }
}

/// The country whose age rules apply at this till: the store's own, falling
/// back to the tenant's. Null when neither is a real country code.
Future<String?> resolveSaleCountry(WidgetRef ref) async {
  final storeId = ref.read(posStoreProvider);
  final stores = ref.read(posStoresProvider).value;
  if (storeId != null && stores != null) {
    for (final s in stores) {
      final c = s.country?.trim();
      if (s.id == storeId && c != null && c.length == 2) return c;
    }
  }
  try {
    final tenant = await ref.read(tenantInfoProvider.future);
    final c = tenant.country.trim();
    return c.length == 2 ? c : null;
  } catch (_) {
    return null;
  }
}

/// The check a cashier must make before an age-restricted item is added.
///
/// Not dismissable by tapping outside it: the cashier either confirms they have
/// checked, or refuses the sale.
/// What the cashier decided, and the detail the due-diligence record keeps.
///
/// A refusal must say why — that is the record a licensing officer asks for
/// first. A pass may say what was shown; Challenge 25 shops want it, the law
/// asks only for reasonable precautions.
sealed class AgeDecision {
  const AgeDecision();
}

class AgePassed extends AgeDecision {
  final String? idType;
  const AgePassed({this.idType});
}

class AgeRefused extends AgeDecision {
  final String reason;
  const AgeRefused(this.reason);
}

const ageRefusalReasons = <String, String>{
  'UNDER_AGE': 'Under age',
  'NO_ID': 'No ID shown',
  'ID_REJECTED': 'ID not accepted',
  'PROXY_SALE': 'Buying for someone under age',
  'BORN_AFTER_CUTOFF': 'Born on or after the cut-off date',
  'OTHER': 'Other',
};

/// The refusal reason that only makes sense when the rule has a birth-date
/// cut-off; offered only then.
const bornAfterCutoffReason = 'BORN_AFTER_CUTOFF';

const ageIdTypes = <String, String>{
  'PASSPORT': 'Passport',
  'DRIVING_LICENCE': 'Driving licence',
  'PASS_CARD': 'PASS card',
  'MILITARY_ID': 'Military ID',
  'NATIONAL_ID': 'National ID',
  'OTHER': 'Other',
};

/// Writes one check to the register. The decision stands whether or not the
/// write succeeds — a refusal is never turned into a sale by a network fault —
/// but the caller is told, because an unrecorded check is a precaution nobody
/// can show was taken.
Future<bool> recordAgeCheck(
  Dio dio, {
  required String storeId,
  required String variantId,
  required AgeCheckRestricted check,
  required AgeDecision decision,
  String? posSessionId,
}) async {
  try {
    await dio.post('/${ApiConstants.order}/pos/age-checks', data: {
      'storeId': storeId,
      'variantId': variantId,
      'category': check.category,
      'minimumAge': check.minimumAge,
      'country': check.country,
      'storePolicy': check.storePolicy,
      if (check.bornBefore != null) 'bornBefore': check.bornBeforeIso,
      if (check.bornBefore != null)
        'bornBeforeStorePolicy': check.bornBeforeStorePolicy,
      'outcome': decision is AgeRefused ? 'REFUSED' : 'PASSED',
      if (decision is AgeRefused) 'reason': decision.reason,
      if (decision is AgePassed && decision.idType != null)
        'idType': decision.idType,
      if (posSessionId != null && posSessionId.isNotEmpty)
        'posSessionId': posSessionId,
    });
    return true;
  } catch (_) {
    return false;
  }
}

class AgeVerificationDialog extends StatefulWidget {
  final String itemName;
  final AgeCheckRestricted check;

  const AgeVerificationDialog(
      {super.key, required this.itemName, required this.check});

  static String categoryLabel(String category) => switch (category) {
        'ALCOHOL' => 'Alcohol',
        'TOBACCO' => 'Tobacco',
        'NICOTINE_VAPE' => 'Vapes and nicotine products',
        'KNIVES' => 'Knives and bladed articles',
        'CORROSIVES' => 'Corrosive substances',
        'SOLVENTS' => 'Solvents',
        'FIREWORKS' => 'Fireworks',
        'LOTTERY' => 'Lottery',
        'VIDEO_18' => 'Age-rated film or game',
        'PETROL' => 'Petrol',
        _ => category,
      };

  @override
  State<AgeVerificationDialog> createState() => _AgeVerificationDialogState();
}

/// Returns an [AgeDecision], or null only if the dialog is somehow dismissed —
/// which the caller treats as a refusal without a record, never as a pass.
class _AgeVerificationDialogState extends State<AgeVerificationDialog> {
  String? _idType;
  bool _refusing = false;
  String? _reason;

  AgeCheckRestricted get check => widget.check;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return AlertDialog(
      icon: Icon(Icons.badge_outlined, color: cs.error),
      title: Text(_refusing ? 'Refusing the sale' : 'Age-restricted item'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(widget.itemName,
                style: const TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 2),
            Text(AgeVerificationDialog.categoryLabel(check.category)),
            const SizedBox(height: 16),
            Text('The customer must be ${check.minimumAge} or over.',
                style: text.titleMedium),
            const SizedBox(height: 4),
            Text(check.storePolicy
                ? 'Store policy in ${countryInSentence(check.country)} — stricter than the legal minimum.'
                : 'Legal minimum in ${countryInSentence(check.country)}.'),
            if (check.bornBefore != null) ...[
              const SizedBox(height: 12),
              Text('And born before ${AppFormat.date(check.bornBeforeIso)}.',
                  style: text.titleMedium),
              const SizedBox(height: 4),
              Text(check.bornBeforeStorePolicy
                  ? 'Store policy: anyone born on or after that date is refused, whatever their age.'
                  : 'The law in ${check.country}: anyone born on or after that date can never be sold this, whatever their age.'),
            ],
            const SizedBox(height: 12),
            if (!_refusing) ...[
              const Text(
                  "If you aren't sure, ask for photo ID. If they can't show it, "
                  'refuse the sale.'),
              const SizedBox(height: 12),
              Text('What did they show? (optional)',
                  style: text.labelLarge),
              const SizedBox(height: 6),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final e in ageIdTypes.entries)
                    ChoiceChip(
                      label: Text(e.value),
                      selected: _idType == e.key,
                      onSelected: (v) =>
                          setState(() => _idType = v ? e.key : null),
                    ),
                ],
              ),
            ] else ...[
              Text('Why is the sale refused?', style: text.labelLarge),
              const SizedBox(height: 4),
              const Text(
                  'This is recorded. A refusal with its reason is the record '
                  'that shows the till was checking.'),
              const SizedBox(height: 6),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final e in ageRefusalReasons.entries)
                    if (e.key != bornAfterCutoffReason ||
                        check.bornBefore != null)
                    ChoiceChip(
                      label: Text(e.value),
                      selected: _reason == e.key,
                      onSelected: (v) =>
                          setState(() => _reason = v ? e.key : null),
                    ),
                ],
              ),
            ],
          ],
        ),
      ),
      actions: _refusing
          ? [
              TextButton(
                onPressed: () => setState(() => _refusing = false),
                child: const Text('Back'),
              ),
              FilledButton(
                style: FilledButton.styleFrom(backgroundColor: cs.error),
                onPressed: _reason == null
                    ? null
                    : () => Navigator.pop(context, AgeRefused(_reason!)),
                child: const Text('Record refusal'),
              ),
            ]
          : [
              TextButton(
                onPressed: () => setState(() => _refusing = true),
                child: const Text('Refuse sale'),
              ),
              FilledButton(
                onPressed: () =>
                    Navigator.pop(context, AgePassed(idType: _idType)),
                child: Text(check.bornBefore == null
                    ? 'Checked — ${check.minimumAge}+'
                    : 'Checked — ${check.minimumAge}+, born before ${AppFormat.date(check.bornBeforeIso)}'),
              ),
            ],
    );
  }
}
