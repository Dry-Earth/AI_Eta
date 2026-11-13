package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ImageRecognitionSettings
import kotlinx.coroutines.flow.Flow

/** 本地图片识别配置的持久化入口，与语音配置一样是独立的一份 JSON。 */
internal object ImageRecognitionRepository {
    fun settingsFlow(): Flow<ImageRecognitionSettings> = SettingsDataStore.imageRecognitionFlow()

    suspend fun settings(): ImageRecognitionSettings = SettingsDataStore.imageRecognition()

    suspend fun save(settings: ImageRecognitionSettings) = SettingsDataStore.setImageRecognition(settings)
}
