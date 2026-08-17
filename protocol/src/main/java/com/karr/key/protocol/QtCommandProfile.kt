package com.karr.key.protocol

/**
 * A heuristic, a-priori classification of every [QtCommand] for the raw command console. It answers
 * two questions before anything is sent: how dangerous is this command ([risk]), and which part of
 * the hardware does it exercise ([layer]) — the latter being the "might be incompatible in a
 * hardware layer" hint, since a CAN command means nothing without a CAN interface fitted, a remote
 * command nothing without the RF board, and so on.
 *
 * These tags are inferred from the recovered command names and the research notes, not from a
 * golden capture. They are guidance only: the authoritative compatibility signal is what the unit
 * actually does when the command is sent — acknowledge, reject with an `ACK_ERROR` code, or time
 * out. The console records that outcome so a real per-unit support map can be built empirically.
 */
data class QtCommandProfile(
    val risk: QtCommandRisk,
    val layer: QtCommandLayer,
    val requiresConfirmation: Boolean,
    val note: String? = null,
)

enum class QtCommandRisk(val label: String) {
    /** Reads state without changing it. Safe to fire freely. */
    SAFE_READ("Read"),

    /** Changes unit or vehicle state, but reversibly. */
    STATE_CHANGE("Writes state"),

    /** Firmware, keys, factory-reset, or bus reflashing — can brick, wipe, or lock out a unit. */
    DESTRUCTIVE("Destructive"),
}

enum class QtCommandLayer(val label: String) {
    /** The KARR unit's own MCU: settings, modes, sensors, violation log, identity. */
    CORE("Core unit"),

    /** Needs a CAN bus interface fitted and a matching vehicle CAN file loaded. */
    CAN_BUS("CAN bus"),

    /** Needs the RF remote-control board and only affects paired key fobs. */
    RF_REMOTES("RF remotes"),

    /** Only present on two-MCU units that carry a second "PIC2" controller. */
    SECOND_MCU("PIC2 / 2nd MCU"),

    /** Drops the unit into its firmware bootloader — only on bootloader-capable hardware. */
    BOOTLOADER("Bootloader"),

    /** GM-LAN single-wire timing, meaningful only on GM-LAN vehicles. */
    GM_LAN("GM-LAN"),

    /** A protocol response/marker code the unit emits, not a command a client sends. */
    PROTOCOL("Protocol / response"),
}

/**
 * Exhaustive by construction: the `when` has no `else`, so adding a [QtCommand] fails to compile
 * until it is classified here. That keeps the console's tags complete as the protocol grows.
 */
val QtCommand.profile: QtCommandProfile
    get() = when (this) {
        // Protocol control and unit-emitted markers. Sending them to a unit is meaningless, but they
        // are real codes and the console lists everything, so they are tagged rather than hidden.
        QtCommand.ACK_OK -> protocol("Generic acknowledgement the unit sends; not a command.")
        QtCommand.AUTH_SUCCESS -> protocol("Unit-emitted result of a successful auth handshake.")
        QtCommand.AUTH_FAILED -> protocol("Unit-emitted result of a failed auth handshake.")
        QtCommand.REMOTE_LEARNT -> protocol("Unit notification that a remote was learned.")
        QtCommand.REMOTE_LEARN_TIMEOUT -> protocol("Unit notification that remote-learn timed out.")
        QtCommand.REMOTE_TEST_RECEIVED -> protocol("Unit notification that a test remote was seen.")
        QtCommand.PIC2_READY ->
            QtCommandProfile(QtCommandRisk.SAFE_READ, QtCommandLayer.SECOND_MCU, false,
                "Handshake from the second MCU; only two-MCU units emit it.")
        QtCommand.CAN_MESSAGE -> protocol("Raw CAN frame notification carried over the link.")
        QtCommand.NONE -> protocol("Placeholder/no-op code.")
        QtCommand.ACK_ERROR -> protocol("Generic rejection the unit sends, carrying an error code.")

        // Core reads — safe.
        QtCommand.READ_ALL_SETTINGS -> read(QtCommandLayer.CORE)
        QtCommand.READ_SPECIFIC_SETTING ->
            read(QtCommandLayer.CORE, "Payload selects which setting to read.")
        QtCommand.READ_GPIO -> read(QtCommandLayer.CORE)
        QtCommand.READ_FIRMWARE_VERSION -> read(QtCommandLayer.CORE)
        QtCommand.READ_VIOLATIONS -> read(QtCommandLayer.CORE)
        QtCommand.READ_STATE -> read(QtCommandLayer.CORE)
        QtCommand.READ_MODE -> read(QtCommandLayer.CORE)
        QtCommand.READ_VIN -> read(QtCommandLayer.CORE, "Programmed by the installer; may be blank.")
        QtCommand.READ_SERIAL -> read(QtCommandLayer.CORE)
        QtCommand.READ_VIOLATION_INHIBIT -> read(QtCommandLayer.CORE)
        QtCommand.READ_VEHICLE_BATTERY_VOLTAGE -> read(QtCommandLayer.CORE)
        QtCommand.GET_TILT_SENSITIVITY -> read(QtCommandLayer.CORE)
        QtCommand.GET_MOVEMENT_SENSITIVITY -> read(QtCommandLayer.CORE)
        QtCommand.READ_RESET_CAUSE -> read(QtCommandLayer.CORE)
        QtCommand.READ_IO_PROTECTION -> read(QtCommandLayer.CORE)
        QtCommand.READ_MODE_HISTORY -> read(QtCommandLayer.CORE)
        QtCommand.READ_REGISTER -> read(QtCommandLayer.CORE, "Payload selects a raw register address.")
        QtCommand.DEBUG_MESSAGE -> read(QtCommandLayer.CORE, "Diagnostic text channel.")

        // CAN reads — safe, but only answer on CAN-equipped units.
        QtCommand.CAN_FILENAME -> read(QtCommandLayer.CAN_BUS)
        QtCommand.CAN_FILE_VERSION -> read(QtCommandLayer.CAN_BUS)
        QtCommand.CAN_READ_FLAG -> read(QtCommandLayer.CAN_BUS)
        QtCommand.CHECK_CAN_ERRORS -> read(QtCommandLayer.CAN_BUS)
        QtCommand.BUS_STATE -> read(QtCommandLayer.CAN_BUS)

        // Remote / GM-LAN / bootloader reads.
        QtCommand.READ_REMOTES -> read(QtCommandLayer.RF_REMOTES)
        QtCommand.REMOTE_DEBUG -> read(QtCommandLayer.RF_REMOTES)
        QtCommand.GMLAN_TIMING_READ -> read(QtCommandLayer.GM_LAN)
        QtCommand.BOOTLOAD_STATUS -> read(QtCommandLayer.BOOTLOADER)

        // Reversible state changes on the core unit.
        QtCommand.CHANGE_MODE ->
            stateChange(QtCommandLayer.CORE, "Payload is the target mode code.")
        QtCommand.SECURE_ARM -> stateChange(QtCommandLayer.CORE)
        QtCommand.SECURE_DISARM -> stateChange(QtCommandLayer.CORE)
        QtCommand.SNOW_MODE -> stateChange(QtCommandLayer.CORE)
        QtCommand.ELITE_MODE -> stateChange(QtCommandLayer.CORE)
        QtCommand.LOW_POWER_OR_REPAIR_MODE -> stateChange(QtCommandLayer.CORE)
        QtCommand.SET_TILT_SENSITIVITY -> stateChange(QtCommandLayer.CORE)
        QtCommand.SET_MOVEMENT_SENSITIVITY -> stateChange(QtCommandLayer.CORE)
        QtCommand.WRITE_SPECIFIC_SETTING ->
            stateChange(QtCommandLayer.CORE, "Payload is a setting id plus its new value.")
        QtCommand.WRITE_VIOLATION_INHIBIT -> stateChange(QtCommandLayer.CORE)
        QtCommand.CLEAR_VIOLATIONS -> stateChange(QtCommandLayer.CORE, "Erases the violation log.")
        QtCommand.BUMP_TEST -> stateChange(QtCommandLayer.CORE, "Runs the sensor bump self-test.")
        QtCommand.VEHICLE_EVENT -> stateChange(QtCommandLayer.CORE)
        QtCommand.CONFIGURE_GPIO ->
            stateChange(QtCommandLayer.CORE, "Reconfigures IO pins; wrong values can misdrive outputs.")
        QtCommand.DISCONNECT_BT -> stateChange(QtCommandLayer.CORE, "Ends the BLE session.")
        QtCommand.REQUEST_AUTH -> stateChange(QtCommandLayer.CORE, "Part of the auth handshake.")
        QtCommand.PROVIDE_AUTH ->
            stateChange(QtCommandLayer.CORE, "Needs a correctly computed challenge response.")
        // Sounds the siren — reversible, but disruptive enough to confirm first.
        QtCommand.ALARM_TRIGGER ->
            QtCommandProfile(QtCommandRisk.STATE_CHANGE, QtCommandLayer.CORE, true,
                "Sounds the siren immediately.")

        // Reversible state changes that drive the vehicle over CAN.
        QtCommand.LOCK_DOORS -> stateChange(QtCommandLayer.CAN_BUS)
        QtCommand.UNLOCK_DOORS -> stateChange(QtCommandLayer.CAN_BUS)
        QtCommand.SILENT_LOCK -> stateChange(QtCommandLayer.CAN_BUS)
        QtCommand.SILENT_UNLOCK -> stateChange(QtCommandLayer.CAN_BUS)
        QtCommand.VEHICLE_CONTROL ->
            stateChange(QtCommandLayer.CAN_BUS, "Generic vehicle output; payload selects the action.")
        QtCommand.LEARN_DOORS -> stateChange(QtCommandLayer.CAN_BUS, "Enters a door-learn routine.")
        QtCommand.DOOR_CHANGE -> stateChange(QtCommandLayer.CAN_BUS)
        QtCommand.CAN_LEARN_MODE -> stateChange(QtCommandLayer.CAN_BUS)

        // Reversible state changes on the RF board.
        QtCommand.REMOTE_LEARN -> stateChange(QtCommandLayer.RF_REMOTES, "Enters remote-learn mode.")
        QtCommand.REMOTE_TEST_MODE -> stateChange(QtCommandLayer.RF_REMOTES)

        // GM-LAN timing write — a wrong value can break bus comms; confirm.
        QtCommand.GMLAN_TIMING_WRITE ->
            QtCommandProfile(QtCommandRisk.STATE_CHANGE, QtCommandLayer.GM_LAN, true,
                "Writes single-wire bus timing; GM-LAN vehicles only.")

        // Identity write — reversible but provisions the unit; confirm.
        QtCommand.WRITE_VIN ->
            QtCommandProfile(QtCommandRisk.STATE_CHANGE, QtCommandLayer.CORE, true,
                "Programs the stored VIN; validated by the unit.")

        // Destructive: settings wipe / factory / reboot / secure key.
        QtCommand.WRITE_ALL_SETTINGS ->
            destructive(QtCommandLayer.CORE, "Overwrites every setting from the payload.")
        QtCommand.RESET_TO_DEFAULT -> destructive(QtCommandLayer.CORE, "Factory-resets the unit.")
        QtCommand.RESET_UNIT -> destructive(QtCommandLayer.CORE, "Reboots the unit.")
        QtCommand.SET_SECURE_KEY ->
            destructive(QtCommandLayer.CORE, "Rewrites the unit's secure key — can lock you out.")

        // Destructive: CAN file transfer reflashes bus behavior; a partial write leaves a bad config.
        QtCommand.START_CAN_FILE_DOWNLOAD -> destructive(QtCommandLayer.CAN_BUS, "Begins a CAN file write.")
        QtCommand.CAN_FILE_CHUNK -> destructive(QtCommandLayer.CAN_BUS, "One chunk of a CAN file write.")
        QtCommand.CAN_FILE_DOWNLOAD_COMPLETE -> destructive(QtCommandLayer.CAN_BUS, "Finalizes a CAN file write.")
        QtCommand.CAN_BAUD_FILE -> destructive(QtCommandLayer.CAN_BUS, "Sets the CAN baud file.")
        QtCommand.CAN_FILE_DELETE -> destructive(QtCommandLayer.CAN_BUS, "Deletes the loaded CAN file.")

        // Destructive: RF pairing wipe.
        QtCommand.CLEAR_REMOTES -> destructive(QtCommandLayer.RF_REMOTES, "Erases all paired remotes.")

        // Destructive: firmware bootloader — can brick.
        QtCommand.BOOTLOAD_ENTER -> destructive(QtCommandLayer.BOOTLOADER, "Enters the firmware bootloader.")
        QtCommand.BOOTLOAD_START -> destructive(QtCommandLayer.BOOTLOADER)
        QtCommand.BOOTLOAD_CHUNK -> destructive(QtCommandLayer.BOOTLOADER)
        QtCommand.BOOTLOAD_COMPLETE -> destructive(QtCommandLayer.BOOTLOADER)
        QtCommand.BOOTLOAD_ENTER_PIC2 ->
            destructive(QtCommandLayer.SECOND_MCU, "Enters the second MCU's bootloader.")
    }

/**
 * A 0–10 danger rating for sending a command, folding two things the operator cares about into one
 * number: how damaging a *successful* send is (its [QtCommandRisk] plus the hardware [QtCommandLayer]
 * it drives) and how badly a *misconfiguration* could bite (a command gated behind a confirmation is
 * one where a wrong value or wrong moment does real harm). Plain reads sit at 0; firmware, secure-key,
 * and CAN-flash writes reach 9–10. Shown to the operator as "Danger: N".
 */
val QtCommand.dangerScore: Int
    get() {
        val profile = profile
        val impact = when (profile.risk) {
            QtCommandRisk.SAFE_READ -> 0
            QtCommandRisk.STATE_CHANGE -> 4
            QtCommandRisk.DESTRUCTIVE -> 8
        }
        val layerRisk = when (profile.layer) {
            QtCommandLayer.CORE,
            QtCommandLayer.RF_REMOTES,
            QtCommandLayer.PROTOCOL,
            -> 0
            QtCommandLayer.CAN_BUS,
            QtCommandLayer.GM_LAN,
            QtCommandLayer.SECOND_MCU,
            -> 1
            QtCommandLayer.BOOTLOADER -> 2
        }
        // Confirmation-gated commands carry extra misconfiguration weight; destructive ones already
        // top out on impact, so the bump only lifts the middle tier (siren, VIN/timing writes).
        val misconfig =
            if (profile.requiresConfirmation && profile.risk != QtCommandRisk.DESTRUCTIVE) 1 else 0
        return (impact + layerRisk + misconfig).coerceIn(0, 10)
    }

private fun read(layer: QtCommandLayer, note: String? = null) =
    QtCommandProfile(QtCommandRisk.SAFE_READ, layer, requiresConfirmation = false, note = note)

private fun stateChange(layer: QtCommandLayer, note: String? = null) =
    QtCommandProfile(QtCommandRisk.STATE_CHANGE, layer, requiresConfirmation = false, note = note)

private fun destructive(layer: QtCommandLayer, note: String? = null) =
    QtCommandProfile(QtCommandRisk.DESTRUCTIVE, layer, requiresConfirmation = true, note = note)

private fun protocol(note: String) =
    QtCommandProfile(QtCommandRisk.SAFE_READ, QtCommandLayer.PROTOCOL, requiresConfirmation = false, note = note)
