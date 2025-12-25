package vn.lochv.fingerprint_reader_example

interface FingerprintReaderBackend {
    fun open(deviceId: String?, onOk: () -> Unit, onErr: (String, String?) -> Unit)
    fun close()
    fun cancel()
    fun capture(
        mode: String,
        timeoutMs: Int?,
        onOk: (ByteArray, Int?) -> Unit,
        onErr: (String, String?) -> Unit
    )
}
