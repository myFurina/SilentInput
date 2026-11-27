package com.silentinput.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTouchpadTest {

    @Test
    fun `center touch maps cleanly to center of virtual touchpad`() {
        val mapped = NativeTouchpadMapper.mapContacts(
            pointerIds = intArrayOf(3),
            xs = floatArrayOf(500f),
            ys = floatArrayOf(400f),
            count = 1,
            surfaceWidthPx = 1000,
            surfaceHeightPx = 800,
            xdpi = 400f,
            ydpi = 400f,
            fallbackDensity = 2.5f,
            sensitivity = 1.0f
        )
        assertEquals(listOf(TouchpadContact(3, 4000, 4000)), mapped)
    }

    @Test
    fun `corners stay inside 20mm palm safe margin even at max sensitivity`() {
        val mapped = NativeTouchpadMapper.mapContacts(
            pointerIds = intArrayOf(0, 1),
            xs = floatArrayOf(0f, 1080f),
            ys = floatArrayOf(0f, 1800f),
            count = 2,
            surfaceWidthPx = 1080,
            surfaceHeightPx = 1800,
            xdpi = 420f,
            ydpi = 420f,
            fallbackDensity = 2.625f,
            sensitivity = 2.0f
        )
        assertEquals(2, mapped.size)
        for (c in mapped) {
            assertTrue(c.x in NativeTouchpadMapper.EDGE_MARGIN..(NativeTouchpadMapper.MAX_X - NativeTouchpadMapper.EDGE_MARGIN))
            assertTrue(c.y in NativeTouchpadMapper.EDGE_MARGIN..(NativeTouchpadMapper.MAX_Y - NativeTouchpadMapper.EDGE_MARGIN))
        }
    }

    @Test
    fun `asymmetric xdpi ydpi preserves uniform physical mm delta and invalid dpi falls back`() {
        val mapped = NativeTouchpadMapper.mapContacts(
            pointerIds = intArrayOf(1, 2),
            xs = floatArrayOf(300f, 100f),
            ys = floatArrayOf(200f, 600f),
            count = 2,
            surfaceWidthPx = 200,
            surfaceHeightPx = 400,
            xdpi = 200f,
            ydpi = 400f,
            fallbackDensity = 2f,
            sensitivity = 1.0f
        )
        assertEquals(2, mapped.size)
        val dx = mapped[0].x - 4000
        val dy = mapped[1].y - 4000
        assertEquals(dx, dy)

        val fallbackMapped = NativeTouchpadMapper.mapContacts(
            pointerIds = intArrayOf(5),
            xs = floatArrayOf(200f),
            ys = floatArrayOf(100f),
            count = 1,
            surfaceWidthPx = 400,
            surfaceHeightPx = 200,
            xdpi = 0f,
            ydpi = Float.NaN,
            fallbackDensity = 2f,
            sensitivity = 1.0f
        )
        assertEquals(listOf(TouchpadContact(5, 4000, 4000)), fallbackMapped)
    }

    @Test
    fun `mapContacts caps contacts at MAX_CONTACTS`() {
        val mapped = NativeTouchpadMapper.mapContacts(
            pointerIds = intArrayOf(0, 1, 2, 3, 4, 5, 6),
            xs = FloatArray(7) { 200f },
            ys = FloatArray(7) { 200f },
            count = 7,
            surfaceWidthPx = 400,
            surfaceHeightPx = 400,
            xdpi = 320f,
            ydpi = 320f,
            fallbackDensity = 2f,
            sensitivity = 0.6f
        )
        assertEquals(NativeTouchpadMapper.MAX_CONTACTS, mapped.size)
        assertEquals(4, mapped.last().pointerId)
    }

    @Test
    fun `session filter coalesces staggered two-finger touchdown and prevents liftoff tail whip`() {
        val filter = NativeTouchpadSessionFilter(22L)
        val f1Start = TouchpadContact(0, 4000, 4000)
        val f1LeadMove = TouchpadContact(0, 4000, 3850)
        val f2Start = TouchpadContact(1, 4500, 4050)
        val f1FastEnd = TouchpadContact(0, 4000, 2500)
        val f2FastEnd = TouchpadContact(1, 4500, 2550)

        // Finger 1 lands slightly early and starts moving within 22ms window -> buffered, zero pointer jump
        assertTrue(filter.onDown(listOf(f1Start), timeMs = 1000L).isEmpty())
        assertTrue(filter.isEntryBuffered)
        assertTrue(filter.onMove(listOf(f1LeadMove), timeMs = 1010L).isEmpty())

        // Finger 2 lands at +15ms -> 1-finger lead-in discarded, emits clean 2-finger entry
        val twoDown = filter.onPointerDown(listOf(f1LeadMove, f2Start))
        assertFalse(filter.isEntryBuffered)
        assertEquals(
            listOf(TouchpadEmitFrame(listOf(f1LeadMove, f2Start), isTransition = true)),
            twoDown
        )

        // Normal 2-finger scroll
        val twoMove = filter.onMove(listOf(f1FastEnd, f2FastEnd), timeMs = 1030L)
        assertEquals(
            listOf(TouchpadEmitFrame(listOf(f1FastEnd, f2FastEnd), isTransition = false)),
            twoMove
        )

        // Finger 2 lifts 25ms before Finger 1 -> immediately emits clean 2->0 liftoff
        val onFirstUp = filter.onPointerUp(listOf(f1FastEnd))
        assertEquals(listOf(TouchpadEmitFrame(emptyList(), isTransition = true)), onFirstUp)

        // Remaining Finger 1 fast trailing moves and final ACTION_UP are ignored (no duplicate empty or cursor whip)
        assertTrue(filter.onMove(listOf(TouchpadContact(0, 4000, 1800)), timeMs = 1050L).isEmpty())
        assertTrue(filter.onAllUp().isEmpty())
    }

    @Test
    fun `session filter preserves ultra-fast single-finger tap within coalesce window`() {
        val filter = NativeTouchpadSessionFilter(22L)
        val c = TouchpadContact(0, 4000, 4000)

        assertTrue(filter.onDown(listOf(c), timeMs = 100L).isEmpty())
        val upFrames = filter.onAllUp()
        assertEquals(
            listOf(
                TouchpadEmitFrame(listOf(c), isTransition = true),
                TouchpadEmitFrame(emptyList(), isTransition = true)
            ),
            upFrames
        )
    }

    @Test
    fun `session filter flushes single-finger drag on timeout without losing initial displacement`() {
        val filter = NativeTouchpadSessionFilter(22L)
        val c0 = TouchpadContact(0, 4000, 4000)
        val c1 = TouchpadContact(0, 4080, 4020)

        assertTrue(filter.onDown(listOf(c0), timeMs = 100L).isEmpty())
        assertTrue(filter.onMove(listOf(c1), timeMs = 112L).isEmpty())

        val flushed = filter.onEntryTimeout()
        assertFalse(filter.isEntryBuffered)
        assertEquals(
            listOf(
                TouchpadEmitFrame(listOf(c0), isTransition = true),
                TouchpadEmitFrame(listOf(c1), isTransition = false)
            ),
            flushed
        )
        assertEquals(
            listOf(TouchpadEmitFrame(emptyList(), isTransition = true)),
            filter.onAllUp()
        )
    }

    @Test
    fun `session filter supports 1-to-2-to-3 escalation and lift-locks immediately on 3-to-2`() {
        val filter = NativeTouchpadSessionFilter(22L)
        val c0 = TouchpadContact(0, 3800, 4000)
        val c1 = TouchpadContact(1, 4100, 4000)
        val c2 = TouchpadContact(2, 4400, 4000)

        filter.onDown(listOf(c0), timeMs = 100L)
        filter.onPointerDown(listOf(c0, c1))
        val threeDown = filter.onPointerDown(listOf(c0, c1, c2))
        assertEquals(
            listOf(TouchpadEmitFrame(listOf(c0, c1, c2), isTransition = true)),
            threeDown
        )

        // Dropping from 3 -> 2 locks liftoff cleanly
        assertEquals(
            listOf(TouchpadEmitFrame(emptyList(), isTransition = true)),
            filter.onPointerUp(listOf(c0, c1))
        )
        assertTrue(filter.onPointerUp(listOf(c0)).isEmpty())
        assertTrue(filter.onAllUp().isEmpty())
    }

    @Test
    fun `session filter respects antiLiftWhip disabled mode`() {
        val filter = NativeTouchpadSessionFilter(initialEntryCoalesceMs = 22L, initialAntiLiftWhip = false)
        val c0 = TouchpadContact(0, 8000, 8000)
        val c1 = TouchpadContact(1, 8600, 8000)

        filter.onDown(listOf(c0), timeMs = 100L)
        filter.onPointerDown(listOf(c0, c1))
        val downToOne = filter.onPointerUp(listOf(c0))
        assertEquals(listOf(TouchpadEmitFrame(listOf(c0), isTransition = true)), downToOne)

        val moveOne = filter.onMove(listOf(TouchpadContact(0, 8100, 8000)), timeMs = 130L)
        assertEquals(listOf(TouchpadEmitFrame(listOf(TouchpadContact(0, 8100, 8000)), isTransition = false)), moveOne)
        assertEquals(listOf(TouchpadEmitFrame(emptyList(), isTransition = true)), filter.onAllUp())
    }
}
