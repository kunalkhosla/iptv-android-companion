package com.khouch.phone.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khouch.core.data.repo.KhouchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ServerUrlViewModel(private val repo: KhouchRepository) : ViewModel() {
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    fun save(url: String, onDone: () -> Unit) {
        _status.value = "Connecting…"
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

class LoginViewModel(private val repo: KhouchRepository) : ViewModel() {
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun login(user: String, pass: String, onSuccess: () -> Unit) {
        if (user.isBlank() || pass.isBlank()) { _status.value = "Enter username and password"; return }
        _busy.value = true; _status.value = null
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
