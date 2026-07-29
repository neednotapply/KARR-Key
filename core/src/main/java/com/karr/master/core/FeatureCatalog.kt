package com.karr.master.core

object FeatureCatalog {
    val customer = listOf(
        feature(
            "customer.controls",
            "Vehicle controls",
            "Lock, unlock, silent variants, trunk, valet, and immobilizer.",
            UserRole.CUSTOMER,
            Capability.CUSTOMER_CONTROL,
            safetySensitive = true,
        ),
        feature(
            "customer.alerts",
            "Alerts",
            "Vehicle alerts and alert configuration.",
            UserRole.CUSTOMER,
            Capability.CUSTOMER_ALERTS,
        ),
        feature(
            "customer.geofences",
            "Geofences",
            "List, create, and edit authorized vehicle geofences.",
            UserRole.CUSTOMER,
            Capability.CUSTOMER_GEOFENCES,
        ),
        feature(
            "customer.logs",
            "Logs and violations",
            "Vehicle activity, requests, diagnostics, trips, and violations.",
            UserRole.CUSTOMER,
            Capability.CUSTOMER_LOGS,
        ),
        feature(
            "customer.users",
            "Users",
            "Verification, user list, details, and delegated access.",
            UserRole.CUSTOMER,
            Capability.CUSTOMER_USERS,
        ),
    )

    val dealer = listOf(
        feature(
            "dealer.inventory",
            "Inventory",
            "Vehicle/serial search, status, products, location, and account scope.",
            UserRole.DEALER,
            Capability.DEALER_INVENTORY,
        ),
        feature(
            "dealer.sales",
            "Sales and unwind",
            "Sale, pending sale, unwind, and offline sale workflows.",
            UserRole.DEALER,
            Capability.DEALER_SALES,
            safetySensitive = true,
        ),
        feature(
            "dealer.bookings",
            "Bookings",
            "BCA/booking creation, return, tent mode, and snow mode.",
            UserRole.DEALER,
            Capability.DEALER_BOOKINGS,
            safetySensitive = true,
        ),
        feature(
            "dealer.device",
            "Unit service",
            "State, settings, battery, ignition, remotes, and violations.",
            UserRole.DEALER,
            Capability.DEALER_DEVICE_SERVICE,
            safetySensitive = true,
        ),
        feature(
            "dealer.artifacts",
            "CAN and firmware",
            "Authorized CAN files and signed firmware transfer.",
            UserRole.DEALER,
            Capability.DEVICE_FIRMWARE_TRANSFER,
            safetySensitive = true,
        ),
    )

    val installer = listOf(
        feature(
            "installer.jobs",
            "Technician jobs",
            "Summary, schedule, account, product, vehicle, and unit selection.",
            UserRole.INSTALLER,
            Capability.INSTALLER_JOBS,
        ),
        feature(
            "installer.fitment",
            "Install",
            "VIN/unit capture, CAN selection, checks, tests, and completion.",
            UserRole.INSTALLER,
            Capability.INSTALLER_FITMENT,
            safetySensitive = true,
        ),
        feature(
            "installer.repair",
            "Repair and replacement",
            "Repair validation, replacement/swop, settings, and test evidence.",
            UserRole.INSTALLER,
            Capability.INSTALLER_REPAIR,
            safetySensitive = true,
        ),
        feature(
            "installer.removal",
            "Removal",
            "Product/unit removal and battery-removal workflow.",
            UserRole.INSTALLER,
            Capability.INSTALLER_REMOVAL,
            safetySensitive = true,
        ),
        feature(
            "installer.offline",
            "Offline queue",
            "Encrypted durable fitment operations and later idempotent push.",
            UserRole.INSTALLER,
            Capability.OFFLINE_QUEUE,
        ),
    )

    val all: List<FeatureDefinition> = customer + dealer + installer

    private fun feature(
        id: String,
        title: String,
        summary: String,
        role: UserRole,
        capability: Capability,
        safetySensitive: Boolean = false,
    ) = FeatureDefinition(
        id = id,
        title = title,
        summary = summary,
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
