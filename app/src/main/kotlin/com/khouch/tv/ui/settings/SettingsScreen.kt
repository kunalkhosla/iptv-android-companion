package com.khouch.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import com.khouch.tv.BuildConfig
import com.khouch.tv.data.api.ApiFactory
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.ui.theme.KhouchColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

class SettingsViewModel(
    private val repo: KhouchRepository,
    private val userPrefs: com.khouch.tv.data.prefs.UserPrefs,
    private val apiFactory: ApiFactory,
    private val appContext: android.content.Context,
) : ViewModel() {
    val profile = repo.profile
    val audioMode = userPrefs.audioMode
    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()
    private val _cacheSize = MutableStateFlow("…")
    val cacheSize = _cacheSize.asStateFlow()
    private val _serverSha = MutableStateFlow<String?>(null)
    val serverSha = _serverSha.asStateFlow()
    private val _serverUrl = MutableStateFlow<String>("")
    val serverUrl = _serverUrl.asStateFlow()

    init {
        viewModelScope.launch {
            _serverUrl.value = userPrefs.serverUrlOnce()
            refreshCacheSize()
            runCatching { repo.probeHealth() }.onSuccess { _serverSha.value = it.sha }
        }
    }

    fun pickAudioMode(mode: String) {
        viewModelScope.launch {
            runCatching { userPrefs.setAudioMode(mode) }
                .onSuccess {
                    _toast.value = if (mode == "surround")
                        "Surround 5.1 (E-AC3) — takes effect on next playback"
                    else
                        "Stereo (AAC) — takes effect on next playback"
                }
                .onFailure { _toast.value = "Couldn't save audio mode" }
        }
    }

    fun clearRecents() {
        viewModelScope.launch {
            runCatching { repo.clearAllRecents() }
                .onSuccess { _toast.value = "Continue Watching cleared" }
                .onFailure { _toast.value = "Couldn't clear — ${it.message}" }
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSize.value = withContext(Dispatchers.IO) {
                val coilBytes = runCatching {
                    SingletonImageLoader.get(appContext).diskCache?.size ?: 0L
                }.getOrDefault(0L)
                val okhttpBytes = runCatching { apiFactory.httpCache.size() }.getOrDefault(0L)
                formatBytes(coilBytes + okhttpBytes)
            }
        }
    }

    fun clearCaches() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { SingletonImageLoader.get(appContext).diskCache?.clear() }
                runCatching { apiFactory.httpCache.evictAll() }
            }
            _toast.value = "Image + HTTP caches cleared"
            refreshCacheSize()
        }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.logout() }
            onDone()
        }
    }

    companion object {
        fun formatBytes(b: Long): String = when {
            b < 1024 -> "$b B"
            b < 1024 * 1024 -> "${b / 1024} KB"
            b < 1024 * 1024 * 1024 -> "${b / (1024 * 1024)} MB"
            else -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
        }
    }
}

@Composable
fun SettingsScreen(
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = koinViewModel()
    val profile by vm.profile.collectAsState()
    val audioMode by vm.audioMode.collectAsState(initial = "stereo")
    val toast by vm.toast.collectAsState()
    val cacheSize by vm.cacheSize.collectAsState()
    val serverSha by vm.serverSha.collectAsState()
    val serverUrl by vm.serverUrl.collectAsState()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    BackHandler { onBack() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = SurfaceDefaults.colors(
            containerColor = KhouchColors.Bg,
            contentColor = KhouchColors.Fg,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 64.dp, vertical = 48.dp)
                .verticalScroll(rememberScrollState())
                .width(560.dp),
        ) {
            Text("Settings", color = KhouchColors.Fg, fontSize = 28.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Signed in as ${profile?.nick ?: "—"}",
                color = KhouchColors.FgDim,
                fontSize = 12.sp,
            )

            // ── Playback ──
            Spacer(Modifier.height(32.dp))
            SectionHeader("Playback")
            Text(
                "Stereo (AAC 192k) is the safe default — works everywhere. " +
                    "Surround (E-AC3) targets AVRs over HDMI for 5.1/7.1; " +
                    "some titles with exotic source audio (DTS-HD MA / TrueHD) " +
                    "may go silent on Surround — flip back to Stereo if so.",
                color = KhouchColors.FgDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AudioChip(
                    label = "Surround 5.1",
                    selected = audioMode == "surround",
                    onClick = { vm.pickAudioMode("surround") },
                )
                AudioChip(
                    label = "Stereo",
                    selected = audioMode == "stereo",
                    onClick = { vm.pickAudioMode("stereo") },
                )
            }

            // ── Account ──
            Spacer(Modifier.height(32.dp))
            SectionHeader("Account")
            SettingsAction(
                label = "Switch profile",
                focusRequester = firstFocus,
                onClick = onSwitchProfile,
            )
            Spacer(Modifier.height(8.dp))
            SettingsAction(label = "Sign out", onClick = { vm.signOut(onSignOut) })

            // ── Privacy ──
            Spacer(Modifier.height(32.dp))
            SectionHeader("Privacy")
            Text(
                "Wipes Continue Watching (recents + saved positions) " +
                    "for the current profile across all clients (web + TV).",
                color = KhouchColors.FgDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            SettingsAction(
                label = "Clear Continue Watching",
                onClick = { vm.clearRecents() },
            )

            // ── Storage ──
            Spacer(Modifier.height(32.dp))
            SectionHeader("Storage")
            Text(
                "On-disk caches: image bitmaps (Coil) + HTTP responses (OkHttp). " +
                    "Clearing forces a fresh download next time you browse.",
                color = KhouchColors.FgDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            SettingsAction(
                label = "Clear cache · $cacheSize",
                onClick = { vm.clearCaches() },
            )

            // ── About ──
            Spacer(Modifier.height(32.dp))
            SectionHeader("About")
            InfoLine("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoLine("Server", serverUrl.ifBlank { "—" })
            InfoLine("Server build", serverSha ?: "—")
            InfoLine("Profile", profile?.nick ?: "—")

            toast?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = KhouchColors.Accent, fontSize = 12.sp)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(label, color = KhouchColors.FgDim, fontSize = 12.sp, modifier = Modifier.width(120.dp))
        Text(value, color = KhouchColors.Fg, fontSize = 12.sp)
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label.uppercase(),
        color = KhouchColors.FgDim,
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SettingsAction(
    label: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = KhouchColors.Bg2,
            contentColor = KhouchColors.Fg,
            focusedContainerColor = KhouchColors.Bg3,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
    ) {
        Text(
            label,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun AudioChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = if (selected) KhouchColors.Accent else KhouchColors.Bg2,
            contentColor = if (selected) Color.White else KhouchColors.Fg,
            focusedContainerColor = if (selected) KhouchColors.Accent else KhouchColors.Bg3,
            focusedContentColor = Color.White,
        ),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

