package app.willbolt.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import app.willbolt.core.Health
import app.willbolt.core.Oem
import app.willbolt.core.Policy
import app.willbolt.data.Config
import app.willbolt.data.EventLog

/**
 * While the uninstall protection is on, bounces you out of the screens where Willbolt could be stopped, wiped or
 * uninstalled: its App-info page (Force stop, Clear data, Uninstall), the device-admin page, uninstall dialogs, and the
 * phone maker's battery / app-launch manager (Honor's "App launch", with its Run-in-background switch - without it the
 * phone force-stops Willbolt as soon as the screen has been off a while, and nothing is blocked until it is opened again).
 * It runs while the protection is on (Policy.selfProtected): its switch (one tap on Home), or while a lock holds it - its
 * own padlock, or a lock made with the protection chosen; then it can't be switched off until that lock opens. Never by
 * itself.
 *
 * It only looks at system settings / installer / battery-manager windows, and only reacts when the window mentions
 * Willbolt itself, so the rest of Settings keeps working. The one exception to closing the battery manager: while the
 * phone is stopping Willbolt anyway ([Oem.stoppingUs]) it stays reachable, so that can be fixed. It cannot be perfect on
 * every phone - the Activity tab has a "guard diagnostics" switch that logs what it sees so it can be tuned.
 *
 * Full edition only: Google Play does not allow an app to stop people from disabling or uninstalling it through the
 * Accessibility API, so the Play edition has an empty version of this object.
 */
object TamperGuard {
    // The phone makers' own battery / app-launch managers, where "run in background" and "auto-start" are switched.
    // Only Honor's (Phone Manager, with "App launch") has been looked at on a real phone; the others are the usual names.
    // (Declared first: the next list is built from it when the object is created.)
    private val makerManagers = setOf(
        "com.samsung.android.lool", "com.samsung.android.sm", "com.coloros.safecenter", "com.oppo.safe", "com.oneplus.security",
        "com.iqoo.secure", "com.vivo.permissionmanager",
    )

    private val sensitiveExact = setOf(
        "com.android.settings",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller", "com.miui.packageinstaller",
        "com.hihonor.packageinstaller", "com.huawei.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.hihonor.systemmanager", "com.huawei.systemmanager",
        "com.hihonor.android.settings", "com.huawei.android.settings",
    ) + makerManagers

    fun isSensitive(pkg: String): Boolean =
        pkg in sensitiveExact ||
            pkg.endsWith(".packageinstaller") ||
            pkg.contains("permissioncontroller") ||
            pkg.endsWith(".settings") ||
            isMakerManager(pkg)

    /** Is this the phone maker's battery / app-launch manager (Honor's and Huawei's Phone Manager, Xiaomi's Security, ...)? */
    internal fun isMakerManager(pkg: String): Boolean =
        pkg.contains("systemmanager") || pkg.contains("securitycenter") || pkg.contains("safecenter") || pkg in makerManagers

    fun applies(cfg: Config, now: Long): Boolean = Policy.selfProtected(cfg, now)

    class Verdict(val block: Boolean, val why: String)

    private val OK = Verdict(false, "")

    // Words on the destructive buttons of App-info / device-admin / uninstall screens, in common languages. Taken from
    // Android's own strings where it matters (Slovenian: the uninstall dialog says "odmestiti", the Force-stop button
    // "Prisilno ust.") - words alone miss a language, so the class-based signs below come first.
    private val risky = listOf(
        "uninstall", "force stop", "force-stop", "deactivate", "clear data", "clear storage",
        "deinstallieren", "beenden erzwingen", "stoppen erzwingen", "deaktivieren", "daten löschen", "speicher löschen",
        "désinstaller", "forcer l'arrêt", "désactiver", "effacer les données", "vider le stockage",
        "desinstalar", "forzar detención", "forzar parada", "desactivar", "borrar datos", "borrar almacenamiento",
        "forçar parada", "desativar", "limpar dados", "apagar dados",
        "disinstalla", "forza arresto", "disattiva", "cancella dati",
        "odstrani", "odmesti", "prisilno ust", "prisilna ustavitev", "onemogoči", "deaktiviraj", "deaktivirati",
        "počisti podatke", "izbriši podatke", "izbrisati podatke", "izbriši shrambo",
        "deinstaliraj", "prisilno zaustavi", "onemogući", "obriši podatke",
        "удалить", "остановить", "отключить", "очистить данные", "стереть данные",
        "odinstaluj", "wymuś zatrzymanie", "wyłącz aplikację", "wyczyść dane",
        "kaldır", "zorla durdur", "devre dışı bırak", "verileri temizle",
        "deïnstalleren", "geforceerd stoppen", "uitschakelen", "gegevens wissen",
        "odinstalovat", "vynutit zastavení", "deaktivovat", "vymazat data",
        "卸载", "强行停止", "停用", "清除数据", "アンインストール", "強制停止", "データを消去", "제거", "강제 중지", "데이터 삭제",
    )

    private fun riskyWord(lower: String): String? = risky.firstOrNull { w -> lower.contains(w) }

    /** Does this lower-case text name one of the destructive controls? Also used to keep Willbolt's own texts free of them. */
    internal fun isRisky(lower: String): Boolean = riskyWord(lower) != null

    // The two screens that are only ever about tampering are told apart by their activity's class name, which is the
    // same in every language: the system's uninstall dialog (UninstallerActivity) and the device-admin screen
    // (DeviceAdminAdd). A dialog reports only its own class, so the activity behind it is remembered for a moment.
    private const val SCREEN_MS = 20_000L
    private const val NEVER = Long.MIN_VALUE / 2
    private var uninstallPkg: String? = null
    private var uninstallAt = NEVER
    private var adminPageAt = NEVER

    /**
     * Called for every event from a sensitive package, before anything is skipped, so the activity is never missed.
     * [stateChange] is true when the event announces a screen coming to the front: only then does its class say which
     * screen is up (for anything changing inside a screen it is just the class of some view).
     */
    fun note(pkg: String, className: CharSequence?, stateChange: Boolean, now: Long) {
        val c = className ?: return
        if (c.contains("uninstall", ignoreCase = true)) {
            uninstallPkg = pkg
            uninstallAt = now
        } else if (c.contains("DeviceAdminAdd", ignoreCase = true)) {
            adminPageAt = now
        } else if (stateChange && !c.startsWith("android.")) {
            // Another screen came to the front (a dialog says "android.app.AlertDialog" and is not one): the activation
            // screen is gone. Remembering it for its 20 seconds anyway let Willbolt's own app-info page stay open that long.
            adminPageAt = NEVER
        }
    }

    /** A screen of Willbolt's own came to the front, so no Settings screen is: what was remembered about them is stale. */
    fun forget() {
        adminPageAt = NEVER
        uninstallAt = NEVER
    }

    internal fun uninstallDialogUp(pkg: String, now: Long) = pkg == uninstallPkg && now - uninstallAt < SCREEN_MS

    internal fun adminPageUp(now: Long) = now - adminPageAt < SCREEN_MS

    /**
     * What to do with a screen, from what was found on it. [pkg] is the app the screen belongs to (not the one an event came
     * from: after Home the launcher is in front, and its icon says "Willbolt" too). Pure, so it is tested without a phone.
     */
    internal fun decide(
        pkg: String,
        appInfoHeader: Boolean,
        uninstallDialog: Boolean,
        adminPage: Boolean,
        adminActive: () -> Boolean,
        batteryFixOpen: () -> Boolean,
        word: String?,
    ): Verdict {
        if (appInfoHeader) return Verdict(true, "App info page")
        if (uninstallDialog) return Verdict(true, "uninstall dialog")
        // The device-admin screen is also where Willbolt is *activated*, which must stay possible: it is closed only once
        // Willbolt is the active administrator, when all it can do there is deactivate or uninstall it. While it is not,
        // it is left alone whatever else it says (makers add their own buttons - Honor's also offers "Odstrani aplikacijo",
        // whose uninstall dialog is closed on its own). It is known by its class when an event said so, else by its views:
        // the first events of a new screen carry no class.
        if (adminPage) return if (adminActive()) Verdict(true, "device-admin page") else OK
        // The phone maker's battery manager lists Willbolt with the switches that decide whether the phone may stop it (on
        // Honor: App launch > Run in background). Switched off, the phone force-stops Willbolt once the screen has been
        // off a while, and then nothing is blocked. So while a lock holds the protection those screens stay closed - except
        // while the phone is stopping Willbolt anyway: then they are where it gets fixed.
        if (isMakerManager(pkg) && !batteryFixOpen()) return Verdict(true, "battery manager screen")
        if (word != null) return Verdict(true, "uninstall / stop controls: \"$word\"")
        return OK
    }

    fun inspect(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        pkg: String,
        uninstallDialog: Boolean,
        adminScreen: Boolean,
        adminActive: () -> Boolean,
        batteryFixOpen: () -> Boolean,
    ): Verdict {
        val texts = ArrayList<String>()
        val ids = ArrayList<Pair<String, String>>()
        collect(root, texts, ids)
        if (texts.isEmpty()) return OK

        val lower = texts.map { it.lowercase() }
        val mentionsUs = lower.any { t -> labels.any { l -> t.contains(l) } }
        if (!mentionsUs) return OK

        val appInfoHeader = ids.any { (id, text) ->
            id.endsWith("entity_header_title") && labels.any { text.contains(it, ignoreCase = true) }
        }
        val adminPage = adminScreen || ids.any { (id, _) -> id.endsWith(":id/admin_name") || id.endsWith(":id/admin_policies") }
        return decide(
            pkg, appInfoHeader, uninstallDialog, adminPage, adminActive, batteryFixOpen,
            word = lower.firstNotNullOfOrNull { riskyWord(it) },
        )
    }

    /** Looks at the window in front for one of those screens and, if it is one, covers it and goes Home. */
    @Suppress("DEPRECATION")
    fun check(
        service: AccessibilityService,
        overlay: BlockOverlay,
        cfg: Config,
        pkg: String,
        className: String?,
        appLabel: String,
    ) {
        val root = service.rootInActiveWindow ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            // The screen in front, which is not always the one the event came from (after Home, a late event from Settings
            // finds the launcher there - with Willbolt's icon on it).
            val shown = root.packageName?.toString() ?: pkg
            val v = inspect(
                root,
                listOf(appLabel),
                pkg = shown,
                uninstallDialog = uninstallDialogUp(pkg, now),
                adminScreen = adminPageUp(now),
                adminActive = { Health.isAdminActive(service) },
                batteryFixOpen = { Oem.stoppingUs(service, cfg) },
            )
            if (cfg.diagnostics) {
                EventLog.add("GUARD", "$pkg $className -> ${if (v.block) "BLOCK (" + v.why + ")" else "ok"}", dedupeMs = 2_000)
            }
            if (v.block) {
                overlay.show(
                    "Uninstall protection is on",
                    "Willbolt's app info, uninstall and battery-manager screens stay closed while a lock holds it. Unlock that lock in Willbolt first.",
                )
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                EventLog.add("TAMPER", "Blocked: ${v.why} ($pkg)", dedupeMs = 3_000)
            }
        } finally {
            root.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun collect(root: AccessibilityNodeInfo, texts: MutableList<String>, ids: MutableList<Pair<String, String>>) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 400) {
            val n = queue.removeFirst()
            count++
            val text = n.text?.toString()
            if (!text.isNullOrBlank()) texts.add(text)
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
            n.viewIdResourceName?.let { ids.add(it to (text ?: "")) }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            if (n !== root) n.recycle()
        }
        queue.forEach { if (it !== root) it.recycle() }
    }
}
