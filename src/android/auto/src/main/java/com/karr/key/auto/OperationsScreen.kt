package com.karr.key.auto

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.karr.key.core.Session
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.RecoveredDeviceOperation
import kotlinx.coroutines.launch

/**
 * The whole car-visible catalog in one flat list — every Customer read and command, reads first.
 * Each row is just the operation label: no per-row description (it was distracting on the head
 * unit) and no category drill-down (that split a short list across screens and hid most of it).
 * Tapping a read runs it and reports success/failure in a toast — the decoded detail is logged to
 * the phone's read history rather than opening a sub-screen in the car. Tapping a command routes
 * through [ConfirmActionScreen] first; there is still no one-tap path to anything that actuates the
 * vehicle.
 *
 * The host rejects a list longer than [ConstraintManager.CONTENT_LIMIT_TYPE_LIST], so when the
 * catalog is longer than that cap the last row pages to the next slice ([pageStart]) — every
 * operation stays reachable in the car rather than being dropped to "phone only".
 */
internal class OperationsScreen(
    carContext: CarContext,
    private val executor: CarCommandExecutor,
    private val session: Session,
    private val deviceAuthenticated: Boolean,
    private val unitModel: KarrUnitModel?,
    private val unitMode: KarrDeviceMode?,
    private val pageStart: Int = 0,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val header = Header.Builder()
            .setStartHeaderAction(Action.BACK)
            .setTitle("Operations")
            .build()
        val operations = carVisibleOperationsSorted()
        val itemList = ItemList.Builder()

        if (operations.isEmpty()) {
            itemList.addItem(
                Row.Builder()
                    .setTitle("No operations available")
                    .addText(
                        "Provision a customer research key on your phone to see Customer " +
                            "operations here.",
                    )
                    .build(),
            )
            return listTemplate(header, itemList)
        }

        val limit = carContext.contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        val page = operationsPage(total = operations.size, limit = limit, start = pageStart)

        operations.subList(page.fromIndex, page.toIndex).forEach { operation ->
            itemList.addItem(operationRow(operation))
        }
        page.nextStart?.let { nextStart ->
            itemList.addItem(
                Row.Builder()
                    .setTitle("More operations")
                    .addText("${operations.size - nextStart} more")
                    .setOnClickListener {
                        screenManager.push(
                            OperationsScreen(
                                carContext = carContext,
                                executor = executor,
                                session = session,
                                deviceAuthenticated = deviceAuthenticated,
                                unitModel = unitModel,
                                unitMode = unitMode,
                                pageStart = nextStart,
                            ),
                        )
                    }
                    .build(),
            )
        }
        return listTemplate(header, itemList)
    }

    private fun listTemplate(header: Header, itemList: ItemList.Builder): Template =
        ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(itemList.build())
            .build()

    /** Every car-visible operation, reads before commands, alphabetical within each. */
    private fun carVisibleOperationsSorted(): List<RecoveredDeviceOperation> =
        CarOperationPolicy.carVisibleOperations(session)
            .sortedWith(compareBy({ operation -> operation.category != "Reads" }, { it.label }))

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
            // The one case a row keeps secondary text: why it's greyed out. Not a description.
            builder.addText(blockingReason)
        } else {
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
            val outcome = executor.execute(
                operation = operation,
                session = session,
                deviceAuthenticated = deviceAuthenticated,
                unitModel = unitModel,
                unitMode = unitMode,
            )
            CarToast.makeText(carContext, readResultMessage(operation, outcome), CarToast.LENGTH_LONG)
                .show()
        }
    }

    private fun readResultMessage(
        operation: RecoveredDeviceOperation,
        outcome: CarOperationOutcome,
    ): String = when (outcome) {
        // Reaching ReadCompleted means the unit answered — the read round-trip succeeded. The
        // decoded payload (if any) is saved to the phone's read history; here we just confirm.
        is CarOperationOutcome.ReadCompleted ->
            if (outcome.decoded != null) {
                "${operation.label}: read successful"
            } else {
                "${operation.label}: response received, nothing to decode"
            }
        else -> outcome.summary()
    }
}
