package app.willbolt

import app.willbolt.service.BrowserUrl
import app.willbolt.service.BrowserUrl.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserUrlTest {

    @Test
    fun hostOfTakesTheHostAndDropsWww() {
        assertEquals("example.com", BrowserUrl.hostOf("https://www.example.com/a?b"))
        assertEquals("example.com", BrowserUrl.hostOf("example.com"))
        assertEquals("m.example.com", BrowserUrl.hostOf("m.example.com:8080/x"))
        assertEquals("example.com", BrowserUrl.hostOf("view-source:https://example.com/"))
    }

    @Test
    fun hostOfIgnoresSearchesAndTitles() {
        assertNull(BrowserUrl.hostOf("cheap flights"))
        assertNull(BrowserUrl.hostOf("Example Domain"))
        assertNull(BrowserUrl.hostOf("localhost"))
        assertNull(BrowserUrl.hostOf("Search or enter address"))
        assertNull(BrowserUrl.hostOf(""))
    }

    @Test
    fun aSettledAddressCounts() {
        assertEquals("example.com", BrowserUrl.settledHost(Field("example.com")))
        assertEquals("example.com", BrowserUrl.decide(listOf(Field("example.com"))).host)
    }

    @Test
    fun firefoxDescribesTheAddressInsteadOfShowingText() {
        // Firefox 158, Slovenian: the address box has no text, only this description.
        val box = Field(null, desc = " example.org. Iskanje ali naslov strani")
        assertEquals("example.org", BrowserUrl.settledHost(box))
        assertEquals("en.wikipedia.org", BrowserUrl.settledHost(Field(null, desc = " en.wikipedia.org/wiki/Slovenia. Search or enter address")))
        // Its start page and the tab counter describe no address.
        assertNull(BrowserUrl.settledHost(Field(null, desc = " Search or enter address")))
        assertNull(BrowserUrl.settledHost(Field(null, desc = "Št. odprtih nezasebnih zavihkov: 3. Tapnite za preklop")))
    }

    @Test
    fun typingDoesNotCountEvenWhenAutocompleteShowsAWholeDomain() {
        // One letter typed, the browser guessed the rest: the bar has the keyboard.
        val typing = Field("example.com", focused = true)
        assertNull(BrowserUrl.settledHost(typing))
        assertNull(BrowserUrl.decide(listOf(typing)).host)
    }

    @Test
    fun anyFieldBeingEditedMeansNothingCountsYet() {
        // Firefox reopens in edit mode with the old address typed in; its container matches too but has no text.
        val reading = BrowserUrl.decide(listOf(Field(null), Field("https://example.com/", focused = true)))
        assertNull(reading.host)
        assertEquals("address bar is being edited", reading.seen)
    }

    @Test
    fun hintsAndTitlesDoNotCount() {
        assertNull(BrowserUrl.settledHost(Field("Search or enter address", showingHint = true)))
        assertNull(BrowserUrl.settledHost(Field("example.com", showingHint = true)))
        assertNull(BrowserUrl.settledHost(Field(null)))
        assertNull(BrowserUrl.settledHost(Field("Example Domain")))
        assertEquals("no address bar", BrowserUrl.decide(emptyList()).seen)
        assertEquals("address bar shows no address", BrowserUrl.decide(listOf(Field(null, desc = " Search or enter address"))).seen)
    }

    @Test
    fun aBarThatSlidAwayStillCountsButAVisibleOneComesFirst() {
        // Scrolling a page slides the bar off screen; the page is still open.
        assertEquals("example.org", BrowserUrl.decide(listOf(Field(null, desc = " example.org. Search", visible = false))).host)
        assertEquals("other.org", BrowserUrl.decide(listOf(Field("example.com", visible = false), Field("other.org"))).host)
    }
}
