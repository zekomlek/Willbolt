package app.willbolt.ui

import androidx.compose.runtime.Composable
import app.willbolt.data.Config

// The Play edition has no donation link and never asks: Play allows tips only through its own billing (see
// PLAY-RELEASE.md). The full edition's versions are in src/full.

/** Nothing under Password on Home in the Play edition. */
@Composable
fun SupportRow() {
}

/** The Play edition never asks. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SupportPrompt(cfg: Config, now: Long, allowed: Boolean) {
}
