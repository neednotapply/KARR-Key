package com.karr.master.feature.installer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.karr.master.core.TechnicianJob

/**
 * Role-specific local work-record summary used by the shared adaptive catalog.
 */
@Composable
fun InstallerWorkspaceSummary(
    jobs: List<TechnicianJob>,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier.fillMaxWidth()) {
        if (jobs.isEmpty()) {
            ListItem(
                headlineContent = { Text("Work records") },
                supportingContent = { Text("No local work records yet.") },
            )
        } else {
            Column {
                ListItem(
                    headlineContent = { Text("Work records") },
                    supportingContent = { Text("${jobs.size} local jobs") },
                )
                jobs.take(3).forEach { job ->
                    ListItem(
                        headlineContent = { Text(job.type.name) },
                        supportingContent = {
                            Text("${job.jobId} • ${job.state}")
                        },
                    )
                }
            }
        }
    }
}
