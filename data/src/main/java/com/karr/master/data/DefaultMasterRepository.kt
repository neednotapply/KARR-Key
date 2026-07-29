package com.karr.master.data

import com.karr.master.core.AccountScope
import com.karr.master.core.Capability
import com.karr.master.core.JobType
import com.karr.master.core.Session
import com.karr.master.core.TechnicianJob
import com.karr.master.core.UserRole
import com.karr.master.core.VehicleSummary
import java.time.Instant

class DefaultMasterRepository(
    private val serverApi: ServerApi = UnconfiguredServerApi(),
    private val tokenStore: TokenStore? = null,
) : MasterRepository {
    override suspend fun login(username: String, password: CharArray): Result<Session> =
        try {
            serverApi.authenticate(username, password).onSuccess { session ->
                tokenStore?.put("session_user_id", session.userId)
            }
        } finally {
            password.fill('\u0000')
        }

    override suspend fun demoSession(roles: Set<UserRole>): Session {
        val selectedRoles = roles.ifEmpty { UserRole.entries.toSet() }
        return Session(
            userId = "local-demo",
            displayName = "Local integration demo",
            roles = selectedRoles,
            capabilities = capabilitiesFor(selectedRoles),
            accounts = listOf(
                AccountScope("demo-dealer", "Demo Dealer"),
                AccountScope("demo-customer", "Demo Customer"),
            ),
            activeAccountId = "demo-dealer",
            liveAuthorization = false,
            expiresAt = null,
            demo = true,
        )
    }

    override suspend fun vehicles(session: Session): Result<List<VehicleSummary>> =
        if (session.demo) {
            Result.success(demoVehicles)
        } else {
            serverApi.vehicles(session)
        }

    override suspend fun jobs(session: Session): Result<List<TechnicianJob>> =
        if (session.demo) {
            Result.success(demoJobs)
        } else {
            serverApi.jobs(session)
        }

    override suspend fun logout() {
        tokenStore?.clear()
    }

    private fun capabilitiesFor(roles: Set<UserRole>): Set<Capability> = buildSet {
        add(Capability.VEHICLE_READ)
        add(Capability.VEHICLE_LOCATION)
        add(Capability.DEVICE_CONNECT)
        if (UserRole.CUSTOMER in roles) {
            add(Capability.CUSTOMER_CONTROL)
            add(Capability.CUSTOMER_ALERTS)
            add(Capability.CUSTOMER_GEOFENCES)
            add(Capability.CUSTOMER_LOGS)
            add(Capability.CUSTOMER_USERS)
        }
        if (UserRole.DEALER in roles) {
            add(Capability.DEALER_INVENTORY)
            add(Capability.DEALER_SALES)
            add(Capability.DEALER_BOOKINGS)
            add(Capability.DEALER_DEVICE_SERVICE)
            add(Capability.DEVICE_SETTINGS)
            add(Capability.DEVICE_DIAGNOSTICS)
            add(Capability.DEVICE_CAN_TRANSFER)
            add(Capability.DEVICE_FIRMWARE_TRANSFER)
            add(Capability.OFFLINE_QUEUE)
        }
        if (UserRole.INSTALLER in roles) {
            add(Capability.INSTALLER_JOBS)
            add(Capability.INSTALLER_FITMENT)
            add(Capability.INSTALLER_REPAIR)
            add(Capability.INSTALLER_REMOVAL)
            add(Capability.DEVICE_SETTINGS)
            add(Capability.DEVICE_DIAGNOSTICS)
            add(Capability.DEVICE_CAN_TRANSFER)
            add(Capability.DEVICE_FIRMWARE_TRANSFER)
            add(Capability.DEVICE_KEY_MANAGEMENT)
            add(Capability.OFFLINE_QUEUE)
        }
    }

    private companion object {
        val demoVehicles = listOf(
            VehicleSummary(
                vehicleId = "vehicle-001",
                unitSerial = "DEMO-UNIT-001",
                vinSuffix = "123456",
                year = 2025,
                make = "Demo",
                model = "Sedan",
                color = "Black",
                stockNumber = "D-1001",
                status = "Inventory",
            ),
            VehicleSummary(
                vehicleId = "vehicle-002",
                unitSerial = "DEMO-UNIT-002",
                vinSuffix = "654321",
                year = 2024,
                make = "Demo",
                model = "SUV",
                color = "Silver",
                stockNumber = "D-1002",
                status = "Customer",
            ),
        )

        val demoJobs = listOf(
            TechnicianJob(
                jobId = "job-001",
                accountId = "demo-dealer",
                vehicleId = "vehicle-001",
                type = JobType.INSTALL,
                scheduledUtc = Instant.parse("2026-07-28T18:00:00Z"),
                state = "Scheduled",
            ),
            TechnicianJob(
                jobId = "job-002",
                accountId = "demo-dealer",
                vehicleId = "vehicle-002",
                type = JobType.REPAIR,
                scheduledUtc = Instant.parse("2026-07-28T20:00:00Z"),
                state = "Ready",
            ),
        )
    }
}
