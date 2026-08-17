package com.karr.key.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karr.key.R
import com.karr.key.ble.BleCommandExchange
import com.karr.key.ble.BleCommandExchangeResult
import com.karr.key.ble.BleController
import com.karr.key.ble.BleModeChangeExchange
import com.karr.key.core.KarrTrace
import com.karr.key.core.Session
import com.karr.key.data.CommandCompatStore
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtErrorCode
import com.karr.key.protocol.QtCommandLayer
import com.karr.key.protocol.QtCommandRisk
import com.karr.key.protocol.QtProtocolCodec
import com.karr.key.protocol.QtResponseDecoder
import com.karr.key.protocol.RecoveredDeviceAction
import com.karr.key.protocol.RecoveredWorkflowCatalog
import com.karr.key.protocol.dangerScore
import com.karr.key.protocol.profile
import kotlinx.coroutines.launch

/**
 * A raw, uncurated protocol console: it lists every [QtCommand], lets a tester attach a hex payload,
 * frames it as an original `0xAA` packet, sends it to the connected unit, and records what came
 * back. Unlike the Operations catalog it applies no role or model gating — the point is exactly to
 * probe which commands a given unit accepts, rejects, or ignores, which is the empirical answer to
 * "what does this hardware support". Destructive commands still ask for confirmation first.
 *
 * One curated behavior does carry over: if the command + payload being sent matches a recovered
 * operation that is only available in certain unit modes, the console auto-drives the unit to a
 * supported mode first (the same shared mode driver every other send path uses), so a mode block
 * never masquerades as an unsupported command. Commands with no known mode requirement are sent as
 * typed.
 */
@Composable
internal fun CommandConsoleScreen(
    controller: BleController,
    deviceAuthenticated: Boolean,
    session: Session,
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val exchange = remember(controller) { BleCommandExchange(controller) }
    val modeExchange = remember(controller) { BleModeChangeExchange(controller) }
    // Per-model record of commands this unit has accepted, so "confirmed working" ones can be hidden
    // to narrow testing. Kept out of the primary database (see CommandCompatStore).
    val compatStore = remember(context) { CommandCompatStore(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var hideWorking by rememberSaveable { mutableStateOf(false) }
    val payloads = remember { mutableStateMapOf<QtCommand, String>() }
    val results = remember { mutableStateMapOf<QtCommand, ConsoleResult>() }
    var sending by remember { mutableStateOf<QtCommand?>(null) }
    var pendingConfirm by remember { mutableStateOf<QtCommand?>(null) }
    // Commands already confirmed working on the connected model, loaded once per model. The hide
    // filter uses this snapshot; a command tested in the current session stays visible regardless
    // (see below), so a freshly-accepted command doesn't vanish before its result can be read.
    var workingSnapshot by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(unitModel) {
        workingSnapshot = unitModel?.let { compatStore.workingCommands(it.code) }.orEmpty()
    }

    fun send(command: QtCommand) {
        if (sending != null) return
        when (val parsed = parseHexPayload(payloads[command].orEmpty())) {
            is HexPayload.Invalid -> {
                results[command] = ConsoleResult(ConsoleResultKind.INVALID, parsed.message)
            }
            is HexPayload.Bytes -> {
                sending = command
                scope.launch {
                    try {
                        val result = runCommand(
                            commandExchange = exchange,
                            modeExchange = modeExchange,
                            session = session,
                            command = command,
                            payload = parsed.value,
                            unitModel = unitModel,
                            unitMode = unitMode,
                        )
                        results[command] = result
                        // Persist the outcome per model so the whole map is readable via adb and the
                        // accepted ones can be hidden. A just-accepted command joins the working
                        // snapshot; the filter still keeps it visible this session (it has a result).
                        val outcome = result.storeOutcome
                        if (outcome != null && unitModel != null) {
                            compatStore.record(unitModel.code, command.name, outcome)
                            if (outcome == CommandCompatStore.OUTCOME_OK) {
                                workingSnapshot = workingSnapshot + command.name
                            }
                        }
                    } finally {
                        sending = null
                    }
                }
            }
        }
    }

    val commands = matchingCommands(query).filterNot { command ->
        // Hide only commands already confirmed working on this model — and keep any command tested
        // this session visible so its result stays on screen.
        hideWorking &&
            unitModel != null &&
            command.name in workingSnapshot &&
            results[command] == null
    }
    val hiddenWorkingCount = if (unitModel != null) {
        matchingCommands(query).count { command ->
            command.name in workingSnapshot && results[command] == null
        }
    } else {
        0
    }

    LazyColumn(
        state = rememberLazyListState(),
        modifier = modifier
            .fillMaxSize()
            .testTag("command-console"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
    ) {
        item(key = "intro") { ConsoleIntroCard(deviceAuthenticated) }

        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Filter commands") },
                leadingIcon = {
                    Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null)
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Clear filter",
                            )
                        }
                    }
                } else {
                    null
                },
                supportingText = { Text("${commands.size} of ${QtCommand.entries.size} commands") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (unitModel != null && (workingSnapshot.isNotEmpty() || hideWorking)) {
            item(key = "hide-working") {
                FilterChip(
                    selected = hideWorking,
                    onClick = { hideWorking = !hideWorking },
                    label = {
                        Text(
                            if (hideWorking) {
                                "Hiding $hiddenWorkingCount confirmed-working"
                            } else {
                                "Hide confirmed-working (${workingSnapshot.size})"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    },
                    modifier = Modifier.testTag("hide-working"),
                )
            }
        }

        if (commands.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No command matches the filter.",
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            commands
                .groupBy { it.profile.layer }
                .toSortedMap(compareBy { it.ordinal })
                .forEach { (layer, layerCommands) ->
                    item(key = "layer:${layer.name}") {
                        Text(
                            text = layer.label,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(
                        count = layerCommands.size,
                        key = { index -> "command:${layerCommands[index].name}" },
                    ) { index ->
                        val command = layerCommands[index]
                        CommandRow(
                            command = command,
                            payload = payloads[command].orEmpty(),
                            onPayloadChange = { payloads[command] = it },
                            result = results[command],
                            enabled = deviceAuthenticated,
                            sending = sending == command,
                            busy = sending != null,
                            onSend = {
                                if (command.profile.requiresConfirmation) {
                                    pendingConfirm = command
                                } else {
                                    send(command)
                                }
                            },
                        )
                    }
                }
        }
    }

    pendingConfirm?.let { command ->
        ConfirmSendDialog(
            command = command,
            onConfirm = {
                pendingConfirm = null
                send(command)
            },
            onDismiss = { pendingConfirm = null },
        )
    }
}

private fun matchingCommands(query: String): List<QtCommand> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return QtCommand.entries
    return QtCommand.entries.filter { command ->
        val profile = command.profile
        command.name.contains(normalized, ignoreCase = true) ||
            command.code.toString() == normalized ||
            command.code.toString(16).equals(normalized.removePrefix("0x").removePrefix("0X"), ignoreCase = true) ||
            profile.layer.label.contains(normalized, ignoreCase = true) ||
            profile.risk.label.contains(normalized, ignoreCase = true)
    }
}

private suspend fun runCommand(
    commandExchange: BleCommandExchange,
    modeExchange: BleModeChangeExchange,
    session: Session,
    command: QtCommand,
    payload: ByteArray,
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode?,
): ConsoleResult {
    // If this raw command+payload matches a known mode-gated operation, drive the unit to a
    // supported mode first — the same shared driver every other send path uses. The command is then
    // sent (and compat-logged) in whatever mode it ended up in.
    var effectiveMode = unitMode
    var modeNote = ""
    curatedModeGatedAction(command, payload, session, unitModel, unitMode)?.let { gatedAction ->
        val drive = modeExchange.driveToRequiredMode(
            session = session,
            action = gatedAction,
            unitModel = unitModel,
            startMode = unitMode,
        )
        drive.failure?.let {
            return ConsoleResult(
                kind = ConsoleResultKind.TRANSPORT,
                headline = "Mode switch failed before sending ${command.name}; not sent.",
            )
        }
        if (drive.changed) {
            effectiveMode = drive.finalMode
            modeNote = "Switched to ${drive.finalMode?.displayName ?: "?"} mode first. "
        }
    }

    val packet = runCatching { QtProtocolCodec.generateOriginal(command, payload) }
        .getOrElse { error ->
            return ConsoleResult(
                ConsoleResultKind.INVALID,
                error.message ?: "The packet could not be built.",
            )
        }
    return when (val result = commandExchange.sendAndAwait(packet, expectedCommand = command)) {
        is BleCommandExchangeResult.Acknowledged -> {
            val decoded = QtResponseDecoder.decode(result.response, unitModel)
            val responseName = result.response.command?.name ?: hexByte(result.response.commandCode)
            val accepted = if (result.response.command == QtCommand.ACK_OK) {
                "ACK_OK"
            } else {
                "ACCEPTED rx=$responseName ${result.response.payload.size}B"
            }
            traceCompat(unitModel, effectiveMode, command, payload, accepted)
            ConsoleResult(
                kind = ConsoleResultKind.ACCEPTED,
                headline = "${modeNote}Accepted",
                detail = result.response.rawResponseText(result.rawFrame),
                decodedLines = decoded?.let { listOf(it.heading) + it.lines },
                storeOutcome = CommandCompatStore.OUTCOME_OK,
            )
        }
        is BleCommandExchangeResult.Rejected -> {
            val error = QtErrorCode.fromAckError(result.response)
            val code = result.response.payload.firstOrNull()?.toInt()?.and(0xff)
            val outcome = buildString {
                append("REJECTED")
                if (code != null) append(" code=${hexByte(code)}")
                error?.let { append(" ${it.displayName}") }
            }
            traceCompat(unitModel, effectiveMode, command, payload, outcome)
            ConsoleResult(
                kind = ConsoleResultKind.REJECTED,
                headline = modeNote + result.response.ackErrorMessage(command),
                detail = result.response.rawResponseText(result.rawFrame),
                storeOutcome = CommandCompatStore.OUTCOME_REJECT_PREFIX +
                    (code?.let { hexByte(it) } ?: "??"),
            )
        }
        BleCommandExchangeResult.TimedOut -> {
            traceCompat(unitModel, effectiveMode, command, payload, "NO_RESPONSE")
            ConsoleResult(
                kind = ConsoleResultKind.TIMEOUT,
                headline = modeNote +
                    "No unit response within 5 seconds (GATT accepted the write).",
                storeOutcome = CommandCompatStore.OUTCOME_TIMEOUT,
            )
        }
        is BleCommandExchangeResult.TransportFailure -> {
            traceCompat(unitModel, effectiveMode, command, payload, "LINK_FAIL")
            ConsoleResult(
                kind = ConsoleResultKind.TRANSPORT,
                headline = modeNote + result.message,
                storeOutcome = CommandCompatStore.OUTCOME_LINK,
            )
        }
    }
}

/**
 * The recovered action for a raw command + payload when one is on record and it is gated to certain
 * unit modes — the hook that lets the console reuse the shared mode driver. Returns null when no
 * curated operation matches (a truly raw command) or the match has no mode requirement, in which
 * case the console sends as typed without touching the unit's mode.
 */
internal fun curatedModeGatedAction(
    command: QtCommand,
    payload: ByteArray,
    session: Session,
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode?,
): RecoveredDeviceAction? {
    val payloadInts = payload.map { byte -> byte.toInt() and 0xff }
    val action = RecoveredWorkflowCatalog.operations
        .firstOrNull { operation -> operation.command == command && operation.payload == payloadInts }
        ?.preferredBinding(session, unitModel, unitMode)
        ?.action
        ?: return null
    return action.takeIf { it.modeRequirement != null }
}

private fun traceCompat(
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode?,
    command: QtCommand,
    payload: ByteArray,
    outcome: String,
) {
    KarrTrace.log(KarrTrace.COMPAT) { compatLogLine(unitModel, unitMode, command, payload, outcome) }
}

/**
 * One compatibility line for the `KARR/Compat` logcat channel, pairing the connected unit's model
 * and mode with the command and the unit's outcome so `adb logcat -s KARR/Compat` builds a
 * per-model command-support picture. Pure and [internal] so the format is unit-tested. Example:
 * `DR1 CAN / Installer | READ_VIN(0x23) | ACCEPTED rx=READ_VIN 17B | tx=-`.
 */
internal fun compatLogLine(
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode?,
    command: QtCommand,
    payload: ByteArray,
    outcome: String,
): String {
    val model = unitModel?.displayName ?: "UNKNOWN-MODEL"
    val mode = unitMode?.displayName ?: "?"
    val tx = if (payload.isEmpty()) "-" else KarrTrace.hex(payload)
    return "$model / $mode | ${command.name}(${hexByte(command.code)}) | $outcome | tx=$tx"
}

private fun hexByte(value: Int): String =
    "0x" + (value and 0xff).toString(16).uppercase().padStart(2, '0')

@Composable
private fun ConsoleIntroCard(deviceAuthenticated: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Raw command console",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Sends unguarded 0xAA protocol frames straight to the connected unit — no role or " +
                    "model checks. Risk and layer tags are heuristic hints; the unit's own " +
                    "accept / reject / timeout response is the real compatibility signal.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!deviceAuthenticated) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Connect and authenticate a unit to send. Commands stay visible so the " +
                            "catalog can be read offline.",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandRow(
    command: QtCommand,
    payload: String,
    onPayloadChange: (String) -> Unit,
    result: ConsoleResult?,
    enabled: Boolean,
    sending: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
) {
    val profile = command.profile
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("command:${command.name}"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = command.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${command.code} · 0x${command.code.toString(16).uppercase().padStart(2, '0')}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Button(
                        onClick = onSend,
                        enabled = enabled && !busy,
                        modifier = Modifier
                            .width(112.dp)
                            .height(36.dp)
                            .testTag("command-send:${command.name}"),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        if (sending) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(16.dp).width(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Send", maxLines = 1)
                        }
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DangerTag(command.dangerScore)
                TagChip(
                    text = profile.risk.label,
                    container = profile.risk.containerColor(),
                    content = profile.risk.contentColor(),
                )
            }

            profile.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val knownPayloads = remember(command) {
                RecoveredWorkflowCatalog.knownPayloadsByCommand[command].orEmpty()
            }
            if (knownPayloads.isNotEmpty()) {
                Text(
                    text = "Recovered payloads",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    knownPayloads.forEach { known ->
                        val hex = known.payload.toPayloadHex()
                        SuggestionChip(
                            onClick = { onPayloadChange(hex) },
                            enabled = enabled && !sending,
                            label = { Text("${known.label} · $hex", maxLines = 1) },
                            modifier = Modifier.testTag("command-preset:${command.name}:$hex"),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = payload,
                onValueChange = onPayloadChange,
                label = { Text("Payload (hex)") },
                placeholder = { Text("e.g. 01 1E — leave blank for none") },
                enabled = enabled && !sending,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("command-payload:${command.name}"),
            )

            result?.let { ConsoleResultBlock(it) }
        }
    }
}

@Composable
private fun ConsoleResultBlock(result: ConsoleResult) {
    Surface(
        color = result.kind.containerColor(),
        contentColor = result.kind.contentColor(),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = result.headline,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            result.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            result.decodedLines?.takeIf { it.isNotEmpty() }?.let { lines ->
                Text(
                    text = lines.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TagChip(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ConfirmSendDialog(
    command: QtCommand,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val note = command.profile.note
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send ${command.name}?") },
        text = {
            Text(
                buildString {
                    append("This is a ")
                    append(command.profile.risk.label.lowercase())
                    append(" command on the ")
                    append(command.profile.layer.label)
                    append(" layer.")
                    if (note != null) {
                        append(" ")
                        append(note)
                    }
                    append(" Send it to the connected unit?")
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("command-confirm-send")) {
                Text("Send")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private data class ConsoleResult(
    val kind: ConsoleResultKind,
    val headline: String,
    val detail: String? = null,
    val decodedLines: List<String>? = null,
    // Compact token persisted per model for adb extraction; null for outcomes not worth storing
    // (a bad-hex payload, or a command never actually sent because a pre-send mode switch failed).
    val storeOutcome: String? = null,
)

private enum class ConsoleResultKind { ACCEPTED, REJECTED, TIMEOUT, TRANSPORT, INVALID }

/** Parsed hex-payload outcome: either the decoded bytes or a human-readable reason it was rejected. */
internal sealed interface HexPayload {
    data class Bytes(val value: ByteArray) : HexPayload
    data class Invalid(val message: String) : HexPayload
}

/**
 * Parses a loose hex string into bytes. Accepts space- or comma-separated tokens, an optional `0x`
 * per token, and continuous even-length runs — so "01 1E", "011E", and "0x01, 0x1e" are equivalent.
 * Blank input is an empty payload (many commands take none).
 */
internal fun parseHexPayload(text: String): HexPayload {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return HexPayload.Bytes(ByteArray(0))
    val bytes = ArrayList<Byte>()
    for (token in trimmed.split(*PAYLOAD_SEPARATORS).filter { it.isNotEmpty() }) {
        val digits = token.removePrefix("0x").removePrefix("0X")
        if (digits.isEmpty()) continue
        if (digits.length % 2 != 0) {
            return HexPayload.Invalid("\"$token\" is not a whole number of hex bytes.")
        }
        var index = 0
        while (index < digits.length) {
            val pair = digits.substring(index, index + 2)
            val value = pair.toIntOrNull(16)
                ?: return HexPayload.Invalid("\"$pair\" is not valid hex.")
            bytes.add(value.toByte())
            index += 2
        }
    }
    if (bytes.size > MAX_CONSOLE_PAYLOAD_BYTES) {
        return HexPayload.Invalid("Payload exceeds $MAX_CONSOLE_PAYLOAD_BYTES bytes.")
    }
    return HexPayload.Bytes(bytes.toByteArray())
}

private val PAYLOAD_SEPARATORS = charArrayOf(' ', '\t', '\n', ',')
private const val MAX_CONSOLE_PAYLOAD_BYTES = 65_534

/** Formats recovered payload bytes as the space-separated upper-hex the input field round-trips. */
private fun List<Int>.toPayloadHex(): String =
    joinToString(" ") { value -> "%02X".format(value and 0xff) }

@Composable
private fun QtCommandRisk.containerColor() = when (this) {
    QtCommandRisk.SAFE_READ -> MaterialTheme.colorScheme.secondaryContainer
    QtCommandRisk.STATE_CHANGE -> MaterialTheme.colorScheme.tertiaryContainer
    QtCommandRisk.DESTRUCTIVE -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun QtCommandRisk.contentColor() = when (this) {
    QtCommandRisk.SAFE_READ -> MaterialTheme.colorScheme.onSecondaryContainer
    QtCommandRisk.STATE_CHANGE -> MaterialTheme.colorScheme.onTertiaryContainer
    QtCommandRisk.DESTRUCTIVE -> MaterialTheme.colorScheme.onErrorContainer
}

@Composable
private fun ConsoleResultKind.containerColor() = when (this) {
    ConsoleResultKind.ACCEPTED -> MaterialTheme.colorScheme.primaryContainer
    ConsoleResultKind.REJECTED,
    ConsoleResultKind.TRANSPORT,
    ConsoleResultKind.INVALID,
    -> MaterialTheme.colorScheme.errorContainer
    ConsoleResultKind.TIMEOUT -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun ConsoleResultKind.contentColor() = when (this) {
    ConsoleResultKind.ACCEPTED -> MaterialTheme.colorScheme.onPrimaryContainer
    ConsoleResultKind.REJECTED,
    ConsoleResultKind.TRANSPORT,
    ConsoleResultKind.INVALID,
    -> MaterialTheme.colorScheme.onErrorContainer
    ConsoleResultKind.TIMEOUT -> MaterialTheme.colorScheme.onSurfaceVariant
}
