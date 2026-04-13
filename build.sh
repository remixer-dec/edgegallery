#!/usr/bin/env bash
# Builds AI Edge Gallery APK.
# Run env.setup.sh first if building on a fresh machine.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_SRC="$SCRIPT_DIR/Android/src"
ANDROID_SDK="${ANDROID_HOME:-/opt/android-sdk}"
BUILD_TYPE="${1:-debug}"

# Detect architecture for aapt2 override
ARCH=$(uname -m)
if [[ "$ARCH" == "aarch64" ]]; then
  # ARM64: use lzhiyong's native aapt2 (35.0.2)
  AAPT2_PATH="$ANDROID_SDK/build-tools/35.0.2/aapt2"
  if [ -f "$AAPT2_PATH" ]; then
    echo "Using ARM64 aapt2: $AAPT2_PATH"
    "$AAPT2_PATH" version 2>/dev/null | head -1 || true
  fi
else
  # x86_64: use Google's official build-tools
  AAPT2_PATH="$ANDROID_SDK/build-tools/35.0.0/aapt2"
fi

# ── Proxy detection ───────────────────────────────────────────────────────────
PROPS="$ANDROID_SRC/gradle.properties"
# Clean existing proxy settings
sed -i '/^systemProp\.\(http\|https\)\.\(proxyHost\|proxyPort\|proxyUser\|proxyPassword\|nonProxyHosts\)/d' "$PROPS" 2>/dev/null || true

if [ -n "${https_proxy:-}" ]; then
  PROXY_PROTO="${https_proxy%%://*}"
  PROXY_REST="${https_proxy#*://}"
  PROXY_USERINFO="${PROXY_REST%%@*}"
  PROXY_HOSTPORT="${PROXY_REST##*@}"
  PROXY_HOST="${PROXY_HOSTPORT%%:*}"
  PROXY_PORT="${PROXY_HOSTPORT##*:}"
  PROXY_USER="${PROXY_USERINFO%%:*}"
  PROXY_PASS="${PROXY_USERINFO#*:}"
  
  for SCHEME in http https; do
    {
      echo "systemProp.${SCHEME}.proxyHost=${PROXY_HOST}"
      echo "systemProp.${SCHEME}.proxyPort=${PROXY_PORT}"
      echo "systemProp.${SCHEME}.proxyUser=${PROXY_USER}"
      echo "systemProp.${SCHEME}.proxyPassword=${PROXY_PASS}"
      echo "systemProp.${SCHEME}.nonProxyHosts=localhost|127.0.0.1|*.svc.cluster.local|*.local"
    } >> "$PROPS"
  done
fi

# ── Build configuration ───────────────────────────────────────────────────────
export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"
export JAVA_HOME

if [[ "$BUILD_TYPE" == "debug" ]]; then
  GRADLE_TASK="assembleDebug"
  APK_PATH="$ANDROID_SRC/app/build/outputs/apk/debug/app-debug.apk"
else
  GRADLE_TASK="assembleRelease"
  APK_PATH="$ANDROID_SRC/app/build/outputs/apk/release/app-release.apk"
fi

# ── Build ─────────────────────────────────────────────────────────────────────
cd "$ANDROID_SRC"
chmod +x gradlew

echo "Building $BUILD_TYPE APK..."
./gradlew assemble${BUILD_TYPE^} --no-daemon 2>&1

APK_SIZE=$(du -h "$APK_PATH" 2>/dev/null | cut -f1)
DEX_COUNT=$(unzip -l "$APK_PATH" 2>/dev/null | grep -c 'classes.*\.dex' || echo "0")

echo ""
echo "════════════════════════════════════════════════"
echo "Build successful: $BUILD_TYPE"
echo "════════════════════════════════════════════════"
echo "  APK:  $APK_PATH"
echo "  Size: $APK_SIZE"
echo "  DEX:  $DEX_COUNT files"
echo ""
if [[ "$BUILD_TYPE" == "debug" ]]; then
  echo "Note: Debug APKs are larger (no R8 shrinking)."
  echo "      Run './build.sh release' for optimized build."
fi
echo "════════════════════════════════════════════════"
