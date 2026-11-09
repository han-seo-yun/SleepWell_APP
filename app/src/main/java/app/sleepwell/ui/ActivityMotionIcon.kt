package app.sleepwell.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** A theme-coloured pictogram; activity duration is illustrative, not a measured gait. */
@Composable
internal fun ActivityMotionIcon(fraction: Float) {
    val amount by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(160), label = "activity pose")
    val cycle = rememberInfiniteTransition(label = "activity motion")
    val phase by cycle.animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "stride")
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(48.dp).semantics {
        contentDescription = when {
            fraction <= 0.02f -> "서 있는 사람"
            fraction < 0.7f -> "걷는 사람"
            else -> "달리는 사람"
        }
    }) {
        val a = amount
        val stride = sin(phase) * a
        val bounce = kotlin.math.abs(sin(phase)) * a * 0.04f
        fun point(x: Float, y: Float) = Offset(size.width * x, size.height * (y - bounce))
        val hip = point(0.46f, 0.56f)
        val shoulder = point(0.46f + a * 0.13f, 0.32f)
        val stroke = 2.7.dp.toPx()
        fun limb(from: Offset, to: Offset) = drawLine(color, from, to, stroke, StrokeCap.Round)
        drawCircle(color, 4.dp.toPx(), point(0.46f + a * 0.15f, 0.19f))
        limb(shoulder, hip)
        for (side in listOf(-1f, 1f)) {
            val knee = point(0.46f + side * (0.045f + stride * 0.19f), 0.73f - a * 0.07f)
            val foot = point(0.46f + side * (0.065f + stride * 0.30f), 0.91f - a * 0.09f)
            limb(hip, knee); limb(knee, foot)
            val elbow = point(0.46f + a * 0.08f - side * (0.09f + stride * 0.19f), 0.48f)
            val hand = point(0.46f + a * 0.18f - side * (0.12f + stride * 0.21f), 0.62f - a * 0.25f)
            limb(shoulder, elbow); limb(elbow, hand)
        }
    }
}
