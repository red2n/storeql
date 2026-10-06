/// Size budget and the downscale/quality ladder used to shrink an owner-uploaded
/// product image before it goes over the wire.
///
/// Product images are stored as `BYTEA` rows in product-svc's Postgres (there is no
/// object store yet — see `V12__product_images.sql`), and the storefront fetches the
/// raw bytes per product. So every byte here is paid three times: upload bandwidth,
/// database size, and again on each catalog render. Compressing on the client is what
/// keeps that bill small; the server's own cap is only a backstop against clients that
/// skip this path.
///
/// The numbers below are deliberately conservative for a *photo*. Product shots are
/// rendered at card size on the catalog and roughly half-width on the product page, so
/// 1280px on the long edge still has detail to spare at 2x device pixel ratio, while
/// WebP q82 puts a typical shot at 90-150 KB.
library;

import 'dart:typed_data';

/// Hard ceiling for the bytes we upload. Anything the ladder cannot squeeze *strictly
/// under* this is rejected rather than sent.
///
/// Mirrors `ProductService.MAX_IMAGE_BYTES` in product-svc, which rejects anything at or
/// above the same number and is backed by a CHECK constraint on `product_images`. Keep
/// the two identical: this side exists so the owner never sees a rejection, not to
/// define the rule.
const int kProductImageMaxBytes = 256 * 1024;

/// Longest edge we ever keep. Sources smaller than this are never upscaled.
const int kProductImageMaxEdge = 1280;

/// One rung of the ladder: an edge cap plus an encoder quality (0-100).
///
/// Tried in order, largest output first, and the first rung landing under
/// [kProductImageMaxBytes] wins — so a normal photo stops at the first rung and only a
/// pathologically noisy one walks down to 640px.
typedef ImageAttempt = ({int maxEdge, int quality});

const List<ImageAttempt> kProductImageAttempts = [
  (maxEdge: 1280, quality: 82),
  (maxEdge: 1280, quality: 70),
  (maxEdge: 1024, quality: 65),
  (maxEdge: 800, quality: 60),
  (maxEdge: 640, quality: 55),
];

/// The result of [compressProductImage]: the bytes to upload and the MIME type to send
/// as `Content-Type`. The type is decided by what the platform could actually encode,
/// not by what we asked for, so it always matches the bytes.
typedef CompressedImage = ({Uint8List bytes, String contentType});

/// Thrown when the source cannot be decoded, or cannot be squeezed under the budget.
class ImageCompressException implements Exception {
  final String message;
  const ImageCompressException(this.message);
  @override
  String toString() => message;
}

/// Scales `width` x `height` down to fit inside a `maxEdge` box, preserving aspect
/// ratio. Never upscales, and never returns a zero dimension for a very thin source.
({int width, int height}) fitWithin(int width, int height, int maxEdge) {
  if (width <= 0 || height <= 0) {
    throw const ImageCompressException('Image has no pixels.');
  }
  if (width <= maxEdge && height <= maxEdge) return (width: width, height: height);
  final longest = width > height ? width : height;
  final scale = maxEdge / longest;
  return (
    width: (width * scale).round().clamp(1, maxEdge),
    height: (height * scale).round().clamp(1, maxEdge),
  );
}

/// Whether the source can be uploaded untouched.
///
/// Re-encoding is lossy, so a small PNG/WebP — a logo, packaging line art, a flat-colour
/// swatch — is passed through as-is rather than smeared by a lossy pass it does not need.
/// JPEG is deliberately *not* passed through even when small: re-encoding is what strips
/// EXIF, and a phone photo carries GPS coordinates we must not publish on a storefront.
bool canUploadUnchanged({
  required int byteLength,
  required int width,
  required int height,
  required String contentType,
}) =>
    byteLength < kProductImageMaxBytes &&
    width <= kProductImageMaxEdge &&
    height <= kProductImageMaxEdge &&
    (contentType == 'image/png' || contentType == 'image/webp');

/// Maps a picked file's extension to the MIME type product-svc accepts.
/// Anything unrecognised is treated as JPEG — the service validates it regardless.
String contentTypeForExtension(String? extension) =>
    switch ((extension ?? '').toLowerCase()) {
      'png' => 'image/png',
      'webp' => 'image/webp',
      _ => 'image/jpeg',
    };

/// "94 KB" — for user-facing confirmation of what was actually uploaded.
String formatBytes(int bytes) => '${(bytes / 1024).round()} KB';
