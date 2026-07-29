package com.karr.master.data

import com.karr.master.core.Capability
import com.karr.master.core.Session
import com.karr.master.core.TechnicianJob
import com.karr.master.core.UserRole
import com.karr.master.core.VehicleSummary

/**
 * Local application state only. KARR Master has no backend adapter and does
 * not synthesize account, vehicle, or job records.
 */
class LocalMasterRepository : MasterRepository {
    override fun localSession(): Session = Session(
        displayName = "Offline workspace",
        roles = UserRole.entries.toSet(),
        capabilities = Capability.entries.toSet(),
    )

    override fun vehicles(): List<VehicleSummary> = emptyList()

    override fun jobs(): List<TechnicianJob> = emptyList()
}
