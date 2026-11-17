package app.willbolt.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the address of the page the browser on screen has open. The DNS filter is the main defence; this catches the rest.
 *
 * Only a settled address counts as a visit. While the bar is being typed in, its text is a draft plus the browser's
 * autocomplete guess (one letter can already show a whole domain), and start pages list sites from the history
 * (shortcuts, recent tabs). Neither means the site is open.
 */
object BrowserUrl {
    /** Browsers we know even if they do not say they open links. Others are found by [discover]. */
    private val knownBrowsers = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
        "com.brave.browser", "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
        "com.sec.android.app.sbrowser", "org.mozilla.firefox", "org.mozilla.fenix", "org.mozilla.focus",
        "com.duckduckgo.mobile.android", "com.vivaldi.browser", "com.kiwibrowser.browser",
    )

    /**
     * View ids of address bars: url_bar (Chrome and its relatives), url_field (Opera), location_bar_edit_text (Samsung),
     * omnibarTextInput (DuckDuckGo), mozac_browser_toolbar_url_view and, since Firefox 15x, ADDRESSBAR_URL_BOX /
     * ADDRESSBAR_SEARCH_BOX (Compose test tags, which is why this walks the tree instead of looking ids up).
     */
    private val addressId = Regex("url|address|omnib|location", RegexOption.IGNORE_CASE)

    @Volatile
    private var discovered: Set<String> = emptySet()

    /** Finds every installed app that opens plain https links (i.e. the browsers). */
    fun discover(ctx: Context) {
        try {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
            discovered = ctx.packageManager.queryIntentActivities(probe, PackageManager.MATCH_ALL)
                .map { it.activityInfo.packageName }
                .toSet()
        } catch (_: Throwable) {
        }
    }

    fun isBrowser(pkg: String) = pkg in knownBrowsers || pkg in discovered

    /**
     * What an address field shows, as plain values so the rules can be unit-tested. [focused] = it has the keyboard, so
     * its text is a draft, maybe with an autocomplete guess after the cursor. (Not the text selection: browsers leave
     * one behind after you press Enter, and that would hide a real visit.) [visible] is false for a bar that slid away
     * while the page scrolls - that page is still open.
     */
    data class Field(
        val text: String?,
        val desc: String? = null,
        val showingHint: Boolean = false,
        val focused: Boolean = false,
        val visible: Boolean = true,
    )

    /** One look at a browser window: the host of the open page (null if none) and what was seen, for the dump. */
    data class Reading(val host: String?, val seen: String)

    /** The host a field shows, or null while it is being edited, empty, or shows something else (a title, a search). */
    fun settledHost(f: Field): String? {
        if (f.focused) return null
        if (!f.showingHint) f.text?.let(::hostOf)?.let { return it }
        // Firefox draws the address and only describes it: " example.org. Search or enter address" (in the phone's language).
        return f.desc?.split(' ')?.firstNotNullOfOrNull { hostOf(it.trimEnd('.', ',', ';', ':')) }
    }

    /** Decides from all address fields in the window. Any of them being edited means the user is typing, so nothing counts yet. */
    fun decide(fields: List<Field>): Reading {
        if (fields.isEmpty()) return Reading(null, "no address bar")
        if (fields.any { it.focused }) return Reading(null, "address bar is being edited")
        val host = fields.sortedByDescending { it.visible }.firstNotNullOfOrNull(::settledHost)
            ?: return Reading(null, "address bar shows no address")
        return Reading(host, "address bar: $host")
    }

    fun read(root: AccessibilityNodeInfo): Reading = decide(addressFields(root).map(::fieldOf))

    /** For the dump: every address field found, by view id. */
    fun inspect(root: AccessibilityNodeInfo): List<Pair<String, Field>> =
        addressFields(root).map { it.viewIdResourceName.orEmpty() to fieldOf(it) }

    private fun fieldOf(n: AccessibilityNodeInfo): Field {
        // Focus changes without the events this service listens to, so ask the browser instead of the cache.
        n.refresh()
        return Field(
            text = n.text?.toString(),
            desc = n.contentDescription?.toString(),
            showingHint = n.isShowingHintText,
            focused = n.isFocused,
            visible = n.isVisibleToUser,
        )
    }

    /**
     * Address-bar nodes, outside lists and the web page itself: a start page's recent sites or the tab overview are not
     * the address bar. (A short Compose list does not say it scrolls, so lists are also recognised by their collection info.)
     */
    private fun addressFields(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < 300) {
            val n = queue.removeFirst()
            seen++
            val id = n.viewIdResourceName?.substringAfter(":id/")
            if (id != null && addressId.containsMatchIn(id)) out.add(n)
            if (isListOrPage(n)) continue
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    fun isListOrPage(n: AccessibilityNodeInfo) =
        n.isScrollable || n.collectionInfo != null || n.className?.contains("WebView") == true

    /** "https://www.example.com/a?b" -> "example.com". Returns null for search terms and other non-URLs. */
    fun hostOf(text: String): String? {
        var t = text.trim().lowercase()
        if (t.isEmpty() || t.contains(' ')) return null
        t = t.removePrefix("view-source:").removePrefix("https://").removePrefix("http://")
        val end = t.indexOfFirst { it == '/' || it == '?' || it == '#' || it == ':' }
        val host = (if (end >= 0) t.substring(0, end) else t).removePrefix("www.").trim('.')
        if (host.length < 4 || !host.contains('.')) return null
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        return host
    }
}
