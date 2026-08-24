package com.karr.key.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.karr.key.data.KarrCarServices
import com.karr.key.data.LocalDeviceReadStore
import com.karr.key.data.LocalMapHistoryStore
import com.karr.key.data.LocalOperationStore

class KarrCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        val services = carContext.applicationContext as KarrCarServices
        val executor = CarCommandExecutor(
            controller = services.bleController,
            operationStore = LocalOperationStore(carContext),
            deviceReadStore = LocalDeviceReadStore(carContext),
        )
        val mapHistoryStore = LocalMapHistoryStore(carContext)
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    executor.close()
                    mapHistoryStore.close()
                }
            },
        )
        return ConnectionScreen(carContext, services, executor, mapHistoryStore)
    }
}
