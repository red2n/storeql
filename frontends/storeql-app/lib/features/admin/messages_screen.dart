import 'dart:math' as math;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/constants.dart';
import '../../core/l10n/message_languages.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';

// ---------------------------------------------------------------------------
// The business's messages in its own words (13.x).
//
// Every message the platform sends on the business's behalf — to customers,
// staff and suppliers — goes out in the platform's words until the business
// writes its own, per form (email, text, push, store alert) and per language.
// A customer who has said which language they read gets it in that one when
// the business has written it; everyone else gets the business's own language.
// Some parts cannot be left out: a recall notice must still say what the law
// says it must. Nothing is saved until the server has checked it.
//
// The list is `/admin/messages` and one message is `/admin/messages/:type`
// (lib/core/router.dart), inside the admin shell: a reload, the browser's
// back button or a shared link lands on the same message.
// ---------------------------------------------------------------------------

class MessageVariable {
  final String name;
  final String kind;
  final String description;
  const MessageVariable(this.name, this.kind, this.description);

  /// What a template writes to use it: a list's field is written by its own name.
  String get token => '{{${name.contains('.') ? name.split('.').last : name}}}';
}

class MessageForm {
  final String form;
  final int subjectMax;
  final int bodyMax;
  final List<List<String>> required;
  final Map<String, int> written;
  const MessageForm(this.form, this.subjectMax, this.bodyMax, this.required, this.written);

  bool get hasSubject => subjectMax > 0;

  factory MessageForm.fromJson(Map<String, dynamic> j) => MessageForm(
        j['form'] as String,
        (j['subjectMax'] as num).toInt(),
        (j['bodyMax'] as num).toInt(),
        [
          for (final g in j['required'] as List<dynamic>? ?? const [])
            [for (final n in g as List<dynamic>) n.toString()],
        ],
        {
          for (final w in j['written'] as List<dynamic>? ?? const [])
            (w as Map<String, dynamic>)['language'] as String: (w['version'] as num).toInt(),
        },
      );
}

class MessageType {
  final String type;
  final String title;
  final String audience;
  final String why;
  final List<MessageVariable> variables;
  final List<MessageForm> forms;
  const MessageType(this.type, this.title, this.audience, this.why, this.variables, this.forms);

  factory MessageType.fromJson(Map<String, dynamic> j) => MessageType(
        j['type'] as String,
        j['title'] as String,
        j['audience'] as String,
        j['why'] as String? ?? '',
        [
          for (final v in j['variables'] as List<dynamic>? ?? const [])
            MessageVariable(
              (v as Map<String, dynamic>)['name'] as String,
              v['kind'] as String? ?? 'TEXT',
              v['description'] as String? ?? '',
            ),
        ],
        [for (final f in j['forms'] as List<dynamic>? ?? const []) MessageForm.fromJson(f as Map<String, dynamic>)],
      );
}

class MessageTemplate {
  final String source;
  final int? version;
  final String subject;
  final String body;
  const MessageTemplate(this.source, this.version, this.subject, this.body);

  bool get isBusiness => source == 'BUSINESS';

  factory MessageTemplate.fromJson(Map<String, dynamic> j) => MessageTemplate(
        j['source'] as String? ?? 'DEFAULT',
        (j['version'] as num?)?.toInt(),
        j['subject'] as String? ?? '',
        j['body'] as String? ?? '',
      );
}

class MessagePreview {
  final String? subject;
  final String? body;
  final int? smsSegments;
  final List<String> problems;
  const MessagePreview(this.subject, this.body, this.smsSegments, this.problems);

  factory MessagePreview.fromJson(Map<String, dynamic> j) => MessagePreview(
        j['subject'] as String?,
        j['body'] as String?,
        (j['smsSegments'] as num?)?.toInt(),
        [for (final p in j['problems'] as List<dynamic>? ?? const []) (p as Map<String, dynamic>)['message'] as String],
      );
}

class MessageSettings {
  final String defaultLanguage;
  final String? signOff;
  final String signedAs;
  const MessageSettings(this.defaultLanguage, this.signOff, this.signedAs);

  factory MessageSettings.fromJson(Map<String, dynamic> j) => MessageSettings(
        j['defaultLanguage'] as String? ?? 'en',
        j['signOff'] as String?,
        j['signedAs'] as String? ?? '',
      );
}

class MessagesApi {
  final Dio _dio;
  MessagesApi(this._dio);

  static const _base = '/${ApiConstants.notification}/admin/notifications';

  Future<List<MessageType>> catalogue() async {
    final r = await _dio.get('$_base/templates');
    return [for (final t in r.data['data'] as List<dynamic>) MessageType.fromJson(t as Map<String, dynamic>)];
  }

  String _one(String type, String form, String language) => '$_base/templates/$type/$form/$language';

  Future<MessageTemplate> template(String type, String form, String language) async =>
      MessageTemplate.fromJson((await _dio.get(_one(type, form, language))).data['data'] as Map<String, dynamic>);

  Future<MessageTemplate> save(String type, String form, String language, String subject, String body) async =>
      MessageTemplate.fromJson(
        (await _dio.put(_one(type, form, language), data: {'subject': subject, 'body': body})).data['data']
            as Map<String, dynamic>,
      );

  Future<void> retire(String type, String form, String language) => _dio.delete(_one(type, form, language));

  Future<MessagePreview> preview(String type, String form, String language, String subject, String body) async =>
      MessagePreview.fromJson(
        (await _dio.post('${_one(type, form, language)}/preview', data: {'subject': subject, 'body': body}))
            .data['data'] as Map<String, dynamic>,
      );

  Future<MessageSettings> settings() async =>
      MessageSettings.fromJson((await _dio.get('$_base/template-settings')).data['data'] as Map<String, dynamic>);

  Future<MessageSettings> putSettings(String language, String? signOff) async => MessageSettings.fromJson(
        (await _dio.put('$_base/template-settings', data: {'defaultLanguage': language, 'signOff': signOff ?? ''}))
            .data['data'] as Map<String, dynamic>,
      );
}

final messagesApiProvider = Provider<MessagesApi>((ref) => MessagesApi(ref.watch(apiClientProvider).dio));
final messageCatalogueProvider =
    FutureProvider.autoDispose<List<MessageType>>((ref) => ref.watch(messagesApiProvider).catalogue());
final messageSettingsProvider =
    FutureProvider.autoDispose<MessageSettings>((ref) => ref.watch(messagesApiProvider).settings());

const _audienceTitles = {'CUSTOMER': 'To customers', 'STAFF': 'To staff', 'SUPPLIER': 'To suppliers'};
const _formNames = {'EMAIL': 'Email', 'SMS': 'Text', 'PUSH': 'Push', 'ALERT': 'Store alert'};

/// Where the list is, and where one message is.
const _messagesPath = '/admin/messages';
String _messagePath(String type) => '$_messagesPath/${Uri.encodeComponent(type)}';

class MessagesScreen extends ConsumerWidget {
  const MessagesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final catalogue = ref.watch(messageCatalogueProvider);
    return ListView(
      // 16 on a phone, 24 from tablet width, like every page.
      padding: context.pagePadding,
      children: [
        const PageHeader(
          title: 'Messages',
          subtitle: 'What the business sends to customers, staff and suppliers. Each goes out in the platform\'s '
              'words until you write your own — per form and per language. A customer who has chosen a '
              'language gets it in that one when you have written it.',
          // The list's own padding is the gutter.
          padding: EdgeInsetsDirectional.only(bottom: AppSpacing.lg),
        ),
        const _SettingsCard(),
        const SizedBox(height: AppSpacing.lg),
        catalogue.when(
          loading: () => const LoadingView(label: 'Loading messages…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the messages.'),
            onRetry: () => ref.invalidate(messageCatalogueProvider),
          ),
          data: (types) => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (final audience in _audienceTitles.keys)
                if (types.any((t) => t.audience == audience)) ...[
                  Padding(
                    padding: const EdgeInsetsDirectional.only(top: AppSpacing.md, bottom: AppSpacing.sm),
                    child: Text(_audienceTitles[audience]!, style: theme.textTheme.titleMedium),
                  ),
                  Card(
                    margin: EdgeInsets.zero,
                    child: Column(
                      children: [
                        for (final t in types.where((t) => t.audience == audience))
                          ListTile(
                            key: Key('messages-open-${t.type}'),
                            title: Text(t.title),
                            subtitle: Text(_written(t)),
                            trailing: const Icon(Icons.chevron_right),
                            // Its own address in the shell. The editor reads the
                            // catalogue again when it saves, so the list is current
                            // when it is back.
                            onTap: () => context.go(_messagePath(t.type)),
                          ),
                      ],
                    ),
                  ),
                ],
            ],
          ),
        ),
      ],
    );
  }

  static String _written(MessageType t) => t.forms.map((f) {
        final langs = f.written.keys.toList()..sort();
        final name = _formNames[f.form] ?? f.form;
        return langs.isEmpty ? '$name: platform\'s words' : '$name: your words in ${langs.map(languageName).join(', ')}';
      }).join(' · ');
}

class _SettingsCard extends ConsumerWidget {
  const _SettingsCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settings = ref.watch(messageSettingsProvider);
    return Card(
      key: const Key('message-settings-card'),
      margin: EdgeInsets.zero,
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: settings.when(
          loading: () => const SizedBox(height: 48, child: Center(child: CircularProgressIndicator())),
          error: (e, _) => Text(friendlyError(e)),
          data: (s) => Row(
            children: [
              Expanded(
                child: Text(
                  'Written in ${languageName(s.defaultLanguage)} when a customer has not chosen, '
                  'and signed "${s.signedAs}".',
                ),
              ),
              TextButton(
                key: const Key('message-settings-edit'),
                onPressed: () => showDialog<void>(context: context, builder: (_) => _SettingsDialog(current: s)),
                child: const Text('Change'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SettingsDialog extends ConsumerStatefulWidget {
  final MessageSettings current;
  const _SettingsDialog({required this.current});

  @override
  ConsumerState<_SettingsDialog> createState() => _SettingsDialogState();
}

class _SettingsDialogState extends ConsumerState<_SettingsDialog> {
  late String _language = widget.current.defaultLanguage;
  late final _signOff = TextEditingController(text: widget.current.signOff ?? '');
  String? _error;

  @override
  void dispose() {
    _signOff.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    try {
      await ref.read(messagesApiProvider).putSettings(_language, _signOff.text.trim());
      ref.invalidate(messageSettingsProvider);
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      setState(() => _error = apiErrorCode(e) == 'FORBIDDEN' ? 'Only an owner or a manager changes this.' : friendlyError(e));
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: const Text('How your messages go out'),
        content: SizedBox(
          width: 420,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              DropdownButtonFormField<String>(
                key: const Key('message-settings-language'),
                initialValue: knownLanguages.containsKey(_language) ? _language : 'en',
                decoration: const InputDecoration(labelText: 'Language when a customer has not chosen one'),
                items: [
                  for (final e in knownLanguages.entries) DropdownMenuItem(value: e.key, child: Text(e.value)),
                ],
                onChanged: (v) => setState(() => _language = v ?? _language),
              ),
              const SizedBox(height: AppSpacing.md),
              TextField(
                key: const Key('message-settings-sign-off'),
                controller: _signOff,
                maxLength: 120,
                decoration: InputDecoration(
                  labelText: 'Signed with',
                  helperText: 'Leave empty to sign with the business\'s name (${widget.current.signedAs}).',
                ),
              ),
              if (_error != null) Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
          FilledButton(key: const Key('message-settings-save'), onPressed: _save, child: const Text('Save')),
        ],
      );
}

/// One message at its own address, `/admin/messages/:type`. It finds the
/// message in the catalogue, so a reload or a link opens the same one, and
/// says so when the business sends no message of that name.
class MessageEditorPage extends ConsumerWidget {
  /// The message's code, from the address: `ORDER_CONFIRMED`.
  final String type;
  const MessageEditorPage({super.key, required this.type});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final catalogue = ref.watch(messageCatalogueProvider);
    // While the catalogue is read again after a save it keeps the one it had,
    // so the editor stays as it is rather than going back to a spinner.
    final types = catalogue.value;
    if (types == null) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const _EditorBackRow(),
          Expanded(
            child: catalogue.hasError
                ? ErrorView(
                    message: friendlyError(catalogue.error!, fallback: 'Could not load the message.'),
                    onRetry: () => ref.invalidate(messageCatalogueProvider),
                  )
                : const LoadingView(label: 'Loading the message…'),
          ),
        ],
      );
    }
    final found = types.where((t) => t.type == type && t.forms.isNotEmpty).firstOrNull;
    if (found == null) {
      return EmptyState(
        icon: Icons.mail_outline,
        title: 'No such message',
        message: 'The business sends no message by that name. The link may be to one that has gone.',
        action: FilledButton(
          onPressed: () => context.go(_messagesPath),
          child: const Text('All messages'),
        ),
      );
    }
    return MessageEditorScreen(type: found);
  }
}

/// The way back to the list, and the message's name once it is known.
class _EditorBackRow extends StatelessWidget {
  final String? title;

  const _EditorBackRow({this.title});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final gutter = context.pageGutter;
    final name = title;
    return Padding(
      // Less than the gutter by the button's own inset, so its arrow lines up
      // with the text under it.
      padding: EdgeInsetsDirectional.fromSTEB(gutter - AppSpacing.md, AppSpacing.sm, gutter, AppSpacing.sm),
      child: Row(
        children: [
          IconButton(
            key: const Key('message-editor-back'),
            icon: const Icon(Icons.arrow_back),
            tooltip: 'All messages',
            onPressed: () => context.go(_messagesPath),
          ),
          if (name != null) ...[
            const SizedBox(width: AppSpacing.xs),
            Expanded(
              child: Semantics(
                header: true,
                child: Text(
                  name,
                  style: context.isCompact ? theme.textTheme.titleLarge : theme.textTheme.headlineSmall,
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// One message: its forms, its languages, the words each goes out in, and a
/// place to change them. It sits in the admin shell, under the shell's own
/// app bar, with the way back to the list above it.
class MessageEditorScreen extends ConsumerStatefulWidget {
  final MessageType type;
  const MessageEditorScreen({super.key, required this.type});

  @override
  ConsumerState<MessageEditorScreen> createState() => _MessageEditorScreenState();
}

class _MessageEditorScreenState extends ConsumerState<MessageEditorScreen> {
  late MessageForm _form = widget.type.forms.first;
  String _language = 'en';
  final _subject = TextEditingController();
  final _body = TextEditingController();
  MessageTemplate? _loaded;
  MessagePreview? _preview;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(MessageEditorScreen old) {
    super.didUpdateWidget(old);
    if (old.type.type != widget.type.type) {
      // Another message in the same place: start it afresh.
      _form = widget.type.forms.first;
      _language = 'en';
      _load();
    } else if (!identical(old.type, widget.type)) {
      // The catalogue read again after a save: the same form, now saying
      // which languages are written.
      _form = widget.type.forms.firstWhere((f) => f.form == _form.form, orElse: () => widget.type.forms.first);
    }
  }

  @override
  void dispose() {
    _subject.dispose();
    _body.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _busy = true;
      _error = null;
      _preview = null;
    });
    try {
      final t = await ref.read(messagesApiProvider).template(widget.type.type, _form.form, _language);
      if (!mounted) return;
      setState(() {
        _loaded = t;
        _subject.text = t.subject;
        _body.text = t.body;
      });
    } catch (e) {
      if (mounted) setState(() => _error = friendlyError(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _run(Future<void> Function() work) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await work();
    } catch (e) {
      if (mounted) {
        setState(() => _error = switch (apiErrorCode(e)) {
              'FORBIDDEN' || 'PERMISSION_DENIED' => 'Only an owner or a manager changes messages.',
              'TEMPLATE_SAVED_MEANWHILE' => 'Somebody saved this at the same moment. Open it again.',
              _ => friendlyError(e),
            });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _showPreview() => _run(() async {
        final p = await ref
            .read(messagesApiProvider)
            .preview(widget.type.type, _form.form, _language, _subject.text, _body.text);
        if (mounted) setState(() => _preview = p);
      });

  Future<void> _save() => _run(() async {
        final t = await ref
            .read(messagesApiProvider)
            .save(widget.type.type, _form.form, _language, _subject.text, _body.text);
        if (!mounted) return;
        setState(() => _loaded = t);
        // The list says which forms are the business's words, and in which languages.
        ref.invalidate(messageCatalogueProvider);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Saved as version ${t.version} in ${languageName(_language)}.')),
        );
      });

  Future<void> _retire() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Use the platform\'s words'),
        content: Text(
          'Your ${_formNames[_form.form]?.toLowerCase()} in ${languageName(_language)} stops being used. '
          'It is kept in the history.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Cancel')),
          FilledButton(
            key: const Key('template-retire-confirm'),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Use the platform\'s words'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    await _run(() async {
      await ref.read(messagesApiProvider).retire(widget.type.type, _form.form, _language);
      ref.invalidate(messageCatalogueProvider);
      await _load();
    });
  }

  void _insert(MessageVariable v) {
    final sel = _body.selection;
    final text = _body.text;
    final at = sel.isValid ? sel.start : text.length;
    final end = sel.isValid ? sel.end : text.length;
    _body.value = TextEditingValue(
      text: text.replaceRange(at, end, v.token),
      selection: TextSelection.collapsed(offset: at + v.token.length),
    );
  }

  Future<void> _otherLanguage() async {
    final ctrl = TextEditingController();
    final code = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Another language'),
        content: TextField(
          key: const Key('template-language-code'),
          controller: ctrl,
          autofocus: true,
          maxLength: 3,
          decoration: const InputDecoration(labelText: 'Its ISO 639 code', hintText: 'e.g. ta, so, lt'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.of(context).pop(ctrl.text.trim().toLowerCase()), child: const Text('Open')),
        ],
      ),
    );
    ctrl.dispose();
    if (code == null || !RegExp(r'^[a-z]{2,3}$').hasMatch(code)) return;
    setState(() => _language = code);
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final languages = {...knownLanguages.keys, ..._form.written.keys, _language}.toList();
    final loaded = _loaded;
    final gutter = context.pageGutter;
    // No Scaffold or AppBar of its own: the admin shell's app bar is the page's.
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _EditorBackRow(title: widget.type.title),
        Expanded(
          // The subject and the body at a form's measure on a desktop,
          // centred: full width, a message's lines ran across the window.
          child: LayoutBuilder(builder: (context, constraints) {
            final side = math.max(gutter, (constraints.maxWidth - AppBreakpoints.formMaxWidth) / 2);
            return ListView(
            padding: EdgeInsetsDirectional.fromSTEB(side, 0, side, gutter),
            children: [
              Text(widget.type.why, style: theme.textTheme.bodyMedium?.copyWith(color: cs.onSurfaceVariant)),
              const SizedBox(height: AppSpacing.lg),
              LayoutBuilder(
                builder: (context, constraints) => Wrap(
                  spacing: AppSpacing.md,
                  runSpacing: AppSpacing.sm,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    if (widget.type.forms.length > 1)
                      SegmentedButton<String>(
                        key: const Key('template-form'),
                        segments: [
                          for (final f in widget.type.forms)
                            ButtonSegment(value: f.form, label: Text(_formNames[f.form] ?? f.form)),
                        ],
                        selected: {_form.form},
                        onSelectionChanged: (s) {
                          setState(() => _form = widget.type.forms.firstWhere((f) => f.form == s.first));
                          _load();
                        },
                      ),
                    // As wide as its longest language, but never wider than the
                    // page: with large text on a phone a long name ellipsizes
                    // rather than running off the side.
                    ConstrainedBox(
                      constraints: BoxConstraints(maxWidth: constraints.maxWidth),
                      child: IntrinsicWidth(
                        child: DropdownButton<String>(
                          key: const Key('template-language'),
                          isExpanded: true,
                          value: _language,
                          items: [
                            for (final l in languages)
                              DropdownMenuItem(
                                value: l,
                                child: Text(
                                  _form.written.containsKey(l) ? '${languageName(l)} · yours' : languageName(l),
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                            const DropdownMenuItem(
                              value: '*',
                              child: Text('Another language…', overflow: TextOverflow.ellipsis),
                            ),
                          ],
                          onChanged: _busy
                              ? null
                              : (v) {
                                  if (v == '*') {
                                    _otherLanguage();
                                  } else if (v != null) {
                                    setState(() => _language = v);
                                    _load();
                                  }
                                },
                        ),
                      ),
                    ),
                    if (loaded != null)
                      Chip(
                        key: const Key('template-source'),
                        label: Text(loaded.isBusiness ? 'Your words · version ${loaded.version}' : 'The platform\'s words'),
                      ),
                  ],
                ),
              ),
              const SizedBox(height: AppSpacing.lg),
              if (_form.required.isNotEmpty)
                Text(
                  'Must say: ${_form.required.map((g) => g.length == 1 ? g.first : 'one of ${g.join(', ')}').join('; ')}',
                  style: theme.textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant),
                ),
              if (_form.hasSubject) ...[
                const SizedBox(height: AppSpacing.sm),
                TextField(
                  key: const Key('template-subject'),
                  controller: _subject,
                  decoration: InputDecoration(labelText: _form.form == 'EMAIL' ? 'Subject' : 'Title'),
                ),
              ],
              const SizedBox(height: AppSpacing.md),
              TextField(
                key: const Key('template-body'),
                controller: _body,
                minLines: _form.form == 'EMAIL' ? 10 : 4,
                maxLines: null,
                decoration: const InputDecoration(labelText: 'Message', alignLabelWithHint: true),
              ),
              const SizedBox(height: AppSpacing.sm),
              Text('Insert a value', style: theme.textTheme.labelLarge),
              const SizedBox(height: AppSpacing.xs),
              Wrap(
                spacing: AppSpacing.xs,
                runSpacing: AppSpacing.xs,
                children: [
                  for (final v in widget.type.variables)
                    Tooltip(
                      message: v.description,
                      child: ActionChip(
                        key: Key('var-${v.name}'),
                        label: Text(v.token),
                        onPressed: () => _insert(v),
                      ),
                    ),
                ],
              ),
              const SizedBox(height: AppSpacing.lg),
              if (_error != null) ...[
                Text(_error!, key: const Key('template-error'), style: TextStyle(color: cs.error)),
                const SizedBox(height: AppSpacing.sm),
              ],
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.sm,
                children: [
                  OutlinedButton.icon(
                    key: const Key('template-preview'),
                    onPressed: _busy ? null : _showPreview,
                    icon: const Icon(Icons.visibility_outlined),
                    label: const Text('Preview'),
                  ),
                  FilledButton.icon(
                    key: const Key('template-save'),
                    onPressed: _busy ? null : _save,
                    icon: const Icon(Icons.save_outlined),
                    label: const Text('Save'),
                  ),
                  if (loaded?.isBusiness == true)
                    TextButton(
                      key: const Key('template-retire'),
                      onPressed: _busy ? null : _retire,
                      child: const Text('Use the platform\'s words'),
                    ),
                ],
              ),
              if (_preview != null) ...[
                const SizedBox(height: AppSpacing.lg),
                _PreviewCard(preview: _preview!, form: _form.form),
              ],
            ],
          );
          }),
        ),
      ],
    );
  }
}

class _PreviewCard extends StatelessWidget {
  final MessagePreview preview;
  final String form;
  const _PreviewCard({required this.preview, required this.form});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    return Card(
      key: const Key('template-preview-card'),
      margin: EdgeInsets.zero,
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('With sample values', style: theme.textTheme.labelLarge?.copyWith(color: cs.onSurfaceVariant)),
            const SizedBox(height: AppSpacing.sm),
            if (preview.subject != null && preview.subject!.isNotEmpty)
              Text(preview.subject!, style: theme.textTheme.titleMedium),
            if (preview.body != null) SelectableText(preview.body!),
            if (preview.smsSegments != null) ...[
              const SizedBox(height: AppSpacing.sm),
              Text(
                preview.smsSegments == 1 ? 'One text message.' : '${preview.smsSegments} text messages, charged as such.',
                style: theme.textTheme.bodySmall,
              ),
            ],
            for (final p in preview.problems) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(p, style: TextStyle(color: cs.error)),
            ],
            if (preview.problems.isEmpty) ...[
              const SizedBox(height: AppSpacing.xs),
              Text('Ready to save.', style: theme.textTheme.bodySmall?.copyWith(color: cs.primary)),
            ],
          ],
        ),
      ),
    );
  }
}
