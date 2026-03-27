package com.flo.whisper.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.flo.whisper.R
import com.flo.whisper.overlay.BubbleView
import com.flo.whisper.wyoming.WyomingClient
import kotlinx.coroutines.*

class FloOverlayService : Service() {

    companion object {
        var instance: FloOverlayService? = null
            private set
        private const val TAG = "FloOverlay"
        private const val CHANNEL_ID = "flo_overlay"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: BubbleView? = null
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var wyomingClient: WyomingClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recordingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupBubble()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Flo Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while Flo voice bubble is active"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Flo")
            .setContentText("Voice input ready")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubbleView = BubbleView(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 16
        }

        bubbleView!!.setOnTouchListener(object : View.OnTouchListener {
            private var initialY = 0
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialY = params.y
                        initialTouchY = event.rawY
                        startRecording()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(bubbleView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        stopRecordingAndTranscribe()
                        return true
                    }
                }
                return false
            }
        })

        // Start hidden; show when text field is focused
        bubbleView!!.visibility = View.GONE
        windowManager?.addView(bubbleView, params)
    }

    fun onTextFieldFocusChanged(focused: Boolean) {
        bubbleView?.post {
            bubbleView?.visibility = if (focused) View.VISIBLE else View.GONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        bubbleView?.setRecording(true)

        val prefs = getSharedPreferences("flo_prefs", Context.MODE_PRIVATE)
        val host = prefs.getString("host", "192.168.1.100") ?: "192.168.1.100"
        val port = prefs.getInt("port", 10300)
        val language = prefs.getString("language", "en") ?: "en"

        wyomingClient = WyomingClient(host, port, language)

        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(SAMPLE_RATE * 2) // At least 1 second buffer

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        recordingJob = scope.launch {
            try {
                wyomingClient!!.connect()
                wyomingClient!!.startAudio()

                audioRecord!!.startRecording()

                val chunkSize = SAMPLE_RATE // 0.5 seconds of 16-bit mono = 16000 bytes
                val buffer = ByteArray(chunkSize)

                withContext(Dispatchers.IO) {
                    while (isRecording && isActive) {
                        val read = audioRecord!!.read(buffer, 0, chunkSize)
                        if (read > 0) {
                            val chunk = if (read == chunkSize) buffer else buffer.copyOf(read)
                            wyomingClient!!.sendAudioChunk(chunk)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
            }
        }
    }

    private fun stopRecordingAndTranscribe() {
        if (!isRecording) return
        isRecording = false
        bubbleView?.setRecording(false)
        bubbleView?.setProcessing(true)

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        scope.launch {
            try {
                val transcript = wyomingClient?.stopAudioAndGetTranscript() ?: ""
                wyomingClient?.disconnect()

                if (transcript.isNotBlank()) {
                    FloAccessibilityService.instance?.pasteText(transcript)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
            } finally {
                bubbleView?.post { bubbleView?.setProcessing(false) }
            }
        }
    }

    override fun onDestroy() {
        instance = null
        isRecording = false
        recordingJob?.cancel()
        scope.cancel()
        audioRecord?.release()
        wyomingClient?.disconnect()
        bubbleView?.let { windowManager?.removeView(it) }
        super.onDestroy()
    }
}
