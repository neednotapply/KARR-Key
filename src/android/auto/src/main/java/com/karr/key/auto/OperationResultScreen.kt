package com.karr.key.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.constraints.ConstraintManager

internal class OperationResultScreen(
    carContext: CarContext,
    private val operationLabel: String,
    private val outcome: CarOperationOutcome.ReadCompleted,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val decoded = outcome.decoded
        val pane = Pane.Builder()
        if (decoded == null) {
            pane.addRow(Row.Builder().setTitle("No response data was decoded.").build())
        } else {
            // Heading and every decoded line are pane rows, and the host rejects a pane longer than
            // its limit, so cap the total (heading counts as one) rather than assume a fixed size.
            val paneLimit = carContext.contentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
                .coerceAtLeast(1)
            val rows = (listOf(decoded.heading) + decoded.lines).take(paneLimit)
            rows.forEach { text -> pane.addRow(Row.Builder().setTitle(text).build()) }
        }
        return PaneTemplate.Builder(pane.build())
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.BACK)
                    .setTitle(operationLabel)
                    .build(),
            )
            .build()
    }
}
