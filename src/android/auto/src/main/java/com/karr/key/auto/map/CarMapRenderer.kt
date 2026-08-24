package com.karr.key.auto.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.karr.key.auto.MappedUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Renders the KARR-unit map onto the head unit's [Surface]: OSM tiles, a marker per device, the
 * car's position and range rings, with pan/zoom, marker tapping, and a heading-up ("compass") mode
 * that rotates the map so the direction of travel is up. The custom, full-fidelity counterpart to
 * the phone's osmdroid map.
 *
 * Heading comes from the GPS bearing (the phone's magnetometer is meaningless in a dock at an
 * arbitrary angle). Rendering is synchronous on the main thread — the Car App on-demand surface
 * model; a background render thread was found to break surface input delivery.
 */
internal class CarMapRenderer(
    private val onMarkerTapped: (MappedUnit) -> Unit,
) : SurfaceCallback {

    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var dpi = 160

    private var centerLat = 0.0
    private var centerLon = 0.0
    private var zoom = DEFAULT_ZOOM
    private var hasCentered = false

    private var units: List<MappedUnit> = emptyList()
    private var myLocation: Pair<Double, Double>? = null
    private var bearingDeg = 0f
    // Opens north-up so all devices are in view (the overview); the compass button switches to
    // heading-up ("headed forward"), which then follows and rotates to the car's GPS bearing.
    private var headingUp = false
    private var followCar = true
    private var lastRotationDeg = 0f
    private var markerScreen: List<Pair<MappedUnit, PointF>> = emptyList()
    private var scaleAccumulator = 1f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderPending = AtomicBoolean(false)
    private val tiles = OsmTileCache(onTileLoaded = { render() })

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val placeholderPaint = Paint().apply { color = Color.parseColor("#E8E8E8") }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3348C0FF")
    }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF3D5AFE")
    }
    private val markerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val myFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF1A73E8")
    }
    private val myStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val attributionBg = Paint().apply { color = Color.parseColor("#B0FFFFFF") }
    private val attributionText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF333333")
        textAlign = Paint.Align.CENTER
    }
    private val compassNorth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFD32F2F")
    }
    private val buttonBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E6202124")
    }
    private val buttonBgActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E61A73E8")
    }
    private val buttonStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val buttonFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private data class ButtonHit(val cx: Float, val cy: Float, val r: Float, val action: () -> Unit)
    private var buttonHits: List<ButtonHit> = emptyList()

    // --- SurfaceCallback -----------------------------------------------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        width = surfaceContainer.width
        height = surfaceContainer.height
        dpi = surfaceContainer.dpi.coerceAtLeast(120)
        render()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        render()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        // A manual pan drops car-follow so the map stays where the user put it.
        val me = myLocation
        if (followCar && me != null) {
            centerLat = me.first
            centerLon = me.second
        }
        followCar = false
        // The drag is in screen space; when the map is rotated, rotate the delta back into
        // world-aligned space so panning moves the map in the direction the finger went.
        val (wdx, wdy) = rotateVector(distanceX, distanceY, lastRotationDeg)
        val cwx = WebMercator.lonToWorldX(centerLon, zoom) + wdx
        val cwy = WebMercator.latToWorldY(centerLat, zoom) + wdy
        centerLon = normalizeLon(WebMercator.worldXToLon(cwx, zoom))
        centerLat = WebMercator.worldYToLat(cwy, zoom).coerceIn(-MAX_LAT, MAX_LAT)
        hasCentered = true
        render()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        scaleAccumulator *= scaleFactor
        when {
            scaleAccumulator >= 2f -> { zoomBy(1, focusX, focusY); scaleAccumulator = 1f }
            scaleAccumulator <= 0.5f -> { zoomBy(-1, focusX, focusY); scaleAccumulator = 1f }
        }
    }

    override fun onClick(x: Float, y: Float) {
        // Our custom on-surface controls take the tap first (they're drawn in screen space).
        buttonHits.forEach { b ->
            if (hypot(x - b.cx, y - b.cy) <= b.r) {
                b.action()
                return
            }
        }
        // markerScreen holds unrotated positions; map the tap back through the current rotation.
        val (ux, uy) = rotatePoint(x, y, width / 2f, height / 2f, lastRotationDeg)
        val hit = markerScreen.minByOrNull { (_, p) -> hypot(p.x - ux, p.y - uy) }
        if (hit != null && hypot(hit.second.x - ux, hit.second.y - uy) <= tapSlopPx()) {
            onMarkerTapped(hit.first)
        }
    }

    // --- Public control ------------------------------------------------------

    fun setUnits(units: List<MappedUnit>) {
        this.units = units
        if (!hasCentered && myLocation == null) centerOnData()
        render()
    }

    fun setMyLocation(lat: Double, lon: Double, bearing: Float?) {
        myLocation = lat to lon
        if (bearing != null) bearingDeg = bearing
        if (!hasCentered) {
            centerLat = lat
            centerLon = lon
            hasCentered = true
        }
        render()
    }

    fun isHeadingUp(): Boolean = headingUp

    fun toggleHeadingUp() {
        headingUp = !headingUp
        if (headingUp) followCar = true
        render()
    }

    fun zoomIn() = zoomBy(1, width / 2f, height / 2f)

    fun zoomOut() = zoomBy(-1, width / 2f, height / 2f)

    fun recenter() {
        val me = myLocation
        if (me != null) {
            centerLat = me.first
            centerLon = me.second
            followCar = true
        } else {
            centerOnData()
        }
        zoom = MAX_ZOOM // "center" snaps to the closest view
        hasCentered = true
        render()
    }

    fun shutdown() {
        mainHandler.removeCallbacksAndMessages(null)
        tiles.shutdown()
        surface = null
    }

    // --- Rendering -----------------------------------------------------------

    /**
     * Coalesced, asynchronous render request — safe to call from any thread and as often as needed.
     * Returns immediately (so surface callbacks never block on drawing, which would ANR the Car App
     * host) and collapses a burst of requests (e.g. a flurry of tile arrivals) into a single frame.
     */
    private fun render() {
        if (renderPending.compareAndSet(false, true)) {
            mainHandler.post {
                renderPending.set(false)
                renderNow()
            }
        }
    }

    private fun renderNow() {
        val target = surface ?: return
        if (!target.isValid || width <= 0 || height <= 0) return
        val canvas = runCatching { target.lockCanvas(null) }.getOrNull() ?: return
        try {
            draw(canvas)
        } finally {
            runCatching { target.unlockCanvasAndPost(canvas) }
        }
    }

    private fun draw(canvas: Canvas) {
        markerStroke.strokeWidth = dp(1.5f)
        myStroke.strokeWidth = dp(2f)

        // In heading-up mode, follow and rotate around the car when we have a fix.
        val me = myLocation
        val centeredOnCar = headingUp && followCar && me != null
        val clat = if (centeredOnCar) me.first else centerLat
        val clon = if (centeredOnCar) me.second else centerLon
        val rotationDeg = if (headingUp) bearingDeg else 0f
        lastRotationDeg = rotationDeg

        canvas.drawColor(Color.parseColor("#DDDDDD"))
        val cwx = WebMercator.lonToWorldX(clon, zoom)
        val cwy = WebMercator.latToWorldY(clat, zoom)

        canvas.save()
        if (rotationDeg != 0f) canvas.rotate(-rotationDeg, width / 2f, height / 2f)
        drawTiles(canvas, cwx, cwy, rotationDeg != 0f)
        drawMarkers(canvas, cwx, cwy)
        drawMyLocation(canvas, cwx, cwy)
        canvas.restore()

        drawButtons(canvas, rotationDeg)
        drawAttribution(canvas)
    }

    /**
     * On-surface map controls, drawn ourselves so we can split them left/right — the
     * NavigationTemplate only renders its action strips on the right edge. Zoom on the left,
     * compass-toggle and recenter on the right, anchored low so they sit near the driver's reach,
     * each a 48dp touch target hit-tested in [onClick]. The compass button doubles as the heading
     * indicator: its needle tracks the map and it lights up while heading-up is active.
     */
    private fun drawButtons(canvas: Canvas, rotationDeg: Float) {
        buttonStroke.strokeWidth = dp(2.5f)
        val r = dp(22f)
        val margin = dp(16f)
        val gap = dp(14f)
        val leftX = margin + r
        val rightX = width - margin - r
        val bottomY = height - margin - r
        val topY = bottomY - (2 * r + gap)

        val hits = ArrayList<ButtonHit>(4)
        drawZoomIn(canvas, leftX, topY, r); hits.add(ButtonHit(leftX, topY, r) { zoomIn() })
        drawZoomOut(canvas, leftX, bottomY, r); hits.add(ButtonHit(leftX, bottomY, r) { zoomOut() })
        drawCompassButton(canvas, rightX, topY, r, rotationDeg); hits.add(ButtonHit(rightX, topY, r) { toggleHeadingUp() })
        drawRecenter(canvas, rightX, bottomY, r); hits.add(ButtonHit(rightX, bottomY, r) { recenter() })
        buttonHits = hits
    }

    private fun drawZoomIn(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, buttonBg)
        val a = r * 0.5f
        canvas.drawLine(cx - a, cy, cx + a, cy, buttonStroke)
        canvas.drawLine(cx, cy - a, cx, cy + a, buttonStroke)
    }

    private fun drawZoomOut(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, buttonBg)
        val a = r * 0.5f
        canvas.drawLine(cx - a, cy, cx + a, cy, buttonStroke)
    }

    private fun drawRecenter(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, buttonBg)
        val ring = r * 0.42f
        canvas.drawCircle(cx, cy, ring, buttonStroke)
        canvas.drawCircle(cx, cy, dp(1.6f), buttonFill)
        val outer = r * 0.66f
        canvas.drawLine(cx, cy - ring, cx, cy - outer, buttonStroke)
        canvas.drawLine(cx, cy + ring, cx, cy + outer, buttonStroke)
        canvas.drawLine(cx - ring, cy, cx - outer, cy, buttonStroke)
        canvas.drawLine(cx + ring, cy, cx + outer, cy, buttonStroke)
    }

    private fun drawCompassButton(canvas: Canvas, cx: Float, cy: Float, r: Float, rotationDeg: Float) {
        // The button IS the compass: filled accent while heading-up is active, and the needle
        // rotates with the map (north points where north is on screen) so its mode reads at a glance.
        canvas.drawCircle(cx, cy, r, if (headingUp) buttonBgActive else buttonBg)
        canvas.save()
        canvas.rotate(-rotationDeg, cx, cy)
        val h = r * 0.55f
        val w = r * 0.28f
        val north = Path().apply {
            moveTo(cx, cy - h); lineTo(cx - w, cy); lineTo(cx + w, cy); close()
        }
        canvas.drawPath(north, compassNorth)
        val south = Path().apply {
            moveTo(cx, cy + h); lineTo(cx - w, cy); lineTo(cx + w, cy); close()
        }
        canvas.drawPath(south, buttonFill)
        canvas.restore()
    }

    private fun drawTiles(canvas: Canvas, cwx: Double, cwy: Double, rotated: Boolean) {
        // A rotated viewport needs tiles out to the screen's diagonal so the corners stay covered.
        val halfW: Double
        val halfH: Double
        if (rotated) {
            val d = hypot(width.toDouble(), height.toDouble()) / 2.0
            halfW = d
            halfH = d
        } else {
            halfW = width / 2.0
            halfH = height / 2.0
        }
        val maxIndex = (1 shl zoom) - 1
        val firstTx = floor((cwx - halfW) / WebMercator.TILE_SIZE).toInt()
        val lastTx = floor((cwx + halfW) / WebMercator.TILE_SIZE).toInt()
        val firstTy = floor((cwy - halfH) / WebMercator.TILE_SIZE).toInt()
        val lastTy = floor((cwy + halfH) / WebMercator.TILE_SIZE).toInt()
        for (ty in firstTy..lastTy) {
            if (ty < 0 || ty > maxIndex) continue
            for (tx in firstTx..lastTx) {
                val wrappedTx = ((tx % (maxIndex + 1)) + (maxIndex + 1)) % (maxIndex + 1)
                val screenX = (width / 2.0 + (tx * WebMercator.TILE_SIZE - cwx)).toFloat()
                val screenY = (height / 2.0 + (ty * WebMercator.TILE_SIZE - cwy)).toFloat()
                val bitmap = tiles.tile(zoom, wrappedTx, ty)
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, screenX, screenY, tilePaint)
                } else {
                    canvas.drawRect(
                        screenX,
                        screenY,
                        screenX + WebMercator.TILE_SIZE,
                        screenY + WebMercator.TILE_SIZE,
                        placeholderPaint,
                    )
                }
            }
        }
    }

    private fun drawMarkers(canvas: Canvas, cwx: Double, cwy: Double) {
        val metersPerPixel = WebMercator.metersPerPixel(centerLat, zoom)
        val radius = dp(6f)
        val screens = ArrayList<Pair<MappedUnit, PointF>>(units.size)
        units.forEach { unit ->
            val sx = (width / 2.0 + (WebMercator.lonToWorldX(unit.longitude, zoom) - cwx)).toFloat()
            val sy = (height / 2.0 + (WebMercator.latToWorldY(unit.latitude, zoom) - cwy)).toFloat()
            unit.rangeMeters?.let { range ->
                val ringRadius = (range / metersPerPixel).toFloat()
                if (ringRadius in 1f..maxOf(width, height).toFloat()) {
                    canvas.drawCircle(sx, sy, ringRadius, ringPaint)
                }
            }
            canvas.drawCircle(sx, sy, radius, markerFill)
            canvas.drawCircle(sx, sy, radius, markerStroke)
            screens.add(unit to PointF(sx, sy))
        }
        markerScreen = screens
    }

    private fun drawMyLocation(canvas: Canvas, cwx: Double, cwy: Double) {
        val me = myLocation ?: return
        val sx = (width / 2.0 + (WebMercator.lonToWorldX(me.second, zoom) - cwx)).toFloat()
        val sy = (height / 2.0 + (WebMercator.latToWorldY(me.first, zoom) - cwy)).toFloat()
        val radius = dp(7f)
        canvas.drawCircle(sx, sy, radius, myFill)
        canvas.drawCircle(sx, sy, radius, myStroke)
    }

    private fun drawAttribution(canvas: Canvas) {
        // Centered along the bottom edge — the on-surface controls now sit in the bottom corners,
        // so the attribution takes the free middle rather than colliding with the left zoom button.
        val text = "© OpenStreetMap"
        val pad = dp(4f)
        val textSize = dp(11f)
        attributionText.textSize = textSize
        val textWidth = attributionText.measureText(text)
        val cx = width / 2f
        canvas.drawRect(
            cx - textWidth / 2f - pad,
            height - textSize - pad * 2,
            cx + textWidth / 2f + pad,
            height.toFloat(),
            attributionBg,
        )
        canvas.drawText(text, cx, height - pad, attributionText)
    }

    // --- Helpers -------------------------------------------------------------

    private fun zoomBy(delta: Int, focusX: Float, focusY: Float) {
        val focusLon = screenToLon(focusX)
        val focusLat = screenToLat(focusY)
        val newZoom = (zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newZoom == zoom) return
        zoom = newZoom
        val gwx = WebMercator.lonToWorldX(focusLon, zoom)
        val gwy = WebMercator.latToWorldY(focusLat, zoom)
        val cwx = gwx - (focusX - width / 2.0)
        val cwy = gwy - (focusY - height / 2.0)
        centerLon = normalizeLon(WebMercator.worldXToLon(cwx, zoom))
        centerLat = WebMercator.worldYToLat(cwy, zoom).coerceIn(-MAX_LAT, MAX_LAT)
        hasCentered = true
        render()
    }

    private fun effectiveCenterLat(): Double {
        val me = myLocation
        return if (headingUp && followCar && me != null) me.first else centerLat
    }

    private fun effectiveCenterLon(): Double {
        val me = myLocation
        return if (headingUp && followCar && me != null) me.second else centerLon
    }

    private fun screenToLon(x: Float): Double {
        val cwx = WebMercator.lonToWorldX(effectiveCenterLon(), zoom)
        return WebMercator.worldXToLon(cwx + (x - width / 2.0), zoom)
    }

    private fun screenToLat(y: Float): Double {
        val cwy = WebMercator.latToWorldY(effectiveCenterLat(), zoom)
        return WebMercator.worldYToLat(cwy + (y - height / 2.0), zoom)
    }

    private fun centerOnData() {
        if (units.isEmpty()) return
        centerLat = units.sumOf { it.latitude } / units.size
        centerLon = units.sumOf { it.longitude } / units.size
    }

    /** Rotates a point about a pivot, matching Android's `Canvas.rotate(deg)` (clockwise, y-down). */
    private fun rotatePoint(px: Float, py: Float, ox: Float, oy: Float, deg: Float): Pair<Float, Float> {
        if (deg == 0f) return px to py
        val a = Math.toRadians(deg.toDouble())
        val c = cos(a)
        val s = sin(a)
        val dx = px - ox
        val dy = py - oy
        return (ox + dx * c - dy * s).toFloat() to (oy + dx * s + dy * c).toFloat()
    }

    private fun rotateVector(vx: Float, vy: Float, deg: Float): Pair<Float, Float> =
        rotatePoint(vx, vy, 0f, 0f, deg)

    private fun dp(value: Float): Float = value * dpi / 160f

    private fun tapSlopPx(): Float = dp(24f)

    private fun normalizeLon(lon: Double): Double {
        var l = lon
        while (l > 180) l -= 360
        while (l < -180) l += 360
        return l
    }

    private companion object {
        const val MIN_ZOOM = 3
        const val MAX_ZOOM = 19
        const val DEFAULT_ZOOM = 15
        const val MAX_LAT = 85.05112878
    }
}
