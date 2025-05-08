package com.example.simplecontroller.net

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.Socket

object NetworkClient {

    private const val HOST = "10.0.2.2"
    private const val PORT = 9001

    private val channel = Channel<String>(Channel.UNLIMITED)
    private val scope   = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var socket: Socket?        = null
    private var writer: BufferedWriter? = null

    /** Call from Activity.onStart() */
    fun start() {
        scope.launch {
            try {
                socket = Socket(HOST, PORT).also {
                    writer = BufferedWriter(OutputStreamWriter(it.getOutputStream()))
                }
                Log.d("NetworkClient", "socket open")
                for (msg in channel) {
                    Log.d("NetworkClient", "write  $msg")
                    writer?.apply {
                        write(msg)
                        newLine()      // 👈 line terminator
                        flush()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                close()
            }
        }
    }

    /** Call from Activity.onStop() */
    fun close() {
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        writer = null
        socket = null
        scope.coroutineContext.cancelChildren()
    }

    /** Non‑blocking, thread‑safe. */
    fun send(message: String) {
        Log.d("NetworkClient", "enqueue $message")
        channel.trySend(message)
    }
}
