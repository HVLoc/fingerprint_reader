import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'fingerprint_reader_platform_interface.dart';

/// Implementation dùng MethodChannel + EventChannel
class MethodChannelFingerprintReader extends FingerprintReaderPlatform {
  /// Method channel để gọi hàm native
  @visibleForTesting
  final methodChannel = const MethodChannel('fingerprint_reader/methods');

  // /// Event channel để nhận stream trạng thái
  // final _eventChannel = const EventChannel('fingerprint_reader/events');

  Stream<Map<String, dynamic>>? _status$;

  // ========= Implement API =========

  @override
  Future<String?> getPlatformVersion() async {
    return methodChannel.invokeMethod<String>('getPlatformVersion');
  }

  @override
  Future<List<Map<String, dynamic>>> listDevices() async {
    final list = await methodChannel
            .invokeListMethod<Map<dynamic, dynamic>>('listDevices') ??
        <Map<dynamic, dynamic>>[];
    return list
        .map((e) => Map<String, dynamic>.from(e))
        .toList(growable: false);
  }

  @override
  Future<bool> open({String? deviceId}) async {
    final ok = await methodChannel.invokeMethod<bool>('open', {
      'deviceId': deviceId,
    });
    return ok ?? false;
    // Native nên trả false nếu chưa có permission/chuẩn bị xong
  }

  @override
  Future<void> close() {
    return methodChannel.invokeMethod<void>('close');
  }

  @override
  Future<Map<String, dynamic>> capture({
    String mode = 'iso19794_2',
    int? timeoutMs,
  }) async {
    final res = await methodChannel.invokeMapMethod<dynamic, dynamic>(
      'capture',
      {
        'mode': mode,
        'timeoutMs': timeoutMs,
      },
    );
    // Kỳ vọng native trả về:
    // { mode: String, bytes: List<int>, quality: int? }
    return Map<String, dynamic>.from(res ?? const {});
  }

  @override
  Future<void> cancel() {
    return methodChannel.invokeMethod<void>('cancel');
  }

  // @override
  // Stream<Map<String, dynamic>> get statusStream {
  //   _status$ ??= _eventChannel.receiveBroadcastStream().map((e) {
  //     // Kỳ vọng native gửi: {state:String, quality:int?, message:String?}
  //     if (e is Map) {
  //       return Map<String, dynamic>.from(e);
  //     }
  //     // Dự phòng: gói e vào message
  //     return <String, dynamic>{'state': 'unknown', 'message': e?.toString()};
  //   }).asBroadcastStream();
  //   return _status$!;
  // }
}
