package com.wakewindow.app.domain.observation

/**
 * A coarse classification of the water body at a location - just enough to catch obviously
 * invalid comparisons (a 23 NM offshore buoy does not represent conditions inside a protected
 * marina), not a hydrographic survey. See docs/STATION_REPRESENTATIVENESS.md for the full
 * classification policy and why several of these values (`RIVER`, `INTRACOASTAL`,
 * `GREAT_LAKES`) are recognized but not yet distinguishable by this sprint's heuristic -
 * `UNKNOWN` is the correct, honest result whenever the available signals don't support a
 * confident call, never a guess.
 */
enum class WaterEnvironment {
    INLAND,
    RIVER,
    ESTUARY,
    INTRACOASTAL,
    HARBOR,
    NEARSHORE,
    OFFSHORE,
    GREAT_LAKES,
    UNKNOWN,
}

/**
 * Classifies a launch location from signals already gathered elsewhere in the fetch pipeline
 * - no extra network calls. Deliberately conservative: see docs/STATION_REPRESENTATIVENESS.md
 * for the exact rule table and its rationale.
 */
object WaterEnvironmentClassifier {

    fun classify(nwsPointType: String?, nearestTideStationDistanceNm: Double?): WaterEnvironment =
        when (nwsPointType) {
            "marine" -> WaterEnvironment.NEARSHORE
            "land" -> when {
                nearestTideStationDistanceNm == null -> WaterEnvironment.INLAND
                nearestTideStationDistanceNm <= 5.0 -> WaterEnvironment.HARBOR
                nearestTideStationDistanceNm <= 25.0 -> WaterEnvironment.ESTUARY
                else -> WaterEnvironment.INLAND
            }
            else -> WaterEnvironment.UNKNOWN
        }

    /** Whether two environments are close enough in kind that a station in one is plausible
     * evidence for the other - e.g. a HARBOR launch and a NEARSHORE station are compatible;
     * an INLAND launch and an OFFSHORE station are not. Deliberately a coarse, symmetric,
     * documented table rather than a distance formula - see docs/STATION_REPRESENTATIVENESS.md. */
    fun areCompatible(a: WaterEnvironment, b: WaterEnvironment): Boolean {
        if (a == WaterEnvironment.UNKNOWN || b == WaterEnvironment.UNKNOWN) return false
        if (a == b) return true
        val coastalCluster = setOf(WaterEnvironment.HARBOR, WaterEnvironment.ESTUARY, WaterEnvironment.INTRACOASTAL, WaterEnvironment.NEARSHORE)
        val openWaterCluster = setOf(WaterEnvironment.NEARSHORE, WaterEnvironment.OFFSHORE)
        return (a in coastalCluster && b in coastalCluster) || (a in openWaterCluster && b in openWaterCluster)
    }
}
