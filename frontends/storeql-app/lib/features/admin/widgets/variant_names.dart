import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/admin_providers.dart';

/// Builds with the product names of [ids], read in one request
/// ([variantLabelsProvider]), so a list of lines reads *Blue mug*, not a
/// fragment of an id. Until the names arrive — or for a variant product-svc
/// does not know — [variantDisplayName] stands in with the end of the id.
///
/// For a part of a screen that is not itself a consumer: wrap the lines in it
/// and read each name from the map it hands the builder.
class VariantNames extends ConsumerWidget {
  const VariantNames({super.key, required this.ids, required this.builder});

  /// Every variant the [builder] names.
  final Iterable<String> ids;
  final Widget Function(BuildContext context, Map<String, VariantLabel> labels)
      builder;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final labels =
        ref.watch(variantLabelsProvider(variantIdsKey(ids))).value ??
            const <String, VariantLabel>{};
    return builder(context, labels);
  }
}

/// A variant's product name and, under it, its SKU (and anything else in
/// [detail]), in the list's own text styles.
class VariantLine extends StatelessWidget {
  const VariantLine({
    super.key,
    required this.variantId,
    required this.labels,
    this.detail = const [],
    this.nameStyle,
  });

  final String variantId;
  final Map<String, VariantLabel> labels;

  /// More to say on the second line, after the SKU.
  final List<String> detail;
  final TextStyle? nameStyle;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final second = [
      variantSku(variantId, labels),
      ...detail,
    ].where((p) => p.isNotEmpty).join(' · ');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(variantDisplayName(variantId, labels), style: nameStyle),
        if (second.isNotEmpty)
          Text(
            second,
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
      ],
    );
  }
}
