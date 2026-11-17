package com.nsheremet.circa.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.time.LocalDate

/** Heart, Stress and Mind under one tab titled "Body"; each screen keeps its own loading. */
@Composable
fun BodyScreen(backend: Boolean?, date: LocalDate = LocalDate.now(), onDate: (LocalDate) -> Unit = {}) {
    var seg by rememberSaveable { mutableIntStateOf(0) }
    val header = @Composable { Segmented(listOf("Heart", "Stress", "Mind"), seg) { seg = it } }
    when (seg) {
        0 -> HeartScreen(backend, date, onDate, header)
        1 -> StressScreen(backend, date, onDate, header)
        else -> MindScreen(backend, header)
    }
}
