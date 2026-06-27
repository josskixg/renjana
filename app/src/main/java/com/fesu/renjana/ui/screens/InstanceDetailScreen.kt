package com.fesu.renjana.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Layers
import com.fesu.renjana.core.InstanceShortcutManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.core.InstanceState
import com.fesu.renjana.core.DeviceDatabase
import com.fesu.renjana.database.InstanceAppEntity
import com.fesu.renjana.ui.components.AppIcon
import com.fesu.renjana.ui.components.Haptics
import com.fesu.renjana.ui.components.RunningIndicator
import com.fesu.renjana.ui.components.rememberHaptics
import com.fesu.renjana.ui.theme.StatusRunning
import com.fesu.renjana.ui.theme.StatusPaused
import com.fesu.renjana.ui.theme.StatusIdle
import com.fesu.renjana.ui.viewmodels.InstanceDetailViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InstanceDetailViewModelFactory(
    private val instanceId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return InstanceDetailViewModel(instanceId) as T
    }
}

enum class DetailTab(val label: String, val icon: ImageVector) {
    OVERVIEW("Overview", Icons.Filled.Info),
    APPS("Apps", Icons.Filled.Apps),
    CONFIG("Config", Icons.Filled.Settings),
    DEVICE("Device", Icons.Filled.PhoneAndroid),
    DANGER("Danger", Icons.Filled.Warning)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceDetailScreen(
    instanceId: String,
    onNavigateBack: () -> Unit,
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToAddApp: (instanceId: String) -> Unit = {},
    viewModel: InstanceDetailViewModel = viewModel(
        factory = InstanceDetailViewModelFactory(instanceId)
    )
) {
    val instance by viewModel.instance.collectAsState()
    val error by viewModel.error.collectAsState()
    val actionSuccess by viewModel.actionSuccess.collectAsState()
    val isDeleted by viewModel.isDeleted.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showCustomizeDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(DetailTab.OVERVIEW) }
    val haptics = rememberHaptics()
    var shortcutMessage by remember { mutableStateOf<String?>(null) }

    // Poll running state
    var runningState by remember { mutableStateOf<InstanceState>(InstanceState.IDLE) }
    LaunchedEffect(instanceId) {
        while (true) {
            runningState = RenjanaApplication.get().lifecycleService
                ?.getInstanceState(instanceId) ?: InstanceState.IDLE
            kotlinx.coroutines.delay(2000)
        }
    }

    LaunchedEffect(isDeleted) { if (isDeleted) onNavigateBack() }
    LaunchedEffect(error) {
        error?.let { snackbarHostState.showSnackbar(it); viewModel.clearError() }
    }
    LaunchedEffect(actionSuccess) {
        actionSuccess?.let { snackbarHostState.showSnackbar(it); viewModel.clearActionSuccess() }
    }
    LaunchedEffect(shortcutMessage) {
        shortcutMessage?.let { snackbarHostState.showSnackbar(it); shortcutMessage = null }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Instance", fontWeight = FontWeight.Bold) },
            text = { Text("This will permanently delete this instance and all its data. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteDialog = false; viewModel.deleteInstance() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Data", fontWeight = FontWeight.Bold) },
            text = { Text("This will clear all data for this instance (files, cache, preferences). Continue?") },
            confirmButton = {
                TextButton(onClick = { showClearDialog = false; viewModel.clearData() }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Cancel") } }
        )
    }
    // ── Customize Dialog (color + emoji) ──────────────────────────────────
    if (showCustomizeDialog) {
        val presetColors = listOf(
            "#F44336" to "Red",
            "#FF5722" to "Orange",
            "#FFC107" to "Amber",
            "#4CAF50" to "Green",
            "#009688" to "Teal",
            "#2196F3" to "Blue",
            "#3F51B5" to "Indigo",
            "#9C27B0" to "Purple",
            "#E91E63" to "Pink",
            "#607D8B" to "Blue Grey",
            "#795548" to "Brown",
            "#FF9800" to "Deep Orange"
        )
        var pendingColor by remember { mutableStateOf(instance?.config?.instanceColor) }
        var pendingEmoji by remember { mutableStateOf(instance?.config?.instanceEmoji ?: "") }
        AlertDialog(
            onDismissRequest = { showCustomizeDialog = false },
            title = { Text("Customize Icon", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Color grid
                    Text(
                        "Accent Color",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(6),
                        modifier = Modifier.height(80.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(presetColors) { (hex, name) ->
                            val selected = pendingColor == hex
                            val parsedColor = try {
                                androidx.compose.ui.graphics.Color(
                                    android.graphics.Color.parseColor(hex)
                                )
                            } catch (e: Exception) { MaterialTheme.colorScheme.primary }
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(parsedColor)
                                    .then(
                                        if (selected) Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.onSurface,
                                            CircleShape
                                        ) else Modifier
                                    )
                                    .clickable { pendingColor = hex },
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Text(
                                        "✓",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        // "None" option
                        item {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .then(
                                        if (pendingColor == null) Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.onSurface,
                                            CircleShape
                                        ) else Modifier
                                    )
                                    .clickable { pendingColor = null },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "✕",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // Emoji input
                    Text(
                        "Label / Emoji (max 2 chars)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = pendingEmoji,
                        onValueChange = { if (it.length <= 2) pendingEmoji = it },
                        placeholder = { Text("e.g. 2 or 🔥") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateVisualConfig(pendingColor, pendingEmoji)
                    showCustomizeDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomizeDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showRenameDialog) {
        var editName by remember { mutableStateOf(instance?.appName ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = editName, onValueChange = { editName = it },
                    label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editName.isNotBlank()) viewModel.renameInstance(editName.trim())
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val inst = instance
        if (inst == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Hero Header with gradient ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 4.dp,
                color = androidx.compose.ui.graphics.Color.Transparent
            ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to MaterialTheme.colorScheme.primaryContainer,
                                0.6f to MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                                1f to MaterialTheme.colorScheme.background
                            )
                        )
                    )
                    .padding(24.dp)
                    .padding(bottom = 16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Row {
                            IconButton(onClick = {
                                val pinned = InstanceShortcutManager.requestPinShortcut(context, inst)
                                shortcutMessage = if (pinned) "Shortcut added to homescreen"
                                                  else "Homescreen shortcuts not supported on this launcher"
                            }) {
                                Icon(Icons.Filled.Home, contentDescription = "Add to homescreen")
                            }
                            IconButton(onClick = { showRenameDialog = true }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Rename")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(
                            packageName = inst.packageName,
                            size = 72.dp,
                            showRenjanaBadge = true,
                            instanceColor = inst.config.instanceColor,
                            instanceEmoji = inst.config.instanceEmoji
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = inst.appName,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                RunningIndicator(
                                    isRunning = runningState == InstanceState.RUNNING,
                                    isPaused = runningState == InstanceState.PAUSED,
                                    size = 9.dp
                                )
                            }
                            Text(
                                text = inst.packageName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "v${inst.versionName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = runningState.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelMedium,
                                color = when (runningState) {
                                    InstanceState.RUNNING -> MaterialTheme.colorScheme.primary
                                    InstanceState.PAUSED -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    if (runningState == InstanceState.RUNNING || runningState == InstanceState.PAUSED) {
                        Button(
                            onClick = { haptics.confirm(); viewModel.stopInstance() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Stop", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        Button(
                            onClick = { haptics.confirm(); viewModel.launchInstance() },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Launch Instance", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            } // end Surface

            // ── Tab Bar ──
            val tabs = DetailTab.values()
            ScrollableTabRow(
                selectedTabIndex = tabs.indexOf(selectedTab),
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                divider = {}
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                        icon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        selectedContentColor = if (tab == DetailTab.DANGER) MaterialTheme.colorScheme.error
                                               else MaterialTheme.colorScheme.primary,
                        unselectedContentColor = if (tab == DetailTab.DANGER) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                                                 else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Tab Content ──
            when (selectedTab) {
                DetailTab.OVERVIEW -> {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // ── Customize Card ────────────────────────────────
                        SectionCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showCustomizeDialog = true }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Color preview circle
                                    val accentColor = inst.config.instanceColor?.let {
                                        try {
                                            androidx.compose.ui.graphics.Color(
                                                android.graphics.Color.parseColor(it)
                                            )
                                        } catch (e: Exception) { null }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(
                                                accentColor ?: MaterialTheme.colorScheme.surfaceVariant
                                            )
                                            .then(
                                                if (accentColor == null) Modifier.border(
                                                    1.dp,
                                                    MaterialTheme.colorScheme.outline,
                                                    CircleShape
                                                ) else Modifier
                                            )
                                    )
                                    Column {
                                        Text(
                                            "Customize",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            buildString {
                                                val c = inst.config.instanceColor
                                                val e = inst.config.instanceEmoji
                                                if (c != null) append(c)
                                                if (c != null && !e.isNullOrBlank()) append("  ·  ")
                                                if (!e.isNullOrBlank()) append(e)
                                                if (c == null && e.isNullOrBlank()) append("No color or label set")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (!inst.config.instanceEmoji.isNullOrBlank()) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = inst.config.instanceEmoji!!,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }
                        SectionCard {
                            InfoRow("Created", dateFormat.format(Date(inst.createdAt)))
                            Divider(modifier = Modifier.padding(start = 16.dp))
                            InfoRow("Last Used", dateFormat.format(Date(inst.lastUsed)))
                            Divider(modifier = Modifier.padding(start = 16.dp))
                            InfoRow("Version", "${inst.versionName} (${inst.versionCode})")
                            Divider(modifier = Modifier.padding(start = 16.dp))
                            InfoRow("Instance ID", inst.id)
                            Divider(modifier = Modifier.padding(start = 16.dp))
                            InfoRow("Data Path", inst.dataPath, maxLines = 2)
                        }
                        FilledTonalButton(
                            onClick = onNavigateToDiagnostics,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Run Diagnostics")
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
                DetailTab.APPS -> {
                    val instanceApps by viewModel.instanceApps.collectAsState()
                    var pendingRemovePackage by remember { mutableStateOf<String?>(null) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        instanceApps.forEach { app ->
                            ListItem(
                                headlineContent = { Text(app.appName) },
                                supportingContent = {
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                leadingContent = {
                                    AppIcon(
                                        packageName = app.packageName,
                                        size = 44.dp,
                                        showRenjanaBadge = false
                                    )
                                },
                                trailingContent = {
                                    Row {
                                        IconButton(onClick = { viewModel.launchApp(app.packageName) }) {
                                            Icon(Icons.Filled.PlayArrow, contentDescription = "Launch")
                                        }
                                        IconButton(onClick = { pendingRemovePackage = app.packageName }) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = "Remove",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            )
                            Divider()
                        }
                        OutlinedButton(
                            onClick = { onNavigateToAddApp(inst.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add App")
                        }
                        pendingRemovePackage?.let { pkg ->
                            AlertDialog(
                                onDismissRequest = { pendingRemovePackage = null },
                                title = { Text("Remove App") },
                                text = { Text("Remove $pkg from this instance?") },
                                confirmButton = {
                                    TextButton(
                                        onClick = { viewModel.removeApp(pkg); pendingRemovePackage = null },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) { Text("Remove") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingRemovePackage = null }) { Text("Cancel") }
                                }
                            )
                        }
                    }
                }
                DetailTab.CONFIG -> {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SectionCard {
                            ConfigToggleRow(
                                icon = Icons.Filled.CloudOff,
                                title = "Google Services (GMS)",
                                subtitle = "Intercept Google Sign-In, Firebase, Play Billing",
                                checked = inst.config.enableGms,
                                onCheckedChange = { viewModel.toggleGms(it) }
                            )
                            Divider(modifier = Modifier.padding(start = 16.dp))
                            ConfigToggleRow(
                                icon = Icons.Filled.VerifiedUser,
                                title = "Spoof Signature",
                                subtitle = "Hide the original APK signature",
                                checked = inst.config.spoofSignature,
                                onCheckedChange = { viewModel.toggleSpoofSignature(it) }
                            )
                            Divider(modifier = Modifier.padding(start = 16.dp))
                            ConfigToggleRow(
                                icon = Icons.Filled.Security,
                                title = "Anti-Detection",
                                subtitle = "Prevent detection by target app",
                                checked = inst.config.enableAntiDetection,
                                onCheckedChange = { viewModel.toggleAntiDetection(it) }
                            )
                            Divider(modifier = Modifier.padding(start = 16.dp))
                            ConfigToggleRow(
                                icon = Icons.Filled.Fingerprint,
                                title = "Fingerprint Spoofing",
                                subtitle = "Randomize device fingerprint",
                                checked = inst.config.enableFingerprint,
                                onCheckedChange = { viewModel.toggleFingerprint(it) }
                            )
                        }

                        // ── Quick Switch Bubble ──────────────────────────────
                        val context = LocalContext.current
                        var overlayGranted by remember {
                            mutableStateOf(Settings.canDrawOverlays(context))
                        }
                        SectionCard {
                            ConfigToggleRow(
                                icon = Icons.Outlined.Layers,
                                title = "Quick switch bubble",
                                subtitle = if (overlayGranted)
                                    "Floating bubble to switch between running instances"
                                else
                                    "Tap to grant overlay permission first",
                                checked = overlayGranted &&
                                    RenjanaApplication.get().bubbleService != null,
                                onCheckedChange = { enabled ->
                                    if (!Settings.canDrawOverlays(context)) {
                                        // Send user to the system overlay permission screen
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        context.startActivity(intent)
                                    } else {
                                        overlayGranted = true
                                        if (enabled) {
                                            com.fesu.renjana.core.QuickSwitchBubbleService
                                                .start(context)
                                        } else {
                                            com.fesu.renjana.core.QuickSwitchBubbleService
                                                .stop(context)
                                        }
                                    }
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
                DetailTab.DEVICE -> {
                    DeviceSpoofSection(
                        instance = inst,
                        viewModel = viewModel
                    )
                }
                DetailTab.DANGER -> {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SectionCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                            DangerRow(
                                icon = Icons.Filled.Clear,
                                title = "Clear Data",
                                subtitle = "Reset files, cache, and preferences",
                                actionText = "Clear",
                                onClick = { showClearDialog = true },
                                textColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Divider(modifier = Modifier.padding(start = 16.dp))
                            DangerRow(
                                icon = Icons.Filled.Delete,
                                title = "Delete Instance",
                                subtitle = "Permanently remove this instance",
                                actionText = "Delete",
                                onClick = { showDeleteDialog = true },
                                textColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) { Column { content() } }
}

@Composable
private fun InfoRow(label: String, value: String, maxLines: Int = 1) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value, style = MaterialTheme.typography.bodyMedium, maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(start = 16.dp)
        )
    }
}

@Composable
private fun ConfigToggleRow(
    icon: ImageVector, title: String, subtitle: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, contentDescription = null, modifier = Modifier.size(24.dp),
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DangerRow(
    icon: ImageVector, title: String, subtitle: String,
    actionText: String, onClick: () -> Unit,
    textColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = textColor)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = textColor)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.7f))
        }
        TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = textColor)) {
            Text(actionText)
        }
    }
}

@Composable
private fun DeviceSpoofSection(
    instance: com.fesu.renjana.models.Instance,
    viewModel: InstanceDetailViewModel
) {
    val config = instance.config
    var model by remember(config.spoofModel) { mutableStateOf(config.spoofModel ?: "") }
    var brand by remember(config.spoofBrand) { mutableStateOf(config.spoofBrand ?: "") }
    var manufacturer by remember(config.spoofManufacturer) { mutableStateOf(config.spoofManufacturer ?: "") }
    var androidVersion by remember(config.spoofAndroidVersion) { mutableStateOf(config.spoofAndroidVersion ?: "") }
    var androidId by remember(config.spoofAndroidId) { mutableStateOf(config.spoofAndroidId ?: "") }
    var serial by remember(config.spoofSerial) { mutableStateOf(config.spoofSerial ?: "") }

    // Extended fingerprint state
    var canvasHash by remember(config.canvasHash) { mutableStateOf(config.canvasHash ?: "") }
    var canvasNoise by remember(config.canvasNoise) { mutableStateOf(config.canvasNoise?.toString() ?: "") }
    var screenDensityDpi by remember(config.screenDensityDpi) { mutableStateOf(config.screenDensityDpi?.toString() ?: "") }
    var screenWidthDp by remember(config.screenWidthDp) { mutableStateOf(config.screenWidthDp?.toString() ?: "") }
    var screenHeightDp by remember(config.screenHeightDp) { mutableStateOf(config.screenHeightDp?.toString() ?: "") }
    var screenRefreshRate by remember(config.screenRefreshRate) { mutableStateOf(config.screenRefreshRate?.toString() ?: "") }
    var sensorAccel by remember(config.sensorAccelerometer) { mutableStateOf(config.sensorAccelerometer ?: true) }
    var sensorGyro by remember(config.sensorGyroscope) { mutableStateOf(config.sensorGyroscope ?: false) }
    var sensorMag by remember(config.sensorMagnetometer) { mutableStateOf(config.sensorMagnetometer ?: false) }
    var sensorBaro by remember(config.sensorBarometer) { mutableStateOf(config.sensorBarometer ?: false) }
    var sensorProx by remember(config.sensorProximity) { mutableStateOf(config.sensorProximity ?: true) }
    var batteryCapacity by remember(config.batteryCapacityMah) { mutableStateOf(config.batteryCapacityMah?.toString() ?: "") }
    var wifiMac by remember(config.wifiMacPrefix) { mutableStateOf(config.wifiMacPrefix ?: "") }

    var extendedExpanded by remember { mutableStateOf(false) }

    val randomizedProfile by viewModel.randomizedProfile.collectAsState()
    val isFetchingDevice by viewModel.isFetchingDevice.collectAsState()

    // Auto-populate from DeviceRepository if fingerprint enabled and fields empty
    LaunchedEffect(config.enableFingerprint) {
        if (config.enableFingerprint && model.isBlank() && brand.isBlank()) {
            viewModel.randomizeDeviceProfile()
        }
    }

    // When ViewModel returns a randomized profile, populate the fields
    LaunchedEffect(randomizedProfile) {
        randomizedProfile?.let { p ->
            model = p.model
            brand = p.brand
            manufacturer = p.manufacturer
            androidVersion = p.androidVersion
            androidId = p.androidId
            serial = p.serial
            // Also populate extended fingerprint from DeviceDatabase
            val profile = DeviceDatabase.profiles.firstOrNull {
                it.model == p.model || it.brand.equals(p.brand, ignoreCase = true)
            }
            if (profile != null) {
                val seed = p.androidId.hashCode().toLong()
                val screenSpecs = DeviceDatabase.generateScreenSpecs(profile, seed)
                val sensorProfile = DeviceDatabase.generateSensorProfile(profile, seed)
                canvasHash = DeviceDatabase.generateCanvasHash(seed)
                canvasNoise = ((seed % 100).toFloat() / 1000f).toString()
                screenDensityDpi = screenSpecs.dpi.toString()
                screenWidthDp = screenSpecs.widthDp.toString()
                screenHeightDp = screenSpecs.heightDp.toString()
                screenRefreshRate = screenSpecs.refreshRate.toString()
                sensorAccel = sensorProfile.accelerometer
                sensorGyro = sensorProfile.gyroscope
                sensorMag = sensorProfile.magnetometer
                sensorBaro = sensorProfile.barometer
                sensorProx = sensorProfile.proximity
                batteryCapacity = DeviceDatabase.generateBatteryCapacity(profile, seed).toString()
                wifiMac = DeviceDatabase.generateWifiMacPrefix(profile.brand, seed)
            }
            viewModel.clearRandomizedProfile()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Randomize from thousands of real device profiles via Tadiphone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { viewModel.randomizeDeviceProfile() },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                enabled = !isFetchingDevice
            ) {
                if (isFetchingDevice) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Randomize", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        SectionCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SpoofTextField("Device Model", model, "e.g., Galaxy S23") { model = it }
                SpoofTextField("Brand", brand, "e.g., Samsung") { brand = it }
                SpoofTextField("Manufacturer", manufacturer, "e.g., Samsung") { manufacturer = it }
                SpoofTextField("Android Version", androidVersion, "e.g., 14") { androidVersion = it }
                SpoofTextField("Android ID", androidId, "16-char hex (auto-generated)") { androidId = it }
                SpoofTextField("Serial Number", serial, "e.g., RX8N400XXXXX") { serial = it }
            }
        }

        Button(
            onClick = {
                viewModel.updateDeviceSpoof(
                    model = model, brand = brand, manufacturer = manufacturer,
                    androidVersion = androidVersion, androidId = androidId, serial = serial
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Save Device Identity", fontWeight = FontWeight.SemiBold)
        }

        // ── Extended Fingerprint Section ──────────────────────────────────
        Divider(modifier = Modifier.padding(vertical = 4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { extendedExpanded = !extendedExpanded }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Extended Fingerprint",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (extendedExpanded) "▲" else "▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (extendedExpanded) {
            SectionCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    // Canvas
                    Text("Canvas", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    SpoofTextField("Canvas Hash (32-char hex)", canvasHash, "e.g., a3f1...") { canvasHash = it }
                    SpoofTextField("Canvas Noise (0.000–0.099)", canvasNoise, "e.g., 0.042") { canvasNoise = it }

                    Divider()

                    // Screen
                    Text("Screen", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    SpoofTextField("Density DPI", screenDensityDpi, "e.g., 440") { screenDensityDpi = it }
                    SpoofTextField("Width DP", screenWidthDp, "e.g., 392") { screenWidthDp = it }
                    SpoofTextField("Height DP", screenHeightDp, "e.g., 848") { screenHeightDp = it }
                    SpoofTextField("Refresh Rate (Hz)", screenRefreshRate, "e.g., 60.0") { screenRefreshRate = it }

                    Divider()

                    // Sensors
                    Text("Sensors", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    SensorToggleRow("Accelerometer", sensorAccel) { sensorAccel = it }
                    SensorToggleRow("Gyroscope", sensorGyro) { sensorGyro = it }
                    SensorToggleRow("Magnetometer", sensorMag) { sensorMag = it }
                    SensorToggleRow("Barometer", sensorBaro) { sensorBaro = it }
                    SensorToggleRow("Proximity", sensorProx) { sensorProx = it }

                    Divider()

                    // Battery & Network
                    Text("Battery & Network", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    SpoofTextField("Battery Capacity (mAh)", batteryCapacity, "e.g., 4500") { batteryCapacity = it }
                    SpoofTextField("WiFi MAC Prefix", wifiMac, "e.g., AC:37:43") { wifiMac = it }
                }
            }

            Button(
                onClick = {
                    val updatedConfig = config.copy(
                        canvasHash = canvasHash.ifBlank { null },
                        canvasNoise = canvasNoise.toFloatOrNull(),
                        screenDensityDpi = screenDensityDpi.toIntOrNull(),
                        screenWidthDp = screenWidthDp.toIntOrNull(),
                        screenHeightDp = screenHeightDp.toIntOrNull(),
                        screenRefreshRate = screenRefreshRate.toFloatOrNull(),
                        sensorAccelerometer = sensorAccel,
                        sensorGyroscope = sensorGyro,
                        sensorMagnetometer = sensorMag,
                        sensorBarometer = sensorBaro,
                        sensorProximity = sensorProx,
                        batteryCapacityMah = batteryCapacity.toIntOrNull(),
                        wifiMacPrefix = wifiMac.ifBlank { null },
                    )
                    viewModel.updateExtendedFingerprint(updatedConfig)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Save Extended Identity", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SensorToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SpoofTextField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            textStyle = MaterialTheme.typography.bodyMedium
        )
    }
}
