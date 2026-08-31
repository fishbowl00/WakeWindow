package com.wakewindow.app.domain.scoring

/**
 * See docs/MARINE_SCORING.md "Categories." Ordered worst-to-best is NOT the declaration
 * order here - use [severityRank] (higher = worse) for comparisons, since Kotlin enum
 * ordinal order follows declaration order and UNAVAILABLE is deliberately not "worse than
 * NO_GO" (it's a different kind of thing: unknown, not dangerous).
 */
enum class BoatingCategory {
    EXCELLENT,
    GOOD,
    CAUTION,
    POOR,
    NO_GO,
    UNAVAILABLE,
}

/** Higher = worse. UNAVAILABLE ranks just above NO_GO so "worst of" rollups still surface a
 * real NO_GO over a merely-unscored gap, while still treating unavailable data as a serious
 * concern for window-level aggregation. */
val BoatingCategory.severityRank: Int
    get() = when (this) {
        BoatingCategory.EXCELLENT -> 0
        BoatingCategory.GOOD -> 1
        BoatingCategory.CAUTION -> 2
        BoatingCategory.POOR -> 3
        BoatingCategory.NO_GO -> 5
        BoatingCategory.UNAVAILABLE -> 4
    }

fun worstCategory(a: BoatingCategory, b: BoatingCategory): BoatingCategory =
    if (a.severityRank >= b.severityRank) a else b

fun categoryFromScore(score: Int): BoatingCategory = when {
    score >= 85 -> BoatingCategory.EXCELLENT
    score >= 70 -> BoatingCategory.GOOD
    score >= 50 -> BoatingCategory.CAUTION
    score >= 25 -> BoatingCategory.POOR
    else -> BoatingCategory.NO_GO
}
