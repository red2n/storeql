/// What the server says a basket costs, and the totals the till builds from it.
///
/// A basket is priced on the server (`POST /prices/quote`): promotions, a
/// spend threshold, a multi-buy and the VAT in every line are all decided
/// there, and an order is charged exactly what the quote said. The till
/// therefore tenders the quote's total, not a sum of its own; its own sum of
/// the shelf prices is only what it shows until the quote arrives and what it
/// falls back to with no server to ask.
library;

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import 'pos_providers.dart';
import 'pos_vat.dart';

/// One line of a quote: what it finally costs, VAT included, and the VAT in it.
class PosQuoteLine {
  final String variantId;
  final double gross;
  final double vat;
  final String? vatCode;
  final double? vatRate;
  const PosQuoteLine({
    required this.variantId,
    required this.gross,
    required this.vat,
    this.vatCode,
    this.vatRate,
  });
}

class PosQuote {
  /// What the goods cost, VAT included, after every offer.
  final double total;
  final double vatTotal;
  final double basketDiscount;

  /// True when the prices are shelf prices, VAT inside: the lines' VAT is then
  /// carried, not added, and a staff discount lowers it.
  final bool taxInclusive;
  final List<PosQuoteLine> lines;
  final List<PosVatRow> vatByRate;

  const PosQuote({
    required this.total,
    required this.vatTotal,
    required this.basketDiscount,
    required this.taxInclusive,
    required this.lines,
    required this.vatByRate,
  });

  /// Reads a quote. An answer with no total is not a quote of nothing: it is
  /// refused ([FormatException]), so a till is never told a basket is free
  /// because a server said something else.
  factory PosQuote.fromJson(Map<String, dynamic> d) {
    double n(Object? v) => (v as num?)?.toDouble() ?? 0;
    if (d['total'] is! num || d['lines'] is! List) {
      throw const FormatException('not a basket quote');
    }
    return PosQuote(
      total: n(d['total']),
      vatTotal: n(d['vatAmount']),
      basketDiscount: n(d['basketDiscount']),
      taxInclusive: d['taxInclusive'] == true,
      lines: [
        for (final l in (d['lines'] as List? ?? const []))
          PosQuoteLine(
            variantId: (l as Map)['variantId'] as String? ?? '',
            gross: n(l['lineGross'] ?? n(l['netTotal']) + n(l['vatAmount'])),
            vat: n(l['vatAmount']),
            vatCode: l['vatCode'] as String?,
            vatRate: (l['vatRate'] as num?)?.toDouble(),
          ),
      ],
      vatByRate: [
        for (final r in (d['vatByRate'] as List? ?? const []))
          PosVatRow(
            code: (r as Map)['vatCode'] as String?,
            rate: (r['rate'] as num?)?.toDouble(),
            gross: n(r['gross']),
            net: n(r['net']),
            vat: n(r['vat']),
          ),
      ],
    );
  }
}

/// The basket as the server prices it, for the lines in the till now. Null when
/// there is nothing to price or the server cannot be asked: the till then
/// shows, and falls back to, its own sum of the prices it was given.
final posQuoteProvider = FutureProvider.autoDispose<PosQuote?>((ref) async {
  final lines = productLines(ref.watch(posCartProvider));
  final store = ref.watch(posStoreProvider);
  if (lines.isEmpty) return null;
  try {
    final resp = await ref.read(apiClientProvider).dio.post(
      '/${ApiConstants.pricing}/prices/quote',
      data: {
        'storeId': ?store,
        'channel': 'POS',
        'lines': [
          for (final l in lines)
            {
              'variantId': l.variantId,
              'qty': l.qty,
              if (l.markdownId != null) 'markdownId': l.markdownId,
            },
        ],
      },
    );
    final quote = PosQuote.fromJson(resp.data['data'] as Map<String, dynamic>);
    // A quote of another basket (a line came or went while it was on its way)
    // is not this one's.
    return quote.lines.length == lines.length ? quote : null;
  } catch (_) {
    // No server, a refusal, or an answer that is not a quote: the till shows,
    // and falls back to, its own sum of the prices it was given.
    return null;
  }
});

/// What a sale comes to, from the lines in the till and, when it has arrived,
/// the server's quote of them.
class PosTotals {
  /// What the goods cost, VAT included: the quote's total, else the till's own
  /// sum of the prices it was given.
  final double goods;

  /// Gift cards being sold: their value, no VAT, never discounted.
  final double cards;

  /// The return-scheme deposits, due in full whatever the discount.
  final double deposits;

  /// Whether [goods] is the server's figure.
  final bool quoted;

  const PosTotals({
    required this.goods,
    required this.cards,
    required this.deposits,
    required this.quoted,
  });

  factory PosTotals.of(List<PosLine> lines, PosQuote? quote) {
    final products = productLines(lines);
    final local = products.fold<double>(0, (s, l) => s + l.lineTotal);
    return PosTotals(
      goods: quote != null && products.isNotEmpty ? quote.total : local,
      cards: lines.fold<double>(0, (s, l) => l.giftCard ? s + l.lineTotal : s),
      deposits: lines.fold<double>(0, (s, l) => s + l.depositTotal),
      quoted: quote != null && products.isNotEmpty,
    );
  }

  /// A discount as the till may take it: not below nothing, not above the goods.
  double discountOf(double asked) => asked.clamp(0, goods).toDouble();

  /// What is due: the goods less the discount, plus the cards and the deposits.
  double due(double asked) => goods - discountOf(asked) + cards + deposits;
}

/// The till's totals, kept current as the basket and its quote change.
final posTotalsProvider = Provider.autoDispose<PosTotals>((ref) {
  return PosTotals.of(
    ref.watch(posCartProvider),
    ref.watch(posQuoteProvider).value,
  );
});

/// The receipt document the server draws for a sale at shelf prices (`GET
/// /orders/{id}/receipt-document`), read for the seller and the VAT table.
/// Null when the server cannot be asked or the sale was priced net: the till
/// then makes the table itself ([offlineVatTable]) from what it rang up.
Future<PosReceiptVat?> fetchReceiptVat(Dio dio, String orderId) async {
  try {
    final resp = await dio.get(
      '/${ApiConstants.order}/orders/$orderId/receipt-document',
    );
    return PosReceiptVat.fromDocument(resp.data['data'] as Map<String, dynamic>);
  } catch (_) {
    return null;
  }
}
