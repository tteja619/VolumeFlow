package dev.volumeflow.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.volumeflow.app.audio.VolumeFlowShizuku
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val android.content.Context.dataStore by preferencesDataStore(name = "volume_profiles")
private data class AppEntry(val label: String, val packageName: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VolumeFlowShizuku.start(this)
        setContent { VolumeFlowTheme { VolumeFlowHome() } }
    }

    override fun onResume() {
        super.onResume()
        VolumeFlowShizuku.start(this)
    }
}

@Composable
private fun VolumeFlowTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeFlowHome() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shizukuStatus by VolumeFlowShizuku.status.collectAsState()
    val engineReady = VolumeFlowShizuku.isReady()
    val apps = remember {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(intent, 0)
            .map { AppEntry(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .sortedBy { it.label.lowercase() }
    }
    val saved by context.dataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            if (key.name.startsWith("gain_") && value is Float)
                key.name.removePrefix("gain_") to value else null
        }.toMap()
    }.collectAsState(initial = emptyMap())

    LaunchedEffect(shizukuStatus, saved) {
        saved.forEach { (packageName, gain) ->
            context.getSharedPreferences("volume_overlay_profiles", android.content.Context.MODE_PRIVATE)
                .edit().putFloat(packageName, gain).apply()
            if (VolumeFlowShizuku.isReady()) {
                VolumeFlowShizuku.applyAppGain(packageName, gain)
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text("VolumeFlow", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Per-app audio control", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, actions = { IconButton(onClick = {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Icon(Icons.Rounded.Tune, contentDescription = "Volume overlay settings") } })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Audio engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(shizukuStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Button(onClick = { VolumeFlowShizuku.requestPermission() }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (engineReady) "Reconnect audio engine" else "Connect with Shizuku")
                        }
                        Text(
                            "Requires Shizuku to be installed, running, and permission granted.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Applications", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${apps.size} found", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(apps, key = { it.packageName }) { app ->
                val gain = (saved[app.packageName] ?: 1f).coerceIn(0f, 1f)
                var applied by remember(app.packageName) { mutableStateOf(false) }
                Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${(gain * 120).toInt()}/120", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                        Slider(value = gain, onValueChange = { value ->
                            val safe = value.coerceIn(0f, 1f)
                            scope.launch {
                                context.dataStore.edit { it[floatPreferencesKey("gain_${app.packageName}")] = safe }
                            }
                            context.getSharedPreferences("volume_overlay_profiles", android.content.Context.MODE_PRIVATE)
                                .edit().putFloat(app.packageName, safe).apply()
                            applied = VolumeFlowShizuku.applyAppGain(app.packageName, safe)
                        }, valueRange = 0f..1f, steps = 119)
                        Text(
                            when {
                                applied -> "120-step gain sent to active playback sessions"
                                engineReady -> "Profile saved. Waiting for this app to play audio."
                                else -> "Profile saved. Connect Shizuku to control audio."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
