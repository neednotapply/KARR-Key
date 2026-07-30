package com.karr.key.core

enum class UserRole {
    CUSTOMER,
    DEALER,
    INSTALLER,
}

enum class Capability {
    VEHICLE_READ,
    CUSTOMER_CONTROL,
    DEALER_DEVICE_SERVICE,
    INSTALLER_FITMENT,
    INSTALLER_REPAIR,
    INSTALLER_REMOVAL,
    DEVICE_CONNECT,
    DEVICE_SETTINGS,
    DEVICE_DIAGNOSTICS,
    DEVICE_KEY_MANAGEMENT,
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
