package com.fesu.renjana.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fesu.renjana.frida.FridaManager
import com.fesu.renjana.frida.ScriptInjector
import com.fesu.renjana.utils.RenjanaLog

/**
 * UI for managing Frida hooks and scripts
 */
class HookManagerActivity : ComponentActivity() {
    companion object {
        private const val TAG = "HookManagerActivity"
        private const val EXTRA_INSTANCE_ID = "instance_id"
        private const val EXTRA_PACKAGE_NAME = "package_name"
        
        fun createIntent(context: Context, instanceId: String, packageName: String): Intent {
            return Intent(context, HookManagerActivity::class.java).apply {
                putExtra(EXTRA_INSTANCE_ID, instanceId)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }
        }
    }
    
    private lateinit var instanceId: String
    private lateinit var packageName: String
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: ""
        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        
        setContent {
            MaterialTheme {
                HookManagerScreen()
            }
        }
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun HookManagerScreen() {
        var showAddScriptDialog by remember { mutableStateOf(false) }
        var scriptContent by remember { mutableStateOf("") }
        var scriptName by remember { mutableStateOf("") }
        
        val scripts = ScriptInjector.getScripts(instanceId)
        val isGadgetActive = FridaManager.isGadgetActive(instanceId)
        
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Hook Manager") },
                    actions = {
                        IconButton(onClick = { showAddScriptDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Script")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        if (isGadgetActive) {
                            FridaManager.unloadGadget(instanceId)
                        } else {
                            FridaManager.loadGadget(instanceId)
                        }
                    }
                ) {
                    Icon(
                        if (isGadgetActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isGadgetActive) "Stop Gadget" else "Start Gadget"
                    )
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                // Gadget Status
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isGadgetActive) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (isGadgetActive) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Frida Gadget",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (isGadgetActive) "Active" else "Inactive",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                
                // Scripts List
                Text(
                    text = "Injected Scripts (${scripts.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                if (scripts.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No scripts injected",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                } else {
                    LazyColumn {
                        items(scripts) { script ->
                            ScriptCard(
                                script = script,
                                onRemove = {
                                    ScriptInjector.removeScript(instanceId, script.id)
                                }
                            )
                        }
                    }
                }
            }
        }
        
        // Add Script Dialog
        if (showAddScriptDialog) {
            AlertDialog(
                onDismissRequest = { showAddScriptDialog = false },
                title = { Text("Add Script") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = scriptName,
                            onValueChange = { scriptName = it },
                            label = { Text("Script Name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = scriptContent,
                            onValueChange = { scriptContent = it },
                            label = { Text("Script Content") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            maxLines = 10
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (scriptName.isNotBlank() && scriptContent.isNotBlank()) {
                                ScriptInjector.injectInlineScript(
                                    instanceId,
                                    scriptName,
                                    scriptContent
                                )
                                showAddScriptDialog = false
                                scriptName = ""
                                scriptContent = ""
                            }
                        }
                    ) {
                        Text("Add")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddScriptDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
    
    @Composable
    fun ScriptCard(
        script: ScriptInjector.ScriptInfo,
        onRemove: () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = script.name,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = if (script.isActive) "Active" else "Inactive",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (script.isActive) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove Script",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
