package com.khouch.tv.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.data.prefs.DEFAULT_SERVER_URL
import com.khouch.tv.ui.theme.KhouchColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class ServerUrlViewModel(private val repo: KhouchRepository) : ViewModel() {
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    fun save(url: String, onDone: () -> Unit) {
        _status.value = "Saving…"
        viewModelScope.launch {
            runCatching {
                repo.setServerUrl(url)
                repo.probeHealth()
            }.onSuccess {
                _status.value = null
                onDone()
            }.onFailure { e ->
                _status.value = "Couldn't reach $url — ${e.message ?: "unknown error"}"
            }
        }
    }
}

@Composable
fun ServerUrlScreen(onContinue: () -> Unit) {
    val vm: ServerUrlViewModel = koinViewModel()
    val status by vm.status.collectAsState()
    var url by remember { mutableStateOf(DEFAULT_SERVER_URL) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = KhouchColors.Bg,
            contentColor = KhouchColors.Fg,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(64.dp), contentAlignment = Alignment.Center) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Khouch Potato",
                    color = KhouchColors.Accent,
                    fontSize = 36.sp,
                )
                Text(
                    text = "Connect to your server",
                    color = KhouchColors.FgDim,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(24.dp))
                BasicTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    singleLine = true,
                    textStyle = TextStyle(color = KhouchColors.Fg, fontSize = 20.sp),
                    cursorBrush = SolidColor(KhouchColors.Accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        imeAction = ImeAction.Done,
                    ),
                    // Pressing the IME's Done key (✓) submits directly so
                    // users don't have to fight Compose-for-TV's focus
                    // model to D-pad off the BasicTextField onto the
                    // Continue button. The TV keyboard is the only
                    // dismount path that works reliably.
                    keyboardActions = KeyboardActions(
                        onDone = { vm.save(url) { onContinue() } },
                    ),
                    modifier = Modifier
                        .width(560.dp)
                        .background(KhouchColors.Bg3)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .focusRequester(focus),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.save(url) { onContinue() } }) {
                    Text("Continue", modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
                status?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(text = it, color = KhouchColors.FgDim, fontSize = 13.sp)
                }
            }
        }
    }
}
