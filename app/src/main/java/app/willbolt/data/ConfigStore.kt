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
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Single source of truth for settings, kept in the app's private storage.
 *
 * In the full edition the locks of the screen check, the website filter and the uninstall protection are also mirrored into
 * the system settings database (see LockMirror), so clearing the app's data does not silently drop them. The Play edition
 * keeps everything here.
 */
object ConfigStore {
    private const val TAG = "ConfigStore"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(Config())
    val state: StateFlow<Config> = _state.asStateFlow()
    val value: Config get() = _state.value

    @Volatile
    private var file: File? = null
    private var appCtx: Context? = null
    val context: Context get() = checkNotNull(appCtx) { "ConfigStore.init() was not called" }

    /** The settings have been loaded ([init] ran). */
    val ready: Boolean get() = file != null

    private val saveSignal = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val app = context.applicationContext
        appCtx = app
        val f = File(app.filesDir, "config.json")
        file = f
        val fresh = !f.exists() && !File(app.filesDir, "config.json.bak").exists()
        _state.value = read(f)
        if (fresh) _state.value = LockMirror.restore(app, _state.value)
        scope.launch {
            for (signal in saveSignal) {
                delay(150)
                writeNow()
            }
        }
    }

    /** Never call TimeGuard.now() (or anything that calls update) inside [transform]. */
    fun update(transform: (Config) -> Config) {
        val next = synchronized(this) {
            val n = transform(_state.value)
            _state.value = n
            n
        }
        saveSignal.trySend(Unit)
        appCtx?.let { LockMirror.write(it, next) }
    }

    private fun read(f: File): Config {
        for (candidate in listOf(f, File(f.parentFile, "config.json.bak"))) {
            try {
                if (candidate.exists()) return decode(candidate.readText())
            } catch (t: Throwable) {
                Log.w(TAG, "could not read ${candidate.name}", t)
            }
        }
        return Config()
    }

    /** Settings from their file. Before 0.4.2 the Websites padlock also covered DNS, so DNS starts out locked the same. */
    internal fun decode(text: String): Config {
        val c = json.decodeFromString(Config.serializer(), text)
        return if ("\"dnsLock\"" in text) c else c.copy(dnsLock = c.webLock)
    }

    private fun writeNow() {
        val f = file ?: return
        try {
            val text = json.encodeToString(Config.serializer(), _state.value)
            val dir = f.parentFile ?: return
            val tmp = File(dir, "config.json.tmp")
            tmp.writeText(text)
            if (f.exists()) f.copyTo(File(dir, "config.json.bak"), overwrite = true)
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not save config", t)
        }
    }
}
