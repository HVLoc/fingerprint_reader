package vn.lochv.fingerprint_reader_example

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity(), EventChannel.StreamHandler {

    // ---- Channels ----
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel

    // ---- App/Activity context ----
    private val ctx: Context get() = this
    private val act: Activity get() = this

    // ---- EventSink (ép về main) ----
    @Volatile
    private var eventSink: EventChannel.EventSink? = null

    // ---- Router (Miaxis + TrustFinger) ----
    private lateinit var router: FingerprintRouter

    // ---- Main handler ----
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private inner class MainThreadEventSink(
        private val delegate: EventChannel.EventSink
    ) : EventChannel.EventSink {
        override fun success(event: Any?) = onMain { delegate.success(event) }
        override fun error(code: String, message: String?, details: Any?) =
            onMain { delegate.error(code, message, details) }
        override fun endOfStream() = onMain { delegate.endOfStream() }
    }

    private inner class MainThreadResult(
        private val delegate: MethodChannel.Result
    ) : MethodChannel.Result {
        override fun success(result: Any?) = onMain { delegate.success(result) }
        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) =
            onMain { delegate.error(errorCode, errorMessage, errorDetails) }
        override fun notImplemented() = onMain { delegate.notImplemented() }
    }

    // ---- USB permission (giữ nguyên, dùng chung) ----
    private val ACTION_USB_PERMISSION = "vn.lochv.fingerprint_reader.USB_PERMISSION"
    private var usbPermissionReceiver: BroadcastReceiver? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun requestUsbPermission(
        onGranted: () -> Unit,
        onDenied: (String) -> Unit
    ) {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usb.deviceList.values.toList()
        if (devices.isEmpty()) { onDenied("NO_USB_DEVICE"); return }

        val device = devices.first()
        if (usb.hasPermission(device)) { onGranted(); return }

        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACTION_USB_PERMISSION), flags
        )

        usbPermissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == ACTION_USB_PERMISSION) {
                    try { unregisterReceiver(this) } catch (_: Throwable) {}
                    usbPermissionReceiver = null
                    val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) onGranted() else onDenied("USB_PERMISSION_DENIED")
                }
            }
        }
        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
        usb.requestPermission(device, pi)
    }

    // ---- Emit status ----
    @MainThread
    private fun emitStatus(state: String, quality: Int? = null, message: String? = null) {
        val payload = mapOf(
            "state" to state,
            "quality" to quality,
            "message" to message
        )
        eventSink?.success(payload)
    }

    // ---- Lifecycle ----
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        methodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "fingerprint_reader/methods"
        )
        eventChannel = EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "fingerprint_reader/events"
        )

        eventChannel.setStreamHandler(this)

        // ✅ Router dùng cho cả 2 SDK
        router = FingerprintRouter(ctx, ::emitStatus)

        methodChannel.setMethodCallHandler { call: MethodCall, raw ->
            val result = MainThreadResult(raw)

            when (call.method) {

                "getPlatformVersion" ->
                    result.success("Android ${Build.VERSION.RELEASE}")

                "listDevices" ->
                    result.success(router.listDevices())

                "open" -> {
                    requestUsbPermission(
                        onGranted = {
                            router.open(
                                call.argument<String>("deviceId"),
                                { result.success(true) },
                                { c, m -> result.error(c, m, null) }
                            )
                        },
                        onDenied = { why ->
                            emitStatus("error", null, why)
                            result.error("USB_NO_PERMISSION", why, null)
                        }
                    )
                }

                "close" -> {
                    router.close()
                    emitStatus("idle", null, "closed")
                    result.success(null)
                }

                "cancel" -> {
                    router.cancel()
                    emitStatus("idle", null, "cancelled")
                    result.success(null)
                }

                "capture" -> {
                    router.capture(
                        call.argument<String>("mode") ?: "iso19794_2",
                        call.argument<String>("finger") ?: "RightThumb",
                        call.argument<Int>("timeoutMs"),
                        call.argument<Int>("qualityThreshold") ?: 50,
                        { bytes, q ->
                            emitStatus("done", q, null)
                            result.success(
                                mapOf(
                                    "bytes" to bytes.toList(),
                                    "quality" to q
                                )
                            )
                        },
                        { c, m ->
                            emitStatus("error", null, m)
                            result.error(c, m, null)
                        }
                    )
                }

                "getDeviceSerial" -> {
                    val serial = getSerialFromSystem()
                    result.success(serial)
                }

                else -> result.notImplemented()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            methodChannel.setMethodCallHandler(null)
            eventChannel.setStreamHandler(null)
        } catch (_: Throwable) {}

        try { usbPermissionReceiver?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        usbPermissionReceiver = null
        eventSink = null
    }

    // ---- EventChannel.StreamHandler ----
    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        eventSink = MainThreadEventSink(events)
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    fun getSerialFromSystem(): String {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            val get = c.getMethod("get", String::class.java)
            get.invoke(null, "ro.serialno") as String
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

}
