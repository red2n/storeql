import 'package:flutter/material.dart';

import '../../../core/amount_entry.dart';

/// A figure field: money, a quantity or a rate, typed the way the app's
/// language writes a number ([AmountMarks]) within the [AmountShape] the
/// receiving request keeps. Every key stays where it was typed; while the text
/// cannot be read the field says why under it, and the screen holding it sends
/// nothing ([figureRefused]). Blank is blank — a field the server defaults —
/// but a mark or a sign alone is refused, never sent as blank.
///
/// The screen rebuilds on [onChanged] so the refusal and its action follow
/// each key.
class FigureField extends StatelessWidget {
  const FigureField({
    super.key,
    this.fieldKey,
    required this.controller,
    required this.shape,
    required this.marks,
    this.label,
    this.helper,
    this.helperMaxLines,
    this.hint,
    this.dense = false,
    this.enabled = true,
    this.autofocus = false,
    this.suffix,
    this.onChanged,
  });

  /// The key on the text field itself, so a test or a form finds it.
  final Key? fieldKey;
  final TextEditingController controller;
  final AmountShape shape;
  final AmountMarks marks;
  final String? label;
  final String? helper;

  /// How many lines the helper may run to; one when not said.
  final int? helperMaxLines;

  /// The hint: nought written to the shape's places in the app's language
  /// unless the field says something else.
  final String? hint;
  final bool dense;
  final bool enabled;
  final bool autofocus;
  final String? suffix;
  final ValueChanged<String>? onChanged;

  @override
  Widget build(BuildContext context) => TextField(
        key: fieldKey,
        controller: controller,
        enabled: enabled,
        autofocus: autofocus,
        keyboardType: TextInputType.numberWithOptions(
          decimal: shape.decimals > 0,
          signed: shape.signed,
        ),
        decoration: InputDecoration(
          labelText: label,
          helperText: helper,
          helperMaxLines: helperMaxLines,
          hintText: hint ?? marks.hint(shape.decimals),
          suffixText: suffix,
          isDense: dense,
          errorText: shape.refusal(controller.text.trim(), marks),
          errorMaxLines: 4,
        ),
        onChanged: onChanged,
      );
}

/// Whether any of [figures] holds text its shape refuses: the screen then
/// sends nothing and the field says why.
bool figureRefused(AmountMarks marks, Iterable<(TextEditingController, AmountShape)> figures) =>
    figures.any((f) => f.$2.refusal(f.$1.text.trim(), marks) != null);

/// What [controller] holds, read as [shape]: the plain decimal typed (sent as
/// it is; JSON-B reads it exactly), or null when blank or refused.
String? figureOf(TextEditingController controller, AmountShape shape, AmountMarks marks) =>
    shape.read(controller.text.trim(), marks);

/// A whole number of things — days, parcels, a place in an order: nine
/// digits, which every service's `Integer` takes, and no decimals.
const wholeNumber = AmountShape(9, 0);

/// What [controller] holds, read as the whole number [shape] allows, or null
/// when blank or refused: never the reading of part of what was typed (`5`
/// out of `5.`), nor a default for text that is not blank.
int? wholeOf(TextEditingController controller, AmountMarks marks, {AmountShape shape = wholeNumber}) =>
    switch (figureOf(controller, shape, marks)) { final whole? => int.parse(whole), null => null };

/// A validator for a form's figure field: why [text] cannot be read as
/// [shape], in words; [blank] when nothing is typed and the figure is needed.
String? Function(String?) figureValidator(AmountShape shape, AmountMarks marks, {String? blank}) =>
    (text) {
      final t = text?.trim() ?? '';
      if (t.isEmpty) return blank;
      return shape.refusal(t, marks);
    };

/// The message a figure screen shows when a field it cannot read holds the
/// send: the field itself says why.
const figureRefusedMessage = 'A figure cannot be read. Correct the one marked.';
