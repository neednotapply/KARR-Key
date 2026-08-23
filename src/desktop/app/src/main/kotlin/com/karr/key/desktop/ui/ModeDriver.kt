package com.karr.key.desktop.ui

import com.karr.key.ble.BleModeChangeExchange
import com.karr.key.ble.BleModeChangeResult
import com.karr.key.core.Session
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.QtProtocolCodec
import com.karr.key.protocol.RecoveredDeviceAction

/**
 * Hop budget for one auto mode-change sequence, guarding against a cycle in the recovered transition
 * graph. Five comfortably covers the recovered mode topology (the longest authorized chain is well
 * under this).
 */
internal const val MAX_AUTO_MODE_HOPS = 5

internal data class ModeDriveResult(
    /** The mode the unit is in after the drive (unchanged from the start if no hop was needed). */
    val finalMode: KarrDeviceMode?,
    /** The modes stepped through, in order — empty when no hop was needed. */
    val hops: List<KarrDeviceMode>,
    /** The first hop that failed to confirm, if any; null when every attempted hop confirmed. */
    val failure: BleModeChangeResult?,
) {
    val changed: Boolean get() = hops.isNotEmpty()
}

/**
 * Drives the unit along the recovered CHANGE_MODE path until [action] is no longer blocked by unit
 * mode — one confirmed hop at a time, each a single CHANGE_MODE followed by a fresh READ_MODE.
 *
 * It resolves *only* mode blocks: [modeSwitchOffer] returns null the moment the action's mode
 * requirement is satisfied, and also for role/hardware blocks (which are not mode problems), so the
 * loop stops there and the caller's own gate still governs whether the command may be sent. A hop
 * that fails to confirm stops the drive and is reported in [ModeDriveResult.failure]; the unit is
 * left in whatever mode the last confirmed hop reached.
 *
 * This is the single implementation shared by every send path — manual operations, the auto-connect
 * sweep, and the raw command console — so mode handling stays identical across all of them.
 */
internal suspend fun BleModeChangeExchange.driveToRequiredMode(
    session: Session,
    action: RecoveredDeviceAction,
    unitModel: KarrUnitModel?,
    startMode: KarrDeviceMode?,
    onHop: suspend (from: KarrDeviceMode?, to: KarrDeviceMode) -> Unit = { _, _ -> },
): ModeDriveResult {
    var currentMode = startMode
    val hops = mutableListOf<KarrDeviceMode>()
    while (hops.size < MAX_AUTO_MODE_HOPS) {
        val offer = modeSwitchOffer(
            session = session,
            blockedAction = action,
            deviceAuthenticated = true,
            unitModel = unitModel,
            unitMode = currentMode,
        ) ?: break
        onHop(currentMode, offer.nextMode)
        val result = changeAndConfirm(
            changePacket = QtProtocolCodec.generateOriginal(
                command = QtCommand.CHANGE_MODE,
                payload = byteArrayOf(offer.nextMode.code.toByte()),
            ),
            targetMode = offer.nextMode,
        )
        if (result !is BleModeChangeResult.Confirmed) {
            return ModeDriveResult(currentMode, hops, result)
        }
        currentMode = offer.nextMode
        hops.add(offer.nextMode)
    }
    return ModeDriveResult(currentMode, hops, failure = null)
}
