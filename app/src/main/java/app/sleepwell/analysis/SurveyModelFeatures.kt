package app.sleepwell.analysis

import org.json.JSONObject
import java.time.Instant

/** Frozen, nullable survey inputs. No fitted coefficients or synthetic labels. */
object SurveyModelFeatures {
    const val CONTRACT = "sleepwell.survey_features.v1"
    data class Input(
        val participant: String, val night: String, val observedMs: Long?,
        val wakeMs: Long?, val isSleepSupplement: Boolean,
        val caffeineUsed: Boolean?, val caffeineMg: Double?, val caffeineMs: Long?,
        val exerciseMinutes: Double?, val exerciseIntensity: String?,
        val fatigue: Int?, val fatigueScaleMax: Int, val rested: Int?
    )

    fun snapshot(rows: List<Input>, participant: String, night: String, cutoffMs: Long,
                 generatedMs: Long, live: Boolean, synthetic: Boolean,
                 baseFeatures: JSONObject? = null): String {
        require(cutoffMs <= generatedMs)
        val known = rows.filter { it.participant == participant &&
            it.observedMs != null && it.observedMs <= cutoffMs }
        val context = known.filter { !it.isSleepSupplement && it.night == night }
            .maxByOrNull { it.observedMs!! }
        // Require an explicit completed sleep link; never infer morning feedback from date alone.
        val previous = known.filter { it.isSleepSupplement && it.wakeMs != null &&
            it.wakeMs < cutoffMs && it.wakeMs <= it.observedMs!! }
            .maxWithOrNull(compareBy<Input>({ it.wakeMs!! }, { it.observedMs!! }))
        val values = JSONObject()
        val sources = JSONObject()
        fun put(name: String, value: Number?, row: Input?) {
            values.put(name, value ?: JSONObject.NULL)
            sources.put(name, if (value != null && row != null) JSONObject()
                .put("observed_ms", row.observedMs).put("sleep_wake_ms", row.wakeMs ?: JSONObject.NULL)
                else JSONObject.NULL)
        }
        fun valid(value: Double?) = value?.takeIf { it.isFinite() && it >= 0 }
        val caffeineTimeValid = context?.caffeineMs?.let { it <= cutoffMs && it <= context.observedMs!! } ?: true
        val caffeine = if (!caffeineTimeValid) null else if (context?.caffeineUsed == false) 0.0
            else valid(context?.caffeineMg)
        put("caffeine_mg", caffeine, context)
        put("hours_since_caffeine", context?.caffeineMs?.takeIf {
            caffeineTimeValid && context.caffeineUsed != false
        }?.let { (cutoffMs - it) / 3600000.0 }, context)
        put("exercise_minutes", valid(context?.exerciseMinutes), context)
        put("exercise_intensity", listOf("none", "light", "moderate", "vigorous")
            .indexOf(context?.exerciseIntensity).takeIf { it >= 0 }, context)
        fun fatigue(row: Input?, prefix: String) {
            val score = row?.fatigue?.takeIf { row.fatigueScaleMax in listOf(5, 7) && it in 1..row.fatigueScaleMax }
            put(prefix + "_raw", score, row)
            put(prefix + "_scale_max", row?.fatigueScaleMax?.takeIf { score != null }, row)
        }
        fatigue(context, "evening_fatigue")
        fatigue(previous, "previous_sleep_fatigue")
        put("previous_sleep_rested", previous?.rested?.takeIf { it in 1..5 }, previous)
        put("previous_sleep_age_hours", previous?.wakeMs?.let { (cutoffMs - it) / 3600000.0 }, previous)
        // Exercise in a completed-sleep supplement has a different meaning from tonight's context.
        put("previous_sleep_exercise_minutes", valid(previous?.exerciseMinutes), previous)
        return JSONObject().put("contract", CONTRACT).put("participant", participant)
            .put("night", night).put("cutoff_ms", cutoffMs).put("generated_ms", generatedMs)
            .put("origin", if (live) "live" else "historical")
            .put("synthetic", synthetic).put("status", "collecting_untrained")
            .put("base_features", baseFeatures ?: JSONObject.NULL)
            .put("applied_to_prediction", false).put("values", values).put("sources", sources).toString()
    }

    fun input(row: SurveyDay): Input = Input(
        row.participant, row.date,
        row.savedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        row.sleepWakeMs, row.sleepWakeDate != null, row.caffeineUsed, row.caffeineMg,
        row.lastCaffeineInstant()?.toEpochMilli(), row.exerciseMinutes, row.exerciseIntensity,
        row.fatigue, row.fatigueScaleMax, row.rested
    )
}
