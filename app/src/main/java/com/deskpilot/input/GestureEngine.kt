package com.deskpilot.input

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sign

data class TouchPoint(val id: Int, val x: Float, val y: Float)

/** A semantic two-finger scroll sample, before the HID layer quantises it. */
data class ScrollDelta(val dx: Float, val dy: Float, val timestamp: Long)

data class GestureDiagnostics(
    val state: GestureState = GestureState.IDLE,
    val pointerCount: Int = 0,
    val recognitionLatencyMs: Long? = null,
    val centroidDx: Float = 0f,
    val centroidDy: Float = 0f,
    val separationDelta: Float = 0f,
    val scrollDx: Float = 0f,
    val scrollDy: Float = 0f,
    val pinchDelta: Float = 0f,
    val cancellations: Int = 0
)

interface GestureSink {
    fun move(dx: Float, dy: Float)
    fun scroll(delta: ScrollDelta)
    fun finishMotion()
    fun mouseDown()
    fun mouseUp()
    fun click(count: Int = 1, button: MouseButton = MouseButton.LEFT, isDoubleTap: Boolean = false)
    fun swipe(fingers: Int, dx: Float, dy: Float)
    fun multiFingerTap(fingers: Int)
    fun pinch(scaleDelta: Float)
    fun cancel()
}

enum class MouseButton { LEFT, RIGHT, MIDDLE }

/** Exposed for the optional developer diagnostics. */
enum class GestureState {
    IDLE, POINTER, TWO_FINGER_TAP_CANDIDATE, TWO_FINGER_PENDING, TWO_FINGER_SCROLL, TWO_FINGER_PINCH,
    MULTI_FINGER_PENDING, MULTI_FINGER_ACTIVE
}

/**
 * Predictable touchpad gestures with a small evidence window for two fingers.
 * Translation and separation are accumulated independently. This recognises a
 * pinch even when one finger is nearly stationary, while tolerating the small
 * separation changes that occur during a normal two-finger scroll.
 */
class GestureEngine(private val sink: GestureSink, private val config: Config = Config()) {
    data class Config(
        val density: Float = 1f,
        val tapSlopDp: Float = 6f,
        val scrollActivationDp: Float = 3.5f,
        val pinchActivationDp: Float = 5f,
        val multiFingerSwipeDp: Float = 12f,
        val tapTimeoutMs: Long = 280,
        val doubleTapDragTimeoutMs: Long = 900
    ) {
        val tapSlop get() = tapSlopDp * density
        val scrollActivationSlop get() = scrollActivationDp * density
        val pinchActivationSlop get() = pinchActivationDp * density
        val multiFingerSwipeSlop get() = multiFingerSwipeDp * density
    }

    var state: GestureState = GestureState.IDLE
        private set
    private val _diagnostics = MutableStateFlow(GestureDiagnostics())
    val diagnostics: StateFlow<GestureDiagnostics> = _diagnostics
    private var diagnosticsEnabled = false

    private var points = emptyMap<Int, TouchPoint>()
    private var start = emptyMap<Int, TouchPoint>()
    private var startedAt = 0L
    private var maxFingers = 0
    private var moved = false
    private var lastTapAt = Long.MIN_VALUE
    private var lastTap: TouchPoint? = null
    private var dragging = false

    private var twoFingerStartDistance = 0f
    private var twoFingerLastDistance = 0f
    private var twoFingerLastCenter = 0f to 0f
    private var translationEvidence = 0f
    private var scaleEvidence = 0f
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var twoFingerMotionFinished = false
    private var recognitionLatencyMs: Long? = null
    private var lastCentroidDx = 0f
    private var lastCentroidDy = 0f
    private var lastSeparationDelta = 0f
    private var lastScrollDx = 0f
    private var lastScrollDy = 0f
    private var lastPinchDelta = 0f
    private var cancellationCount = 0
    private var multiFingerStartCenter = 0f to 0f
    private var multiFingerDispatched = false

    fun setDiagnosticsEnabled(enabled: Boolean) {
        diagnosticsEnabled = enabled
        publishDiagnostics()
    }

    fun down(point: TouchPoint, time: Long) {
        val firstFinger = points.isEmpty()
        points = points + (point.id to point)
        if (firstFinger) {
            val doubleTapDrag = time - lastTapAt <= config.doubleTapDragTimeoutMs && lastTap?.let {
                distance(point, it) < config.tapSlop
            } == true
            start = points
            startedAt = time
            maxFingers = 1
            moved = false
            dragging = doubleTapDrag
            state = GestureState.POINTER
            if (dragging) sink.mouseDown()
            publishDiagnostics()
            return
        }

        start = points
        maxFingers = max(maxFingers, points.size)
        when (points.size) {
            2 -> beginTwoFingerGesture()
            in 3..5 -> beginMultiFingerGesture()
        }
        publishDiagnostics()
    }

    /** Historical MotionEvent samples are supplied before the current sample. */
    fun move(current: List<TouchPoint>, time: Long) {
        if (current.isEmpty()) return
        val next = current.associateBy { it.id }
        val previous = points
        points = next
        maxFingers = max(maxFingers, next.size)

        when {
            next.size == 1 && previous.size == 1 -> movePointer(next, previous)
            next.size == 2 && previous.size == 2 && state != GestureState.MULTI_FINGER_PENDING && state != GestureState.MULTI_FINGER_ACTIVE -> moveTwoFingers(next, previous, time)
            next.size in 3..5 && previous.size == next.size -> moveMultiFinger(next)
            next.size == 1 && previous.size == 2 -> {
                // A pointer-count transition has no meaningful one-finger delta.
                start = next
                state = GestureState.POINTER
            }
        }
        publishDiagnostics()
    }

    fun up(id: Int, time: Long) {
        val released = points[id] ?: return
        val fingers = maxFingers
        val endsActiveTwoFingerGesture = points.size == 2 &&
            (state == GestureState.TWO_FINGER_SCROLL || state == GestureState.TWO_FINGER_PINCH)
        points = points - id
        if (endsActiveTwoFingerGesture) {
            sink.finishMotion()
            twoFingerMotionFinished = true
        }
        if (points.isNotEmpty()) {
            start = points
            if (points.size == 1) state = GestureState.POINTER
            publishDiagnostics()
            return
        }

        if (!twoFingerMotionFinished) sink.finishMotion()
        if (dragging) {
            sink.mouseUp()
        } else if (!moved && time - startedAt < config.tapTimeoutMs) {
            when (fingers) {
                1 -> {
                    sink.click()
                    lastTapAt = time
                    lastTap = released
                }
                2 -> sink.click(button = MouseButton.RIGHT)
                3 -> sink.multiFingerTap(3)
            }
        }
        reset()
        publishDiagnostics()
    }

    fun cancel() {
        if (dragging) sink.mouseUp()
        sink.cancel()
        cancellationCount++
        reset()
        publishDiagnostics()
    }

    private fun movePointer(next: Map<Int, TouchPoint>, previous: Map<Int, TouchPoint>) {
        val point = next.values.first()
        val old = previous[point.id] ?: return
        val total = start[point.id]?.let { distance(point, it) } ?: 0f
        if (total > config.tapSlop) moved = true
        sink.move(point.x - old.x, point.y - old.y)
    }

    private fun moveTwoFingers(next: Map<Int, TouchPoint>, previous: Map<Int, TouchPoint>, time: Long) {
        val ids = next.keys.sorted()
        val first = next.getValue(ids[0])
        val second = next.getValue(ids[1])
        val oldFirst = previous[ids[0]] ?: return
        val oldSecond = previous[ids[1]] ?: return
        val centroid = center(first, second)
        val dx = centroid.first - twoFingerLastCenter.first
        val dy = centroid.second - twoFingerLastCenter.second
        val distance = distance(first, second)
        val separationDelta = distance - twoFingerLastDistance
        lastCentroidDx = dx
        lastCentroidDy = dy
        lastSeparationDelta = separationDelta
        lastScrollDx = 0f
        lastScrollDy = 0f
        lastPinchDelta = 0f

        // Kept separate from the centroid: one finger may be nearly stationary.
        val firstDx = first.x - oldFirst.x
        val firstDy = first.y - oldFirst.y
        val secondDx = second.x - oldSecond.x
        val secondDy = second.y - oldSecond.y
        val fingerVelocityAgreement = firstDx * secondDx + firstDy * secondDy

        pendingDx += dx
        pendingDy += dy
        translationEvidence += hypot(dx.toDouble(), dy.toDouble()).toFloat()
        scaleEvidence += abs(separationDelta)
        twoFingerLastCenter = centroid
        twoFingerLastDistance = distance

        if (state == GestureState.TWO_FINGER_TAP_CANDIDATE &&
            (translationEvidence > config.tapSlop * .25f || scaleEvidence > config.tapSlop * .25f)
        ) state = GestureState.TWO_FINGER_PENDING

        val classifiedNow = if (state == GestureState.TWO_FINGER_PENDING || state == GestureState.TWO_FINGER_TAP_CANDIDATE) {
            classifyTwoFingerGesture(fingerVelocityAgreement, distance, time)
        } else false

        when (state) {
            GestureState.TWO_FINGER_SCROLL -> {
                val bufferedDx = pendingDx
                val bufferedDy = pendingDy
                pendingDx = 0f
                pendingDy = 0f
                if (bufferedDx != 0f || bufferedDy != 0f) {
                    moved = true
                    lastScrollDx = bufferedDx
                    lastScrollDy = bufferedDy
                    sink.scroll(ScrollDelta(bufferedDx, bufferedDy, time))
                }
            }
            GestureState.TWO_FINGER_PINCH -> if (!classifiedNow && separationDelta != 0f) {
                moved = true
                lastPinchDelta = separationDelta / PINCH_DISTANCE_SCALE
                sink.pinch(separationDelta / PINCH_DISTANCE_SCALE)
            }
            else -> Unit
        }
    }

    /** Multi-finger input is one deliberate directional action, never a continuous scroll. */
    private fun moveMultiFinger(next: Map<Int, TouchPoint>) {
        if (multiFingerDispatched) return
        val centroid = centerOf(next.values)
        val dx = centroid.first - multiFingerStartCenter.first
        val dy = centroid.second - multiFingerStartCenter.second
        if (max(abs(dx), abs(dy)) < config.multiFingerSwipeSlop) return
        multiFingerDispatched = true
        moved = true
        state = GestureState.MULTI_FINGER_ACTIVE
        sink.swipe(next.size.coerceAtMost(5), dx, dy)
    }

    private fun classifyTwoFingerGesture(fingerVelocityAgreement: Float, distance: Float, time: Long): Boolean {
        val translationScore = translationEvidence / config.scrollActivationSlop
        val scaleScore = scaleEvidence / config.pinchActivationSlop
        if (translationScore < 1f && scaleScore < 1f) return false

        // Small speed differences create separation noise while scrolling. A
        // one-finger-stationary pinch moves the centroid, so scaling gets a
        // modest confidence advantage.
        val pinchWins = scaleScore > translationScore * PINCH_CONFIDENCE_RATIO ||
            (scaleScore >= 1f && fingerVelocityAgreement < 0f && scaleScore >= translationScore)
        if (pinchWins) {
            state = GestureState.TWO_FINGER_PINCH
            recognitionLatencyMs = time - startedAt
            pendingDx = 0f
            pendingDy = 0f
            val totalChange = distance - twoFingerStartDistance
            val overshoot = (abs(totalChange) - config.pinchActivationSlop).coerceAtLeast(0f) * sign(totalChange)
            if (overshoot != 0f) {
                moved = true
                lastPinchDelta = overshoot / PINCH_DISTANCE_SCALE
                sink.pinch(overshoot / PINCH_DISTANCE_SCALE)
            }
        } else if (translationScore >= 1f) {
            state = GestureState.TWO_FINGER_SCROLL
            recognitionLatencyMs = time - startedAt
        }
        return state == GestureState.TWO_FINGER_SCROLL || state == GestureState.TWO_FINGER_PINCH
    }

    private fun beginTwoFingerGesture() {
        twoFingerStartDistance = distanceBetweenFirstTwo()
        twoFingerLastDistance = twoFingerStartDistance
        twoFingerLastCenter = center()
        translationEvidence = 0f
        scaleEvidence = 0f
        pendingDx = 0f
        pendingDy = 0f
        state = GestureState.TWO_FINGER_TAP_CANDIDATE
    }

    private fun beginMultiFingerGesture() {
        multiFingerStartCenter = centerOf(points.values)
        multiFingerDispatched = false
        state = GestureState.MULTI_FINGER_PENDING
    }

    private fun reset() {
        points = emptyMap()
        start = emptyMap()
        maxFingers = 0
        moved = false
        dragging = false
        translationEvidence = 0f
        scaleEvidence = 0f
        pendingDx = 0f
        pendingDy = 0f
        twoFingerMotionFinished = false
        multiFingerDispatched = false
        recognitionLatencyMs = null
        state = GestureState.IDLE
    }

    private fun publishDiagnostics() {
        if (!diagnosticsEnabled) return
        _diagnostics.value = GestureDiagnostics(
            state = state,
            pointerCount = points.size,
            recognitionLatencyMs = recognitionLatencyMs,
            centroidDx = lastCentroidDx,
            centroidDy = lastCentroidDy,
            separationDelta = lastSeparationDelta,
            scrollDx = lastScrollDx,
            scrollDy = lastScrollDy,
            pinchDelta = lastPinchDelta,
            cancellations = cancellationCount
        )
    }

    private fun distanceBetweenFirstTwo(): Float = points.values.take(2).let { values ->
        if (values.size < 2) 0f else distance(values[0], values[1])
    }

    private fun center(): Pair<Float, Float> = points.values.take(2).let { center(it[0], it[1]) }
    private fun centerOf(values: Collection<TouchPoint>): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        values.forEach { point -> x += point.x; y += point.y }
        return x / values.size to y / values.size
    }
    private fun center(first: TouchPoint, second: TouchPoint) = (first.x + second.x) / 2f to (first.y + second.y) / 2f
    private fun distance(first: TouchPoint, second: TouchPoint) = hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble()).toFloat()

    private companion object {
        const val PINCH_DISTANCE_SCALE = 100f
        const val PINCH_CONFIDENCE_RATIO = 1.08f
    }
}
