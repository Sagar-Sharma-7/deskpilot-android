package com.deskpilot.input

import com.deskpilot.hid.HidReportEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputEnginesTest {
    @Test fun `report mode mouse packet includes wheel`() {
        assertArrayEquals(byteArrayOf(0x07, 12, (-9).toByte(), 3), HidReportEncoder.mouse(0x1f, 12, -9, 3))
    }

    @Test fun `boot mode mouse packet has no wheel`() {
        assertArrayEquals(byteArrayOf(0x07, 12, (-9).toByte()), HidReportEncoder.bootMouse(0x1f, 12, -9))
    }

    @Test fun `mouse reports clamp HID relative ranges`() {
        assertArrayEquals(byteArrayOf(1, 127, -127, 127), HidReportEncoder.mouse(1, 500, -900, 500))
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

    @Test fun `one finger move moves the cursor without clicking`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 0f, 0f), 0)
        engine.move(listOf(TouchPoint(1, 50f, 0f)), 40)
        engine.up(1, 90)
        assertTrue(sink.moves.isNotEmpty())
        assertTrue(sink.clicks.isEmpty())
        assertEquals(1, sink.finishCalls)
    }

    @Test fun `one finger tap left clicks`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 10f, 10f), 0)
        engine.up(1, 80)
        assertEquals(listOf(1 to MouseButton.LEFT), sink.clicks)
    }

    @Test fun `two finger tap right clicks`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 10f, 10f), 0)
        engine.down(TouchPoint(2, 50f, 10f), 5)
        engine.up(1, 70)
        engine.up(2, 75)
        assertEquals(listOf(1 to MouseButton.RIGHT), sink.clicks)
    }

    @Test fun `two fingers scroll and never pinch or swipe`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 20f, 20f), 0)
        engine.down(TouchPoint(2, 120f, 20f), 1)
        engine.move(listOf(TouchPoint(1, 20f, 60f), TouchPoint(2, 120f, 60f)), 20)
        engine.up(1, 30)
        engine.up(2, 35)
        assertTrue(sink.scrolls.isNotEmpty())
        assertTrue(sink.pinches.isEmpty())
        assertTrue(sink.swipes.isEmpty())
    }
}

private class RecordingSink : GestureSink {
    val moves = mutableListOf<Pair<Float, Float>>()
    val scrolls = mutableListOf<Pair<Float, Float>>()
    val pinches = mutableListOf<Float>()
    val clicks = mutableListOf<Pair<Int, MouseButton>>()
    val swipes = mutableListOf<Pair<Int, Pair<Float, Float>>>()
    var finishCalls = 0

    override fun move(dx: Float, dy: Float) { moves += dx to dy }
    override fun scroll(dx: Float, dy: Float) { scrolls += dx to dy }
    override fun finishMotion() { finishCalls++ }
    override fun mouseDown() = Unit
    override fun mouseUp() = Unit
    override fun click(count: Int, button: MouseButton, isDoubleTap: Boolean) { clicks += count to button }
    override fun swipe(fingers: Int, dx: Float, dy: Float) { swipes += fingers to (dx to dy) }
    override fun pinch(scaleDelta: Float) { pinches += scaleDelta }
    override fun cancel() = Unit
}
