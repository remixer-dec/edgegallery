#!/usr/bin/env bash
# Sets up a build environment for AI Edge Gallery on a fresh Ubuntu/Debian machine.
# Run as root or with sudo. Tested on Ubuntu 24.04.
set -euo pipefail

ANDROID_SDK=/opt/android-sdk
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

# ── JDK 21 ────────────────────────────────────────────────────────────────────
if ! java -version 2>&1 | grep -q "21"; then
  apt-get update -qq
  apt-get install -y openjdk-21-jdk-headless
fi
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

# ── Android SDK command-line tools ────────────────────────────────────────────
if [ ! -f "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  apt-get install -y unzip curl
  mkdir -p "$ANDROID_SDK/cmdline-tools"
  TMP=$(mktemp)
  curl -sL "$CMDLINE_TOOLS_URL" -o "$TMP"
  unzip -q "$TMP" -d /tmp/cmdline-unpack
  mv /tmp/cmdline-unpack/cmdline-tools "$ANDROID_SDK/cmdline-tools/latest"
  rm "$TMP"
fi

export ANDROID_HOME=$ANDROID_SDK
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin

# ── SDK packages ──────────────────────────────────────────────────────────────
# sdkmanager has intermittent manifest fetch failures; fall back to direct download.
install_sdk_package() {
  local pkg="$1" zip_url="$2" dest="$3"
  if [ -d "$dest" ]; then return; fi
  echo "Installing $pkg ..."
  TMP=$(mktemp)
  curl -sL "$zip_url" -o "$TMP"
  mkdir -p "$(dirname "$dest")"
  unzip -q "$TMP" -d "$(dirname "$dest")"
  rm "$TMP"
}

install_sdk_package "platform-tools" \
  "https://dl.google.com/android/repository/platform-tools-latest-linux.zip" \
  "$ANDROID_SDK/platform-tools"

install_sdk_package "build-tools;35.0.0" \
  "https://dl.google.com/android/repository/build-tools_r35_linux.zip" \
  "$ANDROID_SDK/build-tools/35.0.0"
# The zip extracts as "android-15" on some versions; rename if needed
if [ -d "$ANDROID_SDK/build-tools/android-15" ] && [ ! -d "$ANDROID_SDK/build-tools/35.0.0" ]; then
  mv "$ANDROID_SDK/build-tools/android-15" "$ANDROID_SDK/build-tools/35.0.0"
fi

install_sdk_package "platforms;android-35" \
  "https://dl.google.com/android/repository/platform-35_r02.zip" \
  "$ANDROID_SDK/platforms/android-35"

yes | "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true

echo ""
echo "Environment ready."
echo "  ANDROID_HOME=$ANDROID_SDK"
echo "  JDK: $(java -version 2>&1 | head -1)"
