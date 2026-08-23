package com.karr.key.desktop.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.karr.key.core.KarrTrace

/** One captured trace line, with a stable id for list keys and a wall-clock stamp. */
data class TraceLine(
    val id: Long,
    val epochMillis: Long,
    val level: KarrTrace.Level,
    val tag: String,
    val message: String,
)

/**
 * In-memory sink for [KarrTrace] output that backs the desktop Terminal panel.
 *
 * On Android the same trace stream goes to `adb logcat`; the desktop host has no
 * logcat, so it captures the stream here and renders it in-app. The buffer is
 * bounded (a ring), thread-safe (BLE callbacks and coroutines on many threads
 * call [append]), and exposes a Compose-observable [revision] so the panel
 * recomposes as lines arrive.
 *
 * Key material never reaches this buffer: [KarrTrace.describeOutgoing] already
 * redacts the PROVIDE_AUTH response at the single wire choke point, upstream of
 * every sink, so nothing here has to re-scrub it.
 */
class TraceLog(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lock = Any()
    private val backing = ArrayDeque<TraceLine>(capacity.coerceAtMost(1_024))
    private var nextId = 0L

    // A monotonic counter written under [lock] on every mutation. Compose reads
    // it during composition, so any append/clear schedules a recomposition; the
    // panel then pulls a fresh [snapshot]. Snapshot state is safe to write from
    // background threads.
    var revision by mutableIntStateOf(0)
        private set

    fun append(level: KarrTrace.Level, tag: String, message: String) {
        synchronized(lock) {
            backing.addLast(
                TraceLine(
                    id = nextId++,
                    epochMillis = System.currentTimeMillis(),
                    level = level,
                    tag = tag,
                    message = message,
                ),
            )
            while (backing.size > capacity) {
                backing.removeFirst()
            }
            revision++
        }
    }

    fun snapshot(): List<TraceLine> = synchronized(lock) { backing.toList() }

    fun clear() {
        synchronized(lock) {
            backing.clear()
            revision++
        }
    }

    /**
     * A [KarrTrace.Sink] that funnels into this buffer. When [mirrorToStdout] is
     * set, lines are also echoed to the process console, matching the "follow a
     * live session without touching the device" intent of the Android logcat path.
     */
    fun asSink(mirrorToStdout: Boolean = true): KarrTrace.Sink =
        KarrTrace.Sink { level, tag, message ->
            append(level, tag, message)
            if (mirrorToStdout) {
                val stream = if (level == KarrTrace.Level.WARN) System.err else System.out
                stream.println("$tag $message")
            }
        }

    private companion object {
        const val DEFAULT_CAPACITY = 5_000
    }
}

/** The trace channels [KarrTrace] emits, for the Terminal's per-tag filter chips. */
enum class TraceChannel(val tag: String, val label: String) {
    BLE(KarrTrace.BLE, "Ble"),
    CMD(KarrTrace.CMD, "Cmd"),
    AUTH(KarrTrace.AUTH, "Auth"),
    PROTO(KarrTrace.PROTO, "Proto"),
    POLICY(KarrTrace.POLICY, "Policy"),
    SWEEP(KarrTrace.SWEEP, "Sweep"),
    MAP(KarrTrace.MAP, "Map"),
    COMPAT(KarrTrace.COMPAT, "Compat"),
}
