package com.khouch.phone.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.khouch.core.data.profile.Portraits
import com.khouch.core.data.model.Profile
import com.khouch.core.data.repo.KhouchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class SettingsViewModel(
    private val repo: KhouchRepository,
    private val downloads: com.khouch.core.data.downloads.DownloadsRepo,
) : ViewModel() {
    val activeProfile = repo.profile
    val downloadCount = downloads.items
    val downloadBytes = downloads.totalBytes

    private val _serverUrl = MutableStateFlow("")
    val serverUrl = _serverUrl.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _serverUrl.value = repo.currentServerUrl()
        }
    }

    fun signOut(onDone: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            runCatching { repo.logout() }
            _busy.value = false
            onDone()
        }
    }
}

@Composable
fun SettingsScreen(
    onSwitchProfile: () -> Unit,
    onChangeServer: () -> Unit,
    onDownloads: () -> Unit,
    onSignedOut: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = koinViewModel()
    val active by vm.activeProfile.collectAsState()
    val serverUrl by vm.serverUrl.collectAsState()
    val busy by vm.busy.collectAsState()
    val downloadCount by vm.downloadCount.collectAsState()
    val downloadBytes by vm.downloadBytes.collectAsState()
    LaunchedEffect(Unit) { vm.load() }
    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Profile section
        active?.let { p ->
            Section("Profile") {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (serverUrl.isNotBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(Portraits.urlFor(serverUrl, p))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = p.nick,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                p.nick.take(1).uppercase(),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.nick,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Active profile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ListRow(Icons.Default.Person, "Switch profile", onClick = onSwitchProfile)
            }
        }

        // Downloads section — count + total size live; tap opens
        // the full DownloadsScreen with per-item delete + delete-all.
        Section("Downloads") {
            ListRow(
                icon = Icons.Default.Download,
                label = if (downloadCount.isEmpty()) "No downloads yet"
                        else "${downloadCount.size} items · " +
                             com.khouch.phone.ui.downloads.formatBytes(downloadBytes),
                onClick = onDownloads,
            )
        }

        // Server section
        Section("Server") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "URL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    serverUrl.ifBlank { "(not set)" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            ListRow(Icons.Default.Settings, "Change server", onClick = onChangeServer)
        }

        // Account section
        Section("Account") {
            ListRow(
                icon = Icons.Default.Logout,
                label = if (busy) "Signing out…" else "Sign out",
                tint = MaterialTheme.colorScheme.error,
                enabled = !busy,
                onClick = { vm.signOut(onSignedOut) },
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Khouch · phone client",
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun ListRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
        )
    }
}
