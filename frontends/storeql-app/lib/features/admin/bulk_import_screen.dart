import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import 'providers/admin_providers.dart';

// ── Supplier Catalogue CSV import ─────────────────────────────────────────────
//
// Accepts the GTBJ catalogue format:
//   Product ID, Category, Product Description, Quantity Type, Case Size, Price,
//   Quantity, Favourite[, Store]
//
// One POST to product-svc creates categories, products, stock receipts and prices
// all server-side. The client only uploads the CSV and picks a destination store.

class BulkImportScreen extends ConsumerStatefulWidget {
  const BulkImportScreen({super.key});

  @override
  ConsumerState<BulkImportScreen> createState() => _BulkImportScreenState();
}

class _BulkImportScreenState extends ConsumerState<BulkImportScreen> {
  String? _fileName;
  String? _csvContent;
  String _mode = 'ADD';

  // Parsed preview stats (computed after file pick)
  int _rowCount = 0;
  int _productCount = 0;
  Set<String> _categoryNames = {};
  Set<String> _storeNamesInCsv = {};
  bool _hasQtyColumn = false;
  bool _hasPriceColumn = false;

  // Per-store-name override selected by the user (store name → store UUID)
  final Map<String, String?> _storeMapping = {};
  // Single destination store for stock receipts (required whenever the CSV has a
  // Quantity column) — a CSV row's own Store column scopes product sellability, which
  // is a different concept from "which store's shelf this delivery is going onto".
  String? _destinationStoreId;

  bool _loading = false;
  String? _error;
  Map<String, dynamic>? _result;

  // ── CSV parsing ─────────────────────────────────────────────────────────────

  List<String> _splitRow(String line) {
    final out = <String>[];
    final sb = StringBuffer();
    bool inQ = false;
    int pos = 0;
    while (pos < line.length) {
      final ch = line[pos];
      if (ch == '"') {
        if (inQ && pos + 1 < line.length && line[pos + 1] == '"') {
          sb.write('"');
          pos += 2;
        } else {
          inQ = !inQ;
          pos++;
        }
      } else if (ch == ',' && !inQ) {
        out.add(sb.toString().trim());
        sb.clear();
        pos++;
      } else {
        sb.write(ch);
        pos++;
      }
    }
    out.add(sb.toString().trim());
    return out;
  }

  int _headerIdx(List<String> headers, List<String> names) {
    for (final n in names) {
      final i = headers.indexWhere((h) => h.toLowerCase() == n.toLowerCase());
      if (i >= 0) return i;
    }
    return -1;
  }

  void _parseCsv(String csv) {
    final lines =
        csv.split(RegExp(r'\r?\n')).where((l) => l.trim().isNotEmpty).toList();
    if (lines.length < 2) {
      setState(() => _error = 'File must have a header row and at least one data row.');
      return;
    }
    // _headerIdx returns the first match — duplicate column names use the first occurrence.
    // Same header names and matching order as ProductService.parseSupplierCsvToRequest, so a
    // row's parsed sku/qty/price here lines up with the variant the backend actually creates.
    final headers = _splitRow(lines[0]);
    final idxDesc = _headerIdx(headers, [
      'product description', 'description', 'product name', 'name',
    ]);
    final idxCat = _headerIdx(headers, ['category']);
    final idxStore = _headerIdx(headers, ['store', 'store name', 'store_name']);
    final idxQty = _headerIdx(headers, ['quantity', 'qty']);
    final idxPrice = _headerIdx(headers, ['price']);

    if (idxDesc < 0) {
      setState(() => _error =
          'Could not find a product name column. '
          'Expected: "Product Description" (or "Description" / "Name").');
      return;
    }

    final products = <String>{};
    final cats = <String>{};
    final storeNames = <String>{};
    int rows = 0;

    for (var i = 1; i < lines.length; i++) {
      final cols = _splitRow(lines[i]);
      final desc = idxDesc < cols.length ? cols[idxDesc] : '';
      if (desc.isEmpty) continue;
      rows++;
      products.add(desc);
      if (idxCat >= 0 && idxCat < cols.length && cols[idxCat].isNotEmpty) {
        cats.add(cols[idxCat]);
      }
      if (idxStore >= 0 &&
          idxStore < cols.length &&
          cols[idxStore].isNotEmpty) {
        storeNames.add(cols[idxStore]);
      }
    }

    setState(() {
      _rowCount = rows;
      _hasQtyColumn = idxQty >= 0;
      _hasPriceColumn = idxPrice >= 0;
      _productCount = products.length;
      _categoryNames = cats;
      _storeNamesInCsv = storeNames;
      _error = null;
      _result = null;
      for (final sn in storeNames) {
        _storeMapping.putIfAbsent(sn, () => null);
      }
    });
  }

  // ── File pick ────────────────────────────────────────────────────────────────

  Future<void> _pickFile() async {
    final r = await FilePicker.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['csv'],
      withData: true,
    );
    if (r == null || r.files.isEmpty) return;
    final f = r.files.first;
    if (f.bytes == null) return;
    final csv = utf8.decode(f.bytes!);
    setState(() {
      _fileName = f.name;
      _csvContent = csv;
    });
    _parseCsv(csv);
  }

  // ── Import ───────────────────────────────────────────────────────────────────

  Future<void> _import(List<StoreInfo> stores) async {
    if (_csvContent == null) return;
    if (_hasQtyColumn &&
        (_destinationStoreId == null || _destinationStoreId!.isEmpty)) {
      setState(() => _error =
          'This file has a Quantity column — select a destination store to '
          'receive that stock into before importing.');
      return;
    }

    // Build store-name → UUID map from the user's selections + fallback to
    // exact-name match against the loaded store list.
    final storeNameToId = <String, String>{};
    for (final sn in _storeNamesInCsv) {
      final selected = _storeMapping[sn];
      if (selected != null && selected.isNotEmpty) {
        storeNameToId[sn] = selected;
      } else {
        final match =
            stores.where((s) => s.name.toLowerCase() == sn.toLowerCase());
        if (match.isNotEmpty) storeNameToId[sn] = match.first.id;
      }
    }

    setState(() {
      _loading = true;
      _error = null;
      _result = null;
    });

    final dio = ref.read(apiClientProvider).dio;
    try {
      // Single server-side call: product-svc creates categories + products,
      // then calls inventory-svc and pricing-svc internally.
      final resp = await dio.post(
        '/${ApiConstants.product}/admin/import/supplier-csv',
        data: {
          'csv': _csvContent,
          'mode': _mode,
          if (storeNameToId.isNotEmpty) 'storeNameToId': storeNameToId,
          if (_destinationStoreId != null) 'storeId': _destinationStoreId,
        },
        options: Options(receiveTimeout: const Duration(minutes: 10)),
      );

      if (!mounted) return;
      ref.invalidate(productsProvider);
      ref.invalidate(categoriesProvider);
      ref.invalidate(variantPricesProvider);

      final result = (resp.data['data'] as Map<String, dynamic>?) ?? {};
      setState(() {
        _loading = false;
        _result = result;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Import failed.');
      });
    }
  }

  // ── UI ───────────────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);

    // The page's gutter, and a width the form reads at, centred: a
    // ConstrainedBox in a scroll view was handed the full width and did
    // nothing on a desktop.
    return SingleChildScrollView(
      padding: context.pagePadding,
      child: ContentBounds(
        maxWidth: 760,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // ── Header ──────────────────────────────────────────────────────
            Text('Supplier Catalogue Import',
                style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 4),
            Text(
              'Upload your supplier catalogue CSV. '
              'Categories, products, and store availability are all created in one step.',
              style: TextStyle(color: cs.outline, fontSize: 13),
            ),
            const SizedBox(height: 20),

            // ── Mode + upload row ─────────────────────────────────────────
            Wrap(
              spacing: 16,
              runSpacing: 12,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                SegmentedButton<String>(
                  segments: const [
                    ButtonSegment(
                        value: 'ADD',
                        icon: Icon(Icons.add),
                        label: Text('New import')),
                    ButtonSegment(
                        value: 'REPLACE',
                        icon: Icon(Icons.sync),
                        label: Text('Override existing')),
                  ],
                  selected: {_mode},
                  onSelectionChanged: (s) =>
                      setState(() => _mode = s.first),
                ),
                FilledButton.icon(
                  onPressed: _pickFile,
                  icon: const Icon(Icons.upload_file),
                  label: Text(
                      _fileName == null ? 'Choose CSV file' : 'Replace file'),
                ),
                TextButton.icon(
                  onPressed: () => _showFormatHelp(context),
                  icon: const Icon(Icons.help_outline, size: 18),
                  label: const Text('Expected format'),
                ),
                if (_fileName != null)
                  Text(_fileName!, style: TextStyle(color: cs.outline)),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              _mode == 'REPLACE'
                  ? 'Override mode: re-imports a sheet, replacing existing products by SKU.'
                  : 'New import mode: creates products and categories. Duplicate SKUs are reported as errors.',
              style: TextStyle(color: cs.outline, fontSize: 12),
            ),

            // ── Error / result banners ─────────────────────────────────────
            if (_error != null) ...[
              const SizedBox(height: 16),
              _ErrorBanner(
                  message: _error!, onDismiss: () => setState(() => _error = null)),
            ],
            if (_result != null) ...[
              const SizedBox(height: 16),
              _ResultBanner(
                  result: _result!, onDismiss: () => setState(() => _result = null)),
            ],

            // ── Preview + store mapping ────────────────────────────────────
            if (_csvContent != null && _error == null) ...[
              const SizedBox(height: 20),
              _PreviewCard(
                rowCount: _rowCount,
                productCount: _productCount,
                categoryNames: _categoryNames,
                storeNamesInCsv: _storeNamesInCsv,
              ),

              // Store-name mapping (only if CSV has a Store column)
              if (_storeNamesInCsv.isNotEmpty) ...[
                const SizedBox(height: 16),
                storesAsync.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (_, _) =>
                      const Text('Could not load stores — store mapping skipped.'),
                  data: (stores) => _StoreMappingCard(
                    storeNamesInCsv: _storeNamesInCsv,
                    stores: stores,
                    mapping: _storeMapping,
                    onChanged: (name, id) =>
                        setState(() => _storeMapping[name] = id),
                  ),
                ),
              ],

              // Destination store for stock receipt (only if CSV has a Quantity column).
              if (_hasQtyColumn) ...[
                const SizedBox(height: 16),
                storesAsync.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (_, _) => const Text(
                      'Could not load stores — stock cannot be received.'),
                  data: (stores) => _DestinationStoreCard(
                    stores: stores,
                    selected: _destinationStoreId,
                    hasPriceColumn: _hasPriceColumn,
                    onChanged: (id) =>
                        setState(() => _destinationStoreId = id),
                  ),
                ),
              ],

              const SizedBox(height: 20),
              storesAsync.when(
                loading: () => const SizedBox.shrink(),
                error: (_, _) => FilledButton.icon(
                  onPressed: _loading ? null : () => _import([]),
                  icon: _loading
                      ?  SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                      : const Icon(Icons.cloud_upload_outlined),
                  label: Text(_loading ? 'Importing…' : 'Import $_productCount products'),
                ),
                data: (stores) => FilledButton.icon(
                  onPressed: _loading ? null : () => _import(stores),
                  icon: _loading
                      ?  SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(
                              strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                      : const Icon(Icons.cloud_upload_outlined),
                  label: Text(_loading ? 'Importing…' : 'Import $_productCount products'),
                ),
              ),
            ],

            // ── Empty state ────────────────────────────────────────────────
            if (_csvContent == null) ...[
              const SizedBox(height: 40),
              Center(
                child: Column(
                  children: [
                    Icon(Icons.upload_file_outlined,
                        size: 72, color: cs.outlineVariant),
                    const SizedBox(height: 12),
                    const Text('No file selected'),
                    const SizedBox(height: 4),
                    Text(
                      'Columns: Product ID · Category · Product Description · Store · Quantity · Price\n'
                      'Columns can be in any order. Only Product Description is required.',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: cs.outline, fontSize: 12),
                    ),
                  ],
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  void _showFormatHelp(BuildContext context) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('CSV format'),
        content: SizedBox(
          width: 620,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Upload a CSV file with any of these 6 columns (in any order). '
                'Only the product name column is required.\n',
              ),
              Container(
                padding: const EdgeInsets.all(12),
                color: Theme.of(ctx).colorScheme.surfaceContainerHighest,
                child: const SelectableText(
                  'Product ID, Category, Product Description, Store, Quantity, Price\n'
                  '115669521, Alcohol, Tennents Bavarian Pilsner 4x440ml, Main Store, 6, 28.86\n'
                  '114600215, Sugar & Baking, Tate & Lyle Granulated Sugar 1kg, , 15, 13.50\n'
                  '"113341477","Condiments, Sauces","Heinz Ketchup 460g", Warehouse, 12, 18.00',
                  style: TextStyle(fontFamily: 'monospace', fontSize: 12),
                ),
              ),
              const SizedBox(height: 12),
              const Text(
                'Columns (case-insensitive, any order, duplicates → first wins):\n'
                '  • Product ID → SKU (auto-generated as IMP-N if missing)\n'
                '  • Category → creates the category if it doesn\'t exist\n'
                '  • Product Description → product name (required)\n'
                '  • Store / Store Name → restricts product to that store\n'
                '  • Quantity → received as real stock at the destination store you pick\n'
                '  • Price → set as the selling price on the default price list\n\n'
                'Blank product name rows are skipped. '
                'Fields containing commas must be double-quoted. '
                'Only .csv files are supported.',
                style: TextStyle(fontSize: 12),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx), child: const Text('Close')),
        ],
      ),
    );
  }
}

// ── Preview card ──────────────────────────────────────────────────────────────

class _PreviewCard extends StatelessWidget {
  final int rowCount;
  final int productCount;
  final Set<String> categoryNames;
  final Set<String> storeNamesInCsv;

  const _PreviewCard({
    required this.rowCount,
    required this.productCount,
    required this.categoryNames,
    required this.storeNamesInCsv,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      color: cs.surfaceContainerHigh,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.check_circle_outline,
                    color: context.status.success, size: 20),
                const SizedBox(width: 8),
                Text('File parsed successfully',
                    style: TextStyle(
                        fontWeight: FontWeight.bold,
                        color: context.status.success)),
              ],
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 24,
              runSpacing: 8,
              children: [
                _Stat('$rowCount', 'rows'),
                _Stat('$productCount', 'products'),
                _Stat('${categoryNames.length}', 'categories'),
                if (storeNamesInCsv.isNotEmpty)
                  _Stat('${storeNamesInCsv.length}', 'stores in CSV'),
              ],
            ),
            if (categoryNames.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(categoryNames.take(8).join(' · ') +
                  (categoryNames.length > 8
                      ? ' + ${categoryNames.length - 8} more'
                      : ''),
                  style: TextStyle(fontSize: 12, color: cs.outline)),
            ],
          ],
        ),
      ),
    );
  }
}

class _Stat extends StatelessWidget {
  final String value;
  final String label;
  const _Stat(this.value, this.label);

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(value,
            style: const TextStyle(
                fontSize: 22, fontWeight: FontWeight.bold)),
        Text(label,
            style: TextStyle(
                fontSize: 12,
                color: Theme.of(context).colorScheme.outline)),
      ],
    );
  }
}

// ── Store-name mapping card ───────────────────────────────────────────────────

class _StoreMappingCard extends StatelessWidget {
  final Set<String> storeNamesInCsv;
  final List<StoreInfo> stores;
  final Map<String, String?> mapping;
  final void Function(String name, String? id) onChanged;

  const _StoreMappingCard({
    required this.storeNamesInCsv,
    required this.stores,
    required this.mapping,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      color: cs.surfaceContainerHigh,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.store_outlined, size: 18, color: cs.primary),
                const SizedBox(width: 6),
                Text('Store mapping',
                    style: TextStyle(
                        fontWeight: FontWeight.bold, color: cs.primary)),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              'Map store names from the CSV to stores in the system. '
              'Exact-name matches are pre-filled. Products without a store are available everywhere.',
              style: TextStyle(fontSize: 12, color: cs.outline),
            ),
            const SizedBox(height: 12),
            for (final sn in storeNamesInCsv) ...[
              Row(
                children: [
                  Expanded(
                    flex: 2,
                    child: Text(sn,
                        style: const TextStyle(fontWeight: FontWeight.w500)),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    flex: 3,
                    child: DropdownButtonFormField<String>(
                      initialValue: mapping[sn],
                      decoration: const InputDecoration(
                          isDense: true,
                          contentPadding: EdgeInsets.symmetric(
                              horizontal: 12, vertical: 10)),
                      hint: const Text('— all stores —'),
                      items: [
                        const DropdownMenuItem(
                            value: null, child: Text('— all stores —')),
                        for (final s in stores)
                          DropdownMenuItem(
                              value: s.id,
                              child: Text('${s.name} (${s.code})')),
                      ],
                      onChanged: (v) => onChanged(sn, v),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
            ],
          ],
        ),
      ),
    );
  }
}

// ── Destination store card (stock receipt) ───────────────────────────────────

class _DestinationStoreCard extends StatelessWidget {
  final List<StoreInfo> stores;
  final String? selected;
  final bool hasPriceColumn;
  final void Function(String? id) onChanged;

  const _DestinationStoreCard({
    required this.stores,
    required this.selected,
    required this.hasPriceColumn,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Card(
      color: cs.surfaceContainerHigh,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.inventory_2_outlined, size: 18, color: cs.primary),
                const SizedBox(width: 6),
                Text('Receive stock at',
                    style: TextStyle(
                        fontWeight: FontWeight.bold, color: cs.primary)),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              'This file has a Quantity column — pick the store this stock is '
              'physically going into. Every row\'s quantity is received there.'
              '${hasPriceColumn ? ' Price is set as the selling price on the default price list.' : ''}',
              style: TextStyle(fontSize: 12, color: cs.outline),
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              initialValue: selected,
              decoration: const InputDecoration(
                  isDense: true,
                  contentPadding:
                      EdgeInsets.symmetric(horizontal: 12, vertical: 10)),
              hint: const Text('Select a store…'),
              items: [
                for (final s in stores)
                  DropdownMenuItem(
                      value: s.id, child: Text('${s.name} (${s.code})')),
              ],
              onChanged: onChanged,
            ),
          ],
        ),
      ),
    );
  }
}

// ── Result banner ─────────────────────────────────────────────────────────────

class _ResultBanner extends StatelessWidget {
  final Map<String, dynamic> result;
  final VoidCallback onDismiss;
  const _ResultBanner({required this.result, required this.onDismiss});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final errors = (result['errors'] as List?)?.cast<Map<String, dynamic>>() ?? [];
    final stockErrors = (result['stockErrors'] as List?)?.cast<String>() ?? [];
    final priceErrors = (result['priceErrors'] as List?)?.cast<String>() ?? [];
    final hasErrors =
        errors.isNotEmpty || stockErrors.isNotEmpty || priceErrors.isNotEmpty;
    final stockReceived = result['stockReceived'] as int?;
    final pricesSet = result['pricesSet'] as int?;
    // Success reuses the app's existing green (secondary) role instead of a
    // one-off Colors.green, so it stays in step with the rest of the theme.
    final bg = hasErrors ? cs.errorContainer : cs.secondaryContainer;
    final fg = hasErrors ? cs.onErrorContainer : cs.onSecondaryContainer;
    return MaterialBanner(
      backgroundColor: bg,
      leading: Icon(
          hasErrors ? Icons.warning_amber_outlined : Icons.check_circle_outline,
          color: hasErrors ? cs.error : cs.secondary),
      content: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            hasErrors
                ? 'Import completed with errors'
                : 'Import completed successfully',
            style: TextStyle(
                fontWeight: FontWeight.bold,
                color: hasErrors ? cs.error : cs.secondary),
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 20,
            runSpacing: 4,
            children: [
              _ResultStat('${result['categoriesCreated'] ?? 0}',
                  'categories created'),
              _ResultStat('${result['categoriesSkipped'] ?? 0}',
                  'categories existing'),
              _ResultStat(
                  '${result['productsCreated'] ?? 0}', 'products created'),
              _ResultStat(
                  '${result['variantsCreated'] ?? 0}', 'variants created'),
              if (stockReceived != null)
                _ResultStat('$stockReceived', 'stock receipts'),
              if (pricesSet != null) _ResultStat('$pricesSet', 'prices set'),
            ],
          ),
          if (errors.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text('Catalog errors:',
                style: TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 13,
                    color: cs.error)),
            const SizedBox(height: 4),
            ...errors.take(20).map((e) => Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Text(
                    '• ${e['item']}: ${e['reason']}',
                    style:
                        TextStyle(fontSize: 12, color: cs.onErrorContainer),
                  ),
                )),
            if (errors.length > 20)
              Text('…and ${errors.length - 20} more errors',
                  style: TextStyle(fontSize: 12, color: cs.outline)),
          ],
          if (stockErrors.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text('Stock receipt errors:',
                style: TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 13,
                    color: cs.error)),
            const SizedBox(height: 4),
            ...stockErrors.take(20).map((e) => Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Text('• $e',
                      style: TextStyle(
                          fontSize: 12, color: cs.onErrorContainer)),
                )),
            if (stockErrors.length > 20)
              Text('…and ${stockErrors.length - 20} more errors',
                  style: TextStyle(fontSize: 12, color: cs.outline)),
          ],
          if (priceErrors.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text('Price errors:',
                style: TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 13,
                    color: cs.error)),
            const SizedBox(height: 4),
            ...priceErrors.take(20).map((e) => Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Text('• $e',
                      style: TextStyle(
                          fontSize: 12, color: cs.onErrorContainer)),
                )),
            if (priceErrors.length > 20)
              Text('…and ${priceErrors.length - 20} more errors',
                  style: TextStyle(fontSize: 12, color: cs.outline)),
          ],
        ],
      ),
      actions: [
        TextButton(
          onPressed: onDismiss,
          child: Text('Dismiss', style: TextStyle(color: fg)),
        ),
      ],
    );
  }
}

class _ResultStat extends StatelessWidget {
  final String value;
  final String label;
  const _ResultStat(this.value, this.label);

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(value,
            style: const TextStyle(
                fontWeight: FontWeight.bold, fontSize: 16)),
        const SizedBox(width: 4),
        Text(label,
            style: TextStyle(
                fontSize: 12,
                color: Theme.of(context).colorScheme.outline)),
      ],
    );
  }
}

// ── Error banner ──────────────────────────────────────────────────────────────

class _ErrorBanner extends StatelessWidget {
  final String message;
  final VoidCallback onDismiss;
  const _ErrorBanner({required this.message, required this.onDismiss});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return MaterialBanner(
      backgroundColor: cs.errorContainer,
      leading: Icon(Icons.error_outline, color: cs.onErrorContainer),
      content: Text(message, style: TextStyle(color: cs.onErrorContainer)),
      actions: [
        TextButton(
          onPressed: onDismiss,
          child: Text('Dismiss', style: TextStyle(color: cs.onErrorContainer)),
        ),
      ],
    );
  }
}

