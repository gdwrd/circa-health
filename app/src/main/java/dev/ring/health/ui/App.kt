package dev.ring.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Today
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.haze
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

private enum class TabItem(val route: String, val label: String, val icon: ImageVector, val outline: ImageVector) {
    Today("today", "Today", Icons.Rounded.Today, Icons.Outlined.Today),
    Sleep("sleep", "Sleep", Icons.Rounded.Bedtime, Icons.Outlined.Bedtime),
    Heart("heart", "Heart", Icons.Rounded.Favorite, Icons.Outlined.FavoriteBorder),
    Stress("stress", "Stress", Icons.Rounded.Spa, Icons.Outlined.Spa),
    Trends("trends", "Trends", Icons.AutoMirrored.Rounded.ShowChart, Icons.AutoMirrored.Outlined.ShowChart),
    Settings("settings", "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
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
        val haze = remember { HazeState() }

        Scaffold(
            containerColor = AppTheme.colors.background,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                TabBar(route, Modifier.hazeChild(haze, AppTheme.bar)) { go(it) }
            },
        ) { inner ->
            // Content runs under the tab bar so the bar can blur it; LargeTitleScaffold pads its list end by this.
            CompositionLocalProvider(LocalBottomBar provides inner.calculateBottomPadding()) {
            NavHost(nav, startDestination = TabItem.Today.route, modifier = Modifier.haze(haze)) {
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
}

@Composable
private fun TabBar(current: String?, modifier: Modifier, onSelect: (TabItem) -> Unit) {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val reduce = LocalReduceMotion.current
    Column(modifier.fillMaxWidth()) {
        Hairline()
        Row(Modifier.navigationBarsPadding().height(56.dp).selectableGroup()) {
            TabItem.entries.forEach { t ->
                val selected = current == t.route
                val color = if (selected) c.tint else c.secondaryLabel
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected, role = Role.Tab, onClick = { if (!selected) haptic.tick(); onSelect(t) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val scale by animateFloatAsState(
                        if (selected && !reduce) 1.1f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "tab",
                    )
                    Icon(
                        if (selected) t.icon else t.outline, contentDescription = null, tint = color,
                        modifier = Modifier.size(26.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                    )
                    Text(t.label, style = AppType.tab, color = color)
                }
            }
        }
    }
}

/** Today still calls this; one no-backend empty state everywhere. */
@Composable
internal fun PlaceholderTab(title: String, backend: Boolean?) = NoBackendTab(title, backend)
