package com.karr.key.data

import com.karr.key.core.FeatureCatalog
import com.karr.key.core.Session
import com.karr.key.core.UserRole

/**
 * Local access state only. KARR Key has no backend or record repository.
 */
class LocalKeyRepository : KeyRepository {
    override fun localSession(): Session = Session(
        displayName = "Local access",
        roles = UserRole.entries.toSet(),
        capabilities = FeatureCatalog.capabilitiesFor(UserRole.entries.toSet()),
    )
}
