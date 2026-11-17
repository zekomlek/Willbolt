package app.willbolt.core

import app.willbolt.data.Config

/** Decides whether a website host is blocked. Used by both the DNS filter and the browser URL check. */
object DomainPolicy {
    sealed interface Verdict {
        val reason: String

        /** [ruleName] is the name you gave the block; empty when it has none. */
        data class ByRule(val ruleName: String) : Verdict {
            override val reason get() = if (ruleName.isEmpty()) "on your block list" else "blocked by \"$ruleName\""
        }

        data object Adult : Verdict {
            override val reason get() = "adult site"
        }
    }

    fun verdict(cfg: Config, host: String, now: Long = TimeGuard.now()): Verdict? {
        val h = host.lowercase().trim('.')
        if (h.isEmpty()) return null
        val suffixes = RuleEngine.suffixes(h)
        if (cfg.allowDomains.isNotEmpty() && suffixes.any { it in cfg.allowDomains }) return null
        RuleEngine.blockedDomainRule(cfg, h, now)?.let { return Verdict.ByRule(it.name.trim()) }
        if (cfg.adultDomainList && AdultList.matches(h)) return Verdict.Adult
        return null
    }
}
