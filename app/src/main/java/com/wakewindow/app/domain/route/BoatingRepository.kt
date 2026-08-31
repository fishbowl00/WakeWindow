package com.wakewindow.app.domain.route

import com.wakewindow.app.domain.scoring.BoatingWindowAssessment

/** The one entry point ViewModels use to turn a [BoatingPlan] into a scored assessment -
 * everything about which providers are consulted, how they're merged, and how failures
 * degrade is an implementation detail behind this interface. */
interface BoatingRepository {
    suspend fun buildAssessment(plan: BoatingPlan): BoatingWindowAssessment
}
