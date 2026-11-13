#!/usr/bin/env bash
# 校验本仓库仅有的定制（系统内置语音输入/输出）仍然存在。
#
# 上游同步是自动合并；冲突会让工作流失败，这里再兜一层：
# 如果上游用重写文件的方式让这两个选项静默消失，构建前就会失败。
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

check_content "app/src/main/kotlin/io/github/mangi/eta/data/model/SpeechSettings.kt" "SYSTEM }" "系统语音引擎枚举"
check_file "app/src/main/kotlin/io/github/mangi/eta/agent/voice/SystemTtsSynthesis.kt"
check_file "app/src/main/kotlin/io/github/mangi/eta/agent/voice/SystemRecognitionSession.kt"
check_content "app/src/main/kotlin/io/github/mangi/eta/agent/voice/SpeechSynthesis.kt" "synthesizeSystemSpeech" "系统合成分发"
check_content "app/src/main/kotlin/io/github/mangi/eta/agent/voice/SpeechInputController.kt" "recognizeWithSystemEngine" "系统识别分发"
check_content "app/src/main/kotlin/io/github/mangi/eta/ui/voice/SpeechConnectionFields.kt" "TtsProvider.SYSTEM" "系统引擎设置项"
check_content "app/src/main/res/values/strings.xml" "speech_asr_system" "系统引擎文案"

if [[ "$failed" -ne 0 ]]; then
  echo "定制功能校验失败：上游同步后请检查上面列出的文件。"
  exit 1
fi

echo "定制功能校验通过。"
