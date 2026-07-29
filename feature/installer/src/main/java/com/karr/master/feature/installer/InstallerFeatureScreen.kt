package com.karr.master.feature.installer

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
import com.karr.master.core.TechnicianJob

@Composable
fun InstallerFeatureScreen(
    session: Session,
    jobs: List<TechnicianJob>,
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
                Text("Installer", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Job-scoped fitment workflows migrated from Installer 1.39.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Text("Technician jobs", style = MaterialTheme.typography.titleMedium)
        }
        items(jobs, key = { it.jobId }) { job ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(job.type.name, style = MaterialTheme.typography.titleMedium)
                        Text(job.state, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "Job ${job.jobId} • ${job.scheduledUtc ?: "unscheduled"}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Text("Workflows", style = MaterialTheme.typography.titleMedium)
        }
        items(FeatureCatalog.installer, key = FeatureDefinition::id) { feature ->
            val decision = policy.authorize(session, feature)
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
                                "JOB REQUIRED",
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
                        is AuthorizationDecision.Denied -> Text(
                            decision.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
