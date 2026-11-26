package com.fromsub.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

class NativeVoiceRecorder(private val context: Context) {

    private val recordingFlag = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var writerThread: Thread? = null
    private var outputFile: File? = null
    private var raf: RandomAccessFile? = null

    @Volatile
    private var totalPcmBytes: Int = 0

    @Volatile
    private var startedAtMs: Long = 0L

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): String {
        if (!hasPermission()) return "permission_required"
        if (recordingFlag.get()) return "ok"

        return try {
            val dir = ChunkCachePaths.chunkDir(context)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "voice_${System.currentTimeMillis()}.wav")
            val randomAccess = RandomAccessFile(file, "rw")
            randomAccess.write(ByteArray(44))

            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf * 2, 8192)

            var recorder: AudioRecord? = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                try { recorder?.release() } catch (_: Exception) {}
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
            }
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                try { recorder?.release() } catch (_: Exception) {}
                try { randomAccess.close() } catch (_: Exception) {}
                try { file.delete() } catch (_: Exception) {}
                return "error:AudioRecord failed to initialize"
            }

            audioRecord = recorder
            outputFile = file
            raf = randomAccess
            totalPcmBytes = 0
            startedAtMs = System.currentTimeMillis()
            recordingFlag.set(true)

            recorder.startRecording()

            val t = Thread({
                val buffer = ByteArray(bufSize)
                while (recordingFlag.get()) {
                    val read = try {
                        recorder.read(buffer, 0, buffer.size)
                    } catch (_: Exception) {
                        break
                    }
                    if (read > 0) {
                        try {
                            randomAccess.write(buffer, 0, read)
                            totalPcmBytes += read
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
            }, "SubLyter-VoiceRec").apply {
                isDaemon = true
                start()
            }
            writerThread = t
            "ok"
        } catch (e: Exception) {
            cleanupQuiet()
            "error:${e.message ?: "mic_start_failed"}"
        }
    }

    @Synchronized
    fun stop(): String {
        recordingFlag.set(false)
        try { writerThread?.join(1500) } catch (_: Exception) {}
        writerThread = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        val file = outputFile
        val randomAccess = raf
        val pcmBytes = totalPcmBytes
        val durationSec = maxOf(1, ((System.currentTimeMillis() - startedAtMs) / 1000L).toInt())

        outputFile = null
        raf = null
        totalPcmBytes = 0

        if (file == null || randomAccess == null) return ""

        return try {
            WavChunkWriter.writeWavHeader(randomAccess, pcmBytes, 16000, 1)
            randomAccess.close()
            if (pcmBytes < 320) {
                try { file.delete() } catch (_: Exception) {}
                ""
            } else {
                JSONObject().apply {
                    put("path", file.absolutePath)
                    put("durationSec", durationSec)
                    put("bytes", pcmBytes + 44)
                }.toString()
            }
        } catch (_: Exception) {
            try { randomAccess.close() } catch (_: Exception) {}
            try { file.delete() } catch (_: Exception) {}
            ""
        }
    }

    @Synchronized
    fun cancel() {
        recordingFlag.set(false)
        try { writerThread?.join(600) } catch (_: Exception) {}
        writerThread = null
        cleanupQuiet()
    }

    private fun cleanupQuiet() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { raf?.close() } catch (_: Exception) {}
        raf = null
        try { outputFile?.delete() } catch (_: Exception) {}
        outputFile = null
        totalPcmBytes = 0
    }
}
