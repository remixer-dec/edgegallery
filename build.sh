#!/usr/bin/env bash
# Builds a release APK for AI Edge Gallery.
# Run env.setup.sh first if building on a fresh machine.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_SRC="$SCRIPT_DIR/Android/src"
ANDROID_SDK="${ANDROID_HOME:-/opt/android-sdk}"

# ── Proxy detection ───────────────────────────────────────────────────────────
# If an HTTP proxy is set in the environment, inject it into gradle.properties
# so Gradle can reach Maven repositories (including Google's Maven).
# Existing systemProp.* lines are removed first to avoid duplicates.
PROPS="$ANDROID_SRC/gradle.properties"
sed -i '/^systemProp\.\(http\|https\)\.\(proxyHost\|proxyPort\|proxyUser\|proxyPassword\|nonProxyHosts\)/d' "$PROPS"

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

# ── Build ─────────────────────────────────────────────────────────────────────
export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"

cd "$ANDROID_SRC"
chmod +x gradlew
./gradlew assembleRelease --no-daemon "$@"

APK="$ANDROID_SRC/app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK" ]; then
  echo ""
  echo "Build successful: $APK ($(du -h "$APK" | cut -f1))"
fi
