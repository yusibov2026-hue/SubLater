package com.fromsub.app

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ChunkCachePaths {
    const val CHUNK_DIR_NAME = "sublater_chunks"

    fun chunkDir(context: Context): File = File(context.cacheDir, CHUNK_DIR_NAME)

    fun resolveSafeChunkFile(context: Context, rawPath: String?): File? {
        if (rawPath.isNullOrBlank()) return null
        return try {
            val baseDir = chunkDir(context).canonicalFile
            val candidate = File(rawPath).canonicalFile
            val basePrefix = baseDir.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
            if (candidate.path.startsWith(basePrefix)) candidate else null
        } catch (_: Exception) {
            null
        }
    }
}

object WavChunkWriter {

    fun writeWavHeader(
        raf: RandomAccessFile,
        pcmDataBytes: Int,
        sampleRate: Int = 16000,
        channels: Int = 1
    ) {
        val byteRate = sampleRate * channels * 2
        val totalDataLen = pcmDataBytes + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * 2).toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmDataBytes)
        raf.seek(0L)
        raf.write(header.array())
    }

    fun channelWeight(channel: Int, channels: Int): Float {
        return when {
            channels <= 1 -> 1f
            channels == 2 -> 0.5f
            channels == 3 -> when (channel) {
                0, 1 -> 0.4f
                else -> 0.6f
            }
            channels == 4 -> when (channel) {
                0, 1 -> 0.4f
                else -> 0.3f
            }
            channels == 6 -> when (channel) {
                0, 1 -> 0.3f
                2 -> 0.6f
                else -> 0.2f
            }
            else -> when (channel) {
                0, 1 -> 0.25f
                2 -> 0.45f
                else -> 0.15f
            }
        }
    }
}
