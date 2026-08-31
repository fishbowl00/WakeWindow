package com.wakewindow.app.domain.model

/**
 * Marine users expect marine units (knots, nautical miles, feet) rather than the generic
 * everyday units a land-weather app defaults to. All domain models store values in these
 * units natively (see [com.wakewindow.app.domain.marine.MarineConditions]) so unit
 * conversion happens once, at the data-mapping boundary, never silently re-derived in
 * multiple places. A metric toggle is architected for but not exposed in this sprint - see
 * docs/ROADMAP.md.
 */
enum class UnitSystem {
    IMPERIAL_MARINE, // knots, feet, nautical miles, Fahrenheit
    METRIC_MARINE,   // knots, meters, nautical miles, Celsius (wind/distance stay nautical by convention)
}

object UnitConversions {
    fun mpsToKnots(metersPerSecond: Double): Double = metersPerSecond * 1.9438444924406
    fun kmhToKnots(kmh: Double): Double = kmh * 0.5399568035
    fun mphToKnots(mph: Double): Double = mph * 0.8689762419
    fun metersToFeet(meters: Double): Double = meters * 3.280839895
    fun celsiusToFahrenheit(celsius: Double): Double = celsius * 9.0 / 5.0 + 32.0
    fun kmToNauticalMiles(km: Double): Double = km * 0.5399568035
    fun milesToNauticalMiles(miles: Double): Double = miles * 0.8689762419
}
