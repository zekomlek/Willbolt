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
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Event(val t: Long, val type: String, val text: String)

/** Small local activity log. Never contains screenshots - only "what happened, where, when". */
object EventLog {
    private const val TAG = "EventLog"
    private const val MAX = 300

    private val json = Json { ignoreUnknownKeys = true }
    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)
    private val lastSeen = HashMap<String, Long>()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "events.json")
        file = f
        try {
            if (f.exists()) {
                val loaded = json.decodeFromString(ListSerializer(Event.serializer()), f.readText())
                _events.value = (loaded + _events.value).takeLast(MAX)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not read event log", t)
        }
        scope.launch {
            for (signal in saveSignal) {
                delay(800)
                persist()
            }
        }
    }

    /** Adds an entry, unless the same one was added less than [dedupeMs] ago. True when it was added. */
    fun add(type: String, text: String, dedupeMs: Long = 0L): Boolean {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (dedupeMs > 0) {
                val key = "$type|$text"
                val prev = lastSeen[key]
                if (prev != null && now - prev < dedupeMs) return false
                lastSeen[key] = now
                if (lastSeen.size > 500) lastSeen.clear()
            }
            _events.value = (_events.value + Event(now, type, text)).takeLast(MAX)
        }
        saveSignal.trySend(Unit)
        return true
    }

    fun clear() {
        _events.value = emptyList()
        saveSignal.trySend(Unit)
    }

    private fun persist() {
        val f = file ?: return
        try {
            f.writeText(json.encodeToString(ListSerializer(Event.serializer()), _events.value))
        } catch (t: Throwable) {
            Log.w(TAG, "could not save event log", t)
        }
    }
}
