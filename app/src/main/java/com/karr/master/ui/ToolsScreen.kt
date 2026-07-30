package com.karr.master.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karr.master.R
import com.karr.master.core.FeatureCatalog
import com.karr.master.core.FeatureDefinition
import com.karr.master.core.Session
import com.karr.master.core.TechnicianJob
import com.karr.master.core.UserRole
import com.karr.master.core.VehicleSummary
import com.karr.master.feature.customer.CustomerWorkspaceSummary
import com.karr.master.feature.dealer.DealerWorkspaceSummary
import com.karr.master.feature.installer.InstallerWorkspaceSummary

@Composable
internal fun AdaptiveToolsScreen(
    session: Session,
    selectedRole: UserRole?,
    selectedFeature: FeatureDefinition?,
    vehicles: List<VehicleSummary>,
    jobs: List<TechnicianJob>,
    deviceAuthenticated: Boolean,
    query: String,
    selectedCategory: String?,
    listState: LazyListState,
    onRoleSelected: (UserRole) -> Unit,
    onQueryChanged: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onOpenFeature: (FeatureDefinition) -> Unit,
    onOpenAccessKeys: () -> Unit,
    detailContent: @Composable (FeatureDefinition) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val expanded = maxWidth >= 960.dp
        if (expanded && selectedFeature != null) {
            Row(Modifier.fillMaxSize()) {
                ToolsCatalogPane(
                    session = session,
                    selectedRole = selectedRole,
                    vehicles = vehicles,
                    jobs = jobs,
                    deviceAuthenticated = deviceAuthenticated,
                    query = query,
                    selectedCategory = selectedCategory,
                    listState = listState,
                    onRoleSelected = onRoleSelected,
                    onQueryChanged = onQueryChanged,
                    onCategorySelected = onCategorySelected,
                    onOpenFeature = onOpenFeature,
                    onOpenAccessKeys = onOpenAccessKeys,
                    modifier = Modifier
                        .weight(0.43f)
                        .fillMaxSize(),
                )
                VerticalDivider()
                Column(
                    modifier = Modifier
                        .weight(0.57f)
                        .fillMaxSize(),
                ) {
                    detailContent(selectedFeature)
                }
            }
        } else if (selectedFeature != null) {
            detailContent(selectedFeature)
        } else {
            ToolsCatalogPane(
                session = session,
                selectedRole = selectedRole,
                vehicles = vehicles,
                jobs = jobs,
                deviceAuthenticated = deviceAuthenticated,
                query = query,
                selectedCategory = selectedCategory,
                listState = listState,
                onRoleSelected = onRoleSelected,
                onQueryChanged = onQueryChanged,
                onCategorySelected = onCategorySelected,
                onOpenFeature = onOpenFeature,
                onOpenAccessKeys = onOpenAccessKeys,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ToolsCatalogPane(
    session: Session,
    selectedRole: UserRole?,
    vehicles: List<VehicleSummary>,
    jobs: List<TechnicianJob>,
    deviceAuthenticated: Boolean,
    query: String,
    selectedCategory: String?,
    listState: LazyListState = rememberLazyListState(),
    onRoleSelected: (UserRole) -> Unit,
    onQueryChanged: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onOpenFeature: (FeatureDefinition) -> Unit,
    onOpenAccessKeys: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val availableRoles = UserRole.entries.filter(session.roles::contains)
    if (availableRoles.isEmpty()) {
        ToolsLockedState(
            onOpenAccessKeys = onOpenAccessKeys,
            modifier = modifier,
        )
        return
    }

    val role = selectedRole?.takeIf(availableRoles::contains) ?: availableRoles.first()
    val features = FeatureCatalog.forRole(role)
    val categories = features.map(FeatureDefinition::category).distinct()
    val filtered = toolsFeaturesFor(role, query, selectedCategory)

    LaunchedEffect(role, categories) {
        if (selectedCategory != null && selectedCategory !in categories) {
            onCategorySelected(null)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            bottom = 32.dp,
        ),
    ) {
        item(key = "role-tabs") {
            if (availableRoles.size > 1) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = availableRoles.indexOf(role),
                ) {
                    availableRoles.forEach { tabRole ->
                        Tab(
                            selected = role == tabRole,
                            onClick = { onRoleSelected(tabRole) },
                            text = { Text(tabRole.displayLabel()) },
                        )
                    }
                }
            }
        }

        item(key = "workspace-summary:$role") {
            WorkspaceSummary(
                role = role,
                vehicles = vehicles,
                jobs = jobs,
            )
        }

        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                label = { Text("Search ${role.displayLabel().lowercase()} tools") },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                    )
                },
                supportingText = {
                    Text("${filtered.size} of ${features.size} tools")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(key = "categories:$role") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { onCategorySelected(null) },
                    label = { Text("All") },
                )
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = {
                            onCategorySelected(
                                category.takeUnless { it == selectedCategory },
                            )
                        },
                        label = { Text(category) },
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "No matching tools",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Clear the search or select another category.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            filtered
                .groupBy(FeatureDefinition::category)
                .forEach { (category, categoryFeatures) ->
                    item(key = "heading:$role:$category") {
                        Text(
                            text = category,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(
                        items = categoryFeatures,
                        key = FeatureDefinition::id,
                    ) { feature ->
                        FeatureListRow(
                            session = session,
                            feature = feature,
                            deviceAuthenticated = deviceAuthenticated,
                            onOpen = { onOpenFeature(feature) },
                        )
                    }
                }
        }
    }
}

@Composable
private fun WorkspaceSummary(
    role: UserRole,
    vehicles: List<VehicleSummary>,
    jobs: List<TechnicianJob>,
) {
    when (role) {
        UserRole.CUSTOMER -> CustomerWorkspaceSummary(vehicles)
        UserRole.DEALER -> DealerWorkspaceSummary(vehicles)
        UserRole.INSTALLER -> InstallerWorkspaceSummary(jobs)
    }
}

@Composable
private fun FeatureListRow(
    session: Session,
    feature: FeatureDefinition,
    deviceAuthenticated: Boolean,
    onOpen: () -> Unit,
) {
    val availability = featureAvailability(
        session = session,
        feature = feature,
        deviceAuthenticated = deviceAuthenticated,
    )
    OutlinedCard(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = feature.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                )
            }
            Text(
                text = feature.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusPill(availability)
        }
    }
}

@Composable
private fun ToolsLockedState(
    onOpenAccessKeys: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ResponsiveContent(modifier = modifier) { contentModifier ->
        Column(
            modifier = contentModifier.padding(top = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(
                title = "Tools are locked",
                supportingText = "Provision an authorized local research key to open the role workspaces.",
            )
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Customer material unlocks Customer tools. Dealer material unlocks Dealer and Installer tools.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenAccessKeys) {
                        Text("Open access keys")
                    }
                }
            }
        }
    }
}

private fun UserRole.displayLabel(): String = when (this) {
    UserRole.CUSTOMER -> "Customer"
    UserRole.DEALER -> "Dealer"
    UserRole.INSTALLER -> "Installer"
}

internal fun toolsFeaturesFor(
    role: UserRole,
    query: String = "",
    category: String? = null,
): List<FeatureDefinition> {
    val normalizedQuery = query.trim()
    return FeatureCatalog.forRole(role).filter { feature ->
        val categoryMatches = category == null || feature.category == category
        val queryMatches = normalizedQuery.isEmpty() ||
            feature.title.contains(normalizedQuery, ignoreCase = true) ||
            feature.summary.contains(normalizedQuery, ignoreCase = true) ||
            feature.category.contains(normalizedQuery, ignoreCase = true)
        categoryMatches && queryMatches
    }
}
