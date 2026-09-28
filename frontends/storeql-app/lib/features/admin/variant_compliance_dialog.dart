import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import 'providers/admin_providers.dart';
import '../../core/theme.dart';

// ---------------------------------------------------------------------------
// Allergens, origin, age restriction and how an item is sold — the statements a
// retailer must be able to make about an item before it is offered.
//
// The rule the dialog is built around: saving must never declare allergens by
// accident. An empty allergen list is a positive statement ("contains none of
// the fourteen"), so it is only sent when the person saving has said they
// checked it against the label — and any change afterwards takes that back.
// ---------------------------------------------------------------------------

enum AllergenPresence { none, mayContain, contains }

const ageRestrictionCategories = <String, String>{
  'ALCOHOL': 'Alcohol',
  'TOBACCO': 'Tobacco',
  'NICOTINE_VAPE': 'Vapes and nicotine products',
  'KNIVES': 'Knives and bladed articles',
  'CORROSIVES': 'Corrosive substances',
  'SOLVENTS': 'Solvents',
  'FIREWORKS': 'Fireworks',
  'LOTTERY': 'Lottery',
  'VIDEO_18': 'Age-rated film or game',
  'PETROL': 'Petrol',
};

class VariantComplianceDialog extends ConsumerStatefulWidget {
  final VariantInfo variant;

  const VariantComplianceDialog({super.key, required this.variant});

  @override
  ConsumerState<VariantComplianceDialog> createState() => _VariantComplianceDialogState();
}

class _VariantComplianceDialogState extends ConsumerState<VariantComplianceDialog> {
  bool _loading = true;
  bool _saving = false;
  String? _error;

  List<({String code, String name})> _allergens = const [];
  String _status = 'NOT_APPLICABLE';
  final Map<String, AllergenPresence> _presence = {};
  bool _food = false;
  bool _declarationChecked = false;

  final _ingredients = TextEditingController();
  final _hsn = TextEditingController();
  final _origin = TextEditingController();
  final _originDetail = TextEditingController();
  final _netContent = TextEditingController();
  final _netContentUom = TextEditingController();
  final _tare = TextEditingController();
  String? _restriction;
  String _soldBy = 'EACH';
  bool _catchWeight = false;
  // 09.16: the drinks container the item comes in, for the deposit a return
  // scheme puts on it where one is in force: material and volume, both or neither.
  String? _depositMaterial;
  final _depositVolume = TextEditingController();

  String get _base => '/${ApiConstants.product}';

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    for (final c in [_ingredients, _hsn, _origin, _originDetail, _netContent, _netContentUom, _tare, _depositVolume]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _load() async {
    final dio = ref.read(apiClientProvider).dio;
    final id = widget.variant.id;
    try {
      final replies = await Future.wait([
        dio.get('$_base/catalog/allergens'),
        dio.get('$_base/catalog/variants/$id/allergens'),
        dio.get('$_base/catalog/variants/$id/compliance'),
      ]);
      final list = (replies[0].data['data'] as List?) ?? const [];
      final declaration = replies[1].data['data'] as Map<String, dynamic>;
      final c = replies[2].data['data'] as Map<String, dynamic>;
      if (!mounted) return;
      setState(() {
        _allergens = [
          for (final a in list)
            (code: (a as Map)['code'] as String, name: a['name'] as String? ?? a['code'] as String),
        ];
        _status = declaration['status'] as String? ?? 'NOT_APPLICABLE';
        _food = _status != 'NOT_APPLICABLE';
        for (final a in (declaration['allergens'] as List? ?? const [])) {
          final m = a as Map;
          _presence[m['code'] as String] = m['presence'] == 'CONTAINS'
              ? AllergenPresence.contains
              : AllergenPresence.mayContain;
        }
        _ingredients.text = c['ingredients'] as String? ?? '';
        _hsn.text = c['hsnCode'] as String? ?? '';
        _origin.text = c['countryOfOrigin'] as String? ?? '';
        _originDetail.text = c['originDetail'] as String? ?? '';
        _restriction = c['restrictionCategory'] as String?;
        _soldBy = c['soldBy'] as String? ?? 'EACH';
        _netContent.text = (c['netContent'] as num?)?.toString() ?? '';
        _netContentUom.text = c['netContentUom'] as String? ?? '';
        _tare.text = (c['tareWeight'] as num?)?.toString() ?? '';
        _catchWeight = c['catchWeight'] as bool? ?? false;
        _depositMaterial = c['depositMaterial'] as String?;
        _depositVolume.text = (c['depositVolumeMl'] as num?)?.toString() ?? '';
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: "Couldn't load this item's details.");
      });
    }
  }

  static String? _blankToNull(String s) => s.trim().isEmpty ? null : s.trim();

  Future<void> _save() async {
    final declare = _food && _declarationChecked;
    setState(() {
      _saving = true;
      _error = null;
    });
    final dio = ref.read(apiClientProvider).dio;
    final id = widget.variant.id;
    try {
      // Every field, every time: this update replaces the item's compliance
      // details, so leaving one out would erase it.
      await dio.put('$_base/admin/products/variants/$id/compliance', data: {
        'food': _food,
        'countryOfOrigin': _blankToNull(_origin.text),
        'originDetail': _blankToNull(_originDetail.text),
        'restrictionCategory': _restriction,
        'ingredients': _blankToNull(_ingredients.text),
        'hsnCode': _blankToNull(_hsn.text),
        'soldBy': _soldBy,
        'netContent': num.tryParse(_netContent.text.trim()),
        'netContentUom': _blankToNull(_netContentUom.text),
        'tareWeight': num.tryParse(_tare.text.trim()),
        'catchWeight': _catchWeight,
        'depositMaterial': _depositMaterial,
        'depositVolumeMl': int.tryParse(_depositVolume.text.trim()),
      });
      if (declare) {
        await dio.put('$_base/admin/products/variants/$id/allergens', data: {
          'allergens': [
            for (final e in _presence.entries)
              if (e.value != AllergenPresence.none)
                {
                  'code': e.key,
                  'presence': e.value == AllergenPresence.contains ? 'CONTAINS' : 'MAY_CONTAIN',
                },
          ],
        });
      }
      if (!mounted) return;
      Navigator.pop(context, true);
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(declare ? 'Saved, and allergens declared.' : 'Saved.')));
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: "Couldn't save.");
      });
    }
  }

  void _choose(String code, AllergenPresence p) => setState(() {
        _presence[code] = p;
        // The check was of the answers as they were. Changing one un-checks it.
        _declarationChecked = false;
      });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final named = _presence.values.where((p) => p != AllergenPresence.none).length;
    // A hint, not a default: the business's own country when it is known,
    // never a country picked for it (multi-location, multi-tenant — SJ-D67).
    final tenantCountry = ref.watch(tenantInfoProvider).value?.country;
    final originHint = (tenantCountry == null || tenantCountry.isEmpty) ? null : tenantCountry;
    return AlertDialog(
      title: Text('Allergens and origin — ${widget.variant.sku}'),
      content: SizedBox(
        width: 600,
        child: _loading
            ? const SizedBox(height: 120, child: Center(child: CircularProgressIndicator()))
            : SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (_error != null) ...[
                      Text(_error!, style: TextStyle(color: cs.error)),
                      const SizedBox(height: 8),
                    ],
                    SwitchListTile.adaptive(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Food product'),
                      subtitle: const Text(
                          'Food must declare the 14 regulated allergens before shoppers are told anything about them.'),
                      value: _food,
                      onChanged: (v) => setState(() => _food = v),
                    ),
                    if (_food) ...[
                      _statusBanner(cs),
                      const SizedBox(height: 12),
                      Text('Allergens', style: text.titleSmall),
                      for (final a in _allergens)
                        Padding(
                          key: Key('allergen-${a.code}'),
                          padding: const EdgeInsets.symmetric(vertical: 2),
                          child: Row(
                            children: [
                              Expanded(child: Text(a.name)),
                              SegmentedButton<AllergenPresence>(
                                showSelectedIcon: false,
                                style: const ButtonStyle(visualDensity: VisualDensity.compact),
                                segments: const [
                                  ButtonSegment(value: AllergenPresence.none, label: Text('No')),
                                  ButtonSegment(
                                      value: AllergenPresence.mayContain,
                                      label: Text('May contain')),
                                  ButtonSegment(
                                      value: AllergenPresence.contains, label: Text('Contains')),
                                ],
                                selected: {_presence[a.code] ?? AllergenPresence.none},
                                onSelectionChanged: (s) => _choose(a.code, s.first),
                              ),
                            ],
                          ),
                        ),
                      CheckboxListTile(
                        contentPadding: EdgeInsets.zero,
                        controlAffinity: ListTileControlAffinity.leading,
                        value: _declarationChecked,
                        onChanged: (v) => setState(() => _declarationChecked = v ?? false),
                        title: Text(named == 0
                            ? 'I have checked the label: it contains none of the 14 allergens'
                            : 'I have checked this declaration against the label'),
                      ),
                      TextField(
                        controller: _ingredients,
                        maxLines: 3,
                        decoration:
                            const InputDecoration(labelText: 'Ingredients, as printed on the pack'),
                      ),
                    ],
                    const SizedBox(height: 16),
                    Text('Tax classification', style: text.titleSmall),
                    TextField(
                      key: const Key('compliance-hsn'),
                      controller: _hsn,
                      keyboardType: TextInputType.number,
                      maxLength: 8,
                      decoration: const InputDecoration(
                        labelText: 'HSN or SAC code',
                        helperText: '4, 6 or 8 digits. An Indian e-invoice names every line by it.',
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text('Origin', style: text.titleSmall),
                    Row(
                      children: [
                        SizedBox(
                          width: 110,
                          child: TextField(
                            controller: _origin,
                            maxLength: 2,
                            textCapitalization: TextCapitalization.characters,
                            decoration: InputDecoration(
                                labelText: 'Country', hintText: originHint, counterText: ''),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: TextField(
                            controller: _originDetail,
                            decoration: const InputDecoration(
                                labelText: 'Origin detail',
                                hintText: 'Produce of Spain, packed in the UK'),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 16),
                    Text('Age restriction', style: text.titleSmall),
                    DropdownButton<String?>(
                      isExpanded: true,
                      value: _restriction,
                      items: [
                        const DropdownMenuItem<String?>(
                            value: null, child: Text('Not age-restricted')),
                        for (final e in ageRestrictionCategories.entries)
                          DropdownMenuItem<String?>(value: e.key, child: Text(e.value)),
                        if (_restriction != null &&
                            !ageRestrictionCategories.containsKey(_restriction))
                          DropdownMenuItem<String?>(
                              value: _restriction, child: Text(_restriction!)),
                      ],
                      onChanged: (v) => setState(() => _restriction = v),
                    ),
                    const SizedBox(height: 16),
                    Text('Sold by', style: text.titleSmall),
                    const SizedBox(height: 4),
                    SegmentedButton<String>(
                      showSelectedIcon: false,
                      segments: const [
                        ButtonSegment(value: 'EACH', label: Text('Each')),
                        ButtonSegment(value: 'WEIGHT', label: Text('Weight')),
                        ButtonSegment(value: 'VOLUME', label: Text('Volume')),
                        ButtonSegment(value: 'LENGTH', label: Text('Length')),
                      ],
                      selected: {_soldBy},
                      onSelectionChanged: (s) => setState(() => _soldBy = s.first),
                    ),
                    if (_soldBy != 'EACH')
                      SwitchListTile.adaptive(
                        contentPadding: EdgeInsets.zero,
                        title: const Text('Catch weight'),
                        subtitle: const Text('Each item has its own weight and is priced on the scale'),
                        value: _catchWeight,
                        onChanged: (v) => setState(() => _catchWeight = v),
                      ),
                    const SizedBox(height: 16),
                    Text('Drinks container', style: text.titleSmall),
                    const SizedBox(height: 4),
                    Text(
                      'For a drink sold in a container a deposit return scheme takes back. '
                      'The scheme sets the deposit where the store trades; the till adds '
                      'it as its own line and pays it back on the empty.',
                      style: text.bodySmall,
                    ),
                    Row(
                      children: [
                        Expanded(
                          child: DropdownButtonFormField<String?>(
                            key: const Key('deposit-material'),
                            isExpanded: true,
                            initialValue: _depositMaterial,
                            decoration: const InputDecoration(labelText: 'Material'),
                            items: const [
                              DropdownMenuItem<String?>(value: null, child: Text('Not a drinks container')),
                              DropdownMenuItem<String?>(value: 'PET', child: Text('PET plastic')),
                              DropdownMenuItem<String?>(value: 'ALUMINIUM', child: Text('Aluminium')),
                              DropdownMenuItem<String?>(value: 'STEEL', child: Text('Steel')),
                              DropdownMenuItem<String?>(value: 'GLASS', child: Text('Glass')),
                            ],
                            onChanged: (v) => setState(() => _depositMaterial = v),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: TextField(
                            key: const Key('deposit-volume'),
                            controller: _depositVolume,
                            keyboardType: TextInputType.number,
                            decoration: const InputDecoration(labelText: 'Volume (ml)'),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: _netContent,
                            keyboardType: const TextInputType.numberWithOptions(decimal: true),
                            decoration: const InputDecoration(labelText: 'Net content'),
                          ),
                        ),
                        const SizedBox(width: 12),
                        SizedBox(
                          width: 90,
                          child: TextField(
                            controller: _netContentUom,
                            textCapitalization: TextCapitalization.characters,
                            decoration: const InputDecoration(labelText: 'Unit', hintText: 'KG'),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: TextField(
                            controller: _tare,
                            keyboardType: const TextInputType.numberWithOptions(decimal: true),
                            decoration: const InputDecoration(labelText: 'Tare weight'),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading || _saving ? null : _save,
          child: Text(_food && _declarationChecked ? 'Save and declare' : 'Save'),
        ),
      ],
    );
  }

  Widget _statusBanner(ColorScheme cs) {
    final (String label, Color bg, Color fg) = switch (_status) {
      'DECLARED' => ('Allergens declared.', cs.secondaryContainer, cs.onSecondaryContainer),
      'UNDECLARED' => (
          'Not yet declared. This item is on the allergen gaps list, and shoppers are told to ask in store.',
          cs.errorContainer,
          cs.onErrorContainer
        ),
      _ => (
          'Not yet marked as food. Saving puts it on the allergen gaps list until its allergens are declared.',
          cs.tertiaryContainer,
          cs.onTertiaryContainer
        ),
    };
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(color: bg, borderRadius: AppRadius.chip),
      child: Text(label, style: TextStyle(color: fg)),
    );
  }
}
