package com.fesu.renjana.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fesu.renjana.ui.components.NavItem
import com.fesu.renjana.ui.components.RenjanaBottomBar
import com.fesu.renjana.ui.screens.*
import java.net.URLEncoder

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Filled.Home)
    object Apps : Screen("apps", "Apps", Icons.Filled.Apps)
    object Accounts : Screen("accounts", "Accounts", Icons.Filled.People)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
    object CreateInstance : Screen("create_instance/{packageName}/{apkPath}", "Create Instance", Icons.Filled.Apps) {
        const val BASE_ROUTE = "create_instance"
        fun createRoute(packageName: String, apkPath: String): String {
            val encodedPkg = URLEncoder.encode(packageName, "UTF-8")
            val encodedPath = URLEncoder.encode(apkPath, "UTF-8")
            return "$BASE_ROUTE/$encodedPkg/$encodedPath"
        }
    }
    object InstanceDetail : Screen("instance_detail/{instanceId}", "Instance Detail", Icons.Filled.Apps) {
        const val BASE_ROUTE = "instance_detail"
        fun createRoute(instanceId: String): String = "$BASE_ROUTE/$instanceId"
    }
    object ErrorLogs : Screen("error_logs", "Error Logs", Icons.Filled.Apps)
    object Diagnostics : Screen("diagnostics/{instanceId}", "Diagnostics", Icons.Filled.BugReport) {
        const val BASE_ROUTE = "diagnostics"
        fun createRoute(instanceId: String) = "$BASE_ROUTE/$instanceId"
    }
}

private val bottomNavItems = listOf(
    NavItem(Screen.Home.route, Screen.Home.label, Screen.Home.icon),
    NavItem(Screen.Apps.route, Screen.Apps.label, Screen.Apps.icon),
    NavItem(Screen.Accounts.route, Screen.Accounts.label, Screen.Accounts.icon),
    NavItem(Screen.Settings.route, Screen.Settings.label, Screen.Settings.icon)
)

@Composable
fun RenjanaApp(
    darkMode: Boolean,
    onToggleDarkMode: (Boolean) -> Unit,
    accentColor: androidx.compose.ui.graphics.Color,
    onAccentChange: (androidx.compose.ui.graphics.Color) -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showBottomBar = currentRoute?.startsWith(Screen.CreateInstance.BASE_ROUTE) != true &&
            currentRoute?.startsWith(Screen.InstanceDetail.BASE_ROUTE) != true &&
            currentRoute?.startsWith(Screen.Diagnostics.BASE_ROUTE) != true &&
            currentRoute != Screen.ErrorLogs.route

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                RenjanaBottomBar(
                    items = bottomNavItems,
                    currentRoute = currentRoute,
                    onItemClick = { item ->
                        navController.navigate(item.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(tween(250)) + slideInHorizontally(tween(300)) { it / 8 } },
            exitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(250)) { -it / 12 } },
            popEnterTransition = { fadeIn(tween(250)) + slideInHorizontally(tween(300)) { -it / 12 } },
            popExitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(250)) { it / 8 } }
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToApps = {
                        navController.navigate(Screen.Apps.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onInstanceClick = { instanceId ->
                        navController.navigate(Screen.InstanceDetail.createRoute(instanceId))
                    }
                )
            }
            composable(Screen.Apps.route) {
                AppsScreen(
                    onSelectApp = { app ->
                        val route = Screen.CreateInstance.createRoute(app.packageName, app.apkPath)
                        navController.navigate(route)
                    },
                    clonedPackageNames = emptySet()
                )
            }
            composable(Screen.Accounts.route) { AccountsScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    darkMode = darkMode,
                    onToggleDarkMode = onToggleDarkMode,
                    accentColor = accentColor,
                    onAccentChange = onAccentChange,
                    onNavigateToErrorLogs = {
                        navController.navigate(Screen.ErrorLogs.route)
                    }
                )
            }
            composable(
                route = Screen.CreateInstance.route,
                arguments = listOf(
                    navArgument("packageName") { type = NavType.StringType },
                    navArgument("apkPath") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val pkg = backStackEntry.arguments?.getString("packageName") ?: ""
                val path = backStackEntry.arguments?.getString("apkPath") ?: ""
                CreateInstanceScreen(
                    onNavigateBack = { navController.popBackStack() },
                    packageName = pkg,
                    apkPath = path
                )
            }
            composable(
                route = Screen.InstanceDetail.route,
                arguments = listOf(
                    navArgument("instanceId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val instanceId = backStackEntry.arguments?.getString("instanceId") ?: ""
                InstanceDetailScreen(
                    instanceId = instanceId,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToDiagnostics = {
                        navController.navigate(Screen.Diagnostics.createRoute(instanceId))
                    }
                )
            }
            composable(
                route = Screen.Diagnostics.route,
                arguments = listOf(
                    navArgument("instanceId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val instanceId = backStackEntry.arguments?.getString("instanceId") ?: ""
                DiagnosticsScreen(
                    instanceId = instanceId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.ErrorLogs.route) {
                ErrorLogScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
