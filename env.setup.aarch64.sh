#!/usr/bin/env bash
# Sets up a build environment for AI Edge Gallery on a fresh Ubuntu/Debian machine.
# Run as root or with sudo. Tested on Ubuntu 24.04.
set -euo pipefail

ANDROID_SDK=/opt/android-sdk
JAVA_VER=21
ARCH=$(uname -m)

# ── Functions ─────────────────────────────────────────────────────────────────
install_arm64_sdk() {
  echo "Installing ARM64 native Android SDK..."

  apt-get update -qq
  apt-get install -y openjdk-${JAVA_VER}-jdk-headless unzip curl

  export JAVA_HOME=/usr/lib/jvm/java-${JAVA_VER}-openjdk-arm64
  export ANDROID_HOME=$ANDROID_SDK
  export ANDROID_SDK_ROOT=$ANDROID_SDK

  mkdir -p "$ANDROID_SDK"

  echo "Downloading ARM64 build-tools (35.0.2)..."
  local tools_url="https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip"
  local tmp_zip=$(mktemp --suffix=.zip)
  curl -sL "$tools_url" -o "$tmp_zip"
  mkdir -p /tmp/sdk-unpack
  unzip -q "$tmp_zip" -d /tmp/sdk-unpack

  mkdir -p "$ANDROID_SDK/build-tools/35.0.2"
  cp /tmp/sdk-unpack/build-tools/* "$ANDROID_SDK/build-tools/35.0.2/"

  mkdir -p "$ANDROID_SDK/platform-tools"
  cp /tmp/sdk-unpack/platform-tools/* "$ANDROID_SDK/platform-tools/"

  rm -f "$tmp_zip"
  rm -rf /tmp/sdk-unpack

  echo "  ✓ build-tools/35.0.2 installed"
  echo "  ✓ platform-tools installed"

  echo "Downloading cmdline-tools..."
  local cmdline_zip=$(mktemp --suffix=.zip)
  curl -sL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -o "$cmdline_zip"
  unzip -q -o "$cmdline_zip" -d /tmp/cmdline-temp
  mkdir -p "$ANDROID_SDK/cmdline-tools/latest"
  cp -r /tmp/cmdline-temp/cmdline-tools/* "$ANDROID_SDK/cmdline-tools/latest/"
  rm -f "$cmdline_zip"
  rm -rf /tmp/cmdline-temp

  echo "  ✓ cmdline-tools installed"

  echo "Accepting SDK licenses..."
  printf 'y\n%30s\ny\n%30s\ny\n%30s\ny\n%30s\ny\n%30s\ny\n%30s\ny\n%30s\ny\n' '' | \
    "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1

  echo "  ✓ licenses accepted"

  # Configure gradle.properties
  # 
  local gradle_props="/workspace/Android/src/gradle.properties"
  if [ -f "$gradle_props" ]; then
    #grep -v '^android\.' "$gradle_props" | grep -v '^sdk\.' > "${gradle_props}.tmp" || true
    #mv "${gradle_props}.tmp" "$gradle_props"
    echo "\n" > $gradle_props
    echo "org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8" >> "$gradle_props"
    echo "org.gradle.java.home=/usr/lib/jvm/java-21-openjdk-arm64" >> "$gradle_props"
    echo "kotlin.code.style=official" >> "$gradle_props"
    echo "org.gradle.daemon=false" >> "$gradle_props"
    echo "systemProp.http.systemPropertiesOverride=true" >> "$gradle_props"
    echo "android.aapt2FromMavenOverride=$ANDROID_SDK/build-tools/35.0.2/aapt2" >> "$gradle_props"
    echo "sdk.dir=$ANDROID_SDK" >> "$gradle_props"
    echo "android.useAndroidX=true" >> "$gradle_props"
    echo "android.nonTransitiveRClass=true" >> "$gradle_props"
  fi
}

install_x86_sdk() {
  echo "Installing x86_64 Android SDK..."

  apt-get update -qq
  apt-get install -y openjdk-${JAVA_VER}-jdk-headless unzip curl

  export JAVA_HOME=/usr/lib/jvm/java-${JAVA_VER}-openjdk-amd64
  export ANDROID_HOME=$ANDROID_SDK
  export ANDROID_SDK_ROOT=$ANDROID_SDK

  mkdir -p "$ANDROID_SDK/cmdline-tools/latest"

  local tmp_zip=$(mktemp --suffix=.zip)
  curl -sL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -o "$tmp_zip"
  unzip -q "$tmp_zip" -d /tmp/cmdline-temp
  mv /tmp/cmdline-temp/cmdline-tools "$ANDROID_SDK/cmdline-tools/latest"
  rm -f "$tmp_zip"
  rm -rf /tmp/cmdline-temp

  "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" \
    "build-tools;35.0.0" \
    "platform-tools" \
    "platforms;android-35" \
    --disable_pretty_console 2>/dev/null || true

  yes | "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true

  echo "  ✓ SDK installed"
}

# ── Main ───────────────────────────────────────────────────────────────────────
if [[ "$ARCH" == "aarch64" ]]; then
  if [ ! -f "$ANDROID_SDK/build-tools/35.0.2/aapt2" ]; then
    install_arm64_sdk
  fi
elif [[ "$ARCH" == "x86_64" ]]; then
  if [ ! -f "$ANDROID_SDK/build-tools/35.0.0/aapt2" ]; then
    install_x86_sdk
  fi
else
  echo "Unsupported architecture: $ARCH"
  exit 1
fi

echo ""
echo "════════════════════════════════════════════════"
echo "Environment ready!"
echo "════════════════════════════════════════════════"
echo "  Arch:   $ARCH"
echo "  JDK:    $(java -version 2>&1 | head -1 | sed 's/^.*"//;s/".*//')"
if [ -f "$ANDROID_HOME/build-tools/35.0.2/aapt2" ]; then
  echo "  aapt2:  $ANDROID_HOME/build-tools/35.0.2/aapt2"
  $ANDROID_HOME/build-tools/35.0.2/aapt2 version 2>&1 | head -1 | sed 's/^/  Version: /'
else
  echo "  aapt2:  $ANDROID_HOME/build-tools/35.0.0/aapt2"
  $ANDROID_HOME/build-tools/35.0.0/aapt2 version 2>&1 | head -1 | sed 's/^/  Version: /'
fi
echo ""
echo "Next: run ./build.sh to build the app"
echo "════════════════════════════════════════════════"
