#!/usr/bin/env bash
# 构建前把 whisper small 模型放进 assets，供 APK 内置。
#
# 模型有 180MB 左右，不进仓库；只在构建机上按需下载并做尺寸校验，
# 已存在且大小合理时直接复用，避免每次构建重复拉取。
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$root/app/src/main/assets/whisper/ggml-small-q5_1.bin"
url="${ETA_WHISPER_MODEL_URL:-https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin}"
min_bytes=150000000

if [[ -f "$dest" ]]; then
  size="$(stat -c %s "$dest")"
  if [[ "$size" -ge "$min_bytes" ]]; then
    echo "已存在 whisper 模型（$size 字节），跳过下载。"
    exit 0
  fi
  echo "现有模型大小异常（$size 字节），重新下载。"
  rm -f "$dest"
fi

mkdir -p "$(dirname "$dest")"
echo "下载 $url"
curl -fL --retry 4 --retry-delay 3 --connect-timeout 30 -o "$dest.part" "$url"

size="$(stat -c %s "$dest.part")"
if [[ "$size" -lt "$min_bytes" ]]; then
  echo "::error::whisper 模型下载不完整（$size 字节）"
  rm -f "$dest.part"
  exit 1
fi

mv "$dest.part" "$dest"
echo "模型就绪：$size 字节"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$dest" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
fi
