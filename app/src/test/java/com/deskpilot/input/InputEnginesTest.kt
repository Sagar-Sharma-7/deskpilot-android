package com.deskpilot.input

import com.deskpilot.hid.HidReportEncoder
import org.junit.Assert.*
import org.junit.Test

class InputEnginesTest {

    @Test fun `boot mouse report is a three byte no-report-id packet`() {
        assertArrayEquals(byteArrayOf(0x07, 12, (-9).toByte()), HidReportEncoder.bootMouse(0x1f, 12, -9))
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
        assertArrayEquals(byteArrayOf(1, 127, -127, 0, 0), HidReportEncoder.mouse(1, 500, -900))
    }
    @Test fun `keyboard release report clears every modifier and key`() {
        assertArrayEquals(ByteArray(8), HidReportEncoder.keyboard(0))
        assertEquals(1, HidReportEncoder.keyboard(1, 0x06)[0].toInt())
    }
    @Test fun `double tap emits exactly two physical clicks`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 10f, 10f), 0); engine.up(1, 80)
        engine.down(TouchPoint(2, 12f, 12f), 150); engine.up(2, 210)
        assertEquals(listOf(1, 1), sink.clicks.map { it.first })
    }
    @Test fun `movement prevents accidental tap`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(3, 0f, 0f), 0)
        engine.move(listOf(TouchPoint(3, 50f, 0f)), 40); engine.up(3, 90)
        assertTrue(sink.clicks.isEmpty()); assertTrue(sink.moves.isNotEmpty())
    }
    @Test fun `parallel two-finger scrolling never turns into a pinch`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 20f, 20f), 0); engine.down(TouchPoint(2, 120f, 20f), 1)
        engine.move(listOf(TouchPoint(1, 20f, 45f), TouchPoint(2, 121f, 45f)), 20)
        engine.move(listOf(TouchPoint(1, 20f, 100f), TouchPoint(2, 123f, 100f)), 40)
        assertTrue(sink.scrolls.isNotEmpty()); assertTrue(sink.pinches.isEmpty())
    }
    @Test fun `cancel releases held drag`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 0f, 0f), 0); engine.up(1, 50)
        engine.down(TouchPoint(2, 0f, 0f), 100); engine.move(listOf(TouchPoint(2, 50f, 0f)), 120); engine.cancel()
        assertEquals(1, sink.downs); assertEquals(1, sink.ups)
    }
}

private class RecordingSink : GestureSink {
    val moves = mutableListOf<Pair<Float, Float>>(); val scrolls = mutableListOf<Pair<Float, Float>>(); val pinches = mutableListOf<Float>(); val clicks = mutableListOf<Pair<Int, MouseButton>>(); var downs = 0; var ups = 0
    override fun move(dx: Float, dy: Float) { moves += dx to dy }; override fun scroll(dx: Float, dy: Float) { scrolls += dx to dy }
    override fun mouseDown() { downs++ }; override fun mouseUp() { ups++ }; override fun click(count: Int, button: MouseButton, isDoubleTap: Boolean) { clicks += count to button }
    override fun swipe(fingers: Int, dx: Float, dy: Float) = Unit; override fun pinch(scaleDelta: Float) { pinches += scaleDelta }; override fun cancel() = Unit
}
