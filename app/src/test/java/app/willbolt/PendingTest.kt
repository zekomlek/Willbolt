package app.willbolt

import app.willbolt.core.pendingValue
import app.willbolt.data.DetectAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingTest {
    // Sensitivity: locked at 74, higher is stricter.
    private val atLeast74 = { v: Int -> v.coerceAtLeast(74) }

    @Test
    fun `a pick on a part that is not locked is never waiting - it is saved at once`() {
        assertNull(pendingValue(current = 74, picked = 80, locked = false, clamp = atLeast74))
    }

    @Test
    fun `nothing picked means nothing waiting`() {
        assertNull(pendingValue(74, null, locked = true, clamp = atLeast74))
    }

    @Test
    fun `a stricter pick waits for Confirm`() {
        assertEquals(80, pendingValue(74, 80, locked = true, clamp = atLeast74))
    }

    @Test
    fun `picking the saved value again waits for nothing`() {
        assertNull(pendingValue(74, 74, locked = true, clamp = atLeast74))
    }

    @Test
    fun `a looser pick is not allowed so it does not wait`() {
        // The slider is dragged back below the floor: the pick is held at 74 (what clamp gives) and nothing waits.
        assertNull(pendingValue(74, 60, locked = true, clamp = atLeast74))
        assertEquals(74, atLeast74(60))
    }

    @Test
    fun `a shorter interval is the stricter side for check-every`() {
        val atMost7 = { v: Int -> v.coerceAtMost(7) }
        assertEquals(3, pendingValue(7, 3, locked = true, clamp = atMost7))
        assertNull(pendingValue(7, 12, locked = true, clamp = atMost7))
    }

    // "When something is found": the Filter screen picks the action by its place in DetectAction, mildest first.

    @Test
    fun `the screen check's actions are declared mildest first - the Filter screen relies on it`() {
        assertEquals(
            listOf(DetectAction.OVERLAY_ONLY, DetectAction.HOME, DetectAction.HOME_AND_LOCKOUT, DetectAction.LOCK_SCREEN),
            DetectAction.entries,
        )
    }

    @Test
    fun `a stricter action waits for Confirm while locked, a milder one is not allowed`() {
        val order = DetectAction.entries
        val saved = order.indexOf(DetectAction.HOME)
        val notMilder = { i: Int -> i.coerceAtLeast(saved) }
        val lock = order.indexOf(DetectAction.LOCK_SCREEN)
        assertEquals(lock, pendingValue(saved, lock, locked = true, clamp = notMilder))
        assertNull(pendingValue(saved, order.indexOf(DetectAction.OVERLAY_ONLY), locked = true, clamp = notMilder))
        assertNull(pendingValue(saved, saved, locked = true, clamp = notMilder)) // picking what is saved calls the pick off
    }

    @Test
    fun `an action picked on a part that is not locked is saved at once, not waiting`() {
        val order = DetectAction.entries
        val saved = order.indexOf(DetectAction.HOME_AND_LOCKOUT)
        assertNull(pendingValue(saved, order.indexOf(DetectAction.OVERLAY_ONLY), locked = false, clamp = { it }))
    }
}
