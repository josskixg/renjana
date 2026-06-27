// Inset pattern: Use Scaffold + TopAppBar, apply only `.padding(padding)` from Scaffold to content.
// Do NOT add manual statusBarsPadding — Scaffold+TopAppBar already handles status bar inset.

package com.fesu.renjana.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fesu.renjana.ui.components.AppIcon
import com.fesu.renjana.ui.viewmodels.CreateInstanceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateInstanceScreen(
    onNavigateBack: () -> Unit,
    packageName: String = "",
    apkPath: String = "",
    viewModel: CreateInstanceViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val pkg by viewModel.packageName.collectAsState()
    val path by viewModel.apkPath.collectAsState()
    val appName by viewModel.appName.collectAsState()
    val isCreating by viewModel.isCreating.collectAsState()
    val creationSuccess by viewModel.creationSuccess.collectAsState()
    val error by viewModel.error.collectAsState()

    val enableGms by viewModel.enableGms.collectAsState()
    val enableFingerprint by viewModel.enableFingerprint.collectAsState()
    val spoofSignature by viewModel.spoofSignature.collectAsState()
    val enableAntiDetection by viewModel.enableAntiDetection.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(packageName, apkPath) {
        if (packageName.isNotBlank() && apkPath.isNotBlank()) {
            viewModel.prefill(packageName, apkPath)
        }
    }

    LaunchedEffect(creationSuccess) {
        if (creationSuccess) {
            viewModel.resetSuccess()
            onNavigateBack()
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Instance") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App summary card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pkg.isNotBlank()) {
                        AppIcon(packageName = pkg, size = 56.dp)
                    } else {
                        Icon(
                            Icons.Filled.PhoneAndroid,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = appName.ifBlank { "Unknown App" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = pkg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Text(
                text = "Configuration",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Config toggles
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column {
                    ConfigToggleRow(
                        title = "Google Services (GMS)",
                        subtitle = "Intercept Google Sign-In, Firebase, Play Billing",
                        checked = enableGms,
                        onCheckedChange = { viewModel.updateEnableGms(it) }
                    )
                    Divider()
                    ConfigToggleRow(
                        title = "Spoof Signature",
                        subtitle = "Hide the original APK signature",
                        checked = spoofSignature,
                        onCheckedChange = { viewModel.updateSpoofSignature(it) }
                    )
                    Divider()
                    ConfigToggleRow(
                        title = "Anti-Detection",
                        subtitle = "Prevent detection by target app",
                        checked = enableAntiDetection,
                        onCheckedChange = { viewModel.updateEnableAntiDetection(it) }
                    )
                    Divider()
                    ConfigToggleRow(
                        title = "Fingerprint Spoofing",
                        subtitle = "Randomize device fingerprint",
                        checked = enableFingerprint,
                        onCheckedChange = { viewModel.updateEnableFingerprint(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { viewModel.createInstance() },
                modifier = Modifier.fillMaxWidth(),
                enabled = pkg.isNotBlank() && path.isNotBlank() && !isCreating,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Create Instance")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateInstanceSheetContent(
    packageName: String,
    apkPath: String,
    onDismiss: () -> Unit,
    viewModel: CreateInstanceViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val pkg by viewModel.packageName.collectAsState()
    val path by viewModel.apkPath.collectAsState()
    val appName by viewModel.appName.collectAsState()
    val isCreating by viewModel.isCreating.collectAsState()
    val creationSuccess by viewModel.creationSuccess.collectAsState()
    val error by viewModel.error.collectAsState()

    val enableGms by viewModel.enableGms.collectAsState()
    val enableFingerprint by viewModel.enableFingerprint.collectAsState()
    val spoofSignature by viewModel.spoofSignature.collectAsState()
    val enableAntiDetection by viewModel.enableAntiDetection.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(packageName, apkPath) {
        if (packageName.isNotBlank() && apkPath.isNotBlank()) {
            viewModel.prefill(packageName, apkPath)
        }
    }

    LaunchedEffect(creationSuccess) {
        if (creationSuccess) {
            viewModel.resetSuccess()
            onDismiss()
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Sheet drag handle label
        Text(
            text = "Create Instance",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        // App summary card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pkg.isNotBlank()) {
                    AppIcon(packageName = pkg, size = 56.dp)
                } else {
                    Icon(
                        Icons.Filled.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = appName.ifBlank { "Unknown App" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = pkg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Config toggles
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column {
                ConfigToggleRow(
                    title = "Enable GMS",
                    subtitle = "Google Mobile Services support",
                    checked = enableGms,
                    onCheckedChange = { viewModel.updateEnableGms(it) }
                )
                Divider()
                ConfigToggleRow(
                    title = "Spoof Signature",
                    subtitle = "Spoof app signature for compatibility",
                    checked = spoofSignature,
                    onCheckedChange = { viewModel.updateSpoofSignature(it) }
                )
                Divider()
                ConfigToggleRow(
                    title = "Anti-Detection",
                    subtitle = "Hide cloned app from detection",
                    checked = enableAntiDetection,
                    onCheckedChange = { viewModel.updateEnableAntiDetection(it) }
                )
                Divider()
                ConfigToggleRow(
                    title = "Spoof Fingerprint",
                    subtitle = "Randomize device fingerprint",
                    checked = enableFingerprint,
                    onCheckedChange = { viewModel.updateEnableFingerprint(it) }
                )
            }
        }

        Button(
            onClick = { viewModel.createInstance() },
            modifier = Modifier.fillMaxWidth(),
            enabled = pkg.isNotBlank() && path.isNotBlank() && !isCreating,
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isCreating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Create Instance")
            }
        }

        // Bottom spacing for navigation bar
        Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun ConfigToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
