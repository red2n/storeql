import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants.dart';
import '../../../core/network/api_client.dart';
import '../../../core/network/api_error.dart';
import '../../../core/spacing.dart';
import '../../../shared/widgets/empty_state.dart';
import '../../../shared/widgets/error_view.dart';
import '../../../shared/widgets/loading_view.dart';
import '../providers/admin_providers.dart';

// ---------------------------------------------------------------------------
// Finding a product by its name or its SKU, never by its id: the catalogue
// search the till and the storefront use, plus the new lines not yet on sale,
// opened from a read-only field that names the choice in words. Shared by the
// Inventory screen (Receive Stock, Set reorder level) and the Fulfilment
// screen (what a picker packed as a substitute).
// ---------------------------------------------------------------------------

/// A variant a form is about: its product by name, the variant in words
/// (*Side*, *250 ml*), its SKU — and the id the request carries.
class VariantChoice {
  final String variantId;
  final String productName;
  final String sku;

  /// What sets this variant apart, from its attributes; empty when it has none.
  final String variant;

  /// The product's place in its lifecycle when it is not simply on sale
  /// (*New line*, *Discontinued*); null when it is.
  final String? lifecycle;

  const VariantChoice({
    required this.variantId,
    required this.productName,
    required this.sku,
    this.variant = '',
    this.lifecycle,
  });

  /// How a form names it: *Plate · Side · PLT-SD*.
  String get label =>
      [productName, variant, sku].where((s) => s.isNotEmpty).join(' · ');

  /// The line under the product's name in the search: *Side · SKU PLT-SD*.
  String get detail => [
    if (variant.isNotEmpty) variant,
    if (sku.isNotEmpty) 'SKU $sku',
    ?lifecycle,
  ].join(' · ');
}

/// A variant's attributes in words — the values of its attribute object
/// (`{"size":"Side"}` → *Side*) — or empty when it has none.
String variantWords(Object? attributes) {
  Object? decoded = attributes;
  if (attributes is String) {
    if (attributes.trim().isEmpty) return '';
    try {
      decoded = jsonDecode(attributes);
    } on FormatException {
      return '';
    }
  }
  if (decoded is! Map) return '';
  return [
    for (final v in decoded.values)
      if (v is String && v.trim().isNotEmpty) v.trim() else if (v is num) '$v',
  ].join(' · ');
}

/// The new lines — listed, not yet on sale — which the catalogue search leaves
/// out, so the first delivery of one can be received (and its reorder level
/// set) before it launches. The newest hundred, matched by name on the device.
final _newLinesProvider = FutureProvider.autoDispose<List<ProductInfo>>((
  ref,
) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get(
        '/${ApiConstants.product}/admin/products',
        queryParameters: {'status': 'NEW_LINE', 'limit': 100},
      );
  final data = (resp.data['data'] as List?) ?? const [];
  return [
    for (final e in data) ProductInfo.fromJson(e as Map<String, dynamic>),
  ];
});

/// How many products one search opens up for their variants.
const _searchProductCap = 8;

/// The variants whose product's name holds [query], or whose SKU is [query]:
/// the catalogue search the till and the storefront use (`?q=` for a name,
/// `?sku=` for an exact SKU, as typed and in capitals), then the new lines by
/// name. An exact SKU comes first; a delisted variant never comes.
final _variantSearchProvider = FutureProvider.autoDispose
    .family<List<VariantChoice>, String>((ref, query) async {
      final q = query.trim();
      if (q.length < 2) return const [];
      final dio = ref.read(apiClientProvider).dio;
      final newLines = ref.watch(_newLinesProvider.future);

      Future<List<ProductInfo>> catalogue(Map<String, dynamic> params) async {
        final resp = await dio.get(
          '/${ApiConstants.product}/catalog/products',
          queryParameters: {...params, 'limit': 10},
        );
        final data = (resp.data['data'] as List?) ?? const [];
        return [
          for (final e in data) ProductInfo.fromJson(e as Map<String, dynamic>),
        ];
      }

      final found = await Future.wait([
        for (final sku in {q, q.toUpperCase()}) catalogue({'sku': sku}),
        catalogue({'q': q}),
      ]);
      List<ProductInfo> unlaunched;
      try {
        unlaunched = await newLines;
      } catch (_) {
        // Whoever may not read the admin list still finds what is on sale.
        unlaunched = const [];
      }
      final lower = q.toLowerCase();
      final products = <String, ProductInfo>{
        for (final list in found)
          for (final p in list) p.id: p,
        for (final p in unlaunched)
          if (p.name.toLowerCase().contains(lower)) p.id: p,
      }.values.take(_searchProductCap).toList();

      final variants = await Future.wait([
        for (final p in products)
          dio.get('/${ApiConstants.product}/admin/products/${p.id}/variants'),
      ]);
      final exact = <VariantChoice>[];
      final rest = <VariantChoice>[];
      for (var i = 0; i < products.length; i++) {
        final p = products[i];
        final lifecycle = p.status.toUpperCase() == 'ACTIVE'
            ? null
            : lifecycleLabelOf(p.status);
        for (final e in (variants[i].data['data'] as List?) ?? const []) {
          final v = e as Map<String, dynamic>;
          final id = v['id'] as String? ?? '';
          final status = (v['status'] as String? ?? '').toUpperCase();
          if (id.isEmpty || status == 'DELISTED') continue;
          final choice = VariantChoice(
            variantId: id,
            productName: p.name,
            sku: v['sku'] as String? ?? '',
            variant: variantWords(v['attributes']),
            lifecycle: lifecycle,
          );
          (choice.sku.toLowerCase() == lower ? exact : rest).add(choice);
        }
      }
      return [...exact, ...rest];
    });

/// Opens the product search over a form and answers the variant chosen,
/// or null when the person closes it.
Future<VariantChoice?> showVariantSearch(BuildContext context) =>
    showDialog<VariantChoice>(
      context: context,
      builder: (_) => const VariantSearchDialog(),
    );

/// The product a form is about, in words; a tap opens the search.
class VariantField extends StatelessWidget {
  final TextEditingController controller;
  final VoidCallback onTap;
  final String helperText;

  /// What the field asks for: `Product *` on a stock form, *What you packed*
  /// on a substitution.
  final String labelText;
  const VariantField({
    super.key,
    required this.controller,
    required this.onTap,
    required this.helperText,
    this.labelText = 'Product *',
  });

  @override
  Widget build(BuildContext context) => TextFormField(
    controller: controller,
    readOnly: true,
    onTap: onTap,
    minLines: 1,
    maxLines: 3,
    decoration: InputDecoration(
      labelText: labelText,
      hintText: 'Find by name or SKU',
      prefixIcon: const Icon(Icons.search),
      helperText: helperText,
      helperMaxLines: 2,
    ),
    validator: (v) => v == null || v.trim().isEmpty ? 'Choose a product' : null,
  );
}

/// Finds a variant by its product's name or its SKU, and answers the one
/// chosen: the product by name, the variant and its SKU under it.
class VariantSearchDialog extends ConsumerStatefulWidget {
  const VariantSearchDialog({super.key});

  @override
  ConsumerState<VariantSearchDialog> createState() =>
      _VariantSearchDialogState();
}

class _VariantSearchDialogState extends ConsumerState<VariantSearchDialog> {
  final _ctrl = TextEditingController();
  Timer? _debounce;
  String _query = '';

  @override
  void dispose() {
    _debounce?.cancel();
    _ctrl.dispose();
    super.dispose();
  }

  /// Searches for [text] once the typing pauses, or at once on [now].
  void _search(String text, {bool now = false}) {
    _debounce?.cancel();
    void run() {
      if (mounted) setState(() => _query = text.trim());
    }

    if (now) {
      run();
    } else {
      _debounce = Timer(const Duration(milliseconds: 300), run);
    }
  }

  @override
  Widget build(BuildContext context) {
    // Held while the search is open, so each new term does not fetch the new
    // lines again.
    ref.watch(_newLinesProvider);
    return AlertDialog(
      title: const Text('Find a product'),
      content: SizedBox(
        width: 440,
        height: 420,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: _ctrl,
              autofocus: true,
              textInputAction: TextInputAction.search,
              decoration: const InputDecoration(
                labelText: 'Name or SKU',
                prefixIcon: Icon(Icons.search),
              ),
              onChanged: _search,
              onSubmitted: (t) => _search(t, now: true),
            ),
            const SizedBox(height: AppSpacing.sm),
            Expanded(child: _results()),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
      ],
    );
  }

  Widget _results() {
    final q = _query;
    if (q.length < 2) {
      return const _Roomy(
        child: EmptyState(
          icon: Icons.search,
          title: 'Search the catalogue',
          message: 'Type two or more letters of the name, or the SKU.',
        ),
      );
    }
    return ref
        .watch(_variantSearchProvider(q))
        .when(
          loading: () => const LoadingView(),
          error: (e, _) => _Roomy(
            child: ErrorView(
              message: friendlyError(
                e,
                fallback: 'Could not search the catalogue.',
              ),
              onRetry: () => ref.invalidate(_variantSearchProvider(q)),
            ),
          ),
          data: (choices) => choices.isEmpty
              ? _Roomy(
                  child: EmptyState(
                    icon: Icons.search_off,
                    title: 'Nothing matches “$q”',
                    message:
                        'Try part of the name, or the SKU as it is printed.',
                  ),
                )
              : ListView.separated(
                  itemCount: choices.length,
                  separatorBuilder: (_, _) => const Divider(height: 1),
                  itemBuilder: (context, i) {
                    final c = choices[i];
                    return ListTile(
                      title: Text(
                        c.productName.isEmpty ? c.sku : c.productName,
                      ),
                      subtitle: Text(c.detail),
                      onTap: () => Navigator.pop(context, c),
                    );
                  },
                ),
        );
  }
}

/// Centres an [EmptyState] in the space under the search box, and scrolls it
/// when that space is shorter than it — a phone with large text — rather than
/// overflowing.
class _Roomy extends StatelessWidget {
  final Widget child;
  const _Roomy({required this.child});

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, bc) => SingleChildScrollView(
      child: ConstrainedBox(
        constraints: BoxConstraints(minHeight: bc.maxHeight),
        child: child,
      ),
    ),
  );
}
