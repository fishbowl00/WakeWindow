package com.wakewindow.app.domain.vessel

import java.util.UUID

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

    /** Stable identity for a saved profile - a preset's `id` is its own name (presets have no
     * separate persistence row; see [com.wakewindow.app.data.local.VesselProfileEntity]), a
     * user-created/customized profile gets a real UUID. Never used by scoring itself (which
     * only ever reads the tolerance fields above), only by persistence/selection UI. */
    val id: String = name,
    /** True only for a profile the user created or edited themselves - see
     * docs/VESSEL_PROFILES.md "Planning preferences, not safe limits." A preset copied but not
     * changed is still preset-derived, not custom. */
    val isCustom: Boolean = false,
    val notes: String? = null,
    val createdAtEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long? = null,
) {
    /** A fresh, real ID for a brand-new custom profile - callers should not construct their own
     * UUIDs inline. */
    fun withNewId(): VesselProfile = copy(id = UUID.randomUUID().toString())

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

        /**
         * The first user-selectable vessel presets - see docs/MARINE_SCORING.md "Vessel
         * profiles." Tolerances are deliberately coarse, defensible starting points (not a
         * manufacturer spec sheet): a PWC's low freeboard and lack of a cabin make it far more
         * wind/wave-sensitive than its size alone suggests, while a sailboat's tolerance
         * reflects motoring/day-sailing in developing weather, not a bluewater passage. Every
         * preset must still be traceable to a real, explainable [BoatingCategory] gate in
         * [com.wakewindow.app.domain.scoring.MarinePointScorer] - none of these numbers bypass
         * that. Never overrides an active marine warning gate - see [MarineAlert].
         */
        fun presets(): List<VesselProfile> = listOf(
            default().copy(name = "Small recreational boat", id = "Small recreational boat", vesselType = VesselType.BOWRIDER, lengthFt = 18.0),
            VesselProfile(
                name = "Center console",
                vesselType = VesselType.SMALL_CENTER_CONSOLE,
                lengthFt = 24.0,
                propulsionType = PropulsionType.OUTBOARD,
                windToleranceKts = 22.0,
                gustToleranceKts = 28.0,
                waveToleranceFt = 3.5,
                thunderstormTolerancePercent = 40,
                visibilityToleranceNm = 1.0,
                isSmallCraft = true,
            ),
            VesselProfile(
                name = "Pontoon",
                vesselType = VesselType.PONTOON,
                lengthFt = 22.0,
                propulsionType = PropulsionType.OUTBOARD,
                windToleranceKts = 15.0,
                gustToleranceKts = 18.0,
                waveToleranceFt = 1.5,
                thunderstormTolerancePercent = 30,
                visibilityToleranceNm = 1.0,
                isSmallCraft = true,
            ),
            VesselProfile(
                name = "PWC (jet ski)",
                vesselType = VesselType.PWC,
                lengthFt = 11.0,
                propulsionType = PropulsionType.JET,
                windToleranceKts = 15.0,
                gustToleranceKts = 18.0,
                waveToleranceFt = 1.5,
                thunderstormTolerancePercent = 25,
                visibilityToleranceNm = 1.0,
                isSmallCraft = true,
            ),
            VesselProfile(
                name = "Sailboat",
                vesselType = VesselType.SAILBOAT,
                lengthFt = 30.0,
                propulsionType = PropulsionType.SAIL,
                windToleranceKts = 25.0,
                gustToleranceKts = 32.0,
                waveToleranceFt = 4.0,
                thunderstormTolerancePercent = 40,
                visibilityToleranceNm = 1.0,
                isSmallCraft = false,
            ),
        )
    }
}
