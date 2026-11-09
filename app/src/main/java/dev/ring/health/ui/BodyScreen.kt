package dev.ring.health.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Heart and Stress under one tab; each screen keeps its own title, DayPicker and loading. */
@Composable
fun BodyScreen(backend: Boolean?) {
    var seg by rememberSaveable { mutableIntStateOf(0) }
    val header = @Composable { Segmented(listOf("Heart", "Stress"), seg) { seg = it } }
    if (seg == 0) HeartScreen(backend, header) else StressScreen(backend, header)
}
