package com.karr.master.feature.dealer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.karr.master.core.VehicleSummary

/**
 * Role-specific local inventory summary used by the shared adaptive catalog.
 */
@Composable
fun DealerWorkspaceSummary(
    vehicles: List<VehicleSummary>,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text("Inventory snapshot") },
            supportingContent = {
                Text("${vehicles.size} locally saved vehicles")
            },
        )
    }
}
