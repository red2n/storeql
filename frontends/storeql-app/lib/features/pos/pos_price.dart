/// A price as pricing-svc resolves it for the till, read once so every way a
/// line gets into the basket (a scan, a tap on a product, a sticker) reads it
/// the same way.
///
/// The till charges what the customer pays: the price **with** VAT. The
/// resolved `unitPrice` is the price before VAT; `totalWithVat` is the price
/// the customer pays, and on a shop that sells at shelf prices
/// (`taxInclusive`) it is exactly the shelf price. A till that read `unitPrice`
/// would tender the wrong sum for every taxed item.
library;

class ResolvedPosPrice {
  /// What one unit costs the customer, VAT included.
  final double gross;

  /// The VAT code and rate the price was taxed at, when pricing-svc said.
  final String? vatCode;
  final double? vatRate;

  final String currency;

  /// Whether the price is a shelf price (VAT inside it) rather than a net
  /// price with VAT added.
  final bool taxInclusive;

  const ResolvedPosPrice({
    required this.gross,
    required this.currency,
    this.vatCode,
    this.vatRate,
    this.taxInclusive = false,
  });

  /// Reads `POST /prices/resolve`. An older pricing-svc that sends no
  /// `totalWithVat` is read as before, from `unitPrice`.
  factory ResolvedPosPrice.fromJson(Map<String, dynamic> p) {
    final gross = (p['totalWithVat'] as num?) ?? (p['unitPrice'] as num?) ?? 0;
    return ResolvedPosPrice(
      gross: gross.toDouble(),
      currency: p['currency'] as String? ?? '',
      vatCode: p['vatCode'] as String?,
      vatRate: (p['vatRate'] as num?)?.toDouble(),
      taxInclusive: p['taxInclusive'] == true,
    );
  }
}
