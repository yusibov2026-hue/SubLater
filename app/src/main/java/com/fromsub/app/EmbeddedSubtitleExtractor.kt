package com.fromsub.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

private data class EmbeddedSubtitleSegment(
    val start: Double,
    var end: Double,
    val text: String
)

object EmbeddedSubtitleExtractor {

    private val EMBEDDED_MKV_ASS_REGEX = Regex(
        """^\d+,\d+,[^,]*,[^,]*,\d+,\d+,\d+,[^,]*,(.*)$""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val EMBEDDED_SRT_TIME_REGEX = Regex("""^\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->\s*.*$""")
    private val EMBEDDED_INT_LINE_REGEX = Regex("""^\d+$""")
    private val EMBEDDED_ASS_TAG_REGEX = Regex("""\{\\[^}]*\}""")
    private val EMBEDDED_HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val EMBEDDED_MULTI_SPACE_REGEX = Regex("""\s+""")

    fun extractSegmentsJson(context: Context, uri: Uri?): String {
        if (uri == null) return "[]"
        if (uri.scheme == "http" || uri.scheme == "https") return "[]"

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var subTrackIdx = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase() ?: continue
                if (
                    mime.startsWith("text/") ||
                    mime.contains("subrip") ||
                    mime.contains("ssa") ||
                    mime.contains("ass") ||
                    mime.contains("vtt")
                ) {
                    subTrackIdx = i
                    break
                }
            }
            if (subTrackIdx < 0) return "[]"

            extractor.selectTrack(subTrackIdx)
            val buf = ByteBuffer.allocate(65536)
            val segments = mutableListOf<EmbeddedSubtitleSegment>()

            var guard = 0
            while (guard < 5000) {
                guard++
                buf.clear()
                val sampleSize = extractor.readSampleData(buf, 0)
                if (sampleSize < 0) break
                if (sampleSize > 0) {
                    val startSec = extractor.sampleTime.coerceAtLeast(0L).toDouble() / 1_000_000.0
                    val bytes = ByteArray(sampleSize)
                    buf.position(0)
                    buf.get(bytes, 0, sampleSize)
                    val cleaned = cleanSubtitleText(String(bytes, Charsets.UTF_8))
                    if (cleaned.isNotEmpty()) {
                        if (segments.isNotEmpty()) {
                            val prev = segments[segments.lastIndex]
                            val trimmedEnd = (startSec - 0.05).coerceAtLeast(prev.start + 0.4)
                            if (trimmedEnd < prev.end) {
                                prev.end = trimmedEnd
                            }
                        }
                        segments.add(EmbeddedSubtitleSegment(startSec, startSec + 3.0, cleaned))
                    }
                }
                if (!extractor.advance()) break
            }

            if (segments.size < 5) return "[]"
            val arr = JSONArray()
            for (s in segments) {
                arr.put(JSONObject().apply {
                    put("start", s.start)
                    put("end", s.end)
                    put("text", s.text)
                })
            }
            arr.toString()
        } catch (_: Exception) {
            "[]"
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    internal fun cleanSubtitleText(raw: String): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""

        if (text.startsWith("Dialogue:", ignoreCase = true)) {
            val parts = text.split(",", limit = 10)
            if (parts.size == 10) text = parts[9]
        } else {
            val mkvAssMatch = EMBEDDED_MKV_ASS_REGEX.matchEntire(text)
            if (mkvAssMatch != null) {
                text = mkvAssMatch.groupValues[1]
            }
        }

        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val hasSrtTiming = lines.any { EMBEDDED_SRT_TIME_REGEX.matches(it.trim()) }
        val filtered = if (hasSrtTiming) {
            lines.filter { line ->
                val t = line.trim()
                t.isNotEmpty() && !EMBEDDED_SRT_TIME_REGEX.matches(t) && !EMBEDDED_INT_LINE_REGEX.matches(t)
            }
        } else {
            lines
        }

        return filtered.joinToString(" ")
            .replace("\\N", " ")
            .replace("\\n", " ")
            .replace(EMBEDDED_ASS_TAG_REGEX, "")
            .replace(EMBEDDED_HTML_TAG_REGEX, "")
            .replace(EMBEDDED_MULTI_SPACE_REGEX, " ")
            .trim()
    }
}
