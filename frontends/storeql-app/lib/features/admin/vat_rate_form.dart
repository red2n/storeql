import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/spacing.dart';
import 'pricing_providers.dart';

// ---------------------------------------------------------------------------
// VAT rates as a business sets them (SJ-D56).
//
// pricing-svc never assumes a rate: a variant with no VAT category is charged
// the business's standard rate, code T1, and until that is set nothing is
// quoted to a shopper or at the till. A business that charges no VAT sets it
// exempt. Rates are typed and shown as percentages; pricing-svc stores the
// fraction.
// ---------------------------------------------------------------------------

/// The code of the business's standard rate.
const standardVatCode = 'T1';

/// A rate typed as a percentage — "20", "7.5", "20 %", "7,5" — as the fraction pricing-svc stores
/// (0.2). Null for anything that is not a number from 0 to 100.
double? vatFractionFromPercent(String input) {
  final text = input.trim().replaceAll('%', '').trim().replaceAll(',', '.');
  if (!RegExp(r'^\d+(\.\d+)?$').hasMatch(text)) return null;
  final percent = double.parse(text);
  if (percent > 100) return null;
  return double.parse((percent / 100).toStringAsFixed(6));
}

/// A stored fraction as the percentage to show or edit: 0.2 → "20", 0.075 → "7.5".
String vatPercentText(double fraction) {
  final fixed = (fraction * 100).toStringAsFixed(4);
  return fixed.replaceFirst(RegExp(r'\.?0+$'), '');
}

/// Above the pricing tabs: nothing can be quoted until the standard rate is set.
class StandardVatBanner extends ConsumerWidget {
  final VoidCallback onAdd;

  const StandardVatBanner({super.key, required this.onAdd});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final rates = ref.watch(vatRatesProvider);
    // Only a list that was read can show the rate is missing; a failed or pending read says nothing.
    if (!rates.hasValue || rates.hasError) return const SizedBox.shrink();
    if (rates.requireValue.any((r) => r.code.toUpperCase() == standardVatCode)) {
      return const SizedBox.shrink();
    }
    final cs = Theme.of(context).colorScheme;
    final gutter = context.pageGutter;
    final icon = Icon(Icons.report_outlined, color: cs.onErrorContainer);
    final message = Text(
      'No standard VAT rate is set, so no price can be quoted to a shopper or at the '
      'till. Add the rate with code $standardVatCode — marked exempt if this business '
      'charges no VAT.',
      style: TextStyle(color: cs.onErrorContainer),
    );
    final button = FilledButton(
      key: const Key('standard-vat-add'),
      onPressed: onAdd,
      child: const Text('Add standard rate'),
    );
    // Inset by the page gutter, like the title above it and the lists below.
    return Padding(
      padding: EdgeInsetsDirectional.fromSTEB(gutter, AppSpacing.md, gutter, 0),
      child: Card(
        key: const Key('standard-vat-missing'),
        color: cs.errorContainer,
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: LayoutBuilder(
            builder: (context, constraints) {
              // Below 600px the button goes under the message: in one row on a phone it squeezed
              // the message to about 105px and eleven lines. With text at 130% and up it goes
              // under below laptop width too, by the rule PageHeader keeps.
              final largeText = MediaQuery.textScalerOf(context).scale(16) > 16 * 1.3;
              final stacked =
                  AppBreakpoints.classOf(constraints.maxWidth) == WindowClass.compact ||
                      (largeText && constraints.maxWidth < AppBreakpoints.expanded);
              if (stacked) {
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        icon,
                        const SizedBox(width: AppSpacing.md),
                        Expanded(child: message),
                      ],
                    ),
                    const SizedBox(height: AppSpacing.md),
                    Align(alignment: AlignmentDirectional.centerEnd, child: button),
                  ],
                );
              }
              return Row(
                children: [
                  icon,
                  const SizedBox(width: AppSpacing.md),
                  Expanded(child: message),
                  const SizedBox(width: AppSpacing.md),
                  button,
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}
