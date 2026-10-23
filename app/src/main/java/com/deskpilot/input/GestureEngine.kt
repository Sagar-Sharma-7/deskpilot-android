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

/**
 * Small, predictable touchpad gesture set:
 * one finger moves/taps, two fingers scroll, and a two-finger tap right-clicks.
 * Dragging remains available through the explicit left button below the pad.
 */
class GestureEngine(private val sink: GestureSink, private val config: Config = Config()) {
    data class Config(val tapSlop: Float = 18f, val tapTimeoutMs: Long = 280)

    private var points = emptyMap<Int, TouchPoint>()
    private var start = emptyMap<Int, TouchPoint>()
    private var startedAt = 0L
    private var maxFingers = 0
    private var moved = false

    fun down(point: TouchPoint, time: Long) {
        val firstFinger = points.isEmpty()
        points = points + (point.id to point)
        if (firstFinger) {
            start = points
            startedAt = time
            maxFingers = 1
            moved = false
        } else {
            start = points
            maxFingers = maxOf(maxFingers, points.size)
        }
    }

    fun move(current: List<TouchPoint>, time: Long) {
        val next = current.associateBy { it.id }
        if (next.isEmpty()) return
        val previous = points
        points = next
        maxFingers = maxOf(maxFingers, next.size)
        when {
            next.size == 1 && previous.size == 1 -> {
                val point = next.values.first()
                val old = previous[point.id] ?: return
                val total = start[point.id]?.let { hypot((point.x - it.x).toDouble(), (point.y - it.y).toDouble()).toFloat() } ?: 0f
                if (total > config.tapSlop) moved = true
                sink.move(point.x - old.x, point.y - old.y)
            }

            next.size == 2 && previous.size == 2 -> {
                val ids = next.keys.sorted()
                val first = next.getValue(ids[0])
                val second = next.getValue(ids[1])
                val oldFirst = previous[ids[0]] ?: return
                val oldSecond = previous[ids[1]] ?: return
                val dx = ((first.x - oldFirst.x) + (second.x - oldSecond.x)) / 2f
                val dy = ((first.y - oldFirst.y) + (second.y - oldSecond.y)) / 2f
                if (dx != 0f || dy != 0f) {
                    moved = true
                    sink.scroll(dx, dy)
                }
            }
        }
    }

    fun up(id: Int, time: Long) {
        val released = points[id] ?: return
        val fingers = maxFingers
        points = points - id
        if (points.isNotEmpty()) {
            start = points
            return
        }
        sink.finishMotion()
        if (!moved && time - startedAt < config.tapTimeoutMs) {
            when (fingers) {
                1 -> sink.click()
                2 -> sink.click(button = MouseButton.RIGHT)
            }
        }
        reset()
    }

    fun cancel() {
        sink.cancel()
        reset()
    }

    private fun reset() {
        points = emptyMap()
        start = emptyMap()
        maxFingers = 0
        moved = false
    }
}
