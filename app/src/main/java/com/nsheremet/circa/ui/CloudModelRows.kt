package com.nsheremet.circa.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nsheremet.circa.CloudModel
import com.nsheremet.circa.Prefs.cloudKey
import com.nsheremet.circa.Prefs.cloudModel
import com.nsheremet.circa.Prefs.setCloudKey
import com.nsheremet.circa.Prefs.setCloudModel
import com.nsheremet.circa.cloudModels
import com.nsheremet.circa.cloudNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/** Settings › Reports "Written by" choices: `reportProvider` value to label. */
internal val PROVIDERS = listOf("phone" to "This phone", "openrouter" to "OpenRouter", "anthropic" to "Anthropic", "openai" to "OpenAI")

/** Settings › Reports rows for a cloud writer: API key, Model (a searchable list), Test. */
@Composable
internal fun CloudModelRows(provider: String) {
    val ctx = LocalContext.current
    val c = AppTheme.colors
    val scope = rememberCoroutineScope()
    var key by remember(provider) { mutableStateOf(ctx.cloudKey(provider)) }
    var model by remember(provider) { mutableStateOf(ctx.cloudModel(provider)) }
    var picking by remember { mutableStateOf(false) }
    var testing by remember(provider) { mutableStateOf(false) }
    var result by remember(provider) { mutableStateOf<Pair<Boolean, String>?>(null) } // ok to the line shown

    FieldRow("API key", key, "Not set", KeyboardType.Password, password = true) { key = it.trim(); ctx.setCloudKey(provider, key); result = null }
    Hairline(Modifier.padding(start = AppSpace.l))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { picking = true }.padding(horizontal = AppSpace.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Model", style = AppType.body, modifier = Modifier.width(120.dp))
        Text(model.ifEmpty { "Not chosen" }, style = AppType.body, color = c.secondaryLabel, textAlign = TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("  ›", style = AppType.body, color = c.secondaryLabel)
    }
    Hairline(Modifier.padding(start = AppSpace.l))
    ButtonRow(if (testing) "Testing…" else "Test", enabled = !testing && key.isNotEmpty() && model.isNotEmpty()) {
        testing = true; result = null
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            result = try {
                cloudNote(provider, key, model, "Reply with the single word OK.", "Test")
                true to String.format(Locale.ROOT, "Works, %.1f s", (SystemClock.elapsedRealtime() - t0) / 1000.0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                false to (e.message ?: "Failed")
            } finally {
                testing = false
            }
        }
    }
    result?.let { (ok, line) ->
        Text(line, style = AppType.footnote, color = if (ok) c.goodText else c.activity, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = AppSpace.l, end = AppSpace.l, bottom = AppSpace.s))
    }

    if (picking) ModelPicker(provider, key, model, onDismiss = { picking = false }) { id ->
        ctx.setCloudModel(provider, id); model = id; result = null
        picking = false
    }
}

/** The provider's models, loaded when opened, filtered by a search on name and id. */
@Composable
private fun ModelPicker(provider: String, key: String, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = AppTheme.colors
    val needsKey = provider != "openrouter" && key.isEmpty()
    var models by remember { mutableStateOf<List<CloudModel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    if (!needsKey) LaunchedEffect(attempt) {
        error = null
        try {
            models = cloudModels(provider, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            error = e.message ?: "Failed"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text("Model") },
        text = {
            Column {
                val list = models
                when {
                    needsKey -> Text("Add the API key first.", style = AppType.body, color = c.secondaryLabel)
                    error != null -> {
                        Text(error!!, style = AppType.body, color = c.activity, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Retry", style = AppType.body, color = c.tint,
                            modifier = Modifier.fillMaxWidth().clickable { attempt++ }.padding(vertical = AppSpace.m))
                    }
                    list == null -> Text("Loading models…", style = AppType.body, color = c.secondaryLabel)
                    else -> {
                        BasicTextField(
                            query, { query = it },
                            modifier = Modifier.fillMaxWidth().background(c.background, RoundedCornerShape(10.dp)).padding(horizontal = AppSpace.m, vertical = AppSpace.s),
                            singleLine = true,
                            textStyle = AppType.body.copy(color = c.label),
                            cursorBrush = SolidColor(c.tint),
                            decorationBox = { inner ->
                                Box {
                                    if (query.isEmpty()) Text("Search", style = AppType.body, color = c.secondaryLabel)
                                    inner()
                                }
                            },
                        )
                        val shown = list.filter { query.isBlank() || it.name.contains(query.trim(), true) || it.id.contains(query.trim(), true) }
                        LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = AppSpace.s)) {
                            items(shown, key = { it.id }) { m ->
                                val cur = m.id == current
                                Column(Modifier.fillMaxWidth().clickable { onPick(m.id) }.padding(vertical = AppSpace.s)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(m.name, style = AppType.body, color = if (cur) c.tint else c.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        if (m.free) Text("  Free", style = AppType.footnote, color = c.goodText)
                                        if (cur) Text("  ✓", style = AppType.body, color = c.tint)
                                    }
                                    if (m.name != m.id) Text(m.id, style = AppType.footnote, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (shown.isEmpty()) item { Text("No match", style = AppType.body, color = c.secondaryLabel, modifier = Modifier.padding(vertical = AppSpace.m)) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = c.tint) } },
    )
}
