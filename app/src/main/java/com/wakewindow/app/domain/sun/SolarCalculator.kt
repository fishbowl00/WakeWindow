package com.wakewindow.app.domain.sun

import com.wakewindow.app.domain.model.GeoPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise, sunset, and civil twilight via the classic "Sunrise/Sunset Algorithm" (Almanac for
 * Computers, 1990 - the same public-domain closed-form approximation behind most open-source
 * sun-time implementations). Pure Kotlin, no external astronomy library or network dependency -
 * see docs/PLACE_DISCOVERY.md's sibling doc on planning for why this stays purely local rather
 * than adding a commercial sun-times API for one supplementary field. This is a reasonable
 * approximation (accurate to within roughly a minute for most latitudes, worse near the poles),
 * not a claim of arc-second precision - the same honest framing
 * [com.wakewindow.app.domain.tide.TideTimeline] uses for its own cosine-bell interpolation.
 *
 * Every returned value is an [Instant] (timezone-agnostic) - the caller converts to local
 * display time with its own [java.time.ZoneId], matching docs/ARCHITECTURE.md "Time zone
 * handling." [date] should already be the calendar date as experienced at [location] (i.e.
 * resolved via that location's own zone, not the device's).
 */
object SolarCalculator {

    /** Standard sunrise/sunset zenith angle - 90 degrees plus atmospheric refraction and the
     * sun's apparent radius. */
    private const val ZENITH_OFFICIAL = 90.833
    private const val ZENITH_CIVIL_TWILIGHT = 96.0

    data class SunTimes(
        val date: LocalDate,
        val sunrise: Instant?,
        val sunset: Instant?,
        val civilTwilightBegin: Instant?,
        val civilTwilightEnd: Instant?,
    ) {
        /** True when the sun never rises or never sets on [date] at this latitude (polar
         * day/night, or the extreme-latitude edge case this algorithm can't resolve) -
         * [sunrise]/[sunset] are null in that case, never a guessed value. */
        val isPolarDayOrNight: Boolean get() = sunrise == null || sunset == null
    }

    fun calculate(location: GeoPoint, date: LocalDate): SunTimes {
        // The algorithm divides by cos(latitude); within a hair of the poles that blows up to
        // a meaningless result - honestly report "can't resolve" rather than a garbage instant.
        if (kotlin.math.abs(location.latitude) > 89.9) {
            return SunTimes(date, null, null, null, null)
        }
        return SunTimes(
            date = date,
            sunrise = eventInstant(location, date, ZENITH_OFFICIAL, rising = true),
            sunset = eventInstant(location, date, ZENITH_OFFICIAL, rising = false),
            civilTwilightBegin = eventInstant(location, date, ZENITH_CIVIL_TWILIGHT, rising = true),
            civilTwilightEnd = eventInstant(location, date, ZENITH_CIVIL_TWILIGHT, rising = false),
        )
    }

    private fun eventInstant(location: GeoPoint, date: LocalDate, zenithDeg: Double, rising: Boolean): Instant? {
        val dayOfYear = date.dayOfYear.toDouble()
        val lngHour = location.longitude / 15.0
        val t = if (rising) dayOfYear + ((6.0 - lngHour) / 24.0) else dayOfYear + ((18.0 - lngHour) / 24.0)

        val m = (0.9856 * t) - 3.289
        val l = normalizeDegrees(m + (1.916 * sinDeg(m)) + (0.020 * sinDeg(2 * m)) + 282.634)

        var ra = normalizeDegrees(atanDeg(0.91764 * tanDeg(l)))
        // RA must be in the same quadrant as L, which atan alone doesn't guarantee.
        val lQuadrant = floor(l / 90.0) * 90.0
        val raQuadrant = floor(ra / 90.0) * 90.0
        ra += (lQuadrant - raQuadrant)
        val raHours = ra / 15.0

        val sinDec = 0.39782 * sinDeg(l)
        val cosDec = cos(asin(sinDec))

        val cosH = (cosDeg(zenithDeg) - (sinDec * sinDeg(location.latitude))) / (cosDec * cosDeg(location.latitude))
        if (cosH.isNaN() || cosH > 1.0 || cosH < -1.0) return null // sun never rises / never sets this date at this latitude

        val hHours = (if (rising) 360.0 - acosDeg(cosH) else acosDeg(cosH)) / 15.0

        val localMeanTime = hHours + raHours - (0.06571 * t) - 6.622
        val utHours = normalizeHours(localMeanTime - lngHour)

        val utcMidnight = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        return utcMidnight.plusSeconds((utHours * 3600.0).roundToLong())
    }

    private fun sinDeg(deg: Double) = sin(Math.toRadians(deg))
    private fun cosDeg(deg: Double) = cos(Math.toRadians(deg))
    private fun tanDeg(deg: Double) = tan(Math.toRadians(deg))
    private fun atanDeg(x: Double) = Math.toDegrees(atan(x))
    private fun acosDeg(x: Double) = Math.toDegrees(acos(x))

    private fun normalizeDegrees(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    private fun normalizeHours(hours: Double): Double {
        var h = hours % 24.0
        if (h < 0) h += 24.0
        return h
    }
}
