package com.karr.key.core

object ResearchAccess {
    fun rolesForProvisionedKeys(
        customerKeyProvisioned: Boolean,
        dealerKeyProvisioned: Boolean,
    ): Set<UserRole> = buildSet {
        if (customerKeyProvisioned) {
            add(UserRole.CUSTOMER)
        }
        if (dealerKeyProvisioned) {
            add(UserRole.DEALER)
            add(UserRole.INSTALLER)
        }
    }

    fun restrictSession(
        base: Session,
        customerKeyProvisioned: Boolean,
        dealerKeyProvisioned: Boolean,
    ): Session {
        val roles = rolesForProvisionedKeys(
            customerKeyProvisioned = customerKeyProvisioned,
            dealerKeyProvisioned = dealerKeyProvisioned,
        )
        return base.copy(
            displayName = if (roles.isEmpty()) {
                "Provision a research key"
            } else {
                "Local research access"
            },
            roles = base.roles intersect roles,
            capabilities = base.capabilities intersect FeatureCatalog.capabilitiesFor(roles),
        )
    }
}
