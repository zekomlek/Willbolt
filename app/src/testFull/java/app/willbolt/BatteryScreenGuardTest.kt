package app.willbolt

import app.willbolt.service.TamperGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2 November, Honor phone: with the three App launch switches off for Willbolt, the phone force-stopped it as soon as the
 * screen had been off a while (seven times that day, once leaving blocking off for seven hours), and the guard never closed
 * the "Zagon aplikacij" screen where those switches are. Now it does, while a lock holds the uninstall protection.
 */
class BatteryScreenGuardTest {
    private val honor = "com.hihonor.systemmanager"

    private fun decide(
        pkg: String,
        appInfo: Boolean = false,
        uninstall: Boolean = false,
        admin: Boolean = false,
        adminActive: Boolean = false,
        fixOpen: Boolean = false,
        word: String? = null,
    ) = TamperGuard.decide(pkg, appInfo, uninstall, admin, { adminActive }, { fixOpen }, word)

    @Test
    fun honorsAppLaunchScreenIsClosedWhileItListsWillbolt() {
        val v = decide(honor)
        assertTrue(v.block)
        assertEquals("battery manager screen", v.why)
    }

    @Test
    fun theOtherMakersManagersToo() {
        for (p in listOf(
            "com.huawei.systemmanager", "com.miui.securitycenter", "com.coloros.safecenter", "com.oppo.safe",
            "com.samsung.android.lool", "com.iqoo.secure", "com.vivo.permissionmanager", "com.oneplus.security",
        )) {
            assertTrue("$p should be a battery manager", TamperGuard.isMakerManager(p))
            assertTrue("$p should be closed", decide(p).block)
            assertTrue("$p should be watched", TamperGuard.isSensitive(p))
        }
    }

    @Test
    fun itStaysReachableWhileThePhoneIsStoppingWillbolt() {
        // Closing the screen then would only leave Willbolt switched off: it is where the cause is fixed.
        assertFalse(decide(honor, fixOpen = true).block)
        // ...but the buttons that remove or force-stop it are still closed there.
        assertTrue(decide(honor, fixOpen = true, word = "force stop").block)
        assertTrue(decide(honor, fixOpen = true, uninstall = true).block)
    }

    @Test
    fun otherScreensAreLeftAlone() {
        for (p in listOf("com.hihonor.android.launcher", "com.google.android.apps.nexuslauncher", "com.android.settings", "com.instagram.android")) {
            assertFalse("$p is not a battery manager", TamperGuard.isMakerManager(p))
            assertFalse("$p should be left alone", decide(p).block)
        }
        assertFalse(TamperGuard.isSensitive("com.instagram.android"))
    }

    @Test
    fun settingsClosesItsOwnPagesAsBefore() {
        assertTrue(decide("com.android.settings", appInfo = true).block)
        assertTrue(decide("com.android.settings", uninstall = true).block)
        assertTrue(decide("com.android.settings", word = "prisilno ust").block)
        assertTrue(decide("com.android.settings", admin = true, adminActive = true).block)
    }

    @Test
    fun theActivationScreenStaysOpenUntilTheAdministratorIsActive() {
        // Whatever else the activation screen says (makers add buttons of their own), it must stay open to be activated.
        assertFalse(decide("com.android.settings", admin = true, adminActive = false, word = "deactivate").block)
        assertFalse(decide(honor, admin = true, adminActive = false).block)
    }
}
