package com.nsheremet.circa.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Heart, Stress and Mind under one tab titled "Body"; each screen keeps its own loading. */
@Composable
fun BodyScreen(backend: Boolean?) {
    var seg by rememberSaveable { mutableIntStateOf(0) }
    val header = @Composable { Segmented(listOf("Heart", "Stress", "Mind"), seg) { seg = it } }
    when (seg) {
        0 -> HeartScreen(backend, header)
        1 -> StressScreen(backend, header)
        else -> MindScreen(backend, header)
    }
}
