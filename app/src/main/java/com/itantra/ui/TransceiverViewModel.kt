package com.itantra.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.core.TransceiverService
import com.itantra.core.TransceiverState
import com.itantra.network.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel that binds to [TransceiverService] and exposes reactive StateFlows
 * for UI consumption in [MainActivity].
 */
class TransceiverViewModel : ViewModel() {

    private val _isBound = MutableStateFlow(false)
    val isBound: StateFlow<Boolean> = _isBound.asStateFlow()

    private val _binder = MutableStateFlow<TransceiverService.LocalBinder?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<TransceiverState> = _binder
        .flatMapLatest { binder -> binder?.state ?: flowOf(TransceiverState.TransceiverOff) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TransceiverState.TransceiverOff)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeLanguage: StateFlow<Language> = _binder
        .flatMapLatest { binder -> binder?.activeLanguage ?: flowOf(Language.HINDI) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Language.HINDI)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service is TransceiverService.LocalBinder) {
                _binder.value = service
                _isBound.value = true
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _binder.value = null
            _isBound.value = false
        }
    }

    fun bindService(context: Context) {
        val intent = Intent(context, TransceiverService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService(context: Context) {
        if (_isBound.value) {
            try {
                context.unbindService(connection)
            } catch (_: Exception) {}
            _binder.value = null
            _isBound.value = false
        }
    }

    fun toggleTransceiver(context: Context, enable: Boolean) {
        val intent = Intent(context, TransceiverService::class.java)
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            bindService(context)
            viewModelScope.launch {
                // Ensure binder is connected before enabling
                _binder.value?.enableTransceiver()
            }
        } else {
            _binder.value?.disableTransceiver()
            unbindService(context)
            context.stopService(intent)
        }
    }

    fun startRecording() {
        _binder.value?.startRecording()
    }

    fun stopRecording() {
        _binder.value?.stopRecording()
    }

    fun switchLanguage(language: Language) {
        _binder.value?.switchLanguage(language)
    }

    override fun onCleared() {
        super.onCleared()
    }
}
