import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import '../../core/format.dart';

import '../../core/constants.dart';

// ---------------------------------------------------------------------------
// Selling by weight, volume or length at the till.
//
// A supermarket cannot trade without loose produce, deli and butchery, and the
// till could not ring any of it up: a line's quantity was a whole number. The
// reading entered here is the one an approved instrument shows. Pricing goods
// by weight on anything else is an offence (in the UK, under the Weights and
// Measures Act 1985), so the dialog asks for that reading and never for an
// estimate — and the till does no metrology of its own: tare is the scale's to
// deduct, not the software's to subtract.
// ---------------------------------------------------------------------------

/// How an item is sold, as far as the till needs to know.
sealed class SaleUnit {
  const SaleUnit();
}

class SoldEach extends SaleUnit {
  const SoldEach();
}

class SoldByMeasure extends SaleUnit {
  /// WEIGHT, VOLUME or LENGTH.
  final String soldBy;

  /// The unit the reading is in, lower case: kg, g, l, m.
  final String unit;

  /// Packaging weight the scale deducts, when the product declares one.
  final double? tare;
  final bool catchWeight;

  const SoldByMeasure({
    required this.soldBy,
    required this.unit,
    this.tare,
    required this.catchWeight,
  });
}

class SaleUnitUnknown extends SaleUnit {
  final String message;
  const SaleUnitUnknown(this.message);
}

/// The unit a measured quantity is shown in when the product names none.
String defaultUnitFor(String soldBy) => switch (soldBy) {
      'VOLUME' => 'l',
      'LENGTH' => 'm',
      _ => 'kg',
    };

/// Asks product-svc how [variantId] is sold.
///
/// An answer it cannot read is not taken as "each": ringing a loose kilo of
/// cheese up as one unit is a wrong price whichever way it falls.
Future<SaleUnit> fetchSaleUnit(Dio dio, String variantId) async {
  try {
    final resp = await dio
        .get('/${ApiConstants.product}/catalog/variants/$variantId/compliance');
    final data = (resp.data as Map?)?['data'];
    final soldBy = data is Map ? data['soldBy'] : null;
    if (soldBy == 'EACH') return const SoldEach();
    if (data is Map && soldBy is String && const {'WEIGHT', 'VOLUME', 'LENGTH'}.contains(soldBy)) {
      final uom = data['netContentUom'] as String?;
      return SoldByMeasure(
        soldBy: soldBy,
        unit: (uom == null || uom.trim().isEmpty) ? defaultUnitFor(soldBy) : uom.trim().toLowerCase(),
        tare: (data['tareWeight'] as num?)?.toDouble(),
        catchWeight: data['catchWeight'] == true,
      );
    }
    return const SaleUnitUnknown("Couldn't tell how this item is sold. Try again.");
  } on DioException {
    return const SaleUnitUnknown("Couldn't check how this item is sold. Try again.");
  }
}

/// A keyed reading, or null when it is not usable: blank, not a number, not
/// above zero, more than three decimal places, or four digits before the point.
double? parseMeasuredQuantity(String input) {
  final t = input.trim().replaceAll(',', '.');
  if (!RegExp(r'^\d{1,3}(\.\d{1,3})?$').hasMatch(t)) return null;
  final v = double.parse(t);
  return v > 0 ? v : null;
}

class MeasuredQuantityDialog extends StatefulWidget {
  final String itemName;
  final SoldByMeasure unit;
  final double unitPrice;
  final String currency;
  final double? initial;
  final String confirmLabel;

  const MeasuredQuantityDialog({
    super.key,
    required this.itemName,
    required this.unit,
    required this.unitPrice,
    required this.currency,
    this.initial,
    this.confirmLabel = 'Add',
  });

  @override
  State<MeasuredQuantityDialog> createState() => _MeasuredQuantityDialogState();
}

class _MeasuredQuantityDialogState extends State<MeasuredQuantityDialog> {
  late final TextEditingController _ctrl =
      TextEditingController(text: widget.initial?.toStringAsFixed(3) ?? '');

  double? get _value => parseMeasuredQuantity(_ctrl.text);

  String get _measure => switch (widget.unit.soldBy) {
        'VOLUME' => 'volume',
        'LENGTH' => 'length',
        _ => 'weight',
      };

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final u = widget.unit.unit;
    final v = _value;
    final label = '${_measure[0].toUpperCase()}${_measure.substring(1)} ($u)';
    return AlertDialog(
      title: Text('Enter the $_measure'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(widget.itemName, style: const TextStyle(fontWeight: FontWeight.bold)),
          Text('${AppFormat.money(widget.unitPrice, currencyCode: widget.currency)} / $u'),
          const SizedBox(height: 12),
          TextField(
            controller: _ctrl,
            autofocus: true,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: label, hintText: '0.000'),
            onChanged: (_) => setState(() {}),
            onSubmitted: (_) {
              final x = _value;
              if (x != null) Navigator.pop(context, x);
            },
          ),
          const SizedBox(height: 8),
          Text(
            v == null
                ? ' '
                : 'Line price: ${AppFormat.money(v * widget.unitPrice, currencyCode: widget.currency)}',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 8),
          Text(widget.unit.soldBy == 'WEIGHT'
              ? 'Enter the weight the approved scale shows. Do not estimate it.'
              : 'Enter the reading the approved measuring instrument shows. Do not estimate it.'),
          if (widget.unit.tare != null && widget.unit.tare! > 0)
            Text('Packaging of ${widget.unit.tare!.toStringAsFixed(3)} $u is deducted '
                'by the scale. Enter the net reading.'),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(
          onPressed: v == null ? null : () => Navigator.pop(context, v),
          child: Text(widget.confirmLabel),
        ),
      ],
    );
  }
}
