#!/usr/bin/env bash
# =====================================================================
# 小说有声阅读 —— 一键构建脚本（Git Bash / MSYS2 环境）
# 用法: bash build.sh [debug|release|both]   (默认 both)
# 产物: app/build/outputs/apk/{debug,release}/*.apk
# =====================================================================
set -e

TOOLS=/g/android-tools
APP=/g/NovelReaderApp
SDK=$TOOLS/sdk
GRADLE=$TOOLS/gradle-8.7/bin/gradle
export JAVA_HOME=$(echo $TOOLS/jdk/jdk-*)
export ANDROID_HOME=$SDK
export PATH="$JAVA_HOME/bin:$SDK/platform-tools:$PATH"

MODE=${1:-both}
cd "$APP"

# ---- 1. release 签名（缺则生成）----
mkdir -p keystores
if [ ! -f keystores/release.keystore ]; then
  echo "[build] 生成 release 签名…"
  "$JAVA_HOME/bin/keytool" -genkeypair -v \
    -keystore keystores/release.keystore \
    -alias novel -keyalg RSA -keysize 2048 -validity 36500 \
    -storepass novelreader2026 -keypass novelreader2026 \
    -dname "CN=novel-reader,O=like,C=CN" >/dev/null 2>&1
fi
cat > keystores/keystore.properties <<'EOF'
storeFile=keystores/release.keystore
storePassword=novelreader2026
keyAlias=novel
keyPassword=novelreader2026
EOF
echo "[build] 签名就绪"

# ---- 2. 构建 ----
if [ "$MODE" = debug ] || [ "$MODE" = both ]; then
  echo "[build] assembleDebug …"
  "$GRADLE" assembleDebug --console=plain
fi
if [ "$MODE" = release ] || [ "$MODE" = both ]; then
  echo "[build] assembleRelease …"
  "$GRADLE" assembleRelease --console=plain
fi

# ---- 3. 校验 ----
# apksigner 在 Windows 上是 java 工具，直接以 jar 运行最稳
APKSIGNER_JAR=$SDK/build-tools/34.0.0/lib/apksigner.jar
for apk in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release.apk; do
  if [ -f "$apk" ]; then
    echo "[build] 校验 $apk"
    "$JAVA_HOME/bin/java" -jar "$APKSIGNER_JAR" verify --print-certs "$(pwd)/$apk" 2>&1 | head -3 || true
  fi
done
echo "[build] 完成。APK:"
ls -la app/build/outputs/apk/*/app-*.apk 2>/dev/null || true
