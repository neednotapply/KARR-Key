package com.karr.key.core

object FeatureCatalog {
    val customer = listOf(
        feature(
            "customer.vehicles",
            "Vehicle identity",
            "Read operating state and mode from the connected unit.",
            "Vehicle information",
            UserRole.CUSTOMER,
            Capability.VEHICLE_READ,
        ),
        feature(
            "customer.controls",
            "Lock and arm",
            "Lock the authorized vehicle and arm its security system.",
            "Vehicle controls",
            UserRole.CUSTOMER,
            Capability.CUSTOMER_CONTROL,
            safetySensitive = true,
        ),
        feature(
            "customer.unlock",
            "Unlock and disarm",
            "Unlock the authorized vehicle and disarm its security system.",
            "Vehicle controls",
            UserRole.CUSTOMER,
            Capability.CUSTOMER_CONTROL,
            safetySensitive = true,
        ),
        feature(
            "customer.silent_controls",
            "Silent lock and unlock",
            "Run the authorized lock or unlock command without audible confirmation.",
            "Vehicle controls",
            UserRole.CUSTOMER,
            Capability.CUSTOMER_CONTROL,
            safetySensitive = true,
        ),
        feature(
            "customer.trunk",
            "Trunk release",
            "Release the trunk on a compatible QT6 RED system.",
            "Vehicle controls",
            UserRole.CUSTOMER,
            Capability.CUSTOMER_CONTROL,
            safetySensitive = true,
        ),
        feature(
            "customer.valet",
            "Valet mode",
            "Enter or return from valet mode for the selected vehicle.",
            "Vehicle controls",
            UserRole.CUSTOMER,
            Capability.CUSTOMER_CONTROL,
            safetySensitive = true,
        ),
        feature(
            "customer.immobilizer",
            "Immobilizer state",
            "Read immobilizer-related state from the connected unit.",
            "Vehicle controls",
            UserRole.CUSTOMER,
            Capability.CUSTOMER_CONTROL,
            safetySensitive = true,
        ),
        feature(
            "customer.remote_learning",
            "Remote learning",
            "Manage the authorized remote-learning workflow for the selected unit.",
            "Vehicle controls",
            UserRole.CUSTOMER,
            Capability.CUSTOMER_CONTROL,
            safetySensitive = true,
        ),
        feature(
            "customer.diagnostics",
            "Vehicle diagnostics",
            "Read state, battery, firmware, and violation data from the connected unit.",
            "Diagnostics",
            UserRole.CUSTOMER,
            Capability.DEVICE_DIAGNOSTICS,
        ),
        feature(
            "customer.settings",
            "Unit settings",
            "Read settings and violation-inhibit state from the connected unit.",
            "Diagnostics",
            UserRole.CUSTOMER,
            Capability.DEVICE_SETTINGS,
        ),
    )

    val dealer = listOf(
        feature(
            "dealer.device",
            "Unit state",
            "Read authorized unit identity and operating state.",
            "Unit service",
            UserRole.DEALER,
            Capability.DEALER_DEVICE_SERVICE,
        ),
        feature(
            "dealer.settings",
            "Unit settings",
            "Review and update authorized unit settings.",
            "Unit service",
            UserRole.DEALER,
            Capability.DEVICE_SETTINGS,
            safetySensitive = true,
        ),
        feature(
            "dealer.battery_ignition",
            "Battery and ignition",
            "Review battery, ignition, door, and movement state.",
            "Unit service",
            UserRole.DEALER,
            Capability.DEALER_DEVICE_SERVICE,
        ),
        feature(
            "dealer.remotes",
            "Remotes",
            "Review and manage authorized unit remotes.",
            "Unit service",
            UserRole.DEALER,
            Capability.DEALER_DEVICE_SERVICE,
            safetySensitive = true,
        ),
        feature(
            "dealer.violations",
            "Violations",
            "Review unit violation and alarm history.",
            "Unit service",
            UserRole.DEALER,
            Capability.DEALER_DEVICE_SERVICE,
        ),
        feature(
            "dealer.diagnostics",
            "Diagnostics",
            "Read state, battery, firmware, and violations from the connected unit.",
            "Unit service",
            UserRole.DEALER,
            Capability.DEVICE_DIAGNOSTICS,
        ),
    )

    val installer = listOf(
        feature(
            "installer.unit",
            "Unit identity",
            "Read operating state and mode from the connected unit.",
            "Unit inspection",
            UserRole.INSTALLER,
            Capability.INSTALLER_FITMENT,
        ),
        feature(
            "installer.fitment",
            "Installation state",
            "Read unit identity, mode, and vehicle battery before installation.",
            "Installation checks",
            UserRole.INSTALLER,
            Capability.INSTALLER_FITMENT,
        ),
        feature(
            "installer.checks",
            "Installation checks",
            "Read power, firmware, violation, identity, and learned-remote state.",
            "Installation checks",
            UserRole.INSTALLER,
            Capability.INSTALLER_FITMENT,
        ),
        feature(
            "installer.tests",
            "Validation tests",
            "Run recovered bump, arm/disarm, and trunk tests.",
            "Validation",
            UserRole.INSTALLER,
            Capability.INSTALLER_FITMENT,
            safetySensitive = true,
        ),
        feature(
            "installer.immobilizer_test",
            "Immobilizer output test",
            "Send the recovered experimental output candidate and observe the unit response.",
            "Validation",
            UserRole.INSTALLER,
            Capability.INSTALLER_FITMENT,
            safetySensitive = true,
        ),
        feature(
            "installer.repair",
            "Repair diagnostics",
            "Read state, battery, firmware, and violations from the connected unit.",
            "Diagnostics",
            UserRole.INSTALLER,
            Capability.INSTALLER_REPAIR,
        ),
        feature(
            "installer.removal",
            "Removal readiness",
            "Read unit state and mode before an authorized removal.",
            "Removal checks",
            UserRole.INSTALLER,
            Capability.INSTALLER_REMOVAL,
        ),
        feature(
            "installer.battery_removal",
            "Battery-removal check",
            "Read vehicle battery and unit state before battery removal.",
            "Removal checks",
            UserRole.INSTALLER,
            Capability.INSTALLER_REMOVAL,
        ),
        feature(
            "installer.settings",
            "Unit settings",
            "Read settings and violation-inhibit state from the connected unit.",
            "Diagnostics",
            UserRole.INSTALLER,
            Capability.DEVICE_SETTINGS,
        ),
    )

    val all: List<FeatureDefinition> = customer + dealer + installer

    fun forRole(role: UserRole): List<FeatureDefinition> = when (role) {
        UserRole.CUSTOMER -> customer
        UserRole.DEALER -> dealer
        UserRole.INSTALLER -> installer
    }

    fun capabilitiesFor(roles: Set<UserRole>): Set<Capability> =
        roles
            .flatMap(::forRole)
            .flatMap(FeatureDefinition::requiredCapabilities)
            .toSet()

    private fun feature(
        id: String,
        title: String,
        summary: String,
        category: String,
        role: UserRole,
        capability: Capability,
        safetySensitive: Boolean = false,
    ) = FeatureDefinition(
        id = id,
        title = title,
        summary = summary,
        category = category,
        sourceApp = when (role) {
            UserRole.CUSTOMER -> "KARR BT 2.15"
            UserRole.DEALER -> "KARR BT Dealer 3.131"
            UserRole.INSTALLER -> "KARR Installer 1.39"
        },
        requiredRole = role,
        requiredCapabilities = setOf(capability),
        safetySensitive = safetySensitive,
    )
}
