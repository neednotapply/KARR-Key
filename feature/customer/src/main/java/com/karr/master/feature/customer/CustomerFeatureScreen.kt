package com.karr.master.feature.customer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.karr.master.core.VehicleSummary

/**
 * Role-specific local-record summary. The shared app catalog owns search,
 * filtering, availability, and feature-row presentation for every role.
 */
@Composable
fun CustomerWorkspaceSummary(
    vehicles: List<VehicleSummary>,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier.fillMaxWidth()) {
        if (vehicles.isEmpty()) {
            ListItem(
                headlineContent = { Text("Customer workspace") },
                supportingContent = { Text("No locally saved vehicles.") },
            )
        } else {
            Column {
                ListItem(
                    headlineContent = { Text("Saved vehicles") },
                    supportingContent = { Text("${vehicles.size} local records") },
                )
                vehicles.take(3).forEach { vehicle ->
                    ListItem(
                        headlineContent = {
                            Text(
                                "${vehicle.year ?: ""} ${vehicle.make} " +
                                    vehicle.model,
                            )
                        },
                        supportingContent = {
                            Text("VIN …${vehicle.vinSuffix} • ${vehicle.status}")
                        },
                    )
                }
            }
        }
    }
}
