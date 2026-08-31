package com.wakewindow.app.domain.scoring

import com.wakewindow.app.domain.model.Confidence
import java.time.Instant

/** Home-screen headline number/category plus the specific reasons driving it - never a
 * generic "conditions may be unfavorable" string. See docs/MARINE_SCORING.md. */
data class OverallAssessment(
    val category: BoatingCategory,
    val score: Int,
    val reasons: List<Hazard>,
)

data class BestWindow(
    val start: Instant,
    val end: Instant,
    val averageScore: Int,
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
) {
    val allPoints: List<PointAssessment> get() =
        listOf(departureAssessment) + underwayAssessments + returnAssessment
}
