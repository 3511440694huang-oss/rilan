#!/usr/bin/env bash
# 日览 · 设备端构建脚本
#
# 两套环境自动识别：
#  1) 旧版 Debian proot：/root 下有 sysroot、JDK、aapt2（Linux 路径）；
#  2) DSH App 升级后的 Termux 原生 Bionic 工具链：$HOME/opt/termux-sys。
# 两种环境都关闭 Gradle daemon、把临时目录指到可写位置。
set -u

PROJ_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_VERSION_DIR=/storage/emulated/0/WORK/02-工具链/toolchain/gradle-8.11.1
TERMUX_PREFIX=/data/data/com.dsharnessmobile.shell/files/usr
TERMUX_SYS="$HOME/opt/termux-sys"

if [ -x "$HOME/opt/termux-sys/bin/aapt2" ] && [ -x "$HOME/opt/termux-sys/lib/jvm/java-17-openjdk/bin/java" ]; then
  echo "== 构建环境：Termux 原生 Bionic"
  export JAVA_HOME="$TERMUX_SYS/lib/jvm/java-17-openjdk"
  export LD_LIBRARY_PATH="$TERMUX_SYS/lib:$TERMUX_PREFIX/lib"
  export PATH="$JAVA_HOME/bin:$TERMUX_SYS/bin:$TERMUX_PREFIX/bin:/system/bin"
  export GRADLE_USER_HOME="$HOME/opt/gradle-home"
  export ANDROID_HOME=/storage/emulated/0/WORK/02-工具链/android-sdk
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  export TMPDIR="$HOME/tmp"
  mkdir -p "$TMPDIR"
  GRADLE_BIN="${GRADLE_BIN:-$GRADLE_VERSION_DIR/bin/gradle}"
  AAPT2="${AAPT2:-$TERMUX_SYS/bin/aapt2}"
  LOG="$HOME/tmp/rilan-build.log"
  EXTRA_ARGS=(
    "-Pkotlin.compiler.execution.strategy=in-process"
    "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -Djava.io.tmpdir=$TMPDIR -Djdk.lang.Process.launchMechanism=FORK"
  )
  export GRADLE_OPTS="-Djava.io.tmpdir=$TMPDIR -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
  export JAVA_OPTS="-Djava.io.tmpdir=$TMPDIR -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"
else
  echo "== 构建环境：Debian proot"
  export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
  export PATH="$JAVA_HOME/bin:$PATH"
  export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/storage/emulated/0/WORK/02-工具链/android-user-home}"
  export ANDROID_HOME="${ANDROID_HOME:-/storage/emulated/0/WORK/02-工具链/android-sdk}"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  export TMPDIR="${TMPDIR:-/root/tmp}"
  mkdir -p "$TMPDIR"
  GRADLE_BIN="${GRADLE_BIN:-$GRADLE_VERSION_DIR/bin/gradle}"
  AAPT2="${AAPT2:-/root/aapt2-bin/aapt2}"
  LOG="${LOG:-/root/rilan-build.log}"
  EXTRA_ARGS=(
    "-Pkotlin.compiler.execution.strategy=in-process"
    "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$TMPDIR -Djdk.lang.Process.launchMechanism=FORK"
  )
  export GRADLE_OPTS="-Djava.io.tmpdir=$TMPDIR -Dfile.encoding=UTF-8"
  export JAVA_OPTS="-Djava.io.tmpdir=$TMPDIR -Dfile.encoding=UTF-8"
fi

if [ $# -eq 0 ]; then
  set -- :app:assembleRelease
fi

cd "$PROJ_DIR" || exit 1
bash "$GRADLE_BIN" "$@" --no-daemon \
  -Pandroid.aapt2FromMavenOverride="$AAPT2" \
  "${EXTRA_ARGS[@]}" \
  2>&1 | tee "$LOG"

STATUS=${PIPESTATUS[0]}
echo "BUILD EXIT=$STATUS"
exit "$STATUS"
