package com.khouch.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.ui.theme.KhouchColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class SettingsViewModel(private val repo: KhouchRepository) : ViewModel() {
    val profile = repo.profile
    val userState = repo.userState
    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()

    fun pickTheme(name: String) {
        viewModelScope.launch {
            runCatching { repo.setTheme(name) }
                .onSuccess { _toast.value = "Theme updated" }
                .onFailure { _toast.value = "Couldn't save theme" }
        }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.logout() }
            onDone()
        }
    }
}

private data class Theme(val key: String, val label: String, val color: Color)

private val THEMES = listOf(
    Theme("netflix", "Netflix Red", Color(0xFFE50914)),
    Theme("hulu", "Hulu Green", Color(0xFF1CE783)),
    Theme("disney", "Disney+ Blue", Color(0xFF0063E5)),
)

@Composable
fun SettingsScreen(
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = koinViewModel()
    val profile by vm.profile.collectAsState()
    val userState by vm.userState.collectAsState()
    val toast by vm.toast.collectAsState()
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
        Row(modifier = Modifier.fillMaxSize().padding(64.dp)) {
            Column(modifier = Modifier.width(400.dp)) {
                Text("Settings", color = KhouchColors.Fg, fontSize = 28.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Signed in as ${profile?.nick ?: "—"}",
                    color = KhouchColors.FgDim,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(32.dp))

                SectionHeader("Profile")
                SettingsAction(
                    label = "Switch profile",
                    focusRequester = firstFocus,
                    onClick = onSwitchProfile,
                )
                Spacer(Modifier.height(8.dp))
                SettingsAction(label = "Sign out", onClick = { vm.signOut(onSignOut) })

                Spacer(Modifier.height(32.dp))
                SectionHeader("Theme")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (t in THEMES) {
                        ThemeChip(
                            theme = t,
                            selected = userState.theme == t.key,
                            onClick = { vm.pickTheme(t.key) },
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))

                SectionHeader("Server")
                Text(
                    "Theme + state sync to your iptv-webui server",
                    color = KhouchColors.FgDim,
                    fontSize = 12.sp,
                )

                toast?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, color = KhouchColors.Accent, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.width(64.dp))

            Column(modifier = Modifier.fillMaxHeight().fillMaxWidth()) {
                Text("Your library", color = KhouchColors.FgDim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                StatRow("Favorites", userState.favorites.values.sumOf { it.size })
                StatRow("My List", userState.myList.values.sumOf { it.size })
                StatRow("Watched", userState.watched.size)
                StatRow("Recently played", userState.recents.values.sumOf { it.size })
            }
        }
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
private fun ThemeChip(theme: Theme, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = KhouchColors.Bg2,
            contentColor = KhouchColors.Fg,
            focusedContainerColor = KhouchColors.Bg3,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp).width(96.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(theme.color),
            )
            Spacer(Modifier.height(8.dp))
            Text(theme.label, fontSize = 11.sp, color = KhouchColors.Fg)
            if (selected) {
                Spacer(Modifier.height(2.dp))
                Text("✓ active", fontSize = 10.sp, color = KhouchColors.Accent)
            }
        }
    }
}

@Composable
private fun StatRow(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = KhouchColors.Fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(count.toString(), color = KhouchColors.FgDim, fontSize = 14.sp)
    }
}
