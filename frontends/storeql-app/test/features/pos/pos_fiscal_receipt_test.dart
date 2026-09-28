import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/features/pos/pos_fiscal_receipt.dart';
import 'package:storeql_app/features/pos/pos_receipt_data.dart';

// ---------------------------------------------------------------------------
// The legal receipt number on the till's receipt.
//
// order-svc issues the number when the payment that completes a sale reaches
// it, a few seconds after the till posts the last tender. The till waits a
// bounded time for it. And a receipt that has no number says so: this receipt
// used to print "Receipt #" over the first eight characters of the order's
// UUID, which is an order reference wearing a receipt number's label.
// ---------------------------------------------------------------------------

class _Replies implements HttpClientAdapter {
  final List<(int, String)> replies;
  final List<RequestOptions> calls = [];
  _Replies(this.replies);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    calls.add(o);
    final r = replies[calls.length - 1 < replies.length ? calls.length - 1 : replies.length - 1];
    return ResponseBody.fromString(r.$2, r.$1, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

const _notYet = (404, '{"error":{"code":"ORDER_RECEIPT_NOT_ISSUED","details":[]}}');
const _issued = (200, '{"data":{"fullNumber":"2026-000042","number":42}}');

Dio _dio(_Replies r) => Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = r;

PosReceiptData _receipt({String? number, String? note}) => PosReceiptData(
      orderId: '01a090ae-611e-701e-a773-cff68a489efe',
      storeName: 'High Street',
      dateTime: DateTime(2026, 9, 10, 11, 30),
      items: const [],
      subtotal: 12,
      discount: 0,
      total: 12,
      currency: 'GBP',
      tenders: const [],
      change: 0,
      fiscalNumber: number,
      fiscalNumberNote: note,
    );

void main() {
  setUpAll(initializeDateFormatting);
  group('awaitFiscalNumber', () {
    test('asks the till-readable path, not the management-only one', () async {
      final r = _Replies([_issued]);
      expect(await awaitFiscalNumber(_dio(r), 'o-1'), '2026-000042');
      expect(r.calls.single.path, '/order-svc/orders/o-1/fiscal-receipt');
    });

    test('asks the server to wait on the first request, and plainly after', () async {
      final r = _Replies([_notYet, _notYet, _issued]);
      expect(await awaitFiscalNumber(_dio(r), 'o-1', waitSeconds: 7, interval: Duration.zero),
          '2026-000042');
      // One round trip that the server holds open beats sixteen that each miss.
      expect(r.calls.first.queryParameters['wait'], 7);
      expect(r.calls[1].queryParameters['wait'], isNull);
      expect(r.calls[2].queryParameters['wait'], isNull);
    });

    test('with no server wait it polls the old way', () async {
      final r = _Replies([_notYet, _issued]);
      expect(await awaitFiscalNumber(_dio(r), 'o-1', waitSeconds: 0, interval: Duration.zero),
          '2026-000042');
      expect(r.calls.first.queryParameters['wait'], isNull);
    });

    test('waits through "not issued yet" for a number that is on its way', () async {
      final r = _Replies([_notYet, _notYet, _issued]);
      expect(await awaitFiscalNumber(_dio(r), 'o-1', interval: Duration.zero),
          '2026-000042');
      expect(r.calls, hasLength(3));
    });

    test('gives up after a bounded wait — the customer is standing there', () async {
      final r = _Replies([_notYet]);
      expect(
          await awaitFiscalNumber(_dio(r), 'o-1',
              attempts: 4, interval: Duration.zero),
          isNull);
      expect(r.calls, hasLength(4));
    });

    test('stops at once on an answer waiting cannot change', () async {
      final r = _Replies([(503, '{"error":{"code":"UPSTREAM_UNAVAILABLE","details":[]}}')]);
      expect(await awaitFiscalNumber(_dio(r), 'o-1', interval: Duration.zero),
          isNull);
      expect(r.calls, hasLength(1));
    });
  });

  group('the regime\'s stamp (18.5)', () {
    const stamped = (200, '{"data":{"fullNumber":"DE-B-2026-000007","number":7,"regime":"DE_KASSENSICHV",'
        '"tse":{"serialNumber":"abc123","clientId":"till-1","transactionNumber":7,"signatureCounter":9,'
        '"signature":"SIG==","algorithm":"ecdsa-plain-SHA256","startedAt":"2026-09-12T10:00:00Z",'
        '"finishedAt":"2026-09-12T10:00:05Z","processType":"Kassenbeleg-V1",'
        '"processData":"Beleg^11.90_0.00_0.00_0.00_0.00^11.90:Bar","qr":"V0;till-1;Kassenbeleg-V1;x;7;9;a;b;c;d;SIG==;PUB"}}}');

    test('the till reads the whole stamp, not just the number', () async {
      final r = _Replies([stamped]);
      final stamp = await awaitFiscalReceipt(_dio(r), 'o-1');
      expect(stamp!.fullNumber, 'DE-B-2026-000007');
      expect(stamp.regime, 'DE_KASSENSICHV');
      expect(stamp.hasTse, isTrue);
      expect(stamp.tseSerial, 'abc123');
      expect(stamp.tseSignatureCounter, 9);
      expect(stamp.tseTransactionNumber, 7);
      expect(stamp.tseQr, startsWith('V0;till-1;'));
      expect(stamp.hasPt, isFalse);
      // The number-only reader still works, from the same call.
      expect(await awaitFiscalNumber(_dio(_Replies([stamped])), 'o-1'), 'DE-B-2026-000007');
    });

    test('a German receipt prints what KassenSichV §6 lists', () {
      final html = _receipt(number: 'DE-B-2026-000007')
          .withFiscalStamp(const FiscalStamp(
            fullNumber: 'DE-B-2026-000007',
            regime: 'DE_KASSENSICHV',
            tseSerial: 'abc123',
            tseTransactionNumber: 7,
            tseSignatureCounter: 9,
            tseSignature: 'SIG==',
            tseStartedAt: '2026-09-12T10:00:00Z',
            tseFinishedAt: '2026-09-12T10:00:05Z',
            tseQr: 'V0;till-1;Kassenbeleg-V1;x;7;9;a;b;c;d;SIG==;PUB',
          ))
          .toHtml();
      expect(html, contains('data-fiscal="tse"'));
      expect(html, contains('TSE-Seriennr.:'));
      expect(html, contains('abc123'));
      expect(html, contains('Transaktionsnr.:</span><span>7'));
      expect(html, contains('Signaturzähler:</span><span>9'));
      expect(html, contains('2026-09-12T10:00:00Z'));
      expect(html, contains('2026-09-12T10:00:05Z'));
      expect(html, contains('SIG=='));
      expect(html, contains('QR: <span class="mono">V0;till-1;'));
      expect(html, contains('Receipt no.:'));
    });

    test('when the module was down the receipt says so, and the sale still prints', () {
      final html = _receipt(number: 'DE-B-2026-000008')
          .withFiscalStamp(const FiscalStamp(
            fullNumber: 'DE-B-2026-000008',
            regime: 'DE_KASSENSICHV',
            tseError: 'cloud TSE unreachable',
          ))
          .toHtml();
      expect(html, contains('data-fiscal="tse-error"'));
      expect(html, contains('Sicherheitseinrichtung ausgefallen: cloud TSE unreachable'));
      expect(html, contains('Receipt no.:'));
    });

    test('a Portuguese receipt prints the four characters and the certificate number', () {
      final html = _receipt(number: '2026-000003')
          .withFiscalStamp(const FiscalStamp(
            fullNumber: '2026-000003',
            regime: 'PT_SAFT',
            ptExcerpt: 'AbCd',
            ptCertificateNumber: '1234',
            ptAtcud: 'XYZ1-3',
          ))
          .toHtml();
      expect(html, contains('data-fiscal="pt"'));
      expect(html, contains('AbCd'));
      expect(html, contains('Processado por programa certificado n.º 1234/AT'));
      expect(html, contains('ATCUD:</span><span>XYZ1-3'));
      expect(html, isNot(contains('data-fiscal="tse"')));
    });

    test('under NONE nothing is stamped', () {
      final html = _receipt(number: '2026-000001')
          .withFiscalStamp(const FiscalStamp(fullNumber: '2026-000001'))
          .toHtml();
      expect(html, isNot(contains('data-fiscal=')));
    });

    test('a stamp escapes what it prints', () {
      final html = _receipt(number: 'x')
          .withFiscalStamp(const FiscalStamp(fullNumber: 'x', regime: 'DE_KASSENSICHV', tseError: '<b>bad</b>'))
          .toHtml();
      expect(html, contains('&lt;b&gt;bad&lt;/b&gt;'));
      expect(html, isNot(contains('<b>bad</b>')));
    });
  });

  group('the printed receipt', () {
    test('prints the legal number as the receipt number, with the order beside it', () {
      final html = _receipt(number: '2026-000042').toHtml();
      expect(html, contains('Receipt no.:'));
      expect(html, contains('2026-000042'));
      expect(html, contains('Order ref:'));
      expect(html, contains('<title>Receipt 2026-000042</title>'));
      expect(html, isNot(contains('Receipt #')));
    });

    test('with no number yet, it does not pass the order id off as one', () {
      final html =
          _receipt(note: 'Receipt number not issued yet. Reprint once it is.').toHtml();
      expect(html, isNot(contains('Receipt no.:')));
      expect(html, isNot(contains('Receipt #')));
      expect(html, contains('Order ref:'));
      expect(html, contains('Receipt number not issued yet'));
    });

    test('a late number can be added for the reprint', () {
      final late = _receipt(note: 'not yet').withFiscalNumber('2026-000043');
      expect(late.fiscalNumber, '2026-000043');
      expect(late.toHtml(), contains('Receipt no.:'));
      expect(late.toHtml(), isNot(contains('not yet')));
    });
  });

  // Order ids are UUIDv7: their first eight characters are a timestamp shared
  // by every sale rung up in the same minute, their last eight are random.
  group('the order ref', () {
    PosReceiptData forOrder(String orderId) => PosReceiptData(
          orderId: orderId,
          storeName: 'High Street',
          dateTime: DateTime(2026, 9, 10, 11, 30),
          items: const [],
          subtotal: 12,
          discount: 0,
          total: 12,
          currency: 'GBP',
          tenders: const [],
          change: 0,
        );

    test('is the end of the order id, upper-cased', () {
      final receipt = forOrder('01a0905d-7082-7518-9ec6-aee90d72a43e');
      expect(receipt.shortId, '0D72A43E');
      final html = receipt.toHtml();
      expect(html, contains('<span>0D72A43E</span>'));
      expect(html, contains('<title>Order 0D72A43E</title>'));
      expect(html, isNot(contains('01A0905D')));
    });

    test('two sales rung up in the same minute print different refs', () {
      final first = forOrder('01a0905d-7082-7518-9ec6-00000000a001');
      final second = forOrder('01a0905d-70a4-7003-9ec6-00000000a002');
      expect(first.shortId, '0000A001');
      expect(second.shortId, '0000A002');
    });

    test('an id shorter than the ref prints whole', () {
      expect(forOrder('o-1').shortId, 'O-1');
      expect(forOrder('').shortId, '');
    });
  });
}
