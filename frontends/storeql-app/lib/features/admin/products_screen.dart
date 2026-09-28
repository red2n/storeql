import 'product_safety_dialog.dart';
import 'package:dio/dio.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/util/image_compress.dart';
import '../../shared/widgets/barcode_scanner_sheet.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/scrollable_table.dart';
import '../../shared/widgets/status_badge.dart';
import 'providers/admin_providers.dart';
import 'providers/products_pagination.dart';
import 'variant_compliance_dialog.dart';

class ProductsScreen extends ConsumerStatefulWidget {
  const ProductsScreen({super.key});

  @override
  ConsumerState<ProductsScreen> createState() => _ProductsScreenState();
}

class _ProductsScreenState extends ConsumerState<ProductsScreen> {
  String _search = '';
  String? _categoryFilter;

  /// Holds the search text, so it survives the filters moving between the
  /// phone layout and the wide one.
  final _searchCtrl = TextEditingController();

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  /// Reloads the first page of products and the categories: the Refresh
  /// button, and a pull on the phone list.
  Future<void> _refresh() {
    final reload =
        ref.read(productsPaginationProvider(_categoryFilter).notifier).refresh();
    ref.invalidate(categoriesProvider);
    return reload;
  }

  @override
  Widget build(BuildContext context) {
    final page = ref.watch(productsPaginationProvider(_categoryFilter));
    final catsAsync = ref.watch(categoriesProvider);
    // One inset for the title, the filters and the table or list under them.
    final gutter = context.pageGutter;

    final cats = catsAsync.value ?? [];
    final catById = {for (var c in cats) c.id: c};
    final loaded = page.products.length;

    return Scaffold(
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _showCreateDialog(context, ref, cats),
        icon: const Icon(Icons.add),
        label: const Text('New Product'),
      ),
      body: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Header
        Padding(
          padding: EdgeInsetsDirectional.fromSTEB(
              gutter, gutter, gutter, AppSpacing.lg),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Online products listed before EU product safety law was enforced here (01.12).
              const MissingSafetyBanner(),
              PageHeader(
                title: 'Products',
                // "+" signals more exist beyond what's loaded so far — the loaded count
                // alone isn't the tenant's true total once results span more than one page.
                subtitle: page.isLoadingInitial || page.error != null
                    ? null
                    : '$loaded${page.hasMore ? '+' : ''} '
                        '${loaded == 1 && !page.hasMore ? 'product' : 'products'}',
                padding: EdgeInsets.zero,
                actions: [
                  OutlinedButton.icon(
                    onPressed: _refresh,
                    icon: const Icon(Icons.refresh),
                    label: const Text('Refresh'),
                  ),
                ],
              ),
            ],
          ),
        ),

        // Filters: on a phone the search takes the whole width and the
        // category sits under it, so neither squeezes the other.
        Padding(
          padding:
              EdgeInsetsDirectional.fromSTEB(gutter, 0, gutter, AppSpacing.md),
          child: LayoutBuilder(builder: (context, constraints) {
            final search = SearchBar(
              controller: _searchCtrl,
              hintText: 'Search products…',
              leading: const Icon(Icons.search),
              onChanged: (v) => setState(() => _search = v.trim()),
            );
            final category = cats.isEmpty
                ? null
                : DropdownButton<String?>(
                    value: _categoryFilter,
                    isExpanded: true,
                    hint: const Text('All categories'),
                    underline: const SizedBox.shrink(),
                    items: [
                      const DropdownMenuItem(
                          value: null, child: Text('All categories')),
                      ...cats
                          .where((c) => c.status.toUpperCase() == 'ACTIVE')
                          .map((c) => DropdownMenuItem(
                              value: c.id,
                              child: Text(c.name,
                                  overflow: TextOverflow.ellipsis))),
                    ],
                    onChanged: (v) => setState(() => _categoryFilter = v),
                  );
            if (AppBreakpoints.classOf(constraints.maxWidth) ==
                WindowClass.compact) {
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                mainAxisSize: MainAxisSize.min,
                children: [
                  search,
                  if (category != null) ...[
                    const SizedBox(height: AppSpacing.sm),
                    category,
                  ],
                ],
              );
            }
            return Row(
              children: [
                Expanded(child: search),
                if (category != null) ...[
                  const SizedBox(width: AppSpacing.md),
                  SizedBox(width: 240, child: category),
                ],
              ],
            );
          }),
        ),

        // List
        Expanded(
          child: Builder(builder: (context) {
            if (page.isLoadingInitial) {
              return const LoadingView(label: 'Loading products…');
            }
            if (page.error != null && page.products.isEmpty) {
              return ErrorView(
                message: 'Could not load products.',
                onRetry: () => ref
                    .read(productsPaginationProvider(_categoryFilter).notifier)
                    .refresh(),
              );
            }
            // The category filter is applied server-side (it's the pagination family key);
            // free-text search stays a client-side filter over whatever's loaded so far.
            final filtered = _search.isEmpty
                ? page.products
                : page.products
                    .where((p) =>
                        p.name.toLowerCase().contains(_search.toLowerCase()))
                    .toList();

            if (filtered.isEmpty) {
              return EmptyState(
                icon: Icons.inventory_2_outlined,
                title: page.products.isEmpty
                    ? 'No products yet'
                    : 'No products match the filter',
                action: page.products.isEmpty
                    ? OutlinedButton.icon(
                        onPressed: () =>
                            _showCreateDialog(context, ref, cats),
                        icon: const Icon(Icons.add),
                        label: const Text('New Product'),
                      )
                    : TextButton(
                        onPressed: () => setState(() {
                          _search = '';
                          _searchCtrl.clear();
                          _categoryFilter = null;
                        }),
                        child: const Text('Clear filter'),
                      ),
              );
            }

            final Widget? loadMore = page.hasMore || page.isLoadingMore
                ? (page.isLoadingMore
                    ? const SizedBox(
                        height: 20,
                        width: 20,
                        child: CircularProgressIndicator(strokeWidth: 2))
                    : OutlinedButton(
                        onPressed: () => ref
                            .read(productsPaginationProvider(_categoryFilter)
                                .notifier)
                            .loadMore(),
                        child: const Text('Load more'),
                      ))
                : null;

            return LayoutBuilder(builder: (context, bc) {
              if (AppBreakpoints.classOf(bc.maxWidth) != WindowClass.compact) {
                return Column(
                  children: [
                    Expanded(
                      child: _WideTable(
                        products: filtered,
                        catById: catById,
                        onViewVariants: (p) =>
                            _showVariantsDialog(context, ref, p),
                        onAssortment: (p) =>
                            _showAssortmentDialog(context, ref, p),
                        onImage: (p) => _manageImage(context, ref, p),
                        onDelist: (p) => _delist(context, ref, p),
                        onLifecycle: (p, move) => _lifecycle(context, ref, p, move),
                      ),
                    ),
                    // The band under the table holds Load more and keeps the
                    // New Product button clear of the table's last rows.
                    ConstrainedBox(
                      constraints: const BoxConstraints(
                          minHeight: AppSpacing.fabClearance),
                      child: Center(child: loadMore),
                    ),
                  ],
                );
              }
              return _NarrowList(
                products: filtered,
                catById: catById,
                footer: loadMore,
                onRefresh: _refresh,
                onViewVariants: (p) => _showVariantsDialog(context, ref, p),
                onAssortment: (p) => _showAssortmentDialog(context, ref, p),
                onImage: (p) => _manageImage(context, ref, p),
                onDelist: (p) => _delist(context, ref, p),
                onLifecycle: (p, move) => _lifecycle(context, ref, p, move),
              );
            });
          }),
        ),
      ],
      ),
    );
  }

  void _showCreateDialog(
      BuildContext context, WidgetRef ref, List<CategoryInfo> cats) {
    showDialog(
      context: context,
      builder: (_) => _ProductDialog(
        cats: cats,
        onSave: (data) async {
          await ref.read(apiClientProvider).dio.post(
                '/${ApiConstants.product}/admin/products',
                data: data,
              );
          ref.read(productsPaginationProvider(_categoryFilter).notifier).refresh();
        },
      ),
    );
  }

  void _showVariantsDialog(
      BuildContext context, WidgetRef ref, ProductInfo product) {
    showDialog(
      context: context,
      builder: (_) => _VariantsDialog(product: product),
    );
  }

  void _showAssortmentDialog(
      BuildContext context, WidgetRef ref, ProductInfo product) {
    showDialog(
      context: context,
      builder: (_) => _AssortmentDialog(product: product),
    );
  }

  /// Owner uploads (or removes) the product's storefront image, via product-svc's
  /// PUT /admin/products/{id}/image contract.
  ///
  /// The picked file is downscaled and re-encoded locally before it leaves the browser
  /// (see shared/util/image_compress.dart), so the owner can pick a full-size camera
  /// photo instead of being told to go and resize it. That keeps the upload, the BYTEA
  /// row in Postgres, and every storefront render small, and strips EXIF GPS on the way.
  Future<void> _manageImage(
      BuildContext context, WidgetRef ref, ProductInfo product) async {
    final action = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Image for "${product.name}"'),
        content: const Text(
            'Pick a JPEG, PNG or WebP — any size. It is optimised for the web '
            'automatically, then appears on the storefront catalog and product page.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Cancel')),
          OutlinedButton.icon(
            onPressed: () => Navigator.pop(ctx, 'remove'),
            icon: const Icon(Icons.delete_outline, size: 18),
            label: const Text('Remove image'),
          ),
          FilledButton.icon(
            onPressed: () => Navigator.pop(ctx, 'upload'),
            icon: const Icon(Icons.upload_outlined, size: 18),
            label: const Text('Choose file…'),
          ),
        ],
      ),
    );
    if (action == null || !context.mounted) return;

    final dio = ref.read(apiClientProvider).dio;
    try {
      if (action == 'remove') {
        await dio.delete(
            '/${ApiConstants.product}/admin/products/${product.id}/image');
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Product image removed.')));
        }
        return;
      }
      final picked = await FilePicker.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['jpg', 'jpeg', 'png', 'webp'],
        withData: true,
      );
      final file = picked?.files.firstOrNull;
      final bytes = file?.bytes;
      if (file == null || bytes == null) return;

      final CompressedImage upload;
      try {
        upload = await compressProductImage(
          bytes,
          sourceContentType: contentTypeForExtension(file.extension),
        );
      } on ImageCompressException catch (e) {
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(e.message),
            backgroundColor: Theme.of(context).colorScheme.error,
          ));
        }
        return;
      }

      await dio.put(
        '/${ApiConstants.product}/admin/products/${product.id}/image',
        data: upload.bytes,
        options: Options(contentType: upload.contentType),
      );
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(
                'Product image uploaded (${formatBytes(upload.bytes.length)}).')));
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(friendlyError(e, fallback: 'Image update failed.')),
          backgroundColor: Theme.of(context).colorScheme.error,
        ));
      }
    }
  }

  /// A lifecycle move (item lifecycle): launch a new line, discontinue a line
  /// for run-down, or reinstate one. The server refuses a move a product cannot
  /// make from where it is.
  Future<void> _lifecycle(
      BuildContext context, WidgetRef ref, ProductInfo product, String move) async {
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post('/${ApiConstants.product}/admin/products/${product.id}/$move');
      ref.read(productsPaginationProvider(_categoryFilter).notifier).refresh();
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(switch (move) {
        'launch' => '${product.name} is on sale.',
        'discontinue' =>
          '${product.name} is being run down: sold while stock lasts, not reordered.',
        _ => '${product.name} is back on sale.',
      })));
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(friendlyError(e, fallback: 'Could not change the line.'))));
    }
  }

  Future<void> _delist(
      BuildContext context, WidgetRef ref, ProductInfo product) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Delist "${product.name}"?'),
        content:
            const Text('The product will be hidden from the storefront and POS.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel')),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(ctx).colorScheme.error,
              foregroundColor: Theme.of(ctx).colorScheme.onError,
            ),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Delist'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) return;
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .delete('/${ApiConstants.product}/admin/products/${product.id}');
      ref.read(productsPaginationProvider(_categoryFilter).notifier).refresh();
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content:
              Text(friendlyError(e, fallback: 'Could not delist product.')),
          backgroundColor: Theme.of(context).colorScheme.error,
        ));
      }
    }
  }
}

// ── Wide table ────────────────────────────────────────────────────────────────

class _WideTable extends StatelessWidget {
  final List<ProductInfo> products;
  final Map<String, CategoryInfo> catById;
  final void Function(ProductInfo) onViewVariants;
  final void Function(ProductInfo) onAssortment;
  final void Function(ProductInfo) onImage;
  final void Function(ProductInfo) onDelist;
  final void Function(ProductInfo, String) onLifecycle;

  const _WideTable({
    required this.products,
    required this.catById,
    required this.onViewVariants,
    required this.onAssortment,
    required this.onImage,
    required this.onDelist,
    required this.onLifecycle,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      // The page gutter, so the table's edges line up with the title and filters.
      padding: EdgeInsets.symmetric(horizontal: context.pageGutter),
      child: Card(
        clipBehavior: Clip.antiAlias,
        // Scrolls both ways inside the card, so every row (including the ones
        // Load more adds) and every column can be reached.
        child: ScrollableTable(
          child: DataTable(
            headingRowColor: WidgetStatePropertyAll(cs.surfaceContainerHigh),
            columnSpacing: 24,
            // Rows grow with their text (two lines per product, larger text
            // sizes) instead of clipping at the default 48.
            dataRowMaxHeight: double.infinity,
            columns: const [
              DataColumn(label: Text('Product')),
              DataColumn(label: Text('Category')),
              DataColumn(label: Text('Online')),
              DataColumn(label: Text('POS')),
              DataColumn(label: Text('Status')),
              DataColumn(label: Text('')),
            ],
            rows: products.map((p) {
              final catName = p.categoryId != null
                  ? (catById[p.categoryId]?.name ?? '—')
                  : '—';
              return DataRow(cells: [
                // Capped, so one long name wraps instead of widening the
                // column until the whole table scrolls sideways on desktop.
                DataCell(ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 280),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(p.name,
                          style: const TextStyle(fontWeight: FontWeight.bold)),
                      if (p.description != null && p.description!.isNotEmpty)
                        Text(
                          p.description!.length > 40
                              ? '${p.description!.substring(0, 40)}…'
                              : p.description!,
                          style: TextStyle(fontSize: 11, color: cs.outline),
                        ),
                    ],
                  ),
                )),
                DataCell(Text(catName,
                    style: TextStyle(fontSize: 12, color: cs.outline))),
                // Not sold here: onSurfaceVariant, which reads in both themes
                // (outlineVariant is a divider colour, under 3:1).
                DataCell(Icon(
                  p.sellableOnline
                      ? Icons.check_circle_outline
                      : Icons.remove_circle_outline,
                  size: 18,
                  color: p.sellableOnline
                      ? context.status.success
                      : cs.onSurfaceVariant,
                )),
                DataCell(Icon(
                  p.sellablePos
                      ? Icons.check_circle_outline
                      : Icons.remove_circle_outline,
                  size: 18,
                  color: p.sellablePos
                      ? context.status.success
                      : cs.onSurfaceVariant,
                )),
                DataCell(_LifecycleBadge(status: p.status)),
                DataCell(_ProductActions(
                  p: p,
                  onViewVariants: onViewVariants,
                  onAssortment: onAssortment,
                  onImage: onImage,
                  onDelist: onDelist,
                  onLifecycle: onLifecycle,
                )),
              ]);
            }).toList(),
          ),
        ),
      ),
    );
  }
}

// ── Narrow list ───────────────────────────────────────────────────────────────

/// The actions on a product row — the same menu on the wide table and the
/// narrow list: its variants, stores, image and safety information, the one
/// lifecycle move it can make next, and delisting until it is delisted.
class _ProductActions extends StatelessWidget {
  final ProductInfo p;
  final void Function(ProductInfo) onViewVariants;
  final void Function(ProductInfo) onAssortment;
  final void Function(ProductInfo) onImage;
  final void Function(ProductInfo) onDelist;
  final void Function(ProductInfo, String) onLifecycle;

  const _ProductActions({
    required this.p,
    required this.onViewVariants,
    required this.onAssortment,
    required this.onImage,
    required this.onDelist,
    required this.onLifecycle,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return PopupMenuButton<String>(
      icon: const Icon(Icons.more_vert),
      tooltip: 'Actions',
      itemBuilder: (_) => [
        const PopupMenuItem(
            value: 'variants',
            child: Row(children: [
              Icon(Icons.view_list_outlined, size: 18),
              SizedBox(width: 8),
              Text('View Variants'),
            ])),
        const PopupMenuItem(
            value: 'stores',
            child: Row(children: [
              Icon(Icons.storefront_outlined, size: 18),
              SizedBox(width: 8),
              Text('Sold at stores'),
            ])),
        const PopupMenuItem(
            value: 'image',
            child: Row(children: [
              Icon(Icons.image_outlined, size: 18),
              SizedBox(width: 8),
              Text('Product image'),
            ])),
        const PopupMenuItem(
            value: 'safety',
            child: Row(children: [
              Icon(Icons.health_and_safety_outlined, size: 18),
              SizedBox(width: 8),
              Text('Safety information'),
            ])),
        if (nextLifecycleMove(p.status) != null)
          PopupMenuItem(
              value: 'lifecycle:${nextLifecycleMove(p.status)!.$1}',
              child: Row(children: [
                const Icon(Icons.swap_horiz, size: 18),
                const SizedBox(width: 8),
                Text(nextLifecycleMove(p.status)!.$2),
              ])),
        if (p.status.toUpperCase() != 'DELISTED')
          PopupMenuItem(
              value: 'delist',
              child: Row(children: [
                Icon(Icons.block_outlined,
                    size: 18, color: cs.error),
                const SizedBox(width: 8),
                Text('Delist',
                    style: TextStyle(color: cs.error)),
              ])),
      ],
      onSelected: (v) {
        if (v == 'variants') {
          onViewVariants(p);
        } else if (v == 'stores') {
          onAssortment(p);
        } else if (v == 'image') {
          onImage(p);
        } else if (v == 'safety') {
          showProductSafetyDialog(context,
              productId: p.id, productName: p.name);
        } else if (v.startsWith('lifecycle:')) {
          onLifecycle(p, v.substring('lifecycle:'.length));
        } else {
          onDelist(p);
        }
      },
    );
  }
}

class _NarrowList extends StatelessWidget {
  final List<ProductInfo> products;
  final Map<String, CategoryInfo> catById;

  /// Load more (or its spinner) after the last row; null once all are loaded.
  final Widget? footer;
  final Future<void> Function() onRefresh;
  final void Function(ProductInfo) onViewVariants;
  final void Function(ProductInfo) onAssortment;
  final void Function(ProductInfo) onImage;
  final void Function(ProductInfo) onDelist;
  final void Function(ProductInfo, String) onLifecycle;

  const _NarrowList({
    required this.products,
    required this.catById,
    required this.footer,
    required this.onRefresh,
    required this.onViewVariants,
    required this.onAssortment,
    required this.onImage,
    required this.onDelist,
    required this.onLifecycle,
  });

  @override
  Widget build(BuildContext context) {
    final gutter = context.pageGutter;
    return RefreshIndicator.adaptive(
      onRefresh: onRefresh,
      child: ListView.separated(
        // Scrollable even when short, so a pull always refreshes.
        physics: const AlwaysScrollableScrollPhysics(),
        // The bottom inset keeps the New Product button clear of the last row
        // and of Load more.
        padding: EdgeInsetsDirectional.fromSTEB(
            gutter, AppSpacing.sm, gutter, AppSpacing.fabClearance),
        itemCount: products.length + (footer == null ? 0 : 1),
        separatorBuilder: (_, _) => const SizedBox(height: 4),
        itemBuilder: (context, i) {
          if (i == products.length) {
            return Padding(
              padding: const EdgeInsets.all(AppSpacing.md),
              child: Center(child: footer),
            );
          }
          final p = products[i];
          final cs = Theme.of(context).colorScheme;
          final catName =
              p.categoryId != null ? (catById[p.categoryId]?.name ?? '—') : '—';
          return Card(
            child: ListTile(
              leading: CircleAvatar(
                backgroundColor: cs.primaryContainer,
                child: Text(
                  p.name.isNotEmpty ? p.name[0].toUpperCase() : '?',
                  style: TextStyle(
                      fontWeight: FontWeight.bold,
                      color: cs.onPrimaryContainer),
                ),
              ),
              title: Text(p.name,
                  style: const TextStyle(fontWeight: FontWeight.bold)),
              // The state sits under the name rather than beside it, so the
              // name keeps most of a phone's width.
              subtitle: Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.xs,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Text(catName,
                      style: TextStyle(fontSize: 12, color: cs.outline)),
                  _LifecycleBadge(status: p.status),
                ],
              ),
              trailing: _ProductActions(
                p: p,
                onViewVariants: onViewVariants,
                onAssortment: onAssortment,
                onImage: onImage,
                onDelist: onDelist,
                onLifecycle: onLifecycle,
              ),
            ),
          );
        },
      ),
    );
  }
}

// ── Lifecycle badge ───────────────────────────────────────────────────────────

/// A product's lifecycle state in words, each state in its own tone: a new
/// line waits to launch (info), a line on sale is good (success), one being
/// run down needs a look (warning), and a delisted one is closed (neutral).
class _LifecycleBadge extends StatelessWidget {
  final String status;
  const _LifecycleBadge({required this.status});

  @override
  Widget build(BuildContext context) {
    final tone = switch (status.toUpperCase()) {
      'NEW_LINE' => StatusTone.info,
      'ACTIVE' => StatusTone.success,
      'DISCONTINUED' => StatusTone.warning,
      'DELISTED' => StatusTone.neutral,
      _ => null,
    };
    // A state this screen does not know yet still reads as words.
    return StatusBadge(
      tone == null ? humanizeCode(status) : lifecycleLabelOf(status),
      tone: tone ?? StatusTone.neutral,
    );
  }
}

// ── Create product dialog ─────────────────────────────────────────────────────

class _ProductDialog extends StatefulWidget {
  final List<CategoryInfo> cats;
  final Future<void> Function(Map<String, dynamic> data) onSave;

  const _ProductDialog({required this.cats, required this.onSave});

  @override
  State<_ProductDialog> createState() => _ProductDialogState();
}

class _ProductDialogState extends State<_ProductDialog> {
  final _formKey = GlobalKey<FormState>();
  final _nameCtrl = TextEditingController();
  final _descCtrl = TextEditingController();
  String? _categoryId;
  bool _online = true;
  bool _pos = true;

  /// Listed now, on sale later (item lifecycle): hidden from the shop and
  /// refused at the till until launched, with the day it is meant to launch.
  bool _newLine = false;
  DateTime? _launchOn;
  final _safety = SafetyInformationForm();
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _nameCtrl.dispose();
    _descCtrl.dispose();
    _safety.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final activeCats =
        widget.cats.where((c) => c.status.toUpperCase() == 'ACTIVE').toList();

    return Dialog(
      shape: const RoundedRectangleBorder(borderRadius: AppRadius.card),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 480),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Form(
            key: _formKey,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text('New Product',
                      style: Theme.of(context)
                          .textTheme
                          .titleLarge
                          ?.copyWith(fontWeight: FontWeight.bold)),
                  const SizedBox(height: 20),
                  if (_error != null) ...[
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: cs.errorContainer,
                        borderRadius: AppRadius.chip,
                      ),
                      child: Text(_error!,
                          style: TextStyle(color: cs.onErrorContainer)),
                    ),
                    const SizedBox(height: 12),
                  ],
                  TextFormField(
                    controller: _nameCtrl,
                    autofocus: true,
                    decoration: const InputDecoration(
                      labelText: 'Product name *',
                      prefixIcon: Icon(Icons.inventory_2_outlined),
                    ),
                    validator: (v) =>
                        v == null || v.trim().isEmpty ? 'Required' : null,
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _descCtrl,
                    maxLines: 2,
                    decoration: const InputDecoration(
                      labelText: 'Description',
                      alignLabelWithHint: true,
                    ),
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String?>(
                    initialValue: _categoryId,
                    decoration:
                        const InputDecoration(labelText: 'Category'),
                    items: [
                      const DropdownMenuItem(
                          value: null, child: Text('— None —')),
                      ...activeCats.map((c) =>
                          DropdownMenuItem(value: c.id, child: Text(c.name))),
                    ],
                    onChanged: (v) => setState(() => _categoryId = v),
                  ),
                  const SizedBox(height: 16),
                  Row(
                    children: [
                      Expanded(
                        child: CheckboxListTile(
                          title: const Text('Online'),
                          value: _online,
                          onChanged: (v) =>
                              setState(() => _online = v ?? true),
                          contentPadding: EdgeInsets.zero,
                          controlAffinity: ListTileControlAffinity.leading,
                        ),
                      ),
                      Expanded(
                        child: CheckboxListTile(
                          title: const Text('POS'),
                          value: _pos,
                          onChanged: (v) =>
                              setState(() => _pos = v ?? true),
                          contentPadding: EdgeInsets.zero,
                          controlAffinity: ListTileControlAffinity.leading,
                        ),
                      ),
                    ],
                  ),
                  CheckboxListTile(
                    key: const Key('product-new-line'),
                    contentPadding: EdgeInsets.zero,
                    title: const Text('New line — not on sale yet'),
                    subtitle: Text(_newLine
                        ? (_launchOn == null
                            ? 'Hidden from the shop and refused at the till until launched.'
                            : 'Goes on sale ${AppFormat.date(_launchOn!.toIso8601String())} — launch it that day.')
                        : 'On sale as soon as it is priced and stocked.'),
                    value: _newLine,
                    onChanged: (v) => setState(() => _newLine = v ?? false),
                  ),
                  if (_newLine)
                    Align(
                      alignment: Alignment.centerLeft,
                      child: TextButton.icon(
                        key: const Key('product-launch-on'),
                        onPressed: () async {
                          final d = await showDatePicker(
                            context: context,
                            firstDate: DateTime.now(),
                            lastDate: DateTime.now().add(const Duration(days: 730)),
                            initialDate: _launchOn ?? DateTime.now(),
                          );
                          if (d != null) setState(() => _launchOn = d);
                        },
                        icon: const Icon(Icons.event),
                        label: Text(_launchOn == null
                            ? 'Launch day'
                            : 'Launch day: ${AppFormat.date(_launchOn!.toIso8601String())}'),
                      ),
                    ),
                  ExpansionTile(
                    key: const Key('new-product-safety'),
                    tilePadding: EdgeInsets.zero,
                    title: const Text('Safety information'),
                    subtitle: const Text(
                        'Needed to offer it online where EU product safety law applies'),
                    children: [SafetyInformationFields(form: _safety)],
                  ),
                  const SizedBox(height: 20),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      TextButton(
                        onPressed:
                            _loading ? null : () => Navigator.pop(context),
                        child: const Text('Cancel'),
                      ),
                      const SizedBox(width: 8),
                      FilledButton(
                        onPressed: _loading ? null : _submit,
                        child: _loading
                            ?  SizedBox(
                                height: 18,
                                width: 18,
                                child: CircularProgressIndicator(
                                    strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                            : const Text('Create'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await widget.onSave({
        'name': _nameCtrl.text.trim(),
        'description':
            _descCtrl.text.trim().isEmpty ? null : _descCtrl.text.trim(),
        'categoryId': _categoryId,
        'sellableOnline': _online,
        'sellablePos': _pos,
        if (_newLine) 'status': 'NEW_LINE',
        if (_newLine && _launchOn != null)
          'launchOn': _launchOn!.toIso8601String().substring(0, 10),
        if (!_safety.isEmpty) 'safetyInformation': _safety.toJson(),
      });
      if (mounted) Navigator.pop(context);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not save product.');
      });
    }
  }
}

// ── Variants dialog ───────────────────────────────────────────────────────────

class _VariantsDialog extends ConsumerStatefulWidget {
  final ProductInfo product;
  const _VariantsDialog({required this.product});

  @override
  ConsumerState<_VariantsDialog> createState() => _VariantsDialogState();
}

class _VariantsDialogState extends ConsumerState<_VariantsDialog> {
  bool _showAddForm = false;
  final _skuCtrl = TextEditingController();
  final _barcodeCtrl = TextEditingController();
  final _unitCtrl = TextEditingController();
  bool _saving = false;
  String? _saveError;

  @override
  void dispose() {
    _skuCtrl.dispose();
    _barcodeCtrl.dispose();
    _unitCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final variantsAsync =
        ref.watch(productVariantsProvider(widget.product.id));
    final pricesAsync = ref.watch(variantPricesProvider);
    final currency = ref.watch(tenantInfoProvider).value?.currency;
    final compact = context.isCompact;
    final cs = Theme.of(context).colorScheme;

    return Dialog(
      shape: const RoundedRectangleBorder(borderRadius: AppRadius.card),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560, maxHeight: 600),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(widget.product.name,
                            style: Theme.of(context)
                                .textTheme
                                .titleLarge
                                ?.copyWith(fontWeight: FontWeight.bold)),
                        Text('Variants',
                            style: TextStyle(
                                color: cs.outline, fontSize: 13)),
                      ],
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close),
                    tooltip: 'Close',
                    onPressed: () => Navigator.pop(context),
                  ),
                ],
              ),
              const SizedBox(height: 16),

              // Variants list
              Expanded(
                child: variantsAsync.when(
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (e, _) => Center(
                      child: Text(friendlyError(e,
                          fallback: 'Could not load variants.'))),
                  data: (variants) {
                    if (variants.isEmpty && !_showAddForm) {
                      return Center(
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.label_outline,
                                size: 48, color: cs.outlineVariant),
                            const SizedBox(height: 12),
                            const Text('No variants yet'),
                            const SizedBox(height: 12),
                            FilledButton.icon(
                              onPressed: () =>
                                  setState(() => _showAddForm = true),
                              icon: const Icon(Icons.add),
                              label: const Text('Add Variant'),
                            ),
                          ],
                        ),
                      );
                    }
                    return ListView(
                      children: [
                        ...variants.map((v) {
                          final price = pricesAsync.value?[v.id];
                          // The default price list is in the business's own
                          // currency.
                          final priceText = Text(
                            price != null
                                ? AppFormat.money(price, currencyCode: currency)
                                : 'No price',
                            style: TextStyle(
                              fontWeight: FontWeight.bold,
                              color: price != null ? cs.primary : cs.outline,
                            ),
                          );
                          void compliance() => showDialog<bool>(
                                context: context,
                                builder: (_) =>
                                    VariantComplianceDialog(variant: v),
                              );
                          return Card(
                            child: ListTile(
                              leading: const Icon(Icons.label_outline),
                              title: Text(v.sku,
                                  style: const TextStyle(
                                      fontFamily: 'monospace',
                                      fontWeight: FontWeight.bold)),
                              subtitle: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Row(
                                    children: [
                                      // Active or Inactive, in words.
                                      StatusBadge(
                                        humanizeCode(v.status),
                                        tone: v.status.toUpperCase() == 'ACTIVE'
                                            ? StatusTone.success
                                            : StatusTone.neutral,
                                      ),
                                      const SizedBox(width: 8),
                                      Expanded(
                                        child: Text(
                                          [
                                            if (v.barcode != null)
                                              'EAN: ${v.barcode}',
                                            if (v.unit != null) v.unit!,
                                          ].join('  ·  '),
                                          overflow: TextOverflow.ellipsis,
                                          style: const TextStyle(fontSize: 12),
                                        ),
                                      ),
                                    ],
                                  ),
                                  // On a phone the price goes under the SKU and
                                  // the two actions into one menu, so the SKU
                                  // keeps the width.
                                  if (compact) priceText,
                                ],
                              ),
                              trailing: compact
                                  ? PopupMenuButton<String>(
                                      tooltip: 'Price, allergens and origin',
                                      onSelected: (a) => a == 'price'
                                          ? _setPrice(v, price)
                                          : compliance(),
                                      itemBuilder: (_) => const [
                                        PopupMenuItem(
                                            value: 'price',
                                            child: Text('Set price')),
                                        PopupMenuItem(
                                            value: 'compliance',
                                            child:
                                                Text('Allergens and origin')),
                                      ],
                                    )
                                  : Row(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        priceText,
                                        IconButton(
                                          icon: const Icon(
                                              Icons.price_change_outlined),
                                          tooltip: 'Set price',
                                          onPressed: () => _setPrice(v, price),
                                        ),
                                        IconButton(
                                          icon:
                                              const Icon(Icons.no_food_outlined),
                                          tooltip: 'Allergens and origin',
                                          onPressed: compliance,
                                        ),
                                      ],
                                    ),
                            ),
                          );
                        }),
                        if (!_showAddForm)
                          Center(
                            child: Padding(
                              padding:
                                  const EdgeInsets.symmetric(vertical: 8),
                              child: OutlinedButton.icon(
                                onPressed: () =>
                                    setState(() => _showAddForm = true),
                                icon: const Icon(Icons.add),
                                label: const Text('Add Variant'),
                              ),
                            ),
                          ),
                      ],
                    );
                  },
                ),
              ),

              // Add variant inline form
              if (_showAddForm) ...[
                const Divider(),
                const SizedBox(height: 8),
                Text('Add Variant',
                    style: Theme.of(context)
                        .textTheme
                        .titleSmall
                        ?.copyWith(fontWeight: FontWeight.bold)),
                const SizedBox(height: 12),
                if (_saveError != null) ...[
                  Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: cs.errorContainer,
                      borderRadius: AppRadius.chip,
                    ),
                    child: Text(_saveError!,
                        style:
                            TextStyle(color: cs.onErrorContainer, fontSize: 12)),
                  ),
                  const SizedBox(height: 8),
                ],
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _skuCtrl,
                        decoration: const InputDecoration(
                          labelText: 'SKU *',
                          isDense: true,
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextField(
                        controller: _barcodeCtrl,
                        decoration: InputDecoration(
                          labelText: 'Barcode',
                          isDense: true,
                          suffixIcon: IconButton(
                            icon: const Icon(Icons.camera_alt_outlined,
                                size: 18),
                            tooltip: 'Scan barcode',
                            onPressed: () async {
                              final code = await scanBarcodeWithCamera(context);
                              if (code != null && code.isNotEmpty) {
                                _barcodeCtrl.text = code;
                              }
                            },
                          ),
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    SizedBox(
                      width: 80,
                      child: TextField(
                        controller: _unitCtrl,
                        decoration: const InputDecoration(
                          labelText: 'Unit',
                          isDense: true,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed: _saving
                          ? null
                          : () => setState(() {
                                _showAddForm = false;
                                _saveError = null;
                              }),
                      child: const Text('Cancel'),
                    ),
                    const SizedBox(width: 8),
                    FilledButton(
                      onPressed: _saving ? null : _saveVariant,
                      child: _saving
                          ?  SizedBox(
                              height: 16,
                              width: 16,
                              child: CircularProgressIndicator(
                                  strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                          : const Text('Add'),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _setPrice(VariantInfo v, double? current) async {
    final ctrl =
        TextEditingController(text: current != null ? current.toStringAsFixed(2) : '');
    String? error;
    bool saving = false;
    await showDialog<void>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setLocal) => AlertDialog(
          title: Text('Set price — ${v.sku}'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (error != null) ...[
                Text(error!,
                    style: TextStyle(color: Theme.of(ctx).colorScheme.error)),
                const SizedBox(height: 8),
              ],
              TextField(
                controller: ctrl,
                autofocus: true,
                keyboardType:
                    const TextInputType.numberWithOptions(decimal: true),
                decoration: const InputDecoration(
                  labelText: 'Selling price',
                  helperText: 'Used on the online store and POS',
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: saving ? null : () => Navigator.pop(ctx),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: saving
                  ? null
                  : () async {
                      final price = double.tryParse(ctrl.text.trim());
                      if (price == null || price <= 0) {
                        setLocal(() => error = 'Enter a price greater than 0');
                        return;
                      }
                      setLocal(() {
                        saving = true;
                        error = null;
                      });
                      try {
                        final listId =
                            await ref.read(defaultPriceListProvider.future);
                        await ref.read(apiClientProvider).dio.post(
                          '/${ApiConstants.pricing}/admin/price-lists/$listId/items',
                          data: {
                            'variantId': v.id,
                            'price': price,
                            'minQty': 1,
                          },
                        );
                        ref.invalidate(variantPricesProvider);
                        if (ctx.mounted) Navigator.pop(ctx);
                      } catch (e) {
                        setLocal(() {
                          saving = false;
                          error = friendlyError(e,
                              fallback: 'Could not save price.');
                        });
                      }
                    },
              child: saving
                  ?  SizedBox(
                      height: 16,
                      width: 16,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                  : const Text('Save'),
            ),
          ],
        ),
      ),
    );
    ctrl.dispose();
  }

  Future<void> _saveVariant() async {
    if (_skuCtrl.text.trim().isEmpty) {
      setState(() => _saveError = 'SKU is required');
      return;
    }
    setState(() {
      _saving = true;
      _saveError = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.product}/admin/products/${widget.product.id}/variants',
        data: {
          'sku': _skuCtrl.text.trim(),
          if (_barcodeCtrl.text.trim().isNotEmpty)
            'barcode': _barcodeCtrl.text.trim(),
          if (_unitCtrl.text.trim().isNotEmpty)
            'unit': _unitCtrl.text.trim(),
        },
      );
      ref.invalidate(productVariantsProvider(widget.product.id));
      _skuCtrl.clear();
      _barcodeCtrl.clear();
      _unitCtrl.clear();
      setState(() {
        _saving = false;
        _showAddForm = false;
      });
    } catch (e) {
      setState(() {
        _saving = false;
        _saveError = friendlyError(e, fallback: 'Could not save variant.');
      });
    }
  }
}

// ── Per-store assortment dialog ─────────────────────────────────────────────────

class _AssortmentDialog extends ConsumerStatefulWidget {
  final ProductInfo product;
  const _AssortmentDialog({required this.product});

  @override
  ConsumerState<_AssortmentDialog> createState() => _AssortmentDialogState();
}

class _AssortmentDialogState extends ConsumerState<_AssortmentDialog> {
  final Set<String> _selected = {};
  bool _allStores = true;
  bool _loaded = false;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    ref.read(productStoresProvider(widget.product.id).future).then((ids) {
      if (!mounted) return;
      setState(() {
        _selected
          ..clear()
          ..addAll(ids);
        _allStores = ids.isEmpty;
        _loaded = true;
      });
    }).catchError((_) {
      if (mounted) setState(() => _loaded = true);
    });
  }

  Future<void> _save() async {
    setState(() {
      _saving = true;
      _error = null;
    });
    final ids = _allStores ? <String>[] : _selected.toList();
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.product}/admin/products/${widget.product.id}/stores',
        data: {'storeIds': ids},
      );
      ref.invalidate(productStoresProvider(widget.product.id));
      if (!mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_allStores
              ? '${widget.product.name} is sold at all stores.'
              : '${widget.product.name} updated for ${ids.length} store(s).'),
        ),
      );
    } catch (e) {
      setState(() {
        _saving = false;
        _error = friendlyError(e, fallback: 'Could not save.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);
    return AlertDialog(
      title: Text('Sold at — ${widget.product.name}'),
      content: SizedBox(
        width: 420,
        child: !_loaded
            ? const SizedBox(
                height: 120, child: Center(child: CircularProgressIndicator()))
            : Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (_error != null) ...[
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: cs.errorContainer,
                        borderRadius: AppRadius.chip,
                      ),
                      child: Text(_error!,
                          style: TextStyle(color: cs.onErrorContainer)),
                    ),
                    const SizedBox(height: 12),
                  ],
                  SwitchListTile.adaptive(
                    contentPadding: EdgeInsets.zero,
                    value: _allStores,
                    onChanged: (v) => setState(() => _allStores = v),
                    title: const Text('Sell at all stores'),
                    subtitle: Text(
                      _allStores
                          ? 'Visible in every store (including new ones).'
                          : 'Choose the specific stores that carry this product.',
                      style: TextStyle(color: cs.outline, fontSize: 12),
                    ),
                  ),
                  if (!_allStores) ...[
                    const Divider(),
                    Flexible(
                      child: storesAsync.when(
                        loading: () => const Padding(
                          padding: EdgeInsets.all(16),
                          child: Center(child: CircularProgressIndicator()),
                        ),
                        error: (e, _) => Text(
                            friendlyError(e,
                                fallback: 'Could not load stores.'),
                            style: TextStyle(color: cs.error)),
                        data: (stores) => SingleChildScrollView(
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              for (final s in stores)
                                CheckboxListTile(
                                  contentPadding: EdgeInsets.zero,
                                  dense: true,
                                  controlAffinity:
                                      ListTileControlAffinity.leading,
                                  value: _selected.contains(s.id),
                                  onChanged: (v) => setState(() {
                                    if (v == true) {
                                      _selected.add(s.id);
                                    } else {
                                      _selected.remove(s.id);
                                    }
                                  }),
                                  title: Text(s.name),
                                  subtitle: Text(s.code,
                                      style: TextStyle(
                                          fontSize: 11, color: cs.outline)),
                                ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ],
                ],
              ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: (_saving || !_loaded) ? null : _save,
          child: _saving
              ?  SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
              : const Text('Save'),
        ),
      ],
    );
  }
}
