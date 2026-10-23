package com.deskpilot.input

import kotlin.math.hypot

data class TouchPoint(val id: Int, val x: Float, val y: Float)

interface GestureSink {
    fun move(dx: Float, dy: Float)
    fun scroll(dx: Float, dy: Float)
    fun finishMotion()
    fun mouseDown()
    fun mouseUp()
    fun click(count: Int = 1, button: MouseButton = MouseButton.LEFT, isDoubleTap: Boolean = false)
    fun swipe(fingers: Int, dx: Float, dy: Float)
    fun pinch(scaleDelta: Float)
    fun cancel()
}
enum class MouseButton { LEFT, RIGHT, MIDDLE }

/** Deterministic state machine built around stable pointer IDs, not pointer indexes. */
class GestureEngine(private val sink: GestureSink, private val config: Config = Config()) {
    data class Config(
        val tapSlop: Float = 18f,
        val dragSlop: Float = 22f,
        val swipeDistance: Float = 110f,
        val doubleTapMs: Long = 280,
        val scrollActivationSlop: Float = 10f,
        val pinchActivationSlop: Float = 34f
    )
    private enum class TwoFingerMode { UNDECIDED, SCROLL, PINCH }
    private var points = emptyMap<Int, TouchPoint>()
    private var start = emptyMap<Int, TouchPoint>()
    private var startedAt = 0L
    private var maxFingers = 0
    private var moved = false
    private var scrolling = false
    private var dragging = false
    private var twoFingerMode = TwoFingerMode.UNDECIDED
    private var lastTapAt = -10_000L
    private var lastTap: TouchPoint? = null
    private var pinchDistance = 0f
    private var twoFingerStartDistance = 0f
    private var twoFingerStartCenter = 0f to 0f
    private var gestureOrigin = 0f to 0f
    private var latestCentroid = 0f to 0f

    fun down(point: TouchPoint, time: Long) {
        val hadNone = points.isEmpty()
        points = points + (point.id to point)
        if (hadNone) {
            start = points; startedAt = time; maxFingers = 1; moved = false; scrolling = false; dragging = false; twoFingerMode = TwoFingerMode.UNDECIDED
            gestureOrigin = centroid(); latestCentroid = gestureOrigin
        } else {
            // Rebaseline when the contact count changes: prevents cursor jump.
            start = points; maxFingers = maxOf(maxFingers, points.size)
            if (points.size == 2) {
                pinchDistance = distanceBetweenFirstTwo()
                twoFingerStartDistance = pinchDistance
                twoFingerStartCenter = centroid()
                twoFingerMode = TwoFingerMode.UNDECIDED
            } else if (points.size >= 3) {
                // A multi-finger swipe starts when the final finger lands, not
                // at the first one-finger contact. This removes an artificial
                // sideways component from an otherwise vertical swipe.
                gestureOrigin = centroid()
                latestCentroid = gestureOrigin
            }
        }
    }

    fun move(current: List<TouchPoint>, time: Long) {
        val next = current.associateBy { it.id }
        if (next.isEmpty()) return
        val old = points
        points = next
        maxFingers = maxOf(maxFingers, points.size)
        latestCentroid = centroid()
        if (points.size == 1 && old.size == 1) {
            val p = points.values.first(); val previous = old[p.id] ?: return
            val dx = p.x - previous.x; val dy = p.y - previous.y
            val total = start[p.id]?.let { hypot((p.x - it.x).toDouble(), (p.y - it.y).toDouble()).toFloat() } ?: 0f
            if (total > config.tapSlop) moved = true
            if (!dragging && lastTapAt + config.doubleTapMs >= time && total > config.dragSlop) { dragging = true; sink.mouseDown() }
            sink.move(dx, dy)
        } else if (points.size == 2 && old.size == 2) {
            val ids = points.keys.sorted(); val p1 = points.getValue(ids[0]); val p2 = points.getValue(ids[1])
            val o1 = old[ids[0]] ?: return; val o2 = old[ids[1]] ?: return
            val dx = ((p1.x - o1.x) + (p2.x - o2.x)) / 2f; val dy = ((p1.y - o1.y) + (p2.y - o2.y)) / 2f
            val newDistance = hypot((p1.x - p2.x).toDouble(), (p1.y - p2.y).toDouble()).toFloat()
            val centerTravel = distance(centroid(), twoFingerStartCenter)
            val separationTravel = kotlin.math.abs(newDistance - twoFingerStartDistance)
            if (twoFingerMode == TwoFingerMode.UNDECIDED) {
                val fingerDotProduct = (p1.x - o1.x) * (p2.x - o2.x) + (p1.y - o1.y) * (p2.y - o2.y)
                twoFingerMode = when {
                    // Parallel travel selects scroll before tiny finger-spacing drift can become a pinch.
                    centerTravel >= config.scrollActivationSlop && centerTravel >= separationTravel -> TwoFingerMode.SCROLL
                    separationTravel >= config.pinchActivationSlop && separationTravel > centerTravel * 1.25f && fingerDotProduct < 0f -> TwoFingerMode.PINCH
                    else -> TwoFingerMode.UNDECIDED
                }
            }
            when (twoFingerMode) {
                TwoFingerMode.SCROLL -> if (dx != 0f || dy != 0f) { moved = true; scrolling = true; sink.scroll(dx, dy) }
                TwoFingerMode.PINCH -> {
                    val distanceChange = newDistance - pinchDistance
                    if (distanceChange != 0f) { moved = true; sink.pinch(distanceChange / 120f); pinchDistance = newDistance }
                }
                TwoFingerMode.UNDECIDED -> Unit
            }
        } else if (points.size >= 3) {
            val first = points.values.first(); val origin = start[first.id] ?: first
            if (hypot((first.x - origin.x).toDouble(), (first.y - origin.y).toDouble()) > config.tapSlop) moved = true
        }
    }

    fun up(id: Int, time: Long) {
        val released = points[id] ?: return
        val finalCount = maxFingers
        points = points - id
        if (points.isNotEmpty()) { start = points; pinchDistance = distanceBetweenFirstTwo(); return }
        // Never replay a queue of stale pointer deltas after the finger lifts.
        sink.finishMotion()
        if (dragging) { sink.mouseUp(); reset(); return }
        val elapsed = time - startedAt
        if (!moved && elapsed < 280) {
            when (finalCount) {
                1 -> {
                    val isDouble = time - lastTapAt <= config.doubleTapMs && lastTap?.let { hypot((it.x - released.x).toDouble(), (it.y - released.y).toDouble()) < config.tapSlop } == true
                    // The first tap was dispatched immediately for low latency; the second tap adds
                    // one more click, producing the two-click sequence Windows expects.
                    sink.click(1, isDoubleTap = isDouble)
                    lastTapAt = if (isDouble) -10_000 else time
                    lastTap = released
                }
                2 -> sink.click(button = MouseButton.RIGHT)
                3 -> sink.click(button = MouseButton.MIDDLE)
            }
        } else if (finalCount >= 3 && moved) {
            val dx = latestCentroid.first - gestureOrigin.first; val dy = latestCentroid.second - gestureOrigin.second
            if (hypot(dx.toDouble(), dy.toDouble()) >= config.swipeDistance) sink.swipe(finalCount, dx, dy)
        }
        reset()
    }
    fun cancel() { if (dragging) sink.mouseUp(); sink.cancel(); reset() }
    private fun reset() { points = emptyMap(); start = emptyMap(); moved = false; scrolling = false; dragging = false; twoFingerMode = TwoFingerMode.UNDECIDED; maxFingers = 0 }
    private fun distanceBetweenFirstTwo(): Float = if (points.size < 2) 0f else points.values.take(2).let { hypot((it[0].x-it[1].x).toDouble(), (it[0].y-it[1].y).toDouble()).toFloat() }
    private fun centroid(): Pair<Float, Float> = points.values.fold(0f to 0f) { sum, p -> sum.first + p.x to sum.second + p.y }.let { it.first / points.size to it.second / points.size }
    private fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot((a.first - b.first).toDouble(), (a.second - b.second).toDouble()).toFloat()
}
