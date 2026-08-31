package com.wakewindow.app.domain.vessel

enum class VesselType {
    SMALL_CENTER_CONSOLE,
    BOWRIDER,
    PONTOON,
    FISHING_BOAT,
    SAILBOAT,
    CRUISER,
    PWC,
    OTHER,
}

enum class PropulsionType { OUTBOARD, INBOARD, STERNDRIVE, SAIL, JET, OTHER }

/**
 * Every marine-scoring threshold in [com.wakewindow.app.domain.scoring.MarineScoreEngine]
 * reads from a VesselProfile rather than a hardcoded constant - the same 20 kt wind forecast
 * is a non-event for a 34' cruiser and a real hazard for a loaded pontoon. See
 * docs/MARINE_SCORING.md. The MVP UI only offers [default]; the fields below exist so
 * scoring is never rewritten when profile selection/editing is built out.
 */
data class VesselProfile(
    val name: String,
    val vesselType: VesselType,
    val lengthFt: Double? = null,
    val beamFt: Double? = null,
    val draftFt: Double? = null,
    val propulsionType: PropulsionType? = null,
    val cruiseSpeedKts: Double? = null,

    val windToleranceKts: Double,
    val gustToleranceKts: Double,
    val waveToleranceFt: Double,
    val thunderstormTolerancePercent: Int,
    val visibilityToleranceNm: Double,

    /** Small Craft Advisories are written for vessels roughly this size and smaller;
     * a materially larger/heavier vessel treats one as a deduction, not a hard gate -
     * see docs/MARINE_SCORING.md. */
    val isSmallCraft: Boolean = true,
) {
    companion object {
        /** Sensible default recreational profile - a mid-size center-console/bowrider,
         * used until per-vessel profile selection is built. */
        fun default(): VesselProfile = VesselProfile(
            name = "Recreational boat (default)",
            vesselType = VesselType.SMALL_CENTER_CONSOLE,
            lengthFt = 22.0,
            propulsionType = PropulsionType.OUTBOARD,
            windToleranceKts = 20.0,
            gustToleranceKts = 25.0,
            waveToleranceFt = 3.0,
            thunderstormTolerancePercent = 40,
            visibilityToleranceNm = 1.0,
            isSmallCraft = true,
        )
    }
}
