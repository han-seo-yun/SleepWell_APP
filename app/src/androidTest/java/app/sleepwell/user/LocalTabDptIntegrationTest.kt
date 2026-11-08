package app.sleepwell.user

import android.os.Bundle
import android.test.InstrumentationTestCase
import app.sleepwell.analysis.NextNightModel
import app.sleepwell.health.AnalysisResult
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

@Suppress("DEPRECATION")
class LocalTabDptIntegrationTest : InstrumentationTestCase() {
    private val seoul = ZoneId.of("Asia/Seoul")

    fun testCorrectedThink64BundleMatchesOneFrozenGoldenAndRejectsIncompleteInput() {
        val provider = LocalTabDptPredictionProvider(instrumentation.targetContext)
        val fixture = bundledGoldens().getJSONArray("cases").getJSONObject(0)
        val raw = fixture.getJSONArray("raw39")
        val snapshots = JSONObject()
            .put("cmi", snapshot(raw))
            .put("mesa", snapshot(raw))

        val result = provider.predict("golden-${fixture.getString("id")}", snapshots)
        assertTrue(result is TabDptResult.Success)
        result as TabDptResult.Success
        val cmi = fixture.getJSONObject("cohorts").getJSONObject("cmi")
            .getJSONArray("portable_python_onnx_raw")
        val mesa = fixture.getJSONObject("cohorts").getJSONObject("mesa")
            .getJSONArray("portable_python_onnx_raw")
        val toleranceMinutes = 1.0 / 60.0

        assertEquals(cmi.getDouble(0), result.prediction.cmiOnsetOffset, toleranceMinutes)
        assertEquals(cmi.getDouble(1), result.prediction.cmiWakeOffset, toleranceMinutes)
        assertEquals(mesa.getDouble(0), result.prediction.mesaOnsetOffset, toleranceMinutes)
        assertEquals(mesa.getDouble(1), result.prediction.mesaWakeOffset, toleranceMinutes)
        assertEquals(LOCAL_TABDPT_MODEL_VERSION, result.prediction.modelVersion)
        assertEquals(PredictionBackend.LOCAL_TABDPT, result.prediction.backend)
        assertEquals(1024, result.prediction.provenance.getInt("context_size"))
        assertEquals(2, result.prediction.provenance.getInt("ensembles"))
        assertEquals(
            bundledGoldens().getString("bundle_manifest_sha256"),
            result.prediction.provenance.getString("manifest_sha256")
        )

        val incomplete = provider.predict(
            "incomplete",
            JSONObject().put("cmi", snapshot(raw)).put("mesa", JSONObject())
        )
        assertTrue(incomplete is TabDptResult.Unavailable)
        incomplete as TabDptResult.Unavailable
        assertEquals(PredictionBackend.LOCAL_TABDPT, incomplete.backend)
    }

    fun testProductionInstallUsesLocalTabDptAndKeepsData33Personalization() {
        val oldProfile = NextNightModel.profile
        val oldSurveyDays = NextNightModel.surveyDays
        val oldDiaryRecords = NextNightModel.diaryRecords
        val oldImportedRecords = NextNightModel.importedRecords
        val oldWearableEnabled = NextNightModel.wearablePersonalizationEnabled
        val oldDemoMode = NextNightModel.demoMode
        val now = at(2026, 11, 3, 20, 0)
        try {
            NextNightModel.profile = NextNightModel.ModelProfile(
                ageBand = "", sex = "female", activityMinutes = 60,
                desiredSleepMinutes = 480, usualBed = "23:00", usualWake = "07:00",
                onboardingCompleted = true
            )
            NextNightModel.surveyDays = emptyList()
            NextNightModel.diaryRecords = (1..28).map { daysAgo ->
                val wake = now.withHour(7).minusDays(daysAgo.toLong())
                val bed = wake.minusHours(8).minusMinutes((daysAgo % 4).toLong() * 5)
                SleepRecord(
                    id = "memory-$daysAgo", day = wake.toLocalDate().toString(),
                    bedtime = bed.toInstant().toString(), wakeTime = wake.toInstant().toString(),
                    durationMinutes = Duration.between(bed, wake).toMinutes().toInt(),
                    rested = 4, fatigue = 3, source = "diary",
                    modifiedAt = wake.plusMinutes(1).toInstant().toString(),
                    sleepLatencyMinutes = 15, awakeMinutes = 0
                )
            }
            NextNightModel.importedRecords = emptyList()
            NextNightModel.wearablePersonalizationEnabled = false
            NextNightModel.demoMode = false

            // A stale server setting must not change the production offline provider.
            NextNightModel.install(
                instrumentation.targetContext,
                TabDptServerSettings(enabled = true, endpoint = "not-an-http-endpoint")
            )
            val nowMs = now.toInstant().toEpochMilli()
            val base = AnalysisResult(
                currentS = 0.5, currentC = 0.0, currentPropensity = 0.5,
                sleepRecords = emptyList(), scHistory = emptyList(), futureS = null,
                recommendation = null, modelVersion = "test", calculationTimeMs = nowMs,
                inputPeriodEndMs = nowMs
            )

            val applied = NextNightModel.applyTo(base, null, emptyMap(), nowMs = nowMs)
            val recommendation = checkNotNull(applied.recommendation)
            assertEquals(LOCAL_TABDPT_MODEL_VERSION, recommendation.residualModelVersion)
            assertEquals("local_tabdpt_hybrid39_personalized_two_process", recommendation.modelStatus)
            assertNotNull(recommendation.predictedBedTimeMs)
            assertNotNull(recommendation.predictedWakeTimeMs)
            assertEquals(28, recommendation.historyRecordCount)
            assertNotNull(recommendation.personalizationWeight)
            assertTrue(checkNotNull(recommendation.personalizationWeight) > 0.0)
            assertTrue(applied.modelVersion.contains(LOCAL_TABDPT_MODEL_VERSION))
            assertNull(NextNightModel.lastError)
            val display = app.sleepwell.analysis.AnalysisPresenter.build(
                applied, latestRefMs = nowMs, recommendationReferenceMs = nowMs
            )
            assertNotNull("Local TabDPT plan must be visible without an explicit alarm", display.schedule?.recommended)
            assertTrue(checkNotNull(display.schedule).status.text.contains("경량 TabDPT"))
            assertTrue(display.planBands.any { it.kind == app.sleepwell.analysis.PlanKind.RECOMMENDED })

            val audit = JSONObject()
                .put("model_id", recommendation.residualModelVersion)
                .put("status", recommendation.modelStatus)
                .put("source", "local_tabdpt_hybrid39")
                .put("predicted_bed_ms", recommendation.predictedBedTimeMs)
                .put("predicted_wake_ms", recommendation.predictedWakeTimeMs)
                .put("history_count", recommendation.historyRecordCount)
                .put("personalization_weight", recommendation.personalizationWeight)
            instrumentation.sendStatus(2, Bundle().apply {
                putString("offline_tabdpt_audit", audit.toString())
            })
        } finally {
            NextNightModel.profile = oldProfile
            NextNightModel.surveyDays = oldSurveyDays
            NextNightModel.diaryRecords = oldDiaryRecords
            NextNightModel.importedRecords = oldImportedRecords
            NextNightModel.wearablePersonalizationEnabled = oldWearableEnabled
            NextNightModel.demoMode = oldDemoMode
            NextNightModel.install(instrumentation.targetContext, TabDptServerSettings())
        }
    }

    private fun bundledGoldens(): JSONObject = JSONObject(
        instrumentation.targetContext.assets.open("tabdpt/goldens_validation32.json")
            .bufferedReader().use { it.readText() }
    )

    private fun snapshot(raw: JSONArray): JSONObject = JSONObject().also { values ->
        PortableFusionModel.HYBRID_FEATURE_ORDER.forEachIndexed { index, name ->
            values.put(name, if (raw.isNull(index)) JSONObject.NULL else raw.getDouble(index))
        }
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, seoul)
}
