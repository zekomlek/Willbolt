package app.willbolt.core

/**
 * While a part is locked, a number that can only get stricter is first *picked* and then applied with Confirm. Saving
 * every tap, keystroke or step of a drag would raise the floor under your finger, and a stricter value cannot be taken
 * back, so a slider or box that went one step too far could never come back.
 *
 * This is the pick that is still waiting for Confirm: null when nothing is picked, the pick is the saved value anyway,
 * the part is not locked (then picking saves at once), or [clamp] says the pick is not allowed as it is.
 */
fun pendingValue(current: Int, picked: Int?, locked: Boolean, clamp: (Int) -> Int): Int? =
    picked?.takeIf { locked && it != current && clamp(it) == it }
