package app.sleepwell.analysis

import android.test.InstrumentationTestCase
import app.sleepwell.health.DisplaySettings
import app.sleepwell.health.DisplayZone
import app.sleepwell.health.HealthResult
import app.sleepwell.health.SleepAnalysisPipeline
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

@Suppress("DEPRECATION")
class LiveDateRegressionTest : InstrumentationTestCase() {
    private val seoul = ZoneId.of("Asia/Seoul")
    private val csvStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private lateinit var previousZone: DisplayZone

    override fun setUp() {
        super.setUp()
        previousZone = DisplaySettings.zone
        DisplaySettings.zone = DisplayZone.KST
    }

    override fun tearDown() {
        DisplaySettings.zone = previousZone
        super.tearDown()
    }

    fun testDemoRebaseUsesLatestCompletedNightAndPreservesIntervals() {
        val sleep = """
            start_time,end_time,duration_min,stage
            2026-10-22 22:00:00,2026-10-22 23:00:00,60,LIGHT
            2026-10-23 23:00:00,2026-10-24 00:00:00,60,REM
        """.trimIndent()
        val survey = """
            participant,date
            Test,2026-10-22
            Test,2026-10-23
        """.trimIndent()
        val earlyMorning = ZonedDateTime.of(2026, 11, 3, 0, 30, 0, 0, seoul).toInstant()

        val (movedSleep, movedSurvey) = DemoDates.rebase(sleep, survey, earlyMorning)
        val before = sleepRows(sleep)
        val after = sleepRows(movedSleep)

        assertEquals("2026-11-02", after.last().second.atZone(seoul).toLocalDate().toString())
        assertTrue(after.last().second.isBefore(earlyMorning))
        assertEquals("Test,2026-11-01", movedSurvey.lineSequence().last())
        assertEquals(
            Duration.between(before[0].first, before[0].second),
            Duration.between(after[0].first, after[0].second)
        )
        assertEquals(
            Duration.between(before[0].second, before[1].first),
            Duration.between(after[0].second, after[1].first)
        )
    }

    fun testClockOnlyTargetUsesCurrentNowEvenWithHistoricalRecord() {
        val now = ZonedDateTime.of(2026, 11, 3, 20, 0, 0, 0, seoul)
        val historicalRecordEnd = ZonedDateTime.of(2026, 10, 24, 7, 0, 0, 0, seoul)

        val parsed = DirectInputParser.parse(
            DirectInputForm(targetWakeText = "07:30"),
            lastRecordEndMs = historicalRecordEnd.toInstant().toEpochMilli(),
            nowMs = now.toInstant().toEpochMilli()
        )

        assertTrue(parsed.ok)
        assertEquals(
            ZonedDateTime.of(2026, 11, 4, 7, 30, 0, 0, seoul).toInstant().toEpochMilli(),
            parsed.input?.targetWakeMs
        )
    }

    fun testPastExplicitTargetDateIsRejectedAgainstCurrentNow() {
        val now = ZonedDateTime.of(2026, 11, 3, 20, 0, 0, 0, seoul)

        val parsed = DirectInputParser.parse(
            DirectInputForm(targetWakeText = "2026-10-24 07:30"),
            nowMs = now.toInstant().toEpochMilli()
        )

        assertFalse(parsed.ok)
        assertTrue(parsed.errors.any { it.contains("현재 시각 이후") })
    }

    fun testClockOnlyTargetCrossesMidnightOntoNextDate() {
        val now = ZonedDateTime.of(2026, 11, 3, 23, 50, 0, 0, seoul)

        val parsed = DirectInputParser.parse(
            DirectInputForm(targetWakeText = "00:10"),
            nowMs = now.toInstant().toEpochMilli()
        )

        assertTrue(parsed.ok)
        assertEquals(
            ZonedDateTime.of(2026, 11, 4, 0, 10, 0, 0, seoul).toInstant().toEpochMilli(),
            parsed.input?.targetWakeMs
        )
    }


    private fun sleepRows(csv: String): List<Pair<Instant, Instant>> = csv.lineSequence()
        .drop(1)
        .filter { it.isNotBlank() }
        .map { row ->
            val columns = row.split(',')
            LocalDateTime.parse(columns[0], csvStamp).toInstant(ZoneOffset.UTC) to
                LocalDateTime.parse(columns[1], csvStamp).toInstant(ZoneOffset.UTC)
        }
        .toList()
}
