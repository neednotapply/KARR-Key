package com.karr.key.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.karr.key.R
import com.karr.key.core.AuthorizationDecision
import com.karr.key.core.Session
import com.karr.key.core.UserRole
import com.karr.key.core.displayName
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.QtCommand
import com.karr.key.protocol.RecoveredCompatibilityConstraint
import com.karr.key.protocol.RecoveredDeviceOperation
import com.karr.key.protocol.RecoveredModelCompatibility
import com.karr.key.protocol.RecoveredWorkflowCatalog
import com.karr.key.protocol.compatibilityWith
import com.karr.key.protocol.dangerScore

internal enum class OperationTypeFilter(val displayLabel: String) {
    READS("Reads"),
    COMMANDS("Commands"),
}

internal const val UNIT_MODE_FILTER = "Unit mode"

// Tri-state sort for the operations list, cycled by the "Danger" chip: off -> descending -> ascending.
internal enum class DangerSort { DESCENDING, ASCENDING }

internal data class OperationFilters(
    val workflow: String? = null,
    val operationType: OperationTypeFilter? = null,
    val dangerSort: DangerSort? = null,
) {
    companion object {
        val Saver = listSaver<OperationFilters, String>(
            save = { filters ->
                listOf(
                    filters.workflow.orEmpty(),
                    filters.operationType?.name.orEmpty(),
                    filters.dangerSort?.name.orEmpty(),
                )
            },
            restore = { values ->
                OperationFilters(
                    workflow = values.firstOrNull()?.ifBlank { null },
                    operationType = values
                        .getOrNull(1)
                        ?.ifBlank { null }
                        ?.let(OperationTypeFilter::valueOf),
                    dangerSort = values
                        .getOrNull(2)
                        ?.ifBlank { null }
                        ?.let(DangerSort::valueOf),
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
    unitMode: KarrDeviceMode? = null,
    query: String,
    filters: OperationFilters,
    listState: LazyListState,
    onQueryChanged: (String) -> Unit,
    onFiltersChanged: (OperationFilters) -> Unit,
    onOpenOperation: (RecoveredDeviceOperation) -> Unit,
    onExecuteOperation: (RecoveredDeviceOperation) -> Unit,
    executingOperationId: String? = null,
    onOpenCommandConsole: () -> Unit = {},
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
                    unitMode = unitMode,
                    query = query,
                    filters = filters,
                    listState = listState,
                    onQueryChanged = onQueryChanged,
                    onFiltersChanged = onFiltersChanged,
                    onOpenOperation = onOpenOperation,
                    onExecuteOperation = onExecuteOperation,
                    executingOperationId = executingOperationId,
                    onOpenCommandConsole = onOpenCommandConsole,
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
                unitMode = unitMode,
                query = query,
                filters = filters,
                listState = listState,
                onQueryChanged = onQueryChanged,
                onFiltersChanged = onFiltersChanged,
                onOpenOperation = onOpenOperation,
                onExecuteOperation = onExecuteOperation,
                executingOperationId = executingOperationId,
                onOpenCommandConsole = onOpenCommandConsole,
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
    unitMode: KarrDeviceMode?,
    query: String,
    filters: OperationFilters,
    listState: LazyListState = rememberLazyListState(),
    onQueryChanged: (String) -> Unit,
    onFiltersChanged: (OperationFilters) -> Unit,
    onOpenOperation: (RecoveredDeviceOperation) -> Unit,
    onExecuteOperation: (RecoveredDeviceOperation) -> Unit,
    executingOperationId: String?,
    onOpenCommandConsole: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val operations = listableOperations()
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
        item(key = "command-console-entry") {
            CommandConsoleEntryCard(onOpen = onOpenCommandConsole)
        }

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
                            listOfNotNull(
                                unitModel?.displayName,
                                unitMode?.let { mode -> "${mode.displayName} mode" },
                            ).ifEmpty {
                                listOf("Model and mode not identified")
                            }.joinToString(" • "),
                            style = MaterialTheme.typography.titleLarge,
                            color = if (unitModel?.isRecognized == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Text(
                            if (unitModel?.isRecognized == true) {
                                "Unit and mode restrictions are checked before operations can run."
                            } else {
                                "Restricted operations stay disabled until the unit reports " +
                                    "recognized compatibility details."
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
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChanged("") }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Clear search",
                            )
                        }
                    }
                } else {
                    null
                },
                supportingText = {
                    Text("${filtered.size} of ${operations.size} operations")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item(key = "operation-filters") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OperationTypeFilter.entries.forEach { operationType ->
                    val selected = filters.operationType == operationType
                    FilterChip(
                        selected = selected,
                        onClick = {
                            // Type and workflow stay mutually exclusive; the danger sort is
                            // orthogonal and carried across toggles.
                            onFiltersChanged(
                                filters.copy(
                                    operationType = if (selected) null else operationType,
                                    workflow = null,
                                ),
                            )
                        },
                        label = {
                            Text(
                                operationType.displayLabel,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        },
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
                                filters.copy(
                                    workflow = if (selected) null else workflow,
                                    operationType = null,
                                ),
                            )
                        },
                        label = {
                            Text(
                                workflow,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        },
                        modifier = Modifier.testTag(
                            "operation-filter:${workflow.lowercase().replace(' ', '-')}",
                        ),
                    )
                }
                // Tri-state sort by danger: off -> descending (🔻) -> ascending (🔺) -> off.
                FilterChip(
                    selected = filters.dangerSort != null,
                    onClick = {
                        val next = when (filters.dangerSort) {
                            null -> DangerSort.DESCENDING
                            DangerSort.DESCENDING -> DangerSort.ASCENDING
                            DangerSort.ASCENDING -> null
                        }
                        onFiltersChanged(filters.copy(dangerSort = next))
                    },
                    label = {
                        // Always render a triangle so the chip keeps the same width whether or not
                        // the sort is active — a hollow ▽ when off, a filled ▼/▲ for the two
                        // directions — so toggling it doesn't nudge the filter row. Plain text
                        // glyphs (not the red emoji) inherit the chip's label color and stay on the
                        // black/white theme. Triangle and text are separate, center-aligned glyphs
                        // so enlarging the triangle doesn't drop the "Danger" baseline below the
                        // neighboring chips.
                        val triangle = when (filters.dangerSort) {
                            DangerSort.DESCENDING -> "▼"
                            DangerSort.ASCENDING -> "▲"
                            null -> "▽"
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                triangle,
                                style = MaterialTheme.typography.labelMedium,
                                fontSize = MaterialTheme.typography.labelMedium.fontSize * 1.3f,
                                maxLines = 1,
                            )
                            Text(
                                "Danger",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    },
                    modifier = Modifier.testTag("operation-filter:danger"),
                )
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
            // When sorting by danger the list is a single ranked run with no category headings; the
            // category grouping would otherwise re-cluster it and hide the ordering. Otherwise keep
            // the familiar Reads / Commands sections.
            val sections: List<Pair<String?, List<RecoveredDeviceOperation>>> =
                if (filters.dangerSort != null) {
                    listOf(null to filtered)
                } else {
                    filtered.groupBy(RecoveredDeviceOperation::category).toList()
                }
            sections.forEach { (category, sectionOperations) ->
                category?.let {
                    item(key = "heading:$it") {
                        Text(
                            text = it,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .semantics { heading() },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                items(
                    items = sectionOperations,
                    key = RecoveredDeviceOperation::id,
                ) { operation ->
                    OperationListRow(
                        session = session,
                        operation = operation,
                        deviceAuthenticated = deviceAuthenticated,
                        unitModel = unitModel,
                        unitMode = unitMode,
                        onOpen = { onOpenOperation(operation) },
                        onExecute = onExecuteOperation,
                        executingOperationId = executingOperationId,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandConsoleEntryCard(onOpen: () -> Unit) {
    OutlinedCard(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("open-command-console"),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Command console",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            "Testing",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text(
                    "Send any raw protocol command and see what the unit accepts, rejects, or " +
                        "ignores. Uncurated — for probing hardware support.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The console carries destructive commands and applies no role gating, so the
                // hazard is named at the door rather than only on the rows inside.
                Text(
                    "Includes destructive commands — firmware, secure keys, factory reset. Each " +
                        "is tagged Destructive and confirms before it sends.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
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
    unitMode: KarrDeviceMode?,
    onOpen: () -> Unit,
    onExecute: (RecoveredDeviceOperation) -> Unit,
    executingOperationId: String?,
) {
    val availability = operationModelAvailability(
        session = session,
        operation = operation,
        deviceAuthenticated = deviceAuthenticated,
        unitModel = unitModel,
        unitMode = unitMode,
    )
    val canOpen = availability.allowsOperationOpen()
    val executionAccess = operation.executionAccess(
        session = session,
        deviceAuthenticated = deviceAuthenticated,
        unitModel = unitModel,
        unitMode = unitMode,
    )
    val modeSwitchOffer = modeSwitchOffer(
        session = session,
        blockedAction = executionAccess.action,
        deviceAuthenticated = deviceAuthenticated,
        unitModel = unitModel,
        unitMode = unitMode,
    )
    // A mode block is now resolved automatically on send — the executor drives the unit to a
    // supported mode first — so a mode-blocked-but-reachable operation stays enabled and reads as
    // runnable, and tapping it runs the operation itself rather than a manual one-hop mode switch.
    val modeAutoResolvable = !executionAccess.canExecute && modeSwitchOffer != null
    val actionEnabled = (executionAccess.canExecute || modeAutoResolvable) &&
        executingOperationId == null
    val actionLabel = operationActionLabel(
        availability = availability,
        executionAccess = executionAccess,
        actionTargetOperationId = operation.id,
        executingOperationId = executingOperationId,
        modeAutoResolvable = modeAutoResolvable,
    )
    val showBlockedIcon = !executionAccess.canExecute && !modeAutoResolvable
    val actionStateDescription = when {
        executionAccess.canExecute -> null
        modeAutoResolvable ->
            "Switches to ${modeSwitchOffer.destinationDescription} on send, then runs."
        else -> executionAccess.blockingReason
    }
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
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Button(
                        onClick = { onExecute(operation) },
                        enabled = actionEnabled,
                        modifier = Modifier
                            .width(144.dp)
                            .height(32.dp)
                            .testTag("operation-action:${operation.id}")
                            .then(
                                actionStateDescription?.let { description ->
                                    Modifier.semantics {
                                        stateDescription = description
                                    }
                                } ?: Modifier,
                            ),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                    ) {
                        if (showBlockedIcon) {
                            Icon(
                                painter = painterResource(R.drawable.ic_status_lock),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            text = actionLabel,
                            modifier = if (showBlockedIcon) {
                                Modifier.padding(start = 6.dp)
                            } else {
                                Modifier
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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
                DangerTag(operation.dangerScore)
                // Only the access floor — the lowest role that may run this — rather than every
                // permitted role; anyone at or above it is allowed.
                RoleTag(operation.lowestRole)
            }
        }
    }
}

internal fun operationActionLabel(
    availability: FeatureAvailability,
    executionAccess: OperationExecutionAccess,
    actionTargetOperationId: String,
    executingOperationId: String?,
    modeAutoResolvable: Boolean = false,
): String = when {
    executingOperationId == actionTargetOperationId ->
        if (executionAccess.action.mutating) "Sending..." else "Reading..."
    // A mode-only block is resolved automatically on send, so it reads as runnable, not blocked.
    executionAccess.canExecute || modeAutoResolvable ->
        if (executionAccess.action.mutating) "Send command" else "Run read"
    !executionAccess.deviceAuthenticated -> "Not connected"
    executionAccess.decision is AuthorizationDecision.Denied ->
        "${executionAccess.requiredRole.displayName()} required"
    executionAccess.recoveryStatus?.available == false -> "Blocked"
    availability != FeatureAvailability.Available -> availability.label
    else -> "Unavailable"
}

internal fun operationModelAvailability(
    session: Session,
    operation: RecoveredDeviceOperation,
    deviceAuthenticated: Boolean,
    unitModel: KarrUnitModel?,
    unitMode: KarrDeviceMode? = null,
): FeatureAvailability {
    if (!deviceAuthenticated) {
        return FeatureAvailability.Available
    }
    return when (
        val compatibility = operation
            .preferredBinding(session, unitModel, unitMode)
            .action
            .compatibilityWith(unitModel, unitMode)
    ) {
        RecoveredModelCompatibility.Supported -> FeatureAvailability.Available
        is RecoveredModelCompatibility.Unsupported -> when (compatibility.constraint) {
            RecoveredCompatibilityConstraint.HARDWARE ->
                FeatureAvailability.HardwareUnsupported(compatibility.reason)
            RecoveredCompatibilityConstraint.UNIT_MODE ->
                FeatureAvailability.ModeRestricted(compatibility.reason)
        }
        is RecoveredModelCompatibility.Unknown -> when (compatibility.constraint) {
            RecoveredCompatibilityConstraint.HARDWARE ->
                FeatureAvailability.HardwareUnknown(compatibility.reason)
            RecoveredCompatibilityConstraint.UNIT_MODE ->
                FeatureAvailability.ModeUnknown(compatibility.reason)
        }
    }
}

internal fun FeatureAvailability.allowsOperationOpen(): Boolean =
    this !is FeatureAvailability.ModelUnsupported &&
        this !is FeatureAvailability.ModelUnknown &&
        this !is FeatureAvailability.HardwareUnsupported &&
        this !is FeatureAvailability.HardwareUnknown &&
        this !is FeatureAvailability.ModeUnknown

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

// The operations shown in the list. Experimental candidates (currently only the immobilizer-output
// test) are intentionally excluded: that testing now happens in the command console, where the same
// command is reachable as a VEHICLE_CONTROL preset.
internal fun listableOperations(): List<RecoveredDeviceOperation> =
    RecoveredWorkflowCatalog.operations.filterNot { operation -> operation.experimental }

internal fun deviceOperationsFor(
    query: String = "",
    filters: OperationFilters = OperationFilters(),
): List<RecoveredDeviceOperation> {
    val normalizedQuery = query.trim()
    val matched = listableOperations().filter { operation ->
        val operationTypeMatches = when (filters.operationType) {
            null -> true
            OperationTypeFilter.READS -> !operation.mutating
            OperationTypeFilter.COMMANDS -> operation.mutating
        }
        val workflowMatches = when (filters.workflow) {
            null -> true
            UNIT_MODE_FILTER -> operation.command == QtCommand.CHANGE_MODE
            else -> operation.bindings.any { binding ->
                binding.feature.category == filters.workflow
            }
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
    return when (filters.dangerSort) {
        null -> matched
        DangerSort.DESCENDING -> matched.sortedByDescending { operation -> operation.dangerScore }
        DangerSort.ASCENDING -> matched.sortedBy { operation -> operation.dangerScore }
    }
}

internal fun workflowFiltersFor(
    operations: List<RecoveredDeviceOperation> = listableOperations(),
): List<String> {
    return buildList {
        if (operations.any { operation -> operation.command == QtCommand.CHANGE_MODE }) {
            add(UNIT_MODE_FILTER)
        }
    }
}
