package com.karr.master.ble

object GattStatus {
    const val SUCCESS = 0
    const val CONNECTION_TIMEOUT = 8
    const val PEER_TERMINATED = 19
    const val LOCAL_HOST_TERMINATED = 22
    const val CONNECTION_FAILED_TO_ESTABLISH = 62
    const val ANDROID_GENERIC_ERROR = 133

    fun disconnectMessage(
        status: Int,
        previousState: BleSessionState,
    ): String = when {
        status == PEER_TERMINATED &&
            previousState is BleSessionState.AuthenticationRequired ->
            "KARR authentication timed out; the unit ended the healthy BLE link " +
                "(GATT status 19). An authorized challenge response is required."
        status == PEER_TERMINATED ->
            "The peripheral ended the BLE link (GATT status 19)."
        status == CONNECTION_TIMEOUT ->
            "The BLE connection timed out (GATT status 8)."
        status == LOCAL_HOST_TERMINATED ->
            "Android ended the BLE link locally (GATT status 22)."
        status == CONNECTION_FAILED_TO_ESTABLISH ->
            "The BLE link could not be established (GATT status 62)."
        status == ANDROID_GENERIC_ERROR ->
            "Android reported a generic GATT stack failure (status 133)."
        else -> "BLE disconnected with GATT status $status."
    }
}
