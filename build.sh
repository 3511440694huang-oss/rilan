#!/usr/bin/env bash
# 日览 · 设备端构建脚本（Ubuntu proot / arm64）
set -u

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/storage/emulated/0/WORK/02-工具链/android-user-home}"
export ANDROID_HOME="${ANDROID_HOME:-/storage/emulated/0/WORK/02-工具链/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_OPTS="-Dorg.gradle.daemon=false -Djava.io.tmpdir=/root/tmp -Dfile.encoding=UTF-8"
export JAVA_OPTS="-Djdk.lang.Process.launchMechanism=FORK ${JAVA_OPTS:-}"

PROJ_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_BIN="${GRADLE_BIN:-/storage/emulated/0/WORK/02-工具链/toolchain/gradle-8.11.1/bin/gradle}"
AAPT2="${AAPT2:-/root/aapt2-bin/aapt2}"
LOG="/root/rilan-build.log"
mkdir -p /root/tmp

if [ $# -eq 0 ]; then
  set -- :app:assembleRelease
fi

cd "$PROJ_DIR" || exit 1
bash "$GRADLE_BIN" "$@" --no-daemon \
  -Pandroid.aapt2FromMavenOverride="$AAPT2" \
  2>&1 | tee "$LOG"

STATUS=${PIPESTATUS[0]}
echo "BUILD EXIT=$STATUS"
exit "$STATUS"
