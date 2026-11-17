package app.willbolt.ui

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.IntentCompat
import app.willbolt.Edition
import app.willbolt.R
import app.willbolt.core.Health
import app.willbolt.core.SelfRepair

/** Opens the system screens (or does the job directly) behind each "Fix" button. */
object Fixes {
    const val PACKAGE = "app.willbolt"

    private fun Intent.newTask() = apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    fun turnOnAccessibility(ctx: Context) {
        // Only the full edition can do this itself (it needs a permission granted over USB); otherwise Android's list opens.
        if (SelfRepair.enableAccessibility(ctx)) {
            Toast.makeText(ctx, "Accessibility service switched on", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            ctx,
            "Find Willbolt in the list and switch it on. If it is greyed out: Settings > Apps > Willbolt > menu (three dots) > Allow restricted settings.",
            Toast.LENGTH_LONG,
        ).show()
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).newTask())
    }

    // Not newTask(): Android's activation screen closes itself at once when it is started as a new task (Settings logs
    // "Cannot start ADD_DEVICE_ADMIN as a new task"), which showed as a black flash and nothing else. Start it from an Activity.
    fun adminIntent(ctx: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Health.adminComponent(ctx))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, ctx.getString(R.string.admin_description))

    fun batteryIntent(ctx: Context): Intent = Edition.batteryIntent(ctx).newTask()

    fun unusedRestrictionsIntent(ctx: Context): Intent =
        IntentCompat.createManageUnusedAppRestrictionsIntent(ctx, ctx.packageName).newTask()

    fun removeAdmin(ctx: Context) {
        try {
            ctx.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(Health.adminComponent(ctx))
        } catch (_: Throwable) {
        }
    }

    fun uninstallIntent(ctx: Context): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:${ctx.packageName}")).newTask()

    fun copy(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
    }
}
