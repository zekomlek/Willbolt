package app.willbolt.data

import android.content.Context

/** The Play edition keeps its settings, locks included, only in the app's own storage. The full edition mirrors the locks. */
@Suppress("UNUSED_PARAMETER")
internal object LockMirror {
    fun write(app: Context, c: Config) {}

    fun restore(app: Context, c: Config): Config = c
}
