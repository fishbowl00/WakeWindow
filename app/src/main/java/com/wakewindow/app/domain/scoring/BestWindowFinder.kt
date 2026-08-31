package com.wakewindow.app.domain.scoring

/**
 * Finds the longest contiguous span of GOOD-or-better points, breaking ties by highest
 * average score. See docs/MARINE_SCORING.md "Best Window." Operates purely on whatever
 * [PointAssessment]s it's given - for Mode A that's currently bounded to the plan's own
 * departure/return window; extending this to scan a wider daylight range (so WakeWindow can
 * suggest a better window than the one the user asked about) only requires passing more
 * points in, not changing this algorithm.
 */
object BestWindowFinder {

    fun find(points: List<PointAssessment>): BestWindow? {
        val sorted = points.sortedBy { it.at }
        var bestStart = -1
        var bestEnd = -1
        var bestLength = 0
        var bestAvg = -1.0

        var i = 0
        while (i < sorted.size) {
            if (sorted[i].category.severityRank <= BoatingCategory.GOOD.severityRank) {
                var j = i
                while (j < sorted.size && sorted[j].category.severityRank <= BoatingCategory.GOOD.severityRank) {
                    j++
                }
                val length = j - i
                val avg = sorted.subList(i, j).map { it.score }.average()
                if (length > bestLength || (length == bestLength && avg > bestAvg)) {
                    bestStart = i
                    bestEnd = j - 1
                    bestLength = length
                    bestAvg = avg
                }
                i = j
            } else {
                i++
            }
        }

        if (bestStart == -1) return null
        return BestWindow(
            start = sorted[bestStart].at,
            end = sorted[bestEnd].at,
            averageScore = bestAvg.let { kotlin.math.round(it).toInt() },
        )
    }
}
