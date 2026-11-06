package app.sleepwell.ui

import android.test.InstrumentationTestCase
import app.sleepwell.analysis.SavedResult
import app.sleepwell.analysis.SleepHistoryNight
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@Suppress("DEPRECATION")
class HistoryCalendarProjectionTest : InstrumentationTestCase() {
    fun testWakeDateUsesCurrentDeviceZone() {
        val night = night("2026-11-05T23:30:00Z")
        val seoul = HistoryCalendarProjection.sleepDate(night, ZoneId.of("Asia/Seoul"))
        val utc = HistoryCalendarProjection.sleepDate(night, ZoneId.of("UTC"))
        assertEquals(LocalDate.of(2026, 11, 6), seoul)
        assertEquals(LocalDate.of(2026, 11, 5), utc)
    }

    fun testSavedDayDateWinsAndLegacyBasisFallsBackToDisplayZone() {
        val basis = Instant.parse("2026-11-05T23:30:00Z").toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 31),
            HistoryCalendarProjection.resultDate(result(basis, "2026-10-31"), ZoneId.of("Asia/Seoul")))
        assertEquals(LocalDate.of(2026, 11, 6),
            HistoryCalendarProjection.resultDate(result(basis, ""), ZoneId.of("Asia/Seoul")))
    }

    fun testNewRawResultMovesWithCurrentZoneWhileLegacyDayDateStaysFixed() {
        val basis = Instant.parse("2026-11-05T23:30:00Z").toEpochMilli()
        val raw = result(basis, "2026-11-05").copy(sourceZoneId = "UTC")
        assertEquals(LocalDate.of(2026, 11, 5),
            HistoryCalendarProjection.resultDate(raw, ZoneId.of("UTC")))
        assertEquals(LocalDate.of(2026, 11, 6),
            HistoryCalendarProjection.resultDate(raw, ZoneId.of("Asia/Seoul")))

        val legacy = result(basis, "2026-11-05").copy(sourceZoneId = null)
        assertEquals(LocalDate.of(2026, 11, 5),
            HistoryCalendarProjection.resultDate(legacy, ZoneId.of("Asia/Seoul")))
    }

    fun testEmptyDateIsNotInventedAndSyntheticResultIsHidden() {
        val real = result(Instant.parse("2026-11-05T02:00:00Z").toEpochMilli(), "2026-11-05")
        val fake = real.copy(savedAtMs = 2, source = "Demo test")
        val days = HistoryCalendarProjection.days(emptyList(), listOf(real, fake), ZoneId.of("Asia/Seoul"))
        assertEquals(1, days.size)
        assertEquals(1, days[LocalDate.of(2026, 11, 5)]!!.savedResults.size)
        assertFalse(days.containsKey(LocalDate.of(2026, 11, 4)))
    }

    fun testMonthCellsStartsSundayAndPadsWholeWeeks() {
        val cells = HistoryCalendarProjection.monthCells(YearMonth.of(2026, 11))
        assertEquals(LocalDate.of(2026, 11, 1), cells.first())
        assertEquals(0, cells.size % 7)
        assertEquals(LocalDate.of(2026, 11, 30), cells.filterNotNull().last())
    }

    private fun night(wake: String): SleepHistoryNight {
        val end = Instant.parse(wake).toEpochMilli()
        return SleepHistoryNight("2026-11-05", end - 8 * 3_600_000L, end, 480,
            "health_connect", "헬스 커넥트", false, null, null, null, null, null, end)
    }

    private fun result(basis: Long, day: String) = SavedResult(
        savedAtMs = 1, source = "health.csv", basisMs = basis, basisText = "기준",
        modelText = "모델", recText = "권장", habitText = "평소", statusText = "상태",
        surveyText = "", sValue = "0.5", cValue = "0.1", pValue = "0.6", dayDate = day
    )
}
