import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/amount_entry.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'providers/admin_providers.dart';
import 'widgets/figure_field.dart';
import 'widgets/variant_picker.dart';

// ---------------------------------------------------------------------------
// Fresh yield, preparation and butchery loss. A template says what a primal
// (a side of beef, a whole salmon) should break into and what share is expected
// to be lost as bone, fat and trim; a breakdown at the counter consumes the
// primal, makes each cut a batch of its own at its apportioned cost and records
// the loss against what was expected. The month's breakdowns are the
// butchery-loss report.
// ---------------------------------------------------------------------------

class YieldOutputSpec {
  final String variantId;
  final double expectedPct;
  final double costShare;
  final int? shelfLifeDays;
  const YieldOutputSpec({
    required this.variantId,
    required this.expectedPct,
    required this.costShare,
    this.shelfLifeDays,
  });

  factory YieldOutputSpec.fromJson(Map<String, dynamic> j) => YieldOutputSpec(
        variantId: j['variantId'] as String? ?? '',
        expectedPct: (j['expectedPct'] as num?)?.toDouble() ?? 0,
        costShare: (j['costShare'] as num?)?.toDouble() ?? 0,
        shelfLifeDays: (j['shelfLifeDays'] as num?)?.toInt(),
      );
}

class YieldTemplate {
  final String id;
  final String name;
  final String inputVariantId;
  final String? unit;
  final bool active;
  final double expectedLossPct;
  final List<YieldOutputSpec> outputs;
  const YieldTemplate({
    required this.id,
    required this.name,
    required this.inputVariantId,
    this.unit,
    required this.active,
    required this.expectedLossPct,
    required this.outputs,
  });

  factory YieldTemplate.fromJson(Map<String, dynamic> j) => YieldTemplate(
        id: j['id'] as String? ?? '',
        name: j['name'] as String? ?? '',
        inputVariantId: j['inputVariantId'] as String? ?? '',
        unit: j['unit'] as String?,
        active: j['active'] as bool? ?? true,
        expectedLossPct: (j['expectedLossPct'] as num?)?.toDouble() ?? 0,
        outputs: ((j['outputs'] as List?) ?? const [])
            .map((e) => YieldOutputSpec.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

class YieldRunOutput {
  final String variantId;
  final double qty;
  final double expectedQty;
  final double? unitCost;
  const YieldRunOutput({
    required this.variantId,
    required this.qty,
    required this.expectedQty,
    this.unitCost,
  });

  factory YieldRunOutput.fromJson(Map<String, dynamic> j) => YieldRunOutput(
        variantId: j['variantId'] as String? ?? '',
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
        expectedQty: (j['expectedQty'] as num?)?.toDouble() ?? 0,
        unitCost: (j['unitCost'] as num?)?.toDouble(),
      );
}

class YieldRun {
  final String id;
  final String storeId;
  final String templateName;
  final double inputQty;
  final double? inputCost;
  final double outputQty;
  final double lossQty;
  final double lossPct;
  final double expectedLossQty;
  final double lossVariance;
  final double? lossAtCost;
  final String? reference;
  final String recordedAt;
  final List<YieldRunOutput> outputs;
  const YieldRun({
    required this.id,
    required this.storeId,
    required this.templateName,
    required this.inputQty,
    this.inputCost,
    required this.outputQty,
    required this.lossQty,
    required this.lossPct,
    required this.expectedLossQty,
    required this.lossVariance,
    this.lossAtCost,
    this.reference,
    required this.recordedAt,
    required this.outputs,
  });

  factory YieldRun.fromJson(Map<String, dynamic> j) => YieldRun(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        templateName: j['templateName'] as String? ?? '',
        inputQty: (j['inputQty'] as num?)?.toDouble() ?? 0,
        inputCost: (j['inputCost'] as num?)?.toDouble(),
        outputQty: (j['outputQty'] as num?)?.toDouble() ?? 0,
        lossQty: (j['lossQty'] as num?)?.toDouble() ?? 0,
        lossPct: (j['lossPct'] as num?)?.toDouble() ?? 0,
        expectedLossQty: (j['expectedLossQty'] as num?)?.toDouble() ?? 0,
        lossVariance: (j['lossVariance'] as num?)?.toDouble() ?? 0,
        lossAtCost: (j['lossAtCost'] as num?)?.toDouble(),
        reference: j['reference'] as String?,
        recordedAt: j['recordedAt'] as String? ?? '',
        outputs: ((j['outputs'] as List?) ?? const [])
            .map((e) => YieldRunOutput.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

class YieldTotals {
  final int runs;
  final double inputQty;
  final double outputQty;
  final double lossQty;
  final double expectedLossQty;
  final double lossAtCost;
  const YieldTotals({
    required this.runs,
    required this.inputQty,
    required this.outputQty,
    required this.lossQty,
    required this.expectedLossQty,
    required this.lossAtCost,
  });

  factory YieldTotals.fromJson(Map<String, dynamic> j) => YieldTotals(
        runs: (j['runs'] as num?)?.toInt() ?? 0,
        inputQty: (j['inputQty'] as num?)?.toDouble() ?? 0,
        outputQty: (j['outputQty'] as num?)?.toDouble() ?? 0,
        lossQty: (j['lossQty'] as num?)?.toDouble() ?? 0,
        expectedLossQty: (j['expectedLossQty'] as num?)?.toDouble() ?? 0,
        lossAtCost: (j['lossAtCost'] as num?)?.toDouble() ?? 0,
      );
}

class YieldReport {
  final List<YieldRun> runs;
  final YieldTotals totals;
  const YieldReport({required this.runs, required this.totals});

  factory YieldReport.fromJson(Map<String, dynamic> j) => YieldReport(
        runs: ((j['runs'] as List?) ?? const [])
            .map((e) => YieldRun.fromJson(e as Map<String, dynamic>))
            .toList(),
        totals: YieldTotals.fromJson((j['totals'] as Map<String, dynamic>?) ?? const {}),
      );
}

const _yield = '/admin/inventory/yield';

final yieldTemplatesProvider = FutureProvider.autoDispose<List<YieldTemplate>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get('/${ApiConstants.inventory}$_yield/templates');
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => YieldTemplate.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// This month's breakdowns and what they add up to.
final yieldRunsProvider = FutureProvider.autoDispose<YieldReport>((ref) async {
  final today = DateTime.now();
  final from = DateTime(today.year, today.month, 1).toIso8601String().split('T').first;
  final to = today.toIso8601String().split('T').first;
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.inventory}$_yield/runs',
    queryParameters: {'from': from, 'to': to},
  );
  return YieldReport.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// A quantity without trailing zeros: 35, 4.167.
String qtyText(num v) {
  if (v == v.roundToDouble()) return v.toStringAsFixed(0);
  final s = v.toStringAsFixed(3);
  return s.replaceFirst(RegExp(r'0+$'), '').replaceFirst(RegExp(r'\.$'), '');
}

/// The Inventory screen's "Yield & prep" tab.
class InventoryYieldTab extends ConsumerWidget {
  const InventoryYieldTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final management = auth is AuthAuthenticated && auth.isManager;
    final stores = ref.watch(storesProvider).value ?? const <StoreInfo>[];
    String storeName(String id) =>
        stores.where((s) => s.id == id).map((s) => s.name).firstOrNull ?? '…${shortRef(id)}';
    final templates = ref.watch(yieldTemplatesProvider);
    final runs = ref.watch(yieldRunsProvider);
    // Primals and cuts by their product's name; the end of an id only while
    // names load.
    final names = ref
            .watch(variantLabelsProvider(variantIdsKey([
              for (final t in templates.value ?? const []) ...[
                t.inputVariantId as String,
                for (final o in t.outputs) o.variantId as String,
              ],
            ])))
            .value ??
        const <String, VariantLabel>{};
    String product(String id) => variantDisplayName(id, names);
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;

    Widget header(String title, String detail, Widget? action) => Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: text.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 2),
                  Text(detail, style: text.bodySmall?.copyWith(color: cs.onSurfaceVariant)),
                ],
              ),
            ),
            if (action != null) ...[const SizedBox(width: 12), action],
          ],
        );

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        header(
          'Yield templates',
          'What a primal should break into: each cut as a share of what went in, and by difference'
              ' what is expected to be lost as bone, fat and trim. The figures are yours.',
          management
              ? FilledButton.icon(
                  key: const Key('yield-new-template'),
                  onPressed: () => showDialog(
                    context: context,
                    builder: (_) => const NewYieldTemplateDialog(),
                  ),
                  icon: const Icon(Icons.add),
                  label: const Text('New template'),
                )
              : null,
        ),
        const SizedBox(height: 12),
        templates.when(
          loading: () => const LoadingView(label: 'Loading templates…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load yield templates.'),
            onRetry: () => ref.invalidate(yieldTemplatesProvider),
          ),
          data: (list) => list.isEmpty
              ? const EmptyState(
                  icon: Icons.content_cut_outlined,
                  title: 'No yield templates',
                  detail: 'Say what a primal should break into before recording a breakdown.',
                )
              : Column(
                  children: [
                    for (final t in list)
                      Card(
                        child: ListTile(
                          key: Key('yield-template-${t.id}'),
                          leading: Icon(Icons.content_cut_outlined,
                              color: t.active ? cs.onSurfaceVariant : cs.outline),
                          title: Text('${t.name}${t.active ? '' : ' · ended'}',
                              style: const TextStyle(fontWeight: FontWeight.w600)),
                          subtitle: Text(
                            'From ${product(t.inputVariantId)}${t.unit == null ? '' : ' (${t.unit})'}: '
                            '${t.outputs.map((o) => '${product(o.variantId)} ${qtyText(o.expectedPct)} %').join(', ')}'
                            ' · expected loss ${qtyText(t.expectedLossPct)} %',
                          ),
                          trailing: management && t.active
                              ? TextButton(
                                  key: Key('yield-end-${t.id}'),
                                  onPressed: () async {
                                    await ref.read(apiClientProvider).dio.post(
                                        '/${ApiConstants.inventory}$_yield/templates/${t.id}/end');
                                    ref.invalidate(yieldTemplatesProvider);
                                  },
                                  child: const Text('End'),
                                )
                              : null,
                        ),
                      ),
                  ],
                ),
        ),
        const SizedBox(height: 24),
        header(
          'Breakdowns this month',
          'Each breakdown: what went in, what came out and what was lost against what was expected;'
              ' the loss at the primal\'s cost is what the bin took. The cuts carry the rest.',
          FilledButton.tonalIcon(
            key: const Key('yield-record'),
            onPressed: () => showDialog(
              context: context,
              builder: (_) => RecordYieldDialog(
                stores: stores,
                templates: (templates.value ?? const []).where((t) => t.active).toList(),
              ),
            ),
            icon: const Icon(Icons.restaurant_outlined),
            label: const Text('Record a breakdown'),
          ),
        ),
        const SizedBox(height: 12),
        runs.when(
          loading: () => const LoadingView(label: 'Loading breakdowns…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load breakdowns.'),
            onRetry: () => ref.invalidate(yieldRunsProvider),
          ),
          data: (r) => r.runs.isEmpty
              ? const EmptyState(
                  icon: Icons.receipt_long_outlined, title: 'No breakdowns this month')
              : Column(
                  children: [
                    for (final x in r.runs)
                      ListTile(
                        key: Key('yield-run-${x.id}'),
                        dense: true,
                        title: Text(
                          '${x.templateName} · ${qtyText(x.inputQty)} in, ${qtyText(x.outputQty)} out at ${storeName(x.storeId)}',
                        ),
                        subtitle: Text(
                          '${AppFormat.dateTime(x.recordedAt)}${x.reference == null ? '' : ' · ${x.reference}'}'
                          ' · lost ${qtyText(x.lossQty)} (${qtyText(x.lossPct)} %) against ${qtyText(x.expectedLossQty)} expected'
                          '${x.lossVariance > 0 ? ', ${qtyText(x.lossVariance)} over' : x.lossVariance < 0 ? ', ${qtyText(-x.lossVariance)} under' : ''}',
                        ),
                        trailing: x.lossAtCost == null
                            ? null
                            : Text(AppFormat.money(x.lossAtCost!),
                                style: TextStyle(color: cs.onSurfaceVariant)),
                      ),
                    ListTile(
                      title: Text('Lost this month', style: text.titleSmall),
                      subtitle: Text(
                        '${qtyText(r.totals.lossQty)} of ${qtyText(r.totals.inputQty)} in, against ${qtyText(r.totals.expectedLossQty)} expected',
                      ),
                      trailing: Text(
                        AppFormat.money(r.totals.lossAtCost),
                        key: const Key('yield-total-loss'),
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                    ),
                  ],
                ),
        ),
      ],
    );
  }
}

class _OutputRow {
  String? productId;
  String? variantId;
  final pct = TextEditingController();
  final share = TextEditingController();
  final life = TextEditingController();
  void dispose() {
    pct.dispose();
    share.dispose();
    life.dispose();
  }
}

/// Say what a primal should break into.
class NewYieldTemplateDialog extends ConsumerStatefulWidget {
  const NewYieldTemplateDialog({super.key});

  @override
  ConsumerState<NewYieldTemplateDialog> createState() => _NewYieldTemplateDialogState();
}

class _NewYieldTemplateDialogState extends ConsumerState<NewYieldTemplateDialog> {
  final _name = TextEditingController();
  final _unit = TextEditingController(text: 'kg');
  String? _productId;
  String? _variantId;
  final List<_OutputRow> _rows = [_OutputRow()];
  bool _busy = false;
  String? _refusal;

  // A cut's figures are read the way the app's language writes a number
  // ([AmountMarks]), within what inventory-svc keeps — a share NUMERIC(7,3), a
  // cost share NUMERIC(9,3), a shelf life in whole days — and sent as the
  // figures typed. One that cannot be read is refused under its field and
  // nothing is saved: parsed with a point, Romanian's 35,5 % went as a share
  // of nothing, and a cost share or shelf life the dialog could not read was
  // saved as none (by weight; the primal's date).
  final _marks = AmountMarks.ofApp();
  static const _pctShape = AmountShape(4, 3);
  static const _shareShape = AmountShape(6, 3);
  static const _lifeShape = AmountShape(5, 0);

  /// Every figure field of every cut, each with the shape it is read in.
  Iterable<(TextEditingController, AmountShape)> get _figures => [
        for (final r in _rows) ...[
          (r.pct, _pctShape),
          (r.share, _shareShape),
          (r.life, _lifeShape),
        ],
      ];

  @override
  void dispose() {
    _name.dispose();
    _unit.dispose();
    for (final r in _rows) {
      r.dispose();
    }
    super.dispose();
  }

  /// What the shares that can be read leave of the primal.
  double get _expectedLoss =>
      100 -
      _rows.fold<double>(
          0, (s, r) => s + double.parse(figureOf(r.pct, _pctShape, _marks) ?? '0'));

  Future<void> _save() async {
    if (figureRefused(_marks, _figures)) {
      setState(() => _refusal = figureRefusedMessage);
      return;
    }
    if (_name.text.trim().isEmpty || _variantId == null) {
      setState(() => _refusal = 'Name the template and pick the primal it breaks down.');
      return;
    }
    final outputs = <Map<String, dynamic>>[];
    for (final r in _rows) {
      if (r.variantId == null) continue;
      final pct = figureOf(r.pct, _pctShape, _marks);
      if (pct == null || pct == '0') {
        setState(() => _refusal = 'Say what share of the primal each cut is expected to be, more than 0.');
        return;
      }
      outputs.add({
        'variantId': r.variantId,
        // The plain decimals typed: JSON-B reads them exactly. A blank cost
        // share or shelf life is left out, for the template's own default.
        'expectedPct': pct,
        'costShare': ?figureOf(r.share, _shareShape, _marks),
        'shelfLifeDays': ?wholeOf(r.life, _marks, shape: _lifeShape),
      });
    }
    if (outputs.isEmpty) {
      setState(() => _refusal = 'Name at least one cut the primal yields.');
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.inventory}$_yield/templates',
        data: {
          'name': _name.text.trim(),
          'inputVariantId': _variantId,
          if (_unit.text.trim().isNotEmpty) 'unit': _unit.text.trim(),
          'outputs': outputs,
        },
      );
      ref.invalidate(yieldTemplatesProvider);
      if (!mounted) return;
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not save the template.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('New yield template'),
      content: SizedBox(
        width: 560,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                key: const Key('yield-name'),
                controller: _name,
                decoration: const InputDecoration(labelText: 'Name *', hintText: 'Side of beef'),
              ),
              const SizedBox(height: 8),
              Text('The primal', style: Theme.of(context).textTheme.labelLarge),
              VariantPicker(
                productId: _productId,
                variantId: _variantId,
                onProduct: (v) => setState(() {
                  _productId = v;
                  _variantId = null;
                }),
                onVariant: (v) => setState(() => _variantId = v),
              ),
              const SizedBox(height: 8),
              TextField(
                key: const Key('yield-unit'),
                controller: _unit,
                decoration: const InputDecoration(
                  labelText: 'Unit the shares are read in',
                  helperText: 'A yield is a weight story: the cuts are in the primal\'s unit',
                ),
              ),
              const SizedBox(height: 12),
              Text('The cuts', style: Theme.of(context).textTheme.labelLarge),
              for (var i = 0; i < _rows.length; i++) ...[
                const SizedBox(height: 8),
                VariantPicker(
                  productId: _rows[i].productId,
                  variantId: _rows[i].variantId,
                  onProduct: (v) => setState(() {
                    _rows[i].productId = v;
                    _rows[i].variantId = null;
                  }),
                  onVariant: (v) => setState(() => _rows[i].variantId = v),
                ),
                const SizedBox(height: 4),
                Row(
                  children: [
                    Expanded(
                      child: FigureField(
                        fieldKey: Key('yield-pct-$i'),
                        controller: _rows[i].pct,
                        shape: _pctShape,
                        marks: _marks,
                        label: 'Expected %',
                        hint: '0',
                        onChanged: (_) => setState(() {}),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: FigureField(
                        fieldKey: Key('yield-share-$i'),
                        controller: _rows[i].share,
                        shape: _shareShape,
                        marks: _marks,
                        label: 'Cost share',
                        helper: 'Blank: by weight',
                        hint: '0',
                        onChanged: (_) => setState(() {}),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: FigureField(
                        fieldKey: Key('yield-life-$i'),
                        controller: _rows[i].life,
                        shape: _lifeShape,
                        marks: _marks,
                        label: 'Shelf life (days)',
                        helper: 'Blank: primal\'s date',
                        onChanged: (_) => setState(() {}),
                      ),
                    ),
                    if (_rows.length > 1)
                      IconButton(
                        tooltip: 'Remove cut',
                        onPressed: () => setState(() => _rows.removeAt(i).dispose()),
                        icon: const Icon(Icons.remove_circle_outline),
                      ),
                  ],
                ),
              ],
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  key: const Key('yield-add-cut'),
                  onPressed: () => setState(() => _rows.add(_OutputRow())),
                  icon: const Icon(Icons.add),
                  label: const Text('Another cut'),
                ),
              ),
              Text(
                'Expected loss: ${qtyText(_expectedLoss)} %',
                key: const Key('yield-expected-loss'),
                style: TextStyle(
                    color: _expectedLoss < 0 ? cs.error : cs.onSurfaceVariant, fontSize: 12),
              ),
              if (_refusal != null) ...[
                const SizedBox(height: 12),
                Text(_refusal!, key: const Key('yield-refusal'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: _busy ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
            key: const Key('yield-save'), onPressed: _busy ? null : _save, child: const Text('Save')),
      ],
    );
  }
}

/// Record a breakdown: the primal in, each cut out, the loss known.
class RecordYieldDialog extends ConsumerStatefulWidget {
  const RecordYieldDialog({super.key, required this.stores, required this.templates});
  final List<StoreInfo> stores;
  final List<YieldTemplate> templates;

  @override
  ConsumerState<RecordYieldDialog> createState() => _RecordYieldDialogState();
}

class _RecordYieldDialogState extends ConsumerState<RecordYieldDialog> {
  String? _storeId;
  YieldTemplate? _template;
  final _input = TextEditingController();
  final _reference = TextEditingController();
  final Map<String, TextEditingController> _out = {};
  bool _busy = false;
  String? _refusal;

  // What went in and what came out are quantities, to three places, read the
  // way the app's language writes a number ([AmountMarks]) and sent as the
  // decimals typed. One that cannot be read is refused under its field and
  // nothing is recorded: parsed with a point, Romanian's 34,5 kg of sirloin
  // was recorded as none, all of it loss, and 1.250 kg as a kilo and a quarter.
  final _marks = AmountMarks.ofApp();

  /// The cuts' fields of the chosen template, with what went in.
  Iterable<(TextEditingController, AmountShape)> get _figures => [
        (_input, AmountShape.quantity),
        for (final o in _template?.outputs ?? const <YieldOutputSpec>[])
          if (_out[o.variantId] case final c?) (c, AmountShape.quantity),
      ];

  @override
  void dispose() {
    _input.dispose();
    _reference.dispose();
    for (final c in _out.values) {
      c.dispose();
    }
    super.dispose();
  }

  /// Fills each cut in at what the template expects of what went in, written
  /// the way the app's language writes a number so it reads back unchanged.
  void _prefill() {
    final typed = figureOf(_input, AmountShape.quantity, _marks);
    final t = _template;
    if (t == null || typed == null) return;
    final input = double.parse(typed);
    for (final o in t.outputs) {
      _out.putIfAbsent(o.variantId, TextEditingController.new).text =
          _marks.write(qtyText(input * o.expectedPct / 100));
    }
  }

  Future<void> _save() async {
    final t = _template;
    if (figureRefused(_marks, _figures)) {
      setState(() => _refusal = figureRefusedMessage);
      return;
    }
    final input = figureOf(_input, AmountShape.quantity, _marks);
    if (_storeId == null || t == null || input == null || input == '0') {
      setState(() => _refusal = 'Pick the store and the template, and say how much went in.');
      return;
    }
    setState(() {
      _busy = true;
      _refusal = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.inventory}$_yield/runs',
        data: {
          'storeId': _storeId,
          'templateId': t.id,
          // The plain decimals typed: JSON-B reads them exactly. A cut left
          // blank came to nothing.
          'inputQty': input,
          'outputs': [
            for (final o in t.outputs)
              {
                'variantId': o.variantId,
                'qty': switch (_out[o.variantId]) {
                  final c? => figureOf(c, AmountShape.quantity, _marks) ?? '0',
                  null => '0',
                },
              },
          ],
          if (_reference.text.trim().isNotEmpty) 'reference': _reference.text.trim(),
        },
      );
      final data = resp.data['data'] as Map<String, dynamic>? ?? const {};
      ref.invalidate(yieldRunsProvider);
      if (!mounted) return;
      final lost = (data['lossQty'] as num?)?.toDouble() ?? 0;
      final expected = (data['expectedLossQty'] as num?)?.toDouble() ?? 0;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('Recorded: ${qtyText(lost)} lost against ${qtyText(expected)} expected.'),
      ));
      Navigator.of(context).pop();
    } on DioException catch (e) {
      setState(() => _refusal = friendlyError(e, fallback: 'Could not record the breakdown.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final t = _template;
    final names = ref
            .watch(variantLabelsProvider(
                variantIdsKey([for (final o in t?.outputs ?? const []) o.variantId as String])))
            .value ??
        const <String, VariantLabel>{};
    return AlertDialog(
      title: const Text('Record a breakdown'),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              DropdownButtonFormField<String>(
                key: const Key('yield-store'),
                initialValue: _storeId,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Store *'),
                items: [
                  for (final s in widget.stores)
                    DropdownMenuItem(value: s.id, child: Text(s.name, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (v) => setState(() => _storeId = v),
              ),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                key: const Key('yield-template'),
                initialValue: t?.id,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Template *'),
                items: [
                  for (final x in widget.templates)
                    DropdownMenuItem(value: x.id, child: Text(x.name, overflow: TextOverflow.ellipsis)),
                ],
                onChanged: (v) => setState(() {
                  _template = widget.templates.where((x) => x.id == v).firstOrNull;
                  _prefill();
                }),
              ),
              const SizedBox(height: 8),
              FigureField(
                fieldKey: const Key('yield-input'),
                controller: _input,
                shape: AmountShape.quantity,
                marks: _marks,
                label: 'Went in *${t?.unit == null ? '' : ' (${t!.unit})'}',
                helper: 'The cuts below are filled in at what the template expects',
                hint: '0',
                onChanged: (_) => setState(_prefill),
              ),
              if (t != null) ...[
                const SizedBox(height: 8),
                for (final o in t.outputs) ...[
                  FigureField(
                    fieldKey: Key('yield-out-${o.variantId}'),
                    controller: _out.putIfAbsent(o.variantId, TextEditingController.new),
                    shape: AmountShape.quantity,
                    marks: _marks,
                    label: '${variantDisplayName(o.variantId, names)} came out',
                    helper: 'Expected ${qtyText(o.expectedPct)} %',
                    hint: '0',
                    onChanged: (_) => setState(() {}),
                  ),
                  const SizedBox(height: 4),
                ],
              ],
              const SizedBox(height: 8),
              TextField(
                key: const Key('yield-reference'),
                controller: _reference,
                decoration: const InputDecoration(labelText: 'Reference (the docket)'),
              ),
              if (_refusal != null) ...[
                const SizedBox(height: 12),
                Text(_refusal!, key: const Key('yield-run-refusal'), style: TextStyle(color: cs.error)),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: _busy ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
            key: const Key('yield-run-save'),
            onPressed: _busy ? null : _save,
            child: const Text('Record')),
      ],
    );
  }
}
