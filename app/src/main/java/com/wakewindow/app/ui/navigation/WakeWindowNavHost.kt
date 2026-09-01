package com.wakewindow.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wakewindow.app.ui.WakeWindowViewModel
import com.wakewindow.app.ui.about.AboutScreen
import com.wakewindow.app.ui.assessment.AssessmentScreen
import com.wakewindow.app.ui.launchinfo.LaunchInfoScreen
import com.wakewindow.app.ui.launchlist.LaunchListScreen
import com.wakewindow.app.ui.launchsearch.LaunchSearchScreen
import com.wakewindow.app.ui.planboat.PlanBoatScreen
import com.wakewindow.app.ui.settings.SettingsScreen
import com.wakewindow.app.ui.trip.TripWaypointTarget
import com.wakewindow.app.ui.tripplan.TripPlanScreen
import com.wakewindow.app.ui.tripresult.TripResultScreen
import com.wakewindow.app.ui.vessel.VesselProfileScreen

@Composable
fun WakeWindowNavHost(
    viewModel: WakeWindowViewModel,
    navController: NavHostController = rememberNavController(),
) {
    val state by viewModel.uiState.collectAsState()

    NavHost(navController = navController, startDestination = WakeWindowDestinations.LAUNCH_LIST) {
        composable(WakeWindowDestinations.LAUNCH_LIST) {
            LaunchListScreen(
                state = state,
                onAddLaunch = { navController.navigate(WakeWindowDestinations.SEARCH) },
                onOpenLaunch = { launch ->
                    viewModel.selectSavedLaunch(launch)
                    navController.navigate(WakeWindowDestinations.PLAN)
                },
                onOpenLaunchInfo = { launch ->
                    viewModel.viewLaunchInfo(launch)
                    navController.navigate(WakeWindowDestinations.LAUNCH_INFO)
                },
                onAddTrip = {
                    viewModel.startNewTrip()
                    navController.navigate(WakeWindowDestinations.TRIP_PLAN)
                },
                onOpenTrip = { trip ->
                    viewModel.selectSavedTrip(trip)
                    navController.navigate(WakeWindowDestinations.TRIP_PLAN)
                },
                onOpenSettings = { navController.navigate(WakeWindowDestinations.SETTINGS) },
                onOpenAbout = { navController.navigate(WakeWindowDestinations.ABOUT) },
            )
        }
        composable(WakeWindowDestinations.SEARCH) {
            // Reused for both Mode A launch search and Mode B waypoint search - see
            // WakeWindowUiState.tripSearchTarget's doc comment. Routing back differs: a trip
            // waypoint pick returns to the trip editor already on the back stack rather than
            // pushing a new PLAN destination.
            val isTripSearch = state.tripSearchTarget != null
            LaunchSearchScreen(
                state = state,
                onQueryChange = viewModel::onSearchQueryChange,
                onSearch = viewModel::search,
                onSelect = { candidate ->
                    viewModel.selectSearchCandidate(candidate)
                    if (isTripSearch) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(WakeWindowDestinations.PLAN) {
                            popUpTo(WakeWindowDestinations.LAUNCH_LIST)
                        }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(WakeWindowDestinations.TRIP_PLAN) {
            TripPlanScreen(
                state = state,
                onSetName = viewModel::setTripName,
                onSearchDeparture = {
                    viewModel.startTripWaypointSearch(TripWaypointTarget.Departure)
                    navController.navigate(WakeWindowDestinations.SEARCH)
                },
                onSearchDestination = {
                    viewModel.startTripWaypointSearch(TripWaypointTarget.Destination)
                    navController.navigate(WakeWindowDestinations.SEARCH)
                },
                onAddWaypoint = {
                    viewModel.startTripWaypointSearch(TripWaypointTarget.NewWaypoint)
                    navController.navigate(WakeWindowDestinations.SEARCH)
                },
                onRemoveWaypoint = viewModel::removeTripWaypoint,
                onDepartureTimeChange = viewModel::setTripDepartureTime,
                onVesselChange = viewModel::setTripVessel,
                onCruiseSpeedChange = viewModel::setTripCruiseSpeed,
                onNotesChange = viewModel::setTripNotes,
                onAssessTrip = { navController.navigate(WakeWindowDestinations.TRIP_RESULT) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(WakeWindowDestinations.TRIP_RESULT) {
            TripResultScreen(
                state = state,
                onRefresh = viewModel::runTripAssessment,
                onBack = { navController.popBackStack() },
            )
        }
        composable(WakeWindowDestinations.PLAN) {
            PlanBoatScreen(
                state = state,
                onDepartureChange = viewModel::setDepartureTime,
                onReturnChange = viewModel::setReturnTime,
                onVesselChange = viewModel::setVessel,
                onEditVessel = { navController.navigate(WakeWindowDestinations.VESSEL) },
                onQuickPlan = viewModel::applyQuickPlan,
                onShowConditions = { navController.navigate(WakeWindowDestinations.ASSESSMENT) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(WakeWindowDestinations.VESSEL) {
            VesselProfileScreen(
                state = state,
                onSelectVessel = viewModel::setVessel,
                onSaveProfile = viewModel::saveVesselProfile,
                onDeleteProfile = viewModel::deleteVesselProfile,
                onBack = { navController.popBackStack() },
            )
        }
        composable(WakeWindowDestinations.ASSESSMENT) {
            AssessmentScreen(
                state = state,
                onRefresh = viewModel::runAssessment,
                onBack = { navController.popBackStack() },
            )
        }
        composable(WakeWindowDestinations.LAUNCH_INFO) {
            LaunchInfoScreen(state = state, onBack = { navController.popBackStack() })
        }
        composable(WakeWindowDestinations.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(WakeWindowDestinations.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
