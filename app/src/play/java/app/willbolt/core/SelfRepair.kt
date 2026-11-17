package app.willbolt.core

import android.content.Context
import app.willbolt.data.Config

/**
 * The Play edition does not change system settings or switch its own accessibility service on: the person does that in
 * Android's settings. The full edition's version (`src/full`) can, with a permission granted over USB.
 */
@Suppress("UNUSED_PARAMETER")
object SelfRepair {
    fun available(ctx: Context) = false

    fun enableAccessibility(ctx: Context) = false

    fun heal(ctx: Context, cfg: Config, now: Long) {}

    fun settle(ctx: Context) {}
}
