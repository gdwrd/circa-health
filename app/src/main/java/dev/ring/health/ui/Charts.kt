package dev.ring.health.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import androidx.compose.animation.core.tween
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.component.shadow
import com.patrykandpatrick.vico.compose.common.component.shapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.compose.common.shader.verticalGradient
import com.patrykandpatrick.vico.compose.common.shape.dashedShape
import com.patrykandpatrick.vico.compose.common.shape.markerCorneredShape
import com.patrykandpatrick.vico.compose.common.shape.rounded
import com.patrykandpatrick.vico.core.cartesian.CartesianChart
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.shader.ShaderProvider
import com.patrykandpatrick.vico.core.common.shape.CorneredShape

/** Shared Vico pieces: scrub marker, axes, 2.5 dp lines with a faded area, and the host every chart uses. */

/**
 * Press-and-drag marker: hairline guideline, a card label with a tick pointing down at it, a dot on each opaque line.
 * [format] gets the chart x under the finger and returns e.g. "72 bpm · 3:15 PM". Ticks the haptics when x changes.
 */
@Composable
fun rememberScrubMarker(color: Color, format: (Double) -> CharSequence): CartesianMarker {
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current
    val label = rememberTextComponent(
        color = c.label, textSize = 13.sp, lineCount = 1,
        padding = insets(8.dp, 4.dp), margins = insets(4.dp),
        background = rememberShapeComponent(
            fill(c.card), markerCorneredShape(CorneredShape.rounded(8.dp)),
            strokeFill = fill(c.cardStroke), strokeThickness = 1.dp,
            shadow = shadow(radius = 6.dp, y = 1.dp, color = c.secondaryLabel.copy(alpha = 0.3f)),
        ),
    )
    val hairline = with(LocalDensity.current) { 1.toDp() }
    val guideline = rememberLineComponent(fill(c.secondaryLabel), hairline)
    val dot = shapeComponent(fill(color), CorneredShape.Pill, strokeFill = fill(c.card), strokeThickness = 2.dp)
    val none = shapeComponent(fill(Color.Transparent))
    return remember(label, guideline, dot, format) {
        object : DefaultCartesianMarker(
            label = label,
            valueFormatter = { _, targets -> format(targets.first().x) },
            labelPosition = LabelPosition.Top,
            // Translucent series (baseline bands) get no dot.
            indicator = { argb -> if (Color(argb).alpha < 0.5f) none else dot },
            indicatorSizeDp = 10f,
            guideline = guideline,
        ) {
            private var lastX = Double.NaN
            private var lastTick = 0L

            override fun drawOverLayers(context: CartesianDrawingContext, targets: List<CartesianMarker.Target>) {
                super.drawOverLayers(context, targets)
                val x = targets.firstOrNull()?.x ?: return
                val now = System.nanoTime()
                // 5-min HR slots pass under the finger faster than a tick feels distinct; cap at ~25 per second.
                if (x != lastX && now - lastTick > 40_000_000) {
                    haptic.tick()
                    lastTick = now
                }
                lastX = x
            }
        }
    }
}

@Composable
fun startAxis(step: Double, formatter: CartesianValueFormatter = CartesianValueFormatter.decimal()) = VerticalAxis.rememberStart(
    itemPlacer = VerticalAxis.ItemPlacer.step({ step }),
    guideline = rememberLineComponent(fill = fill(AppTheme.colors.separator), thickness = 0.5.dp),
    label = rememberAxisLabelComponent(color = AppTheme.colors.secondaryLabel),
    valueFormatter = formatter,
    line = null, tick = null,
)

@Composable
fun bottomAxis(formatter: CartesianValueFormatter, spacing: Int = 1, offset: Int = 0) = HorizontalAxis.rememberBottom(
    label = rememberAxisLabelComponent(color = AppTheme.colors.secondaryLabel),
    guideline = null, line = null, tick = null,
    valueFormatter = formatter,
    itemPlacer = HorizontalAxis.ItemPlacer.aligned({ spacing }, { offset }),
)

/** 2.5 dp cubic line in [color]; [area] adds a 20 % → 0 % vertical fade under it. */
@Composable
fun chartLine(color: Color, area: Boolean = false) = LineCartesianLayer.rememberLine(
    fill = LineCartesianLayer.LineFill.single(fill(color)),
    stroke = LineCartesianLayer.LineStroke.Continuous(thicknessDp = 2.5f),
    areaFill = if (area) LineCartesianLayer.AreaFill.single(
        fill(ShaderProvider.verticalGradient(arrayOf(color.copy(alpha = 0.2f), color.copy(alpha = 0f)))),
    ) else null,
    pointConnector = LineCartesianLayer.PointConnector.cubic(),
)

/** Dashed reference line (period average, sleep goal) at [y]. */
@Composable
fun dashedLine(y: Double, color: Color) = HorizontalLine(
    y = { y },
    line = rememberLineComponent(fill(color.copy(alpha = 0.7f)), 1.dp, dashedShape(dashLength = 4.dp, gapLength = 3.dp)),
)

/** Fits all content, no scroll (so a drag scrubs), no diff animation under reduce-motion. */
@Composable
fun ScrubChartHost(chart: CartesianChart, producer: CartesianChartModelProducer, height: Dp) {
    val reduce = LocalReduceMotion.current
    CartesianChartHost(
        chart, producer, Modifier.fillMaxWidth().height(height).padding(AppSpace.l),
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = remember { Zoom.Content }),
        animationSpec = if (reduce) null else tween(500), // Vico's default diff spec
        animateIn = !reduce,
    )
}
