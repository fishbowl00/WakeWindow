package com.wakewindow.app.domain.scoring

import com.wakewindow.app.domain.alert.AlertImpactBehavior
import com.wakewindow.app.domain.alert.AlertSeverityCap
import com.wakewindow.app.domain.marine.MarineConditions
import com.wakewindow.app.domain.model.Confidence
import com.wakewindow.app.domain.model.ConfidenceLevel
import com.wakewindow.app.domain.observation.WaterEnvironment
import com.wakewindow.app.domain.route.RouteSample
import com.wakewindow.app.domain.vessel.VesselProfile
import kotlin.math.roundToInt

/**
 * Scores a single [MarineConditions] reading against a [VesselProfile]. See
 * docs/MARINE_SCORING.md "Point-level scoring" for the full rationale: deductions scale with
 * how far a value sits past the vessel's tolerance; gates (severe hazards) cap the category
 * outright rather than being folded into the numeric score, and a gate can only pull the
 * category down from what the raw score implies, never up.
 */
object MarinePointScorer {

    fun score(
        sample: RouteSample,
        conditions: MarineConditions?,
        vessel: VesselProfile,
        environment: WaterEnvironment = WaterEnvironment.UNKNOWN,
    ): PointAssessment {
        if (conditions == null) {
            return PointAssessment(
                at = sample.estimatedTime,
                sample = sample,
                conditions = null,
                category = BoatingCategory.UNAVAILABLE,
                score = 0,
                hazards = emptyList(),
                confidence = Confidence.unavailable("No forecast data available for this hour"),
            )
        }

        var deduction = 0.0
        val hazards = mutableListOf<Hazard>()
        var gateCap: BoatingCategory? = null

        fun applyGate(cap: BoatingCategory) {
            gateCap = gateCap?.let { worstCategory(it, cap) } ?: cap
        }

        // --- Marine alerts: gated by relevance/impact, not a blanket "any advisory caps the
        // category" policy - see docs/MARINE_SCORING.md "Alert relevance model." An alert is
        // always surfaced (a Hazard entry is always added) even when it has no scoring
        // consequence, so nothing "disappears" silently - see docs/ROADMAP.md Sprint 3.
        for (alert in conditions.marineAlerts) {
            if (!alert.isActiveAt(conditions.timestamp)) continue
            val impact = alert.impact
            when (impact.behavior) {
                AlertImpactBehavior.HARD_GATE, AlertImpactBehavior.CATEGORY_CEILING -> {
                    val cap = impact.categoryCap.toBoatingCategory() ?: BoatingCategory.CAUTION
                    applyGate(cap)
                    hazards += Hazard(hazardTypeFor(cap), "${alert.event} in effect", conditions.timestamp, categoryCap = cap)
                }
                AlertImpactBehavior.SCORE_DEDUCTION -> {
                    deduction += impact.scoreDeduction
                    hazards += Hazard(HazardType.MARINE_ALERT_ADVISORY, "${alert.event} in effect", conditions.timestamp)
                }
                AlertImpactBehavior.INFORMATIONAL_ONLY -> {
                    hazards += Hazard(HazardType.MARINE_ALERT_ADVISORY, "${alert.event} in effect (informational)", conditions.timestamp)
                }
            }
        }

        // --- Thunderstorm probability ---
        conditions.thunderstormProbabilityPercent?.let { pct ->
            val tolerance = vessel.thunderstormTolerancePercent
            val softStart = (tolerance * 0.3).roundToInt()
            if (pct > softStart) {
                deduction += rampPenalty(pct.toDouble(), softStart.toDouble(), tolerance.toDouble(), maxPenalty = 20.0)
            }
            if (pct >= tolerance) {
                val cap = when {
                    pct >= 90 -> BoatingCategory.NO_GO
                    pct >= tolerance + 30 -> BoatingCategory.POOR
                    else -> BoatingCategory.CAUTION
                }
                applyGate(cap)
                hazards += Hazard(HazardType.THUNDERSTORM, "Thunderstorm probability $pct%", conditions.timestamp, pct.toDouble(), tolerance.toDouble(), cap)
            }
        }

        // --- Wave height ---
        conditions.waveHeightFt?.let { waveFt ->
            val tolerance = vessel.waveToleranceFt
            val softStart = tolerance * 0.4
            if (waveFt > softStart) {
                deduction += rampPenalty(waveFt, softStart, tolerance, maxPenalty = 25.0)
            }
            if (waveFt >= tolerance) {
                val cap = when {
                    waveFt >= tolerance * 1.5 -> BoatingCategory.NO_GO
                    waveFt >= tolerance * 1.25 -> BoatingCategory.POOR
                    else -> BoatingCategory.CAUTION
                }
                applyGate(cap)
                hazards += Hazard(HazardType.WAVE_HEIGHT, "Seas around ${formatFeet(waveFt)}", conditions.timestamp, waveFt, tolerance, cap)
            }
        }
        // Short, steep wave period is worse than the same height at a long period.
        val wavePeriod = conditions.wavePeriodSec
        val waveHeight = conditions.waveHeightFt
        if (wavePeriod != null && waveHeight != null && wavePeriod < 5.0 && waveHeight > 1.5) {
            deduction += 8.0
        }

        // --- Gusts ---
        conditions.gustKts?.let { gust ->
            val tolerance = vessel.gustToleranceKts
            val softStart = tolerance * 0.6
            if (gust > softStart) {
                deduction += rampPenalty(gust, softStart, tolerance, maxPenalty = 20.0)
            }
            if (gust >= tolerance) {
                val cap = if (gust >= tolerance + 10) BoatingCategory.POOR else BoatingCategory.CAUTION
                applyGate(cap)
                hazards += Hazard(HazardType.GUST, "Wind gusts reaching ${gust.roundToInt()} kt", conditions.timestamp, gust, tolerance, cap)
            }
        } ?: conditions.sustainedWindKts?.let { wind ->
            // No gust data - fall back to sustained wind against the same tolerance, more conservatively.
            val tolerance = vessel.windToleranceKts
            val softStart = tolerance * 0.5
            if (wind > softStart) {
                deduction += rampPenalty(wind, softStart, tolerance, maxPenalty = 20.0)
            }
        }

        // --- Visibility ---
        conditions.visibilityNm?.let { vis ->
            if (vis < vessel.visibilityToleranceNm) {
                val cap = if (vis < vessel.visibilityToleranceNm / 2) BoatingCategory.POOR else BoatingCategory.CAUTION
                applyGate(cap)
                hazards += Hazard(HazardType.VISIBILITY, "Visibility down to ${formatNm(vis)}", conditions.timestamp, vis, vessel.visibilityToleranceNm, cap)
            } else if (vis < vessel.visibilityToleranceNm * 3) {
                deduction += rampPenalty(
                    vessel.visibilityToleranceNm * 3 - vis,
                    0.0,
                    vessel.visibilityToleranceNm * 3 - vessel.visibilityToleranceNm,
                    maxPenalty = 8.0,
                )
            }
        }

        // --- Precipitation probability (general nuisance, not vessel-scaled) ---
        conditions.precipitationProbabilityPercent?.let { pct ->
            deduction += when {
                pct >= 60 -> 10.0
                pct >= 30 -> 5.0
                else -> 0.0
            }
        }

        val rawScore = (100.0 - deduction).roundToInt().coerceIn(0, 100)
        var category = categoryFromScore(rawScore)
        gateCap?.let { category = worstCategory(category, it) }

        // --- Environment-aware evidence ceiling: only ever pulls EXCELLENT down to GOOD when
        // evidence this environment normally provides is missing - never raises a category, and
        // never gates an environment where the missing evidence isn't expected to exist. See
        // docs/MARINE_SCORING.md "Environment-aware evidence requirements."
        EvidenceRequirementEvaluator.evaluate(environment, conditions)?.let { (ceiling, reason) ->
            if (worstCategory(category, ceiling) != category) {
                hazards += Hazard(HazardType.EVIDENCE_INCOMPLETE, reason, conditions.timestamp, categoryCap = ceiling)
            }
            category = worstCategory(category, ceiling)
        }

        val confidence = pointConfidence(conditions, hazards.isNotEmpty())

        return PointAssessment(
            at = conditions.timestamp,
            sample = sample,
            conditions = conditions,
            category = category,
            score = rawScore,
            hazards = hazards,
            confidence = confidence,
        )
    }

    private fun rampPenalty(value: Double, rampStart: Double, rampEnd: Double, maxPenalty: Double): Double {
        if (rampEnd <= rampStart) return if (value >= rampEnd) maxPenalty else 0.0
        val fraction = ((value - rampStart) / (rampEnd - rampStart)).coerceIn(0.0, 1.0)
        return fraction * maxPenalty
    }

    /**
     * A missing field must never silently read as "conditions must be fine" - see
     * docs/ASSESSMENT_VALIDATION.md "Missing data policy." This never adds a scoring
     * deduction for a missing field (an inland lake with no wave sensor isn't being
     * penalized for waves that don't exist there); it only ever reduces how much the
     * resulting score should be trusted, and always says exactly which evidence is missing.
     */
    private fun pointConfidence(conditions: MarineConditions, hasHazards: Boolean): Confidence {
        var level = conditions.confidence.level
        val reasons = conditions.confidence.reasons.toMutableList()

        if (level == ConfidenceLevel.HIGH && !conditions.hasAnyMarineData) {
            level = ConfidenceLevel.MEDIUM
            reasons += "No marine (wave/tide) data available for this location"
        }

        val missingCore = buildList {
            if (conditions.sustainedWindKts == null) add("wind")
            if (conditions.waveHeightFt == null) add("wave height")
            if (conditions.thunderstormProbabilityPercent == null) add("thunderstorm risk")
            if (conditions.visibilityNm == null) add("visibility")
        }
        if (missingCore.size >= 3) {
            // Most of the safety-relevant evidence this hour's score would be based on is
            // simply absent - a POOR/EXCELLENT verdict from what little remains isn't
            // trustworthy, so this is LOW, not merely MEDIUM.
            level = if (level.ordinal < ConfidenceLevel.LOW.ordinal) ConfidenceLevel.LOW else level
            reasons += "Missing ${missingCore.joinToString(", ")} for this hour"
        } else if (missingCore.isNotEmpty()) {
            level = if (level.ordinal < ConfidenceLevel.MEDIUM.ordinal) ConfidenceLevel.MEDIUM else level
            reasons += "Missing ${missingCore.joinToString(", ")} for this hour"
        }

        return Confidence(level, reasons.distinct())
    }

    private fun formatFeet(value: Double): String = "${String.format("%.1f", value)} ft"
    private fun formatNm(value: Double): String = "${String.format("%.1f", value)} NM"

    /** [AlertSeverityCap] is deliberately decoupled from [BoatingCategory] (see
     * [AlertSeverityCap]'s doc comment) - this is the one place that bridges them back
     * together, since this scorer already depends on both packages. */
    private fun AlertSeverityCap.toBoatingCategory(): BoatingCategory? = when (this) {
        AlertSeverityCap.NO_GO -> BoatingCategory.NO_GO
        AlertSeverityCap.POOR -> BoatingCategory.POOR
        AlertSeverityCap.CAUTION -> BoatingCategory.CAUTION
        AlertSeverityCap.NONE -> null
    }

    private fun hazardTypeFor(cap: BoatingCategory): HazardType = when (cap) {
        BoatingCategory.NO_GO -> HazardType.MARINE_ALERT_EXTREME
        BoatingCategory.POOR -> HazardType.MARINE_ALERT_SEVERE
        else -> HazardType.MARINE_ALERT_ADVISORY
    }
}
