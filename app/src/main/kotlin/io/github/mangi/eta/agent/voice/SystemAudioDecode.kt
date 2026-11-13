package io.github.mangi.eta.agent.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlin.math.min

/**
 * 把外部服务返回的音频解码成播放链路要求的 24 kHz 单声道 PCM16。
 * 支持裸 WAV 与 MP3（MediaCodec），其它格式给出明确错误而不是静默播放噪音。
 */
internal object SystemAudioDecode {
    fun toPcm24kMono(bytes: ByteArray, contentType: String?): ByteArray {
        if (bytes.size < 16) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音服务没有返回音频")
        val riff = bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF"
        if (riff) return wavToPcm24kMono(bytes)
        val mp3 = bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte() ||
            (bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0)
        if (mp3 || contentType?.contains("mpeg", ignoreCase = true) == true) return mp3ToPcm24kMono(bytes)
        throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音服务返回了不支持的音频格式（$contentType）")
    }

    private fun wavToPcm24kMono(wav: ByteArray): ByteArray {
        var channels = 1
        var sampleRate = 24_000
        var bits = 16
        var dataStart = -1
        var dataSize = 0
        var offset = 12
        while (offset + 8 <= wav.size) {
            val id = String(wav, offset, 4, Charsets.US_ASCII)
            val size = leInt(wav, offset + 4)
            if (size < 0) break
            when (id) {
                "fmt " -> {
                    if (offset + 8 + 16 > wav.size) break
                    channels = (wav[offset + 10].toInt() and 0xFF) or ((wav[offset + 11].toInt() and 0xFF) shl 8)
                    sampleRate = leInt(wav, offset + 12)
                    bits = (wav[offset + 22].toInt() and 0xFF) or ((wav[offset + 23].toInt() and 0xFF) shl 8)
                }
                "data" -> {
                    dataStart = offset + 8
                    dataSize = min(size, wav.size - dataStart)
                }
            }
            offset += 8 + size + (size and 1)
        }
        if (dataStart < 0 || dataSize < 2 || channels < 1 || bits != 16) {
            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音服务返回了不支持的 WAV 格式")
        }
        val frames = dataSize / (2 * channels)
        if (frames <= 0) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音服务返回了空音频")

        fun sample(frame: Int, channel: Int): Float {
            val position = dataStart + (frame * channels + channel) * 2
            return ((wav[position].toInt() and 0xFF) or (wav[position + 1].toInt() shl 8)).toShort().toFloat()
        }

        fun mix(frame: Int): Float {
            if (channels == 1) return sample(frame, 0)
            var total = 0f
            for (channel in 0 until channels) total += sample(frame, channel)
            return total / channels
        }

        val target = 24_000
        val ratio = sampleRate.toDouble() / target
        val outFrames = if (sampleRate == target) frames else (frames / ratio).toInt().coerceAtLeast(1)
        val output = ByteArray(outFrames * 2)
        for (index in 0 until outFrames) {
            val value = if (sampleRate == target) {
                mix(index)
            } else {
                val position = index * ratio
                val first = position.toInt().coerceIn(0, frames - 1)
                val second = min(first + 1, frames - 1)
                val fraction = position - first
                mix(first) * (1 - fraction) + mix(second) * fraction
            }.toInt().coerceIn(-32768, 32767)
            output[index * 2] = (value and 0xFF).toByte()
            output[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return output
    }

    private fun mp3ToPcm24kMono(mp3: ByteArray): ByteArray {
        val file = runCatching { File.createTempFile("eta-mp3-", ".mp3") }.getOrElse {
            throw SpeechFailure(SpeechErrorCode.STORAGE, "无法创建临时音频文件")
        }
        try {
            file.writeBytes(mp3)
            val output = java.io.ByteArrayOutputStream()
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(file.absolutePath)
                var track = -1
                var format: MediaFormat? = null
                for (index in 0 until extractor.trackCount) {
                    val candidate = extractor.getTrackFormat(index)
                    if (candidate.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                        track = index
                        format = candidate
                        break
                    }
                }
                val chosen = format ?: throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音服务返回了无法识别的音频")
                extractor.selectTrack(track)
                val mime = chosen.getString(MediaFormat.KEY_MIME) ?: ""
                codec = MediaCodec.createDecoderByType(mime).apply {
                    configure(chosen, null, null, 0)
                    start()
                }
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex)
                            val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outputIndex)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val chunk = ByteArray(info.size)
                                buffer.get(chunk)
                                output.write(chunk)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
            }
            val pcm = output.toByteArray()
            if (pcm.isEmpty()) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "语音服务返回了空音频")
            return pcm
        } finally {
            file.delete()
        }
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
