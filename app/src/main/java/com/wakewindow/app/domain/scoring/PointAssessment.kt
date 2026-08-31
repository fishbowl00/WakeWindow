package com.wakewindow.app.domain.scoring

import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.route.RouteSample
import java.time.Instant

/**
 * The atomic scoring unit: one place, one hour. Every window-level number (departure,
 * return, overall) is built by combining these - never a separate "whole day" formula, so
 * every value shown in the UI is traceable back to a specific hour's conditions. See
 * docs/MARINE_SCORING.md.
 */
data class PointAssessment(
    val at: Instant,
    val sample: RouteSample,
    val conditions: MarineConditions?,
    val category: BoatingCategory,
    val score: Int,
    val hazards: List<Hazard>,
    val confidence: Confidence,
)
