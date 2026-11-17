package app.willbolt.core

import app.willbolt.Edition
import app.willbolt.data.ConfigStore

/**
 * When the full edition asks, once, whether you would like to support Willbolt (a popup on Home; the "Buy me a coffee" row
 * under Password is there all the time). It waits until Willbolt has been installed for [MIN_DAYS] days AND has blocked
 * something [MIN_BLOCKS] times, so it only asks people it has actually helped. The Play edition never asks and has no link:
 * Play allows tips only through its own billing (see PLAY-RELEASE.md).
 */
object Support {
    const val MIN_DAYS = 3
    const val MIN_BLOCKS = 5
    private const val DAY_MS = 86_400_000L

    /**
     * Whether to ask now: Willbolt was first installed at [installedMs] (0 = unknown, then never), it is [now], it has blocked
     * [blocks] times and it has not [asked] before. Both times are the phone's clock.
     */
    fun due(installedMs: Long, now: Long, blocks: Int, asked: Boolean): Boolean =
        !asked && installedMs > 0L && now - installedMs >= MIN_DAYS * DAY_MS && blocks >= MIN_BLOCKS

    /** The count after one more block. It stops at [MIN_BLOCKS]: nothing needs more, and the settings stop changing. */
    internal fun counted(blocks: Int): Int = if (blocks < MIN_BLOCKS) blocks + 1 else blocks

    /**
     * Willbolt just blocked something the person saw: an app, a page, a picture or a private tab. (Call it only for log
     * entries that were really added - a repeat a few seconds later is the same block.) DNS answers are not counted: apps look
     * names up in the background, so those can happen without anyone noticing.
     */
    fun noteBlock() {
        if (!Edition.FULL || !ConfigStore.ready || ConfigStore.value.blocksSeen >= MIN_BLOCKS) return
        ConfigStore.update { it.copy(blocksSeen = counted(it.blocksSeen)) }
    }
}
