package app.willbolt.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** How much a rule's apps were used in the current window, plus the state of its break timer. All times are TimeGuard time. */
@Serializable
data class RuleUsage(
    /** Start of the daily window the numbers below belong to. */
    val windowStart: Long = 0L,
    /** Use inside this window, in ms. */
    val usedMs: Long = 0L,
    /** Closed until this time (allowance used up). 0 = not closed. */
    val blockedUntil: Long = 0L,
    /** Use since the last break, in ms. */
    val sinceBreakMs: Long = 0L,
    /** Last time one of the rule's apps was in front. */
    val lastActiveAt: Long = 0L,
    /** A break is running until this time. 0 = none. */
    val breakUntil: Long = 0L,
    /** How long the running (or paused) break is in all, for the progress bar. 0 = not known. */
    val breakLenMs: Long = 0L,
    /** What is left of a break that was paused because another app was in use ([Breaks.pauseAway]). 0 = none. */
    val breakLeftMs: Long = 0L,
    /** The "one minute left" notice has been shown for this window. */
    val warned: Boolean = false,
)

/**
 * Usage numbers per rule id, kept apart from [ConfigStore] because they change every second.
 * Written to disk every few seconds (and when the service stops), so a crash costs at most a few seconds of the count.
 */
object UsageStore {
    private const val TAG = "UsageStore"
    private const val SAVE_EVERY_MS = 10_000L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = MapSerializer(String.serializer(), RuleUsage.serializer())

    private val _state = MutableStateFlow<Map<String, RuleUsage>>(emptyMap())
    val state: StateFlow<Map<String, RuleUsage>> = _state.asStateFlow()

    private var file: File? = null
    @Volatile
    private var dirty = false
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "usage.json")
        file = f
        try {
            if (f.exists()) _state.value = json.decodeFromString(serializer, f.readText())
        } catch (t: Throwable) {
            Log.w(TAG, "could not read usage", t)
        }
        scope.launch {
            for (signal in saveSignal) {
                delay(SAVE_EVERY_MS)
                flush()
            }
        }
    }

    fun get(ruleId: String): RuleUsage = _state.value[ruleId] ?: RuleUsage()

    fun put(ruleId: String, usage: RuleUsage) {
        _state.update { it + (ruleId to usage) }
        dirty = true
        saveSignal.trySend(Unit)
    }

    /** Forgets the numbers of rules that no longer exist. */
    fun prune(validIds: Set<String>) {
        if (_state.value.keys.all { it in validIds }) return
        _state.update { m -> m.filterKeys { it in validIds } }
        dirty = true
        saveSignal.trySend(Unit)
    }

    @Synchronized
    fun flush() {
        val f = file ?: return
        if (!dirty) return
        dirty = false
        try {
            val tmp = File(f.parentFile, "usage.json.tmp")
            tmp.writeText(json.encodeToString(serializer, _state.value))
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            dirty = true
            Log.w(TAG, "could not save usage", t)
        }
    }
}
