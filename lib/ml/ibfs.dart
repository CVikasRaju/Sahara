import 'dart:convert';
import 'dart:typed_data';

import 'languages.dart';

/// iTantra Binary Framing Spec (iBFS-v1) — docs/NETWORK_PROTOCOL.md.
///
/// Wire layout (all big-endian):
///   Byte 0-1    Magic 0x49 0x54 ("IT")
///   Byte 2      [Version:4][Type:4]
///   Byte 3      [Priority:4][Lang:4]
///   Byte 4-7    Sequence ID (uint32)
///   Byte 8-9    Payload length N (uint16, <= 512)
///   Byte 10..   Payload: ALWAYS begins with the §4 flags byte
///   ...(10+N)..(11+N)  CRC-16-CCITT over header + payload
///
/// Total overhead: 12 header + 2 CRC = 14 bytes, regardless of payload.
class IbfCodec {
  IbfCodec._();

  // ── Constants ─────────────────────────────────────────────────────
  static const int magic0 = 0x49; // 'I'
  static const int magic1 = 0x54; // 'T'
  static const int headerLen = 10;
  static const int crcLen = 2;
  static const int totalOverhead = headerLen + crcLen; // 12 bytes
  static const int maxPayloadBytes = 512;
}

/// Packet type identifiers (NETWORK_PROTOCOL.md §2, byte 2 low nibble).
enum PacketType {
  pttVoice(0x1),
  silentSos(0x2),
  ack(0x3),
  storeForward(0x4);

  const PacketType(this.value);
  final int value;
}

/// Priority levels (NETWORK_PROTOCOL.md §2, byte 3 high nibble).
enum Priority {
  routine(0x0),
  high(0x1),
  emergency(0xF);

  const Priority(this.value);
  final int value;
}

/// Extended payload flags byte (NETWORK_PROTOCOL.md §4).
class PayloadFlags {
  final bool hasGps;
  final bool hasSourceLang;

  const PayloadFlags({this.hasGps = false, this.hasSourceLang = false});

  int toByte() {
    int b = 0;
    if (hasGps) b |= 0x80;
    if (hasSourceLang) b |= 0x40;
    return b;
  }

  static PayloadFlags fromByte(int b) {
    return PayloadFlags(
      hasGps: (b & 0x80) != 0,
      hasSourceLang: (b & 0x40) != 0,
    );
  }
}

/// A fully decoded iTantra packet.
class IbfPacket {
  final PacketType type;
  final Priority priority;
  final Lang language;
  final int sequenceId;
  final String text;
  final PayloadFlags flags;
  final double? latitude;
  final double? longitude;
  final Lang? sourceLang;
  final int? measuredTransferMs;

  const IbfPacket({
    required this.type,
    required this.priority,
    required this.language,
    required this.sequenceId,
    required this.text,
    this.flags = const PayloadFlags(),
    this.latitude,
    this.longitude,
    this.sourceLang,
    this.measuredTransferMs,
  });
}

/// Thrown when a received frame fails validation.
class IbfDecodeError implements Exception {
  final String reason;
  const IbfDecodeError(this.reason);

  @override
  String toString() => 'IbfDecodeError: $reason';
}

/// ── Encoder ──────────────────────────────────────────────────────

/// Encode an [IbfPacket] into its wire-format bytes.
///
/// The flags byte is written *unconditionally* — this is a deliberate fix:
/// sniffing bit 7 is ambiguous because Devanagari/Tamil/etc. UTF-8 lead bytes
/// share those bits, which would silently garble received text.
Uint8List encodeIbfs(IbfPacket packet) {
  // Build the UTF-8 payload.
  final textBytes = utf8.encode(packet.text);
  if (textBytes.length > IbfCodec.maxPayloadBytes) {
    throw ArgumentError(
      'Payload ${textBytes.length} bytes exceeds max ${IbfCodec.maxPayloadBytes}',
    );
  }

  // Build extended payload bytes.
  final gpsBytes = _encodeGps(packet.latitude, packet.longitude);
  final srcLangByte = packet.flags.hasSourceLang && packet.sourceLang != null
      ? [packet.sourceLang!.wireId & 0x0F]
      : <int>[];

  // Flags byte (always present).
  final flagsByte = packet.flags.toByte();

  // Assemble: flags + gps + srcLang + text
  final payload = [
    flagsByte,
    ...gpsBytes,
    ...srcLangByte,
    ...textBytes,
  ];

  final payloadLen = payload.length;
  if (payloadLen > IbfCodec.maxPayloadBytes) {
    throw ArgumentError('Assembled payload exceeds max');
  }

  // Header
  final buf = ByteData(IbfCodec.headerLen + payloadLen + IbfCodec.crcLen);

  // Byte 0-1: Magic
  buf.setUint8(0, IbfCodec.magic0);
  buf.setUint8(1, IbfCodec.magic1);

  // Byte 2: [Version:4][Type:4]
  buf.setUint8(2, (0x1 << 4) | (packet.type.value & 0x0F));

  // Byte 3: [Priority:4][Lang:4]
  buf.setUint8(3, ((packet.priority.value & 0x0F) << 4) | (packet.language.wireId & 0x0F));

  // Byte 4-7: Sequence ID (uint32 big-endian)
  buf.setUint32(4, packet.sequenceId, Endian.big);

  // Byte 8-9: Payload length (uint16 big-endian)
  buf.setUint16(8, payloadLen, Endian.big);

  // Byte 10..: Payload
  final headerEnd = IbfCodec.headerLen;
  for (var i = 0; i < payloadLen; i++) {
    buf.setUint8(headerEnd + i, payload[i]);
  }

  // CRC-16-CCITT over header + payload
  final crcOffset = headerEnd + payloadLen;
  final crc = crc16Ccitt(buf.buffer.asUint8List(0, crcOffset));
  buf.setUint16(crcOffset, crc, Endian.big);

  return buf.buffer.asUint8List(buf.offsetInBytes, buf.lengthInBytes);
}

/// ── Decoder ──────────────────────────────────────────────────────

/// Decode raw [bytes] into an [IbfPacket]. Throws [IbfDecodeError] on failure.
IbfPacket decodeIbfs(Uint8List bytes) {
  if (bytes.length < IbfCodec.headerLen + IbfCodec.crcLen) {
    throw IbfDecodeError(
      'Frame too short: ${bytes.length} bytes (min ${IbfCodec.headerLen + IbfCodec.crcLen})',
    );
  }

  // Magic check
  if (bytes[0] != IbfCodec.magic0 || bytes[1] != IbfCodec.magic1) {
    throw IbfDecodeError(
      'Bad magic: 0x${bytes[0].toRadixString(16)}${bytes[1].toRadixString(16)} (expected 0x4954)',
    );
  }

  // CRC-16 check.
  // NOTE: ByteData.sublistView honours bytes.offsetInBytes. Using
  // ByteData.view(bytes.buffer) here read from the start of the *backing*
  // buffer, so any inbound frame that arrived as a view into a larger buffer
  // (which is exactly what BLE chunk reassembly produces) decoded garbage.
  final crcOffset = bytes.length - IbfCodec.crcLen;
  final view = ByteData.sublistView(bytes);
  final expectedCrc = view.getUint16(crcOffset, Endian.big);
  final computedCrc = crc16Ccitt(bytes.sublist(0, crcOffset));
  if (expectedCrc != computedCrc) {
    throw IbfDecodeError(
      'CRC mismatch: expected 0x${expectedCrc.toRadixString(16)}, computed 0x${computedCrc.toRadixString(16)}',
    );
  }

  // Parse header fields
  final b2 = bytes[2];
  final typeVal = b2 & 0x0F;

  final b3 = bytes[3];
  final priorityVal = (b3 >> 4) & 0x0F;
  final langId = b3 & 0x0F;

  final sequenceId = view.getUint32(4, Endian.big);
  final payloadLen = view.getUint16(8, Endian.big);

  // Validate payload length
  final availablePayload = crcOffset - IbfCodec.headerLen;
  if (payloadLen > availablePayload) {
    throw IbfDecodeError(
      'Declared payload $payloadLen exceeds available $availablePayload',
    );
  }

  // Parse language
  final lang = langByWireId(langId);
  if (lang == null) {
    throw IbfDecodeError('Unknown language wire ID: $langId');
  }

  // Parse packet type
  final type = PacketType.values.firstWhere(
    (t) => t.value == typeVal,
    orElse: () => PacketType.pttVoice,
  );

  // Parse priority
  final priority = Priority.values.firstWhere(
    (p) => p.value == priorityVal,
    orElse: () => Priority.routine,
  );

  // Parse payload: flags byte is ALWAYS first
  if (payloadLen < 1) {
    throw IbfDecodeError('Payload too short (need at least flags byte)');
  }

  final payloadStart = IbfCodec.headerLen;
  final flagsByte = bytes[payloadStart];
  final flags = PayloadFlags.fromByte(flagsByte);

  var cursor = payloadStart + 1; // past flags byte

  double? lat;
  double? lon;
  if (flags.hasGps) {
    if (cursor + 8 > crcOffset) {
      throw IbfDecodeError('GPS flag set but not enough payload bytes');
    }
    lat = view.getFloat32(cursor, Endian.big);
    lon = view.getFloat32(cursor + 4, Endian.big);
    cursor += 8;
  }

  Lang? sourceLang;
  if (flags.hasSourceLang) {
    if (cursor + 1 > crcOffset) {
      throw IbfDecodeError('SourceLang flag set but not enough payload bytes');
    }
    sourceLang = langByWireId(bytes[cursor] & 0x0F);
    cursor += 1;
  }

  // Remaining bytes are the UTF-8 text
  final textBytes = bytes.sublist(cursor, payloadStart + payloadLen);
  final text = utf8.decode(textBytes, allowMalformed: true);

  return IbfPacket(
    type: type,
    priority: priority,
    language: lang,
    sequenceId: sequenceId,
    text: text,
    flags: flags,
    latitude: lat,
    longitude: lon,
    sourceLang: sourceLang,
  );
}

/// ── GPS Helpers ──────────────────────────────────────────────────

Uint8List _encodeGps(double? lat, double? lon) {
  if (lat == null || lon == null) return Uint8List(0);
  final buf = ByteData(8);
  buf.setFloat32(0, lat, Endian.big);
  buf.setFloat32(4, lon, Endian.big);
  return buf.buffer.asUint8List();
}

/// ── CRC-16-CCITT ─────────────────────────────────────────────────

/// CRC-16-CCITT (polynomial 0x1021, init 0xFFFF) — NETWORK_PROTOCOL.md §5.
int crc16Ccitt(List<int> data) {
  var crc = 0xFFFF;
  for (final byte in data) {
    crc ^= byte << 8;
    for (var i = 0; i < 8; i++) {
      if ((crc & 0x8000) != 0) {
        crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
      } else {
        crc = (crc << 1) & 0xFFFF;
      }
    }
  }
  return crc;
}

/// ── Distress Detection ───────────────────────────────────────────

/// Per-language distress keyword lists (ADDITIONAL_FEATURES.md §1).
/// These are run against STT text output — NOT against audio.
const Map<String, List<String>> _distressKeywords = {
  'hi': ['मदद', 'बचाओ', 'घायल', 'फंसे', 'आग', 'emergency', 'help', 'trapped', 'injured', 'fire'],
  'gu': ['મદદ', 'બચાવો', 'ઘાયલ', 'ફસાયા', 'આગ', 'emergency', 'help', 'trapped'],
  'mr': ['मदत', 'वाचवा', 'जखमी', 'अडकले', 'आग', 'emergency', 'help', 'trapped'],
  'kn': ['ಸಹಾಯ', 'ರಕ್ಷಿಸಿ', 'ಗಾಯಗೊಂಡ', 'ಸಿಕ್ಕಿಬಿದ್ದ', 'ಬೆಂಕಿ', 'emergency', 'help', 'trapped'],
  'ta': ['உதவி', 'காப்பாற்று', 'காயமடைந்த', 'சிக்கிய', 'தீ', 'emergency', 'help', 'trapped'],
  'te': ['సహాయం', 'రక్షించండి', 'గాయపడిన', 'చిక్కుకున్న', 'మంట', 'emergency', 'help', 'trapped'],
  'ml': ['സഹായം', 'രക്ഷിക്കൂ', 'മുറിവേറ്റ', 'കുടുങ്ങിയ', 'തീ', 'emergency', 'help', 'trapped'],
  'or': ['ସାହାଯ୍ୟ', 'ବଞ୍ଚାଅ', 'ଆହତ', 'ଫସିଯାଇଛନ୍ତି', 'ଅଗ୍ନି', 'emergency', 'help', 'trapped'],
  'bn': ['সাহায্য', 'বাঁচাও', 'আহত', 'আটকে', 'আগুন', 'emergency', 'help', 'trapped'],
  'en': ['help', 'trapped', 'injured', 'fire', 'emergency', 'sos', 'danger'],
};

/// Returns `true` if the [text] contains distress keywords for the given
/// [langIso639] language code. Falls back to English keywords if the
/// language is not in the keyword map.
bool detectDistress(String text, String langIso639) {
  final lowerText = text.toLowerCase();
  final keywords = _distressKeywords[langIso639] ?? _distressKeywords['en']!;
  return keywords.any((kw) => lowerText.contains(kw.toLowerCase()));
}
