package com.karr.master.data

import com.karr.master.core.FeatureCatalog
import com.karr.master.core.Session
import com.karr.master.core.UserRole

/**
 * Local access state only. KARR Master has no backend or record repository.
 */
class LocalMasterRepository : MasterRepository {
    override fun localSession(): Session = Session(
        displayName = "Local access",
        roles = UserRole.entries.toSet(),
        capabilities = FeatureCatalog.capabilitiesFor(UserRole.entries.toSet()),
    )
}
