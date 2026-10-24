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

    @Test fun `advanced mouse packet includes horizontal pan`() {
        assertArrayEquals(byteArrayOf(0x03, 12, (-9).toByte(), 2, (-4).toByte()), HidReportEncoder.advancedMouse(3, 12, -9, 2, -4))
    }

    @Test fun `consumer packet exposes media controls`() {
        assertArrayEquals(byteArrayOf(HidReportEncoder.CONSUMER_VOLUME_UP.toByte()), HidReportEncoder.consumer(HidReportEncoder.CONSUMER_VOLUME_UP))
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

    @Test fun `second tap held and moved begins a selection drag`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 10f, 10f), 0)
        engine.up(1, 80)
        engine.down(TouchPoint(2, 12f, 12f), 500)
        engine.move(listOf(TouchPoint(2, 45f, 12f)), 540)
        engine.up(2, 600)
        assertEquals(1, sink.mouseDowns)
        assertEquals(1, sink.mouseUps)
    }

    @Test fun `a delayed second tap does not start a selection drag`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 10f, 10f), 0)
        engine.up(1, 80)
        engine.down(TouchPoint(2, 12f, 12f), 1_100)
        engine.move(listOf(TouchPoint(2, 45f, 12f)), 1_140)
        assertEquals(0, sink.mouseDowns)
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

    @Test fun `large two finger separation produces pinch instead of scroll`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 50f, 100f), 0)
        engine.down(TouchPoint(2, 150f, 100f), 1)
        engine.move(listOf(TouchPoint(1, 10f, 100f), TouchPoint(2, 190f, 100f)), 30)
        engine.move(listOf(TouchPoint(1, 0f, 100f), TouchPoint(2, 200f, 100f)), 45)
        assertTrue(sink.pinches.isNotEmpty())
        assertTrue(sink.scrolls.isEmpty())
    }

    @Test fun `pinch with a small center drift never becomes scroll`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 50f, 100f), 0)
        engine.down(TouchPoint(2, 150f, 100f), 1)
        engine.move(listOf(TouchPoint(1, 30f, 110f), TouchPoint(2, 170f, 110f)), 30)
        engine.move(listOf(TouchPoint(1, 20f, 115f), TouchPoint(2, 180f, 115f)), 45)
        assertTrue(sink.pinches.isNotEmpty())
        assertTrue(sink.scrolls.isEmpty())
    }

    @Test fun `slow two finger scrolling keeps fractional motion and never pinches`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 20f, 20f), 0)
        engine.down(TouchPoint(2, 120f, 20f), 1)
        for (step in 1..8) {
            engine.move(listOf(TouchPoint(1, 20f, 20f + step * 2f), TouchPoint(2, 120f, 20f + step * 2.2f)), step * 16L)
        }
        assertTrue(sink.scrolls.isNotEmpty())
        assertTrue(sink.scrolls.sumOf { it.second.toDouble() } > 0)
        assertTrue(sink.pinches.isEmpty())
    }

    @Test fun `one stationary finger can still pinch`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 40f, 100f), 0)
        engine.down(TouchPoint(2, 140f, 100f), 1)
        engine.move(listOf(TouchPoint(1, 40f, 100f), TouchPoint(2, 165f, 100f)), 20)
        engine.move(listOf(TouchPoint(1, 40f, 100f), TouchPoint(2, 185f, 100f)), 40)
        assertTrue(sink.pinches.isNotEmpty())
        assertTrue(sink.scrolls.isEmpty())
    }

    @Test fun `two finger tap stays a right click`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 10f, 10f), 0)
        engine.down(TouchPoint(2, 70f, 10f), 5)
        engine.move(listOf(TouchPoint(1, 11f, 10f), TouchPoint(2, 71f, 10f)), 20)
        engine.up(1, 45)
        engine.up(2, 50)
        assertEquals(listOf(1 to MouseButton.RIGHT), sink.clicks)
        assertTrue(sink.scrolls.isEmpty())
        assertTrue(sink.pinches.isEmpty())
    }

    @Test fun `active two finger gesture finishes when either finger is lifted`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 20f, 20f), 0)
        engine.down(TouchPoint(2, 120f, 20f), 1)
        engine.move(listOf(TouchPoint(1, 20f, 50f), TouchPoint(2, 120f, 50f)), 20)
        engine.up(2, 30)
        assertEquals(1, sink.finishCalls)
        engine.up(1, 35)
        assertEquals(1, sink.finishCalls)
    }

    @Test fun `cancelled gesture does not produce a later click`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 20f, 20f), 0)
        engine.down(TouchPoint(2, 120f, 20f), 1)
        engine.move(listOf(TouchPoint(1, 20f, 50f), TouchPoint(2, 120f, 50f)), 20)
        engine.cancel()
        assertTrue(sink.clicks.isEmpty())
        assertTrue(sink.cancelled)
    }

    @Test fun `three finger swipe emits one directional action`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 20f, 20f), 0)
        engine.down(TouchPoint(2, 70f, 20f), 1)
        engine.down(TouchPoint(3, 120f, 20f), 2)
        engine.move(listOf(TouchPoint(1, 20f, 55f), TouchPoint(2, 70f, 55f), TouchPoint(3, 120f, 55f)), 30)
        engine.move(listOf(TouchPoint(1, 20f, 95f), TouchPoint(2, 70f, 95f), TouchPoint(3, 120f, 95f)), 45)
        assertEquals(1, sink.swipes.size)
        assertEquals(3, sink.swipes.single().first)
    }

    @Test fun `three finger tap is distinct from a two finger right click`() {
        val sink = RecordingSink(); val engine = GestureEngine(sink)
        engine.down(TouchPoint(1, 20f, 20f), 0)
        engine.down(TouchPoint(2, 70f, 20f), 1)
        engine.down(TouchPoint(3, 120f, 20f), 2)
        engine.up(1, 50)
        engine.up(2, 55)
        engine.up(3, 60)
        assertEquals(listOf(3), sink.multiFingerTaps)
        assertTrue(sink.clicks.isEmpty())
    }
}

private class RecordingSink : GestureSink {
    val moves = mutableListOf<Pair<Float, Float>>()
    val scrolls = mutableListOf<Pair<Float, Float>>()
    val pinches = mutableListOf<Float>()
    val clicks = mutableListOf<Pair<Int, MouseButton>>()
    val swipes = mutableListOf<Pair<Int, Pair<Float, Float>>>()
    val multiFingerTaps = mutableListOf<Int>()
    var finishCalls = 0
    var mouseDowns = 0
    var mouseUps = 0
    var cancelled = false

    override fun move(dx: Float, dy: Float) { moves += dx to dy }
    override fun scroll(delta: ScrollDelta) { scrolls += delta.dx to delta.dy }
    override fun finishMotion() { finishCalls++ }
    override fun mouseDown() { mouseDowns++ }
    override fun mouseUp() { mouseUps++ }
    override fun click(count: Int, button: MouseButton, isDoubleTap: Boolean) { clicks += count to button }
    override fun swipe(fingers: Int, dx: Float, dy: Float) { swipes += fingers to (dx to dy) }
    override fun multiFingerTap(fingers: Int) { multiFingerTaps += fingers }
    override fun pinch(scaleDelta: Float) { pinches += scaleDelta }
    override fun cancel() { cancelled = true }
}
