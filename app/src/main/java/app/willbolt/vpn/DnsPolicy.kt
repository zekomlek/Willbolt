package app.willbolt.vpn

import app.willbolt.core.DomainPolicy
import app.willbolt.core.RuleEngine
import app.willbolt.data.Config

/** What to do with one DNS question. */
object DnsPolicy {
    /** Names starting like this are answered by the filter itself; the service uses them to test its own tunnel. */
    const val SELFTEST_PREFIX = "willbolt-selftest-"

    sealed interface Decision {
        data class Block(val reason: String) : Decision
        data class Rewrite(val target: String) : Decision
        data object SelfTest : Decision
        data object Forward : Decision
    }

    /** Public encrypted-DNS endpoints. Browsers and Android's Private DNS use these to skip filters. */
    private val encryptedDnsHosts = setOf(
        "dns.google", "dns.google.com", "dns64.dns.google",
        "cloudflare-dns.com", "chrome.cloudflare-dns.com", "mozilla.cloudflare-dns.com",
        "one.one.one.one", "1dot1dot1dot1.cloudflare-dns.com",
        "dns.quad9.net", "doh.opendns.com", "dns.adguard-dns.com", "dns.nextdns.io",
        "doh.cleanbrowsing.org", "dns.sb", "doh.dns.sb", "doh.mullvad.net", "dns.mullvad.net",
    )

    private val googleSearch = Regex("^(www\\.)?google\\.(com|[a-z]{2}|co\\.[a-z]{2}|com\\.[a-z]{2,3})$")

    /** Safe-search enforcement: these names are answered with the search engine's own "family-safe" front end. */
    fun safeSearchTarget(host: String): String? = when {
        googleSearch.matches(host) -> "forcesafesearch.google.com"
        host == "bing.com" || host == "www.bing.com" -> "strict.bing.com"
        host == "duckduckgo.com" || host == "www.duckduckgo.com" -> "safe.duckduckgo.com"
        host == "www.youtube.com" || host == "m.youtube.com" || host == "youtubei.googleapis.com" ||
            host == "youtube.googleapis.com" || host == "www.youtube-nocookie.com" -> "restrictmoderate.youtube.com"
        else -> null
    }

    fun decide(cfg: Config, host: String): Decision {
        val h = host.lowercase().trim('.')
        if (h.isEmpty()) return Decision.Forward
        if (h.startsWith(SELFTEST_PREFIX)) return Decision.SelfTest

        if (cfg.closeDnsLoopholes && RuleEngine.suffixes(h).any { it in encryptedDnsHosts }) {
            return Decision.Block("encrypted-DNS loophole")
        }
        DomainPolicy.verdict(cfg, h)?.let { return Decision.Block(it.reason) }
        if (cfg.safeSearch) safeSearchTarget(h)?.let { return Decision.Rewrite(it) }
        return Decision.Forward
    }
}
