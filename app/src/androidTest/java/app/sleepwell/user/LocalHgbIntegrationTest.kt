package app.sleepwell.user

import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime

@Suppress("DEPRECATION")
class LocalHgbIntegrationTest : InstrumentationTestCase() {
    private val seoul = ZoneId.of("Asia/Seoul")

    fun testGoldenHybrid39RowsProduceExactFourOutputParity() {
        val provider = localProvider()
        val cmi = fixtures("cmi")
        val mesa = fixtures("mesa")
        assertEquals(12, cmi.length())
        assertEquals(12, mesa.length())

        repeat(12) { index ->
            val cmiFixture = cmi.getJSONObject(index)
            val mesaFixture = mesa.getJSONObject(index)
            val result = provider.predict(
                "golden-$index",
                JSONObject()
                    .put("cmi", snapshot(cmiFixture.getJSONArray("features")))
                    .put("mesa", snapshot(mesaFixture.getJSONArray("features")))
            ) as TabDptResult.Success
            val expectedCmi = cmiFixture.getJSONArray("expected_constrained")
            val expectedMesa = mesaFixture.getJSONArray("expected_constrained")

            assertEquals(expectedCmi.getDouble(0), result.prediction.cmiOnsetOffset, 0.0)
            assertEquals(expectedCmi.getDouble(1), result.prediction.cmiWakeOffset, 0.0)
            assertEquals(expectedMesa.getDouble(0), result.prediction.mesaOnsetOffset, 0.0)
            assertEquals(expectedMesa.getDouble(1), result.prediction.mesaWakeOffset, 0.0)
            assertEquals(PredictionBackend.LOCAL_HGB, result.prediction.backend)
        }
    }

    fun testFrozenFeatureLengthAndOrderGuardsRejectDrift() {
        try {
            LocalHgbPredictionProvider.validateFeatureOrder(
                LocalHgbPredictionProvider.HGB_FEATURE_ORDER.reversed()
            )
            fail("Expected feature-order drift to fail")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        val cmi = snapshot(fixtures("cmi").getJSONObject(0).getJSONArray("features"))
            .put("unexpected_feature", 1.0)
        val mesa = snapshot(fixtures("mesa").getJSONObject(0).getJSONArray("features"))
        val result = localProvider().predict("wrong-length", JSONObject().put("cmi", cmi).put("mesa", mesa))
        assertTrue(result is TabDptResult.Unavailable)
        result as TabDptResult.Unavailable
        assertEquals("local_hgb_failed", result.code)
        assertEquals(PredictionBackend.LOCAL_HGB, result.backend)
    }

    fun testEngineUsesCorrectCohortsAndRecordsLocalOfflineSource() {
        val delegate = localProvider()
        var providerPrediction: TabDptPrediction? = null
        val provider = object : TabDptPredictionProvider, PredictionProviderMetadata {
            override val backend = PredictionBackend.LOCAL_HGB
            override fun predict(requestId: String, snapshots: JSONObject): TabDptResult =
                delegate.predict(requestId, snapshots).also {
                    providerPrediction = (it as TabDptResult.Success).prediction
                }
        }
        val output = UserModelEngine(instrumentation.targetContext, provider)
            .compute(profile(), emptyList(), at(2026, 11, 3, 20, 0).toInstant())
        val prediction = checkNotNull(providerPrediction)
        val research = output.outcomes.getJSONObject("research_predictions")
        val cmi = research.getJSONObject("hgb_hybrid_cmi")
        val mesa = research.getJSONObject("hgb_hybrid_mesa")
        val runtime = output.outcomes.getJSONObject("prediction_runtime")
        val measurement = output.outcomes.getJSONObject("measurement_recommendation")

        assertEquals(prediction.cmiOnsetOffset, cmi.getDouble("onset_offset_min"), 0.0)
        assertEquals(prediction.cmiWakeOffset, cmi.getDouble("wake_offset_min"), 0.0)
        assertEquals(prediction.mesaOnsetOffset, mesa.getDouble("onset_offset_min"), 0.0)
        assertEquals(prediction.mesaWakeOffset, mesa.getDouble("wake_offset_min"), 0.0)
        assertFalse("cohort outputs should be independently produced",
            prediction.cmiOnsetOffset == prediction.mesaOnsetOffset &&
                prediction.cmiWakeOffset == prediction.mesaWakeOffset)
        assertEquals("local_hgb_hybrid39", cmi.getString("source"))
        assertEquals("local_hgb_hybrid39", runtime.getString("source"))
        assertEquals("hgb_hybrid", runtime.getString("prediction_key"))
        assertFalse(runtime.getBoolean("network_required"))
        assertTrue(output.outcomes.isNull("tabdpt_runtime"))
        assertTrue(output.forecast.note.contains("기기 내 HGB"))
        assertEquals(runtime.getString("status") == "available_applied",
            measurement.getBoolean("prediction_preference_applied"))
        assertFalse(measurement.isNull("prediction_preferred_bed"))
        assertFalse(measurement.getBoolean("tabdpt_hybrid_preference_applied"))
        assertTrue(measurement.isNull("tabdpt_hybrid_preferred_bed"))
        assertTrue(measurement.isNull("tabdpt_preferred_bed"))
    }

    fun testModelLoadFailureReturnsLocalUnavailableAndPreservesPhysicalPlan() {
        val failed = LocalHgbPredictionProvider { path ->
            if (path.startsWith("models/mobile_hgb_")) throw IllegalStateException("missing test asset")
            instrumentation.targetContext.assets.open(path).use { it.readBytes() }
        }
        val fixture = JSONObject()
            .put("cmi", snapshot(fixtures("cmi").getJSONObject(0).getJSONArray("features")))
            .put("mesa", snapshot(fixtures("mesa").getJSONObject(0).getJSONArray("features")))
        val failedResult = failed.predict("load-failure", fixture)
        assertTrue(failedResult is TabDptResult.Unavailable)
        failedResult as TabDptResult.Unavailable
        assertEquals("local_hgb_failed", failedResult.code)
        assertEquals(PredictionBackend.LOCAL_HGB, failedResult.backend)

        val now = at(2026, 11, 3, 20, 0).toInstant()
        val offline = UserModelEngine(instrumentation.targetContext, failed)
            .compute(profile(), emptyList(), now)
        val physical = UserModelEngine(instrumentation.targetContext)
            .compute(profile(), emptyList(), now)
        val runtime = offline.outcomes.getJSONObject("prediction_runtime")
        assertEquals("unavailable_physical_only", runtime.getString("status"))
        assertEquals("local_hgb_hybrid39", runtime.getString("source"))
        assertNull(offline.forecast.ensembleBedOffset)
        assertEquals(physical.recommendation.bedtime, offline.recommendation.bedtime)
        assertEquals(physical.recommendation.wakeTime, offline.recommendation.wakeTime)
    }

    fun testHistoricalEvaluationSuppressesLocalModelAndPreservesRecommendation() {
        var calls = 0
        val provider = object : TabDptPredictionProvider, PredictionProviderMetadata {
            override val backend = PredictionBackend.LOCAL_HGB
            override fun predict(requestId: String, snapshots: JSONObject): TabDptResult {
                calls++
                return localProvider().predict(requestId, snapshots)
            }
        }
        val now = at(2026, 11, 3, 20, 0).toInstant()
        val historical = UserModelEngine(instrumentation.targetContext, provider)
            .compute(profile(), emptyList(), now, historicalEvaluation = true)
        val baseline = UserModelEngine(instrumentation.targetContext)
            .compute(profile(), emptyList(), now, historicalEvaluation = true)
        val runtime = historical.outcomes.getJSONObject("prediction_runtime")

        assertEquals(0, calls)
        assertEquals("not_requested_historical_reconstruction", runtime.getString("status"))
        assertEquals("local_hgb_hybrid39", runtime.getString("source"))
        assertEquals(baseline.recommendation.bedtime, historical.recommendation.bedtime)
        assertEquals(baseline.recommendation.wakeTime, historical.recommendation.wakeTime)
    }

    private fun localProvider() = LocalHgbPredictionProvider { path ->
        instrumentation.targetContext.assets.open(path).use { it.readBytes() }
    }

    private fun fixtures(cohort: String): JSONArray = JSONArray(
        instrumentation.context.assets.open("fixtures/mobile_hgb_${cohort}_golden.json")
            .bufferedReader().use { it.readText() }
    )

    private fun snapshot(features: JSONArray): JSONObject = JSONObject().also { values ->
        LocalHgbPredictionProvider.HGB_FEATURE_ORDER.forEachIndexed { index, name ->
            values.put(name, if (features.isNull(index)) JSONObject.NULL else features.getDouble(index))
        }
    }

    private fun profile() = UserProfile(
        ageBand = "18-29", sex = "female", activityMinutes = 60,
        usualBed = "23:00", usualWake = "07:00", desiredSleepMinutes = 480,
        createdAt = "2026-10-01T00:00:00Z", sleepLatencyMinutes = 15.0,
        averageSteps = 6_000.0, exerciseMinutes = 30.0
    )

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, seoul)
}
