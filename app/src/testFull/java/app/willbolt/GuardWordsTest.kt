package app.willbolt

import app.willbolt.service.TamperGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The uninstall guard closes any Settings screen that has Willbolt's name next to words like uninstall, force stop or
 * deactivate. Android shows some of Willbolt's own strings on screens the guard must leave alone (the accessibility
 * service's page, the "activate device administrator" screen), so those strings must stay free of such words.
 */
class GuardWordsTest {
    private fun fullStrings(): Map<String, String> {
        val xml = File("src/full/res/values/edition.xml").readText()
        return Regex("""<string name="([a-z_]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    @Test
    fun theTextsAndroidShowsOnScreensTheGuardMustLeaveAloneAreFreeOfItsWords() {
        val strings = fullStrings()
        for (name in listOf("accessibility_description", "admin_description")) {
            val text = strings.getValue(name).lowercase()
            assertFalse("$name would make the guard close Willbolt's own screen", TamperGuard.isRisky(text))
        }
    }

    @Test
    fun theGuardStillRecognisesItsWords() {
        assertTrue(TamperGuard.isRisky("prisilno zaustavi"))
        assertTrue(TamperGuard.isRisky("force stop"))
        assertTrue(TamperGuard.isRisky("this app will be uninstalled"))
        assertFalse(TamperGuard.isRisky("willbolt is a device administrator"))
    }

    @Test
    fun theGuardKnowsAndroidsOwnSlovenianWords() {
        // Read from the Honor phone's installer and Settings: the system uninstall dialog said "odmestiti", which the guard
        // once missed, and the Force-stop button is "Prisilno ust.".
        assertTrue(TamperGuard.isRisky("ali želite odmestiti to aplikacijo?"))
        assertTrue(TamperGuard.isRisky("prisilno ust."))
        assertTrue(TamperGuard.isRisky("če želite odstraniti program willbolt, je treba deaktivirati njegovega skrbnika naprave"))
        // ...while the activation screen's own texts stay clear of them.
        assertFalse(TamperGuard.isRisky("ali želite omogočiti skrbnika naprave?"))
        assertFalse(TamperGuard.isRisky("vklopi"))
        assertFalse(TamperGuard.isRisky("če omogočite tega skrbnika, bo za willbolt omogočeno izvajanje teh dejanj:"))
    }

    @Test
    fun theUninstallDialogIsToldByItsActivityWhateverTheLanguage() {
        val installer = "com.google.android.packageinstaller"
        TamperGuard.note(installer, "com.android.packageinstaller.UninstallerActivity", true, 1_000)
        assertTrue(TamperGuard.uninstallDialogUp(installer, 2_000))
        assertFalse(TamperGuard.uninstallDialogUp("com.android.settings", 2_000))
        assertFalse(TamperGuard.uninstallDialogUp(installer, 60_000))
        // The dialog's own events carry the dialog's class and do not forget the activity.
        TamperGuard.note(installer, "android.app.AlertDialog", true, 3_000)
        assertTrue(TamperGuard.uninstallDialogUp(installer, 4_000))
    }

    @Test
    fun theDeviceAdminScreenIsToldByItsClass() {
        TamperGuard.note("com.android.settings", "com.android.settings.DeviceAdminAdd", true, 100_000)
        assertTrue(TamperGuard.adminPageUp(105_000))
        assertFalse(TamperGuard.adminPageUp(200_000))
    }

    @Test
    fun theDeviceAdminScreenIsForgottenAsSoonAsAnotherScreenComesToTheFront() {
        // Seen on the phone with 0.6.2: after the activation screen had been shown, Willbolt's own app-info page (with Force
        // stop) stayed open for the 20 seconds the activation screen was remembered. It must be forgotten when it is left.
        val settings = "com.android.settings"
        TamperGuard.note(settings, "com.android.settings.DeviceAdminAdd", true, 300_000)
        assertTrue(TamperGuard.adminPageUp(301_000))
        // Things changing inside it, and a dialog on top of it, do not say another screen came to the front...
        TamperGuard.note(settings, "android.widget.FrameLayout", false, 301_500)
        TamperGuard.note(settings, "android.app.AlertDialog", true, 301_800)
        assertTrue(TamperGuard.adminPageUp(302_000))
        // ...but another activity does,
        TamperGuard.note(settings, "com.android.settings.applications.InstalledAppDetails", true, 303_000)
        assertFalse(TamperGuard.adminPageUp(303_100))
        // and so does one of Willbolt's own (BACK from the activation screen lands there).
        TamperGuard.note(settings, "com.android.settings.DeviceAdminAdd", true, 400_000)
        assertTrue(TamperGuard.adminPageUp(400_500))
        TamperGuard.forget()
        assertFalse(TamperGuard.adminPageUp(400_600))
    }
}
