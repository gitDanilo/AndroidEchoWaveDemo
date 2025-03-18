package com.example.echowavedemo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

object EchoWaveDevice {
    private val tag = EchoWaveDevice::class.java.simpleName

    private var serialPort: UsbSerialPort? = null
    private var readBuffer: ByteBuffer? = null

    val isInitialized: Boolean
        get() = serialPort?.isOpen == true

    private var isListening = AtomicBoolean(false)

    suspend fun init(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) {
            close()
        }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: run {
            Log.e(tag, "Failed to get USB manager")
            return@withContext false
        }

        val driver = UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .firstOrNull() ?: run {
            Log.e(tag, "Failed to find USB drivers")
            return@withContext false
        }

        if (!usbManager.hasPermission(driver.device)) {
            val intent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(Constants.ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbManager.requestPermission(driver.device, intent)
            return@withContext false
        }

        val connection = usbManager.openDevice(driver.device) ?: run {
            Log.e(tag, "Failed to open USB device")
            return@withContext false
        }

        serialPort = driver.ports.firstOrNull() ?: run {
            Log.e(tag, "Failed to get USB port")
            return@withContext false
        }

        try {
            serialPort?.run {
                open(connection)
                setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                isListening.set(false)
                readBuffer = ByteBuffer.allocate(readEndpoint.maxPacketSize).also {
                    read(it.array(), 500)
                }
                val msg = readMsg(500)
                if (msg?.type != MsgType.READY) {
                    sendMsg(Msg(type = MsgType.REPLY, data = ReplyData(type = ReplyType.OK)))
                    sendMsg(Msg(type = MsgType.STOP))
                }
            }
        } catch (e: IOException) {
            Log.e(tag, "Failed to open USB port", e)
            close()
            return@withContext false
        }

        return@withContext true
    }

    private suspend fun sendMsg(msg: Msg, withRetry: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val port = serialPort ?: return@withContext false
            val bytes = msg.toBytes()

            try {
                port.write(bytes, Constants.MSG_SIZE, Constants.SERIAL_TIMEOUT)
                return@withContext true
            } catch (e: IOException) {
                Log.e(tag, "Failed to send msg", e)
                close()
                return@withContext false
            }
        }

    private suspend fun readMsg(timeout: Int = Constants.SERIAL_TIMEOUT): Msg? =
        withContext(Dispatchers.IO) {
            val buffer = readBuffer ?: return@withContext null
            val port = serialPort ?: return@withContext null
            val bytesRead: Int

            try {
                bytesRead = port.read(buffer.array(), timeout)
            } catch (e: IOException) {
                Log.e(tag, "Failed to read msg", e)
                close()
                return@withContext null
            }

            if (bytesRead == 0) {
                return@withContext null
            }

            if (bytesRead != Constants.MSG_SIZE) {
                Log.e(tag, "Received msg with wrong size: $bytesRead")
                sendMsg(Msg(MsgType.REPLY, ReplyData(ReplyType.INVALID_SIZE)))
                return@withContext null
            }

            val msg = Msg.fromBytes(buffer.array())
            Log.d(tag, "Received msg: $msg")
            if (msg?.type != MsgType.REPLY && msg?.checkCrc() == false) {
                Log.e(tag, "Received msg with bad CRC")
                sendMsg(Msg(MsgType.REPLY, ReplyData(ReplyType.BAD_CRC)))
                return@withContext null
            }

            return@withContext msg
        }

    fun startListening(): Flow<RcData> {
        if (!isInitialized) {
            Log.e(tag, "Device is not initialized")
            throw IllegalStateException("Device is not initialized")
        }

        return flow {
            if (!sendMsg(Msg(MsgType.RX_REQUEST))) {
                Log.e(tag, "Failed to send RX_REQUEST")
                throw IllegalStateException("Failed to send RX_REQUEST")
            }

            Log.d(tag, "Started listening")
            isListening.set(true)

            var msg: Msg
            do {
                msg = readMsg(0) ?: continue
                if (msg.data is RcData) {
                    sendMsg(Msg(type = MsgType.REPLY, data = ReplyData(ReplyType.OK)))
                    emit(msg.data)
                }
            } while (isListening.get())
        }.flowOn(Dispatchers.IO)
    }

    suspend fun stopListening() = withContext(Dispatchers.IO) {
        if (!isListening.get()) {
            Log.w(tag, "Not listening")
            return@withContext
        }

        isListening.set(false)

        if (!isInitialized) {
            Log.e(tag, "Device is not initialized")
            return@withContext
        }

        if (!sendMsg(Msg(MsgType.STOP))) {
            Log.e(tag, "Failed to send STOP")
            return@withContext
        }

        Log.d(tag, "Stopped listening")
    }

    suspend fun sendRcCode(code: RcData): Boolean = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.e(tag, "Device is not initialized")
            return@withContext false
        }

        if (isListening.get()) {
            Log.e(tag, "Device is in listening mode")
            return@withContext false
        }

        if (!sendMsg(Msg(MsgType.TX_REQUEST, code))) {
            Log.e(tag, "Failed to send TX_REQUEST")
            return@withContext false
        }

        readMsg()

        return@withContext true
    }

    fun close() {
        Log.d(tag, "Closing device")
        isListening.set(false)
        try {
            serialPort?.close()
        } catch (_: IOException) {
        }
        serialPort = null
        readBuffer?.clear()
        readBuffer = null
    }
}