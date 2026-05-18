package com.khouch.tv.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.khouch.tv.data.profile.Portraits
import com.khouch.tv.data.model.Profile
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.ui.theme.KhouchColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class ProfilePickerViewModel(private val repo: KhouchRepository) : ViewModel() {
    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles = _profiles.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _serverUrl = MutableStateFlow("")
    val serverUrl = _serverUrl.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _serverUrl.value = repo.currentServerUrl()
            runCatching { repo.listProfiles() }
                .onSuccess { _profiles.value = it.profiles; _error.value = null }
                .onFailure { _error.value = "Couldn't load profiles — ${it.message}" }
        }
    }

    fun pick(profile: Profile, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.selectProfile(profile.id) }
                .onSuccess { onDone() }
                .onFailure { _error.value = "Couldn't select ${profile.nick} — ${it.message}" }
        }
    }
}

@Composable
fun ProfilePickerScreen(onPicked: () -> Unit) {
    val vm: ProfilePickerViewModel = koinViewModel()
    val profiles by vm.profiles.collectAsState()
    val error by vm.error.collectAsState()
    val serverUrl by vm.serverUrl.collectAsState()
    LaunchedEffect(Unit) { vm.load() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = KhouchColors.Bg,
            contentColor = KhouchColors.Fg,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(64.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Text("Who's watching?", color = KhouchColors.Fg, fontSize = 36.sp)
                Spacer(Modifier.height(16.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    contentPadding = PaddingValues(horizontal = 32.dp),
                ) {
                    items(profiles, key = { it.id }) { p ->
                        ProfileTile(profile = p, serverUrl = serverUrl, onClick = { vm.pick(p, onPicked) })
                    }
                }
                error?.let { Text(it, color = KhouchColors.Accent, fontSize = 13.sp) }
                if (profiles.isEmpty() && error == null) {
                    Text("Loading profiles…", color = KhouchColors.FgDim)
                }
            }
        }
    }
}

@Composable
private fun ProfileTile(profile: Profile, serverUrl: String, onClick: () -> Unit) {
    val thisYear = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    val kidAge = profile.kidsBirthYear?.let { thisYear - it }?.takeIf { it in 0..17 }
    // The TV repo has its own Profile model (separate from core's),
    // so we can't pass it to Portraits.resolve(profile) directly.
    // Do the equivalent resolve here: honor the chosen portrait id
    // if it's valid, otherwise hash the nick deterministically.
    val portraitId = remember(profile.id, profile.avatar, profile.nick) {
        profile.avatar?.takeIf { it in Portraits.IDS }
            ?: Portraits.pickForNick(profile.nick)
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = KhouchColors.Bg2,
            contentColor = KhouchColors.Fg,
            focusedContainerColor = KhouchColors.Bg3,
        ),
        modifier = Modifier.width(160.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            // Outer Box for the photo + kid badge overlay. Identical
            // size for every profile — earlier the "Kid · 12" text
            // sat below the photo and shifted the vertical rhythm so
            // kid tiles looked visually larger. Now the kid marker
            // is a small badge overlaid on the photo's top-right
            // corner; tile heights are uniform across the row.
            Box(modifier = Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .clip(CircleShape)
                        .background(KhouchColors.Bg3),
                    contentAlignment = Alignment.Center,
                ) {
                    if (serverUrl.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data("${serverUrl.trimEnd('/')}/portraits/$portraitId.svg")
                                .build(),
                            contentDescription = profile.nick,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(profile.nick.take(1).uppercase(), fontSize = 48.sp)
                    }
                }
                if (kidAge != null) {
                    // Pill in the bottom-right of the photo. Two
                    // lines would make it grow vertically — kept the
                    // age compact ("Kid 12") so it reads at-a-glance
                    // without dominating the portrait.
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(KhouchColors.Accent, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            "Kid $kidAge",
                            color = KhouchColors.Bg,
                            fontSize = 10.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                    }
                }
            }
            Text(profile.nick, color = KhouchColors.Fg, fontSize = 16.sp)
        }
    }
}
