package com.example.echowavedemo

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

data class RcCode(
    val color: Int,
    val data: RcData,
    val timestamp: Long,
) {
    companion object {
        fun fromString(str: String): RcCode {
            val values = str.split(",")
            if (values.size != 10) throw IllegalArgumentException("Invalid RC code string")
            return RcCode(
                color = values[0].toInt(),
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
)

class MainViewModel : ViewModel(), CoroutineScope {
    private val viewModelJob = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + viewModelJob

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    private fun updateRcCodes(context: Context) {
        val codes = _state.value.rcCodes

        context.getSharedPreferences(
            Constants.SHARED_PREFERENCES_NAME,
            Context.MODE_PRIVATE
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

        launch(Dispatchers.IO) {
            val codes = context.getSharedPreferences(
                Constants.SHARED_PREFERENCES_NAME,
                Context.MODE_PRIVATE
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
        launch(Dispatchers.IO) {
            if (!EchoWaveDevice.isInitialized && !EchoWaveDevice.init(context)) {
                return@launch
            }

            _state.update {
                it.copy(isListening = true)
            }

            EchoWaveDevice.startListening().collect { data ->
                val timestamp = System.currentTimeMillis()
                val newCode = RcCode(0, data, timestamp)
                _state.update {
                    it.copy(
                        rcCodes = it.rcCodes + newCode,
                    )
                }
                updateRcCodes(context)
            }
        }
    }

    fun stopListening() {
        _state.update {
            it.copy(isListening = false)
        }

        launch {
            EchoWaveDevice.stopListening()
        }
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
        launch {
            if (!EchoWaveDevice.isInitialized && !EchoWaveDevice.init(context)) {
                return@launch
            }
            if (_state.value.isListening) {
                EchoWaveDevice.stopListening()
                _state.update {
                    it.copy(isListening = false)
                }
            }
            EchoWaveDevice.sendRcCode(code)
        }
    }
}