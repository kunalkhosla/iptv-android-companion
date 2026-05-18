package com.khouch.tv.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.khouch.tv.data.repo.KhouchRepository
import com.khouch.tv.ui.theme.KhouchColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class LoginViewModel(private val repo: KhouchRepository) : ViewModel() {
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun login(user: String, pass: String, onSuccess: () -> Unit) {
        if (user.isBlank() || pass.isBlank()) {
            _status.value = "Enter username and password"
            return
        }
        _busy.value = true
        _status.value = null
        viewModelScope.launch {
            runCatching { repo.login(user, pass) }
                .onSuccess {
                    _busy.value = false
                    if (it.ok) onSuccess()
                    else _status.value = it.error ?: "Wrong username or password"
                }
                .onFailure { e ->
                    _busy.value = false
                    _status.value = "Login failed — ${e.message ?: "unknown error"}"
                }
        }
    }
}

@Composable
fun LoginScreen(onSuccess: () -> Unit, onChangeServer: () -> Unit) {
    val vm: LoginViewModel = koinViewModel()
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { userFocus.requestFocus() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = KhouchColors.Bg,
            contentColor = KhouchColors.Fg,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(64.dp), contentAlignment = Alignment.Center) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Sign in", color = KhouchColors.Fg, fontSize = 28.sp)
                Spacer(Modifier.height(16.dp))

                BasicTextField(
                    value = user,
                    onValueChange = { user = it },
                    singleLine = true,
                    textStyle = TextStyle(color = KhouchColors.Fg, fontSize = 20.sp),
                    cursorBrush = SolidColor(KhouchColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    // Pressing IME Next (→|) jumps to the password field
                    // without forcing a D-pad escape that Compose-for-TV
                    // doesn't reliably route off a focused BasicTextField.
                    keyboardActions = KeyboardActions(
                        onNext = { runCatching { passFocus.requestFocus() } },
                    ),
                    modifier = Modifier
                        .width(420.dp)
                        .background(KhouchColors.Bg3)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .focusRequester(userFocus),
                )
                Text("Username", color = KhouchColors.FgDim, fontSize = 11.sp)

                Spacer(Modifier.height(4.dp))

                BasicTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    textStyle = TextStyle(color = KhouchColors.Fg, fontSize = 20.sp),
                    cursorBrush = SolidColor(KhouchColors.Accent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    // Pressing "Done" on the IME submits — otherwise the
                    // user has to fight Compose-for-TV's focus model to
                    // tab off the text field to reach the button.
                    keyboardActions = KeyboardActions(
                        onDone = { if (!busy) vm.login(user, pass, onSuccess) },
                    ),
                    modifier = Modifier
                        .width(420.dp)
                        .background(KhouchColors.Bg3)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .focusRequester(passFocus),
                )
                Text("Password", color = KhouchColors.FgDim, fontSize = 11.sp)

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { if (!busy) vm.login(user, pass, onSuccess) },
                ) {
                    Text(
                        text = if (busy) "Signing in…" else "Sign in",
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                    )
                }
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(text = it, color = KhouchColors.Accent, fontSize = 13.sp)
                }

                Spacer(Modifier.height(24.dp))
                Button(onClick = onChangeServer) {
                    Text(
                        "Change server URL",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
