package io.github.mangi.eta.agent.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * Edge 朗读只提供 MP3。播放链路统一使用 24 kHz 单声道 PCM16，
 * 因此这里用系统自带的 MediaCodec 解码并严格校验采样参数，不把 MP3 直接交给音轨。
 */
internal object Mp3PcmDecoder {
    const val SAMPLE_RATE = 24_000

    fun decode(mp3: ByteArray, onAudio: (ByteArray) -> Unit) {
        if (mp3.size < 4) throw invalid("Edge 没有返回可播放的音频")
        val file = try {
            File.createTempFile("eta-edge-tts-", ".mp3")
        } catch (error: Exception) {
            throw SpeechFailure(SpeechErrorCode.STORAGE, "无法创建临时音频文件")
        }
        try {
            file.writeBytes(mp3)
            decodeFile(file, onAudio)
        } catch (error: SpeechFailure) {
            throw error
        } catch (error: Exception) {
            throw invalid("Edge 音频解码失败，请重试")
        } finally {
            file.delete()
        }
    }

    private fun decodeFile(file: File, onAudio: (ByteArray) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = selectAudioTrack(extractor)
            extractor.selectTrack(track.index)
            codec = MediaCodec.createDecoderByType(track.mime).apply {
                configure(track.format, null, null, 0)
                start()
            }
            pump(extractor, codec, onAudio)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private class AudioTrack(val index: Int, val mime: String, val format: MediaFormat)

    private fun selectAudioTrack(extractor: MediaExtractor): AudioTrack {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return AudioTrack(index, mime, format)
        }
        throw invalid("Edge 返回了无法识别的音频格式")
    }

    private fun pump(extractor: MediaExtractor, codec: MediaCodec, onAudio: (ByteArray) -> Unit) {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var total = 0L
        val deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MS
        while (!outputDone) {
            if (System.currentTimeMillis() > deadline) throw invalid("Edge 音频解码超时，请重试")
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
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
            when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> validateFormat(codec.outputFormat)
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    val buffer = codec.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        buffer.get(chunk)
                        total += chunk.size
                        if (total > MAX_PCM_BYTES) throw invalid("Edge 返回的音频过长，请分段朗读")
                        onAudio(chunk)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        if (total == 0L) throw invalid("Edge 没有返回可播放的音频")
    }

    private fun validateFormat(format: MediaFormat) {
        val sampleRate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT)
        if (sampleRate != SAMPLE_RATE || channels != 1) {
            throw invalid("Edge 返回了不支持的音频参数（${sampleRate ?: "?"} Hz / ${channels ?: "?"} 声道）")
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        runCatching { getInteger(key) }.getOrNull()

    private fun invalid(message: String): SpeechFailure =
        SpeechFailure(SpeechErrorCode.PROTOCOL, message)

    private const val TIMEOUT_US = 10_000L
    private const val DECODE_TIMEOUT_MS = 30_000L
    private const val MAX_PCM_BYTES = 24_000L * 2 * 300
}
