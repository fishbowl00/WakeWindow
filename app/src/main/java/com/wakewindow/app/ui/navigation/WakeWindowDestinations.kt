package com.wakewindow.app.ui.navigation

/** Plain string route constants, matching RideCast's own low-ceremony navigation shape - see
 * docs/RIDECAST_REFERENCE_AUDIT.md section 6. State is shared via one ViewModel hoisted above
 * the NavHost rather than nav arguments, since this app's flow is one continuous journey
 * (search -> plan -> assessment), not several independent graphs. */
object WakeWindowDestinations {
    const val LAUNCH_LIST = "launch_list"
    const val SEARCH = "search"
    const val PLAN = "plan"
    const val ASSESSMENT = "assessment"
    const val LAUNCH_INFO = "launch_info"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
}
