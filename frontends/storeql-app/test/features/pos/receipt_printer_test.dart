import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/storage/app_storage.dart';
import 'package:storeql_app/features/pos/pos_printer_settings.dart';
import 'package:storeql_app/features/pos/pos_receipt_data.dart';
import 'package:storeql_app/features/pos/pos_receipt_escpos.dart';
import 'package:storeql_app/features/pos/pos_receipt_printer.dart';

// ---------------------------------------------------------------------------
// The till's printer (09.12): the settings it keeps on the device and the
// dispatch that turns a receipt into what those settings ask for. Every
// transport is a fake here, so what is tested is the choice, the bytes handed
// over, and that a failure comes back as words rather than an exception while
// a customer waits.
// ---------------------------------------------------------------------------

class _MemStorage implements AppStorage {
  final Map<String, String> map = {};
  @override
  Future<String?> read({required String key}) async => map[key];
  @override
  Future<void> write({required String key, required String? value}) async {
    if (value == null) {
      map.remove(key);
    } else {
      map[key] = value;
    }
  }

  @override
  Future<void> delete({required String key}) async => map.remove(key);
  @override
  Future<void> deleteAll({Set<String> keep = const {}}) async =>
      map.removeWhere((k, _) => !keep.contains(k));
}

PosReceiptData _receipt() => PosReceiptData(
      orderId: '01a090ae-611e-701e-a773-cff68a489efe',
      storeName: 'High Street',
      dateTime: DateTime(2026, 9, 13, 11, 30),
      items: const [],
      subtotal: 0,
      discount: 0,
      total: 0,
      currency: 'GBP',
      tenders: const [],
      change: 0,
      fiscalNumber: '2026-000042',
    );

void main() {
  setUpAll(initializeDateFormatting);
  group('settings', () {
    test('round-trip through JSON', () {
      const s = PrinterSettings(
          mode: PrinterMode.network, host: '10.0.0.5', port: 9100, paper: PaperWidth.mm58, openDrawer: true);
      final back = PrinterSettings.fromJson(jsonDecode(jsonEncode(s.toJson())) as Map<String, dynamic>);
      expect(back.mode, PrinterMode.network);
      expect(back.host, '10.0.0.5');
      expect(back.port, 9100);
      expect(back.paper, PaperWidth.mm58);
      expect(back.openDrawer, isTrue);
    });

    test('unknown or corrupt values fall back to the defaults, not a crash', () {
      final s = PrinterSettings.fromJson({'mode': 'laser', 'port': 'nine', 'paper': 'a4'});
      expect(s.mode, PrinterMode.defaultMode);
      expect(s.port, 9100);
      expect(s.paper, PaperWidth.mm58);
    });

    test('a network printer needs a host and a real port', () {
      expect(const PrinterSettings(mode: PrinterMode.network).validate(), contains('The printer needs a host name or IP address.'));
      expect(const PrinterSettings(mode: PrinterMode.network, host: 'bad host!').validate(), isNotEmpty);
      expect(const PrinterSettings(mode: PrinterMode.network, host: '10.0.0.5', port: 70000).validate(),
          contains('The port must be between 1 and 65535.'));
      expect(const PrinterSettings(mode: PrinterMode.network, host: '10.0.0.5', port: 0).validate(), isNotEmpty);
      expect(const PrinterSettings(mode: PrinterMode.network, host: 'printer.local', port: 9100).validate(), isEmpty);
    });

    test('a bridge needs an http address', () {
      expect(const PrinterSettings(mode: PrinterMode.bridge, bridgeUrl: 'ftp://x').validate(), isNotEmpty);
      expect(const PrinterSettings(mode: PrinterMode.bridge, bridgeUrl: 'not a url').validate(), isNotEmpty);
      expect(const PrinterSettings(mode: PrinterMode.bridge, bridgeUrl: 'http://192.168.1.20:9109/print').validate(), isEmpty);
    });

    test('a mode this build cannot perform is refused', () {
      // On the Dart VM the build is non-web: the browser dialog is not on offer.
      expect(const PrinterSettings(mode: PrinterMode.browser).validate(), isNotEmpty);
      expect(PrinterMode.available, isNot(contains(PrinterMode.browser)));
    });

    test('the notifier keeps valid settings on the device and refuses invalid ones', () async {
      final storage = _MemStorage();
      final n = PrinterSettingsNotifier(storage: storage);
      expect(await n.save(const PrinterSettings(mode: PrinterMode.network)), isNotEmpty);
      expect(storage.map, isEmpty);
      expect(await n.save(const PrinterSettings(mode: PrinterMode.network, host: '10.0.0.5')), isEmpty);
      expect(n.state.host, '10.0.0.5');
      expect(jsonDecode(storage.map['pos_printer']!)['host'], '10.0.0.5');

      // A new notifier on the same device loads what was kept.
      final again = PrinterSettingsNotifier(storage: storage);
      await Future<void>.delayed(Duration.zero);
      expect(again.state.mode, PrinterMode.network);
      expect(again.state.host, '10.0.0.5');
    });

    test('unreadable stored settings are the defaults', () async {
      final storage = _MemStorage()..map['pos_printer'] = '{not json';
      final n = PrinterSettingsNotifier(storage: storage);
      await Future<void>.delayed(Duration.zero);
      expect(n.state.mode, PrinterMode.defaultMode);
    });
  });

  group('dispatch', () {
    test('a network printer gets ESC/POS bytes at its host and port, and the outcome says so', () async {
      Uint8List? sent;
      String? host;
      int? port;
      final printer = ReceiptPrinter(
        settings: const PrinterSettings(mode: PrinterMode.network, host: '10.0.0.5', port: 9100),
        network: (b, h, p) async {
          sent = b;
          host = h;
          port = p;
        },
      );
      final out = await printer.print(_receipt());
      expect(out.ok, isTrue);
      expect(out.method, ReceiptMethod.thermal);
      expect(host, '10.0.0.5');
      expect(port, 9100);
      expect(sent!.sublist(0, 2), [0x1B, 0x40]);
      expect(out.message, contains('10.0.0.5'));
    });

    test('the drawer is kicked only when the sale asks, never on a reprint', () async {
      Uint8List? sent;
      final printer = ReceiptPrinter(
        settings: const PrinterSettings(mode: PrinterMode.network, host: '10.0.0.5', openDrawer: true),
        network: (b, h, p) async => sent = b,
      );
      await printer.print(_receipt(), openDrawer: true);
      expect(_has(sent!, [0x1B, 0x70]), isTrue);
      await printer.print(_receipt(), openDrawer: false);
      expect(_has(sent!, [0x1B, 0x70]), isFalse);
    });

    test('a printer that cannot be reached comes back as words, not an exception', () async {
      final printer = ReceiptPrinter(
        settings: const PrinterSettings(mode: PrinterMode.network, host: '10.0.0.5'),
        network: (b, h, p) async => throw Exception('Connection timed out, host: 10.0.0.5, port: 9100'),
      );
      final out = await printer.print(_receipt());
      expect(out.ok, isFalse);
      expect(out.method, ReceiptMethod.none);
      expect(out.message, 'Could not print: Connection timed out, host: 10.0.0.5, port: 9100');
    });

    test('a bridge gets the bytes at its address', () async {
      String? url;
      Uint8List? sent;
      final printer = ReceiptPrinter(
        settings: const PrinterSettings(mode: PrinterMode.bridge, bridgeUrl: 'http://192.168.1.20:9109/print'),
        bridge: (u, b) async {
          url = u;
          sent = b;
        },
      );
      final out = await printer.print(_receipt());
      expect(out.ok, isTrue);
      expect(out.method, ReceiptMethod.thermal);
      expect(url, 'http://192.168.1.20:9109/print');
      expect(sent!.last, 0); // the cut's final byte
    });

    test('save hands the HTML to the file seam under the receipt number', () async {
      String? name;
      String? html;
      final printer = ReceiptPrinter(
        settings: const PrinterSettings(mode: PrinterMode.save),
        saver: (n, c) async {
          name = n;
          html = c;
          return '/docs/$n';
        },
      );
      final out = await printer.print(_receipt());
      expect(out.ok, isTrue);
      expect(out.method, ReceiptMethod.save);
      expect(name, 'receipt-2026-000042.html');
      expect(html, contains('<!DOCTYPE html>'));
      expect(out.savedPath, '/docs/receipt-2026-000042.html');
      expect(out.message, contains('/docs/receipt-2026-000042.html'));
    });

    test('save is available whatever the mode, and a web download reports as such', () async {
      final printer = ReceiptPrinter(
        settings: const PrinterSettings(mode: PrinterMode.network, host: '10.0.0.5'),
        network: (b, h, p) async {},
        saver: (n, c) async => null,
      );
      final out = await printer.save(_receipt());
      expect(out.ok, isTrue);
      expect(out.savedPath, isNull);
      expect(out.message, 'Receipt downloaded.');
    });

    test('the browser mode opens the dialog and records as a print', () async {
      var opened = 0;
      final printer = ReceiptPrinter(
        settings: const PrinterSettings(mode: PrinterMode.browser),
        browser: (d) => opened++,
      );
      final out = await printer.print(_receipt());
      expect(opened, 1);
      expect(out.method, ReceiptMethod.print);
    });

    test('no printer means no paper and nothing recorded, and is not an error', () async {
      var touched = false;
      final printer = ReceiptPrinter(
        settings: const PrinterSettings(mode: PrinterMode.none),
        network: (b, h, p) async => touched = true,
        bridge: (u, b) async => touched = true,
        browser: (d) => touched = true,
        saver: (n, c) async {
          touched = true;
          return null;
        },
      );
      final out = await printer.print(_receipt());
      expect(out.ok, isTrue);
      expect(out.method, ReceiptMethod.none);
      expect(touched, isFalse);
    });
  });

  group('the bridge transport', () {
    test('posts the bytes as an octet-stream', () async {
      final adapter = _Adapter(200, 'ok');
      final dio = Dio()..httpClientAdapter = adapter;
      await sendToPrintBridge(dio, 'http://bridge/print', Uint8List.fromList([1, 2, 3]));
      expect(adapter.request!.method, 'POST');
      expect(adapter.request!.uri.toString(), 'http://bridge/print');
      expect(adapter.request!.headers[Headers.contentTypeHeader], 'application/octet-stream');
      expect(adapter.body, [1, 2, 3]);
    });

    test('a bridge that refuses is reported with its status', () async {
      final dio = Dio()..httpClientAdapter = _Adapter(500, 'printer offline');
      final printer = ReceiptPrinter(
        settings: const PrinterSettings(mode: PrinterMode.bridge, bridgeUrl: 'http://bridge/print'),
        bridgeDio: dio,
      );
      final out = await printer.print(_receipt());
      expect(out.ok, isFalse);
      expect(out.message, 'Could not print: the print bridge answered 500.');
    });
  });
}

bool _has(Uint8List bytes, List<int> seq) {
  for (var i = 0; i + seq.length <= bytes.length; i++) {
    var hit = true;
    for (var j = 0; j < seq.length; j++) {
      if (bytes[i + j] != seq[j]) {
        hit = false;
        break;
      }
    }
    if (hit) return true;
  }
  return false;
}

class _Adapter implements HttpClientAdapter {
  _Adapter(this.status, this.reply);
  final int status;
  final String reply;
  RequestOptions? request;
  List<int>? body;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    request = o;
    if (s != null) body = await s.fold<List<int>>([], (a, b) => a..addAll(b));
    return ResponseBody.fromString(reply, status);
  }
}
