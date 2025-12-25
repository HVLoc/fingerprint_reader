package vn.lochv.fingerprint_reader_example

interface FingerprintBackend {
    fun listDevices(): List<Map<String, String>>
    fun open(deviceId: String?, onOk: () -> Unit, onErr: (String, String?) -> Unit)
    fun close()
    fun cancel()
    fun capture(
        mode: String,
        finger: String,
        timeoutMs: Int?,
        qualityThreshold: Int,
        onOk: (ByteArray, Int) -> Unit,
        onErr: (String, String?) -> Unit
    )
}