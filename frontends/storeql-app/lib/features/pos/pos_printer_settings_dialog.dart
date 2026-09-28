import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'pos_printer_settings.dart';
import 'pos_providers.dart';
import 'pos_receipt_data.dart';
import 'pos_receipt_escpos.dart';
import 'pos_receipt_printer.dart';

/// This till's receipt printer (09.12): how receipts come out here, where the
/// printer is, what paper it takes, and a test page to prove it before a
/// customer is waiting for one. Settings are kept on the device.
class PrinterSettingsDialog extends ConsumerStatefulWidget {
  const PrinterSettingsDialog({super.key});

  @override
  ConsumerState<PrinterSettingsDialog> createState() => _PrinterSettingsDialogState();
}

class _PrinterSettingsDialogState extends ConsumerState<PrinterSettingsDialog> {
  late PrinterSettings _draft = ref.read(printerSettingsProvider);
  late final _host = TextEditingController(text: _draft.host);
  late final _port = TextEditingController(text: '${_draft.port}');
  late final _bridge = TextEditingController(text: _draft.bridgeUrl);
  List<String> _problems = const [];
  bool _testing = false;

  @override
  void dispose() {
    _host.dispose();
    _port.dispose();
    _bridge.dispose();
    super.dispose();
  }

  PrinterSettings _current() => _draft.copyWith(
        host: _host.text.trim(),
        port: int.tryParse(_port.text.trim()) ?? 0,
        bridgeUrl: _bridge.text.trim(),
      );

  Future<void> _testPage() async {
    final s = _current();
    final problems = s.validate();
    if (problems.isNotEmpty) {
      setState(() => _problems = problems);
      return;
    }
    setState(() {
      _problems = const [];
      _testing = true;
    });
    final printer = ref.read(receiptPrinterFactoryProvider)(s);
    final outcome = await printer.print(_testReceipt(), openDrawer: false);
    if (!mounted) return;
    setState(() => _testing = false);
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(outcome.message),
      backgroundColor: outcome.ok ? null : Theme.of(context).colorScheme.error,
    ));
  }

  Future<void> _save() async {
    final problems = await ref.read(printerSettingsProvider.notifier).save(_current());
    if (!mounted) return;
    if (problems.isNotEmpty) {
      setState(() => _problems = problems);
      return;
    }
    Navigator.pop(context);
    ScaffoldMessenger.of(context)
        .showSnackBar(const SnackBar(content: Text('Printer settings saved on this till.')));
  }

  static PosReceiptData _testReceipt() => PosReceiptData(
        orderId: '00000000-0000-7000-8000-000000000000',
        storeName: 'Receipt printer test',
        storeAddress: 'If you can read this, the till can print.',
        dateTime: DateTime.now(),
        items: const [
          PosLine(
            variantId: 'test',
            sku: 'TEST',
            name: 'Test line — 1 x 0.00',
            qty: 1,
            unitPrice: 0,
            currency: '',
            soldBy: 'EACH',
          ),
        ],
        subtotal: 0,
        discount: 0,
        total: 0,
        currency: '',
        tenders: const [],
        change: 0,
        fiscalNumberNote: 'This is a test page, not a receipt.',
      );

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Receipt printer'),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('How this till produces a receipt. Kept on this device — each till has its own printer.'),
              const SizedBox(height: 12),
              DropdownButtonFormField<PrinterMode>(
                key: const Key('printer-mode'),
                isExpanded: true,
                initialValue: PrinterMode.available.contains(_draft.mode) ? _draft.mode : PrinterMode.defaultMode,
                decoration: const InputDecoration(labelText: 'Print with'),
                items: [
                  for (final m in PrinterMode.available)
                    DropdownMenuItem(value: m, child: Text(m.label, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (m) => setState(() => _draft = _draft.copyWith(mode: m)),
              ),
              if (_draft.mode == PrinterMode.network) ...[
                const SizedBox(height: 8),
                Row(children: [
                  Expanded(
                    flex: 3,
                    child: TextField(
                      key: const Key('printer-host'),
                      controller: _host,
                      decoration: const InputDecoration(labelText: 'Printer host or IP'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: TextField(
                      key: const Key('printer-port'),
                      controller: _port,
                      keyboardType: TextInputType.number,
                      decoration: const InputDecoration(labelText: 'Port'),
                    ),
                  ),
                ]),
              ],
              if (_draft.mode == PrinterMode.bridge) ...[
                const SizedBox(height: 8),
                TextField(
                  key: const Key('printer-bridge'),
                  controller: _bridge,
                  decoration: const InputDecoration(
                    labelText: 'Print bridge address',
                    helperText: 'An agent on this network that takes ESC/POS bytes and hands them to the printer.',
                  ),
                ),
              ],
              if (_draft.mode.thermal) ...[
                const SizedBox(height: 12),
                SegmentedButton<PaperWidth>(
                  key: const Key('printer-paper'),
                  segments: const [
                    ButtonSegment(value: PaperWidth.mm58, label: Text('58 mm')),
                    ButtonSegment(value: PaperWidth.mm80, label: Text('80 mm')),
                  ],
                  selected: {_draft.paper},
                  onSelectionChanged: (s) => setState(() => _draft = _draft.copyWith(paper: s.first)),
                ),
                SwitchListTile.adaptive(
                  key: const Key('printer-drawer'),
                  contentPadding: EdgeInsets.zero,
                  value: _draft.openDrawer,
                  onChanged: (v) => setState(() => _draft = _draft.copyWith(openDrawer: v)),
                  title: const Text('Open the cash drawer on a cash sale'),
                  subtitle: const Text('The drawer connected to the printer. Reprints never open it.'),
                ),
              ],
              if (_problems.isNotEmpty) ...[
                const SizedBox(height: 8),
                for (final p in _problems) Text(p, style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        OutlinedButton.icon(
          key: const Key('printer-test'),
          onPressed: _testing ? null : _testPage,
          icon: const Icon(Icons.print_outlined, size: 18),
          label: Text(_testing ? 'Printing…' : 'Print test page'),
        ),
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(key: const Key('printer-save'), onPressed: _save, child: const Text('Save')),
      ],
    );
  }
}
