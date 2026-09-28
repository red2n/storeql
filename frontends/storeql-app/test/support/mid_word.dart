import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';

/// Whether [finder]'s text starts a new line inside a word (*Chargeb-acks*)
/// rather than at a space.
///
/// The test font draws every glyph 1em wide, so a title may take two lines in
/// a test that one line holds on a phone; what must never happen is a word cut
/// in two because the actions beside it left the title too little room.
bool breaksMidWord(WidgetTester tester, Finder finder) {
  final p = tester.renderObject<RenderParagraph>(finder);
  final text = p.text.toPlainText();
  double? lastTop;
  for (var i = 0; i < text.length; i++) {
    final boxes = p.getBoxesForSelection(
        TextSelection(baseOffset: i, extentOffset: i + 1));
    if (boxes.isEmpty) continue;
    final top = boxes.first.top;
    if (lastTop != null && top > lastTop + 0.5 && text[i - 1] != ' ') {
      return true;
    }
    lastTop = top;
  }
  return false;
}
