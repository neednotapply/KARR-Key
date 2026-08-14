package com.karr.key.core

import android.util.Log

/**
 * Debug-only structured trace for the live BLE / protocol / authentication
 * pipeline. It exists so a connected developer can follow a real-hardware
 * session over `adb logcat` without touching the phone.
 *
 * Safety rules, mirroring the project invariant that no key material may appear
 * in source, tests, logs, or release artifacts:
 *  - Disabled by default. Only [KarrApplication] enables it, and only for a
 *    debuggable build, so nothing is ever emitted from a release APK.
 *  - Secret bytes are never logged. The `PROVIDE_AUTH` response payload is
 *    redacted at the single wire choke point by [describeOutgoing], defence in
 *    depth against any caller, matching the in-memory zeroing the session
 *    already performs on that payload.
 *  - Unit-under-test identifiers (serial / MAC) are logged deliberately to
 *    correlate an authorised device; acceptable because this never ships.
 *
 * Filter in the field with, for example:
 *   adb logcat -s KARR/Ble KARR/Cmd KARR/Auth KARR/Proto KARR/Policy
 */
object KarrTrace {
    @Volatile
    var enabled: Boolean = false

    const val BLE = "KARR/Ble"
    const val CMD = "KARR/Cmd"
    const val AUTH = "KARR/Auth"
    const val PROTO = "KARR/Proto"
    const val POLICY = "KARR/Policy"

    /** Command code for `PROVIDE_AUTH`; duplicated here to keep [core] free of a
     * protocol dependency. Kept in sync with `QtCommand.PROVIDE_AUTH`. */
    private const val PROVIDE_AUTH_CODE = 15
    private const val MARKER_ORIGINAL = 0xAA
    private const val MARKER_COUNTER = 0xBB

    /** Lazily builds the message only when tracing is on. */
    inline fun log(tag: String, message: () -> String) {
        if (enabled) {
            Log.d(tag, message())
        }
    }

    fun warn(tag: String, message: String) {
        if (enabled) {
            Log.w(tag, message)
        }
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"

    /** Uppercase, space-separated hex, e.g. `AA 00 02 18 0A DC`. Format-free so
     * it stays locale-independent and cheap. */
    fun hex(bytes: ByteArray): String {
        if (bytes.isEmpty()) {
            return ""
        }
        val builder = StringBuilder(bytes.size * 3)
        bytes.forEachIndexed { index, byte ->
            if (index > 0) {
                builder.append(' ')
            }
            val value = byte.toInt() and 0xFF
            builder.append(HEX_DIGITS[value ushr 4])
            builder.append(HEX_DIGITS[value and 0x0F])
        }
        return builder.toString()
    }

    /**
     * Describes an outgoing wire packet for the trace, redacting the
     * `PROVIDE_AUTH` response so its bytes never reach the log regardless of the
     * calling path.
     */
    fun describeOutgoing(packet: ByteArray): String {
        if (packet.isEmpty()) {
            return "(empty)"
        }
        val commandCode = when (packet[0].toInt() and 0xFF) {
            MARKER_ORIGINAL -> packet.getOrNull(3)
            MARKER_COUNTER -> packet.getOrNull(4)
            else -> null
        }?.toInt()?.and(0xFF)
        if (commandCode == PROVIDE_AUTH_CODE) {
            return "PROVIDE_AUTH <redacted ${packet.size} bytes>"
        }
        return hex(packet)
    }
}
