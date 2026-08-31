package com.wakewindow.app.domain.observation

enum class RepresentativenessLevel { HIGH, MEDIUM, LOW, UNKNOWN }

/**
 * How much a station's observation should be trusted as evidence for a *specific* launch/plan
 * - distinct from [ObservationFreshness], which only measures age. A station can be perfectly
 * fresh and still a poor stand-in for the user's actual water (23 NM offshore vs. a protected
 * marina). See docs/STATION_REPRESENTATIVENESS.md for the full, deterministic rule table this
 * implements.
 */
data class StationRepresentativeness(
    val level: RepresentativenessLevel,
    val reasons: List<String>,
)

object StationRepresentativenessEvaluator {

    private const val HIGH_DISTANCE_NM = 10.0
    private const val MEDIUM_DISTANCE_NM = 30.0

    fun evaluate(
        distanceNm: Double,
        launchEnvironment: WaterEnvironment,
        stationEnvironment: WaterEnvironment,
        freshness: ObservationFreshness,
    ): StationRepresentativeness {
        val reasons = mutableListOf<String>()

        if (freshness == ObservationFreshness.UNUSABLE) {
            return StationRepresentativeness(RepresentativenessLevel.LOW, listOf("Observation is too old to represent current conditions"))
        }

        val environmentsUnknown = launchEnvironment == WaterEnvironment.UNKNOWN || stationEnvironment == WaterEnvironment.UNKNOWN
        val compatible = WaterEnvironmentClassifier.areCompatible(launchEnvironment, stationEnvironment)

        if (environmentsUnknown) {
            reasons += "Water environment could not be confidently classified"
        } else if (!compatible) {
            reasons += "Station environment ($stationEnvironment) does not match the launch's environment ($launchEnvironment)"
        }

        val level = when {
            environmentsUnknown -> RepresentativenessLevel.UNKNOWN
            !compatible -> RepresentativenessLevel.LOW
            distanceNm <= HIGH_DISTANCE_NM && freshness == ObservationFreshness.FRESH -> RepresentativenessLevel.HIGH
            distanceNm <= MEDIUM_DISTANCE_NM -> RepresentativenessLevel.MEDIUM
            else -> RepresentativenessLevel.LOW
        }

        if (level == RepresentativenessLevel.LOW && compatible && !environmentsUnknown) {
            reasons += "Station is ${"%.0f".format(distanceNm)} NM away - too far to confidently represent this launch"
        }
        if (level == RepresentativenessLevel.MEDIUM) {
            reasons += "Station is ${"%.0f".format(distanceNm)} NM away and in a compatible environment, but not close enough for full confidence"
        }

        return StationRepresentativeness(level, reasons)
    }
}
