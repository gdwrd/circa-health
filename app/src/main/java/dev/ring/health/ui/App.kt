package dev.ring.health.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private enum class TabItem(val route: String, val label: String, val icon: ImageVector) {
    Today("today", "Today", Icons.Rounded.Today),
    Sleep("sleep", "Sleep", Icons.Rounded.Bedtime),
    Heart("heart", "Heart", Icons.Rounded.Favorite),
    Stress("stress", "Stress", Icons.Rounded.Spa),
    Trends("trends", "Trends", Icons.AutoMirrored.Rounded.ShowChart),
    Settings("settings", "Settings", Icons.Rounded.Settings),
}

@Composable
fun App(hasPermissions: () -> Boolean, requestPermissions: () -> Unit, link: Pair<String, String?>?, onLinkHandled: () -> Unit) {
    AppTheme {
        val ctx = LocalContext.current
        val nav = rememberNavController()
        val route = nav.currentBackStackEntryAsState().value?.destination?.route
        var backend by remember { mutableStateOf(Api.cached(ctx)) }
        // Re-detect on every tab change so a URL/token edited in Settings takes effect.
        LaunchedEffect(route) { backend = Api.cached(ctx) ?: Api.hasBackend(ctx) }
        fun go(t: TabItem) = nav.navigate(t.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        // Today consumes a journal link once its sheet opens (it may still be waiting for the backend check).
        LaunchedEffect(link) {
            when (link?.first) {
                "trends" -> { go(TabItem.Trends); onLinkHandled() }
                "journal" -> { go(TabItem.Today); if (link.second == null) onLinkHandled() }
                null -> {}
                else -> onLinkHandled()
            }
        }
        val journalDate = link?.takeIf { it.first == "journal" }?.second

        Scaffold(
            containerColor = AppTheme.colors.background,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                TabBar(route) { go(it) }
            },
        ) { inner ->
            NavHost(nav, startDestination = TabItem.Today.route, modifier = Modifier.padding(inner)) {
                composable(TabItem.Today.route) { TodayScreen(backend, journalDate, onLinkHandled) }
                composable(TabItem.Sleep.route) { SleepScreen(backend) }
                composable(TabItem.Heart.route) { HeartScreen(backend) }
                composable(TabItem.Stress.route) { StressScreen(backend) }
                composable(TabItem.Trends.route) { TrendsScreen(backend) }
                composable(TabItem.Settings.route) { SettingsScreen(hasPermissions, requestPermissions) }
            }
        }
    }
}

@Composable
private fun TabBar(current: String?, onSelect: (TabItem) -> Unit) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().background(c.tabBar)) {
        Hairline()
        Row(Modifier.navigationBarsPadding().height(56.dp).selectableGroup()) {
            TabItem.entries.forEach { t ->
                val selected = current == t.route
                val color = if (selected) c.tint else c.secondaryLabel
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected, role = Role.Tab, onClick = { onSelect(t) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
                    Text(t.label, style = AppType.tab, color = color)
                }
            }
        }
    }
}

/** Shell for tabs whose content lands in later items: a large title and one card. */
@Composable
internal fun PlaceholderTab(title: String, backend: Boolean?) {
    LargeTitleScaffold(title) {
        if (backend != null) item {
            InsetCard {
                Column(Modifier.padding(AppSpace.l)) {
                    Text(if (backend) "Coming soon" else "Scores need the backend", style = AppType.headline)
                    Text(
                        if (backend) "This screen is being built." else "Set the server URL and token in Settings.",
                        style = AppType.subhead, color = AppTheme.colors.secondaryLabel,
                        modifier = Modifier.padding(top = AppSpace.xs),
                    )
                }
            }
        }
    }
}
