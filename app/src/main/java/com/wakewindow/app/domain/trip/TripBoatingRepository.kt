package com.wakewindow.app.domain.trip

/** The Mode B counterpart to [com.wakewindow.app.domain.route.BoatingRepository] - builds a
 * [TripAssessment] from a [MarineTripPlan] by fetching conditions at each point's own location
 * and expected arrival time. See [com.wakewindow.app.data.repository.DefaultTripBoatingRepository]. */
interface TripBoatingRepository {
    suspend fun buildTripAssessment(plan: MarineTripPlan): TripAssessment
}
