package local.courtsearch

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import local.courtsearch.ui.CaseScreen
import local.courtsearch.ui.CourtTheme
import local.courtsearch.ui.HistoryScreen
import local.courtsearch.ui.PrivacyScreen
import local.courtsearch.ui.ResultsScreen
import local.courtsearch.ui.SearchScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.parseColor("#1E3A5F")),
            navigationBarStyle = SystemBarStyle.light(Color.parseColor("#FAFAF9"), Color.parseColor("#FAFAF9")),
        )
        setContent {
            val vm: SearchViewModel = viewModel()
            CourtTheme { App(vm) }
        }
    }
}

@Composable
private fun App(vm: SearchViewModel) {
    val state by vm.state.collectAsState()
    val case by vm.case.collectAsState()
    val nav = rememberNavController()
    LaunchedEffect(Unit) {
        vm.events.collect {
            nav.navigate("results") { launchSingleTop = true }
        }
    }
    NavHost(navController = nav, startDestination = "search") {
        composable("search") {
            SearchScreen(
                state,
                vm,
                onResults = { nav.navigate("results") { launchSingleTop = true } },
                onHistory = { nav.navigate("history") },
                onPrivacy = { nav.navigate("privacy") },
            )
        }
        composable("privacy") {
            PrivacyScreen(onBack = { nav.popBackStack() })
        }
        composable("history") {
            HistoryScreen(vm, onBack = { nav.popBackStack() })
        }
        composable("results") {
            ResultsScreen(
                state,
                vm,
                onBack = { nav.popBackStack() },
                onOpen = { row -> nav.navigate("case/${row.fips}/${row.caseNumber}") },
            )
        }
        composable("case/{fips}/{caseNumber}") { entry ->
            val fips = entry.arguments?.getString("fips").orEmpty()
            val caseNumber = entry.arguments?.getString("caseNumber").orEmpty()
            LaunchedEffect(fips, caseNumber) { vm.loadCase(fips, caseNumber) }
            CaseScreen(
                case,
                onBack = { nav.popBackStack() },
                onPrint = vm::printCase,
                onRetry = { vm.loadCase(fips, caseNumber) },
            )
        }
    }
}
