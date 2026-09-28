/// The contents of one POS receipt, and its HTML rendering.
///
/// Deliberately free of any platform imports: an offline sale builds one of these
/// with no server and no browser involved, and the widget tests construct them on
/// the Dart VM. How a receipt is actually *presented* is platform-specific and
/// lives behind the conditional export in `pos_receipt.dart`.
library;

import '../../core/format.dart';
import 'pos_fiscal_receipt.dart';
import 'pos_providers.dart';
import '../../shared/util/short_ref.dart';

class PosReceiptData {
  final String orderId;
  final String storeName;
  final String? storeAddress; // e.g. "12 High St, London, EC1A 1BB"
  final DateTime dateTime;
  final String? cashierEmail;
  final List<PosLine> items;
  final double subtotal;
  final double discount;

  /// The return-scheme deposits on the sale's containers (09.16), shown as
  /// their own line: part of the total, no part of the goods or the VAT on them.
  final double deposit;
  final double total;
  final String currency;
  final List<PosTender> tenders;
  final double change;
  final String? customerName;

  /// The legal receipt number, e.g. `2026-000042`. Null until order-svc has
  /// issued it — and always null for a sale held offline.
  final String? fiscalNumber;

  /// Printed where the number would be when there is none, so a receipt never
  /// passes an order id off as a receipt number.
  final String? fiscalNumberNote;

  /// What the store's fiscal regime stamped on the sale (18.5): printed under
  /// the totals, as the regime requires. Null under NONE or before issue.
  final FiscalStamp? fiscalStamp;

  const PosReceiptData({
    required this.orderId,
    required this.storeName,
    this.storeAddress,
    required this.dateTime,
    this.cashierEmail,
    required this.items,
    required this.subtotal,
    required this.discount,
    this.deposit = 0,
    required this.total,
    required this.currency,
    required this.tenders,
    required this.change,
    this.customerName,
    this.fiscalNumber,
    this.fiscalNumberNote,
    this.fiscalStamp,
  });

  /// The same receipt, now carrying the number that was not issued in time.
  PosReceiptData withFiscalNumber(String number) => withFiscalStamp(
    FiscalStamp(fullNumber: number, regime: fiscalStamp?.regime ?? 'NONE'),
  );

  /// The same receipt, now carrying the number and the regime's stamp.
  PosReceiptData withFiscalStamp(FiscalStamp stamp) => PosReceiptData(
    orderId: orderId,
    storeName: storeName,
    storeAddress: storeAddress,
    dateTime: dateTime,
    cashierEmail: cashierEmail,
    items: items,
    subtotal: subtotal,
    discount: discount,
    deposit: deposit,
    total: total,
    currency: currency,
    tenders: tenders,
    change: change,
    customerName: customerName,
    fiscalNumber: stamp.fullNumber,
    fiscalStamp: stamp,
  );

  String get shortId => shortRef(orderId).toUpperCase();

  /// Money as the shopper reads it, `£12.00`. (The thermal encoder keeps
  /// currency codes: a printer's code page cannot print every symbol.)
  String _fmt(double v) => AppFormat.money(v, currencyCode: currency);

  /// When the sale was made, in the till's own time: `25 Sept 2026 14:05`.
  String _fmtDate() => AppFormat.dateTime(dateTime.toIso8601String());

  String toHtml() {
    final itemRows = StringBuffer();
    for (final l in items) {
      final lineTotal = _fmt(l.lineTotal);
      // A measured line prints its reading and the price per unit:
      // "0.375 kg × GBP 12.00/kg" is what a weights inspector reads.
      final qtyPrice = l.measured
          ? '${l.qtyLabel} × ${_fmt(l.unitPrice)}/${l.unit ?? ''}'
          : '${l.qtyLabel} × ${_fmt(l.unitPrice)}';
      itemRows.write('''
        <tr>
          <td class="item-name">${_esc(l.name)}</td>
          <td class="item-qty">${_esc(qtyPrice)}</td>
          <td class="item-total">${_esc(lineTotal)}</td>
        </tr>
      ''');
    }

    final tenderRows = StringBuffer();
    for (final t in tenders) {
      tenderRows.write('''
        <tr>
          <td>${_esc(t.label)}</td>
          <td></td>
          <td class="item-total">${_esc(_fmt(t.amount))}</td>
        </tr>
      ''');
      // A card taken on an EMV terminal prints what the terminal said (07.16):
      // the application label, the four digits a receipt may print, how the card
      // was read and how the cardholder was verified. A card receipt without
      // these is not a valid card receipt in any market this platform trades in.
      final card = t.terminalReceiptLine;
      if (card != null && card.isNotEmpty) {
        tenderRows.write('''
        <tr>
          <td class="muted" colspan="3">${_esc(card)}</td>
        </tr>
      ''');
      }
    }

    final depositRow = deposit > 0

        ? '<tr><td class="total-label">Container deposit</td>'

            '<td class="total-value">${_esc(_fmt(deposit))}</td></tr>'

        : '';

    final discountRow = discount > 0
        ? '<tr><td>Discount</td><td></td><td class="item-total">- ${_esc(_fmt(discount))}</td></tr>'
        : '';

    final changeRow = change > 0.005
        ? '<tr class="change-row"><td colspan="2"><b>Change</b></td><td class="item-total"><b>${_esc(_fmt(change))}</b></td></tr>'
        : '';

    final customerRow = customerName != null && customerName!.isNotEmpty
        ? '<div class="info-row"><span>Customer:</span><span>${_esc(customerName!)}</span></div>'
        : '';

    final addressLine = storeAddress != null && storeAddress!.isNotEmpty
        ? '<div>${_esc(storeAddress!)}</div>'
        : '';

    // The receipt number is the legal one or nothing. This row used to read
    // "Receipt #" over the first eight characters of the order's UUID, which is
    // an order reference wearing a receipt number's label.
    final numberRows = fiscalNumber != null
        ? '<div class="info-row receipt-no"><span>Receipt no.:</span><span>${_esc(fiscalNumber!)}</span></div>\n'
              '  <div class="info-row"><span>Order ref:</span><span>$shortId</span></div>'
        : '<div class="info-row"><span>Order ref:</span><span>$shortId</span></div>'
              '${fiscalNumberNote != null ? '\n  <div class="info-row">${_esc(fiscalNumberNote!)}</div>' : ''}';

    // The regime's stamp (18.5). Germany: KassenSichV §6 requires the serial
    // of the security module, the transaction number, the signature counter,
    // the start and end of the transaction and the signature (or the QR code
    // that carries them all). Portugal: four characters of the document's
    // signature and the certificate number, on every document.
    final stamp = fiscalStamp;
    String fiscalBlock = '';
    if (stamp != null && stamp.hasTse) {
      if (stamp.tseError != null) {
        fiscalBlock =
            '''
  <hr class="divider">
  <div class="fiscal" data-fiscal="tse-error">
    <div class="info-row"><span>TSE:</span><span>ausgefallen</span></div>
    <div class="fiscal-note">Sicherheitseinrichtung ausgefallen: ${_esc(stamp.tseError!)}</div>
  </div>''';
      } else {
        fiscalBlock =
            '''
  <hr class="divider">
  <div class="fiscal" data-fiscal="tse">
    <div class="info-row"><span>TSE-Seriennr.:</span><span class="mono">${_esc(stamp.tseSerial ?? '')}</span></div>
    <div class="info-row"><span>Transaktionsnr.:</span><span>${stamp.tseTransactionNumber ?? ''}</span></div>
    <div class="info-row"><span>Signaturzähler:</span><span>${stamp.tseSignatureCounter ?? ''}</span></div>
    <div class="info-row"><span>Vorgangsbeginn:</span><span>${_esc(stamp.tseStartedAt ?? '')}</span></div>
    <div class="info-row"><span>Vorgangsende:</span><span>${_esc(stamp.tseFinishedAt ?? '')}</span></div>
    <div class="fiscal-note">Signatur: <span class="mono">${_esc(stamp.tseSignature ?? '')}</span></div>
    ${stamp.tseQr != null ? '<div class="fiscal-note">QR: <span class="mono">${_esc(stamp.tseQr!)}</span></div>' : ''}
  </div>''';
      }
    } else if (stamp != null && stamp.hasPt) {
      fiscalBlock =
          '''
  <hr class="divider">
  <div class="fiscal" data-fiscal="pt">
    ${stamp.ptAtcud != null ? '<div class="info-row"><span>ATCUD:</span><span>${_esc(stamp.ptAtcud!)}</span></div>' : ''}
    <div class="fiscal-note"><span class="mono">${_esc(stamp.ptExcerpt!)}</span> — Processado por programa certificado n.º ${_esc(stamp.ptCertificateNumber ?? '—')}/AT</div>
  </div>''';
    }

    return '''<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>${fiscalNumber != null ? 'Receipt ${_esc(fiscalNumber!)}' : 'Order $shortId'}</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      font-family: 'Courier New', Courier, monospace;
      font-size: 13px;
      color: #000;
      background: #fff;
      width: 340px;
      margin: 0 auto;
      padding: 20px 12px;
    }
    .center { text-align: center; }
    .store-name { font-size: 20px; font-weight: bold; letter-spacing: 1px; margin-bottom: 4px; }
    .store-sub { font-size: 11px; color: #444; line-height: 1.4; }
    .divider { border: none; border-top: 1px dashed #000; margin: 8px 0; }
    .divider-solid { border: none; border-top: 2px solid #000; margin: 8px 0; }
    .info-row { display: flex; justify-content: space-between; margin: 2px 0; font-size: 12px; }
    table { width: 100%; border-collapse: collapse; margin: 4px 0; }
    th { font-size: 11px; text-align: left; border-bottom: 1px solid #000; padding-bottom: 3px; }
    td { font-size: 12px; padding: 2px 0; vertical-align: top; }
    .item-name { width: 55%; }
    .item-qty { width: 28%; font-size: 11px; color: #333; }
    .item-total { width: 17%; text-align: right; }
    .totals-table td { padding: 1px 0; }
    .total-label { width: 70%; }
    .total-value { width: 30%; text-align: right; }
    .grand-total td { font-size: 15px; font-weight: bold; border-top: 1px solid #000; border-bottom: 2px solid #000; padding: 4px 0; }
    .change-row td { font-size: 14px; color: #006600; padding-top: 4px; }
    .footer { text-align: center; font-size: 11px; color: #444; margin-top: 12px; line-height: 1.6; }
    .receipt-no { font-size: 12px; letter-spacing: 1px; }
    .fiscal { font-size: 10px; line-height: 1.5; }
    .fiscal-note { font-size: 9px; word-break: break-all; margin-top: 2px; }
    .mono { font-family: 'Courier New', Courier, monospace; }
    @media print {
      body { width: 100%; padding: 0; margin: 0; }
      button, .no-print { display: none !important; }
    }
  </style>
</head>
<body>
  <div class="center">
    <div class="store-name">${_esc(storeName)}</div>
    $addressLine
  </div>

  <hr class="divider-solid">

  <div class="info-row"><span>Date:</span><span>${_fmtDate()}</span></div>
  $numberRows
  ${cashierEmail != null ? '<div class="info-row"><span>Cashier:</span><span>${_esc(cashierEmail!)}</span></div>' : ''}
  $customerRow

  <hr class="divider">

  <table>
    <thead>
      <tr>
        <th class="item-name">Item</th>
        <th class="item-qty">Qty × Price</th>
        <th class="item-total">Amt</th>
      </tr>
    </thead>
    <tbody>
      $itemRows
    </tbody>
  </table>

  <hr class="divider">

  <table class="totals-table">
    <tr>
      <td class="total-label">Subtotal</td>
      <td class="total-value">${_esc(_fmt(subtotal))}</td>
    </tr>
    $depositRow
    $discountRow
    <tr class="grand-total">
      <td class="total-label">TOTAL</td>
      <td class="total-value">${_esc(_fmt(total))}</td>
    </tr>
    $tenderRows
    $changeRow
  </table>
  $fiscalBlock

  <hr class="divider-solid">

  <div class="footer">
    Thank you for your purchase!<br>
    Please retain this receipt.
  </div>

  <script>window.onload = function () { window.print(); };</script>
</body>
</html>''';
  }

  static String _esc(String s) => s
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;');
}
