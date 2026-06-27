package com.fesu.renjana.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fesu.renjana.RenjanaApplication
import com.fesu.renjana.models.InstanceConfig
import com.fesu.renjana.ui.viewmodels.DiagnosticsViewModel
import com.fesu.renjana.ui.viewmodels.DiagnosticsViewModelFactory
import com.fesu.renjana.ui.viewmodels.RealDeviceInfo
import kotlin.math.sin
import kotlin.math.cos

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    instanceId: String,
    onNavigateBack: () -> Unit
) {
    val application = LocalContext.current.applicationContext as android.app.Application
    val vm: DiagnosticsViewModel = viewModel(
        factory = DiagnosticsViewModelFactory(application, instanceId)
    )

    val instance by vm.instance.collectAsState()
    val realInfo by vm.realDeviceInfo.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instance Diagnostics", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (instance == null || realInfo == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val inst = instance!!
        val real = realInfo!!
        val config = inst.config

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Section 1: Real Device ──────────────────────────────────────
            DiagSectionCard(title = "Real Device") {
                DiagRow("Model", real.model)
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Brand", real.brand)
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Manufacturer", real.manufacturer)
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Android Version", real.androidVersion)
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("SDK", real.sdkInt.toString())
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Fingerprint", real.fingerprint, mono = true)
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Serial", real.serial)
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Screen DPI", "${real.screenDpi} dpi")
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Screen Resolution", "${real.screenWidthPx} × ${real.screenHeightPx} px")
            }

            // ── Section 2: Spoofed Identity ────────────────────────────────
            DiagSectionCard(title = "Spoofed Identity") {
                DiagRow("Spoof Model", config.spoofModel ?: "(not set — will use real)")
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Spoof Brand", config.spoofBrand ?: "(not set)")
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Spoof Manufacturer", config.spoofManufacturer ?: "(not set)")
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Spoof Android Version", config.spoofAndroidVersion ?: "(not set)")
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Spoof Android ID", config.spoofAndroidId ?: "(not set)", mono = true)
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Spoof Serial", config.spoofSerial ?: "(not set)")
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Screen DPI", config.screenDensityDpi?.toString() ?: "(not set)")
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Canvas Hash", config.canvasHash ?: "(not set)", mono = true)
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("Battery (mAh)", config.batteryCapacityMah?.toString() ?: "(not set)")
                Divider(modifier = Modifier.padding(start = 12.dp))
                DiagRow("WiFi MAC Prefix", config.wifiMacPrefix ?: "(not set)")
            }

            // ── Section 3: Spoof Coverage ──────────────────────────────────
            SpooFCoverageSection(config = config, real = real)

            // ── Section 4: Canvas Fingerprint Simulation ───────────────────
            CanvasFingerprintSection(config = config)

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Section 3: Coverage
// ---------------------------------------------------------------------------

private data class CoverageField(val label: String, val spoofed: Boolean, val realValue: String, val spoofValue: String?)

@Composable
private fun SpooFCoverageSection(config: InstanceConfig, real: RealDeviceInfo) {
    val fields = listOf(
        CoverageField("Model",           config.spoofModel != null,           real.model,                 config.spoofModel),
        CoverageField("Brand",           config.spoofBrand != null,           real.brand,                 config.spoofBrand),
        CoverageField("Manufacturer",    config.spoofManufacturer != null,    real.manufacturer,          config.spoofManufacturer),
        CoverageField("Android Version", config.spoofAndroidVersion != null,  real.androidVersion,        config.spoofAndroidVersion),
        CoverageField("Android ID",      config.spoofAndroidId != null,       "hidden",                   config.spoofAndroidId),
        CoverageField("Serial",          config.spoofSerial != null,          real.serial,                config.spoofSerial),
        CoverageField("Screen DPI",      config.screenDensityDpi != null,     "${real.screenDpi} dpi",    config.screenDensityDpi?.toString()),
        CoverageField("Canvas Hash",     config.canvasHash != null,           "unset",                    config.canvasHash),
        CoverageField("Battery",         config.batteryCapacityMah != null,   "unknown",                  config.batteryCapacityMah?.toString()),
        CoverageField("WiFi MAC",        config.wifiMacPrefix != null,        "unknown",                  config.wifiMacPrefix),
    )
    val spoofedCount = fields.count { it.spoofed }

    DiagSectionCard(title = "Spoof Coverage") {
        // Progress bar
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$spoofedCount / ${fields.size} fields spoofed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${(spoofedCount * 100 / fields.size)}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            LinearProgressIndicator(
                progress = spoofedCount.toFloat() / fields.size.toFloat(),
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        Divider()
        fields.forEach { field ->
            CoverageRow(field)
            if (field != fields.last()) Divider(modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun CoverageRow(field: CoverageField) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (field.spoofed) "✅" else "⚠️",
            fontSize = 16.sp
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = field.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (field.spoofed) field.spoofValue ?: "" else "Using real: ${field.realValue}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (field.spoofed) FontWeight.Medium else FontWeight.Normal,
                color = if (field.spoofed)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Section 4: Canvas Fingerprint Simulation
// ---------------------------------------------------------------------------

@Composable
private fun CanvasFingerprintSection(config: InstanceConfig) {
    val noise = config.canvasNoise ?: 0.5f
    val seed = (noise * 10000).toLong()

    DiagSectionCard(title = "Canvas Fingerprint Simulation") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Simulates the canvas fingerprint a target app would observe from this instance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // The canvas
            val primaryColor = MaterialTheme.colorScheme.primary
            val secondaryColor = MaterialTheme.colorScheme.secondary
            val tertiaryColor = MaterialTheme.colorScheme.tertiary
            val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

            Canvas(
                modifier = Modifier
                    .size(128.dp)
                    .align(Alignment.CenterHorizontally)
            ) {
                val w = size.width
                val h = size.height

                // Gradient background
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(surfaceVariant, primaryColor.copy(alpha = 0.3f)),
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    )
                )

                // Seeded geometric shapes using noise as deterministic seed
                val rng = java.util.Random(seed)
                repeat(6) {
                    val cx = rng.nextFloat() * w
                    val cy = rng.nextFloat() * h
                    val r = rng.nextFloat() * (w * 0.18f) + (w * 0.04f)
                    val color = when (rng.nextInt(3)) {
                        0 -> primaryColor.copy(alpha = 0.55f)
                        1 -> secondaryColor.copy(alpha = 0.45f)
                        else -> tertiaryColor.copy(alpha = 0.50f)
                    }
                    drawCircle(color = color, radius = r, center = Offset(cx, cy))
                }

                // Wave pattern using noise seed
                repeat(3) { i ->
                    val amplitude = (noise * 12f) + 4f
                    val freq = (seed % 5 + 2).toFloat()
                    val yBase = h * (0.3f + i * 0.2f)
                    val path = androidx.compose.ui.graphics.Path()
                    path.moveTo(0f, yBase)
                    var x = 0f
                    while (x <= w) {
                        val y = yBase + amplitude * sin(x / w * freq * Math.PI).toFloat()
                        path.lineTo(x, y)
                        x += 2f
                    }
                    drawPath(
                        path = path,
                        color = primaryColor.copy(alpha = 0.25f - i * 0.05f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                    )
                }

                // Corner rectangles as stable anchor shapes
                drawRect(
                    color = secondaryColor.copy(alpha = 0.35f),
                    topLeft = Offset(0f, 0f),
                    size = Size(w * 0.18f, h * 0.18f)
                )
                drawRect(
                    color = tertiaryColor.copy(alpha = 0.35f),
                    topLeft = Offset(w * 0.82f, h * 0.82f),
                    size = Size(w * 0.18f, h * 0.18f)
                )
            }

            // Hash display
            val displayHash = config.canvasHash ?: run {
                // Derive a deterministic hash string from seed for display when no hash is stored
                val raw = seed xor 0x5F3759DFL
                "%016X".format(raw)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Canvas Hash:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = displayHash,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "Noise seed: $noise  •  ${if (config.canvasNoise != null) "Custom" else "Default"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared composables
// ---------------------------------------------------------------------------

@Composable
private fun DiagSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Divider()
            content()
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.6f),
            maxLines = if (mono) 2 else 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}
