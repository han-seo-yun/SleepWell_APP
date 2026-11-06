package app.sleepwell.ui

import app.sleepwell.analysis.SavedResult
import app.sleepwell.analysis.SleepHistoryNight
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Pure, read-only projection shared by the history calendar and regression tests. */
data class HistoryCalendarDay(
    val date: LocalDate,
    val sleepRecords: List<SleepHistoryNight>,
    val savedResults: List<SavedResult>
) {
    val hasSleep: Boolean get() = sleepRecords.isNotEmpty()
    val hasSavedResult: Boolean get() = savedResults.isNotEmpty()
}

object HistoryCalendarProjection {
    fun resultDate(result: SavedResult, zone: ZoneId): LocalDate? {
        // New rows retain an absolute basis instant and can follow the device zone just like live history.
        // dayDate remains the compatibility key for received/legacy rows without time provenance.
        if (result.sourceZoneId != null && result.basisMs > 0L)
            return Instant.ofEpochMilli(result.basisMs).atZone(zone).toLocalDate()
        return result.dayDate.takeIf { it.isNotBlank() }
            ?.let { text -> runCatching { LocalDate.parse(text) }.getOrNull() }
            ?: result.basisMs.takeIf { it > 0L }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    }

    fun sleepDate(night: SleepHistoryNight, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(night.wakeMs).atZone(zone).toLocalDate()

    fun days(
        nights: List<SleepHistoryNight>,
        results: List<SavedResult>,
        zone: ZoneId
    ): Map<LocalDate, HistoryCalendarDay> {
        val realResults = results.filterNot { it.source.isSyntheticSource() }
        val dates = (nights.map { sleepDate(it, zone) } + realResults.mapNotNull { resultDate(it, zone) }).toSortedSet()
        return dates.associateWith { date ->
            HistoryCalendarDay(
                date,
                nights.filter { sleepDate(it, zone) == date }.sortedBy { it.bedtimeMs },
                realResults.filter { resultDate(it, zone) == date }.sortedBy { it.savedAtMs }
            )
        }
    }

    fun monthCells(month: YearMonth): List<LocalDate?> {
        val leadingSundayCells = month.atDay(1).dayOfWeek.value % 7
        val values = MutableList<LocalDate?>(leadingSundayCells) { null }
        values += (1..month.lengthOfMonth()).map(month::atDay)
        while (values.size % 7 != 0) values += null
        return values
    }
}

internal fun String.isSyntheticSource(): Boolean {
    val normalized = lowercase()
    return normalized.contains("test") || normalized.contains("demo") || normalized.contains("합성")
}
