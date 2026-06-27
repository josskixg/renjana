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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fesu.renjana.ui.viewmodels.HomeViewModel
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
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Filled.Home)
    object Apps : Screen("apps?instanceId={instanceId}", "Apps", Icons.Filled.Apps) {
        const val BASE_ROUTE = "apps"
        fun createRoute(instanceId: String? = null): String =
            if (instanceId != null) "apps?instanceId=$instanceId" else "apps?instanceId="
    }
    object Accounts : Screen("accounts", "Accounts", Icons.Filled.People)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
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
    NavItem(Screen.Apps.BASE_ROUTE, Screen.Apps.label, Screen.Apps.icon),
    NavItem(Screen.Accounts.route, Screen.Accounts.label, Screen.Accounts.icon),
    NavItem(Screen.Settings.route, Screen.Settings.label, Screen.Settings.icon)
)

@OptIn(ExperimentalMaterial3Api::class)
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

    val showBottomBar = currentRoute?.startsWith(Screen.InstanceDetail.BASE_ROUTE) != true &&
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
                        navController.navigate(Screen.Apps.createRoute()) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onInstanceClick = { instanceId ->
                        navController.navigate(Screen.InstanceDetail.createRoute(instanceId))
                    },
                    onCreateInstance = { instanceId ->
                        navController.navigate(Screen.InstanceDetail.createRoute(instanceId))
                    }
                )
            }
            composable(
                route = Screen.Apps.route,
                arguments = listOf(
                    navArgument("instanceId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val targetInstanceId = backStackEntry.arguments?.getString("instanceId")
                    ?.takeIf { it.isNotBlank() && it != "null" }
                val homeViewModel: HomeViewModel = viewModel()
                val instances by homeViewModel.instances.collectAsState()
                val clonedPackageNames = remember(instances) {
                    instances.map { it.packageName }.toSet()
                }
                val coroutineScope = rememberCoroutineScope()

                var showDuplicateDialog by remember { mutableStateOf(false) }
                var createSheetApp by remember { mutableStateOf<Pair<String, String>?>(null) }

                if (showDuplicateDialog) {
                    AlertDialog(
                        onDismissRequest = { showDuplicateDialog = false },
                        title = { Text("App Sudah Di-clone") },
                        text = { Text("App ini sudah di-clone. Untuk clone lagi, buat instance baru.") },
                        confirmButton = {
                            TextButton(onClick = { showDuplicateDialog = false }) {
                                Text("OK")
                            }
                        }
                    )
                }

                createSheetApp?.let { (pkg, apkPath) ->
                    ModalBottomSheet(
                        onDismissRequest = { createSheetApp = null }
                    ) {
                        CreateInstanceSheetContent(
                            packageName = pkg,
                            apkPath = apkPath,
                            onDismiss = { createSheetApp = null }
                        )
                    }
                }

                if (targetInstanceId != null) {
                    androidx.activity.compose.BackHandler {
                        navController.popBackStack()
                    }
                }

                AppsScreen(
                    onSelectApp = { app ->
                        if (targetInstanceId != null) {
                            val instanceManager = com.fesu.renjana.RenjanaApplication.get().instanceManager
                            coroutineScope.launch {
                                val result = instanceManager.addAppToInstance(
                                    instanceId = targetInstanceId,
                                    packageName = app.packageName,
                                    appName = app.appName,
                                    apkPath = app.apkPath
                                )
                                result.fold(
                                    onSuccess = { navController.popBackStack() },
                                    onFailure = { e ->
                                        android.widget.Toast.makeText(
                                            navController.context,
                                            e.message ?: "Failed to add app",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }
                        } else if (app.packageName in clonedPackageNames) {
                            showDuplicateDialog = true
                        } else {
                            createSheetApp = Pair(app.packageName, app.apkPath)
                        }
                    },
                    clonedPackageNames = clonedPackageNames,
                    onNavigateBack = if (targetInstanceId != null) {
                        { navController.popBackStack() }
                    } else null
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
                    },
                    onNavigateToAddApp = { targetId ->
                        navController.navigate(Screen.Apps.createRoute(instanceId = targetId))
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
