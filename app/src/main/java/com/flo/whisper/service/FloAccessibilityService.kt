package com.flo.whisper.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.flo.whisper.overlay.BubbleView
import com.flo.whisper.wyoming.WyomingClient
import kotlinx.coroutines.*

class FloAccessibilityService : AccessibilityService() {

    companion object {
        var instance: FloAccessibilityService? = null
            private set
        private const val TAG = "FloAccessibility"
        private const val SAMPLE_RATE = 16000
    }

    private var focusedNode: AccessibilityNodeInfo? = null
    private var isTextFieldFocused = false
    private var bubbleView: BubbleView? = null
    private var windowManager: WindowManager? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var wyomingClient: WyomingClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recordingJob: Job? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubbleView = BubbleView(this)

        bubbleParams = WindowManager.LayoutParams(
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
                        initialY = bubbleParams!!.y
                        initialTouchY = event.rawY
                        startRecording()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        bubbleParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(bubbleView, bubbleParams)
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

        bubbleView!!.visibility = View.GONE
        try {
            windowManager?.addView(bubbleView, bubbleParams)
            Log.i(TAG, "Bubble view added")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble view", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                checkForTextField(event)
            }
        }
    }

    private fun checkForTextField(event: AccessibilityEvent) {
        val source = event.source ?: return

        val isEditable = source.isEditable
        val wasFocused = isTextFieldFocused
        isTextFieldFocused = isEditable && source.isFocused

        if (isTextFieldFocused) {
            focusedNode = source
        } else if (!isEditable) {
            val root = rootInActiveWindow
            if (root != null) {
                val editableNode = findFocusedEditable(root)
                if (editableNode != null) {
                    isTextFieldFocused = true
                    focusedNode = editableNode
                }
            }
        }

        if (wasFocused != isTextFieldFocused) {
            bubbleView?.post {
                bubbleView?.visibility = if (isTextFieldFocused) View.VISIBLE else View.GONE
            }
        }
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditable(child)
            if (result != null) return result
        }
        return null
    }

    fun pasteText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("flo", text))

        val node = findFocusedEditableInActiveWindow() ?: focusedNode
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.i(TAG, "Paste action performed")
        } else {
            Log.e(TAG, "No focused editable node found for paste")
        }
    }

    private fun findFocusedEditableInActiveWindow(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findFocusedEditable(root)
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

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        recordingJob = scope.launch {
            try {
                Log.i(TAG, "Connecting to Wyoming at $host:$port")
                wyomingClient!!.connect()
                Log.i(TAG, "Connected, starting audio stream")
                wyomingClient!!.startAudio()
                audioRecord!!.startRecording()
                Log.i(TAG, "AudioRecord state: ${audioRecord!!.recordingState}, sample rate: ${audioRecord!!.sampleRate}")

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloAccessibilityService, "Recording...", Toast.LENGTH_SHORT).show()
                }

                val chunkSize = SAMPLE_RATE // 0.5s of 16-bit mono
                val buffer = ByteArray(chunkSize)

                var chunkCount = 0
                withContext(Dispatchers.IO) {
                    while (isRecording && isActive) {
                        val read = audioRecord!!.read(buffer, 0, chunkSize)
                        if (read > 0) {
                            val chunk = if (read == chunkSize) buffer else buffer.copyOf(read)
                            wyomingClient!!.sendAudioChunk(chunk)
                            chunkCount++
                        }
                    }
                }
                Log.i(TAG, "Sent $chunkCount audio chunks")
            } catch (e: Exception) {
                Log.e(TAG, "Recording error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloAccessibilityService, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
                isRecording = false
                bubbleView?.post { bubbleView?.setRecording(false) }
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
                Log.i(TAG, "Stopping audio, waiting for transcript...")
                val transcript = wyomingClient?.stopAudioAndGetTranscript() ?: ""
                wyomingClient?.disconnect()
                Log.i(TAG, "Transcript received: '$transcript'")

                withContext(Dispatchers.Main) {
                    if (transcript.isNotBlank()) {
                        Toast.makeText(this@FloAccessibilityService, "Got: $transcript", Toast.LENGTH_SHORT).show()
                        pasteText(transcript)
                    } else {
                        Toast.makeText(this@FloAccessibilityService, "Empty transcript", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FloAccessibilityService, "Transcribe error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                bubbleView?.post { bubbleView?.setProcessing(false) }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        isRecording = false
        recordingJob?.cancel()
        scope.cancel()
        audioRecord?.release()
        wyomingClient?.disconnect()
        bubbleView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}
