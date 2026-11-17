package io.github.mangi.eta.ui.geniex

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
import io.github.mangi.eta.agent.vision.VisionFailure
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ImageRecognitionSettings
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.voice.SpeechActionRow
import io.github.mangi.eta.ui.voice.SpeechField
import io.github.mangi.eta.ui.voice.SpeechFieldColumn
import io.github.mangi.eta.ui.voice.SpeechGroupCard
import io.github.mangi.eta.ui.voice.SpeechLoadingState
import io.github.mangi.eta.ui.voice.SpeechNote
import io.github.mangi.eta.ui.voice.SpeechSaveBlock
import io.github.mangi.eta.ui.vision.ImageRecognitionStore
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 设备内 GenieX 模型管理：已下载模型、云端下载、本地导入、计算单元。
 *
 * 模型配置与图片识别共用同一份设置，所以这里直接用 [ImageRecognitionStore]；
 * 后续「角色本地模型」也会复用同一份模型选择。
 */
@Composable
internal fun GenieXModelsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ImageRecognitionStore(context, scope) }
    val settings = store.settings
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(title = stringResource(R.string.route_geniex_models), onBack = onBack) {
                if (!store.loaded) {
                    SpeechLoadingState(store.message)
                    return@CouiScaffoldPage
                }
                GenieXContent(store, settings)
                SpeechSaveBlock(
                    loaded = store.loaded,
                    saving = store.saving,
                    message = store.message,
                    messageIsError = store.messageIsError,
                    onClick = { store.save { config -> validateForGenieX(config) } },
                )
                CouiPageBottomSpacer()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.route_geniex_models),
                onBack = onBack,
                modifier = Modifier.imePadding(),
            ) {
                if (!store.loaded) {
                    item(key = "loading") { SpeechLoadingState(store.message) }
                    return@MiuixScaffoldPage
                }
                item(key = "content") { GenieXContent(store, settings) }
                item(key = "save") {
                    SpeechSaveBlock(
                        loaded = store.loaded,
                        saving = store.saving,
                        message = store.message,
                        messageIsError = store.messageIsError,
                        onClick = { store.save { config -> validateForGenieX(config) } },
                    )
                }
            }
        }
    }
}

private fun validateForGenieX(config: ImageRecognitionSettings) {
    if (config.geniexModel.isBlank() && config.geniexLocalPath.isBlank()) {
        throw VisionFailure("请填写模型名，或选择本地模型文件")
    }
}

@Composable
private fun GenieXContent(store: ImageRecognitionStore, settings: ImageRecognitionSettings) {
    val hubs = listOf(
        stringResource(R.string.vision_hub_hf),
        stringResource(R.string.vision_hub_modelscope),
        stringResource(R.string.vision_hub_aihub),
    )
    val hubValues = listOf("HUGGINGFACE", "MODELSCOPE", "AIHUB")
    val selectedHub = hubValues.indexOf(settings.geniexHub).coerceAtLeast(0)
    val units = listOf(
        stringResource(R.string.vision_compute_auto),
        "NPU",
        "GPU",
        "CPU",
    )
    val unitValues = listOf("", "npu", "gpu", "cpu")
    val selectedUnit = unitValues.indexOf(settings.geniexComputeUnit).coerceAtLeast(0)

    Column {
        SpeechGroupCard(title = stringResource(R.string.vision_group_service)) {
            SpeechFieldColumn {
                SpeechField(
                    label = stringResource(R.string.vision_geniex_model),
                    value = settings.geniexModel,
                    onChange = { store.edit(settings.copy(geniexModel = it.trim())) },
                )
                SpeechField(
                    label = stringResource(R.string.vision_geniex_local_path),
                    value = settings.geniexLocalPath,
                    onChange = { store.edit(settings.copy(geniexLocalPath = it.trim())) },
                )
                SpeechField(
                    label = stringResource(R.string.vision_geniex_mmproj),
                    value = settings.geniexMmprojPath,
                    onChange = { store.edit(settings.copy(geniexMmprojPath = it.trim())) },
                )
            }
            CouiDropdownPreference(
                title = stringResource(R.string.vision_hub_source),
                items = hubs,
                selectedIndex = selectedHub,
                onSelectedIndexChange = { index ->
                    hubValues.getOrNull(index)?.let { store.edit(settings.copy(geniexHub = it)) }
                },
            )
            Divider()
            CouiDropdownPreference(
                title = stringResource(R.string.vision_compute_unit),
                items = units,
                selectedIndex = selectedUnit,
                onSelectedIndexChange = { index ->
                    unitValues.getOrNull(index)?.let { store.edit(settings.copy(geniexComputeUnit = it)) }
                },
            )
            Divider()
            CouiSwitchPreference(
                title = stringResource(R.string.vision_geniex_on_demand),
                summary = stringResource(R.string.vision_geniex_on_demand_summary),
                checked = settings.geniexOnDemand,
                onCheckedChange = { store.edit(settings.copy(geniexOnDemand = it)) },
            )
        }
        SpeechGroupCard(title = stringResource(R.string.vision_downloaded)) {
            if (store.downloaded.isEmpty()) {
                SpeechNote(stringResource(R.string.vision_downloaded_empty))
            } else {
                store.downloaded.forEach { name ->
                    SpeechActionRow(
                        title = if (name == settings.geniexModel) "✓ " + name else name,
                        onClick = { store.select(name) },
                    )
                }
            }
        }
        if (settings.geniexModel.isNotBlank()) {
            SpeechActionRow(
                title = stringResource(R.string.vision_delete_model),
                onClick = { store.removeSelected() },
            )
        }
        SpeechNote(stringResource(R.string.vision_note))
    }
}
