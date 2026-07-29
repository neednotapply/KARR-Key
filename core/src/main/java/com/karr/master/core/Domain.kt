package com.karr.master.core

import java.time.Instant

enum class UserRole {
    CUSTOMER,
    DEALER,
    INSTALLER,
}

enum class Capability {
    VEHICLE_READ,
    VEHICLE_LOCATION,
    CUSTOMER_CONTROL,
    DEALER_INVENTORY,
    DEALER_DEVICE_SERVICE,
    INSTALLER_JOBS,
    INSTALLER_FITMENT,
    INSTALLER_REPAIR,
    INSTALLER_REMOVAL,
    DEVICE_CONNECT,
    DEVICE_SETTINGS,
    DEVICE_DIAGNOSTICS,
    DEVICE_CAN_TRANSFER,
    DEVICE_FIRMWARE_TRANSFER,
    DEVICE_KEY_MANAGEMENT,
    LOCAL_RECORDS,
}

data class VehicleSummary(
    val vehicleId: String,
    val unitSerial: String?,
    val vinSuffix: String,
    val year: Int?,
    val make: String,
    val model: String,
    val color: String?,
    val stockNumber: String?,
    val status: String,
)

data class TechnicianJob(
    val jobId: String,
    val accountId: String,
    val vehicleId: String?,
    val type: JobType,
    val scheduledUtc: Instant?,
    val state: String,
)

enum class JobType {
    INSTALL,
    REPAIR,
    REPLACEMENT,
    REMOVAL,
    BATTERY_REMOVAL,
}

data class Session(
    val displayName: String,
    val roles: Set<UserRole>,
    val capabilities: Set<Capability>,
)

data class FeatureDefinition(
    val id: String,
    val title: String,
    val summary: String,
    val category: String,
    val sourceApp: String,
    val requiredRole: UserRole,
    val requiredCapabilities: Set<Capability>,
    val safetySensitive: Boolean = false,
)

sealed interface AuthorizationDecision {
    data object Allowed : AuthorizationDecision
    data class Denied(val reason: String) : AuthorizationDecision
}
