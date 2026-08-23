package com.karr.key.desktop

import com.karr.key.data.MapCandidateTier
import com.karr.key.desktop.map.DeviceMapEstimate
import com.karr.key.desktop.map.DeviceOverlayPainter
import com.karr.key.desktop.map.MapHit
import java.time.Instant
import org.jxmapviewer.JXMapViewer
import org.jxmapviewer.OSMTileFactoryInfo
import org.jxmapviewer.viewer.DefaultTileFactory
import org.jxmapviewer.viewer.GeoPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the map overlay's geo projection + hit-testing without a display: a
 * marker at the map centre must be selected by a tap there, and a far-away tap
 * must not. This validates the JXMapViewer plumbing the Map screen relies on.
 */
class MapOverlayTest {
    private fun estimate(lat: Double, lon: Double) = DeviceMapEstimate(
        address = "AA:BB:CC:DD:EE:01",
        label = "DR 1A2B3C4",
        tier = MapCandidateTier.CONFIRMED_FORMAT,
        evidence = "test",
        latitude = lat,
        longitude = lon,
        radiusMeters = 60.0,
        rssi = -60,
        lastSeenUtc = Instant.parse("2026-08-03T10:00:00Z"),
    )

    @Test
    fun tapOnMarkerSelectsItAndFarTapDoesNot() {
        val viewer = JXMapViewer()
        viewer.setSize(800, 600)
        viewer.tileFactory = DefaultTileFactory(OSMTileFactoryInfo())
        viewer.zoom = 5
        viewer.addressLocation = GeoPosition(32.7157, -117.1611)

        val painter = DeviceOverlayPainter()
        painter.estimates = listOf(estimate(32.7157, -117.1611))

        val center = viewer.convertGeoPositionToPoint(GeoPosition(32.7157, -117.1611))
        val onMarker = painter.hitTest(viewer, center.x, center.y)
        assertTrue(onMarker is MapHit.Selected)
        assertEquals(1, (onMarker as MapHit.Selected).hits.size)

        val far = painter.hitTest(viewer, center.x + 5_000, center.y + 5_000)
        assertTrue(far is MapHit.Selected && far.hits.isEmpty())
    }
}
