/// A receipt as ESC/POS bytes, for the thermal printers every till has (09.12).
///
/// Pure: a receipt in, bytes out, nothing platform-specific and nothing sent.
/// How the bytes reach a printer is the transport's business (a raw socket on
/// the desktop and mobile tills, a print bridge on the web).
///
/// Everything printed as text goes through [_text], which strips control
/// characters. That is not tidiness: ESC and GS are how a printer is told to
/// cut, feed and kick the drawer, and a product name is typed by a shop. A name
/// containing `\x1Bp\x00` would otherwise open the till drawer on every sale.
library;

import 'dart:typed_data';

import 'pos_receipt_data.dart';

/// The paper a receipt printer takes, as printable columns at the default font.
enum PaperWidth {
  mm58(32),
  mm80(48);

  const PaperWidth(this.columns);
  final int columns;

  static PaperWidth fromName(String? name) =>
      name == 'mm80' ? PaperWidth.mm80 : PaperWidth.mm58;
}

class EscPosReceipt {
  const EscPosReceipt({
    this.paper = PaperWidth.mm80,
    this.cut = true,
    this.openDrawer = false,
    this.feedLines = 3,
  });

  final PaperWidth paper;
  final bool cut;

  /// Kick the cash drawer connected to the printer after the cut. Only a cash
  /// sale should ask for this; the caller decides.
  final bool openDrawer;
  final int feedLines;

  static const int _esc = 0x1B;
  static const int _gs = 0x1D;
  static const int _lf = 0x0A;

  int get _cols => paper.columns;

  Uint8List encode(PosReceiptData d) {
    final b = BytesBuilder(copy: false);
    // Initialise, then Windows-1252 so £ and € print rather than garble.
    b.add([_esc, 0x40]);
    b.add([_esc, 0x74, 16]);

    // Store header, centred, the name double-sized.
    b.add([_esc, 0x61, 1]);
    b.add([_gs, 0x21, 0x11]);
    for (final line in _wrap(d.storeName, _cols ~/ 2)) {
      _line(b, line);
    }
    b.add([_gs, 0x21, 0x00]);
    if (d.storeAddress != null && d.storeAddress!.isNotEmpty) {
      for (final line in _wrap(d.storeAddress!, _cols)) {
        _line(b, line);
      }
    }
    b.add([_esc, 0x61, 0]);
    _divider(b, '=');

    _row(b, 'Date:', _fmtDate(d.dateTime));
    if (d.fiscalNumber != null) {
      _row(b, 'Receipt no.:', d.fiscalNumber!);
      _row(b, 'Order ref:', d.shortId);
    } else {
      _row(b, 'Order ref:', d.shortId);
      if (d.fiscalNumberNote != null) {
        for (final line in _wrap(d.fiscalNumberNote!, _cols)) {
          _line(b, line);
        }
      }
    }
    if (d.cashierEmail != null) _row(b, 'Cashier:', d.cashierEmail!);
    if (d.customerName != null && d.customerName!.isNotEmpty) {
      _row(b, 'Customer:', d.customerName!);
    }
    _divider(b, '-');

    // Items: the name on its own line(s), then quantity × price against the amount.
    for (final l in d.items) {
      for (final line in _wrap(l.name, _cols)) {
        _line(b, line);
      }
      final qtyPrice = l.measured
          ? '  ${l.qtyLabel} x ${_money(d.currency, l.unitPrice)}/${l.unit ?? ''}'
          : '  ${l.qtyLabel} x ${_money(d.currency, l.unitPrice)}';
      _row(b, qtyPrice, _money(d.currency, l.lineTotal));
    }
    _divider(b, '-');

    _row(b, 'Subtotal', _money(d.currency, d.subtotal));
    if (d.deposit > 0) {
      _row(b, 'Container deposit', _money(d.currency, d.deposit));
    }
    if (d.discount > 0) _row(b, 'Discount', '-${_money(d.currency, d.discount)}');
    b.add([_esc, 0x45, 1]);
    b.add([_gs, 0x21, 0x10]);
    _row(b, 'TOTAL', _money(d.currency, d.total));
    b.add([_gs, 0x21, 0x00]);
    b.add([_esc, 0x45, 0]);
    for (final t in d.tenders) {
      _row(b, t.label, _money(d.currency, t.amount));
    }
    if (d.change > 0.005) _row(b, 'Change', _money(d.currency, d.change));

    _fiscal(b, d);

    // The receipt number as a scannable code, so the Returns screen finds the sale.
    final code = d.receiptCode;
    if (code != null) {
      _divider(b, '-');
      b.add([_esc, 0x61, 1]);
      _qr(b, code);
      _line(b, code);
      b.add([_esc, 0x61, 0]);
    }

    _divider(b, '=');
    b.add([_esc, 0x61, 1]);
    _line(b, 'Thank you for your purchase!');
    _line(b, 'Please retain this receipt.');
    b.add([_esc, 0x61, 0]);

    for (var i = 0; i < feedLines; i++) {
      b.addByte(_lf);
    }
    if (openDrawer) {
      // Drawer kick on pin 2: on for 50 ms, off for 500 ms.
      b.add([_esc, 0x70, 0x00, 25, 250]);
    }
    if (cut) {
      // Partial cut after feeding to the cutter.
      b.add([_gs, 0x56, 66, 0]);
    }
    return b.toBytes();
  }

  // ── the regime's stamp (18.5) ────────────────────────────────────────────

  void _fiscal(BytesBuilder b, PosReceiptData d) {
    final s = d.fiscalStamp;
    if (s == null) return;
    if (s.hasTse) {
      _divider(b, '-');
      if (s.tseError != null) {
        _row(b, 'TSE:', 'ausgefallen');
        for (final line in _wrap('Sicherheitseinrichtung ausgefallen: ${s.tseError}', _cols)) {
          _line(b, line);
        }
        return;
      }
      _row(b, 'TSE-Seriennr.:', s.tseSerial ?? '');
      _row(b, 'Transaktionsnr.:', '${s.tseTransactionNumber ?? ''}');
      _row(b, 'Signaturzaehler:', '${s.tseSignatureCounter ?? ''}');
      _row(b, 'Vorgangsbeginn:', s.tseStartedAt ?? '');
      _row(b, 'Vorgangsende:', s.tseFinishedAt ?? '');
      for (final line in _wrap('Signatur: ${s.tseSignature ?? ''}', _cols)) {
        _line(b, line);
      }
      if (s.tseQr != null && s.tseQr!.isNotEmpty) {
        b.add([_esc, 0x61, 1]);
        _qr(b, s.tseQr!);
        b.add([_esc, 0x61, 0]);
      }
    } else if (s.hasPt) {
      _divider(b, '-');
      if (s.ptAtcud != null) _row(b, 'ATCUD:', s.ptAtcud!);
      for (final line in _wrap(
          '${s.ptExcerpt} - Processado por programa certificado n.o ${s.ptCertificateNumber ?? '-'}/AT',
          _cols)) {
        _line(b, line);
      }
    }
  }

  /// A model-2 QR code through the standard GS ( k function set: model, size,
  /// error correction, store the data, print it.
  void _qr(BytesBuilder b, String data) {
    final bytes = _text(data);
    b.add([_gs, 0x28, 0x6B, 4, 0, 49, 65, 50, 0]); // model 2
    b.add([_gs, 0x28, 0x6B, 3, 0, 49, 67, 4]); // module size 4
    b.add([_gs, 0x28, 0x6B, 3, 0, 49, 69, 49]); // error correction M
    final len = bytes.length + 3;
    b.add([_gs, 0x28, 0x6B, len & 0xFF, (len >> 8) & 0xFF, 49, 80, 48]);
    b.add(bytes);
    b.add([_gs, 0x28, 0x6B, 3, 0, 49, 81, 48]); // print
  }

  // ── layout ───────────────────────────────────────────────────────────────

  void _line(BytesBuilder b, String s) {
    b.add(_text(_fit(s, _cols)));
    b.addByte(_lf);
  }

  /// A label on the left and a value on the right, on one line when they fit
  /// and on two when they do not — the value is never cut short.
  void _row(BytesBuilder b, String label, String value) {
    final v = _fit(value, _cols);
    final room = _cols - v.length - 1;
    if (room >= 1 && label.length <= room) {
      _line(b, label + ' ' * (_cols - label.length - v.length) + v);
    } else {
      for (final line in _wrap(label, _cols)) {
        _line(b, line);
      }
      _line(b, ' ' * (_cols - v.length) + v);
    }
  }

  void _divider(BytesBuilder b, String ch) => _line(b, ch * _cols);

  static String _fit(String s, int width) =>
      s.length <= width ? s : s.substring(0, width);

  /// Word-wrap to [width], breaking a word longer than the width.
  static List<String> _wrap(String s, int width) {
    final out = <String>[];
    var line = StringBuffer();
    for (final word in s.split(RegExp(r'\s+'))) {
      if (word.isEmpty) continue;
      var w = word;
      while (w.length > width) {
        if (line.isNotEmpty) {
          out.add(line.toString());
          line = StringBuffer();
        }
        out.add(w.substring(0, width));
        w = w.substring(width);
      }
      if (line.isEmpty) {
        line.write(w);
      } else if (line.length + 1 + w.length <= width) {
        line.write(' $w');
      } else {
        out.add(line.toString());
        line = StringBuffer(w);
      }
    }
    if (line.isNotEmpty) out.add(line.toString());
    return out.isEmpty ? [''] : out;
  }

  static String _money(String currency, double v) => '$currency ${v.toStringAsFixed(2)}';

  static String _fmtDate(DateTime t) {
    final d = t.toLocal();
    String p(int n) => n.toString().padLeft(2, '0');
    return '${d.year}-${p(d.month)}-${p(d.day)} ${p(d.hour)}:${p(d.minute)}';
  }

  /// Text as Windows-1252 bytes with every control character removed. A byte
  /// below 0x20 or equal to 0x7F never comes from here, so nothing a shop types
  /// into a name can become a command; a character the page lacks prints as '?'.
  static List<int> _text(String s) {
    final out = <int>[];
    for (final rune in s.runes) {
      if (rune < 0x20 || rune == 0x7F) continue;
      if (rune < 0x7F) {
        out.add(rune);
      } else if (rune == 0x20AC) {
        out.add(0x80); // euro sign in Windows-1252
      } else if (rune >= 0xA0 && rune <= 0xFF) {
        out.add(rune);
      } else {
        out.add(0x3F);
      }
    }
    return out;
  }
}
