#!/usr/bin/env bash
# 下载 GenieX Android SDK（AAR）与官方模型目录，供构建使用。
#
# - AAR 取官方最新 Release，校验随包发布的 .sha256；
# - 模型目录取官方仓库里的 bench-models.json，作为"常用模型"快捷项的数据源；
# - 两者都不进仓库，避免把 80 MB 二进制和易变清单提交进去。
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
libs="$root/app/libs"
assets="$root/app/src/main/assets/geniex"
mkdir -p "$libs" "$assets"

if [[ "${ETA_SKIP_GENIEX_SDK:-0}" == "1" ]]; then
  echo "已要求跳过 GenieX SDK 下载。"
  exit 0
fi

api="https://api.github.com/repos/qualcomm/GenieX/releases/latest"
tag="$(curl -fsSL "$api" | grep -o '"tag_name": *"[^"]*"' | head -n 1 | sed 's/.*"\([^"]*\)"$/\1/')"
if [[ -z "$tag" ]]; then
  echo "::error::无法获取 GenieX 最新版本号"
  exit 1
fi
echo "GenieX 最新版本：$tag"

aar_name="geniex-android-aar-$tag.aar"
base="https://github.com/qualcomm/GenieX/releases/download/$tag"
target="$libs/geniex-android.aar"

echo "下载 $aar_name"
curl -fL --retry 4 --retry-delay 3 --connect-timeout 30 -o "$target.part" "$base/$aar_name"
curl -fL --retry 4 --retry-delay 3 --connect-timeout 30 -o "$target.sha256" "$base/$aar_name.sha256"

expected="$(grep -oE '[0-9a-fA-F]{64}' "$target.sha256" | head -n 1 | tr 'A-Z' 'a-z')"
actual="$(sha256sum "$target.part" | cut -d' ' -f1)"
if [[ -z "$expected" || "$expected" != "$actual" ]]; then
  echo "::error::GenieX AAR 校验失败（expected=$expected actual=$actual）"
  rm -f "$target.part"
  exit 1
fi
mv "$target.part" "$target"
rm -f "$target.sha256"
echo "AAR 校验通过：$actual"

echo "下载官方模型目录 bench-models.json"
curl -fL --retry 4 --retry-delay 3 --connect-timeout 30 \
  -o "$assets/bench-models.json" \
  "https://raw.githubusercontent.com/qualcomm/GenieX/main/sdk/benchmark/qdc/bench-models.json" || {
  echo "::warning::官方模型目录下载失败，App 将回退到内置快照"
  rm -f "$assets/bench-models.json"
}
