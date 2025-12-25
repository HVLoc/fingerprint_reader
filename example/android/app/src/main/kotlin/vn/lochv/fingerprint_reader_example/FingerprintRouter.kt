package vn.lochv.fingerprint_reader_example

import android.content.Context

class FingerprintRouter(
    private val ctx: Context,
    private val emit: (String, Int?, String?) -> Unit
) {

    private var active: FingerprintBackend? = null

    private val miaxis by lazy { MiaxisReader(ctx, emit) }
    private var trust: TrustFingerBackend? = null

    fun listDevices(): List<Map<String, String>> {
        val devices = mutableListOf<Map<String, String>>()

        // --- Miaxis ---
        try {
            devices.addAll(
                listOf(
                    mapOf(
                        "id" to "miaxis-0",
                        "name" to "Miaxis Fingerprint",
                        "backend" to "miaxis"
                    )
                )
            )
        } catch (_: Throwable) {}

        // --- TrustFinger ---
        try {
            if (trust == null) {
                trust = TrustFingerBackend(ctx, emit)
            }

            val tf = trust!!.trustFinger
            val list = tf.deviceList   // <-- SDK Aratek

            list.forEachIndexed { idx, desc ->
                devices.add(
                    mapOf(
                        "id" to "trustfinger-$idx",
                        "name" to desc,
                        "backend" to "trustfinger"
                    )
                )
            }
        } catch (e: UnsatisfiedLinkError) {
            emit("warning", null, "TrustFinger native lib not found")
        } catch (e: Throwable) {
            emit("warning", null, "TrustFinger error: ${e.message}")
        }

        return devices
    }

    fun open(deviceId: String?, onOk: () -> Unit, onErr: (String, String?) -> Unit) {
        active = (if (deviceId?.startsWith("miaxis") == true) miaxis else trust) as FingerprintBackend?
        active?.open(deviceId, onOk, onErr)
    }

    fun close() = active?.close()
    fun cancel() = active?.cancel()

    fun capture(
        mode: String,
        finger: String,
        timeoutMs: Int?,
        qualityThreshold: Int,
        onOk: (ByteArray, Int) -> Unit,
        onErr: (String, String?) -> Unit
    ) {
        active?.capture(mode, finger, timeoutMs, qualityThreshold, onOk, onErr)
            ?: onErr("NO_ACTIVE", "No active backend")
    }
}
