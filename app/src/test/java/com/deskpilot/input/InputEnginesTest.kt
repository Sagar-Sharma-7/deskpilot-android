package com.deskpilot.input

import com.deskpilot.hid.HidReportEncoder
import org.junit.Assert.*
import org.junit.Test

class InputEnginesTest {

    @Test fun `mouse report is a three byte boot-compatible packet`() {
        assertArrayEquals(byteArrayOf(0x07, 12, (-9).toByte()), HidReportEncoder.mouse(0x1f, 12, -9))
    }
    @Test fun `pointer retains subpixel movement until a HID integer exists`() {
        val engine = PointerEngine(PointerTuning(sensitivity = 1f, acceleration = false))
        assertEquals(0 to 0, engine.move(.4f, .4f))
        assertEquals(0 to 0, engine.move(.4f, .4f))
        assertEquals(1 to 1, engine.move(.4f, .4f))
    }
    @Test fun `scroll retains fractional wheel delta`() {
        val engine = ScrollEngine(.25f)
        assertEquals(0 to 0, engine.scroll(0f, 2f, natural = true))
        assertEquals(0 to 1, engine.scroll(0f, 2f, natural = true))
    }
    @Test fun `mouse reports clamp HID relative ranges`() {
        assertArrayEquals(byteArrayOf(1, 127, -127), HidReportEncoder.mouse(1, 500, -900))
    }
    @Test fun `double tap emits exactly two physical clicks`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 10f, 10f), 0); engine.up(1, 80)
        engine.down(TouchPoint(2, 12f, 12f), 150); engine.up(2, 210)
        assertEquals(listOf(1, 1), sink.clicks.map { it.first })
    }
    @Test fun `a move after a tap elsewhere does not start text-selection drag`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 10f, 10f), 0); engine.up(1, 80)
        engine.down(TouchPoint(2, 180f, 180f), 150)
        engine.move(listOf(TouchPoint(2, 250f, 180f)), 180)
        assertEquals(0, sink.downs)
    }
    @Test fun `second tap at the same place can begin a drag`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 10f, 10f), 0); engine.up(1, 80)
        engine.down(TouchPoint(2, 12f, 12f), 150)
        engine.move(listOf(TouchPoint(2, 50f, 12f)), 180)
        assertEquals(1, sink.downs)
    }
    @Test fun `movement prevents accidental tap`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(3, 0f, 0f), 0)
        engine.move(listOf(TouchPoint(3, 50f, 0f)), 40); engine.up(3, 90)
        assertTrue(sink.clicks.isEmpty()); assertTrue(sink.moves.isNotEmpty()); assertEquals(1, sink.finishCalls)
    }
    @Test fun `parallel two-finger scrolling never turns into a pinch`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 20f, 20f), 0); engine.down(TouchPoint(2, 120f, 20f), 1)
        engine.move(listOf(TouchPoint(1, 20f, 45f), TouchPoint(2, 121f, 45f)), 20)
        engine.move(listOf(TouchPoint(1, 20f, 100f), TouchPoint(2, 123f, 100f)), 40)
        assertTrue(sink.scrolls.isNotEmpty()); assertTrue(sink.pinches.isEmpty())
    }
    @Test fun `intentional two-finger separation activates pinch instead of scroll`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 40f, 100f), 0); engine.down(TouchPoint(2, 160f, 100f), 1)
        engine.move(listOf(TouchPoint(1, 15f, 100f), TouchPoint(2, 185f, 100f)), 30)
        assertTrue(sink.pinches.isNotEmpty()); assertTrue(sink.scrolls.isEmpty())
    }
    @Test fun `three-finger swipe emits desktop gesture`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 30f, 120f), 0); engine.down(TouchPoint(2, 80f, 120f), 1); engine.down(TouchPoint(3, 130f, 120f), 2)
        engine.move(listOf(TouchPoint(1, 30f, 0f), TouchPoint(2, 80f, 0f), TouchPoint(3, 130f, 0f)), 50)
        engine.up(1, 60); engine.up(2, 70); engine.up(3, 80)
        assertEquals(listOf(3 to (0f to -120f)), sink.swipes)
    }
    @Test fun `cancel releases held drag`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 0f, 0f), 0); engine.up(1, 50)
        engine.down(TouchPoint(2, 0f, 0f), 100); engine.move(listOf(TouchPoint(2, 50f, 0f)), 120); engine.cancel()
        assertEquals(1, sink.downs); assertEquals(1, sink.ups)
    }
}

private class RecordingSink : GestureSink {
    val moves = mutableListOf<Pair<Float, Float>>(); val scrolls = mutableListOf<Pair<Float, Float>>(); val pinches = mutableListOf<Float>(); val clicks = mutableListOf<Pair<Int, MouseButton>>(); val swipes = mutableListOf<Pair<Int, Pair<Float, Float>>>(); var downs = 0; var ups = 0; var finishCalls = 0
    override fun move(dx: Float, dy: Float) { moves += dx to dy }; override fun scroll(dx: Float, dy: Float) { scrolls += dx to dy }; override fun finishMotion() { finishCalls++ }
    override fun mouseDown() { downs++ }; override fun mouseUp() { ups++ }; override fun click(count: Int, button: MouseButton, isDoubleTap: Boolean) { clicks += count to button }
    override fun swipe(fingers: Int, dx: Float, dy: Float) { swipes += fingers to (dx to dy) }; override fun pinch(scaleDelta: Float) { pinches += scaleDelta }; override fun cancel() = Unit
}
