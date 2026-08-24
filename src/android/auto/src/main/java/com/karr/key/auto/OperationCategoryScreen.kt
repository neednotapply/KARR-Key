package com.karr.key.auto

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.constraints.ConstraintManager
import androidx.lifecycle.lifecycleScope
import com.karr.key.core.Session
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.RecoveredDeviceOperation
import kotlinx.coroutines.launch

internal class OperationCategoryScreen(
    carContext: CarContext,
    private val executor: CarCommandExecutor,
    private val session: Session,
    private val deviceAuthenticated: Boolean,
    private val unitModel: KarrUnitModel?,
    private val unitMode: KarrDeviceMode?,
    private val category: String,
    private val operations: List<RecoveredDeviceOperation>,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        // The host rejects a list longer than its content limit, so cap it and, when there are
        // more, spend the final row telling the user where the rest are rather than dropping them
        // silently. Realistically only the read-heavy category can approach the limit.
        val limit = carContext.contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        val truncated = operations.size > limit
        val shown = if (truncated) operations.take((limit - 1).coerceAtLeast(1)) else operations

        val itemList = ItemList.Builder()
        shown.forEach { operation -> itemList.addItem(operationRow(operation)) }
        if (truncated) {
            itemList.addItem(
                Row.Builder()
                    .setTitle("More operations in the phone app")
                    .addText("${operations.size - shown.size} not shown here")
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.BACK)
                    .setTitle(category)
                    .build(),
            )
            .setSingleList(itemList.build())
            .build()
    }

    private fun operationRow(operation: RecoveredDeviceOperation): Row {
        val blockingReason = CarOperationPolicy.blockingReason(
            operation = operation,
            session = session,
            deviceAuthenticated = deviceAuthenticated,
            unitModel = unitModel,
            unitMode = unitMode,
        )
        val builder = Row.Builder().setTitle(operation.label)
        if (blockingReason != null) {
            builder.addText(blockingReason)
        } else {
            builder.addText(operation.description)
            builder.setOnClickListener { onOperationTapped(operation) }
        }
        return builder.build()
    }

    private fun onOperationTapped(operation: RecoveredDeviceOperation) {
        if (CarOperationPolicy.requiresConfirmation(operation)) {
            screenManager.push(
                ConfirmActionScreen(
                    carContext = carContext,
                    executor = executor,
                    operation = operation,
                    session = session,
                    deviceAuthenticated = deviceAuthenticated,
                    unitModel = unitModel,
                    unitMode = unitMode,
                ),
            )
            return
        }
        lifecycleScope.launch {
            when (
                val outcome = executor.execute(
                    operation = operation,
                    session = session,
                    deviceAuthenticated = deviceAuthenticated,
                    unitModel = unitModel,
                    unitMode = unitMode,
                )
            ) {
                is CarOperationOutcome.ReadCompleted ->
                    screenManager.push(OperationResultScreen(carContext, operation.label, outcome))
                else ->
                    CarToast.makeText(carContext, outcome.summary(), CarToast.LENGTH_LONG).show()
            }
        }
    }
}
