package app.sleepwell.analysis

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class SleepTrendPoint(
    val day: LocalDate,
    val durationMinutes: Int,
    /** Minutes since noon on the day before the displayed wake date. */
    val bedtimeAxisMinute: Int,
    val wakeAxisMinute: Int,
    val sourceLabel: String
)

/** Pure, read-only chart projections. Missing dates are deliberately not synthesized as zeroes. */
object SleepChartData {
    /** Axis origin is previous-day noon; labels refer to the displayed wake date. */
    fun clockAxisLabel(axisMinute: Int): String {
        val minute = Math.floorMod(axisMinute + 720, 1440)
        val offset = Math.floorDiv(axisMinute + 720, 1440) - 1
        val day = when (offset) {
            -1 -> "전날"
            0 -> "당일"
            1 -> "다음날"
            else -> "${offset}일"
        }
        return "$day %02d:%02d".format(java.util.Locale.ROOT, minute / 60, minute % 60)
    }

    /** Do not draw a misleading steep segment across a clock/date wrap. Keep both real points. */
    fun connectsClockValues(previous: Int, current: Int): Boolean =
        kotlin.math.abs(current.toLong() - previous.toLong()) < 720

    /** Position inside a selected day window. Today/period end is 1; missing trailing days stay blank. */
    fun axisFraction(day: LocalDate, periodEnd: LocalDate, days: Int): Float {
        val span = (days.coerceAtLeast(2) - 1).toLong()
        val daysAgo = ChronoUnit.DAYS.between(day, periodEnd)
        return ((span - daysAgo).toFloat() / span).coerceIn(0f, 1f)
    }

    fun trend(
        nights: List<SleepHistoryNight>,
        zone: ZoneId,
        nowMs: Long,
        days: Int
    ): List<SleepTrendPoint> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val firstDay = today.minusDays((days.coerceAtLeast(1) - 1).toLong())
        return trendBetween(nights, zone, firstDay, today)
    }

    fun trendBetween(nights: List<SleepHistoryNight>, zone: ZoneId,
                     firstDay: LocalDate, lastDay: LocalDate): List<SleepTrendPoint> {
        require(!firstDay.isAfter(lastDay)) { "시작일은 종료일 이후일 수 없습니다." }
        return nights.mapNotNull { night ->
            val bed = Instant.ofEpochMilli(night.bedtimeMs).atZone(zone)
            val wake = Instant.ofEpochMilli(night.wakeMs).atZone(zone)
            val wakeDay = wake.toLocalDate()
            if (wakeDay.isBefore(firstDay) || wakeDay.isAfter(lastDay)) return@mapNotNull null
            val axisStart = wakeDay.minusDays(1).atTime(12, 0).atZone(zone)
            SleepTrendPoint(
                day = wakeDay,
                durationMinutes = night.intervalMinutes,
                // Clock trend represents displayed wall time. Zoned elapsed minutes would shift the
                // plotted clock by an hour across DST even though the label still says the same HH:mm.
                bedtimeAxisMinute = ChronoUnit.MINUTES.between(axisStart.toLocalDateTime(), bed.toLocalDateTime()).toInt(),
                wakeAxisMinute = ChronoUnit.MINUTES.between(axisStart.toLocalDateTime(), wake.toLocalDateTime()).toInt(),
                sourceLabel = night.sourceLabel
            )
        }.sortedBy { it.day }
    }
}
