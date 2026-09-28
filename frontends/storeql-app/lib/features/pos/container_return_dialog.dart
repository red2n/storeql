import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../storefront/storefront_providers.dart' show DepositScheme;
import 'pos_providers.dart';
import 'pos_session_providers.dart';
import 'package:storeql_app/core/ids.dart';

// ---------------------------------------------------------------------------
// Container return (09.16).
//
// A customer brings empties back; the till pays the scheme's deposit on each
// container the scheme takes back. order-svc records the refund and tells
// payment-svc, which books the cash out of this till session's drawer once.
// ---------------------------------------------------------------------------

class _ReturnLine {
  String material;
  final volume = TextEditingController();
  final count = TextEditingController(text: '1');
  _ReturnLine(this.material);
  void dispose() {
    volume.dispose();
    count.dispose();
  }
}

/// Opens the dialog; resolves to the amount paid back, or null when nothing was.
Future<double?> showContainerReturnDialog(BuildContext context) =>
    showDialog<double>(
      context: context,
      builder: (_) => const ContainerReturnDialog(),
    );

class ContainerReturnDialog extends ConsumerStatefulWidget {
  const ContainerReturnDialog({super.key});

  @override
  ConsumerState<ContainerReturnDialog> createState() =>
      _ContainerReturnDialogState();
}

class _ContainerReturnDialogState extends ConsumerState<ContainerReturnDialog> {
  final List<_ReturnLine> _lines = [];
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    for (final l in _lines) {
      l.dispose();
    }
    super.dispose();
  }

  double _amount(DepositScheme s) {
    var total = 0.0;
    for (final l in _lines) {
      final n = int.tryParse(l.count.text.trim()) ?? 0;
      final v = int.tryParse(l.volume.text.trim());
      if (n > 0 && s.covers(l.material, v)) total += n * s.depositEach;
    }
    return total;
  }

  Future<void> _refund(DepositScheme scheme) async {
    final session = ref.read(posSessionProvider);
    final storeId = ref.read(posStoreProvider) ?? session?.storeId;
    if (session == null || storeId == null) {
      setState(() => _error = 'Clock in to a store first.');
      return;
    }
    final lines = [
      for (final l in _lines)
        if ((int.tryParse(l.count.text.trim()) ?? 0) > 0)
          {
            'material': l.material,
            'volumeMl': int.tryParse(l.volume.text.trim()) ?? 0,
            'count': int.tryParse(l.count.text.trim()) ?? 0,
          }
    ];
    if (lines.isEmpty) {
      setState(() => _error = 'Add at least one container.');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
            '/${ApiConstants.order}/orders/container-refunds',
            data: {
              'storeId': storeId,
              'tillSessionId': session.id,
              'lines': lines,
            },
            options: Options(headers: {
              'Idempotency-Key':
                  newId(),
            }),
          );
      final amount =
          ((resp.data['data'] as Map<String, dynamic>)['amount'] as num?)
                  ?.toDouble() ??
              0;
      ref.read(posSessionProvider.notifier).touch();
      if (!mounted) return;
      Navigator.pop(context, amount);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'The refund was not recorded.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final session = ref.watch(posSessionProvider);
    final storeId = ref.watch(posStoreProvider) ?? session?.storeId;
    final schemeAsync = storeId == null
        ? const AsyncValue<DepositScheme?>.data(null)
        : ref.watch(posDepositSchemeProvider(storeId));
    return AlertDialog(
      title: const Text('Container return'),
      content: SizedBox(
        width: 460,
        child: schemeAsync.when(
          loading: () => const SizedBox(
              height: 80, child: Center(child: CircularProgressIndicator())),
          error: (e, _) => Text(friendlyError(e,
              fallback: 'Could not read the deposit scheme for this store.')),
          data: (scheme) {
            if (scheme == null) {
              return const Text(
                  key: Key('no-scheme'),
                  'No deposit return scheme is in force where this store '
                  'trades, so there is no deposit to pay back.');
            }
            if (_lines.isEmpty) _lines.add(_ReturnLine(scheme.materials.first));
            final amount = _amount(scheme);
            return SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    '${AppFormat.money(scheme.depositEach, currencyCode: scheme.currency)} '
                    'back on each container of ${scheme.inWords}.',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                  const SizedBox(height: 8),
                  for (var i = 0; i < _lines.length; i++)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: Row(
                        children: [
                          Expanded(
                            flex: 3,
                            child: DropdownButtonFormField<String>(
                              key: Key('return-material-$i'),
                              initialValue: _lines[i].material,
                              decoration:
                                  const InputDecoration(labelText: 'Material'),
                              items: [
                                for (final m in scheme.materials)
                                  DropdownMenuItem(value: m, child: Text(m)),
                              ],
                              onChanged: (v) =>
                                  setState(() => _lines[i].material = v ?? ''),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            flex: 2,
                            child: TextField(
                              key: Key('return-volume-$i'),
                              controller: _lines[i].volume,
                              keyboardType: TextInputType.number,
                              decoration: const InputDecoration(labelText: 'ml'),
                              onChanged: (_) => setState(() {}),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            flex: 2,
                            child: TextField(
                              key: Key('return-count-$i'),
                              controller: _lines[i].count,
                              keyboardType: TextInputType.number,
                              decoration:
                                  const InputDecoration(labelText: 'Count'),
                              onChanged: (_) => setState(() {}),
                            ),
                          ),
                        ],
                      ),
                    ),
                  TextButton.icon(
                    onPressed: () => setState(
                        () => _lines.add(_ReturnLine(scheme.materials.first))),
                    icon: const Icon(Icons.add),
                    label: const Text('Another kind'),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    key: const Key('return-amount'),
                    'Pay back ${AppFormat.money(amount, currencyCode: scheme.currency)}',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  if (_error != null) ...[
                    const SizedBox(height: 8),
                    Text(_error!,
                        style: TextStyle(
                            color: Theme.of(context).colorScheme.error)),
                  ],
                ],
              ),
            );
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('return-refund'),
          onPressed: _saving || schemeAsync.value == null
              ? null
              : () => _refund(schemeAsync.value!),
          child: Text(_saving ? 'Recording…' : 'Refund'),
        ),
      ],
    );
  }
}
