import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import 'variable_measure_barcode.dart';

// The weighing instruments a store may sell by weight on (Weights and
// Measures Act 1985 s.11). The register lives in tenant-svc; the till reads the
// certified ones before it prices anything by weight, and refuses when there
// are none — selling by weight on an instrument that has not been passed as
// fit for trade is an offence, and so is doing it on one whose stamp a repair
// has broken.
//
// The till is not the only check: order-svc asks the same register about every
// instrument an order names and refuses one this store does not hold, or holds
// uncertified (409 ORDER_SCALE_NOT_CERTIFIED). It lets the sale through when
// tenant-svc cannot answer, so the till's picker still comes first. A sale this
// till weighed while offline is recorded when the queue replays it within
// order-svc's grace, and a scale that was not fit then is flagged for a manager
// on the audit trail instead of refused.

class WeighingInstrument {
  final String id;
  final String identifier;
  final String serialNumber;
  final String kind;
  final String status;
  final String standing;
  final bool certified;
  final String? make;
  final String? model;
  final String? certificateRef;
  final String? nextDue;
  final LabelScheme? labelScheme;

  const WeighingInstrument({
    required this.id,
    required this.identifier,
    required this.serialNumber,
    required this.kind,
    required this.status,
    required this.standing,
    required this.certified,
    this.make,
    this.model,
    this.certificateRef,
    this.nextDue,
    this.labelScheme,
  });

  bool get isLabelling => kind == 'LABELLING';

  factory WeighingInstrument.fromJson(Map<String, dynamic> j) {
    final latest = j['latestVerification'] as Map<String, dynamic>?;
    LabelScheme? scheme;
    final rawScheme = j['labelScheme'];
    if (rawScheme is String && rawScheme.isNotEmpty) {
      try {
        scheme = LabelScheme.fromJson(
            Map<String, dynamic>.from(jsonDecode(rawScheme) as Map));
      } catch (_) {
        scheme = null;
      }
    }
    return WeighingInstrument(
      id: j['id'] as String? ?? '',
      identifier: j['identifier'] as String? ?? '',
      serialNumber: j['serialNumber'] as String? ?? '',
      kind: j['kind'] as String? ?? 'COUNTER',
      status: j['status'] as String? ?? '',
      standing: j['standing'] as String? ?? 'NEVER_VERIFIED',
      certified: j['certified'] as bool? ?? false,
      make: j['make'] as String?,
      model: j['model'] as String?,
      certificateRef: latest?['certificateRef'] as String?,
      nextDue: latest?['nextDue'] as String?,
      labelScheme: scheme,
    );
  }

}

String standingLabel(String standing) => switch (standing) {
      'CERTIFIED' => 'Certified for trade',
      'NEVER_VERIFIED' => 'Never verified',
      'FAILED' => 'Failed its last check',
      'REPAIRED_SINCE' => 'Repaired since last verified',
      'OVERDUE' => 'Re-verification overdue',
      'OUT_OF_SERVICE' => 'Out of service',
      'RETIRED' => 'Retired',
      _ => standing,
    };

/// The instruments certified for trade at one store — what the till may sell
/// by weight on. Kept alive while the till is open so a scan does not wait on
/// the network; refreshed when the store changes.
final certifiedInstrumentsProvider =
    FutureProvider.family<List<WeighingInstrument>, String>(
        // No automatic retry: a cashier at a till gets the answer now, and a
        // refusal with the reason beats a spinner while Riverpod backs off.
        retry: (count, error) => null, (ref, storeId) async {
  final dio = ref.watch(apiClientProvider).dio;
  final resp = await dio.get(
    '/${ApiConstants.tenant}/admin/stores/$storeId/weighing-instruments',
    queryParameters: {'certified': true},
  );
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => WeighingInstrument.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// Every instrument at a store, certified or not — the admin register.
final storeInstrumentsProvider =
    FutureProvider.autoDispose.family<List<WeighingInstrument>, String>(
        retry: (count, error) => null, (ref, storeId) async {
  final dio = ref.watch(apiClientProvider).dio;
  final resp = await dio
      .get('/${ApiConstants.tenant}/admin/stores/$storeId/weighing-instruments');
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => WeighingInstrument.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// Asks which certified instrument the reading came from. Returns null when
/// the cashier backs out — and never offers an uncertified one.
Future<WeighingInstrument?> pickInstrument(
    BuildContext context, List<WeighingInstrument> certified,
    {required String itemName}) {
  final counters = certified.where((i) => !i.isLabelling).toList();
  if (counters.length == 1) return Future.value(counters.first);
  return showDialog<WeighingInstrument>(
    context: context,
    barrierDismissible: false,
    builder: (ctx) => AlertDialog(
      title: const Text('Which scale?'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(itemName, style: const TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          const Text('Only a scale that is certified for trade is offered.'),
          const SizedBox(height: 8),
          for (final i in counters)
            ListTile(
              leading: const Icon(Icons.scale_outlined),
              title: Text(i.identifier),
              subtitle: Text([
                if (i.make != null) i.make!,
                if (i.model != null) i.model!,
                if (i.certificateRef != null) 'cert. ${i.certificateRef}',
              ].join(' · ')),
              onTap: () => Navigator.pop(ctx, i),
            ),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
      ],
    ),
  );
}

/// The message the till shows when it cannot sell by weight here.
const noCertifiedScaleMessage =
    "This store has no scale certified for trade, so nothing can be sold by "
    "weight. A manager registers and verifies scales under Admin → Stores → "
    "Instruments. (Weights and Measures Act 1985 s.11)";

/// The message when the store's certified instruments could not be read at
/// all — whatever went wrong. A till that cannot find out refuses to sell by
/// weight; it does not guess.
String instrumentsUnavailableMessage(Object error) {
  return "Couldn't check which scales are certified. Try again; if it "
      "persists, sell by weight only once a manager has confirmed the scale.";
}
