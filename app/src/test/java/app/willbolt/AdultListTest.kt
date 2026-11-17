package app.willbolt

import app.willbolt.core.AdultList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdultListTest {
    private val sample = sequenceOf(
        "# Willbolt adult-site list",
        "# entries: 5",
        "",
        "pornhub.com",
        "XVIDEOS.com",
        "  example-three.net  ",
        "four.org",
        "five.tv",
    )

    @Test
    fun onlyTheFirstEntriesAreKeptAndTheTotalComesFromTheHeader() {
        val p = AdultList.parse(sample, 3)
        assertEquals(setOf("pornhub.com", "xvideos.com", "example-three.net"), p.domains)
        assertEquals(5, p.available)
    }

    @Test
    fun allEntriesWhenTheLimitIsBigAndTheCountWithoutAHeader() {
        val all = AdultList.parse(sample, 1_000_000)
        assertEquals(5, all.domains.size)
        val noHeader = AdultList.parse(sequenceOf("a.com", "b.com", "# comment", "c.com"), 2)
        assertEquals(2, noHeader.domains.size)
        assertEquals(3, noHeader.available) // without a header it has to read to the end to know
    }

    @Test
    fun theBundledListIsSortedAndFormattedAsTheAppExpects() {
        // Gradle runs unit tests from the module folder (app/).
        val f = File("src/main/assets/lists/adult-domains.txt")
        assertTrue("list file missing at ${f.absolutePath}", f.exists())
        val p = f.useLines { AdultList.parse(it, 1_000_000) }
        assertEquals("header count must match the entries", p.available, p.domains.size)
        assertTrue("popular sites come first", f.useLines { l -> l.filter { !it.startsWith("#") }.take(30).toList() }.contains("pornhub.com"))
        val top1000 = f.useLines { l -> l.filter { !it.startsWith("#") }.take(1000).toSet() }
        assertTrue("xvideos.com" in top1000 && "xhamster.com" in top1000 && "onlyfans.com" in top1000)
        assertFalse("no raw hosts-file lines", p.domains.any { it.startsWith("0.0.0.0") || it.contains(' ') })
    }
}
