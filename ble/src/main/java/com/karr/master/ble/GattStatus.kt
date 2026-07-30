package com.karr.master.ble

object GattStatus {
    const val SUCCESS = 0
    const val INVALID_HANDLE = 1
    const val READ_NOT_PERMITTED = 2
    const val WRITE_NOT_PERMITTED = 3
    const val INSUFFICIENT_AUTHENTICATION = 5
    const val REQUEST_NOT_SUPPORTED = 6
    const val INVALID_OFFSET = 7
    const val INSUFFICIENT_AUTHORIZATION = 8
    const val INVALID_ATTRIBUTE_LENGTH = 13
    const val UNLIKELY_ERROR = 14
    const val INSUFFICIENT_ENCRYPTION = 15
    const val INSUFFICIENT_RESOURCES = 17
    const val CONNECTION_TIMEOUT = 8
    const val PEER_TERMINATED = 19
    const val LOCAL_HOST_TERMINATED = 22
    const val LINK_NEGOTIATION_TIMEOUT = 34
    const val CONNECTION_FAILED_TO_ESTABLISH = 62
    const val NO_RESOURCES = 128
    const val INTERNAL_ERROR = 129
    const val WRONG_STATE = 130
    const val BUSY = 132
    const val ANDROID_GENERIC_ERROR = 133
    const val AUTHENTICATION_FAILED = 137
    const val INVALID_CONFIGURATION = 139
    const val NOT_ENCRYPTED = 142
    const val CONGESTED = 143
    const val GATT_CONNECTION_TIMEOUT = 147
    const val CCC_CONFIGURATION_ERROR = 253
    const val PROCEDURE_IN_PROGRESS = 254
    const val VALUE_OUT_OF_RANGE = 255
    const val FAILURE = 257

    fun disconnectMessage(
        status: Int,
        previousState: BleSessionState,
    ): String = when {
        status == SUCCESS ->
            "The Bluetooth link closed without an Android error. Keep the KARR unit " +
                "powered and nearby while the app reconnects."
        status == PEER_TERMINATED &&
            (
                previousState is BleSessionState.AuthenticationRequired ||
                    previousState is BleSessionState.Authenticating
                ) ->
            "The KARR unit closed the Bluetooth connection while application " +
                "authentication was in progress. Verify that the provisioned key matches " +
                "this unit, then reconnect."
        status == PEER_TERMINATED ->
            "The KARR unit closed the Bluetooth connection. Make sure it remains powered " +
                "and is not connected to another phone, then scan again."
        status == CONNECTION_TIMEOUT ->
            "The KARR unit stopped responding before Android completed the Bluetooth " +
                "connection. Move closer, make sure the unit is awake, then scan again."
        status == LOCAL_HOST_TERMINATED ->
            "Android closed the Bluetooth connection locally. Scan again; if this repeats, " +
                "restart Bluetooth on this phone."
        status == LINK_NEGOTIATION_TIMEOUT ->
            "The phone and KARR unit did not complete Bluetooth link negotiation in time. " +
                "Move closer, make sure the unit is awake, then scan again."
        status == CONNECTION_FAILED_TO_ESTABLISH ->
            "Android could not establish a Bluetooth link with the KARR unit. Move closer, " +
                "make sure the unit is awake and not connected to another phone, then scan again."
        status == ANDROID_GENERIC_ERROR ->
            "Android could not establish or maintain the Bluetooth GATT session. Move closer, " +
                "make sure no other app is connected, then scan again; restart Bluetooth if " +
                "this repeats."
        status == GATT_CONNECTION_TIMEOUT ->
            "Android timed out while setting up the Bluetooth GATT session. Move closer, " +
                "make sure the unit is awake, then scan again."
        else ->
            "The Bluetooth connection ended for an unrecognized Android reason " +
                "(diagnostic status $status). Scan again and capture a Bluetooth log if it repeats."
    }

    fun operationFailure(
        operation: String,
        status: Int,
    ): String = when (status) {
        INVALID_HANDLE ->
            "$operation failed because the KARR unit no longer recognizes the selected " +
                "Bluetooth characteristic. Disconnect and scan again."
        READ_NOT_PERMITTED ->
            "$operation is not readable on this KARR unit."
        WRITE_NOT_PERMITTED ->
            "$operation is not writable on this KARR unit."
        INSUFFICIENT_AUTHENTICATION ->
            "$operation requires Bluetooth-level authentication that is not active. " +
                "Reconnect to the unit and try again."
        REQUEST_NOT_SUPPORTED ->
            "$operation is not supported by this KARR unit."
        INVALID_OFFSET ->
            "$operation used an offset the KARR unit does not support."
        INSUFFICIENT_AUTHORIZATION ->
            "$operation was not authorized by the KARR unit at the Bluetooth attribute layer."
        INVALID_ATTRIBUTE_LENGTH ->
            "$operation sent a value that is too long or too short for the Bluetooth characteristic."
        UNLIKELY_ERROR ->
            "$operation was rejected by the KARR unit because it could not complete the request."
        INSUFFICIENT_ENCRYPTION ->
            "$operation requires an encrypted Bluetooth link. Reconnect to the unit and try again."
        INSUFFICIENT_RESOURCES, NO_RESOURCES ->
            "$operation could not complete because the Bluetooth stack ran out of resources. " +
                "Disconnect, wait a moment, then scan again."
        INTERNAL_ERROR ->
            "Android encountered an internal Bluetooth error while completing $operation. " +
                "Disconnect and scan again."
        WRONG_STATE ->
            "$operation started while Android's Bluetooth session was in the wrong state. " +
                "Disconnect and scan again."
        BUSY, PROCEDURE_IN_PROGRESS ->
            "$operation could not start because another Bluetooth operation is still in progress. " +
                "Wait a moment and try again."
        ANDROID_GENERIC_ERROR ->
            "Android lost the active Bluetooth GATT session while completing $operation. " +
                "Move closer, make sure no other app is connected, then scan again."
        AUTHENTICATION_FAILED ->
            "$operation failed because Bluetooth-level authentication did not succeed. " +
                "Reconnect to the unit and try again."
        INVALID_CONFIGURATION ->
            "$operation failed because Android rejected the Bluetooth characteristic configuration. " +
                "Disconnect and scan again."
        NOT_ENCRYPTED ->
            "$operation requires Bluetooth encryption, but the current link is not encrypted. " +
                "Reconnect to the unit and try again."
        CONGESTED ->
            "$operation could not complete because the Bluetooth connection is congested. " +
                "Move closer, wait a moment, then try again."
        GATT_CONNECTION_TIMEOUT ->
            "$operation timed out because Android lost the Bluetooth GATT connection. " +
                "Move closer and scan again."
        CCC_CONFIGURATION_ERROR ->
            "$operation failed because Bluetooth notifications are not configured correctly. " +
                "Disconnect and scan again."
        VALUE_OUT_OF_RANGE ->
            "$operation sent a value outside the range accepted by the KARR unit."
        FAILURE ->
            "Android could not complete $operation and did not provide a narrower cause. " +
                "Disconnect and scan again; restart Bluetooth if this repeats."
        else ->
            "$operation failed for an unrecognized Android reason (diagnostic status $status). " +
                "Disconnect and capture a Bluetooth log if it repeats."
    }

    fun scanFailure(errorCode: Int): String = when (errorCode) {
        1 -> "A Bluetooth scan is already running. Stop it before starting another scan."
        2 ->
            "Android could not register this app for Bluetooth scanning. Restart Bluetooth " +
                "on this phone and try again."
        3 ->
            "Android encountered an internal Bluetooth scanning error. Restart Bluetooth " +
                "on this phone and try again."
        4 -> "This phone does not support the Bluetooth scanning feature requested by the app."
        5 ->
            "The phone has no Bluetooth scanning resources available. Stop other Bluetooth " +
                "scans, wait a moment, then try again."
        6 ->
            "Android is temporarily limiting Bluetooth scans because scans were started too " +
                "frequently. Wait a moment before scanning again."
        else ->
            "Android could not start Bluetooth scanning for an unrecognized reason " +
                "(diagnostic status $errorCode)."
    }
}
