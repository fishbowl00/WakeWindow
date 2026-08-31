package com.wakewindow.app.domain.route

import com.wakewindow.app.domain.sun.SolarCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Shortcut departure/return windows for a same-launch outing - see docs/PLANNING.md "Quick
 * plans." Uses real sunrise/sunset when a [SolarCalculator.SunTimes] is available (i.e. a
 * launch location is known), falling back to simple, deterministic clock-time defaults
 * otherwise - a plan must always be produceable even before a launch is picked or if the sun
 * calculation can't resolve (e.g. polar day/night - see [SolarCalculator]). [CUSTOM] is not a
 * computed window at all; it just means "keep using the manual date/time pickers."
 */
enum class QuickPlanKind { MORNING, AFTERNOON, EVENING, FULL_DAY, CUSTOM }

data class QuickPlanWindow(val kind: QuickPlanKind, val departure: Instant, val returnTime: Instant)

object QuickPlanPresets {

    private val DEFAULT_MORNING_START = LocalTime.of(7, 0)
    private val MIDDAY = LocalTime.of(12, 0)
    private val LATE_AFTERNOON = LocalTime.of(17, 0)
    private val EVENING_START = LocalTime.of(16, 0)
    private val DEFAULT_EVENING_END = LocalTime.of(20, 0)
    private val POST_SUNSET_BUFFER_MINUTES = 30L

    /** [kind] must not be [QuickPlanKind.CUSTOM] - there is no computed window for it. */
    fun windowFor(kind: QuickPlanKind, date: LocalDate, zoneId: ZoneId, sunTimes: SolarCalculator.SunTimes?): QuickPlanWindow {
        require(kind != QuickPlanKind.CUSTOM) { "CUSTOM has no computed window - it means 'use the manual pickers'" }

        val morningStart = sunTimes?.sunrise ?: atLocalTime(date, DEFAULT_MORNING_START, zoneId)
        val midday = atLocalTime(date, MIDDAY, zoneId)
        val lateAfternoon = atLocalTime(date, LATE_AFTERNOON, zoneId)
        val eveningStart = atLocalTime(date, EVENING_START, zoneId)
        val eveningEnd = sunTimes?.sunset?.plusSeconds(POST_SUNSET_BUFFER_MINUTES * 60)
            ?: atLocalTime(date, DEFAULT_EVENING_END, zoneId)

        val (departure, returnTime) = when (kind) {
            QuickPlanKind.MORNING -> morningStart to midday
            QuickPlanKind.AFTERNOON -> midday to lateAfternoon
            QuickPlanKind.EVENING -> eveningStart to eveningEnd
            QuickPlanKind.FULL_DAY -> morningStart to lateAfternoon
            QuickPlanKind.CUSTOM -> error("unreachable - guarded by require() above")
        }
        // A computed window must never be zero/negative duration even at extreme sun-time
        // edge cases (e.g. sunrise unresolved and falling back oddly) - guarantee at least an
        // hour rather than handing the caller an invalid plan.
        val safeReturn = if (returnTime.isAfter(departure)) returnTime else departure.plusSeconds(3600)
        return QuickPlanWindow(kind, departure, safeReturn)
    }

    private fun atLocalTime(date: LocalDate, time: LocalTime, zoneId: ZoneId): Instant =
        date.atTime(time).atZone(zoneId).toInstant()
}
