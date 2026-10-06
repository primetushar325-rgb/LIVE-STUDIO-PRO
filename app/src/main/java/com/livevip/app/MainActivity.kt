package com.livevip.app

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livevip.app.core.MasterClock
import com.livevip.app.core.StreamState
import com.livevip.app.data.AppSettings
import com.livevip.app.data.ProfileRepository
import com.livevip.app.data.SecureKeyStore
import com.livevip.app.data.StreamProfile
import com.livevip.app.engine.LiveStreamingEngine
import com.livevip.app.gl.LiveCompositor
import com.livevip.app.service.LiveStreamingForegroundService
import com.livevip.app.ui.DashboardScreen
import com.livevip.app.ui.DiagnosticsScreen
import com.livevip.app.ui.EditProfileScreen
import com.livevip.app.ui.HomeScreen
import com.livevip.app.ui.LiveVipTheme
import com.livevip.app.ui.SettingsScreen
import com.livevip.app.video.PlaybackEvents
import com.livevip.app.video.VideoSourceController

/**
 * UI only. The Activity never owns the streaming engine — the foreground
 * service does, so destroying this Activity cannot stop a live broadcast,
 * and reopening it re-attaches to the real engine state (timer included).
 */
class MainActivity : ComponentActivity() {

    private sealed interface Screen {
        data object Home : Screen
        data class Edit(val profile: StreamProfile) : Screen
        data class Dashboard(val profileId: String) : Screen
        data object Diagnostics : Screen
        data object Settings : Screen
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        val repo = ProfileRepository.get(this)
        val engine = LiveStreamingEngine.get(this)
        val settingsStore = AppSettings.get(this)

        setContent {
            val settings by settingsStore.state.collectAsStateWithLifecycle()
            LiveVipTheme(settings) {
                Surface(Modifier) {
                    AppRoot(repo, engine, settingsStore)
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
            permissions += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    @Composable
    private fun AppRoot(
        repo: ProfileRepository,
        engine: LiveStreamingEngine,
        settingsStore: AppSettings
    ) {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        var validationError by remember { mutableStateOf<String?>(null) }
        val profiles by repo.profiles.collectAsStateWithLifecycle()
        val state by engine.state.collectAsStateWithLifecycle()
        val stats by engine.stats.collectAsStateWithLifecycle()
        val logs by engine.logs.collectAsStateWithLifecycle()
        val rtmpDiag by engine.rtmpDiagnostics.collectAsStateWithLifecycle()
        val settings by settingsStore.state.collectAsStateWithLifecycle()
        val context = this

        when (val current = screen) {
            is Screen.Home -> HomeScreen(
                profiles = profiles,
                onCreate = {
                    screen = Screen.Edit(
                        StreamProfile(
                            bitrateKbps = settings.defaultBitrateKbps,
                            fps = settings.defaultFps
                        )
                    )
                },
                onOpen = { validationError = null; screen = Screen.Dashboard(it.id) },
                onEdit = { screen = Screen.Edit(it) },
                onDuplicate = { repo.duplicate(it.id) },
                onDelete = { repo.delete(it.id) },
                onDiagnostics = { screen = Screen.Diagnostics },
                onSettings = { screen = Screen.Settings }
            )

            is Screen.Edit -> EditProfileScreen(
                initial = current.profile,
                initialKey = repo.streamKey(current.profile.id),
                probe = { uri -> probeVideo(uri) },
                displayNameOf = { uri -> displayName(context, uri) },
                onSave = { profile, key ->
                    repo.upsert(profile)
                    repo.setStreamKey(profile.id, key.trim())
                    validationError = null
                    screen = Screen.Dashboard(profile.id)
                },
                onCancel = { screen = Screen.Home }
            )

            is Screen.Dashboard -> {
                val profile = repo.get(current.profileId)
                if (profile == null) {
                    screen = Screen.Home
                } else {
                    // Preview only runs when we are not live; while live the engine
                    // is already rendering the real pipeline into the same surface.
                    LaunchedEffect(profile.id, state) {
                        if (!state.isActive) engine.startPreview(profile)
                    }
                    DashboardScreen(
                        profile = profile,
                        engine = engine,
                        state = state,
                        stats = stats,
                        maskedKey = SecureKeyStore.mask(repo.streamKey(profile.id)),
                        validationError = validationError,
                        onBack = {
                            if (!state.isActive) engine.stopPreview()
                            screen = Screen.Home
                        },
                        onEdit = { screen = Screen.Edit(profile) },
                        onStartLive = {
                            val key = repo.streamKey(profile.id)
                            val error = engine.validate(profile, key)
                            if (error != null) {
                                validationError = "${error.code}: ${error.detail}"
                            } else {
                                validationError = null
                                engine.stopPreview()
                                LiveStreamingForegroundService.start(context, profile.id)
                            }
                        },
                        onStopLive = { LiveStreamingForegroundService.stop(context) },
                        onDiagnostics = { screen = Screen.Diagnostics }
                    )
                }
            }

            is Screen.Diagnostics -> DiagnosticsScreen(
                stats = stats,
                rtmp = rtmpDiag,
                logs = logs,
                onBack = { screen = Screen.Home }
            )

            is Screen.Settings -> SettingsScreen(
                settings = settings,
                onChange = { transform -> settingsStore.update(transform) },
                onDiagnostics = { screen = Screen.Diagnostics },
                onBack = { screen = Screen.Home }
            )
        }
    }

    private fun probeVideo(uriString: String): Triple<Int, Int, Long>? {
        val controller = VideoSourceController(
            this, LiveCompositor(), MasterClock(), object : PlaybackEvents {}
        )
        return runCatching { controller.probe(Uri.parse(uriString)) }.getOrNull()
    }

    private fun displayName(context: Context, uriString: String): String {
        val uri = Uri.parse(uriString)
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: (uri.lastPathSegment ?: "video")
    }

    override fun onDestroy() {
        super.onDestroy()
        val engine = LiveStreamingEngine.get(this)
        if (engine.state.value == StreamState.IDLE || engine.state.value == StreamState.STOPPED) {
            engine.stopPreview()
        }
    }
}
