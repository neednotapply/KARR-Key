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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karr.key.R
import com.karr.key.core.Session
import com.karr.key.core.UserRole
import com.karr.key.core.displayName
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.RecoveredDeviceOperation
import com.karr.key.protocol.RecoveredModelCompatibility
import com.karr.key.protocol.RecoveredWorkflowCatalog
import com.karr.key.protocol.compatibilityWith

internal enum class OperationTypeFilter(val displayLabel: String) {
    READS("Reads"),
    COMMANDS("Commands"),
}

internal data class OperationFilters(
    val workflow: String? = null,
    val operationType: OperationTypeFilter? = null,
) {
    companion object {
        val Saver = listSaver<OperationFilters, String>(
            save = { filters ->
                listOf(
                    filters.workflow.orEmpty(),
                    filters.operationType?.name.orEmpty(),
                )
            },
            restore = { values ->
                OperationFilters(
                    workflow = values.firstOrNull()?.ifBlank { null },
                    operationType = values
                        .getOrNull(1)
                        ?.ifBlank { null }
                        ?.let(OperationTypeFilter::valueOf),
                )
            },
        )
    }
}

@Composable
internal fun AdaptiveOperationsScreen(
    session: Session,
    selectedOperation: RecoveredDeviceOperation?,
    deviceAuthenticated: Boolean,
    unitModel: KarrUnitModel?,
    query: String,
    filters: OperationFilters,
    listState: LazyListState,
    onQueryChanged: (String) -> Unit,
    onFiltersChanged: (OperationFilters) -> Unit,
    onOpenOperation: (RecoveredDeviceOperation) -> Unit,
    detailContent: @Composable (RecoveredDeviceOperation) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val expanded = maxWidth >= 960.dp
        if (expanded && selectedOperation != null) {
            Row(Modifier.fillMaxSize()) {
                OperationsCatalogPane(
                    session = session,
                    deviceAuthenticated = deviceAuthenticated,
                    unitModel = unitModel,
                    query = query,
                    filters = filters,
                    listState = listState,
                    onQueryChanged = onQueryChanged,
                    onFiltersChanged = onFiltersChanged,
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
                session = session,
                deviceAuthenticated = deviceAuthenticated,
                unitModel = unitModel,
                query = query,
                filters = filters,
                listState = listState,
                onQueryChanged = onQueryChanged,
                onFiltersChanged = onFiltersChanged,
                onOpenOperation = onOpenOperation,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun OperationsCatalogPane(
    session: Session,
    deviceAuthenticated: Boolean,
    unitModel: KarrUnitModel?,
    query: String,
    filters: OperationFilters,
    listState: LazyListState = rememberLazyListState(),
    onQueryChanged: (String) -> Unit,
    onFiltersChanged: (OperationFilters) -> Unit,
    onOpenOperation: (RecoveredDeviceOperation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val operations = RecoveredWorkflowCatalog.operations
    val workflows = workflowFiltersFor(operations)
    val filtered = deviceOperationsFor(
        query = query,
        filters = filters,
    )

    LaunchedEffect(workflows) {
        if (filters.workflow != null && filters.workflow !in workflows) {
            onFiltersChanged(filters.copy(workflow = null))
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.testTag("operations-catalog"),
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

        item(key = "operation-filters") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filters.workflow == null && filters.operationType == null,
                    onClick = { onFiltersChanged(OperationFilters()) },
                    label = { Text("All") },
                    modifier = Modifier.testTag("operation-filter:all"),
                )
                OperationTypeFilter.entries.forEach { operationType ->
                    val selected = filters.operationType == operationType
                    FilterChip(
                        selected = selected,
                        onClick = {
                            onFiltersChanged(
                                if (selected) {
                                    OperationFilters()
                                } else {
                                    OperationFilters(operationType = operationType)
                                },
                            )
                        },
                        label = { Text(operationType.displayLabel) },
                        modifier = Modifier.testTag(
                            "operation-filter:${operationType.name.lowercase()}",
                        ),
                    )
                }
                workflows.forEach { workflow ->
                    val selected = filters.workflow == workflow
                    FilterChip(
                        selected = selected,
                        onClick = {
                            onFiltersChanged(
                                if (selected) {
                                    OperationFilters()
                                } else {
                                    OperationFilters(workflow = workflow)
                                },
                            )
                        },
                        label = { Text(workflow) },
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
                        "Clear the search or select another workflow.",
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
                            session = session,
                            operation = operation,
                            deviceAuthenticated = deviceAuthenticated,
                            unitModel = unitModel,
                            onOpen = { onOpenOperation(operation) },
                        )
                    }
                }
        }
    }
}

@Composable
private fun OperationListRow(
    session: Session,
    operation: RecoveredDeviceOperation,
    deviceAuthenticated: Boolean,
    unitModel: KarrUnitModel?,
    onOpen: () -> Unit,
) {
    val availability = operationModelAvailability(
        session = session,
        operation = operation,
        deviceAuthenticated = deviceAuthenticated,
        unitModel = unitModel,
    )
    val canOpen = availability.allowsOperationOpen()
    OutlinedCard(
        onClick = onOpen,
        enabled = canOpen,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("operation:${operation.id}"),
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
                if (canOpen) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                    )
                } else {
                    StatusPill(availability)
                }
            }
            Text(
                text = operation.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!canOpen) {
                Text(
                    text = availability.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (availability is FeatureAvailability.ModelUnsupported) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
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

internal fun operationModelAvailability(
    session: Session,
    operation: RecoveredDeviceOperation,
    deviceAuthenticated: Boolean,
    unitModel: KarrUnitModel?,
): FeatureAvailability {
    if (!deviceAuthenticated) {
        return FeatureAvailability.Available
    }
    return when (
        val compatibility = operation
            .preferredBinding(session, unitModel)
            .action
            .compatibilityWith(unitModel)
    ) {
        RecoveredModelCompatibility.Supported -> FeatureAvailability.Available
        is RecoveredModelCompatibility.Unsupported ->
            FeatureAvailability.ModelUnsupported(compatibility.reason)
        is RecoveredModelCompatibility.Unknown ->
            FeatureAvailability.ModelUnknown(compatibility.reason)
    }
}

internal fun FeatureAvailability.allowsOperationOpen(): Boolean =
    this !is FeatureAvailability.ModelUnsupported &&
        this !is FeatureAvailability.ModelUnknown

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
    filters: OperationFilters = OperationFilters(),
): List<RecoveredDeviceOperation> {
    val normalizedQuery = query.trim()
    return RecoveredWorkflowCatalog.operations.filter { operation ->
        val operationTypeMatches = when (filters.operationType) {
            null -> true
            OperationTypeFilter.READS -> !operation.mutating
            OperationTypeFilter.COMMANDS -> operation.mutating
        }
        val workflowMatches = filters.workflow == null || operation.bindings.any { binding ->
            binding.feature.category == filters.workflow
        }
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
        operationTypeMatches && workflowMatches && queryMatches
    }
}

internal fun workflowFiltersFor(
    operations: List<RecoveredDeviceOperation> = RecoveredWorkflowCatalog.operations,
): List<String> {
    val operationIdsByWorkflow = linkedMapOf<String, MutableSet<String>>()
    operations.forEach { operation ->
        operation.bindings
            .map { binding -> binding.feature.category }
            .distinct()
            .forEach { workflow ->
                operationIdsByWorkflow
                    .getOrPut(workflow) { linkedSetOf() }
                    .add(operation.id)
            }
    }
    val workflows = operationIdsByWorkflow.entries.toList()
    return workflows.mapIndexedNotNull { index, candidate ->
        val addsDistinctFilteringValue = workflows.withIndex().none { (otherIndex, other) ->
            otherIndex != index &&
                other.value.containsAll(candidate.value) &&
                (other.value.size > candidate.value.size || otherIndex < index)
        }
        candidate.key.takeIf { addsDistinctFilteringValue }
    }
}
