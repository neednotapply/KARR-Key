package com.karr.master.feature.dealer

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
fun DealerFeatureScreen(
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
                Text("Dealer", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Inventory and unit service tools reconstructed from BT Dealer 3.131.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Inventory snapshot", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${vehicles.size} saved vehicles",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Text("Feature catalog", style = MaterialTheme.typography.titleMedium)
            Text(
                "${FeatureCatalog.dealer.size} dealer features",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FeatureCatalog.dealer.groupBy(FeatureDefinition::category).forEach { (category, features) ->
            item(key = "category:$category") {
                Text(
                    category,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(features, key = FeatureDefinition::id) { feature ->
                val decision = policy.authorize(session, feature, deviceAuthenticated)
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
                            AuthorizationDecision.Allowed -> Button(
                                onClick = { onOpenFeature(feature) },
                            ) {
                                Text("Open")
                            }
                            is AuthorizationDecision.Denied -> {
                                Text(
                                    decision.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Button(onClick = { onOpenFeature(feature) }) {
                                    Text("Inspect")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
