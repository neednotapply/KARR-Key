package com.karr.key.desktop.transport

import com.karr.key.core.KarrTrace
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * [RawBleTransport] backed by the Windows-native WinRT helper process
 * (`desktop/blebridge`). The JVM and the helper speak a line-delimited JSON
 * protocol over the helper's stdio:
 *
 * JVM -> helper (one JSON object per line):
 *   {"cmd":"startScan"} · {"cmd":"stopScan"} · {"cmd":"connect","address":".."}
 *   {"cmd":"disconnect"} · {"cmd":"write","data":"<hex>"} · {"cmd":"shutdown"}
 *
 * helper -> JVM:
 *   {"event":"scan","address":"..","name":"..","rssi":-60,"connectable":true,
 *    "services":["<uuid>"],"manufacturerData":["<hex>"]}
 *   {"event":"connected","address":".."} · {"event":"uartReady"}
 *   {"event":"notification","data":"<hex>"} · {"event":"disconnected","status":N}
 *   {"event":"error","message":".."}
 *
 * Hardware/helper validation is pending (requires the built helper + a unit); the
 * app defaults to the mock transport and selects this only when opted in.
 */
class WindowsBleTransport(
    command: List<String>,
) : RawBleTransport {
    private val _events = MutableSharedFlow<RawBleEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<RawBleEvent> = _events.asSharedFlow()

    private val process: Process = ProcessBuilder(command)
        .redirectErrorStream(false)
        .start()
    private val writer: Writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val writeLock = Any()

    init {
        Thread({ readLoop() }, "winble-reader").apply { isDaemon = true }.start()
        Thread({ errLoop() }, "winble-stderr").apply { isDaemon = true }.start()
    }

    private fun readLoop() {
        BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
            reader.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                runCatching { parseEvent(JSONObject(line)) }
                    .onFailure { KarrTrace.warn(KarrTrace.BLE, "helper line unparsed: $line") }
            }
        }
        _events.tryEmit(RawBleEvent.Disconnected(status = 0))
    }

    private fun errLoop() {
        BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { reader ->
            reader.lineSequence().forEach { line -> KarrTrace.warn(KarrTrace.BLE, "helper: $line") }
        }
    }

    private fun parseEvent(json: JSONObject) {
        when (json.optString("event")) {
            "scan" -> _events.tryEmit(
                RawBleEvent.ScanResult(
                    address = json.getString("address"),
                    name = json.optString("name").takeIf { it.isNotBlank() },
                    rssi = json.optInt("rssi", -100),
                    connectable = json.optBoolean("connectable", true),
                    serviceUuids = json.optJSONArray("services").toStringList(),
                    manufacturerData = json.optJSONArray("manufacturerData").toHexByteList(),
                ),
            )
            "connected" -> _events.tryEmit(RawBleEvent.Connected(json.getString("address")))
            "uartReady" -> _events.tryEmit(RawBleEvent.UartReady)
            "notification" -> _events.tryEmit(RawBleEvent.Notification(hexToBytes(json.getString("data"))))
            "disconnected" -> _events.tryEmit(RawBleEvent.Disconnected(json.optInt("status", 0)))
            "error" -> _events.tryEmit(RawBleEvent.Failure(json.optString("message", "Helper error.")))
        }
    }

    private fun send(json: JSONObject): Result<Unit> = runCatching {
        synchronized(writeLock) {
            writer.write(json.toString())
            writer.write("\n")
            writer.flush()
        }
    }

    override fun startScan(): Result<Unit> = send(JSONObject().put("cmd", "startScan"))
    override fun stopScan() { send(JSONObject().put("cmd", "stopScan")) }
    override fun connect(address: String): Result<Unit> =
        send(JSONObject().put("cmd", "connect").put("address", address))
    override fun disconnect() { send(JSONObject().put("cmd", "disconnect")) }
    override suspend fun write(bytes: ByteArray): Result<Unit> =
        send(JSONObject().put("cmd", "write").put("data", bytesToHex(bytes)))

    override fun shutdown() {
        send(JSONObject().put("cmd", "shutdown"))
        runCatching { writer.close() }
        process.destroy()
    }

    private companion object {
        fun JSONArray?.toStringList(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }

        fun JSONArray?.toHexByteList(): List<ByteArray> =
            if (this == null) emptyList() else (0 until length()).map { hexToBytes(getString(it)) }

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.filter { !it.isWhitespace() }
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
