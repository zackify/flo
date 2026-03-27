package com.flo.whisper.wyoming

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket

/**
 * Wyoming protocol client for speech-to-text via wyoming-whisper.
 *
 * Protocol flow:
 *   -> transcribe (with language)
 *   -> audio-start (format info)
 *   -> audio-chunk * N (raw PCM data)
 *   -> audio-stop
 *   <- transcript (result text)
 */
class WyomingClient(
    private val host: String,
    private val port: Int,
    private val language: String = "en"
) {
    companion object {
        private const val TAG = "WyomingClient"
    }
    private var socket: Socket? = null
    private var output: BufferedOutputStream? = null
    private var input: BufferedInputStream? = null

    suspend fun connect() = withContext(Dispatchers.IO) {
        socket = Socket(host, port).apply {
            soTimeout = 30_000
        }
        output = BufferedOutputStream(socket!!.getOutputStream())
        input = BufferedInputStream(socket!!.getInputStream())
    }

    suspend fun startAudio(rate: Int = 16000, width: Int = 2, channels: Int = 1) = withContext(Dispatchers.IO) {
        // Send transcribe event
        sendEvent("transcribe", JSONObject().apply {
            put("language", language)
        })

        // Send audio-start
        sendEvent("audio-start", JSONObject().apply {
            put("rate", rate)
            put("width", width)
            put("channels", channels)
        })
    }

    suspend fun sendAudioChunk(pcmData: ByteArray, rate: Int = 16000, width: Int = 2, channels: Int = 1) = withContext(Dispatchers.IO) {
        sendEvent("audio-chunk", JSONObject().apply {
            put("rate", rate)
            put("width", width)
            put("channels", channels)
        }, pcmData)
    }

    suspend fun stopAudioAndGetTranscript(): String = withContext(Dispatchers.IO) {
        sendEvent("audio-stop")

        // Read events until we get a transcript
        var transcript = ""
        var attempts = 0
        while (attempts < 50) {
            val event = readEvent() ?: break
            val type = event.first
            val data = event.second

            when (type) {
                "transcript" -> {
                    transcript = data?.optString("text", "") ?: ""
                    break
                }
                "error" -> {
                    throw Exception("Wyoming error: ${data?.optString("text", "unknown")}")
                }
            }
            attempts++
        }
        transcript
    }

    fun disconnect() {
        try { output?.close() } catch (_: Exception) {}
        try { input?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        output = null
        input = null
    }

    private fun sendEvent(type: String, data: JSONObject? = null, payload: ByteArray? = null) {
        val header = JSONObject().apply {
            put("type", type)
            if (data != null) {
                put("data", data)
            }
            if (payload != null) {
                put("payload_length", payload.size)
            }
        }

        val headerLine = header.toString() + "\n"
        Log.d(TAG, ">> $type (${payload?.size ?: 0} bytes payload)")
        val headerBytes = headerLine.toByteArray(Charsets.UTF_8)
        output!!.write(headerBytes)
        if (payload != null) {
            output!!.write(payload)
        }
        output!!.flush()
    }

    private fun readEvent(): Pair<String, JSONObject?>? {
        val line = readLine() ?: return null
        Log.d(TAG, "<< RAW header: $line")
        val header = JSONObject(line)
        val type = header.getString("type")
        val dataLength = if (header.has("data_length")) header.getInt("data_length") else 0
        val payloadLength = if (header.has("payload_length")) header.getInt("payload_length") else 0

        // Read data JSON (sent as separate bytes after the header line)
        var data = if (header.has("data")) header.getJSONObject("data") else null
        if (dataLength > 0) {
            val dataBytes = ByteArray(dataLength)
            var offset = 0
            while (offset < dataLength) {
                val read = input!!.read(dataBytes, offset, dataLength - offset)
                if (read <= 0) break
                offset += read
            }
            val dataStr = String(dataBytes, Charsets.UTF_8)
            Log.d(TAG, "<< RAW data: $dataStr")
            data = JSONObject(dataStr)
        }

        // Skip binary payload if present
        if (payloadLength > 0) {
            var remaining = payloadLength
            while (remaining > 0) {
                val skipped = input!!.skip(remaining.toLong())
                if (skipped <= 0) break
                remaining -= skipped.toInt()
            }
        }

        return Pair(type, data)
    }

    private fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input!!.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
    }
}
