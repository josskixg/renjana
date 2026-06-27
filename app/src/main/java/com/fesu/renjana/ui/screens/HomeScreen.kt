package com.fesu.renjana.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.core.InstanceState
import com.fesu.renjana.database.InstanceAppEntity
import com.fesu.renjana.models.Instance
import com.fesu.renjana.ui.components.AppIcon
import com.fesu.renjana.ui.components.EmptyStateIllustration
import com.fesu.renjana.ui.components.Haptics
import com.fesu.renjana.ui.components.PressableCard
import com.fesu.renjana.ui.components.RunningIndicator
import com.fesu.renjana.ui.components.ShimmerInstanceCard
import com.fesu.renjana.ui.components.StaggeredEntrance
import com.fesu.renjana.ui.components.StatHeader
import com.fesu.renjana.ui.components.rememberHaptics
import com.fesu.renjana.ui.viewmodels.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HomeViewMode { LIST, GRID }

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onNavigateToApps: () -> Unit = {},
    onInstanceClick: (String) -> Unit = {},
    onCreateInstance: (String) -> Unit = {}
) {
    val instances by viewModel.instances.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val createdInstanceId by viewModel.createdInstanceId.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var viewMode by remember { mutableStateOf(HomeViewMode.LIST) }
    var editMode by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    // Track scroll state for list
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Poll running instances
    var runningStates by remember { mutableStateOf<Map<String, InstanceState>>(emptyMap()) }
    LaunchedEffect(Unit) {
        while (true) {
            runningStates = RenjanaApplication.get().lifecycleService?.getRunningInstances()?.associate {
                it.instanceId to it.state
            } ?: emptyMap()
            kotlinx.coroutines.delay(2000)
        }
    }

    val runningCount = runningStates.values.count { it == InstanceState.RUNNING || it == InstanceState.PAUSED }
    val haptics = rememberHaptics()

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Navigate to new container after creation
    LaunchedEffect(createdInstanceId) {
        createdInstanceId?.let { id ->
            onCreateInstance(id)
            viewModel.clearCreatedInstanceId()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Instances", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        haptics.tick()
                        viewMode = if (viewMode == HomeViewMode.LIST) HomeViewMode.GRID else HomeViewMode.LIST
                    }) {
                        Icon(
                            if (viewMode == HomeViewMode.LIST) Icons.Filled.ViewModule else Icons.Filled.ViewList,
                            contentDescription = if (viewMode == HomeViewMode.LIST) "Grid view" else "List view"
                        )
                    }
                    if (instances.isNotEmpty()) {
                        IconButton(onClick = {
                            haptics.tick()
                            editMode = !editMode
                        }) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Edit",
                                tint = if (editMode) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!editMode) {
                FloatingActionButton(
                    onClick = {
                        haptics.tap()
                        showCreateDialog = true
                    },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Add instance"
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            // Stats
            if (instances.isNotEmpty() || isLoading) {
                StatHeader(
                    totalInstances = instances.size,
                    runningCount = runningCount
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Content
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    isLoading && instances.isEmpty() -> {
                        Column { repeat(4) { ShimmerInstanceCard() } }
                    }
                    instances.isEmpty() -> {
                        EmptyStateHome(onNavigateToApps = {
                            haptics.tap()
                            onNavigateToApps()
                        })
                    }
                    viewMode == HomeViewMode.LIST -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(instances, key = { _, it -> it.id }) { index, instance ->
                                val state = runningStates[instance.id]
                                StaggeredEntrance(index = index) {
                                    InstanceListCard(
                                        instance = instance,
                                        isRunning = state == InstanceState.RUNNING,
                                        isPaused = state == InstanceState.PAUSED,
                                        editMode = editMode,
                                        viewModel = viewModel,
                                        onClick = {
                                            haptics.tap()
                                            if (editMode) editMode = false
                                            onInstanceClick(instance.id)
                                        },
                                        onStop = {
                                            viewModel.stopInstance(instance.id)
                                        },
                                        onDelete = {
                                            haptics.reject()
                                            pendingDeleteId = instance.id
                                        }
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
                            items(instances, key = { it.id }) { instance ->
                                val state = runningStates[instance.id]
                                StaggeredEntrance(index = instances.indexOf(instance)) {
                                    InstanceGridCard(
                                        instance = instance,
                                        isRunning = state == InstanceState.RUNNING,
                                        isPaused = state == InstanceState.PAUSED,
                                        editMode = editMode,
                                        onClick = {
                                            haptics.tap()
                                            if (editMode) editMode = false
                                            onInstanceClick(instance.id)
                                        },
                                        onStop = {
                                            viewModel.stopInstance(instance.id)
                                        },
                                        onDelete = {
                                            haptics.reject()
                                            pendingDeleteId = instance.id
                                        }
                                    )
                                }
                            }
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                                Spacer(modifier = Modifier.height(80.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Create Container Dialog
    if (showCreateDialog) {
        CreateContainerDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                viewModel.createContainer(name)
                showCreateDialog = false
            }
        )
    }

    // Delete Instance Confirmation Dialog
    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete Instance") },
            text = { Text("This will permanently delete the instance and all its data. Continue?") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteInstance(id); pendingDeleteId = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CreateContainerDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var containerName by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = {
            onDismiss()
            containerName = ""
            nameError = null
        },
        title = { Text("New Container", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "A container holds multiple apps with isolated storage and accounts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = containerName,
                    onValueChange = {
                        containerName = it
                        nameError = null
                    },
                    label = { Text("Container name") },
                    placeholder = { Text("e.g. Work, Gaming, Shopping") },
                    isError = nameError != null,
                    supportingText = nameError?.let { err -> { Text(err, color = MaterialTheme.colorScheme.error) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (containerName.isBlank()) {
                        nameError = "Name cannot be empty"
                    } else {
                        onCreate(containerName.trim())
                        containerName = ""
                    }
                }
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismiss()
                containerName = ""
            }) { Text("Cancel") }
        }
    )
}

@Composable
private fun EmptyStateHome(onNavigateToApps: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        EmptyStateIllustration(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "No instances yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Clone your favorite apps and run multiple accounts",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        FilledTonalButton(
            onClick = onNavigateToApps,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create Instance")
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun InstanceListCard(
    instance: Instance,
    isRunning: Boolean,
    isPaused: Boolean,
    editMode: Boolean,
    viewModel: HomeViewModel,
    onClick: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val scale by animateFloatAsState(if (editMode) 0.97f else 1f, label = "cardScale")
    val instanceApps by viewModel.getAppsForInstance(instance.id).collectAsState(initial = emptyList())

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .combinedClickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (editMode) 0.dp else 1.dp),
        border = if (editMode) androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ) else null
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mini app grid — up to 4 icons
                Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.BottomEnd) {
                    when {
                        instanceApps.isEmpty() -> {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Add app",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                        instanceApps.size == 1 -> {
                            AppIcon(
                                packageName = instanceApps[0].packageName,
                                size = 52.dp,
                                showRenjanaBadge = true,
                                instanceColor = instance.config.instanceColor,
                                instanceEmoji = instance.config.instanceEmoji
                            )
                        }
                        else -> {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                modifier = Modifier.size(52.dp),
                                userScrollEnabled = false
                            ) {
                                items(instanceApps.take(4)) { app ->
                                    AppIcon(
                                        packageName = app.packageName,
                                        size = 24.dp,
                                        showRenjanaBadge = false
                                    )
                                }
                            }
                            if (instanceApps.size > 4) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(18.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+${instanceApps.size - 4}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 8.sp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = instance.appName.ifBlank { instance.appName },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isRunning || isPaused) {
                            Spacer(modifier = Modifier.width(6.dp))
                            RunningIndicator(
                                isRunning = isRunning,
                                isPaused = isPaused,
                                size = 7.dp
                            )
                        }
                    }
                    Text(
                        text = if (instanceApps.isEmpty()) "No apps added"
                               else instanceApps.joinToString(", ", limit = 2) { it.appName },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = dateFormat.format(Date(instance.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 11.sp
                    )
                }
                if (!editMode) {
                    if (isRunning || isPaused) {
                        IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = "Stop",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
            if (editMode) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clickable(onClick = onDelete)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun InstanceGridCard(
    instance: Instance,
    isRunning: Boolean,
    isPaused: Boolean,
    editMode: Boolean,
    onClick: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit
) {
    val scale by animateFloatAsState(if (editMode) 0.97f else 1f, label = "gridScale")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .combinedClickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (editMode) 0.dp else 1.dp),
        border = if (editMode) androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ) else null
    ) {
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box {
                    AppIcon(
                        packageName = instance.packageName,
                        size = 52.dp,
                        instanceColor = instance.config.instanceColor,
                        instanceEmoji = instance.config.instanceEmoji
                    )
                    if (isRunning || isPaused) {
                        RunningIndicator(
                            isRunning = isRunning,
                            isPaused = isPaused,
                            size = 8.dp,
                            modifier = Modifier.align(Alignment.BottomEnd)
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .size(16.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "R",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 8.sp,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = instance.appName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!editMode) {
                    Spacer(modifier = Modifier.height(6.dp))
                    if (isRunning || isPaused) {
                        OutlinedButton(
                            onClick = onStop,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop", fontSize = 11.sp)
                        }
                    }
                }
            }
            if (editMode) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clickable(onClick = onDelete)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
