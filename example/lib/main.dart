import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'package:fingerprint_reader/fingerprint_reader.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const FingerprintExampleApp());
}

class FingerprintExampleApp extends StatelessWidget {
  const FingerprintExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Fingerprint Reader Example',
      theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.indigo),
      home: const HomePage(),
      debugShowCheckedModeBanner: false,
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final reader = FingerprintReader();

  List<Map<String, dynamic>> devices = [];
  String? selectedDeviceId;

  String mode = 'iso19794_2';
  final modes = const ['image', 'iso19794_2', 'ansi378', 'miaxis'];

  final timeoutCtrl = TextEditingController(text: '8000');

  StreamSubscription<Map<String, dynamic>>? _statusSub;
  final List<String> logs = [];

  // Kết quả capture
  Uint8List? imageBytes; // nếu mode=image
  Uint8List? templateBytes; // nếu mode=template
  int? quality;

  @override
  void initState() {
    super.initState();
    _listenStatus();
    _initVersionLog();
  }

  Future<void> _initVersionLog() async {
    final ver = await reader.getPlatformVersion();
    _log('Platform: $ver');
  }

  void _listenStatus() {
    _statusSub = reader.statusStream.listen(
      (e) {
        final state = e['state'];
        final q = e['quality'];
        final msg = e['message'];
        _log(
          'status → $state'
          '${q != null ? ' | q=$q' : ''}'
          '${msg != null ? ' | $msg' : ''}',
        );
        if (mounted) setState(() {});
      },
      onError: (err) {
        _log('status error: $err');
      },
    );
  }

  @override
  void dispose() {
    _statusSub?.cancel();
    timeoutCtrl.dispose();
    super.dispose();
  }

  void _log(String s) {
    setState(() {
      logs.insert(0, '[${TimeOfDay.now().format(context)}] $s');
    });
  }

  Future<void> _listDevices() async {
    try {
      final list = await reader.listDevices();
      setState(() {
        devices = list;
        selectedDeviceId =
            devices.isNotEmpty ? devices.first['id'] as String? : null;
      });
      _log('Found ${list.length} device(s)');
    } catch (e) {
      _log('listDevices error: $e');
    }
  }

  Future<void> _open() async {
    try {
      final ok = await reader.open(deviceId: selectedDeviceId);
      _log(ok ? 'Open OK' : 'Open FAILED');
    } catch (e) {
      _log('open error: $e');
      print('open error: $e');
    }
  }

  Future<void> _close() async {
    try {
      await reader.close();
      _log('Closed');
    } catch (e) {
      _log('close error: $e');
    }
  }

  Future<void> _cancel() async {
    try {
      await reader.cancel();
      _log('Cancelled');
    } catch (e) {
      _log('cancel error: $e');
    }
  }

  Future<void> _capture() async {
    setState(() {
      imageBytes = null;
      templateBytes = null;
      quality = null;
    });
    int? timeoutMs;
    final t = int.tryParse(timeoutCtrl.text.trim());
    if (t != null && t > 0) timeoutMs = t;

    try {
      final res = await reader.capture(mode: mode, timeoutMs: timeoutMs);
      final bytes = Uint8List.fromList((res['bytes'] as List).cast<int>());
      final q = res['quality'] as int?;
      quality = q;

      if (mode == 'image') {
        imageBytes = bytes;
        _log(
          'Capture image (${bytes.length} bytes)'
          '${q != null ? ', q=$q' : ''}',
        );
      } else {
        templateBytes = bytes;
        _log(
          'Capture template (${bytes.length} bytes, mode=$mode)'
          '${q != null ? ', q=$q' : ''}',
        );
      }
      if (mounted) setState(() {});
    } catch (e) {
      _log('capture error: $e');
    }
  }

  String _hexPreview(Uint8List data, {int max = 32}) {
    final sb = StringBuffer();
    final len = data.length < max ? data.length : max;
    for (var i = 0; i < len; i++) {
      sb.write(data[i].toRadixString(16).padLeft(2, '0'));
      if (i != len - 1) sb.write(' ');
    }
    if (data.length > max) sb.write(' …');
    return sb.toString();
  }

  Future<void> _copyHex() async {
    if (templateBytes == null) return;
    final hex = _hexPreview(templateBytes!, max: templateBytes!.length);
    await Clipboard.setData(ClipboardData(text: hex));
    if (mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Copied HEX to clipboard')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final isTemplate = mode != 'image';

    return Scaffold(
      appBar: AppBar(
        title: const Text('Fingerprint Reader Example'),
        actions: [
          IconButton(
            tooltip: 'List devices',
            onPressed: _listDevices,
            icon: const Icon(Icons.usb),
          ),
          IconButton(
            tooltip: 'Open',
            onPressed: _open,
            icon: const Icon(Icons.link),
          ),
          IconButton(
            tooltip: 'Close',
            onPressed: _close,
            icon: const Icon(Icons.link_off),
          ),
        ],
      ),
      body: Column(
        children: [
          _buildTopControls(isTemplate),
          const Divider(height: 1),
          Expanded(
            child: Row(
              children: [
                Expanded(child: _buildResultPane(isTemplate)),
                const VerticalDivider(width: 1),
                Expanded(child: _buildLogPane()),
              ],
            ),
          ),
        ],
      ),
      // floatingActionButton: _buildFABs(),
    );
  }

  Widget _buildTopControls(bool isTemplate) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 0),
      child: Wrap(
        crossAxisAlignment: WrapCrossAlignment.center,
        spacing: 12,
        runSpacing: 8,
        children: [
          // Device dropdown
          ConstrainedBox(
            constraints: const BoxConstraints(minWidth: 220, maxWidth: 340),
            child: DropdownButtonFormField<String>(
              value: selectedDeviceId,
              items: devices
                  .map(
                    (d) => DropdownMenuItem<String>(
                      value: d['id'] as String?,
                      child: Text('${d['name']}  (${d['type']})'),
                    ),
                  )
                  .toList(),
              onChanged: (v) => setState(() => selectedDeviceId = v),
              decoration: const InputDecoration(
                labelText: 'Thiết bị',
                border: OutlineInputBorder(),
                contentPadding: EdgeInsets.symmetric(horizontal: 12),
              ),
            ),
          ),

          // Mode dropdown
          ConstrainedBox(
            constraints: const BoxConstraints(minWidth: 180),
            child: DropdownButtonFormField<String>(
              value: mode,
              items: modes
                  .map(
                    (m) => DropdownMenuItem<String>(value: m, child: Text(m)),
                  )
                  .toList(),
              onChanged: (v) => setState(() => mode = v ?? mode),
              decoration: const InputDecoration(
                labelText: 'Mode',
                border: OutlineInputBorder(),
                contentPadding: EdgeInsets.symmetric(horizontal: 12),
              ),
            ),
          ),

          // Timeout
          SizedBox(
            width: 140,
            child: TextField(
              controller: timeoutCtrl,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: 'Timeout (ms)',
                border: OutlineInputBorder(),
                contentPadding: EdgeInsets.symmetric(horizontal: 12),
              ),
            ),
          ),

          // Buttons
          FilledButton.icon(
            onPressed: _capture,
            icon: const Icon(Icons.fingerprint),
            label: const Text('Capture'),
          ),
          OutlinedButton.icon(
            onPressed: _cancel,
            icon: const Icon(Icons.cancel),
            label: const Text('Cancel'),
          ),
          if (isTemplate && templateBytes != null)
            OutlinedButton.icon(
              onPressed: _copyHex,
              icon: const Icon(Icons.copy),
              label: const Text('Copy HEX'),
            ),
        ],
      ),
    );
  }

  Widget _buildResultPane(bool isTemplate) {
    final theme = Theme.of(context);

    if (mode == 'image') {
      return Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Kết quả (IMAGE)', style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            Expanded(
              child: Container(
                decoration: BoxDecoration(
                  border: Border.all(color: theme.dividerColor),
                  borderRadius: BorderRadius.circular(12),
                ),
                clipBehavior: Clip.antiAlias,
                child: imageBytes == null
                    ? const Center(
                        child: Text('Chưa có ảnh — hãy nhấn Capture'),
                      )
                    : InteractiveViewer(
                        child: Image.memory(imageBytes!, fit: BoxFit.contain),
                      ),
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Text('Bytes: ${imageBytes?.length ?? 0}'),
                const SizedBox(width: 16),
                Text('Quality: ${quality ?? '-'}'),
              ],
            ),
          ],
        ),
      );
    } else {
      final hex =
          templateBytes == null ? '' : _hexPreview(templateBytes!, max: 64);
      return Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'Kết quả (TEMPLATE: $mode)',
              style: theme.textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                border: Border.all(color: theme.dividerColor),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Length: ${templateBytes?.length ?? 0} bytes'),
                  const SizedBox(height: 4),
                  Text('Quality: ${quality ?? '-'}'),
                  const SizedBox(height: 8),
                  Text(
                    'HEX preview (64B):\n$hex',
                    style: const TextStyle(fontFamily: 'monospace'),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 8),
            const Text('Gợi ý: gửi bytes này lên BE để verify/match.'),
          ],
        ),
      );
    }
  }

  Widget _buildLogPane() {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.all(12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Logs', style: theme.textTheme.titleMedium),
          const SizedBox(height: 8),
          Expanded(
            child: Container(
              decoration: BoxDecoration(
                border: Border.all(color: theme.dividerColor),
                borderRadius: BorderRadius.circular(12),
              ),
              child: logs.isEmpty
                  ? const Center(child: Text('Chưa có log'))
                  : Scrollbar(
                      child: ListView.separated(
                        reverse: true,
                        padding: const EdgeInsets.all(8),
                        itemCount: logs.length,
                        separatorBuilder: (_, __) => const Divider(height: 8),
                        itemBuilder: (_, i) => Text(
                          logs[i],
                          style: const TextStyle(fontFamily: 'monospace'),
                        ),
                      ),
                    ),
            ),
          ),
        ],
      ),
    );
  }
}
