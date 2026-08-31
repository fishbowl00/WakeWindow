package com.wakewindow.app.domain.scoring

import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.observation.MarineDisagreement
import com.wakewindow.app.domain.observation.ObservationForecastComparison
import com.wakewindow.app.domain.observation.SelectedMarineStation
import com.wakewindow.app.domain.observation.WaterEnvironment
import java.time.Instant

/** Home-screen headline number/category plus the specific reasons driving it - never a
 * generic "conditions may be unfavorable" string. See docs/MARINE_SCORING.md. */
data class OverallAssessment(
    val category: BoatingCategory,
    val score: Int,
    val reasons: List<Hazard>,
)

/**
 * See docs/MARINE_SCORING.md "Best Window." [matchesPlannedWindow] is true when this span is
 * (approximately) the same as what the user already planned - in that case the UI must not
 * call it "Best Window" as if a better alternative exists; it should say the planned window
 * itself is good. [reasons] and [recommendReturnBy] are generated deterministically from the
 * scored points, never hand-written prose.
 */
data class BestWindow(
    val start: Instant,
    val end: Instant,
    val averageScore: Int,
    val reasons: List<String>,
    val matchesPlannedWindow: Boolean,
    /** Set when conditions are expected to deteriorate before the plan's own return time -
     * the last point still GOOD-or-better before that happens. Null when the planned return
     * is already within (or before) the good window. */
    val recommendReturnBy: Instant?,
)

/** One line of evidence that did or didn't contribute to this assessment, for the confidence
 * explanation UI - see docs/MARINE_SCORING.md "Confidence." */
data class EvidenceItem(val label: String, val available: Boolean)

data class ConfidenceEvidence(
    val items: List<EvidenceItem>,
    val limitations: List<String>,
)

/**
 * The full result for one boating-day plan. See docs/MARINE_SCORING.md "Output shape" -
 * every value here is built from the same [PointAssessment]s, so nothing is a parallel,
 * independently-computed "whole day" figure.
 */
data class BoatingWindowAssessment(
    val departureAssessment: PointAssessment,
    val underwayAssessments: List<PointAssessment>,
    val returnAssessment: PointAssessment,
    val overallAssessment: OverallAssessment,
    val bestWindow: BestWindow?,
    val worstHazards: List<Hazard>,
    val confidence: Confidence,
    val evidence: ConfidenceEvidence = ConfidenceEvidence(emptyList(), emptyList()),
    val nearestObservationStation: SelectedMarineStation? = null,
    /** Convenience view of [observationComparison]'s disagreements, kept for callers that only
     * care about the messages - the full comparison (representativeness, status, timestamps)
     * lives on [observationComparison]. */
    val disagreements: List<MarineDisagreement> = emptyList(),
    /** The full forecast-vs-observation comparison, always evaluated at the station's own
     * coordinates - see [com.wakewindow.app.data.repository.DefaultBoatingRepository] and
     * docs/MARINE_SCORING.md "Forecast vs. observation." Null exactly when
     * [nearestObservationStation] is null (no station/observation was available at all). */
    val observationComparison: ObservationForecastComparison? = null,
    /** The launch's classified water body - see [WaterEnvironment] and
     * docs/STATION_REPRESENTATIVENESS.md. UNKNOWN when the available signals didn't support a
     * confident classification. */
    val waterEnvironment: WaterEnvironment = WaterEnvironment.UNKNOWN,
) {
    val allPoints: List<PointAssessment> get() =
        listOf(departureAssessment) + underwayAssessments + returnAssessment
}
