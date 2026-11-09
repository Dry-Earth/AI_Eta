#!/usr/bin/env bash
# 校验本仓库新增的语音与识图定制仍然存在。
#
# 上游同步是自动合并，冲突已经会让工作流失败；这里再加一层兜底：
# 如果上游用“删掉/重写文件”的方式让功能静默消失（没有冲突但功能没了），
# 这个脚本会在构建前失败，避免构建出一个缺少定制功能的 APK。
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
failed=0

check_file() {
  local path="$1"
  if [[ ! -f "$root/$path" ]]; then
    echo "::error file=$path::缺少定制文件 $path"
    failed=1
  fi
}

check_content() {
  local path="$1" pattern="$2" label="$3"
  if [[ ! -f "$root/$path" ]] || ! grep -qF -- "$pattern" "$root/$path"; then
    echo "::error file=$path::定制内容丢失：$label"
    failed=1
  fi
}

# 微软 Edge 免费播报
check_file "app/src/main/kotlin/io/github/mangi/eta/agent/voice/EdgeTtsSynthesis.kt"
check_file "app/src/main/kotlin/io/github/mangi/eta/agent/voice/EdgeTtsCodec.kt"
check_file "app/src/main/kotlin/io/github/mangi/eta/agent/voice/Mp3PcmDecoder.kt"
check_content "app/src/main/kotlin/io/github/mangi/eta/data/model/SpeechSettings.kt" "EDGE" "TtsProvider.EDGE 枚举"

# 自定义 OpenAI 兼容语音
check_file "app/src/main/kotlin/io/github/mangi/eta/agent/voice/OpenAiCompatibleSpeech.kt"
check_content "app/src/main/kotlin/io/github/mangi/eta/data/model/SpeechSettings.kt" "CUSTOM" "自定义 ASR/TTS 枚举"

# 自定义接口的本机/局域网地址策略
check_file "app/src/main/kotlin/io/github/mangi/eta/core/LocalEndpointPolicy.kt"
check_content "app/src/main/kotlin/io/github/mangi/eta/agent/voice/SpeechProtocol.kt" "customBaseUrl" "自建语音地址策略"

# 本地图片识别（禁止逃逸到云端 + 自定义提示词）
check_file "app/src/main/kotlin/io/github/mangi/eta/agent/vision/LocalVisionClient.kt"
check_file "app/src/main/kotlin/io/github/mangi/eta/data/model/ImageRecognitionSettings.kt"
check_file "app/src/main/kotlin/io/github/mangi/eta/ui/vision/ImageRecognitionScreen.kt"
check_content "app/src/main/kotlin/io/github/mangi/eta/agent/tool/AgentImageTools.kt" "describeLocally" "read_image 本地识图分支"
check_content "app/src/main/kotlin/io/github/mangi/eta/agent/vision/LocalVisionClient.kt" "只允许本机或局域网" "识图公网地址拦截"
check_content "app/src/main/kotlin/io/github/mangi/eta/data/model/ImageRecognitionSettings.kt" "prompt" "识图自定义提示词"

# 设置入口与文案
check_content "app/src/main/kotlin/io/github/mangi/eta/ui/navigation/AppRoute.kt" "ImageRecognition" "图片识别路由"
check_content "app/src/main/res/values/strings.xml" "vision_note" "图片识别文案"

if [[ "$failed" -ne 0 ]]; then
  echo "定制功能校验失败：上游同步后请检查上面列出的文件。"
  exit 1
fi

echo "定制功能校验通过。"
