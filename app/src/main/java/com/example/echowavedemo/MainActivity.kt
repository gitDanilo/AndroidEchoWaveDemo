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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.echowavedemo.ui.theme.EchoWaveDemoTheme
import kotlinx.coroutines.launch

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
                        Log.d("USB", "permission request received for device $device")
                    } else {
                        Log.d("USB", "Permission denied for device $device")
                    }
                }
            }
        }
    }

    private fun showToast(message: String) = Toast.makeText(
        this,
        message,
        Toast.LENGTH_SHORT
    ).show()


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

    private fun setEventListener() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.event.collect { event ->
                    when (event.type) {
                        EventType.INITIALIZED -> {}
                        EventType.LISTEN_MODE -> {
                            if (event.success) {
                                playHapticFeedback(longArrayOf(0, 100))
                            } else {
                                showToast("Failed to start listen mode")
                                playHapticFeedback(longArrayOf(0, 100, 0, 100))
                            }
                        }

                        EventType.SEND_RC_CODE -> {
                            if (event.success) {
                                playHapticFeedback(longArrayOf(0, 100))
                            } else {
                                showToast("Failed to send code")
                                playHapticFeedback(longArrayOf(0, 100, 0, 100))
                            }
                        }

                        EventType.RC_CODE_RECEIVED -> {
                            playHapticFeedback(longArrayOf(0, 100))
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter(Constants.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        }

        viewModel.loadRcCodes(application)

        setEventListener()

        enableEdgeToEdge()
        setContent {
            EchoWaveDemoTheme {
                val state = viewModel.state.collectAsStateWithLifecycle().value

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("EchoWave Demo") },
                            actions = {
                                IconButton(
                                    onClick = {
                                        viewModel.showConfirmationDialog()
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Clear All",
                                    )
                                }
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
                            },
                        )
                    },
                ) { innerPadding ->

                    if (state.showConfirmationDialog) {
                        ConfirmationDialog(
                            "Clear all codes ?",
                            onConfirm = {
                                Log.d("MainActivity", "onConfirm")
                                viewModel.clearAllCodes(application)
                            },
                            onDismiss = {
                                Log.d("MainActivity", "onDismiss")
                            },
                        )
                    }

                    if (state.selectedRcCode != null) {
                        RcCodeDetailsBottomSheet(
                            rcCode = state.selectedRcCode,
                            onDismiss = {
                                viewModel.selectRcCode(null)
                            }
                        )
                    }

                    RcCodesList(
                        rcCodes = state.rcCodes,
                        isLoading = state.isLoading,
                        modifier = Modifier.padding(innerPadding),
                        onItemPress = {
                            Log.d("MainActivity", "onItemPress: $it")
                            viewModel.sendRcCode(application, it.data.copy(repeat = 3))
                        },
                        onItemLongPress = {
                            Log.d("MainActivity", "onItemLongPress: $it")
                            viewModel.selectRcCode(it)
                        },
                        onItemDoubleTap = {
                            Log.d("MainActivity", "onItemDoubleTap: $it")
                        },
                        onItemDismiss = {
                            Log.d("MainActivity", "onDismiss: $it")
                            viewModel.removeRcCode(application, it)
                        },
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Composable
    fun RcCodeDetailsBottomSheet(
        rcCode: RcCode,
        onDismiss: () -> Unit,
    ) {
        val repeatValues = remember { (1..10).map { it.toString() } }
        val colorValues = remember { Constants.RC_CODE_COLORS }
        val repeatPickerState = rememberPickerState("")
        val colorPickerState = rememberPickerState<ULong>(Color.White.value)

        val maybeUpdate: (Int, ULong) -> Unit = { value, color ->
            val repeatValue = if (rcCode.data.repeat < 1) 1 else rcCode.data.repeat
            if (repeatValue != value || rcCode.color != color) {
                viewModel.updateRcCode(
                    application,
                    rcCode.hashCode(),
                    rcCode.copy(
                        color = color,
                        data = rcCode.data.copy(repeat = value),
                    )
                )
            }
            onDismiss()
        }

        ModalBottomSheet(
            onDismissRequest = {
                val value = repeatPickerState.selectedItem.toInt()
                maybeUpdate(value, colorPickerState.selectedItem)
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "RC Code Details",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .padding(bottom = 20.dp)
                        .align(Alignment.CenterHorizontally),
                )
                Text("Code: 0x${rcCode.data.code.toHexString(HexFormat.UpperCase)}")
                Text("Length: ${rcCode.data.length} bits")
                Text("Pulse Length: ${rcCode.data.pulseLength}")
                Text("Sync Factor: ${rcCode.data.syncFactor}")
                Text("One: ${rcCode.data.one}")
                Text("Zero: ${rcCode.data.zero}")
                Text("Inverted: ${rcCode.data.inverted}")
                Text("Timestamp: ${rcCode.timestamp.formatTimestamp()}")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .wrapContentSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Color")
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .width(80.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(8.dp),
                                )
                        ) {
                            ColorPicker(
                                state = colorPickerState,
                                items = colorValues,
                                startIndex = Constants.RC_CODE_COLORS.indexOfFirst { it == rcCode.color },
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .wrapContentSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Repeat")
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .width(80.dp)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(8.dp),
                                )
                        ) {
                            Picker(
                                state = repeatPickerState,
                                items = repeatValues,
                                startIndex = if (rcCode.data.repeat < 1) 0 else rcCode.data.repeat - 1,
                                textModifier = Modifier.padding(vertical = 8.dp),
                                textStyle = TextStyle(fontSize = 14.sp),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ConfirmationDialog(
        message: String,
        onDismiss: () -> Unit,
        onConfirm: () -> Unit,
    ) {
        BasicAlertDialog(
            onDismissRequest = {
                viewModel.dismissConfirmationDialog()
                onDismiss()
            },
            content = {
                Card(
                    modifier = Modifier
                        .padding(0.dp)
                        .wrapContentWidth()
                        .wrapContentHeight(),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(30.dp),
                        textAlign = TextAlign.Center,
                    )
                    TextButton(
                        onClick = {
                            viewModel.dismissConfirmationDialog()
                            onConfirm()
                        },
                        shape = RectangleShape,
                        modifier = Modifier
                            .fillMaxWidth(),
                    ) {
                        Text(
                            text = "Confirm",
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            },
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Composable
    fun RcCodeItem(
        rcCode: RcCode,
        onItemPress: (RcCode) -> Unit,
        onItemLongPress: (RcCode) -> Unit,
        onItemDoubleTap: (RcCode) -> Unit,
    ) {
        val formattedTime = remember(rcCode.timestamp) {
            rcCode.timestamp.formatTimestamp()
        }

        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer)
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 10.dp, end = 10.dp)
                    .size(10.dp)
                    .background(
                        color = Color(rcCode.color),
                        shape = CircleShape,
                    ),
            )
            Text(
                text = "Code: 0x${rcCode.data.code.toHexString(HexFormat.UpperCase)} (${rcCode.data.length} bits) at $formattedTime",
                fontSize = 14.sp,
                textAlign = TextAlign.Start,
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
                                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.background
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
                                )
                            }
                        },
                    ) {
                        RcCodeItem(rcCode, onItemPress, onItemLongPress, onItemDoubleTap)
                    }
                    HorizontalDivider()
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