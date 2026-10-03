import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/amount_entry.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/util/short_ref.dart';
import 'providers/admin_providers.dart';

/// A manual journal (17.1): a date, a description and two or more lines that
/// balance, each a debit or a credit and never both.
///
/// The server refuses a journal that does not balance, and this dialog says so
/// first — the running difference is on screen as the lines are typed, and the
/// button stays disabled until it reads nought — because a finance user typing
/// twenty lines should not learn on the twenty-first that the third was wrong.
///
/// Where it is posted: purchase-svc posts a journal naming no store at the
/// caller's only store, or as the business's own for a caller held to none,
/// and refuses a caller held to two or more who names none
/// (`BUSINESS_WIDE_ONLY`). So a caller held to several picks one of theirs
/// ([storeIds]) and it is sent; anyone else is asked nothing.
///
/// Every debit and credit is read the way the app's language writes a number
/// ([AmountMarks]) and sent as the plain decimal it is. One the dialog cannot
/// read is refused in words, naming its line, and the journal waits: read as
/// nought it dropped out of the journal unsaid while the rest still balanced
/// and posted, and read with a point where the language groups thousands with
/// one, 1.000 lei was posted as 1.
///
/// An amount is money in the business's home currency, at that currency's own
/// places ([_Ledger]): a dinar's third place is taken, half a yen is refused,
/// and the sum and the totals are kept at those places. Until the currency is
/// read there are no places to read an amount at, so the journal waits and
/// says so.
class PostJournalDialog extends ConsumerStatefulWidget {
  const PostJournalDialog({super.key, this.storeIds = const []});

  /// The stores the caller is held to; empty for a caller held to none.
  final List<String> storeIds;

  /// Whether the caller must name the store: held to two or more.
  bool get picksStore => storeIds.length > 1;

  static const maxLines = 50;

  @override
  ConsumerState<PostJournalDialog> createState() => _PostJournalDialogState();
}

/// How a journal's amounts are read: with the marks the app's language
/// writes a number with, at the places the business's home currency is kept
/// at — three for the Kuwaiti dinar, none for the yen, never a fixed two.
/// purchase-svc keeps a ledger amount as it is sent (a NUMERIC with no scale
/// of its own) and checks none, so the currency's places are held here.
class _Ledger {
  _Ledger(this.marks, this.currency) : places = AppFormat.minorUnits(currency);

  final AmountMarks marks;

  /// The business's home currency, or null while it has not been read: two
  /// places then, as [AppFormat.money] writes an amount that has no currency,
  /// and nothing is posted.
  final String? currency;

  /// The currency's minor units ([AppFormat.minorUnits]).
  final int places;

  /// Fourteen digits in all, the currency's own places among them, so the
  /// fifty lines a journal may have add up to a figure the totals line still
  /// writes exactly.
  AmountShape get shape => AmountShape(14 - places, places);

  /// Why the amount typed in [field] cannot be read, or null.
  String? refusal(TextEditingController field) => shape.refusal(field.text.trim(), marks);

  /// The amount typed in [field] as the plain decimal it is, or null for
  /// nothing typed, nought, or a figure refused.
  String? plain(TextEditingController field) {
    final plain = shape.read(field.text.trim(), marks);
    return plain == '0' ? null : plain;
  }

  /// The amount typed in [field] in the currency's minor units — thousandths
  /// of a dinar, whole yen — summed exactly, as the ledger adds them, never in
  /// floating point. Nought for nothing typed or a figure refused.
  BigInt minor(TextEditingController field) {
    final typed = plain(field);
    if (typed == null) return BigInt.zero;
    final parts = typed.split('.');
    final fraction = (parts.length > 1 ? parts[1] : '').padRight(places, '0');
    return BigInt.parse('${parts[0]}$fraction');
  }

  /// [minor] units written as money in the app's language and the business's
  /// currency, at its places (`£1,050.00`, `1.050,00 RON`, `¥1,050`).
  String money(BigInt minor) =>
      AppFormat.money(minor.toDouble() / math.pow(10, places), currencyCode: currency);
}

class _JournalLine {
  final code = TextEditingController();
  final name = TextEditingController();
  final debit = TextEditingController();
  final credit = TextEditingController();

  void dispose() {
    code.dispose();
    name.dispose();
    debit.dispose();
    credit.dispose();
  }

  bool bothSides(_Ledger l) => l.plain(debit) != null && l.plain(credit) != null;

  /// No amount on either side, and nothing refused: left out of the journal.
  bool empty(_Ledger l) =>
      l.plain(debit) == null &&
      l.plain(credit) == null &&
      l.refusal(debit) == null &&
      l.refusal(credit) == null;
}

class _PostJournalDialogState extends ConsumerState<PostJournalDialog> {
  final _date = TextEditingController(text: yyyyMmDd(DateTime.now()));
  final _description = TextEditingController();
  final _marks = AmountMarks.ofApp();
  final _lines = <_JournalLine>[_JournalLine(), _JournalLine()];

  /// The store picked, for a caller held to several ([PostJournalDialog.picksStore]).
  String? _storeId;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _date.addListener(_refresh);
    _description.addListener(_refresh);
    for (final l in _lines) {
      _listen(l);
    }
  }

  void _listen(_JournalLine l) {
    l.debit.addListener(_refresh);
    l.credit.addListener(_refresh);
    l.code.addListener(_refresh);
  }

  void _refresh() => setState(() {});

  @override
  void dispose() {
    _date.dispose();
    _description.dispose();
    for (final l in _lines) {
      l.dispose();
    }
    super.dispose();
  }

  /// The business's home currency from [tenant], or null while it has not
  /// been read (still loading, the read failed, or it names none).
  static String? _currencyOf(AsyncValue<TenantInfo> tenant) {
    final code = tenant.value?.currency.trim() ?? '';
    return code.isEmpty ? null : code;
  }

  /// Whether the business is still being read: asked for, or about to be
  /// asked again after a failed attempt.
  static bool _reading(AsyncValue<TenantInfo> tenant) => tenant.isLoading || tenant.retrying;

  /// Why the journal waits for the business's currency, or null once it is
  /// read. A ledger line cannot be corrected, only reversed, so a journal is
  /// never posted at places that were guessed.
  static String? _currencyHold(AsyncValue<TenantInfo> tenant) {
    if (_currencyOf(tenant) != null) return null;
    return _reading(tenant)
        ? 'Reading the business\'s currency…'
        : 'The business\'s currency could not be read, so its amounts cannot be taken. '
            'Nothing is posted until it is.';
  }

  BigInt _totalDebit(_Ledger l) => _lines.fold(BigInt.zero, (s, line) => s + l.minor(line.debit));
  BigInt _totalCredit(_Ledger l) => _lines.fold(BigInt.zero, (s, line) => s + l.minor(line.credit));
  BigInt _difference(_Ledger l) => _totalDebit(l) - _totalCredit(l);

  /// The first amount that cannot be read, in words with its line, or null.
  String? _amountRefusal(_Ledger l) {
    for (final (i, line) in _lines.indexed) {
      if (l.refusal(line.debit) case final why?) return 'Debit on line ${i + 1}: $why';
      if (l.refusal(line.credit) case final why?) return 'Credit on line ${i + 1}: $why';
    }
    return null;
  }

  static final _codeShape = RegExp(r'^[A-Za-z0-9]{1,10}$');

  /// Why the journal cannot be posted yet, or null when it can.
  String? _blocker(AsyncValue<TenantInfo> tenant) {
    if (_currencyHold(tenant) case final why?) return why;
    final l = _Ledger(_marks, _currencyOf(tenant));
    if (_amountRefusal(l) case final why?) return why;
    if (widget.picksStore && _storeId == null) {
      return 'Choose the store the journal is posted at.';
    }
    if (_description.text.trim().isEmpty) return 'Describe the journal.';
    if (!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(_date.text.trim())) {
      return 'Date as yyyy-MM-dd.';
    }
    final live = _lines.where((line) => !line.empty(l)).toList();
    if (live.length < 2) return 'At least two lines with an amount.';
    for (final line in live) {
      if (!_codeShape.hasMatch(line.code.text.trim())) {
        return 'Every line needs a nominal code of 1–10 letters or digits.';
      }
      if (line.bothSides(l)) return 'A line is a debit or a credit, not both.';
    }
    final difference = _difference(l);
    if (difference != BigInt.zero) {
      return 'Debits and credits differ by ${l.money(difference.abs())}.';
    }
    return null;
  }

  Future<void> _submit() async {
    final tenant = ref.read(tenantInfoProvider);
    if (_blocker(tenant) != null || _busy) return;
    final ledger = _Ledger(_marks, _currencyOf(tenant));
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.purchase}/nominal-ledger/journals',
        data: {
          if (widget.picksStore) 'storeId': _storeId,
          'entryDate': _date.text.trim(),
          'description': _description.text.trim(),
          'lines': [
            for (final l in _lines.where((l) => !l.empty(ledger)))
              {
                'nominalCode': l.code.text.trim(),
                if (l.name.text.trim().isNotEmpty)
                  'nominalName': l.name.text.trim(),
                // The plain decimal typed: JSON-B reads it into a BigDecimal
                // exactly.
                'debit': ?ledger.plain(l.debit),
                'credit': ?ledger.plain(l.credit),
              },
          ],
        },
      );
      if (!mounted) return;
      Navigator.pop(context, true);
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Journal posted.')));
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = friendlyError(e, fallback: 'Could not post the journal.');
      });
    }
  }

  void _addLine() {
    if (_lines.length >= PostJournalDialog.maxLines) return;
    final l = _JournalLine();
    _listen(l);
    setState(() => _lines.add(l));
  }

  void _removeLine(int i) {
    if (_lines.length <= 2) return;
    final l = _lines.removeAt(i);
    setState(() {});
    l.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    // The business's home currency: the journal is in it, and at its places.
    final tenant = ref.watch(tenantInfoProvider);
    final ledger = _Ledger(_marks, _currencyOf(tenant));
    final hold = _currencyHold(tenant);
    final unread = hold != null && !_reading(tenant);
    final blocker = _blocker(tenant);
    final refused = unread || (hold == null && _amountRefusal(ledger) != null);
    final difference = _difference(ledger);
    final amountKeys = TextInputType.numberWithOptions(decimal: ledger.places > 0);
    return AlertDialog(
      title: const Text('Post a journal'),
      content: SizedBox(
        width: 640,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (widget.picksStore) ...[
                _StorePicker(
                  storeIds: widget.storeIds,
                  value: _storeId,
                  onChanged: (id) => setState(() => _storeId = id),
                ),
                const SizedBox(height: 8),
              ],
              Row(children: [
                SizedBox(
                  width: 150,
                  child: TextField(
                    key: const Key('journal-date'),
                    controller: _date,
                    decoration: const InputDecoration(labelText: 'Date'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    key: const Key('journal-description'),
                    controller: _description,
                    maxLength: 500,
                    decoration:
                        const InputDecoration(labelText: 'Description'),
                  ),
                ),
              ]),
              const SizedBox(height: 8),
              for (var i = 0; i < _lines.length; i++)
                Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Row(children: [
                    SizedBox(
                      width: 90,
                      child: TextField(
                        key: Key('journal-code-$i'),
                        controller: _lines[i].code,
                        maxLength: 10,
                        decoration: const InputDecoration(
                            labelText: 'Code', counterText: ''),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextField(
                        key: Key('journal-name-$i'),
                        controller: _lines[i].name,
                        maxLength: 100,
                        decoration: const InputDecoration(
                            labelText: 'Account', counterText: ''),
                      ),
                    ),
                    const SizedBox(width: 8),
                    SizedBox(
                      width: 100,
                      child: TextField(
                        key: Key('journal-debit-$i'),
                        controller: _lines[i].debit,
                        keyboardType: amountKeys,
                        // Refused, it is marked here and said in full below.
                        decoration: InputDecoration(
                          labelText: 'Debit',
                          hintText: _marks.hint(ledger.places),
                          error: ledger.refusal(_lines[i].debit) == null
                              ? null
                              : const SizedBox.shrink(),
                        ),
                      ),
                    ),
                    const SizedBox(width: 8),
                    SizedBox(
                      width: 100,
                      child: TextField(
                        key: Key('journal-credit-$i'),
                        controller: _lines[i].credit,
                        keyboardType: amountKeys,
                        decoration: InputDecoration(
                          labelText: 'Credit',
                          hintText: _marks.hint(ledger.places),
                          error: ledger.refusal(_lines[i].credit) == null
                              ? null
                              : const SizedBox.shrink(),
                        ),
                      ),
                    ),
                    IconButton(
                      key: Key('journal-remove-$i'),
                      tooltip: 'Remove line',
                      onPressed:
                          _lines.length > 2 ? () => _removeLine(i) : null,
                      icon: const Icon(Icons.remove_circle_outline, size: 18),
                    ),
                  ]),
                ),
              Row(children: [
                TextButton.icon(
                  key: const Key('journal-add-line'),
                  onPressed: _lines.length < PostJournalDialog.maxLines
                      ? _addLine
                      : null,
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('Add line'),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Dr ${ledger.money(_totalDebit(ledger))} · '
                    'Cr ${ledger.money(_totalCredit(ledger))} · '
                    'difference ${ledger.money(difference)}',
                    key: const Key('journal-totals'),
                    textAlign: TextAlign.right,
                    style: TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 12,
                      color: difference == BigInt.zero ? cs.outline : cs.error,
                    ),
                  ),
                ),
              ]),
              if (blocker != null && _error == null)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(blocker,
                      key: const Key('journal-blocker'),
                      style: TextStyle(
                          color: refused ? cs.error : cs.outline, fontSize: 12)),
                ),
              if (unread && _error == null)
                Align(
                  alignment: AlignmentDirectional.centerStart,
                  child: TextButton(
                    key: const Key('journal-currency-retry'),
                    onPressed: () => ref.invalidate(tenantInfoProvider),
                    child: const Text('Try again'),
                  ),
                ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_error!, style: TextStyle(color: cs.error)),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('journal-post'),
          onPressed: blocker == null && !_busy ? _submit : null,
          child: const Text('Post'),
        ),
      ],
    );
  }
}

/// The caller's own stores, by name, to post the journal at. A store whose
/// name cannot be read yet is shown by its short reference.
class _StorePicker extends ConsumerWidget {
  const _StorePicker({required this.storeIds, required this.value, required this.onChanged});

  final List<String> storeIds;
  final String? value;
  final ValueChanged<String?> onChanged;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final names = {
      for (final s in ref.watch(storesProvider).value ?? const <StoreInfo>[]) s.id: s.name,
    };
    return DropdownButtonFormField<String>(
      key: const Key('journal-store'),
      initialValue: value,
      isExpanded: true,
      decoration: const InputDecoration(
        labelText: 'Store',
        helperText: 'One of your stores: the journal is posted at it.',
      ),
      items: [
        for (final id in storeIds)
          DropdownMenuItem(value: id, child: Text(names[id] ?? shortRef(id))),
      ],
      onChanged: onChanged,
    );
  }
}
