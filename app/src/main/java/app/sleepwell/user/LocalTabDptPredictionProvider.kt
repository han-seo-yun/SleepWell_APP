package app.sleepwell.user

import android.content.Context
import android.os.Looper
import app.sleepwell.tabdpt.TabDptMobileRuntime
import org.json.JSONObject
import java.security.MessageDigest
import java.util.LinkedHashMap

internal const val LOCAL_TABDPT_MODEL_VERSION =
    "tabdpt-v1.3-mobile-ctx1024-e2-think64-v2-20261108"

/**
 * Offline Android runtime for the validated lightweight TabDPT bundle.
 *
 * Model loading is intentionally deferred until the first prediction. Callers must execute
 * [predict] away from the main thread because the first load and every inference are expensive
 * on the target A52s device.
 */
internal class LocalTabDptPredictionProvider(
    context: Context
) : TabDptPredictionProvider, PredictionProviderMetadata {
    override val backend: PredictionBackend = PredictionBackend.LOCAL_TABDPT
    private val applicationContext = context.applicationContext
    private val cache = object : LinkedHashMap<String, TabDptPrediction>(4, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, TabDptPrediction>?
        ): Boolean = size > 4
    }

    init {
        validateFeatureOrder(PortableFusionModel.HYBRID_FEATURE_ORDER)
    }

    @Synchronized
    override fun predict(requestId: String, snapshots: JSONObject): TabDptResult {
        return try {
            require(requestId.isNotBlank() && requestId.length <= 128) {
                "request_id가 올바르지 않습니다."
            }
            val cmi = rawFeatures(snapshots, "cmi")
            val mesa = rawFeatures(snapshots, "mesa")
            val key = featureKey(cmi, mesa)
            cache[key]?.let { return TabDptResult.Success(it) }

            require(Looper.myLooper() != Looper.getMainLooper()) {
                "TabDPT 추론은 백그라운드 스레드에서 실행해야 합니다."
            }
            val loaded = SharedRuntime.get(applicationContext)
            require(loaded.manifestSha256 == EXPECTED_MANIFEST_SHA256) {
                "TabDPT manifest 해시가 검증된 모델과 다릅니다."
            }
            val output = synchronized(loaded) { loaded.predictAll(cmi, mesa) }
            require(output.size == 4 && output.all(Double::isFinite)) {
                "TabDPT 출력 형식이 올바르지 않습니다."
            }
            val prediction = TabDptPrediction(
                output[0], output[1], output[2], output[3],
                LOCAL_TABDPT_MODEL_VERSION,
                JSONObject()
                    .put("runtime", "onnxruntime_android_1.22.0")
                    .put("execution", "on_device_offline")
                    .put("feature_contract", TABDPT_FEATURE_CONTRACT)
                    .put("context_size", 1024)
                    .put("ensembles", 2)
                    .put("thinking_rows", 64)
                    .put("manifest_sha256", loaded.manifestSha256)
                    .put("network_required", false),
                PredictionBackend.LOCAL_TABDPT
            )
            cache[key] = prediction
            TabDptResult.Success(prediction)
        } catch (failure: Exception) {
            TabDptResult.Unavailable(
                "local_tabdpt_failed",
                failure.message ?: failure::class.java.simpleName,
                PredictionBackend.LOCAL_TABDPT
            )
        }
    }

    private fun rawFeatures(snapshots: JSONObject, cohort: String): DoubleArray {
        val snapshot = snapshots.getJSONObject(cohort)
        require(snapshot.length() == TABDPT_FEATURE_ORDER.size) {
            "$cohort TabDPT 입력은 정확히 ${TABDPT_FEATURE_ORDER.size}개여야 합니다."
        }
        return DoubleArray(TABDPT_FEATURE_ORDER.size) { index ->
            val name = TABDPT_FEATURE_ORDER[index]
            require(snapshot.has(name)) { "$cohort TabDPT 입력에 $name 항목이 없습니다." }
            val value = snapshot.opt(name)
            require(value == JSONObject.NULL || value is Number) {
                "$cohort TabDPT의 $name 값 형식이 잘못됐습니다."
            }
            if (value is Number) {
                value.toDouble().also {
                    require(it.isFinite()) { "$cohort TabDPT의 $name 값이 유한하지 않습니다." }
                }
            } else {
                Double.NaN
            }
        }
    }

    private fun featureKey(cmi: DoubleArray, mesa: DoubleArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(LOCAL_TABDPT_MODEL_VERSION.toByteArray(Charsets.UTF_8))
        listOf(cmi, mesa).forEach { values ->
            values.forEach { value ->
                val bits = java.lang.Double.doubleToLongBits(value)
                for (shift in 56 downTo 0 step 8) digest.update((bits ushr shift).toByte())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal companion object {
        private const val EXPECTED_MANIFEST_SHA256 =
            "a9f899025307c75c70f45dbfd6404bd27489287bf211a2cdc698d33b6472b324"
        internal val TABDPT_FEATURE_ORDER = listOf(
            "population_bed", "population_wake", "habit_bed", "habit_wake",
            "recent_bed", "recent_wake", "previous_bed", "previous_wake",
            "recent_duration", "bed_variability", "wake_variability", "history_count_7",
            "history_age_days", "personal_weight", "weekend", "next_day_sin",
            "next_day_cos", "next_day_weekend", "median14_bed", "median14_wake",
            "ew_bed", "ew_wake", "weekday_bed", "weekday_wake", "weekday_count",
            "bed_mad", "wake_mad", "duration_mad", "previous_duration", "bed_trend",
            "wake_trend", "gap_days_mean", "recent_count14", "current_s", "current_c",
            "sleep_threshold", "wake_threshold", "physical_bed", "physical_wake"
        )

        internal fun validateFeatureOrder(actual: List<String>) {
            require(actual == TABDPT_FEATURE_ORDER) {
                "TabDPT feature order drift: expected the frozen hybrid39 contract"
            }
        }
    }

    private object SharedRuntime {
        @Volatile private var instance: TabDptMobileRuntime? = null

        fun get(context: Context): TabDptMobileRuntime =
            instance ?: synchronized(this) {
                instance ?: TabDptMobileRuntime(context).also { instance = it }
            }
    }
}
