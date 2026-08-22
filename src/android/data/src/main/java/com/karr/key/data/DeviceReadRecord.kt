package com.karr.key.data

import java.time.Instant

/**
 * The latest decoded result of a single READ operation against one unit. One record exists per
 * (device MAC, [command]) pair — a fresh read of the same command overwrites the previous one.
 *
 * [command] and [unitModel] are stored as raw enum names (rather than the enums themselves) so a
 * value exported by one build still round-trips through a build that has since renamed or removed
 * that enum constant; display only relies on the human-readable [heading] and [lines].
 */
data class DeviceReadRecord(
    val command: String,
    val heading: String,
    val lines: List<String>,
    val unitModel: String?,
    val capturedUtc: Instant,
)

/**
 * Serializes a device's READ snapshot (a list of [DeviceReadRecord]) to and from a compact JSON
 * array. Hand-rolled to avoid pulling a JSON dependency into the otherwise dependency-light data
 * module, and to keep the logic pure so it is unit-testable on the plain JVM (Android's
 * `org.json` is stubbed in JVM unit tests). The output lives inside a single WiGLE CSV cell, so it
 * must stay on one line — the encoder escapes any control characters, including newlines.
 */
object DeviceReadCodec {
    fun encode(records: List<DeviceReadRecord>): String {
        val builder = StringBuilder()
        builder.append('[')
        records.forEachIndexed { index, record ->
            if (index > 0) builder.append(',')
            builder.append('{')
            builder.appendJsonField("command", record.command)
            builder.append(',')
            builder.appendJsonField("heading", record.heading)
            builder.append(',')
            builder.append("\"lines\":[")
            record.lines.forEachIndexed { lineIndex, line ->
                if (lineIndex > 0) builder.append(',')
                builder.appendJsonString(line)
            }
            builder.append(']')
            builder.append(',')
            builder.append("\"unitModel\":")
            if (record.unitModel == null) {
                builder.append("null")
            } else {
                builder.appendJsonString(record.unitModel)
            }
            builder.append(',')
            builder.appendJsonField("capturedUtc", record.capturedUtc.toString())
            builder.append('}')
        }
        builder.append(']')
        return builder.toString()
    }

    /** Serializes a plain list of strings as a JSON array (used for a record's decoded lines). */
    fun encodeStringList(values: List<String>): String {
        val builder = StringBuilder()
        builder.append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) builder.append(',')
            builder.appendJsonString(value)
        }
        builder.append(']')
        return builder.toString()
    }

    /** Inverse of [encodeStringList]; returns an empty list for blank or malformed input. */
    fun decodeStringList(json: String): List<String> = runCatching {
        (JsonParser(json).parse() as? List<*>).orEmpty().filterIsInstance<String>()
    }.getOrDefault(emptyList())

    /** Returns an empty list for blank or malformed input rather than throwing on a bad import. */
    fun decode(json: String): List<DeviceReadRecord> = runCatching {
        val value = JsonParser(json).parse()
        (value as? List<*>).orEmpty().mapNotNull { element ->
            val obj = element as? Map<*, *> ?: return@mapNotNull null
            val command = obj["command"] as? String ?: return@mapNotNull null
            val heading = obj["heading"] as? String ?: return@mapNotNull null
            val lines = (obj["lines"] as? List<*>).orEmpty().filterIsInstance<String>()
            val unitModel = obj["unitModel"] as? String
            val capturedUtc = (obj["capturedUtc"] as? String)
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            DeviceReadRecord(command, heading, lines, unitModel, capturedUtc)
        }
    }.getOrDefault(emptyList())

    private fun StringBuilder.appendJsonField(name: String, value: String) {
        appendJsonString(name)
        append(':')
        appendJsonString(value)
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
}

/**
 * Minimal recursive-descent JSON reader supporting exactly the value model the codec emits:
 * objects, arrays, strings, `null`, and numbers/booleans (parsed but unused). Not a general-purpose
 * parser — it is paired with [DeviceReadCodec.encode] and covered by round-trip unit tests.
 */
private class JsonParser(private val text: String) {
    private var index = 0

    fun parse(): Any? {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        return value
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        return when (val char = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            else -> if (char == '-' || char in '0'..'9') parseNumber() else error("Unexpected '$char'")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val result = LinkedHashMap<String, Any?>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return result
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            result[key] = parseValue()
            skipWhitespace()
            when (val char = next()) {
                ',' -> continue
                '}' -> break
                else -> error("Expected ',' or '}' but found '$char'")
            }
        }
        return result
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val result = mutableListOf<Any?>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return result
        }
        while (true) {
            result.add(parseValue())
            skipWhitespace()
            when (val char = next()) {
                ',' -> continue
                ']' -> break
                else -> error("Expected ',' or ']' but found '$char'")
            }
        }
        return result
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            when (val char = next()) {
                '"' -> break
                '\\' -> when (val escape = next()) {
                    '"' -> builder.append('"')
                    '\\' -> builder.append('\\')
                    '/' -> builder.append('/')
                    'n' -> builder.append('\n')
                    'r' -> builder.append('\r')
                    't' -> builder.append('\t')
                    'b' -> builder.append('\b')
                    'f' -> builder.append(0x0C.toChar())
                    'u' -> {
                        val hex = text.substring(index, index + 4)
                        index += 4
                        builder.append(hex.toInt(16).toChar())
                    }
                    else -> error("Invalid escape '\\$escape'")
                }
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }

    private fun parseBoolean(): Boolean =
        if (text.startsWith("true", index)) {
            index += 4
            true
        } else if (text.startsWith("false", index)) {
            index += 5
            false
        } else {
            error("Invalid literal")
        }

    private fun parseNull(): Any? {
        if (!text.startsWith("null", index)) error("Invalid literal")
        index += 4
        return null
    }

    private fun parseNumber(): Double {
        val start = index
        while (index < text.length && (text[index].isDigit() ||
                text[index] in "-+.eE")
        ) {
            index++
        }
        return text.substring(start, index).toDouble()
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun peek(): Char {
        if (index >= text.length) error("Unexpected end of input")
        return text[index]
    }

    private fun next(): Char {
        if (index >= text.length) error("Unexpected end of input")
        return text[index++]
    }

    private fun expect(char: Char) {
        val actual = next()
        if (actual != char) error("Expected '$char' but found '$actual'")
    }
}
