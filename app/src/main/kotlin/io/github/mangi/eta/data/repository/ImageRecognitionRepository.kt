package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ImageRecognitionSettings
import io.github.mangi.eta.data.model.SpeechCredentialField
import kotlinx.coroutines.flow.Flow

/**
 * 本地图片识别配置与可选 Key。
 *
 * Key 与语音凭据共用同一份 Keystore 加密存储（多了 vision 字段），
 * 因此不会出现第二套加密流程，也不会把凭据写进普通配置导出。
 */
internal object ImageRecognitionRepository {
    fun settingsFlow(): Flow<ImageRecognitionSettings> = SettingsDataStore.imageRecognitionFlow()

    suspend fun settings(): ImageRecognitionSettings = SettingsDataStore.imageRecognition()

    suspend fun save(settings: ImageRecognitionSettings) = SettingsDataStore.setImageRecognition(settings)

    /**
     * 读取可选 Key。凭据整体不可解密时抛 [SpeechCredentialsUnavailable]，
     * 由调用方提示重新填写，而不是静默当作空 Key。
     */
    suspend fun apiKey(context: Context): String = SpeechSettingsRepository.credentials(context).vision

    suspend fun saveApiKey(context: Context, value: String) {
        val current = SpeechSettingsRepository.credentials(context)
        SpeechSettingsRepository.saveCredentials(context, current.withValue(SpeechCredentialField.VISION, value.trim()))
    }
}
