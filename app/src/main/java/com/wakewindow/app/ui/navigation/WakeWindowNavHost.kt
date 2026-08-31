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
                onOpenSettings = { navController.navigate(WakeWindowDestinations.SETTINGS) },
                onOpenAbout = { navController.navigate(WakeWindowDestinations.ABOUT) },
            )
        }
        composable(WakeWindowDestinations.SEARCH) {
            LaunchSearchScreen(
                state = state,
                onQueryChange = viewModel::onSearchQueryChange,
                onSearch = viewModel::search,
                onSelect = { candidate ->
                    viewModel.selectSearchCandidate(candidate)
                    navController.navigate(WakeWindowDestinations.PLAN) {
                        popUpTo(WakeWindowDestinations.LAUNCH_LIST)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(WakeWindowDestinations.PLAN) {
            PlanBoatScreen(
                state = state,
                onDepartureChange = viewModel::setDepartureTime,
                onReturnChange = viewModel::setReturnTime,
                onVesselChange = viewModel::setVessel,
                onShowConditions = { navController.navigate(WakeWindowDestinations.ASSESSMENT) },
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
            state.infoLaunch?.let { launch ->
                LaunchInfoScreen(place = launch.place, onBack = { navController.popBackStack() })
            }
        }
        composable(WakeWindowDestinations.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(WakeWindowDestinations.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
