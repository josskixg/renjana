package com.fesu.renjana.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fesu.renjana.models.AppInfo
import com.fesu.renjana.ui.components.AppIcon
import com.fesu.renjana.ui.components.EmptyStateIllustration
import com.fesu.renjana.ui.components.Haptics
import com.fesu.renjana.ui.components.PressableCard
import com.fesu.renjana.ui.components.ShimmerInstanceCard
import com.fesu.renjana.ui.components.StaggeredEntrance
import com.fesu.renjana.ui.components.rememberHaptics
import com.fesu.renjana.ui.viewmodels.AppsViewModel

enum class AppViewMode { LIST, GRID }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(
    viewModel: AppsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onSelectApp: (AppInfo) -> Unit = {},
    clonedPackageNames: Set<String> = emptySet(),
    onNavigateBack: (() -> Unit)? = null
) {
    if (onNavigateBack != null) {
        BackHandler { onNavigateBack.invoke() }
    }

    val apps by viewModel.apps.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var viewMode by remember { mutableStateOf(AppViewMode.LIST) }
    val haptics = rememberHaptics()

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apps", fontWeight = FontWeight.Bold) },
                navigationIcon = if (onNavigateBack != null) {
                    {
                        IconButton(onClick = { onNavigateBack.invoke() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                } else ({}),
                actions = {
                    IconButton(onClick = {
                        haptics.tick()
                        viewMode = if (viewMode == AppViewMode.LIST) AppViewMode.GRID else AppViewMode.LIST
                    }) {
                        Icon(
                            if (viewMode == AppViewMode.LIST) Icons.Filled.ViewModule else Icons.Filled.ViewList,
                            contentDescription = if (viewMode == AppViewMode.LIST) "Grid view" else "List view"
                        )
                    }
                    IconButton(onClick = { haptics.tick(); viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
    ) {
        // Search
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Search apps...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary
            )
        )

        // Content
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading -> {
                    Column { repeat(5) { ShimmerInstanceCard() } }
                }
                error != null -> {
                    Text(
                        text = "Error: $error",
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
                filteredApps.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        EmptyStateIllustration(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "No apps match your search" else "No apps found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (searchQuery.isBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Install apps on your device to clone them",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                viewMode == AppViewMode.LIST -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(filteredApps, key = { _, it -> it.packageName }) { index, app ->
                            val isCloned = clonedPackageNames.contains(app.packageName)
                            StaggeredEntrance(index = index) {
                                AppListItem(
                                    app = app,
                                    isCloned = isCloned,
                                    onClick = { haptics.tap(); onSelectApp(app) }
                                )
                            }
                        }
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val isCloned = clonedPackageNames.contains(app.packageName)
                            StaggeredEntrance(index = filteredApps.indexOf(app)) {
                                AppGridItem(
                                    app = app,
                                    isCloned = isCloned,
                                    onClick = { haptics.tap(); onSelectApp(app) }
                                )
                            }
                        }
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }
}
}
@Composable
fun AppListItem(app: AppInfo, isCloned: Boolean, onClick: () -> Unit) {
    PressableCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                AppIcon(packageName = app.packageName, size = 44.dp)
                if (isCloned) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.BottomEnd).size(16.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Cloned",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "v${app.versionName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 11.sp
                )
            }
            if (isCloned) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "Cloned",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun AppGridItem(app: AppInfo, isCloned: Boolean, onClick: () -> Unit) {
    PressableCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                AppIcon(packageName = app.packageName, size = 52.dp)
                if (isCloned) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.BottomEnd).size(16.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Cloned",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = app.appName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "v${app.versionName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontSize = 11.sp
            )
        }
    }
}
