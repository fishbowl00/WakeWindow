package com.wakewindow.app.domain.observation

import com.wakewindow.app.domain.model.GeoPoint

/**
 * The cheapest available signal for [WaterEnvironmentClassifier]: a coarse "land"/"marine"
 * (or provider-specific) classification of a coordinate, with no extra domain modeling of its
 * own. Entirely optional for [com.wakewindow.app.data.repository.DefaultBoatingRepository] -
 * without one, every location classifies as [WaterEnvironment.UNKNOWN], which never gates a
 * category (see [EvidenceRequirementEvaluator]) rather than guessing. See
 * docs/STATION_REPRESENTATIVENESS.md.
 */
interface WaterPointTypeProvider {
    suspend fun pointType(location: GeoPoint): String?
}
