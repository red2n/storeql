import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';

// ── Date-code markdown: reduce to clear (05.4) and the ladder that plans it
// (03.9). The morning's job on a fresh counter: what is coming up to its date,
// what the ladder says to sticker it at, the sticker issued, and the sticker
// taken off again. Everything money-shaped comes from pricing-svc; nothing
// here computes a price.

class MarkdownStep {
  final int daysToExpiry;
  final double percentOff;
  const MarkdownStep({required this.daysToExpiry, required this.percentOff});

  factory MarkdownStep.fromJson(Map<String, dynamic> j) => MarkdownStep(
    daysToExpiry: (j['daysToExpiry'] as num?)?.toInt() ?? 0,
    percentOff: (j['percentOff'] as num?)?.toDouble() ?? 0,
  );

  Map<String, dynamic> toJson() => {
    'daysToExpiry': daysToExpiry,
    'percentOff': percentOff,
  };
}

class MarkdownLadder {
  final String? storeId;

  /// STORE, TENANT or DEFAULT: where the steps came from.
  final String source;
  final List<MarkdownStep> steps;
  const MarkdownLadder({
    this.storeId,
    required this.source,
    required this.steps,
  });

  factory MarkdownLadder.fromJson(Map<String, dynamic> j) => MarkdownLadder(
    storeId: j['storeId'] as String?,
    source: j['source'] as String? ?? 'DEFAULT',
    steps: ((j['steps'] as List?) ?? [])
        .map((e) => MarkdownStep.fromJson(e as Map<String, dynamic>))
        .toList(),
  );
}

class Markdown {
  final String id;
  final String storeId;
  final String variantId;
  final String? batchId;
  final String? batchNo;
  final String expiryDate;
  final double qty;
  final double redeemedQty;
  final double remainingQty;
  final String currency;
  final double originalPrice;
  final double markdownPrice;
  final double percentOff;
  final String reason;
  final String labelCode;

  /// ACTIVE, EXPIRED or CANCELLED.
  final String status;
  final String? createdAt;
  final String? cancelReason;

  const Markdown({
    required this.id,
    required this.storeId,
    required this.variantId,
    this.batchId,
    this.batchNo,
    required this.expiryDate,
    required this.qty,
    required this.redeemedQty,
    required this.remainingQty,
    required this.currency,
    required this.originalPrice,
    required this.markdownPrice,
    required this.percentOff,
    required this.reason,
    required this.labelCode,
    required this.status,
    this.createdAt,
    this.cancelReason,
  });

  factory Markdown.fromJson(Map<String, dynamic> j) => Markdown(
    id: j['id'] as String? ?? '',
    storeId: j['storeId'] as String? ?? '',
    variantId: j['variantId'] as String? ?? '',
    batchId: j['batchId'] as String?,
    batchNo: j['batchNo'] as String?,
    expiryDate: j['expiryDate'] as String? ?? '',
    qty: (j['qty'] as num?)?.toDouble() ?? 0,
    redeemedQty: (j['redeemedQty'] as num?)?.toDouble() ?? 0,
    remainingQty: (j['remainingQty'] as num?)?.toDouble() ?? 0,
    currency: j['currency'] as String? ?? '',
    originalPrice: (j['originalPrice'] as num?)?.toDouble() ?? 0,
    markdownPrice: (j['markdownPrice'] as num?)?.toDouble() ?? 0,
    percentOff: (j['percentOff'] as num?)?.toDouble() ?? 0,
    reason: j['reason'] as String? ?? '',
    labelCode: j['labelCode'] as String? ?? '',
    status: j['status'] as String? ?? '',
    createdAt: j['createdAt'] as String?,
    cancelReason: j['cancelReason'] as String?,
  );
}

/// One line of the morning's plan: a batch coming up to its date and what the
/// ladder says to do with it.
class MarkdownSuggestion {
  final String batchId;
  final String variantId;
  final String? batchNo;
  final String? expiryDate;
  final int daysToExpiry;
  final double remainingQty;
  final double? currentPrice;
  final String? currency;
  final int? stepDays;
  final double? percentOff;
  final double? suggestedPrice;
  final Markdown? existing;

  const MarkdownSuggestion({
    required this.batchId,
    required this.variantId,
    this.batchNo,
    this.expiryDate,
    required this.daysToExpiry,
    required this.remainingQty,
    this.currentPrice,
    this.currency,
    this.stepDays,
    this.percentOff,
    this.suggestedPrice,
    this.existing,
  });

  factory MarkdownSuggestion.fromJson(Map<String, dynamic> j) =>
      MarkdownSuggestion(
        batchId: j['batchId'] as String? ?? '',
        variantId: j['variantId'] as String? ?? '',
        batchNo: j['batchNo'] as String?,
        expiryDate: j['expiryDate'] as String?,
        daysToExpiry: (j['daysToExpiry'] as num?)?.toInt() ?? 0,
        remainingQty: (j['remainingQty'] as num?)?.toDouble() ?? 0,
        currentPrice: (j['currentPrice'] as num?)?.toDouble(),
        currency: j['currency'] as String?,
        stepDays: (j['stepDays'] as num?)?.toInt(),
        percentOff: (j['percentOff'] as num?)?.toDouble(),
        suggestedPrice: (j['suggestedPrice'] as num?)?.toDouble(),
        existing: j['existing'] is Map<String, dynamic>
            ? Markdown.fromJson(j['existing'] as Map<String, dynamic>)
            : null,
      );
}

class MarkdownPlan {
  final String storeId;
  final int withinDays;
  final String ladderSource;

  /// False when inventory-svc could not be read: the list is then empty rather
  /// than wrong, and the screen says so.
  final bool inventoryReachable;
  final List<MarkdownSuggestion> suggestions;

  const MarkdownPlan({
    required this.storeId,
    required this.withinDays,
    required this.ladderSource,
    required this.inventoryReachable,
    required this.suggestions,
  });

  factory MarkdownPlan.fromJson(Map<String, dynamic> j) => MarkdownPlan(
    storeId: j['storeId'] as String? ?? '',
    withinDays: (j['withinDays'] as num?)?.toInt() ?? 7,
    ladderSource: j['ladderSource'] as String? ?? 'DEFAULT',
    inventoryReachable: j['inventoryReachable'] == true,
    suggestions: ((j['suggestions'] as List?) ?? [])
        .map((e) => MarkdownSuggestion.fromJson(e as Map<String, dynamic>))
        .toList(),
  );
}

/// The plan for a store: every batch expiring within the horizon.
final markdownPlanProvider = FutureProvider.autoDispose
    .family<MarkdownPlan, ({String storeId, int withinDays})>((ref, a) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(
            '/${ApiConstants.pricing}/markdowns/plan',
            queryParameters: {'storeId': a.storeId, 'withinDays': a.withinDays},
          );
      return MarkdownPlan.fromJson(resp.data['data'] as Map<String, dynamic>);
    });

/// A store's markdowns, newest first; [status] ACTIVE, EXPIRED, CANCELLED or
/// empty for all.
final markdownsProvider = FutureProvider.autoDispose
    .family<List<Markdown>, ({String storeId, String status})>((ref, a) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(
            '/${ApiConstants.pricing}/markdowns',
            queryParameters: {
              'storeId': a.storeId,
              if (a.status.isNotEmpty) 'status': a.status,
            },
          );
      final data = (resp.data['data'] as List?) ?? [];
      return data
          .map((e) => Markdown.fromJson(e as Map<String, dynamic>))
          .toList();
    });

/// The ladder that applies at a store (empty id: the business's own).
final markdownLadderProvider = FutureProvider.autoDispose
    .family<MarkdownLadder, String>((ref, storeId) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(
            '/${ApiConstants.pricing}/markdowns/ladder',
            queryParameters: {if (storeId.isNotEmpty) 'storeId': storeId},
          );
      return MarkdownLadder.fromJson(resp.data['data'] as Map<String, dynamic>);
    });

/// Stickers a batch. Give [percentOff] or [markdownPrice], not both; the
/// server derives the other from the current POS price and issues the code.
Future<Markdown> createMarkdown(
  WidgetRef ref, {
  required String storeId,
  required String variantId,
  String? batchId,
  String? batchNo,
  required String expiryDate,
  // The plain decimals typed ([plainDecimal]): JSON-B reads them exactly.
  required String qty,
  String? percentOff,
  String? markdownPrice,
  required String reason,
}) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .post(
        '/${ApiConstants.pricing}/markdowns',
        data: {
          'storeId': storeId,
          'variantId': variantId,
          'batchId': ?batchId,
          if (batchNo != null && batchNo.isNotEmpty) 'batchNo': batchNo,
          'expiryDate': expiryDate,
          'qty': qty,
          'percentOff': ?percentOff,
          'markdownPrice': ?markdownPrice,
          'reason': reason,
        },
      );
  return Markdown.fromJson(resp.data['data'] as Map<String, dynamic>);
}

/// Takes the stickers off: the code stops scanning.
Future<Markdown> cancelMarkdown(WidgetRef ref, String id, String reason) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .post(
        '/${ApiConstants.pricing}/markdowns/$id/cancel',
        data: {'reason': reason},
      );
  return Markdown.fromJson(resp.data['data'] as Map<String, dynamic>);
}

/// Replaces the ladder for a store, or the business's when [storeId] is empty.
Future<MarkdownLadder> setMarkdownLadder(
  WidgetRef ref,
  String storeId,
  List<MarkdownStep> steps,
) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .put(
        '/${ApiConstants.pricing}/markdowns/ladder',
        data: {
          if (storeId.isNotEmpty) 'storeId': storeId,
          'steps': steps.map((s) => s.toJson()).toList(),
        },
      );
  return MarkdownLadder.fromJson(resp.data['data'] as Map<String, dynamic>);
}
