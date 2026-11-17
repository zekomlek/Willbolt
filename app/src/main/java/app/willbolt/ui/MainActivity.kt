package app.willbolt.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.willbolt.core.Policy
import app.willbolt.data.ConfigStore
import app.willbolt.data.EventLog
import app.willbolt.data.UsageStore
import app.willbolt.service.GuardService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ConfigStore.init(applicationContext)
        EventLog.init(applicationContext)
        UsageStore.init(applicationContext)
        Fmt.init(this)
        GuardService.start(this)
        setContent { WillboltTheme { Root() } }
    }
}

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", LineIcons.Home),
    Blocks("Blocks", LineIcons.Wall),
    Filter("Filter", LineIcons.Eye),
    Activity("Activity", LineIcons.Pulse),
}

/** Full-screen pages that open over the tabs. */
sealed interface Page {
    data object Settings : Page

    /** A block's page, with everything typed into it so far. */
    class Editor(val draft: BlockDraft) : Page

    /** The privacy policy or the open-source licences. Back goes to Settings when it was opened from there. */
    class Legal(val doc: LegalDoc, val fromSettings: Boolean = true) : Page
}

/** Where the app is: the tab, a page on top, the lock sheets, and a block page left without saving. */
@Stable
class Nav {
    var tab by mutableStateOf(Tab.Home)
    var page by mutableStateOf<Page?>(null)
    var lockTarget by mutableStateOf<Policy.Target?>(null)
    var locksOpen by mutableStateOf(false)

    /** Left with changes that were not saved; the "Changes not saved" popup offers it back for a few seconds. */
    var unsaved by mutableStateOf<BlockDraft?>(null)

    fun openLock(t: Policy.Target) {
        locksOpen = false
        lockTarget = t
    }

    /** Opens a block's page (null = a new block). */
    fun openEditor(ruleId: String?) {
        unsaved = null
        page = Page.Editor(BlockDraft(ConfigStore.value.rules.firstOrNull { it.id == ruleId }))
    }

    fun openSettings() {
        unsaved = null
        page = Page.Settings
    }

    fun openLegal(doc: LegalDoc, fromSettings: Boolean = true) {
        page = Page.Legal(doc, fromSettings)
    }

    /** Back from a page. A block page with changes is kept for the popup instead of being thrown away. */
    fun back() {
        val p = page
        if (p is Page.Editor && p.draft.changed()) unsaved = p.draft
        // The privacy policy and the licences mostly open from Settings (the policy also from Home): back goes where they came from.
        page = if (p is Page.Legal && p.fromSettings) Page.Settings else null
    }

    /** Back to a block page left without saving, as it was. */
    fun reopen(d: BlockDraft) {
        unsaved = null
        page = Page.Editor(d)
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav is not provided") }

@Composable
private fun Root() {
    val nav = remember { Nav() }
    // The page that is showing, kept (not as state) so it can still be drawn while it slides away.
    val last = remember { arrayOfNulls<Page>(1) }
    nav.page?.let { last[0] = it }
    val shown = last[0]

    CompositionLocalProvider(LocalNav provides nav) {
        BackHandler(enabled = nav.page == null && nav.tab != Tab.Home) { nav.tab = Tab.Home }
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = nav.tab == t,
                                onClick = { nav.tab = t },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (nav.tab) {
                        Tab.Home -> HomeScreen()
                        Tab.Blocks -> BlocksScreen()
                        Tab.Filter -> FilterScreen()
                        Tab.Activity -> ActivityScreen()
                    }
                }
            }
            UnsavedPopup(
                draft = nav.unsaved.takeIf { nav.page == null },
                onReopen = { nav.reopen(it) },
                onGone = { nav.unsaved = null },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp),
            )
            AnimatedVisibility(
                visible = nav.page != null,
                enter = slideInHorizontally { it / 3 } + fadeIn(),
                exit = slideOutHorizontally { it / 3 } + fadeOut(),
            ) {
                BackHandler { nav.back() }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (val p = shown) {
                        Page.Settings -> SettingsScreen(onBack = { nav.back() })
                        is Page.Editor -> BlockEditor(p.draft)
                        is Page.Legal -> LegalScreen(p.doc, onBack = { nav.back() })
                        null -> {}
                    }
                }
            }
        }
        nav.lockTarget?.let { t -> LockSheet(t, onDismiss = { nav.lockTarget = null }) }
        if (nav.locksOpen) LocksSheet(onPick = { nav.openLock(it) }, onDismiss = { nav.locksOpen = false })
    }
}

/**
 * "Changes not saved" for five seconds after leaving a block's page without saving. Tapping it goes back to the page
 * as it was; a line along the bottom runs out with the time.
 */
@Composable
private fun UnsavedPopup(draft: BlockDraft?, onReopen: (BlockDraft) -> Unit, onGone: () -> Unit, modifier: Modifier) {
    // Still drawn while it slides away.
    val last = remember { arrayOfNulls<BlockDraft>(1) }
    draft?.let { last[0] = it }
    AnimatedVisibility(
        visible = draft != null,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
        modifier = modifier,
    ) {
        val d = last[0] ?: return@AnimatedVisibility
        val left = remember(d) { Animatable(1f) }
        LaunchedEffect(d) {
            left.animateTo(0f, tween(5_000, easing = LinearEasing))
            onGone()
        }
        Surface(
            onClick = { onReopen(d) },
            shape = MaterialTheme.shapes.large,
            color = Palette.Deep,
            contentColor = Palette.Cream,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column {
                Row(Modifier.padding(start = 16.dp, end = 18.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(LineIcons.Info, contentDescription = null, tint = Palette.Aqua, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Changes not saved", style = MaterialTheme.typography.titleSmall)
                        Text(
                            d.title(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.Cream.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Go back", style = MaterialTheme.typography.labelLarge, color = Palette.Aqua)
                }
                Box(Modifier.fillMaxWidth(left.value).height(3.dp).background(Palette.Aqua))
            }
        }
    }
}
