package app.sleepwell.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import java.time.temporal.ChronoUnit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import app.sleepwell.analysis.SleepChartData
import app.sleepwell.analysis.SleepHistoryNight
import app.sleepwell.analysis.SleepTrendPoint
import java.time.ZoneId
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

private enum class TrendMode { DURATION, CLOCK }
private val trendDay = DateTimeFormatter.ofPattern("M/d", Locale.KOREAN)

@Composable
fun SleepTrendCard(
    nights: List<SleepHistoryNight>,
    zone: ZoneId,
    nowMs: Long = System.currentTimeMillis()
) {
    var days by rememberSaveable { mutableIntStateOf(7) }
    var modeName by rememberSaveable { mutableStateOf(TrendMode.DURATION.name) }
    val mode = TrendMode.valueOf(modeName)
    val context = LocalContext.current
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    var customStart by rememberSaveable { mutableStateOf<String?>(null) }
    var customEnd by rememberSaveable { mutableStateOf<String?>(null) }
    val periodEnd = customEnd?.let(LocalDate::parse)?.coerceAtMost(today) ?: today
    val periodStart = customStart?.let(LocalDate::parse)?.coerceAtMost(periodEnd)
        ?: periodEnd.minusDays((days - 1).toLong())
    val periodDays = ChronoUnit.DAYS.between(periodStart, periodEnd).toInt() + 1
    val points = remember(nights, zone, periodStart, periodEnd) {
        SleepChartData.trendBetween(nights, zone, periodStart, periodEnd)
    }
    fun pickDate(start: Boolean) {
        val initial = if (start) periodStart else periodEnd
        android.app.DatePickerDialog(context, { _, year, month, day ->
            val picked = LocalDate.of(year, month + 1, day)
            if (start) {
                customStart = picked.toString()
                customEnd = maxOf(periodEnd, picked).toString()
            } else {
                customEnd = picked.toString()
                customStart = minOf(periodStart, picked).toString()
            }
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            datePicker.maxDate = today.atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        }.show()
    }
    Card(
        Modifier.fillMaxWidth(), RoundedCornerShape(22.dp),
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("수면 추세", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(true) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("시작일\n$periodStart")
                }
                OutlinedButton(onClick = { pickDate(false) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("종료일\n$periodEnd")
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(7, 30).forEach { option ->
                    FilterChip(
                        selected = customStart == null && days == option, onClick = { days = option; customStart = null; customEnd = null },
                        label = { Text("${option}일") }, modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
                FilterChip(
                    selected = mode == TrendMode.DURATION, onClick = { modeName = TrendMode.DURATION.name },
                    label = { Text("수면 구간") }, modifier = Modifier.heightIn(min = 48.dp)
                )
                FilterChip(
                    selected = mode == TrendMode.CLOCK, onClick = { modeName = TrendMode.CLOCK.name },
                    label = { Text("취침·기상") }, modifier = Modifier.heightIn(min = 48.dp)
                )
            }
            if (points.isEmpty()) {
                Text(
                    "이 기간에 표시할 기록이 없어요. 기록이 없는 날짜는 0시간으로 그리지 않습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                TrendCanvas(points, periodDays, mode, periodEnd)
                val average = points.map { it.durationMinutes }.average().toInt()
                Text(
                    "${periodDays}일 중 ${points.size}회 기록 · 평균 구간 ${durationText(average)}",
                    style = MaterialTheme.typography.bodyMedium
                )

            }
        }
    }
}

@Composable
private fun TrendCanvas(points: List<SleepTrendPoint>, days: Int, mode: TrendMode, periodEnd: LocalDate) {
    var selected by remember(points, mode) { mutableIntStateOf(points.lastIndex) }
    var widthPx by remember { mutableIntStateOf(1) }
    val line = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val maxObservedDuration = (points.maxOfOrNull { it.durationMinutes } ?: 1).coerceAtLeast(1)
    val maxDuration = (ceil(maxObservedDuration / 180.0).toInt() * 180).coerceAtLeast(540)
    val minAxis = Math.floorDiv((points.minOfOrNull { minOf(it.bedtimeAxisMinute, it.wakeAxisMinute) } ?: 600) - 30, 180) * 180
    val maxAxis = (ceil(((points.maxOfOrNull { maxOf(it.bedtimeAxisMinute, it.wakeAxisMinute) } ?: 2100) + 30) / 180.0).toInt()) * 180
    val density = LocalDensity.current
    val leftPaddingPx = with(density) { 68.dp.toPx() }
    val rightPaddingPx = with(density) { if (mode == TrendMode.CLOCK) 30.sp.toPx() + 8.dp.toPx() else 8.dp.toPx() }
    val axisTextSizePx = with(density) { 10.sp.toPx() }
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val periodStart = periodEnd.minusDays((days.coerceAtLeast(1) - 1).toLong())
    fun pointX(point: SleepTrendPoint): Float {
        val plotWidth = (widthPx - leftPaddingPx - rightPaddingPx).coerceAtLeast(1f)
        return leftPaddingPx + plotWidth * SleepChartData.axisFraction(point.day, periodEnd, days)
    }
    fun choose(x: Float) {
        selected = points.indices.minByOrNull { abs(pointX(points[it]) - x) } ?: -1
    }
    val description = points.joinToString(". ") {
        "${it.day.format(trendDay)} ${durationText(it.durationMinutes)}, 취침 ${axisTime(it.bedtimeAxisMinute)}, 기상 ${axisTime(it.wakeAxisMinute)}"
    }
    Canvas(
        Modifier.fillMaxWidth().height(190.dp).onSizeChanged { widthPx = it.width }.pointerInput(points, mode) {
            awaitEachGesture {
                var change = awaitFirstDown(requireUnconsumed = false)
                choose(change.position.x)
                while (change.pressed) {
                    change = awaitPointerEvent().changes.firstOrNull() ?: break
                    choose(change.position.x)
                }
            }
        }.semantics { contentDescription = "${if (mode == TrendMode.DURATION) "수면 구간" else "취침과 기상"} 추세. $description" }
    ) {
        val left = leftPaddingPx
        val right = size.width - rightPaddingPx
        val top = 12.dp.toPx()
        val bottom = size.height - 24.dp.toPx()
        val plotW = (right - left).coerceAtLeast(1f)
        val plotH = (bottom - top).coerceAtLeast(1f)
        val axisPaint = android.graphics.Paint().apply {
            color = axisTextColor.toArgb()
            textSize = axisTextSizePx
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        val ticks = if (mode == TrendMode.DURATION) (0..3).map { maxDuration * it / 3 }
            else (minAxis..maxAxis step 180).toList()
        ticks.forEach { value ->
            val fraction = if (mode == TrendMode.DURATION) value / maxDuration.toFloat()
                else (value - minAxis) / (maxAxis - minAxis).coerceAtLeast(1).toFloat()
            val y = bottom - plotH * fraction
            val midnight = mode == TrendMode.CLOCK && Math.floorMod(value + 720, 1440) == 0
            drawLine(if (midnight) axisTextColor else line, Offset(left, y), Offset(right, y),
                if (midnight) 1.5.dp.toPx() else 1.dp.toPx())
            val label = if (mode == TrendMode.DURATION) durationAxisLabel(value)
                else SleepChartData.clockAxisLabel(value)
            drawContext.canvas.nativeCanvas.drawText(label, left - 6.dp.toPx(), y + axisTextSizePx * 0.35f, axisPaint)
        }
        fun x(point: SleepTrendPoint): Float = left + plotW * SleepChartData.axisFraction(point.day, periodEnd, days)
        if (mode == TrendMode.DURATION) {
            val barWidth = (plotW / days * 0.58f).coerceIn(3.dp.toPx(), 18.dp.toPx())
            points.forEachIndexed { i, point ->
                val h = plotH * point.durationMinutes / maxDuration.toFloat()
                drawRoundRect(
                    if (i == selected) secondary else primary,
                    Offset(x(point) - barWidth / 2, bottom - h), Size(barWidth, h), CornerRadius(4.dp.toPx())
                )
            }
        } else {
            val span = (maxAxis - minAxis).coerceAtLeast(1)
            fun y(value: Int) = bottom - plotH * (value - minAxis) / span.toFloat()
            fun drawSeries(value: (SleepTrendPoint) -> Int, color: androidx.compose.ui.graphics.Color, dashed: Boolean) {
                val path = Path()
                var previous: SleepTrendPoint? = null
                points.forEach { point ->
                    val px = x(point); val py = y(value(point))
                    if (previous == null || java.time.temporal.ChronoUnit.DAYS.between(previous!!.day, point.day) > 1 ||
                        !SleepChartData.connectsClockValues(value(previous!!), value(point))) path.moveTo(px, py)
                    else path.lineTo(px, py)
                    drawCircle(color, 4.dp.toPx(), Offset(px, py))
                    previous = point
                }
                drawPath(
                    path, color, style = Stroke(
                        2.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
                    )
                )
            }
            drawSeries({ it.bedtimeAxisMinute }, primary, false)
            drawSeries({ it.wakeAxisMinute }, secondary, true)
            // Direct labels tie the series names to the last real values, not to empty dates.
            val last = points.last()
            val labelPaint = android.graphics.Paint(axisPaint).apply {
                textAlign = android.graphics.Paint.Align.LEFT
                textSize = 12.sp.toPx()
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val sleepY = y(last.bedtimeAxisMinute)
            val wakeY = y(last.wakeAxisMinute)
            val gap = 16.sp.toPx()
            val labelSleepY = if (abs(sleepY - wakeY) < gap) minOf(bottom, sleepY + gap / 2) else sleepY
            val labelWakeY = if (abs(sleepY - wakeY) < gap) maxOf(top, wakeY - gap / 2) else wakeY
            fun label(text: String, valueY: Float, labelY: Float, color: androidx.compose.ui.graphics.Color) {
                drawLine(color.copy(alpha = 0.5f), Offset(x(last), valueY), Offset(right + 3.dp.toPx(), labelY), 1.dp.toPx())
                labelPaint.color = color.toArgb()
                drawContext.canvas.nativeCanvas.drawText(text, right + 5.dp.toPx(), labelY + labelPaint.textSize * 0.35f, labelPaint)
            }
            label("수면", sleepY, labelSleepY, primary)
            label("기상", wakeY, labelWakeY, secondary)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(periodStart.format(trendDay), style = MaterialTheme.typography.labelSmall)
        Text(if (mode == TrendMode.DURATION) "단위: 분" else "세로축: 시각", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(periodEnd.format(trendDay), style = MaterialTheme.typography.labelSmall)
    }
    points.getOrNull(selected)?.let { point ->
        Text(
            if (mode == TrendMode.DURATION) "${point.day.format(trendDay)} · ${durationText(point.durationMinutes)}"
            else "${point.day.format(trendDay)} · ${axisTime(point.bedtimeAxisMinute)} 취침 · ${axisTime(point.wakeAxisMinute)} 기상",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun axisTime(axisMinute: Int): String {
    val minute = ((axisMinute + 720) % 1440 + 1440) % 1440
    return "%02d:%02d".format(minute / 60, minute % 60)
}

private fun durationAxisLabel(minutes: Int): String = when {
    minutes <= 0 -> "0h"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h${minutes % 60}"
}

