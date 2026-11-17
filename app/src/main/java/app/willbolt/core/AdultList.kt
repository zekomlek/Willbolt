package app.willbolt.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.willbolt.data.Config
import app.willbolt.data.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Offline list of adult domains, most common first (StevenBlack/hosts "porn" extension, MIT, ordered by the
 * Majestic Million ranking - see tools/make-adult-list.py). Only the first [Config.adultListSize] entries are kept in
 * memory, so a smaller setting is cheaper. A domain also covers its subdomains.
 */
object AdultList {
    private const val TAG = "AdultList"
    private const val ASSET = "lists/adult-domains.txt"

    class Parsed(val domains: HashSet<String>, val available: Int)

    class Stats(val loaded: Int, val available: Int, val millis: Long)

    @Volatile
    private var set: HashSet<String>? = null

    /** What the last load did: how many domains are in use, how many the file has, how long reading took. */
    @Volatile
    var stats: Stats = Stats(0, 0, 0)
        private set

    private val lock = Any()
    private var wanted = Config.ALL_SITES
    private var loadedFor = -1
    private var working = false
    private var observing = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val size: Int get() = set?.size ?: 0
    val ready: Boolean get() = set != null

    /**
     * Loads the list and keeps it in step with the settings: the "how many sites" choice, or nothing at all (just the
     * header, to learn how many there are) while the feature is switched off. Safe to call more than once.
     */
    fun preload(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) {
            if (observing) return
            observing = true
        }
        scope.launch {
            ConfigStore.state
                .map { if (it.adultDomainList) it.adultListSize else 0 }
                .distinctUntilChanged()
                .collect { request(app, it) }
        }
    }

    private fun request(app: Context, limit: Int) {
        synchronized(lock) {
            wanted = limit
            if (working) return
            working = true
        }
        thread(name = "adult-list", isDaemon = true) {
            while (true) {
                var l = 0
                synchronized(lock) {
                    if (wanted == loadedFor) {
                        working = false
                        return@thread
                    }
                    l = wanted
                }
                try {
                    val t0 = SystemClock.elapsedRealtime()
                    val parsed = app.assets.open(ASSET).bufferedReader().useLines { parse(it, l) }
                    set = parsed.domains
                    stats = Stats(parsed.domains.size, parsed.available, SystemClock.elapsedRealtime() - t0)
                    Log.i(TAG, "using ${parsed.domains.size} of ${parsed.available} domains (${stats.millis} ms)")
                } catch (t: Throwable) {
                    Log.w(TAG, "could not load adult list", t)
                    if (set == null) set = HashSet()
                }
                synchronized(lock) { loadedFor = l }
            }
        }
    }

    /** Reads the list file: '#' lines are comments, every other line is one domain, most common first. */
    fun parse(lines: Sequence<String>, limit: Int): Parsed {
        val out = HashSet<String>(minOf(limit, 60_000) * 4 / 3 + 16)
        var counted = 0
        var declared = -1
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line[0] == '#') {
                if (line.startsWith("# entries:")) declared = line.substringAfter(':').trim().toIntOrNull() ?: -1
                continue
            }
            counted++
            if (out.size < limit) out.add(line.lowercase())
            else if (declared >= 0) break // the header already told us how many there are
        }
        return Parsed(out, if (declared >= 0) declared else counted)
    }

    fun matches(host: String): Boolean {
        val s = set ?: return false
        return RuleEngine.suffixes(host).any { it in s }
    }
}
