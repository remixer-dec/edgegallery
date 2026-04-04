# Porting Notes

## Build environment

- OS: Ubuntu 24.04 (Linux)
- JDK: OpenJDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`)
- Android SDK: `/opt/android-sdk`
  - cmdline-tools 12.0 (downloaded from dl.google.com)
  - build-tools 35.0.0
  - platforms android-35
  - platform-tools (latest)
- Gradle: 8.10.2 (auto-downloaded by wrapper)
- Build command: `./gradlew assembleRelease --no-daemon` from `Android/src/`

Proxy note: this environment routes all traffic through an HTTP proxy. Gradle needs explicit
proxy settings in gradle.properties (systemProp.https.proxyHost/Port/User/Password) and
nonProxyHosts must NOT include *.google.com, otherwise Gradle bypasses the proxy for
dl.google.com/maven.google.com and direct connections time out.

## Changes to lower minSdk from 31 to 24

### Android/src/app/build.gradle.kts
- Changed `minSdk = 31` to `minSdk = 24`

### Android/src/app/src/main/AndroidManifest.xml
- Removed the `<uses-sdk>` element (it conflicted with the value in build.gradle.kts)

### Android/src/app/src/main/java/com/google/ai/edge/gallery/worker/AndroidManifest.xml
- Removed the `<uses-sdk>` element

### Android/src/gradle/libs.versions.toml
- `serializationPlugin`: "2.0.21" -> "2.2.0" (must match kotlin version "2.2.0")
- `kotlinReflect`: "2.2.21" -> "2.2.0" (original value was invalid; no such Kotlin release)

### Android/src/gradle.properties
- Increased JVM heap: `Xmx2048m` -> `Xmx4096m`
- Added `org.gradle.java.home` pointing to JDK 21
- Added `systemProp.http/https.proxyHost/Port/User/Password` for proxy routing
- Added `systemProp.http/https.nonProxyHosts` (localhost and cluster-local only)

## Notes on API 31+ compatibility

The app source code already guards all API 31+ calls behind `Build.VERSION.SDK_INT >= S`
checks (e.g. Build.SOC_MODEL in Consts.kt). The crashes seen in the prebuilt Play Store APK
were inside compiled Compose/AndroidX library internals, not in app code. Building from
source with a proper minSdk avoids those issues since the libraries use the same version
