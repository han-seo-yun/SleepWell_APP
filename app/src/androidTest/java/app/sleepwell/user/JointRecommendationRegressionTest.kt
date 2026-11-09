package app.sleepwell.user

import android.test.InstrumentationTestCase
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.Duration

@Suppress("DEPRECATION")
class JointRecommendationRegressionTest : InstrumentationTestCase() {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = ZonedDateTime.of(2026, 11, 9, 20, 0, 0, 0, zone)
    private fun plan(phase: Double = 180.0, duration: Int = 480,
                     deadline: ZonedDateTime? = null, at: ZonedDateTime = now) =
        MeasurementRecommendationPolicy.plan(at, now.plusDays(1).withHour(7).withMinute(30),
            duration, 15.0, phase, true, true, LocalTime.of(23, 30), LocalTime.of(7, 30),
            emptyList(), null, recommendBoth = true, latestWake = deadline)

    fun testLaterRhythmMovesBothTimesWithoutFixingWakeToProfile() {
        val early = plan(150.0)
        val late = plan(270.0)
        assertTrue(early.feasible && late.feasible)
        assertTrue(late.bed.isAfter(early.bed))
        assertTrue(late.wake.isAfter(early.wake))
        assertFalse(early.wake.toLocalTime() == LocalTime.of(7, 30) &&
            late.wake.toLocalTime() == LocalTime.of(7, 30))
        for (p in listOf(early, late)) {
            assertTrue(p.jointRecommendation)
            assertTrue(p.sleepOpportunityMinutes >= 495)
            assertTrue(p.bed.isAfter(now) || p.bed == now)
        }
    }

    fun testDesiredDurationChangesOpportunityAndPreservesOrdering() {
        val short = plan(duration = 420)
        val long = plan(duration = 540)
        assertTrue(short.feasible && long.feasible)
        assertTrue(long.sleepOpportunityMinutes > short.sleepOpportunityMinutes)
        assertTrue(long.wake.isAfter(long.bed))
    }

    fun testExplicitScheduleIsDeadlineNotForcedWake() {
        val free = plan()
        val loose = plan(deadline = now.plusDays(1).withHour(11))
        assertEquals(free.bed, loose.bed)
        assertEquals(free.wake, loose.wake)
        val deadline = now.plusDays(1).withHour(6)
        val limited = plan(deadline = deadline)
        assertFalse(limited.wake.isAfter(deadline))
        assertTrue(limited.feasible)
    }

    fun testLateNightReportsShortfallRatherThanInventingEightHours() {
        val at = now.plusDays(1).withHour(5)
        val deadline = at.withHour(7)
        val p = plan(deadline = deadline, at = at)
        assertFalse(p.feasible)
        assertEquals(at, p.bed)
        assertEquals(120, p.sleepOpportunityMinutes)
    }

    fun testProductionEngineOptInChangesScheduleWithoutChangingPersonalizationOrForecast() {
        val profile = UserProfile(ageBand = "", sex = "any", activityMinutes = 60, createdAt = "2026-10-01T00:00:00Z", usualBed = "23:30", usualWake = "07:30", desiredSleepMinutes = 480)
        val engine = UserModelEngine(instrumentation.targetContext)
        val fixed = engine.compute(profile, emptyList(), now.toInstant())
        val joint = engine.compute(profile, emptyList(), now.toInstant(), recommendBoth = true)
        assertTrue(joint.outcomes.getBoolean("joint_recommendation"))
        assertEquals("joint_sleep_window_v2", joint.outcomes.getString("recommendation_policy"))
        assertEquals(fixed.outcomes.getJSONObject("personalization").toString(),
            joint.outcomes.getJSONObject("personalization").toString())
        assertTrue(joint.outcomes.getJSONObject("recommendation_feasibility").isNull("constrained_wake"))
        val research = engine.compute(profile, emptyList(), now.toInstant(), historicalEvaluation = true, recommendBoth = true)
        assertFalse(research.outcomes.getBoolean("joint_recommendation"))
    }
}
