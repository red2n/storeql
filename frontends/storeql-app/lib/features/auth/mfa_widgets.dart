import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:qr/qr.dart';

import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import 'mfa_api.dart';

/// Copies [text] and says so on the button itself: *Copied*, with a tick, for
/// two seconds. A copy that happens silently leaves the person wondering
/// whether it did — and a snack bar is no answer here, because on the
/// security screen these widgets sit in a dialog, whose scrim covers the
/// page's snack bars.
///
/// With a [label] it is a labelled button (*Copy all*); without, an icon
/// button named by [tooltip] that shows the word while it confirms.
class _CopyButton extends StatefulWidget {
  const _CopyButton({required this.text, this.label, this.tooltip});

  final String text;
  final String? label;
  final String? tooltip;

  @override
  State<_CopyButton> createState() => _CopyButtonState();
}

class _CopyButtonState extends State<_CopyButton> {
  Timer? _timer;
  bool _copied = false;

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: widget.text));
    if (!mounted) return;
    setState(() => _copied = true);
    _timer?.cancel();
    _timer = Timer(const Duration(seconds: 2), () {
      if (mounted) setState(() => _copied = false);
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final label = widget.label;
    // A live region, so a screen reader says *Copied* when it appears.
    return Semantics(
      liveRegion: true,
      child: label == null && !_copied
          ? IconButton(
              tooltip: widget.tooltip,
              icon: const Icon(Icons.copy_outlined),
              onPressed: _copy,
            )
          : TextButton.icon(
              icon: Icon(_copied ? Icons.check : Icons.copy_outlined),
              label: Text(_copied ? 'Copied' : label!),
              onPressed: _copy,
            ),
    );
  }
}

/// A QR code, painted from the encoder's modules: what an authenticator app scans.
class QrView extends StatelessWidget {
  final String data;
  final double size;

  const QrView({super.key, required this.data, this.size = 200});

  /// The side to draw at: 200 where there is room, 160 in a short window (a
  /// laptop's 800), where the set-up's steps would otherwise run below the
  /// fold. Both scan from a phone at arm's length.
  static double sideFor(BuildContext context) => MediaQuery.sizeOf(context).height < 900 ? 160 : 200;

  @override
  Widget build(BuildContext context) {
    final image = QrImage(QrCode(payload: QrPayload.fromString(data)));
    return Semantics(
      label: 'QR code for an authenticator app',
      image: true,
      child: Container(
        // Scanners need black on white, whatever the theme (UI-GUIDE §7.1).
        color: Colors.white,
        padding: const EdgeInsets.all(AppSpacing.md),
        child: CustomPaint(size: Size.square(size), painter: _QrPainter(image)),
      ),
    );
  }
}

class _QrPainter extends CustomPainter {
  final QrImage image;

  _QrPainter(this.image);

  @override
  void paint(Canvas canvas, Size size) {
    final n = image.moduleCount;
    final cell = size.width / n;
    final dark = Paint()..color = Colors.black;
    for (var y = 0; y < n; y++) {
      for (var x = 0; x < n; x++) {
        if (image.isDark(y, x)) {
          // A hair of overlap, so neighbouring modules leave no seam between them.
          canvas.drawRect(Rect.fromLTWH(x * cell, y * cell, cell + 0.5, cell + 0.5), dark);
        }
      }
    }
  }

  @override
  bool shouldRepaint(_QrPainter old) => old.image != image;
}

/// Setting an authenticator app up: the QR code and the secret to type by hand,
/// then a code from the app to prove it has them. Calls [onEnrolled] with what
/// the server answered — the recovery codes, and the session if one was owed.
class TotpSetup extends StatefulWidget {
  final MfaApi api;
  final ValueChanged<FactorEnrolled> onEnrolled;

  const TotpSetup({super.key, required this.api, required this.onEnrolled});

  @override
  State<TotpSetup> createState() => _TotpSetupState();
}

class _TotpSetupState extends State<TotpSetup> {
  final _code = TextEditingController();
  TotpEnrolment? _enrolment;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _begin();
  }

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  Future<void> _begin() async {
    try {
      final enrolment = await widget.api.beginTotp();
      if (mounted) setState(() => _enrolment = enrolment);
    } catch (e) {
      if (mounted) setState(() => _error = friendlyError(e));
    }
  }

  Future<void> _confirm() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final enrolled = await widget.api.confirmTotp(_code.text);
      if (mounted) widget.onEnrolled(enrolled);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = apiErrorCode(e) == 'MFA_CODE_INVALID'
            ? 'That code did not match. Check the app shows this account, and try the next code.'
            : friendlyError(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final enrolment = _enrolment;
    final text = Theme.of(context).textTheme;
    if (enrolment == null) {
      return _error == null
          ? const Center(child: Padding(padding: EdgeInsets.all(AppSpacing.xl), child: CircularProgressIndicator()))
          : Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error));
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('1. Scan this with an authenticator app', style: text.titleSmall),
        const SizedBox(height: AppSpacing.md),
        Center(child: QrView(data: enrolment.otpauthUri, size: QrView.sideFor(context))),
        const SizedBox(height: AppSpacing.md),
        Text('Or type this key into the app:', style: text.bodySmall),
        const SizedBox(height: AppSpacing.xs),
        Row(
          children: [
            Expanded(
              child: SelectableText(
                _grouped(enrolment.secret),
                key: const Key('totp-secret'),
                style: text.bodyMedium?.copyWith(fontFamily: 'monospace', letterSpacing: 1.2),
              ),
            ),
            _CopyButton(text: enrolment.secret, tooltip: 'Copy the key'),
          ],
        ),
        const SizedBox(height: AppSpacing.lg),
        Text('2. Enter the six-digit code it shows', style: text.titleSmall),
        const SizedBox(height: AppSpacing.sm),
        CodeField(controller: _code, onSubmitted: _busy ? null : _confirm),
        if (_error != null) ...[
          const SizedBox(height: AppSpacing.sm),
          Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
        ],
        const SizedBox(height: AppSpacing.lg),
        FilledButton(
          key: const Key('totp-confirm'),
          onPressed: _busy ? null : _confirm,
          child: Text(_busy ? 'Checking…' : 'Confirm'),
        ),
      ],
    );
  }

  static String _grouped(String secret) =>
      RegExp('.{1,4}').allMatches(secret).map((m) => m.group(0)).join(' ');
}

/// The field a second-factor code is typed into: digits for an authenticator
/// app, or a recovery code when [recovery] is set.
class CodeField extends StatelessWidget {
  final TextEditingController controller;
  final VoidCallback? onSubmitted;
  final bool recovery;

  const CodeField({super.key, required this.controller, this.onSubmitted, this.recovery = false});

  @override
  Widget build(BuildContext context) => TextField(
        key: const Key('mfa-code'),
        controller: controller,
        autofocus: true,
        keyboardType: recovery ? TextInputType.text : TextInputType.number,
        autofillHints: const [AutofillHints.oneTimeCode],
        inputFormatters: recovery
            ? [LengthLimitingTextInputFormatter(20)]
            : [FilteringTextInputFormatter.allow(RegExp(r'[0-9 ]')), LengthLimitingTextInputFormatter(7)],
        textInputAction: TextInputAction.done,
        onSubmitted: onSubmitted == null ? null : (_) => onSubmitted!(),
        decoration: InputDecoration(
          labelText: recovery ? 'Recovery code' : 'Six-digit code',
          hintText: recovery ? 'XXXX-XXXX-XXXX' : '123 456',
          prefixIcon: Icon(recovery ? Icons.key_outlined : Icons.pin_outlined),
        ),
      );
}

/// Recovery codes, shown the one time they exist in the clear. The way on is
/// disabled until their owner says they have been kept somewhere.
class RecoveryCodesPanel extends StatefulWidget {
  final List<String> codes;
  final VoidCallback onDone;
  final String doneLabel;

  const RecoveryCodesPanel({super.key, required this.codes, required this.onDone, this.doneLabel = 'Continue'});

  @override
  State<RecoveryCodesPanel> createState() => _RecoveryCodesPanelState();
}

class _RecoveryCodesPanelState extends State<RecoveryCodesPanel> {
  bool _kept = false;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('Keep these recovery codes', style: text.titleMedium),
        const SizedBox(height: AppSpacing.sm),
        Text(
          'Each one signs you in once if you lose your phone. They are shown only now: '
          'print them or keep them in a password manager, not beside your password.',
          style: text.bodyMedium,
        ),
        const SizedBox(height: AppSpacing.lg),
        Container(
          padding: const EdgeInsets.all(AppSpacing.lg),
          decoration: BoxDecoration(color: cs.surfaceContainerHighest, borderRadius: AppRadius.chip),
          child: Wrap(
            spacing: AppSpacing.xl,
            runSpacing: AppSpacing.sm,
            children: [
              for (final code in widget.codes)
                SelectableText(code, style: text.bodyLarge?.copyWith(fontFamily: 'monospace')),
            ],
          ),
        ),
        const SizedBox(height: AppSpacing.sm),
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: _CopyButton(text: widget.codes.join('\n'), label: 'Copy all'),
        ),
        CheckboxListTile(
          key: const Key('recovery-kept'),
          contentPadding: EdgeInsets.zero,
          controlAffinity: ListTileControlAffinity.leading,
          value: _kept,
          onChanged: (v) => setState(() => _kept = v ?? false),
          title: const Text('I have kept these somewhere safe'),
        ),
        FilledButton(
          key: const Key('recovery-done'),
          onPressed: _kept ? widget.onDone : null,
          child: Text(widget.doneLabel),
        ),
      ],
    );
  }
}
