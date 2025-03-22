package com.example.echowavedemo

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RcCode(
    val color: ULong,
    val data: RcData,
    val timestamp: Long,
) {
    companion object {
        fun fromString(str: String): RcCode {
            val values = str.split(",")
            if (values.size != 10) throw IllegalArgumentException("Invalid RC code string")
            return RcCode(
                color = values[0].toULong(),
                data = RcData(
                    code = values[1].toInt(),
                    length = values[2].toInt(),
                    repeat = values[3].toInt(),
                    pulseLength = values[4].toInt(),
                    syncFactor = values[5].toInt(),
                    one = values[6].toInt(),
                    zero = values[7].toInt(),
                    inverted = values[8].toBoolean(),
                ),
                timestamp = values[9].toLong(),
            )
        }
    }

    override fun toString(): String {
        val data =
            "${data.code},${data.length},${data.repeat},${data.pulseLength},${data.syncFactor},${data.one},${data.zero},${data.inverted}"
        return "$color,$data,$timestamp"
    }
}

data class MainState(
    val rcCodes: List<RcCode> = emptyList(),
    val isLoading: Boolean = true,
    val isListening: Boolean = false,
    val showConfirmationDialog: Boolean = false,
    val selectedRcCode: RcCode? = null,
)

enum class EventType(val type: Int) {
    INITIALIZED(0x00), LISTEN_MODE(0x01), SEND_RC_CODE(0x02), RC_CODE_RECEIVED(0x03),
}

data class EventState(
    val type: EventType = EventType.INITIALIZED,
    val success: Boolean = true,
)

class MainViewModel : ViewModel() {
    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()
    private val _event = MutableSharedFlow<EventState>()
    val event: SharedFlow<EventState> = _event

    private fun updateRcCodes(context: Context) {
        val codes = _state.value.rcCodes

        context.getSharedPreferences(
            Constants.SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE
        ).run {
            edit {
                if (codes.isEmpty()) {
                    clear()
                } else {
                    val codeSet = codes.map { it.toString() }.toSet()
                    putStringSet(Constants.RC_CODES_KEY, codeSet)
                }
                apply()
            }
        }
    }

    fun loadRcCodes(context: Context) {
        _state.update {
            it.copy(isLoading = true, isListening = false)
        }

        viewModelScope.launch(Dispatchers.IO) {
            val codes = context.getSharedPreferences(
                Constants.SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE
            ).getStringSet(Constants.RC_CODES_KEY, null)

            val rcCodes: List<RcCode> = codes?.map { RcCode.fromString(it) } ?: emptyList()

            _state.update {
                it.copy(
                    isLoading = false,
                    isListening = false,
                    rcCodes = rcCodes,
                )
            }
        }
    }

    fun clearAllCodes(context: Context) {
        _state.update {
            it.copy(
                rcCodes = emptyList(),
            )
        }
        updateRcCodes(context)
    }

    fun startListening(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!EchoWaveDevice.isInitialized && !EchoWaveDevice.init(context)) {
                _event.emit(
                    EventState(
                        type = EventType.LISTEN_MODE,
                        success = false,
                    )
                )
                return@launch
            }

            _state.update {
                it.copy(isListening = true)
            }
            _event.emit(
                EventState(
                    type = EventType.LISTEN_MODE,
                    success = true,
                )
            )

            EchoWaveDevice.startListening().collect { data ->
                val timestamp = System.currentTimeMillis()
                val newCode = RcCode(Color.White.value, data, timestamp)
                _state.update {
                    it.copy(rcCodes = it.rcCodes + newCode)
                }
                updateRcCodes(context)
                _event.emit(
                    EventState(type = EventType.RC_CODE_RECEIVED)
                )
            }
        }
    }

    fun stopListening() {
        _state.update {
            it.copy(isListening = false)
        }

        viewModelScope.launch {
            EchoWaveDevice.stopListening()
        }
    }

    fun updateRcCode(context: Context, hash: Int, rcCode: RcCode) {
        _state.update {
            it.copy(
                rcCodes = it.rcCodes.map { code ->
                    if (code.hashCode() == hash) {
                        rcCode
                    } else {
                        code
                    }
                })
        }
        updateRcCodes(context)
    }

    fun removeRcCode(context: Context, rcCode: RcCode) {
        val hash = rcCode.hashCode()
        _state.update {
            it.copy(
                rcCodes = it.rcCodes.filter { it.hashCode() != hash },
            )
        }
        updateRcCodes(context)
    }

    fun sendRcCode(
        context: Context,
        code: RcData,
    ) {
        viewModelScope.launch {
            if (!EchoWaveDevice.isInitialized && !EchoWaveDevice.init(context)) {
                _event.emit(
                    EventState(
                        type = EventType.SEND_RC_CODE,
                        success = false,
                    )
                )
                return@launch
            }

            if (_state.value.isListening) {
                EchoWaveDevice.stopListening()
                _state.update {
                    it.copy(isListening = false)
                }
            }

            EchoWaveDevice.sendRcCode(code)

            _event.emit(
                EventState(
                    type = EventType.SEND_RC_CODE,
                    success = true,
                )
            )
        }
    }

    fun showConfirmationDialog() {
        _state.update {
            it.copy(showConfirmationDialog = true)
        }
    }

    fun dismissConfirmationDialog() {
        _state.update {
            it.copy(showConfirmationDialog = false)
        }
    }

    fun selectRcCode(rcCode: RcCode? = null) {
        _state.update {
            it.copy(selectedRcCode = rcCode)
        }
    }
}