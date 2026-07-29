package com.karr.master.feature.customer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.karr.master.core.AuthorizationDecision
import com.karr.master.core.FeatureCatalog
import com.karr.master.core.FeatureDefinition
import com.karr.master.core.RolePolicy
import com.karr.master.core.Session
import com.karr.master.core.VehicleSummary

@Composable
fun CustomerFeatureScreen(
    session: Session,
    vehicles: List<VehicleSummary>,
    deviceAuthenticated: Boolean,
    onOpenFeature: (FeatureDefinition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val policy = RolePolicy()
    LazyColumn(
        modifier = modifier.padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Customer", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Vehicle controls and account tools reconstructed from KARR BT 2.15.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (vehicles.isNotEmpty()) {
            item {
                Text("Vehicles", style = MaterialTheme.typography.titleMedium)
            }
            items(vehicles, key = { it.vehicleId }) { vehicle ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "${vehicle.year ?: ""} ${vehicle.make} ${vehicle.model}".trim(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "VIN …${vehicle.vinSuffix}  •  ${vehicle.status}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Text(
                "Feature catalog",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${FeatureCatalog.customer.size} customer features",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FeatureCatalog.customer.groupBy(FeatureDefinition::category).forEach { (category, features) ->
            item(key = "category:$category") {
                Text(
                    category,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(features, key = FeatureDefinition::id) { feature ->
                val decision = policy.authorize(session, feature, deviceAuthenticated)
                CustomerFeatureCard(
                    feature = feature,
                    decision = decision,
                ) {
                    onOpenFeature(feature)
                }
            }
        }
    }
}

@Composable
private fun CustomerFeatureCard(
    feature: FeatureDefinition,
    decision: AuthorizationDecision,
    onOpen: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(feature.title, style = MaterialTheme.typography.titleMedium)
                if (feature.safetySensitive) {
                    Text(
                        "BLE AUTH",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                feature.summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (decision) {
                AuthorizationDecision.Allowed -> Button(onClick = onOpen) {
                    Text("Open")
                }
                is AuthorizationDecision.Denied -> Text(
                    decision.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (decision is AuthorizationDecision.Denied) {
                Button(onClick = onOpen) {
                    Text("Inspect")
                }
            }
        }
    }
}
