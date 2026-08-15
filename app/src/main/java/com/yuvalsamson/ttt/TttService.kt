package com.yuvalsamson.ttt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TttService : Service() {

    companion object {
        const val ACTION_START = "com.yuvalsamson.ttt.START"
        const val ACTION_STOP = "com.yuvalsamson.ttt.STOP"
        const val ACTION_STATUS = "com.yuvalsamson.ttt.STATUS"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_STATUS = "status"
        const val CHANNEL_ID = "ttt"
        const val NOTIF_ID = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var recognizer: SpeechRecognizer? = null
    private var address: String = ""

    @Volatile private var running = false     // connection should stay alive
    @Volatile private var listening = false   // mic loop active
    private var reconnectScheduled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                address = intent.getStringExtra(EXTRA_ADDRESS) ?: address
                startForeground(NOTIF_ID, buildNotification("מחובר, ממתין"))
                running = true
                // connect() drops any existing (possibly dead) socket first, so every
                // START is a clean reconnect - this is what revives it in the morning.
                connect()
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification("ttt"))
            }
        }
        return START_STICKY
    }

    // ---------------- WebSocket ----------------
    private fun wsUrl(): String {
        var a = address.trim()
        a = a.removePrefix("ws://").removePrefix("http://").trimEnd('/')
        return "ws://$a/ws"
    }

    private fun connect() {
        if (!running) return
        try {
            ws?.cancel()   // drop any previous socket before opening a new one
        } catch (_: Exception) {
        }
        ws = null
        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder().url(wsUrl()).build()
        status("מתחבר אל $address")
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                status("מחובר, ממתין ללחיצה במחשב")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleCommand(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                status("נותק, מנסה שוב")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running || reconnectScheduled) return
        reconnectScheduled = true
        main.postDelayed({
            reconnectScheduled = false
            if (running) connect()
        }, 3000)
    }

    private fun handleCommand(text: String) {
        val cmd = try {
            JSONObject(text).optString("cmd")
        } catch (e: Exception) {
            ""
        }
        main.post {
            when (cmd) {
                "start" -> startListeningLoop()
                "stop" -> stopListening()
            }
        }
    }

    // ---------------- Speech ----------------
    private fun startListeningLoop() {
        if (listening) return
        listening = true
        muteBeep(true)
        status("מקשיב, דבר עכשיו")
        updateNotification("מקשיב")
        startOne()
    }

    private fun startOne() {
        if (!listening) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(listener)
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // Ask Google to add automatic punctuation/formatting (Android 13+).
            // Support varies by language; if Hebrew ignores it there is no harm.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY
                )
            }
        }
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            restartSoon()
        }
    }

    private fun restartSoon() {
        if (!listening) return
        main.postDelayed({ startOne() }, 250)
    }

    private fun stopListening() {
        listening = false
        muteBeep(false)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        status("מחובר, ממתין ללחיצה במחשב")
        updateNotification("מחובר")
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            // No match / speech timeout / recognizer busy: just loop again while listening.
            restartSoon()
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = list?.firstOrNull()
            if (!best.isNullOrBlank()) sendText(best, true)
            restartSoon()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = list?.firstOrNull()
            if (!best.isNullOrBlank()) sendText(best, false)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun sendText(text: String, final: Boolean) {
        val json = JSONObject().put("text", text).put("final", final).toString()
        ws?.send(json)
    }

    // ---------------- Beep mute ----------------
    // The speech recognizer plays a short earcon on every (re)start. Mute the
    // streams it may use while we are listening, then restore afterwards.
    private val beepStreams = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION
    )

    private fun muteBeep(mute: Boolean) {
        val am = try {
            getSystemService(AUDIO_SERVICE) as AudioManager
        } catch (_: Exception) {
            return
        }
        for (s in beepStreams) {
            try {
                am.adjustStreamVolume(
                    s,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0
                )
            } catch (_: Exception) {
                // some streams need Do-Not-Disturb access; ignore if not allowed
            }
        }
    }

    // ---------------- Lifecycle ----------------
    private fun stopEverything() {
        running = false
        listening = false
        muteBeep(false)
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        try {
            ws?.close(1000, "bye")
        } catch (_: Exception) {
        }
        ws = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // ---------------- Notification / status ----------------
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "ttt", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ttt")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun status(s: String) {
        val i = Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, s)
        sendBroadcast(i)
    }
}
