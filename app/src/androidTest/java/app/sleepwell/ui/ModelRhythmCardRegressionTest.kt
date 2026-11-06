package app.sleepwell.ui

import android.test.InstrumentationTestCase
import app.sleepwell.analysis.AnalysisDisplay
import app.sleepwell.analysis.ChartPoint

@Suppress("DEPRECATION")
class ModelRhythmCardRegressionTest : InstrumentationTestCase() {
    fun testFutureOnlyProjectionRemainsVisible() {
        val future = listOf(
            ChartPoint(2_000L, 0.7, 0.2, 0.9),
            ChartPoint(3_000L, 0.8, 0.1, 0.9)
        )
        val display = display(history = emptyList(), future = future, start = 1_000L, end = 4_000L)

        val projection = modelChartProjection(display)

        assertNotNull(projection)
        assertEquals(1_000L, projection?.startMs)
        assertEquals(4_000L, projection?.endMs)
        assertEquals(future, projection?.points)
    }

    fun testNearestPointIgnoresPointsOutsideVisibleRange() {
        val outside = ChartPoint(500L, 0.1, 0.2, 0.3)
        val first = ChartPoint(2_000L, 0.4, 0.2, 0.6)
        val second = ChartPoint(3_000L, 0.8, 0.1, 0.9)

        val nearest = nearestModelPoint(listOf(outside, first, second), 2_800L, 1_000L, 4_000L)

        assertEquals(second, nearest)
    }

    fun testProjectionUsesAllThreeRelativeModelSeriesForScale() {
        val display = display(
            history = listOf(ChartPoint(2_000L, -0.4, 0.2, 1.7)),
            future = emptyList(),
            start = 1_000L,
            end = 4_000L
        )

        val projection = modelChartProjection(display)!!

        assertTrue(projection.low < -0.4)
        assertTrue(projection.high > 1.7)
    }

    fun testPointerMappingUsesDensityConvertedPlotBounds() {
        val start = ChartPoint(1_000L, 0.2, 0.1, 0.3)
        val middle = ChartPoint(2_000L, 0.4, 0.2, 0.6)
        val end = ChartPoint(3_000L, 0.6, 0.3, 0.9)
        val projection = modelChartProjection(
            display(listOf(start, middle, end), emptyList(), 1_000L, 3_000L)
        )!!
        val density = 3f
        val widthPx = 360f * density
        val leftPx = 40f * density
        val rightPaddingPx = 4f * density
        val rightPx = widthPx - rightPaddingPx
        val bounds = modelChartHorizontalBounds(widthPx, leftPx, rightPaddingPx)

        assertEquals(leftPx, bounds.first)
        assertEquals(rightPx, bounds.second)
        assertEquals(start, selectedPointAt(leftPx, widthPx, leftPx, rightPaddingPx, projection))
        assertEquals(middle, selectedPointAt((leftPx + rightPx) / 2f, widthPx, leftPx, rightPaddingPx, projection))
        assertEquals(end, selectedPointAt(rightPx, widthPx, leftPx, rightPaddingPx, projection))
    }

    private fun display(
        history: List<ChartPoint>,
        future: List<ChartPoint>,
        start: Long,
        end: Long
    ) = AnalysisDisplay(
        modelLabel = "test",
        isTemporaryModel = true,
        referenceMs = 2_500L,
        readings = emptyList(),
        history = history,
        future = future,
        futureIsTemporary = future.isNotEmpty(),
        sleepBands = emptyList(),
        easiestSleepMs = null,
        recentSleeps = emptyList(),
        recommendationText = "",
        graphStartMs = start,
        graphEndMs = end
    )
}
