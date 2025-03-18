package com.example.echowavedemo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.echowavedemo.ui.theme.EchoWaveDemoTheme

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val usbReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (Constants.ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.d("USB", "permission request received for device $device");
                    } else {
                        Log.d("USB", "Permission denied for device $device")
                    }
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun playHapticFeedback(pattern: LongArray = longArrayOf(0, 50)) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter(Constants.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        }

        viewModel.loadRcCodes(application)

        enableEdgeToEdge()
        setContent {
            EchoWaveDemoTheme {
                val state = viewModel.state.collectAsStateWithLifecycle().value
                val showConfirmationDialog = remember { mutableStateOf(false) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(title = { Text("EchoWave Demo") }, actions = {
                            IconButton(
                                onClick = {
                                    if (state.isListening) {
                                        viewModel.stopListening()
                                    } else {
                                        viewModel.startListening(application)
                                    }
                                },
                            ) {
                                Icon(
                                    painter = painterResource(if (state.isListening) R.drawable.outline_hearing_disabled_black_24 else R.drawable.outline_hearing_black_24),
                                    contentDescription = "Start/Stop Listening",
                                )
                            }
                            IconButton(
                                onClick = {
                                    showConfirmationDialog.value = true
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Clear All",
                                )
                            }
                        })
                    },
                ) { innerPadding ->
                    ConfirmationDialog(
                        showConfirmationDialog,
                        "Clear all codes ?",
                        onConfirm = {
                            Log.d("MainActivity", "onConfirm")
                            viewModel.clearAllCodes(application)
                        },
                        onDismiss = {
                            Log.d("MainActivity", "onDismiss")
                        },
                    )
                    RcCodesList(
                        rcCodes = state.rcCodes,
                        isLoading = state.isLoading,
                        modifier = Modifier.padding(innerPadding),
                        onItemPress = {
                            Log.d("MainActivity", "onItemPress: $it")
                            viewModel.sendRcCode(application, it.data.copy(repeat = 3))
                            playHapticFeedback(longArrayOf(0, 200))
                        },
                        onItemLongPress = {
                            Log.d("MainActivity", "onItemLongPress: $it")
                            playHapticFeedback(longArrayOf(0, 100))
                        },
                        onItemDoubleTap = {
                            Log.d("MainActivity", "onItemDoubleTap: $it")
                            playHapticFeedback(longArrayOf(0, 50, 100, 50))

                        },
                        onItemDismiss = {
                            Log.d("MainActivity", "onDismiss: $it")
                            viewModel.removeRcCode(application, it)
                            playHapticFeedback(longArrayOf(0, 75, 0, 75))
                        },
                    )
                }
            }
        }
    }

    @Composable
    fun ConfirmationDialog(
        shouldShowDialog: MutableState<Boolean>,
        message: String,
        onDismiss: () -> Unit,
        onConfirm: () -> Unit,
    ) {
        if (shouldShowDialog.value) {
            BasicAlertDialog(
                onDismissRequest = {
                    shouldShowDialog.value = false
                    onDismiss()
                },
                content = {
                    Card(
                        modifier = Modifier
                            .wrapContentWidth()
                            .wrapContentHeight()
                        ,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(30.dp)
                            ,
                            textAlign = TextAlign.Center,
                        )
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp, end = 10.dp, start = 10.dp)
                                .align(Alignment.End)
                        ) {
                            TextButton(
                                onClick = { },
                                modifier = Modifier
                                    .padding(horizontal = 6.dp),
                            ) {
                                Text("Dismiss")
                            }
                            TextButton(
                                onClick = { },
                                modifier = Modifier
                                    .padding(horizontal = 6.dp),
                            ) {
                                Text("Confirm")
                            }
                        }
                    }
                },
            )
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Composable
    fun RcCodeItem(
        rcCode: RcCode,
        onItemPress: (RcCode) -> Unit,
        onItemLongPress: (RcCode) -> Unit,
        onItemDoubleTap: (RcCode) -> Unit,
    ) {
        Box(
            modifier = Modifier
                .background(color = Color.DarkGray)
                .fillMaxWidth()
                .height(50.dp)
                .pointerInput(rcCode) {
                    detectTapGestures(
                        onTap = {
                            onItemPress(rcCode)
                        },
                        onLongPress = {
                            onItemLongPress(rcCode)
                        },
                        onDoubleTap = {
                            onItemDoubleTap(rcCode)
                        },
                    )
                },

            ) {
            Text(
                text = "Code: 0x${rcCode.data.code.toHexString(HexFormat.UpperCase)} (${rcCode.data.length} bits), PulseLength: ${rcCode.data.pulseLength}",
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 8.dp),
                fontSize = 16.sp,
            )
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Composable
    fun RcCodesList(
        rcCodes: List<RcCode>,
        isLoading: Boolean,
        modifier: Modifier = Modifier,
        onItemPress: (RcCode) -> Unit,
        onItemLongPress: (RcCode) -> Unit,
        onItemDoubleTap: (RcCode) -> Unit,
        onItemDismiss: (RcCode) -> Unit,
    ) {
        if (isLoading) {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = modifier.fillMaxWidth(),
            ) {
                items(
                    items = rcCodes, key = { it.hashCode() }) { rcCode ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { dismissValue ->
                            if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                                onItemDismiss(rcCode)
                                true
                            } else false
                        },
                        positionalThreshold = with(LocalDensity.current) { { 300.dp.toPx() } },
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            val color = when (dismissState.dismissDirection) {
                                SwipeToDismissBoxValue.EndToStart -> Color.Gray
                                else -> Color.Transparent
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(color)
                                    .padding(horizontal = 16.dp),
                                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = Color.White
                                )
                            }
                        },
                    ) {
                        RcCodeItem(rcCode, onItemPress, onItemLongPress, onItemDoubleTap)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.Black)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        EchoWaveDevice.close()
    }
}