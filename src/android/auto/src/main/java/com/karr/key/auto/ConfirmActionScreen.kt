package com.karr.key.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import com.karr.key.core.Session
import com.karr.key.protocol.KarrDeviceMode
import com.karr.key.protocol.KarrUnitModel
import com.karr.key.protocol.RecoveredDeviceOperation
import kotlinx.coroutines.launch

/**
 * Every mutating operation routes through this screen before it sends — there is no one-tap path
 * to a command that can affect the vehicle from the car surface.
 */
internal class ConfirmActionScreen(
    carContext: CarContext,
    private val executor: CarCommandExecutor,
    private val operation: RecoveredDeviceOperation,
    private val session: Session,
    private val deviceAuthenticated: Boolean,
    private val unitModel: KarrUnitModel?,
    private val unitMode: KarrDeviceMode?,
) : Screen(carContext) {
    private var sending = false
    private var outcome: CarOperationOutcome? = null

    override fun onGetTemplate(): Template {
        val header = Header.Builder()
            .setStartHeaderAction(Action.BACK)
            .setTitle(operation.label)
            .build()

        val currentOutcome = outcome
        if (currentOutcome != null) {
            return MessageTemplate.Builder(currentOutcome.summary())
                .setHeader(header)
                .addAction(
                    Action.Builder()
                        .setTitle("OK")
                        .setOnClickListener { screenManager.pop() }
                        .build(),
                )
                .build()
        }

        val message = if (sending) {
            "Sending ${operation.label}..."
        } else {
            "${operation.description}\n\nSend this command to the connected unit?"
        }
        val builder = MessageTemplate.Builder(message).setHeader(header)
        if (!sending) {
            builder.addAction(
                Action.Builder()
                    .setTitle("Confirm")
                    .setOnClickListener { send() }
                    .build(),
            )
            builder.addAction(
                Action.Builder()
                    .setTitle("Cancel")
                    .setOnClickListener { screenManager.pop() }
                    .build(),
            )
        }
        return builder.build()
    }

    private fun send() {
        if (sending) return
        sending = true
        invalidate()
        lifecycleScope.launch {
            outcome = executor.execute(
                operation = operation,
                session = session,
                deviceAuthenticated = deviceAuthenticated,
                unitModel = unitModel,
                unitMode = unitMode,
            )
            sending = false
            invalidate()
        }
    }
}
