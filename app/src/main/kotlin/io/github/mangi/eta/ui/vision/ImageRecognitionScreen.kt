package io.github.mangi.eta.ui.vision

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.itos.coui.layout.Divider
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.vision.LocalVisionClient
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ImageRecognitionSettings
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.voice.SpeechField
import io.github.mangi.eta.ui.voice.SpeechFieldColumn
import io.github.mangi.eta.ui.voice.SpeechGroupCard
import io.github.mangi.eta.ui.voice.SpeechLoadingState
import io.github.mangi.eta.ui.voice.SpeechNote
import io.github.mangi.eta.ui.voice.SpeechSaveBlock
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 本地图片识别设置。
 *
 * 说明文字明确「图片不会发给对话模型」，因为这条边界由 [LocalVisionClient] 的地址校验
 * 和 read_image 的失败即终止策略共同保证，而不是可选的开关。
 */
@Composable
internal fun ImageRecognitionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ImageRecognitionStore(context, scope) }
    val settings = store.settings
    val validate: (ImageRecognitionSettings) -> Unit = { LocalVisionClient.validate(it) }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(title = stringResource(R.string.vision_title), onBack = onBack) {
                if (!store.loaded) {
                    SpeechLoadingState(store.message)
                    return@CouiScaffoldPage
                }
                SpeechGroupCard(title = stringResource(R.string.vision_group_service)) {
                    CouiSwitchPreference(
                        title = stringResource(R.string.vision_enabled),
                        summary = stringResource(R.string.vision_enabled_summary),
                        checked = settings.enabled,
                        onCheckedChange = { store.edit(settings.copy(enabled = it)) },
                    )
                    if (settings.enabled) {
                        Divider()
                        VisionServiceFields(store)
                    }
                }
                if (settings.enabled) {
                    VisionPromptFields(store)
                }
                SpeechSaveBlock(
                    loaded = store.loaded,
                    saving = store.saving,
                    message = store.message,
                    messageIsError = store.messageIsError,
                    onClick = { store.save(validate) },
                )
                CouiPageBottomSpacer()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.vision_title),
                onBack = onBack,
                modifier = Modifier.imePadding(),
            ) {
                if (!store.loaded) {
                    item(key = "loading") { SpeechLoadingState(store.message) }
                    return@MiuixScaffoldPage
                }
                item(key = "service") {
                    SpeechGroupCard(title = stringResource(R.string.vision_group_service)) {
                        SwitchPreference(
                            title = stringResource(R.string.vision_enabled),
                            summary = stringResource(R.string.vision_enabled_summary),
                            checked = settings.enabled,
                            onCheckedChange = { store.edit(settings.copy(enabled = it)) },
                        )
                        if (settings.enabled) {
                            VisionServiceFields(store)
                        }
                    }
                }
                if (settings.enabled) {
                    item(key = "prompt") { VisionPromptFields(store) }
                }
                item(key = "save") {
                    SpeechSaveBlock(
                        loaded = store.loaded,
                        saving = store.saving,
                        message = store.message,
                        messageIsError = store.messageIsError,
                        onClick = { store.save(validate) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VisionServiceFields(store: ImageRecognitionStore) {
    val settings = store.settings
    SpeechFieldColumn {
        SpeechField(
            label = stringResource(R.string.vision_base_url),
            value = settings.baseUrl,
            onChange = { store.edit(settings.copy(baseUrl = it.trim())) },
        )
        SpeechField(
            label = stringResource(R.string.vision_path),
            value = settings.path,
            onChange = { store.edit(settings.copy(path = it.trim())) },
        )
        SpeechField(
            label = stringResource(R.string.vision_model),
            value = settings.model,
            onChange = { store.edit(settings.copy(model = it.trim())) },
        )
        SpeechField(
            label = stringResource(R.string.vision_api_key),
            value = store.apiKey,
            secret = true,
            onChange = { store.key(it.trim()) },
        )
    }
}

@Composable
private fun VisionPromptFields(store: ImageRecognitionStore) {
    val settings = store.settings
    Column {
        SpeechGroupCard(title = stringResource(R.string.vision_group_prompt)) {
            SpeechFieldColumn {
                SpeechField(
                    label = stringResource(R.string.vision_prompt),
                    value = settings.prompt,
                    singleLine = false,
                    onChange = {
                        if (it.length <= ImageRecognitionSettings.MAX_PROMPT_CHARS) {
                            store.edit(settings.copy(prompt = it))
                        }
                    },
                )
            }
        }
        SpeechNote(stringResource(R.string.vision_note))
    }
}
