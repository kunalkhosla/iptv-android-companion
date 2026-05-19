package com.khouch.phone.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.khouch.core.data.model.Profile
import com.khouch.core.data.profile.Portraits
import com.khouch.core.data.repo.KhouchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class ProfilePickerViewModel(private val repo: KhouchRepository) : ViewModel() {
    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles = _profiles.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _picking = MutableStateFlow<String?>(null)
    val picking = _picking.asStateFlow()
    private val _serverUrl = MutableStateFlow("")
    val serverUrl = _serverUrl.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _serverUrl.value = repo.currentServerUrl()
            runCatching { repo.listProfiles() }
                .onSuccess { _profiles.value = it.profiles; _error.value = null }
                .onFailure { _error.value = it.message }
        }
    }

    fun pick(profile: Profile, onDone: () -> Unit) {
        if (_picking.value != null) return
        _picking.value = profile.id
        _error.value = null
        viewModelScope.launch {
            runCatching { repo.selectProfile(profile.id) }
                .onSuccess { _picking.value = null; onDone() }
                .onFailure { _picking.value = null; _error.value = it.message ?: "Failed" }
        }
    }
}

@Composable
fun ProfilePickerScreen(onPicked: () -> Unit) {
    val vm: ProfilePickerViewModel = koinViewModel()
    val profiles by vm.profiles.collectAsState()
    val error by vm.error.collectAsState()
    val picking by vm.picking.collectAsState()
    val serverUrl by vm.serverUrl.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Who's watching?",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(48.dp))
            when {
                profiles.isEmpty() && error == null -> CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary)
                else -> {
                    // Two-column grid wrapping vertically. Avoids the
                    // horizontal-scroll trap where 4+ profiles got cut
                    // off on the right edge. Up to 8 profiles fit
                    // without any scroll; more than that scrolls
                    // vertically along with the rest of the page.
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        for (p in profiles) {
                            ProfileAvatar(p, serverUrl, picking == p.id) { vm.pick(p, onPicked) }
                        }
                    }
                }
            }
            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ProfileAvatar(
    profile: Profile,
    serverUrl: String,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val portraitUrl = remember(profile, serverUrl) {
        if (serverUrl.isBlank()) null else Portraits.urlFor(serverUrl, profile)
    }
    Column(
        Modifier.width(88.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            onClick = onClick,
            enabled = !loading,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(72.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                } else if (portraitUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(portraitUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = profile.nick,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(profile.nick.take(1), fontSize = 36.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(profile.nick,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 1)
    }
}
