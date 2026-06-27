package com.fesu.renjana.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fesu.renjana.RenjanaApplication
import kotlinx.coroutines.launch
import com.fesu.renjana.ui.theme.AccentBlue
import com.fesu.renjana.ui.theme.AccentGreen
import com.fesu.renjana.ui.theme.AccentOrange
import com.fesu.renjana.ui.theme.AccentPink
import com.fesu.renjana.BuildConfig
import com.fesu.renjana.ui.theme.AccentPurple

data class AccentOption(val name: String, val color: Color)

private val accentOptions = listOf(
    AccentOption("Blue", AccentBlue),
    AccentOption("Purple", AccentPurple),
    AccentOption("Green", AccentGreen),
    AccentOption("Orange", AccentOrange),
    AccentOption("Pink", AccentPink),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    darkMode: Boolean,
    onToggleDarkMode: (Boolean) -> Unit,
    accentColor: Color = AccentBlue,
    onAccentChange: (Color) -> Unit = {},
    onNavigateToErrorLogs: () -> Unit = {}
) {
    val instanceManager = remember { RenjanaApplication.get().instanceManager }
    val instanceCount by instanceManager.getInstanceCount().collectAsState(initial = 0)
    var showClearAllDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear All Data", fontWeight = FontWeight.Bold) },
            text = { Text("This will delete ALL instances and their data. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch { instanceManager.deleteAllInstances() }
                        showClearAllDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete All") }
            },
            dismissButton = { TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) }
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {

        // ── Appearance ──
        SectionTitle("Appearance")
        SettingsCard {
            SettingsToggleRow(
                icon = Icons.Filled.Brightness6,
                title = "Dark Mode",
                subtitle = "True black OLED theme",
                checked = darkMode,
                onCheckedChange = onToggleDarkMode
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Palette, contentDescription = "Accent Color", modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Accent Color", style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    accentOptions.forEach { option ->
                        val isSelected = accentColor == option.color
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(option.color)
                                .clickable { onAccentChange(option.color) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Surface(shape = CircleShape, color = Color.White, modifier = Modifier.size(14.dp)) {}
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Storage ──
        SectionTitle("Storage")
        SettingsCard {
            SettingsInfoRow(icon = Icons.Filled.Storage, title = "Instances", value = "$instanceCount")
        }
        Spacer(modifier = Modifier.height(8.dp))
        SettingsCard {
            ClickableRow(
                icon = Icons.Filled.CleaningServices,
                title = "Clear All Data",
                subtitle = "Delete all instances and their data",
                isDanger = true,
                onClick = { showClearAllDialog = true }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Diagnostics ──
        SectionTitle("Diagnostics")
        SettingsCard {
            SettingsInfoRow(
                icon = Icons.Filled.Info,
                title = "Per-instance notifications",
                value = "Active"
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        SettingsCard {
            ClickableRow(
                icon = Icons.Filled.BugReport,
                title = "Error Logs",
                subtitle = "View crash reports and logcat captures",
                isDanger = false,
                onClick = onNavigateToErrorLogs
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── About ──
        SectionTitle("About")
        SettingsCard {
            SettingsInfoRow(icon = Icons.Filled.Info, title = "Version", value = BuildConfig.VERSION_NAME)
        }
        Spacer(modifier = Modifier.height(8.dp))
        SettingsCard {
            SettingsInfoRow(icon = Icons.Filled.Code, title = "Application", value = "Renjana Container")
        }
        Spacer(modifier = Modifier.height(8.dp))
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Created by", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("JOSSKIXG", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Renjana is an Android virtual container that enables running multiple instances of the same app with different accounts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "For educational and research purposes only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp)
    ) { Column { content() } }
}

@Composable
private fun SettingsInfoRow(icon: ImageVector, title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = title, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(16.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector, title: String, subtitle: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, contentDescription = title, modifier = Modifier.size(24.dp),
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
private fun ClickableRow(
    icon: ImageVector, title: String, subtitle: String,
    onClick: () -> Unit, isDanger: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, contentDescription = title, modifier = Modifier.size(24.dp),
            tint = if (isDanger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge,
                color = if (isDanger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
