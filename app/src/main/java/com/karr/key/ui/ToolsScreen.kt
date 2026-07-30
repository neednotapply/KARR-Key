package com.karr.key.ui

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.karr.key.R
import com.karr.key.core.FeatureCatalog
import com.karr.key.core.FeatureDefinition
import com.karr.key.core.Session
import com.karr.key.core.UserRole

@Composable
internal fun AdaptiveToolsScreen(
    session: Session,
    selectedFeature: FeatureDefinition?,
    deviceAuthenticated: Boolean,
    query: String,
    selectedCategory: String?,
    listState: LazyListState,
    onQueryChanged: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onOpenFeature: (FeatureDefinition) -> Unit,
    detailContent: @Composable (FeatureDefinition) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val expanded = maxWidth >= 960.dp
        if (expanded && selectedFeature != null) {
            Row(Modifier.fillMaxSize()) {
                ToolsCatalogPane(
                    session = session,
                    deviceAuthenticated = deviceAuthenticated,
                    query = query,
                    selectedCategory = selectedCategory,
                    listState = listState,
                    onQueryChanged = onQueryChanged,
                    onCategorySelected = onCategorySelected,
                    onOpenFeature = onOpenFeature,
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
                deviceAuthenticated = deviceAuthenticated,
                query = query,
                selectedCategory = selectedCategory,
                listState = listState,
                onQueryChanged = onQueryChanged,
                onCategorySelected = onCategorySelected,
                onOpenFeature = onOpenFeature,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ToolsCatalogPane(
    session: Session,
    deviceAuthenticated: Boolean,
    query: String,
    selectedCategory: String?,
    listState: LazyListState = rememberLazyListState(),
    onQueryChanged: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onOpenFeature: (FeatureDefinition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val features = FeatureCatalog.all
    val categories = features.map(FeatureDefinition::category).distinct()
    val filtered = toolsFeaturesFor(query, selectedCategory)

    LaunchedEffect(categories) {
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
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                label = { Text("Search tools") },
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

        item(key = "categories") {
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
                    item(key = "heading:$category") {
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoleTag(feature.requiredRole)
                StatusPill(availability)
            }
        }
    }
}

@Composable
private fun RoleTag(role: UserRole) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = role.displayLabel(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun UserRole.displayLabel(): String = when (this) {
    UserRole.CUSTOMER -> "Customer"
    UserRole.DEALER -> "Dealer"
    UserRole.INSTALLER -> "Installer"
}

internal fun toolsFeaturesFor(
    query: String = "",
    category: String? = null,
): List<FeatureDefinition> {
    val normalizedQuery = query.trim()
    return FeatureCatalog.all.filter { feature ->
        val categoryMatches = category == null || feature.category == category
        val queryMatches = normalizedQuery.isEmpty() ||
            feature.title.contains(normalizedQuery, ignoreCase = true) ||
            feature.summary.contains(normalizedQuery, ignoreCase = true) ||
            feature.category.contains(normalizedQuery, ignoreCase = true) ||
            feature.requiredRole.displayLabel().contains(
                normalizedQuery,
                ignoreCase = true,
            )
        categoryMatches && queryMatches
    }
}
