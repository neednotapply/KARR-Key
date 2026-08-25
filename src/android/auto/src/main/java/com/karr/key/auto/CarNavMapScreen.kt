package com.karr.key.auto

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.karr.key.auto.map.CarMapRenderer
import com.karr.key.auto.map.FusedCarLocation
import com.karr.key.data.KarrCarServices
import com.karr.key.data.LocalMapHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The full custom map: a [NavigationTemplate] whose surface [CarMapRenderer] draws — OSM tiles, a
 * marker per KARR unit, the car's position, and range rings, with pan/zoom and marker tapping. This
 * is the navigation-class counterpart to the phone's osmdroid map; using it is why the car app
 * declares the NAVIGATION category and the surface/navigation-template permissions.
 *
 * Tapping a marker opens [CarUnitDetailScreen], which offers Connect when that unit is in range.
 */
internal class CarNavMapScreen(
    carContext: CarContext,
    private val services: KarrCarServices,
    private val mapHistoryStore: LocalMapHistoryStore,
) : Screen(carContext), DefaultLifecycleObserver {

    private val renderer = CarMapRenderer(
        onMarkerTapped = { unit ->
            screenManager.push(CarUnitDetailScreen(carContext, services, unit))
        },
    )
    private val locationTracker = FusedCarLocation(carContext) { lat, lon, bearing, speed ->
        renderer.setMyLocation(lat, lon, bearing, speed)
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        lifecycleScope.launch {
            val units = withContext(Dispatchers.IO) { latestPerDevice(mapHistoryStore.recent()) }
            renderer.setUnits(units)
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        locationTracker.start()
    }

    override fun onPause(owner: LifecycleOwner) {
        locationTracker.stop()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        locationTracker.stop()
        renderer.shutdown()
        runCatching { carContext.getCarService(AppManager::class.java).setSurfaceCallback(null) }
    }

    override fun onGetTemplate(): Template {
        // The zoom / compass / recenter controls are drawn by CarMapRenderer directly onto the map
        // surface, split left/right — the NavigationTemplate only renders its action strips on the
        // right edge, and its native map action strip was also DHU-fragile. So the template just
        // needs its required action strip: Close.
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Close")
                    .setOnClickListener { screenManager.pop() }
                    .build(),
            )
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()
    }
}
