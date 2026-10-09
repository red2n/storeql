import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';

/// A file a person chose: its name and everything in it.
class PickedFileData {
  const PickedFileData(this.name, this.bytes);

  final String name;
  final Uint8List bytes;

  /// The name's extension without the leading dot, or null when it has none
  /// (a dotfile such as `.csv` has none either).
  String? get extension {
    final dot = name.lastIndexOf('.');
    return dot <= 0 || dot == name.length - 1 ? null : name.substring(dot + 1);
  }
}

/// Asks the person for one file of the given extensions (no leading dot) and
/// reads it whole; null when the dialog is dismissed or the file cannot be
/// read.
///
/// file_picker 13 no longer hands over bytes with the pick (`withData` is gone):
/// the file is read afterwards with `PlatformFile.readAsBytes()`, which can
/// throw. Before 13 a failed read left the bytes null and the screen did
/// nothing, so a failed read is null here too. A failure of the dialog itself
/// is not swallowed: it reaches the caller as it always did.
Future<PickedFileData?> pickFileWithBytes(
  List<String> allowedExtensions,
) async {
  final file = await FilePicker.pickFile(
    type: FileType.custom,
    allowedExtensions: allowedExtensions,
  );
  if (file == null) return null;
  try {
    return PickedFileData(file.name, await file.readAsBytes());
  } catch (_) {
    return null;
  }
}
