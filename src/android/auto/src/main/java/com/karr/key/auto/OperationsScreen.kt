package com.karr.key.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.karr.key.core.Session
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel

/**
 * Groups the car-visible catalog by [com.karr.key.protocol.RecoveredDeviceOperation.category]
 * (Vehicle information / Vehicle controls / Diagnostics for the Customer role) rather than
 * listing all 10 operations flat, since car list templates are expected to keep to a small
 * number of rows per screen.
 */
internal class OperationsScreen(
    carContext: CarContext,
    private val executor: CarCommandExecutor,
    private val session: Session,
    private val deviceAuthenticated: Boolean,
    private val unitModel: KarrUnitModel?,
    private val unitMode: KarrDeviceMode?,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val operations = CarOperationPolicy.carVisibleOperations(session)
        val header = Header.Builder()
            .setStartHeaderAction(Action.BACK)
            .setTitle("Operations")
            .build()

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
        } else {
            operations.groupBy { it.category }.forEach { (category, categoryOperations) ->
                itemList.addItem(
                    Row.Builder()
                        .setTitle(category)
                        .addText("${categoryOperations.size} operation(s)")
                        .setOnClickListener {
                            screenManager.push(
                                OperationCategoryScreen(
                                    carContext = carContext,
                                    executor = executor,
                                    session = session,
                                    deviceAuthenticated = deviceAuthenticated,
                                    unitModel = unitModel,
                                    unitMode = unitMode,
                                    category = category,
                                    operations = categoryOperations,
                                ),
                            )
                        }
                        .build(),
                )
            }
        }

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(itemList.build())
            .build()
    }
}
