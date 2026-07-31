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
import com.karr.key.core.UserRole
import com.karr.key.core.displayName
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.RecoveredDeviceOperation
import com.karr.key.protocol.RecoveredWorkflowCatalog

@Composable
internal fun AdaptiveOperationsScreen(
    selectedOperation: RecoveredDeviceOperation?,
    deviceAuthenticated: Boolean,
    unitModel: KarrUnitModel?,
    query: String,
    selectedCategory: String?,
    listState: LazyListState,
    onQueryChanged: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onOpenOperation: (RecoveredDeviceOperation) -> Unit,
    detailContent: @Composable (RecoveredDeviceOperation) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val expanded = maxWidth >= 960.dp
        if (expanded && selectedOperation != null) {
            Row(Modifier.fillMaxSize()) {
                OperationsCatalogPane(
                    deviceAuthenticated = deviceAuthenticated,
                    unitModel = unitModel,
                    query = query,
                    selectedCategory = selectedCategory,
                    listState = listState,
                    onQueryChanged = onQueryChanged,
                    onCategorySelected = onCategorySelected,
                    onOpenOperation = onOpenOperation,
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
                    detailContent(selectedOperation)
                }
            }
        } else if (selectedOperation != null) {
            detailContent(selectedOperation)
        } else {
            OperationsCatalogPane(
                deviceAuthenticated = deviceAuthenticated,
                unitModel = unitModel,
                query = query,
                selectedCategory = selectedCategory,
                listState = listState,
                onQueryChanged = onQueryChanged,
                onCategorySelected = onCategorySelected,
                onOpenOperation = onOpenOperation,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun OperationsCatalogPane(
    deviceAuthenticated: Boolean,
    unitModel: KarrUnitModel?,
    query: String,
    selectedCategory: String?,
    listState: LazyListState = rememberLazyListState(),
    onQueryChanged: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onOpenOperation: (RecoveredDeviceOperation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val operations = RecoveredWorkflowCatalog.operations
    val categories = operations.map(RecoveredDeviceOperation::category).distinct()
    val filtered = deviceOperationsFor(query, selectedCategory)

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
        if (deviceAuthenticated) {
            item(key = "connected-model") {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Connected unit",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            unitModel?.displayName ?: "Model not identified",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (unitModel?.isRecognized == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Text(
                            if (unitModel?.isRecognized == true) {
                                "Model-restricted operations are checked before they can run."
                            } else {
                                "Model-restricted operations stay disabled until the unit " +
                                    "reports a recognized model."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                label = { Text("Search operations") },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                    )
                },
                supportingText = {
                    Text("${filtered.size} of ${operations.size} operations")
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
                        "No matching operations",
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
                .groupBy(RecoveredDeviceOperation::category)
                .forEach { (category, categoryOperations) ->
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
                        items = categoryOperations,
                        key = RecoveredDeviceOperation::id,
                    ) { operation ->
                        OperationListRow(
                            operation = operation,
                            onOpen = { onOpenOperation(operation) },
                        )
                    }
                }
        }
    }
}

@Composable
private fun OperationListRow(
    operation: RecoveredDeviceOperation,
    onOpen: () -> Unit,
) {
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
                    text = operation.label,
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
                text = operation.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                operation.roles.forEach { role ->
                    RoleTag(role)
                }
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
            text = role.displayName(),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal fun deviceOperationsFor(
    query: String = "",
    category: String? = null,
): List<RecoveredDeviceOperation> {
    val normalizedQuery = query.trim()
    return RecoveredWorkflowCatalog.operations.filter { operation ->
        val categoryMatches = category == null || operation.category == category
        val queryMatches = normalizedQuery.isEmpty() ||
            operation.label.contains(normalizedQuery, ignoreCase = true) ||
            operation.description.contains(normalizedQuery, ignoreCase = true) ||
            operation.category.contains(normalizedQuery, ignoreCase = true) ||
            operation.command.name.contains(normalizedQuery, ignoreCase = true) ||
            operation.roles.any { role ->
                role.displayName().contains(normalizedQuery, ignoreCase = true)
            } ||
            operation.bindings.any { binding ->
                binding.feature.title.contains(normalizedQuery, ignoreCase = true) ||
                    binding.feature.category.contains(normalizedQuery, ignoreCase = true)
            }
        categoryMatches && queryMatches
    }
}
