package app.willbolt.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * The full edition mirrors the locks of the screen check, the website filter, DNS and the uninstall protection into the
 * system settings database (needs WRITE_SECURE_SETTINGS), so clearing the app's data does not silently drop them. The Play
 * edition keeps everything in the app's own storage only.
 */
internal object LockMirror {
    private const val TAG = "LockMirror"

    /**
     * The settings database is shared by every app on the phone, so the name has to be this app's own: the debug build
     * (app.willbolt.debug) installed next to the real app would otherwise take the real app's locks on its first start.
     * The real app keeps the name it always had, so locks mirrored by older versions still come back.
     */
    internal fun key(pkg: String) = if (pkg == "app.willbolt") "willbolt_state" else "willbolt_state_" + pkg.replace('.', '_')

    private fun hasSecureWrite(app: Context) =
        app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    // A running unlock is not mirrored: after a wipe the part comes back simply locked. Nor is the password, so a
    // password lock comes back as a 4-day wait - the same way round as for a forgotten password. The last field is the
    // uninstall protection the lock holds (Lock.guard); entries written before it have five fields and hold none.
    private fun enc(l: Lock) =
        "${if (l.on) 1 else 0},${l.kind.name.first()},${l.delayMin},${l.until},${l.ends},${if (l.guard) 1 else 0}"

    private fun dec(s: String, version: String): Lock? {
        val p = s.split(',')
        if (p.size < 3 || p[0] != "1") return null
        if (version == "v2") return Lock(on = true, delayMin = p[1].toIntOrNull() ?: 30, until = p[2].toLongOrNull() ?: 0L)
        if (p.size < 5) return null
        val kind = LockKind.entries.firstOrNull { it.name.first().toString() == p[1] } ?: LockKind.WAIT
        val lock = Lock(
            on = true, kind = kind, delayMin = p[2].toIntOrNull() ?: 30,
            until = p[3].toLongOrNull() ?: 0L, ends = p[4].toLongOrNull() ?: 0L,
            guard = p.getOrNull(5) == "1",
        )
        return if (kind == LockKind.PASSWORD) lock.copy(kind = LockKind.WAIT, delayMin = 4 * 24 * 60) else lock
    }

    // v4 = v3 plus the DNS lock at the end.
    internal fun encode(c: Config) =
        "v4;${enc(c.scanLock)};${enc(c.webLock)};${enc(c.selfLock)};${c.clockPenaltyMs};${enc(c.dnsLock)}"

    /** [c] with the locks [s] says were on put back, or null when [s] is not a mirror or holds no lock. */
    internal fun decode(s: String, c: Config): Config? {
        val p = s.split(';')
        if (p.size < 5 || p[0] !in listOf("v2", "v3", "v4")) return null
        val scan = dec(p[1], p[0])
        val web = dec(p[2], p[0])
        val self = dec(p[3], p[0])
        // Before v4 the website lock covered DNS too.
        val dns = if (p[0] == "v4") p.getOrNull(5)?.let { dec(it, p[0]) } else web
        if (scan == null && web == null && self == null && dns == null) return null
        return c.copy(
            scanLock = scan ?: c.scanLock,
            webLock = web ?: c.webLock,
            dnsLock = dns ?: c.dnsLock,
            selfLock = self ?: c.selfLock,
            clockPenaltyMs = p[4].toLongOrNull() ?: c.clockPenaltyMs,
        )
    }

    fun write(app: Context, c: Config) {
        if (!hasSecureWrite(app)) return
        try {
            val s = encode(c)
            if (Settings.Secure.getString(app.contentResolver, key(app.packageName)) != s) {
                Settings.Secure.putString(app.contentResolver, key(app.packageName), s)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "mirror write failed", t)
        }
    }

    /** Only for a fresh start (no settings file): [c] with the locks that were on when the data was wiped put back. */
    fun restore(app: Context, c: Config): Config {
        try {
            val s = Settings.Secure.getString(app.contentResolver, key(app.packageName)) ?: return c
            val restored = decode(s, c) ?: return c
            Log.i(TAG, "restored locks from the system settings mirror")
            return restored
        } catch (t: Throwable) {
            Log.w(TAG, "mirror restore failed", t)
            return c
        }
    }
}
