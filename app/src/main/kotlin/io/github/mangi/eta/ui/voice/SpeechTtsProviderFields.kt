package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.itos.coui.layout.Divider
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.CustomTtsConfig
import io.github.mangi.eta.data.model.GeminiTtsModel
import io.github.mangi.eta.data.model.MiniMaxTtsModel
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

@Composable
internal fun GeminiConnectionSection(store: SpeechSettingsStore) {
    val config = store.settings.geminiTts
    val items = listOf(
        stringResource(R.string.speech_gemini_flash),
        stringResource(R.string.speech_gemini_flash_lite),
    )
    SpeechApiKeyConnectionSection(
        baseUrl = config.baseUrl,
        apiKey = store.credentials.geminiTts,
        onBaseUrl = { store.edit(store.settings.copy(geminiTts = config.copy(baseUrl = it))) },
        onApiKey = { store.credential(SpeechCredentialField.GEMINI_TTS, it) },
        note = stringResource(R.string.speech_gemini_connection_note),
    ) {
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiDropdownPreference(
                    title = stringResource(R.string.speech_tts_model),
                    items = items,
                    selectedIndex = config.model.ordinal,
                    onSelectedIndexChange = { index ->
                        GeminiTtsModel.entries.getOrNull(index)?.let {
                            store.edit(store.settings.copy(geminiTts = config.copy(model = it)))
                        }
                    },
                )
                Divider()
            }
            AppearanceUiStyle.MIUIX -> {
                OverlayDropdownPreference(
                    title = stringResource(R.string.speech_tts_model),
                    items = items,
                    selectedIndex = config.model.ordinal,
                    onSelectedIndexChange = { index ->
                        GeminiTtsModel.entries.getOrNull(index)?.let {
                            store.edit(store.settings.copy(geminiTts = config.copy(model = it)))
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun MiniMaxConnectionSection(store: SpeechSettingsStore) {
    val config = store.settings.minimaxTts
    val items = listOf(
        stringResource(R.string.speech_minimax_turbo),
        stringResource(R.string.speech_minimax_hd),
    )
    SpeechApiKeyConnectionSection(
        baseUrl = config.baseUrl,
        apiKey = store.credentials.minimaxTts,
        onBaseUrl = { store.edit(store.settings.copy(minimaxTts = config.copy(baseUrl = it))) },
        onApiKey = { store.credential(SpeechCredentialField.MINIMAX_TTS, it) },
        note = stringResource(R.string.speech_minimax_connection_note),
    ) {
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiDropdownPreference(
                    title = stringResource(R.string.speech_tts_model),
                    items = items,
                    selectedIndex = config.model.ordinal,
                    onSelectedIndexChange = { index ->
                        MiniMaxTtsModel.entries.getOrNull(index)?.let {
                            store.edit(store.settings.copy(minimaxTts = config.copy(model = it)))
                        }
                    },
                )
                Divider()
            }
            AppearanceUiStyle.MIUIX -> {
                OverlayDropdownPreference(
                    title = stringResource(R.string.speech_tts_model),
                    items = items,
                    selectedIndex = config.model.ordinal,
                    onSelectedIndexChange = { index ->
                        MiniMaxTtsModel.entries.getOrNull(index)?.let {
                            store.edit(store.settings.copy(minimaxTts = config.copy(model = it)))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SpeechApiKeyConnectionSection(
    baseUrl: String,
    apiKey: String,
    onBaseUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    note: String,
    model: @Composable () -> Unit,
) {
    Column {
        SpeechGroupCard(title = stringResource(R.string.speech_group_connection)) {
            model()
            SpeechFieldColumn {
                SpeechField("API Key", apiKey, secret = true, onChange = { onApiKey(it.trim()) })
                SpeechField(stringResource(R.string.speech_base_url), baseUrl, onChange = { onBaseUrl(it.trim()) })
            }
        }
        SpeechNote(note)
    }
}

@Composable
internal fun SpeechDeliveryFields(store: SpeechSettingsStore) {
    when (store.settings.tts) {
        TtsProvider.GEMINI -> {
            val config = store.settings.geminiTts
            SpeechFieldColumn {
                SpeechField(
                    label = stringResource(R.string.speech_gemini_style),
                    value = config.style,
                    singleLine = false,
                    onChange = { store.edit(store.settings.copy(geminiTts = config.copy(style = it))) },
                )
            }
        }
        TtsProvider.MINIMAX -> {
            val config = store.settings.minimaxTts
            val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                .plus(config.speed).distinct().sorted()
            val items = speeds.map { "$it×" }
            when (LocalUiStyle.current) {
                AppearanceUiStyle.COUI -> {
                    Divider()
                    CouiDropdownPreference(
                        title = stringResource(R.string.speech_speed),
                        items = items,
                        selectedIndex = speeds.indexOf(config.speed),
                        onSelectedIndexChange = { index ->
                            speeds.getOrNull(index)?.let {
                                store.edit(store.settings.copy(minimaxTts = config.copy(speed = it)))
                            }
                        },
                    )
                }
                AppearanceUiStyle.MIUIX -> {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.speech_speed),
                        items = items,
                        selectedIndex = speeds.indexOf(config.speed),
                        onSelectedIndexChange = { index ->
                            speeds.getOrNull(index)?.let {
                                store.edit(store.settings.copy(minimaxTts = config.copy(speed = it)))
                            }
                        },
                    )
                }
            }
        }
        TtsProvider.EDGE -> {
            val config = store.settings.edgeTts
            val rates = listOf(-50, -25, 0, 25, 50, 100)
            val pitches = listOf(-50, -25, 0, 25, 50)
            val rateItems = rates.map { if (it > 0) "+$it%" else "$it%" }
            val pitchItems = pitches.map { if (it > 0) "+${it}Hz" else "${it}Hz" }
            when (LocalUiStyle.current) {
                AppearanceUiStyle.COUI -> {
                    Divider()
                    CouiDropdownPreference(
                        title = stringResource(R.string.speech_speed),
                        items = rateItems,
                        selectedIndex = rates.indexOf(config.rate).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            rates.getOrNull(index)?.let {
                                store.edit(store.settings.copy(edgeTts = config.copy(rate = it)))
                            }
                        },
                    )
                    Divider()
                    CouiDropdownPreference(
                        title = stringResource(R.string.speech_pitch),
                        items = pitchItems,
                        selectedIndex = pitches.indexOf(config.pitch).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            pitches.getOrNull(index)?.let {
                                store.edit(store.settings.copy(edgeTts = config.copy(pitch = it)))
                            }
                        },
                    )
                }
                AppearanceUiStyle.MIUIX -> {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.speech_speed),
                        items = rateItems,
                        selectedIndex = rates.indexOf(config.rate).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            rates.getOrNull(index)?.let {
                                store.edit(store.settings.copy(edgeTts = config.copy(rate = it)))
                            }
                        },
                    )
                    OverlayDropdownPreference(
                        title = stringResource(R.string.speech_pitch),
                        items = pitchItems,
                        selectedIndex = pitches.indexOf(config.pitch).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            pitches.getOrNull(index)?.let {
                                store.edit(store.settings.copy(edgeTts = config.copy(pitch = it)))
                            }
                        },
                    )
                }
            }
        }
        TtsProvider.CUSTOM -> {
            val config = store.settings.customTts
            SpeechFieldColumn {
                SpeechField(
                    label = stringResource(R.string.speech_custom_speed),
                    value = config.speed.toString(),
                    onChange = { value ->
                        value.trim().toFloatOrNull()?.let { speed ->
                            if (speed in CustomTtsConfig.MIN_SPEED..CustomTtsConfig.MAX_SPEED) {
                                store.edit(store.settings.copy(customTts = config.copy(speed = speed)))
                            }
                        }
                    },
                )
            }
        }
        TtsProvider.NONE, TtsProvider.QWEN, TtsProvider.DOUBAO -> Unit
    }
}
