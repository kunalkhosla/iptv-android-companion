package com.khouch.phone.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel

@Composable
fun ServerUrlScreen(onContinue: () -> Unit) {
    val vm: ServerUrlViewModel = koinViewModel()
    val status by vm.status.collectAsState()
    var url by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold { pv ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pv)
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Khouch Potato", style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("Enter your server address", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                label = { Text("Server URL  e.g. https://khouch.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { vm.save(url) { onContinue() } }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = { vm.save(url) { onContinue() } }, modifier = Modifier.fillMaxWidth()) {
                Text(if (status == "Connecting…") "Connecting…" else "Continue")
            }
            status?.let { msg ->
                if (msg != "Connecting…") {
                    Spacer(Modifier.height(12.dp))
                    Text(msg, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
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

    Scaffold { pv ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pv)
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Sign in", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { passFocus.requestFocus() }),
                modifier = Modifier.fillMaxWidth().focusRequester(userFocus),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (!busy) vm.login(user, pass, onSuccess) }),
                modifier = Modifier.fillMaxWidth().focusRequester(passFocus),
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { if (!busy) vm.login(user, pass, onSuccess) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(if (busy) "Signing in…" else "Sign in")
            }
            status?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onChangeServer) { Text("Change server") }
        }
    }
}
