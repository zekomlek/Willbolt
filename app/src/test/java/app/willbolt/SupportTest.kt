package app.willbolt

import app.willbolt.core.Support
import app.willbolt.data.ConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-time "Happy with Willbolt?" popup (full edition) asks only after a few days and a few blocks, and only once. */
class SupportTest {
    private val day = 86_400_000L
    private val installed = 1_800_000_000_000L
    private val enough = Support.MIN_BLOCKS

    @Test
    fun asksOnceBothDaysAndBlocksAreThere() {
        assertTrue(Support.due(installed, installed + 3 * day, enough, asked = false))
        assertTrue(Support.due(installed, installed + 30 * day, enough, asked = false))
    }

    @Test
    fun notBeforeThreeDays() {
        assertFalse(Support.due(installed, installed + 3 * day - 1, enough, asked = false))
        assertFalse(Support.due(installed, installed, enough, asked = false))
    }

    @Test
    fun notBeforeAFewBlocks() {
        assertFalse(Support.due(installed, installed + 10 * day, enough - 1, asked = false))
        assertFalse(Support.due(installed, installed + 10 * day, 0, asked = false))
    }

    @Test
    fun neverAgainOnceAsked() {
        assertFalse(Support.due(installed, installed + 10 * day, enough, asked = true))
    }

    @Test
    fun anUnknownInstallTimeOrAClockBeforeItNeverAsks() {
        assertFalse(Support.due(0L, installed + 10 * day, enough, asked = false))
        assertFalse(Support.due(installed, installed - 10 * day, enough, asked = false))
    }

    @Test
    fun theCountStopsWhereNothingNeedsMore() {
        assertEquals(1, Support.counted(0))
        assertEquals(enough, Support.counted(enough - 1))
        assertEquals(enough, Support.counted(enough))
    }

    @Test
    fun anOlderSettingsFileStartsWithNothingCountedAndNotAsked() {
        // Files from 0.6.8 and before have neither field: counting starts at 0, so an update does not ask at once.
        val c = ConfigStore.decode("""{"scanSensitivity":50,"privacyNoteSeen":true}""")
        assertEquals(0, c.blocksSeen)
        assertFalse(c.supportAsked)
    }
}
