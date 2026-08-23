package com.karr.key.desktop.map

import com.karr.key.data.MapCandidateTier
import com.karr.key.protocol.KarrVulnerabilityStatus
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Point2D
import kotlin.math.cos
import kotlin.math.hypot
import org.jxmapviewer.JXMapViewer
import org.jxmapviewer.painter.Painter
import org.jxmapviewer.viewer.GeoPosition

/**
 * Renders the device estimates onto the JXMapViewer, porting the Android
 * ConfidenceOverlay: a tier-coloured confidence circle per unit sized by its
 * radius, a vulnerability glyph (⚠️/🛡️) or dot at the centre, screen-space
 * clustering into numbered bubbles when markers overlap, the revealed unit's
 * reported-GPS annotation (dashed line + hollow marker), and per-unit history
 * trails. Also hit-tests taps (reported marker → cluster bubble → circles).
 */
class DeviceOverlayPainter : Painter<JXMapViewer> {
    var estimates: List<DeviceMapEstimate> = emptyList()
    var vulnerabilityByAddress: Map<String, KarrVulnerabilityStatus> = emptyMap()
    var revealedReportedAddress: String? = null
    var historyTracks: Map<String, List<GeoPoint>> = emptyMap()
    var selectedAddresses: Set<String> = emptySet()
    var myLocation: GeoPoint? = null
    var darkTheme: Boolean = false

    private class Cluster(val members: List<Pair<DeviceMapEstimate, Point2D>>) {
        val centerX = members.map { it.second.x }.average()
        val centerY = members.map { it.second.y }.average()
    }

    override fun paint(g: Graphics2D, viewer: JXMapViewer, width: Int, height: Int) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        drawMyLocation(g, viewer)
        drawHistoryTracks(g, viewer)
        clusters(viewer).forEach { cluster ->
            cluster.members.firstOrNull { it.first.address == revealedReportedAddress }
                ?.let { drawReported(g, viewer, it.first, it.second) }
            val single = cluster.members.singleOrNull()
            if (single != null) drawSingle(g, viewer, single.first, single.second)
            else drawCluster(g, cluster)
        }
    }

    private fun drawMyLocation(g: Graphics2D, viewer: JXMapViewer) {
        val location = myLocation ?: return
        val p = viewer.convertGeoPositionToPoint(GeoPosition(location.latitude, location.longitude))
        val dot = if (darkTheme) PHONE_DOT_DARK else PHONE_DOT_LIGHT
        g.color = dot
        fillCircle(g, p.x, p.y, 7.0)
        val guide = if (darkTheme) GUIDE_RING_DARK else GUIDE_RING_LIGHT
        g.color = Color(guide.red, guide.green, guide.blue, 90)
        g.stroke = BasicStroke(1.5f)
        strokeCircle(g, p.x, p.y, 16.0)
    }

    private fun drawHistoryTracks(g: Graphics2D, viewer: JXMapViewer) {
        if (historyTracks.isEmpty()) return
        val trail = markerDot()
        g.color = Color(trail.red, trail.green, trail.blue, 150)
        g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        historyTracks.values.forEach { points ->
            var previous: Point2D? = null
            points.forEach { geo ->
                val p = viewer.convertGeoPositionToPoint(GeoPosition(geo.latitude, geo.longitude))
                previous?.let { g.drawLine(it.x.toInt(), it.y.toInt(), p.x.toInt(), p.y.toInt()) }
                g.color = Color(trail.red, trail.green, trail.blue, 220)
                fillCircle(g, p.x, p.y, HISTORY_DOT_RADIUS)
                g.color = Color(trail.red, trail.green, trail.blue, 150)
                previous = p
            }
        }
    }

    private fun drawReported(g: Graphics2D, viewer: JXMapViewer, estimate: DeviceMapEstimate, rssiPoint: Point2D) {
        val reported = estimate.reportedPosition ?: return
        val p = viewer.convertGeoPositionToPoint(GeoPosition(reported.latitude, reported.longitude))
        val c = reportedColor()
        g.color = Color(c.red, c.green, c.blue, 170)
        g.stroke = BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(14f, 10f), 0f)
        g.drawLine(rssiPoint.x.toInt(), rssiPoint.y.toInt(), p.x.toInt(), p.y.toInt())
        g.stroke = BasicStroke(3f)
        g.color = Color(c.red, c.green, c.blue, if (darkTheme) 80 else 60)
        fillCircle(g, p.x, p.y, REPORTED_MARKER_RADIUS)
        g.color = Color(c.red, c.green, c.blue, 235)
        strokeCircle(g, p.x, p.y, REPORTED_MARKER_RADIUS)
    }

    private fun drawSingle(g: Graphics2D, viewer: JXMapViewer, estimate: DeviceMapEstimate, center: Point2D) {
        val radius = projectedRadiusPx(viewer, estimate, center)
        val confirmed = estimate.tier == MapCandidateTier.CONFIRMED_FORMAT
        val color = tierColor(confirmed)
        val selected = estimate.address in selectedAddresses
        g.color = Color(color.red, color.green, color.blue, if (darkTheme) 90 else 48)
        fillCircle(g, center.x, center.y, radius)
        g.stroke = BasicStroke(if (selected) 5f else 3f)
        g.color = Color(color.red, color.green, color.blue, if (darkTheme) 235 else 190)
        strokeCircle(g, center.x, center.y, radius)
        val glyph = vulnerabilityByAddress[estimate.address]?.statusGlyph()
        if (glyph != null && EMOJI_FONT.canDisplay(glyph.codePointAt(0))) {
            g.font = EMOJI_FONT
            val fm = g.fontMetrics
            g.color = markerDot()
            g.drawString(glyph, (center.x - fm.stringWidth(glyph) / 2.0).toFloat(),
                (center.y - (fm.ascent + fm.descent) / 2.0 + fm.ascent).toFloat())
        } else {
            g.color = markerDot()
            fillCircle(g, center.x, center.y, 6.0)
        }
    }

    private fun drawCluster(g: Graphics2D, cluster: Cluster) {
        val confirmed = cluster.members.any { it.first.tier == MapCandidateTier.CONFIRMED_FORMAT }
        val color = tierColor(confirmed)
        g.color = Color(color.red, color.green, color.blue, if (darkTheme) 210 else 190)
        fillCircle(g, cluster.centerX, cluster.centerY, CLUSTER_RADIUS)
        g.stroke = BasicStroke(3f)
        g.color = color
        strokeCircle(g, cluster.centerX, cluster.centerY, CLUSTER_RADIUS)
        g.font = CLUSTER_FONT
        g.color = markerDot()
        val text = cluster.members.size.toString()
        val fm = g.fontMetrics
        g.drawString(text, (cluster.centerX - fm.stringWidth(text) / 2.0).toFloat(),
            (cluster.centerY - (fm.ascent + fm.descent) / 2.0 + fm.ascent).toFloat())
    }

    /** Hit-test a tap: the revealed reported marker, then a cluster bubble (expand), then circles. */
    fun hitTest(viewer: JXMapViewer, x: Double, y: Double): MapHit {
        val clusters = clusters(viewer)
        clusters.asSequence()
            .mapNotNull { c -> c.members.firstOrNull { it.first.address == revealedReportedAddress }?.first }
            .mapNotNull { it.reportedPosition }
            .firstOrNull { reported ->
                val p = viewer.convertGeoPositionToPoint(GeoPosition(reported.latitude, reported.longitude))
                hypot(x - p.x, y - p.y) <= maxOf(REPORTED_MARKER_RADIUS, MIN_TOUCH_RADIUS)
            }?.let { return MapHit.ReportedTapped(it) }

        clusters.firstOrNull { it.members.size > 1 &&
            hypot(x - it.centerX, y - it.centerY) <= maxOf(CLUSTER_RADIUS, MIN_TOUCH_RADIUS) }
            ?.let { return MapHit.ClusterExpand(it.members.map { m -> m.first }) }

        val hits = clusters.filter { it.members.size == 1 }.filter { c ->
            val (estimate, point) = c.members.first()
            val r = maxOf(projectedRadiusPx(viewer, estimate, point), MIN_TOUCH_RADIUS)
            hypot(x - point.x, y - point.y) <= r
        }.map { it.members.first().first }
        return MapHit.Selected(hits)
    }

    private fun clusters(viewer: JXMapViewer): List<Cluster> {
        if (estimates.isEmpty()) return emptyList()
        val buckets = LinkedHashMap<Pair<Int, Int>, MutableList<Pair<DeviceMapEstimate, Point2D>>>()
        estimates.forEach { estimate ->
            val p = viewer.convertGeoPositionToPoint(GeoPosition(estimate.latitude, estimate.longitude))
            val key = (p.x / CLUSTER_CELL).toInt() to (p.y / CLUSTER_CELL).toInt()
            buckets.getOrPut(key) { mutableListOf() }.add(estimate to p)
        }
        return buckets.values.map { Cluster(it) }
    }

    private fun projectedRadiusPx(viewer: JXMapViewer, estimate: DeviceMapEstimate, center: Point2D): Double {
        val metersPerDegLon = maxOf(1.0, 111_320.0 * cos(Math.toRadians(estimate.latitude)).coerceAtLeast(0.01))
        val edge = viewer.convertGeoPositionToPoint(
            GeoPosition(estimate.latitude, estimate.longitude + estimate.radiusMeters / metersPerDegLon),
        )
        return kotlin.math.abs(edge.x - center.x).coerceAtLeast(12.0)
    }

    private fun fillCircle(g: Graphics2D, cx: Double, cy: Double, r: Double) =
        g.fill(Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2))

    private fun strokeCircle(g: Graphics2D, cx: Double, cy: Double, r: Double) =
        g.draw(Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2))

    private fun tierColor(confirmed: Boolean): Color = when {
        confirmed && darkTheme -> CONFIRMED_DARK
        confirmed -> CONFIRMED_LIGHT
        darkTheme -> POSSIBLE_DARK
        else -> POSSIBLE_LIGHT
    }

    private fun markerDot(): Color = if (darkTheme) MARKER_DOT_DARK else MARKER_DOT_LIGHT
    private fun reportedColor(): Color = if (darkTheme) REPORTED_DARK else REPORTED_LIGHT

    private companion object {
        val CONFIRMED_LIGHT = Color(0x0f766e); val CONFIRMED_DARK = Color(0x2dd4bf)
        val POSSIBLE_LIGHT = Color(0xb45309); val POSSIBLE_DARK = Color(0xfbbf24)
        val MARKER_DOT_LIGHT = Color(0x111827); val MARKER_DOT_DARK = Color(0xf8fafc)
        val REPORTED_LIGHT = Color(0x7c3aed); val REPORTED_DARK = Color(0xa78bfa)
        val PHONE_DOT_LIGHT = Color(0x2563eb); val PHONE_DOT_DARK = Color(0x60a5fa)
        val GUIDE_RING_LIGHT = Color(0x64748b); val GUIDE_RING_DARK = Color(0x94a3b8)
        const val REPORTED_MARKER_RADIUS = 10.0
        const val HISTORY_DOT_RADIUS = 5.0
        const val CLUSTER_RADIUS = 20.0
        const val CLUSTER_CELL = 64.0
        const val MIN_TOUCH_RADIUS = 20.0
        // Windows ships Segoe UI Emoji, which has the warning/shield glyphs;
        // Java2D renders them (monochrome). Falls back to a dot if unavailable.
        val EMOJI_FONT = Font("Segoe UI Emoji", Font.PLAIN, 18)
        val CLUSTER_FONT = Font(Font.SANS_SERIF, Font.BOLD, 15)
    }
}

/** The result of hit-testing a tap on the map. */
sealed interface MapHit {
    data class ReportedTapped(val position: GeoPoint) : MapHit
    data class ClusterExpand(val members: List<DeviceMapEstimate>) : MapHit
    data class Selected(val hits: List<DeviceMapEstimate>) : MapHit
}
