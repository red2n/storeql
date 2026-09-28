import 'package:flutter/material.dart';
import '../../shared/util/status_labels.dart';
import '../../core/format.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../pos/weighing_instruments.dart';
import 'providers/admin_providers.dart';
import '../../shared/widgets/empty_state.dart';

// The weighing-instrument register for one store (Weights and Measures Act
// 1985): every scale the store weighs for trade on, whether it may be used
// today and why, and its verification history. A manager registers a scale,
// records each verification, inspection or repair, and takes it out of
// service or retires it. Nothing here edits a history entry — the server keeps
// that append-only, and the inspector reads it as written.

class StoreInstrumentsDialog extends ConsumerWidget {
  final StoreInfo store;
  final bool isManager;
  const StoreInstrumentsDialog({super.key, required this.store, required this.isManager});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(storeInstrumentsProvider(store.id));
    return AlertDialog(
      title: Row(
        children: [
          Expanded(child: Text('Weighing instruments · ${store.name}')),
          if (isManager)
            FilledButton.icon(
              onPressed: () => _showForm(context, ref, null),
              icon: const Icon(Icons.add, size: 18),
              label: const Text('Register scale'),
            ),
        ],
      ),
      content: SizedBox(
        width: 560,
        height: 460,
        child: async.when(
          loading: () => const LoadingView(label: 'Loading the register…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the register.'),
            onRetry: () => ref.invalidate(storeInstrumentsProvider(store.id)),
          ),
          data: (instruments) {
            if (instruments.isEmpty) {
              return Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.scale_outlined, size: 56, color: cs.outlineVariant),
                    const SizedBox(height: 12),
                    const Text('No instruments registered'),
                    const SizedBox(height: 4),
                    Text(
                      'Until a scale is registered and verified, this store\n'
                      'cannot sell anything by weight.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: cs.outline, fontSize: 12),
                    ),
                  ],
                ),
              );
            }
            return ListView.separated(
              itemCount: instruments.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (_, i) {
                final w = instruments[i];
                return ListTile(
                  leading: Icon(
                    w.certified ? Icons.verified_outlined : Icons.report_problem_outlined,
                    color: w.certified ? cs.primary : cs.error,
                  ),
                  title: Text('${w.identifier} · ${instrumentKindLabel(w.kind)}'),
                  subtitle: Text([
                    'S/N ${w.serialNumber}',
                    standingLabel(w.standing),
                    if (w.nextDue != null) 'due ${AppFormat.date(w.nextDue)}',
                    if (w.certificateRef != null) 'cert. ${w.certificateRef}',
                  ].join(' · ')),
                  trailing: isManager
                      ? PopupMenuButton<String>(
                          onSelected: (v) => _act(context, ref, w, v),
                          itemBuilder: (_) => [
                            const PopupMenuItem(value: 'verify', child: Text('Record verification…')),
                            const PopupMenuItem(value: 'history', child: Text('History')),
                            const PopupMenuItem(value: 'edit', child: Text('Edit')),
                            if (w.status == 'IN_SERVICE')
                              const PopupMenuItem(
                                  value: 'OUT_OF_SERVICE', child: Text('Take out of service')),
                            if (w.status == 'OUT_OF_SERVICE')
                              const PopupMenuItem(
                                  value: 'IN_SERVICE', child: Text('Return to service')),
                            if (w.status != 'RETIRED')
                              const PopupMenuItem(value: 'RETIRED', child: Text('Retire')),
                          ],
                        )
                      : TextButton(
                          onPressed: () => _showHistory(context, ref, w),
                          child: const Text('History'),
                        ),
                );
              },
            );
          },
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Close')),
      ],
    );
  }

  Future<void> _act(
      BuildContext context, WidgetRef ref, WeighingInstrument w, String action) async {
    switch (action) {
      case 'verify':
        await _showVerificationForm(context, ref, w);
      case 'history':
        await _showHistory(context, ref, w);
      case 'edit':
        await _showForm(context, ref, w);
      default:
        await _setStatus(context, ref, w, action);
    }
  }

  Future<void> _setStatus(
      BuildContext context, WidgetRef ref, WeighingInstrument w, String status) async {
    if (status == 'RETIRED') {
      final ok = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Retire this instrument?'),
          content: const Text(
              'Retirement is final. A repaired or replaced scale is registered as a new instrument.'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
            FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Retire')),
          ],
        ),
      );
      if (ok != true) return;
    }
    try {
      await ref.read(apiClientProvider).dio.patch(
        '/${ApiConstants.tenant}/admin/stores/${store.id}/weighing-instruments/${w.id}/status',
        data: {'status': status},
      );
      ref.invalidate(storeInstrumentsProvider(store.id));
      ref.invalidate(certifiedInstrumentsProvider(store.id));
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(friendlyError(e, fallback: 'Could not change the status.'))));
    }
  }

  Future<void> _showForm(BuildContext context, WidgetRef ref, WeighingInstrument? existing) =>
      showDialog<void>(
        context: context,
        builder: (_) => _InstrumentForm(store: store, existing: existing),
      );

  Future<void> _showVerificationForm(
          BuildContext context, WidgetRef ref, WeighingInstrument w) =>
      showDialog<void>(
        context: context,
        builder: (_) => _VerificationForm(store: store, instrument: w),
      );

  Future<void> _showHistory(BuildContext context, WidgetRef ref, WeighingInstrument w) =>
      showDialog<void>(
        context: context,
        builder: (_) => _HistoryDialog(store: store, instrument: w),
      );
}

class _InstrumentForm extends ConsumerStatefulWidget {
  final StoreInfo store;
  final WeighingInstrument? existing;
  const _InstrumentForm({required this.store, this.existing});

  @override
  ConsumerState<_InstrumentForm> createState() => _InstrumentFormState();
}

class _InstrumentFormState extends ConsumerState<_InstrumentForm> {
  late final _identifier = TextEditingController(text: widget.existing?.identifier ?? '');
  late final _serial = TextEditingController(text: widget.existing?.serialNumber ?? '');
  late final _make = TextEditingController(text: widget.existing?.make ?? '');
  late final _model = TextEditingController(text: widget.existing?.model ?? '');
  final _capacity = TextEditingController();
  final _interval = TextEditingController();
  final _approval = TextEditingController();
  final _scheme = TextEditingController();
  late String _kind = widget.existing?.kind ?? 'COUNTER';
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    for (final c in [_identifier, _serial, _make, _model, _capacity, _interval, _approval, _scheme]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    final body = {
      'identifier': _identifier.text.trim(),
      'serialNumber': _serial.text.trim(),
      'make': _make.text.trim().isEmpty ? null : _make.text.trim(),
      'model': _model.text.trim().isEmpty ? null : _model.text.trim(),
      'kind': _kind,
      if (_capacity.text.trim().isNotEmpty) 'maxCapacity': double.tryParse(_capacity.text.trim()),
      if (_capacity.text.trim().isNotEmpty) 'capacityUom': 'KG',
      if (_interval.text.trim().isNotEmpty) 'scaleInterval': double.tryParse(_interval.text.trim()),
      'approvalRef': _approval.text.trim().isEmpty ? null : _approval.text.trim(),
      if (_kind == 'LABELLING' && _scheme.text.trim().isNotEmpty) 'labelScheme': _scheme.text.trim(),
    };
    final base = '/${ApiConstants.tenant}/admin/stores/${widget.store.id}/weighing-instruments';
    try {
      final dio = ref.read(apiClientProvider).dio;
      if (widget.existing == null) {
        await dio.post(base, data: body);
      } else {
        await dio.put('$base/${widget.existing!.id}', data: body);
      }
      ref.invalidate(storeInstrumentsProvider(widget.store.id));
      ref.invalidate(certifiedInstrumentsProvider(widget.store.id));
      if (mounted) Navigator.pop(context);
    } catch (e) {
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'Could not save the instrument.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.existing == null ? 'Register a weighing instrument' : 'Edit instrument'),
      content: SizedBox(
        width: 480,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                  controller: _identifier,
                  decoration: const InputDecoration(labelText: 'Name in the shop (e.g. Deli scale 2)')),
              TextField(
                  controller: _serial,
                  decoration: const InputDecoration(labelText: 'Serial number (from the plate)')),
              Row(children: [
                Expanded(
                    child: TextField(
                        controller: _make, decoration: const InputDecoration(labelText: 'Make'))),
                const SizedBox(width: 8),
                Expanded(
                    child: TextField(
                        controller: _model, decoration: const InputDecoration(labelText: 'Model'))),
              ]),
              DropdownButtonFormField<String>(
                initialValue: _kind,
                decoration: const InputDecoration(labelText: 'Kind'),
                items: const [
                  DropdownMenuItem(value: 'COUNTER', child: Text('Counter scale — read at the till')),
                  DropdownMenuItem(value: 'LABELLING', child: Text('Labelling scale — prints a barcode')),
                  DropdownMenuItem(value: 'PLATFORM', child: Text('Platform scale')),
                  DropdownMenuItem(value: 'HANGING', child: Text('Hanging scale')),
                ],
                onChanged: (v) => setState(() => _kind = v ?? 'COUNTER'),
              ),
              Row(children: [
                Expanded(
                    child: TextField(
                        controller: _capacity,
                        keyboardType: const TextInputType.numberWithOptions(decimal: true),
                        decoration: const InputDecoration(labelText: 'Max capacity (kg)'))),
                const SizedBox(width: 8),
                Expanded(
                    child: TextField(
                        controller: _interval,
                        keyboardType: const TextInputType.numberWithOptions(decimal: true),
                        decoration: const InputDecoration(labelText: 'Scale interval e (kg)'))),
              ]),
              TextField(
                  controller: _approval,
                  decoration: const InputDecoration(labelText: 'Type-approval / CE reference')),
              if (_kind == 'LABELLING')
                TextField(
                  controller: _scheme,
                  maxLines: 3,
                  decoration: const InputDecoration(
                    labelText: 'Label scheme (JSON)',
                    helperText:
                        '{"prefixes":["20"],"itemDigits":5,"valueKind":"PRICE","valueDecimals":2}',
                  ),
                ),
              if (_error != null) ...[
                const SizedBox(height: 8),
                Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
            onPressed: _saving ? null : _save,
            child: Text(widget.existing == null ? 'Register' : 'Save')),
      ],
    );
  }
}

class _VerificationForm extends ConsumerStatefulWidget {
  final StoreInfo store;
  final WeighingInstrument instrument;
  const _VerificationForm({required this.store, required this.instrument});

  @override
  ConsumerState<_VerificationForm> createState() => _VerificationFormState();
}

class _VerificationFormState extends ConsumerState<_VerificationForm> {
  String _kind = 'RE_VERIFICATION';
  final _on = TextEditingController(text: DateTime.now().toIso8601String().substring(0, 10));
  final _by = TextEditingController();
  final _cert = TextEditingController();
  final _due = TextEditingController();
  final _notes = TextEditingController();
  bool _passed = true;
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    for (final c in [_on, _by, _cert, _due, _notes]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.tenant}/admin/stores/${widget.store.id}/weighing-instruments/${widget.instrument.id}/verifications',
        data: {
          'kind': _kind,
          'performedOn': _on.text.trim(),
          'performedBy': _by.text.trim(),
          'certificateRef': _cert.text.trim().isEmpty ? null : _cert.text.trim(),
          'passed': _kind == 'REPAIR' ? false : _passed,
          'nextDue': _due.text.trim().isEmpty ? null : _due.text.trim(),
          'notes': _notes.text.trim().isEmpty ? null : _notes.text.trim(),
        },
      );
      ref.invalidate(storeInstrumentsProvider(widget.store.id));
      ref.invalidate(certifiedInstrumentsProvider(widget.store.id));
      if (mounted) Navigator.pop(context);
    } catch (e) {
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'Could not record it.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final repair = _kind == 'REPAIR';
    return AlertDialog(
      title: Text('Record for ${widget.instrument.identifier}'),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<String>(
                initialValue: _kind,
                decoration: const InputDecoration(labelText: 'What was done'),
                items: const [
                  DropdownMenuItem(value: 'INITIAL', child: Text('Initial verification (passed and stamped)')),
                  DropdownMenuItem(value: 'RE_VERIFICATION', child: Text('Re-verification')),
                  DropdownMenuItem(value: 'INSPECTION', child: Text('Trading-standards inspection')),
                  DropdownMenuItem(value: 'REPAIR', child: Text('Repair or adjustment (breaks the stamp)')),
                ],
                onChanged: (v) => setState(() => _kind = v ?? 'RE_VERIFICATION'),
              ),
              TextField(controller: _on, decoration: const InputDecoration(labelText: 'Date (YYYY-MM-DD)')),
              TextField(
                  controller: _by,
                  decoration: const InputDecoration(labelText: 'Done by (verifier or inspector)')),
              if (!repair) ...[
                SwitchListTile.adaptive(
                  value: _passed,
                  onChanged: (v) => setState(() => _passed = v),
                  title: const Text('Passed as fit for trade'),
                ),
                TextField(controller: _cert, decoration: const InputDecoration(labelText: 'Certificate reference')),
                TextField(
                    controller: _due,
                    decoration: const InputDecoration(labelText: 'Due again (YYYY-MM-DD, optional)')),
              ] else
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                      'A repair is never a pass. The instrument is out of trade until it is verified again.'),
                ),
              TextField(controller: _notes, decoration: const InputDecoration(labelText: 'Notes')),
              if (_error != null) ...[
                const SizedBox(height: 8),
                Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: _saving ? null : _save, child: const Text('Record')),
      ],
    );
  }
}

class _HistoryDialog extends ConsumerWidget {
  final StoreInfo store;
  final WeighingInstrument instrument;
  const _HistoryDialog({required this.store, required this.instrument});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final history = ref.watch(_historyProvider((store.id, instrument.id)));
    return AlertDialog(
      title: Text('History · ${instrument.identifier}'),
      content: SizedBox(
        width: 520,
        height: 380,
        child: history.when(
          loading: () => const LoadingView(label: 'Loading…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the history.'),
            onRetry: () => ref.invalidate(_historyProvider((store.id, instrument.id))),
          ),
          data: (rows) => rows.isEmpty
              ? const EmptyState(title: 'Never verified.')
              : ListView(
                  children: [
                    for (final r in rows)
                      ListTile(
                        leading: Icon(
                          r['kind'] == 'REPAIR'
                              ? Icons.build_outlined
                              : (r['passed'] == true ? Icons.check_circle_outline : Icons.cancel_outlined),
                        ),
                        title: Text('${verificationKindLabel(r['kind'] as String?)} · ${AppFormat.date(r['performedOn'] as String?)}'),
                        subtitle: Text([
                          r['performedBy'],
                          if (r['certificateRef'] != null) 'cert. ${r['certificateRef']}',
                          if (r['nextDue'] != null) 'due ${AppFormat.date(r['nextDue'] as String?)}',
                          if (r['notes'] != null) r['notes'],
                        ].join(' · ')),
                      ),
                  ],
                ),
        ),
      ),
      actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Close'))],
    );
  }
}

final _historyProvider =
    FutureProvider.autoDispose.family<List<Map<String, dynamic>>, (String, String)>((ref, key) async {
  final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.tenant}/admin/stores/${key.$1}/weighing-instruments/${key.$2}/verifications');
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => Map<String, dynamic>.from(e as Map))
      .toList();
});

/// A weighing instrument's kind in words, as the form offers it.
String instrumentKindLabel(String kind) => switch (kind.toUpperCase()) {
      'COUNTER' => 'counter scale',
      'LABELLING' => 'labelling scale',
      'PLATFORM' => 'platform scale',
      'HANGING' => 'hanging scale',
      _ => humanizeCode(kind).toLowerCase(),
    };

/// What was done to an instrument, in the form's own words.
String verificationKindLabel(String? kind) => switch ((kind ?? '').toUpperCase()) {
      'INITIAL' => 'Initial verification',
      'RE_VERIFICATION' => 'Re-verification',
      'INSPECTION' => 'Trading-standards inspection',
      'REPAIR' => 'Repair',
      _ => humanizeCode(kind),
    };
