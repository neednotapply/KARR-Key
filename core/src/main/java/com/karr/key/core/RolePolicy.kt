package com.karr.key.core

class RolePolicy {
    fun authorize(
        session: Session?,
        feature: FeatureDefinition,
        deviceAuthenticated: Boolean = false,
    ): AuthorizationDecision {
        if (session == null) {
            return AuthorizationDecision.Denied("Provision local access material first.")
        }
        if (feature.requiredRole !in session.roles) {
            return AuthorizationDecision.Denied(
                "${feature.requiredRole.displayName()} role is required.",
            )
        }
        val missing = feature.requiredCapabilities - session.capabilities
        if (missing.isNotEmpty()) {
            return AuthorizationDecision.Denied(
                "Missing capability: ${missing.first().name}.",
            )
        }
        if (feature.safetySensitive && !deviceAuthenticated) {
            return AuthorizationDecision.Denied(
                "An authenticated Bluetooth device is required for this operation.",
            )
        }
        return AuthorizationDecision.Allowed
    }
}

fun UserRole.displayName(): String = when (this) {
    UserRole.CUSTOMER -> "Customer"
    UserRole.DEALER -> "Dealer"
    UserRole.INSTALLER -> "Installer"
}
