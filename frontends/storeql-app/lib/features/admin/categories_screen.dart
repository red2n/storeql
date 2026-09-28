import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../shared/widgets/status_badge.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/scrollable_table.dart';
import 'providers/admin_providers.dart';
import '../../core/theme.dart';

class CategoriesScreen extends ConsumerWidget {
  const CategoriesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final catsAsync = ref.watch(categoriesProvider);
    final cs = Theme.of(context).colorScheme;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: EdgeInsets.fromLTRB(context.pageGutter, context.pageGutter,
              context.pageGutter, 16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text('Categories',
                        style: Theme.of(context).textTheme.headlineMedium),
                  ),
                  catsAsync.when(
                    loading: () => const SizedBox.shrink(),
                    error: (_, _) => const SizedBox.shrink(),
                    data: (list) => Chip(
                      label: Text('${list.length} categories'),
                      backgroundColor: cs.secondaryContainer,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              OverflowBar(
                spacing: 8,
                overflowSpacing: 8,
                overflowAlignment: OverflowBarAlignment.start,
                children: [
                  FilledButton.icon(
                    onPressed: () => _showCreateDialog(context, ref, null),
                    icon: const Icon(Icons.add),
                    label: const Text('New Category'),
                  ),
                  OutlinedButton.icon(
                    onPressed: () => ref.invalidate(categoriesProvider),
                    icon: const Icon(Icons.refresh),
                    label: const Text('Refresh'),
                  ),
                ],
              ),
            ],
          ),
        ),

        Expanded(
          child: catsAsync.when(
            loading: () => const LoadingView(label: 'Loading categories…'),
            error: (e, _) => ErrorView(
              message: 'Could not load categories.',
              onRetry: () => ref.invalidate(categoriesProvider),
            ),
            data: (cats) {
              if (cats.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.category_outlined,
                          size: 64, color: cs.outlineVariant),
                      const SizedBox(height: 16),
                      Text('No categories yet',
                          style: Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 8),
                      OutlinedButton.icon(
                        onPressed: () => _showCreateDialog(context, ref, null),
                        icon: const Icon(Icons.add),
                        label: const Text('New Category'),
                      ),
                    ],
                  ),
                );
              }

              // Build lookup maps for display
              final byId = {for (var c in cats) c.id: c};

              // Sort: root first, children after their parent
              final roots = cats.where((c) => c.parentId == null).toList();
              final children = cats.where((c) => c.parentId != null).toList();
              final sorted = [...roots, ...children];

              return LayoutBuilder(builder: (context, bc) {
                final wide = bc.maxWidth >= 700;
                if (wide) {
                  return _WideTable(
                    cats: sorted,
                    byId: byId,
                    allCats: cats,
                    onEdit: (c) => _showEditDialog(context, ref, c, cats),
                    onDeactivate: (c) => _deactivate(context, ref, c),
                  );
                }
                return _NarrowList(
                  cats: sorted,
                  byId: byId,
                  onEdit: (c) => _showEditDialog(context, ref, c, cats),
                  onDeactivate: (c) => _deactivate(context, ref, c),
                );
              });
            },
          ),
        ),
      ],
    );
  }

  void _showCreateDialog(
      BuildContext context, WidgetRef ref, CategoryInfo? parent) {
    showDialog(
      context: context,
      builder: (_) => _CategoryDialog(
        title: 'New Category',
        initialParentId: parent?.id,
        onSave: (name, parentId) async {
          await ref.read(apiClientProvider).dio.post(
            '/${ApiConstants.product}/admin/categories',
            data: {'name': name, 'parentId': parentId},
          );
          ref.invalidate(categoriesProvider);
        },
        availableParents: ref.read(categoriesProvider).value ?? [],
      ),
    );
  }

  void _showEditDialog(BuildContext context, WidgetRef ref, CategoryInfo cat,
      List<CategoryInfo> allCats) {
    showDialog(
      context: context,
      builder: (_) => _CategoryDialog(
        title: 'Edit Category',
        initialName: cat.name,
        initialParentId: cat.parentId,
        onSave: (name, parentId) async {
          await ref.read(apiClientProvider).dio.put(
            '/${ApiConstants.product}/admin/categories/${cat.id}',
            data: {'name': name, 'parentId': parentId},
          );
          ref.invalidate(categoriesProvider);
        },
        availableParents: allCats.where((c) => c.id != cat.id).toList(),
      ),
    );
  }

  Future<void> _deactivate(
      BuildContext context, WidgetRef ref, CategoryInfo cat) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Deactivate "${cat.name}"?'),
        content: const Text(
            'This category will be hidden from product assignment forms.'),
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
            child: const Text('Deactivate'),
          ),
        ],
      ),
    );
    if (confirmed != true || !context.mounted) return;
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .delete('/${ApiConstants.product}/admin/categories/${cat.id}');
      ref.invalidate(categoriesProvider);
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(
              friendlyError(e, fallback: 'Could not deactivate category.')),
          backgroundColor: Theme.of(context).colorScheme.error,
        ));
      }
    }
  }
}

// ── Wide table ────────────────────────────────────────────────────────────────

class _WideTable extends StatelessWidget {
  final List<CategoryInfo> cats;
  final Map<String, CategoryInfo> byId;
  final List<CategoryInfo> allCats;
  final void Function(CategoryInfo) onEdit;
  final void Function(CategoryInfo) onDeactivate;

  const _WideTable({
    required this.cats,
    required this.byId,
    required this.allCats,
    required this.onEdit,
    required this.onDeactivate,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    // Both axes scroll inside the card: the rows past its height were
    // clipped with no way to reach them.
    return Padding(
      padding: EdgeInsets.symmetric(horizontal: context.pageGutter),
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: ScrollableTable(
          child: DataTable(
            headingRowColor:
                WidgetStatePropertyAll(cs.surfaceContainerHigh),
            columnSpacing: 24,
            columns: const [
              DataColumn(label: Text('Name')),
              DataColumn(label: Text('Parent')),
              DataColumn(label: Text('Status')),
              DataColumn(label: Text('')),
            ],
            rows: cats.map((cat) {
              final active = cat.status.toUpperCase() == 'ACTIVE';
              final parentName = cat.parentId != null
                  ? (byId[cat.parentId]?.name ?? '—')
                  : '—';
              final isChild = cat.parentId != null;
              return DataRow(cells: [
                DataCell(Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (isChild) ...[
                      const SizedBox(width: 20),
                      Icon(Icons.subdirectory_arrow_right,
                          size: 14, color: cs.outline),
                      const SizedBox(width: 4),
                    ],
                    Text(cat.name,
                        style: TextStyle(
                          fontWeight: isChild
                              ? FontWeight.normal
                              : FontWeight.bold,
                        )),
                  ],
                )),
                DataCell(Text(parentName,
                    style: TextStyle(
                        fontSize: 12, color: cs.outline))),
                DataCell(_StatusChip(active: active, label: cat.status)),
                DataCell(PopupMenuButton<String>(
                  icon: const Icon(Icons.more_vert),
                  tooltip: 'Actions',
                  itemBuilder: (_) => [
                    const PopupMenuItem(
                        value: 'edit',
                        child: Row(children: [
                          Icon(Icons.edit_outlined, size: 18),
                          SizedBox(width: 8),
                          Text('Edit'),
                        ])),
                    if (active)
                      PopupMenuItem(
                          value: 'deactivate',
                          child: Row(children: [
                            Icon(Icons.block_outlined,
                                size: 18, color: cs.error),
                            const SizedBox(width: 8),
                            Text('Deactivate',
                                style: TextStyle(color: cs.error)),
                          ])),
                  ],
                  onSelected: (v) =>
                      v == 'edit' ? onEdit(cat) : onDeactivate(cat),
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

class _NarrowList extends StatelessWidget {
  final List<CategoryInfo> cats;
  final Map<String, CategoryInfo> byId;
  final void Function(CategoryInfo) onEdit;
  final void Function(CategoryInfo) onDeactivate;

  const _NarrowList({
    required this.cats,
    required this.byId,
    required this.onEdit,
    required this.onDeactivate,
  });

  @override
  Widget build(BuildContext context) {
    return ListView.separated(
      padding: EdgeInsets.symmetric(horizontal: context.pageGutter, vertical: 8),
      itemCount: cats.length,
      separatorBuilder: (_, _) => const SizedBox(height: 4),
      itemBuilder: (context, i) {
        final cat = cats[i];
        final cs = Theme.of(context).colorScheme;
        final active = cat.status.toUpperCase() == 'ACTIVE';
        final isChild = cat.parentId != null;
        return Card(
          margin: EdgeInsets.only(left: isChild ? 16 : 0),
          child: ListTile(
            leading: CircleAvatar(
              backgroundColor: cs.primaryContainer,
              child: Icon(Icons.category_outlined,
                  size: 18, color: cs.onPrimaryContainer),
            ),
            title: Text(cat.name,
                style:
                    TextStyle(fontWeight: isChild ? FontWeight.normal : FontWeight.bold)),
            subtitle: cat.parentId != null
                ? Text('Under: ${byId[cat.parentId]?.name ?? '—'}',
                    style: TextStyle(fontSize: 12, color: cs.outline))
                : null,
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                _StatusChip(active: active, label: cat.status),
                PopupMenuButton<String>(
                  icon: const Icon(Icons.more_vert),
                  itemBuilder: (_) => [
                    const PopupMenuItem(
                        value: 'edit',
                        child: Row(children: [
                          Icon(Icons.edit_outlined, size: 18),
                          SizedBox(width: 8),
                          Text('Edit'),
                        ])),
                    if (active)
                      PopupMenuItem(
                          value: 'deactivate',
                          child: Row(children: [
                            Icon(Icons.block_outlined,
                                size: 18, color: cs.error),
                            const SizedBox(width: 8),
                            Text('Deactivate',
                                style: TextStyle(color: cs.error)),
                          ])),
                  ],
                  onSelected: (v) =>
                      v == 'edit' ? onEdit(cat) : onDeactivate(cat),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

// ── Status chip ───────────────────────────────────────────────────────────────

/// A category's status as the shared badge, in words.
class _StatusChip extends StatelessWidget {
  final bool active;
  final String label;
  const _StatusChip({required this.active, required this.label});

  @override
  Widget build(BuildContext context) => StatusBadge(
        active ? 'Active' : humanizeCode(label),
        tone: active ? StatusTone.success : StatusTone.neutral,
      );
}

// ── Create / Edit dialog ──────────────────────────────────────────────────────

class _CategoryDialog extends StatefulWidget {
  final String title;
  final String? initialName;
  final String? initialParentId;
  final List<CategoryInfo> availableParents;
  final Future<void> Function(String name, String? parentId) onSave;

  const _CategoryDialog({
    required this.title,
    this.initialName,
    this.initialParentId,
    required this.availableParents,
    required this.onSave,
  });

  @override
  State<_CategoryDialog> createState() => _CategoryDialogState();
}

class _CategoryDialogState extends State<_CategoryDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameCtrl;
  String? _parentId;
  bool _loading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _nameCtrl = TextEditingController(text: widget.initialName ?? '');
    _parentId = widget.initialParentId;
  }

  @override
  void dispose() {
    _nameCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final activeParents =
        widget.availableParents.where((c) => c.status.toUpperCase() == 'ACTIVE').toList();

    return Dialog(
      shape: const RoundedRectangleBorder(borderRadius: AppRadius.card),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 420),
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Form(
            key: _formKey,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(widget.title,
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
                    labelText: 'Category name *',
                    prefixIcon: Icon(Icons.category_outlined),
                  ),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? 'Required' : null,
                ),
                const SizedBox(height: 12),
                DropdownButtonFormField<String?>(
                  initialValue: _parentId,
                  decoration:
                      const InputDecoration(labelText: 'Parent category'),
                  items: [
                    const DropdownMenuItem(value: null, child: Text('— None (root) —')),
                    ...activeParents.map((c) =>
                        DropdownMenuItem(value: c.id, child: Text(c.name))),
                  ],
                  onChanged: (v) => setState(() => _parentId = v),
                ),
                const SizedBox(height: 24),
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
                          : const Text('Save'),
                    ),
                  ],
                ),
              ],
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
      await widget.onSave(_nameCtrl.text.trim(), _parentId);
      if (mounted) Navigator.pop(context);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not save category.');
      });
    }
  }
}
